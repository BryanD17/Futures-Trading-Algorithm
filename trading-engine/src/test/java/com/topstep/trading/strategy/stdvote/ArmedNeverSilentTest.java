package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.domain.AccountState;
import com.topstep.trading.domain.Candle;
import com.topstep.trading.event.EventBus;
import com.topstep.trading.event.GateDecisionEvent;
import com.topstep.trading.event.StrategySignalEvent;
import com.topstep.trading.strategy.DefaultStrategyContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * V5 Agent 05.4 - "armed but silent" can never happen: every OTE_ARMED
 * attempt that does not emit writes its reason into
 * {@code SetupContext.lastGateFailed} (self-written, cleared per attempt)
 * and publishes a GateDecisionEvent with the two numbers.
 *
 * <p>Synthetic runner paths (golden fixture, legacy target model = default):
 * SIZE deny with the two dollar numbers, then a re-plan that is funded
 * emits (the diagnostic did not poison M9); scalp NO-OVERLAP (open
 * position). The real-tape invariant lives in {@link ArmedNeverSilentTapeTest}.
 */
class ArmedNeverSilentTest {

    @BeforeEach
    void legacy() {
        PreV5BiasCompat.apply();
        System.setProperty(ScalpConfig.ENABLED_PROPERTY, "false");
        System.setProperty("stdvote.detectorTimeframe", "1");
    }

    @AfterEach
    void cleanup() {
        PreV5BiasCompat.clear();
        System.clearProperty(ScalpConfig.ENABLED_PROPERTY);
        System.clearProperty(ScalpConfig.MIN_RAID_SCORE_PROPERTY);
        System.clearProperty("stdvote.detectorTimeframe");
        StdvOteRegistry.unregister(StdvOteGoldenFixture.SYMBOL);
    }

    private static Candle flat(Candle last, int minutes) {
        return new Candle(last.getSymbol(), last.getTimestamp().plusSeconds(60L * minutes),
                last.getClose(), last.getClose() + 0.25, last.getClose() - 0.25, last.getClose(), 100);
    }

    @Test
    void sizeDenyWritesReasonAndNumbersThenAFundedRetryEmits() {
        EventBus bus = new EventBus();
        List<StrategySignalEvent> signals = new CopyOnWriteArrayList<>();
        List<GateDecisionEvent> sizeGates = new CopyOnWriteArrayList<>();
        bus.subscribe(StrategySignalEvent.class, signals::add);
        bus.subscribe(GateDecisionEvent.class, g -> { if ("SIZE".equals(g.getGate())) sizeGates.add(g); });
        bus.start();
        try {
            StdvOteRunnerStrategy s = new StdvOteRunnerStrategy(StdvOteGoldenFixture.SYMBOL, "MES", bus);
            s.initialize();
            AccountState acct = new AccountState(50_000.0);
            // Yesterday's loss leaves $5 of MLL room (the trailing floor does
            // not reset daily; today's DLL room is whole): budget = $5 while
            // one micro of the fixture's stop costs $8.
            double mll = ScalpConfig.activeRiskLimits().getMaxLossLimit();
            java.time.LocalDate day0 = java.time.LocalDate.of(2026, 6, 14);
            acct.recordRealizedPnL(-(mll - 5.0), day0);
            acct.recordRealizedPnL(0.0, day0.plusDays(1));
            DefaultStrategyContext ctx = new DefaultStrategyContext(acct);
            List<Candle> fixture = StdvOteGoldenFixture.fullFixture();
            for (Candle c : fixture) s.onCandle(c, ctx);
            assertThat(bus.awaitIdle(5_000)).isTrue();

            SetupContext sc = s.getSetupContext();
            assertThat(sc.state).as("armed, not emitted").isEqualTo(SetupState.OTE_ARMED);
            assertThat(signals).isEmpty();
            assertThat(sc.lastGateFailed).as("never silent")
                    .startsWith("SIZE: stop too wide for risk budget (need $")
                    .isEqualTo("SIZE: stop too wide for risk budget (need $8.00/micro, have $5.00)");
            assertThat(sizeGates).hasSize(1);
            GateDecisionEvent g = sizeGates.get(0);
            assertThat(g.getReason()).isEqualTo(sc.lastGateFailed);
            assertThat(g.getNumberA()).as("need $/micro").isCloseTo(8.0, within(1e-9));
            assertThat(g.getNumberB()).as("have $").isCloseTo(5.0, within(1e-9));
            System.out.println("[A-05.4] synthetic SIZE deny: " + g.getReason()
                    + " numberA=" + g.getNumberA() + " numberB=" + g.getNumberB());

            // Budget restored -> the next attempt re-plans, the diagnostic is
            // cleared per attempt (M9 not poisoned) and the setup emits.
            acct.recordRealizedPnL(mll - 5.0, day0.plusDays(1));
            s.onCandle(flat(fixture.get(fixture.size() - 1), 1), ctx);
            assertThat(bus.awaitIdle(5_000)).isTrue();
            assertThat(sc.state).isEqualTo(SetupState.IN_TRADE);
            assertThat(signals).hasSize(1);
            assertThat(signals.get(0).getQuantity()).isBetween(1, 20);
            assertThat(sc.lastGateFailed).isNull();
            assertThat(sizeGates).as("no second SIZE event").hasSize(1);
        } finally {
            bus.stop();
        }
    }

    @Test
    void scalpNoOverlapWritesPositionReason() {
        System.setProperty(ScalpConfig.ENABLED_PROPERTY, "true");
        System.setProperty(ScalpConfig.MIN_RAID_SCORE_PROPERTY, "5");   // the fixture's raid scores 5
        EventBus bus = new EventBus();
        List<StrategySignalEvent> signals = new CopyOnWriteArrayList<>();
        List<GateDecisionEvent> posGates = new CopyOnWriteArrayList<>();
        bus.subscribe(StrategySignalEvent.class, signals::add);
        bus.subscribe(GateDecisionEvent.class, g -> { if ("POSITION".equals(g.getGate())) posGates.add(g); });
        bus.start();
        try {
            StdvOteRunnerStrategy s = new StdvOteRunnerStrategy(StdvOteGoldenFixture.SYMBOL, "MES", bus);
            s.initialize();
            AccountState acct = new AccountState(50_000.0);
            acct.updatePosition("MNQ", 1, 21000.0);   // an open MNQ position from elsewhere
            DefaultStrategyContext ctx = new DefaultStrategyContext(acct);
            for (Candle c : StdvOteGoldenFixture.fullFixture()) s.onCandle(c, ctx);
            assertThat(bus.awaitIdle(5_000)).isTrue();
            SetupContext sc = s.getSetupContext();
            System.out.println("[A-05.4] scalp overlap: state=" + sc.state + " lastGateFailed=" + sc.lastGateFailed);
            assertThat(sc.state).isEqualTo(SetupState.OTE_ARMED);
            assertThat(sc.lastGateFailed).isEqualTo("POSITION: MNQ position still open (latch=false, account=true)");
            assertThat(posGates).hasSize(1);
            assertThat(posGates.get(0).getReason()).isEqualTo(sc.lastGateFailed);
            assertThat(posGates.get(0).getNumberA()).isEqualTo(0.0);
            assertThat(posGates.get(0).getNumberB()).isEqualTo(1.0);
            assertThat(signals).as("never emits over an open position").isEmpty();
        } finally {
            bus.stop();
        }
    }

    @Test
    void sizerFundsOneMicroWheneverTheBudgetCoversIt() {
        // MNQ $0.50/tick: 124.75 pts = $249.50/micro -> 1; 125 pts = $250.00 -> 1; 125.25 pts -> deny.
        StdvOteSizer.RiskSize a = StdvOteSizer.riskDerived(250, 30000, 30000 - 124.75, 0.25, 0.5, 1, 20);
        StdvOteSizer.RiskSize b = StdvOteSizer.riskDerived(250, 30000, 30000 - 125.00, 0.25, 0.5, 1, 20);
        StdvOteSizer.RiskSize c = StdvOteSizer.riskDerived(250, 30000, 30000 - 125.25, 0.25, 0.5, 1, 20);
        assertThat(a.contracts()).isEqualTo(1);
        assertThat(b.contracts()).isEqualTo(1);
        assertThat(c.denied()).isTrue();
        assertThat(c.reason()).isEqualTo("SIZE: stop too wide for risk budget (need $250.50/micro, have $250.00)");
        assertThat(c.needDollars()).isEqualTo(250.5);
        assertThat(c.haveDollars()).isEqualTo(250.0);
        // 27 pts ($54/micro) at $250 -> 4 micros; never above the cap.
        assertThat(StdvOteSizer.riskDerived(250, 30773.5, 30746.5, 0.25, 0.5, 1, 20).contracts()).isEqualTo(4);
        assertThat(StdvOteSizer.riskDerived(250, 30000, 29998, 0.25, 0.5, 1, 20).contracts()).isEqualTo(20);
    }
}
