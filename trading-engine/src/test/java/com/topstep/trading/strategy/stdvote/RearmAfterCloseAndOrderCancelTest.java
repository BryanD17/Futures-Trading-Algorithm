package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.domain.AccountState;
import com.topstep.trading.domain.Candle;
import com.topstep.trading.domain.Order;
import com.topstep.trading.domain.OrderSide;
import com.topstep.trading.domain.OrderType;
import com.topstep.trading.event.EventBus;
import com.topstep.trading.event.GateDecisionEvent;
import com.topstep.trading.event.PositionClosedEvent;
import com.topstep.trading.event.SetupCancelledEvent;
import com.topstep.trading.event.StrategySignalEvent;
import com.topstep.trading.execution.ExecutionEngine;
import com.topstep.trading.strategy.DefaultStrategyContext;
import com.topstep.trading.strategy.session.SessionConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V5 Agent 05.3 — items 1 and 2 on the strategy (real runner path, legacy
 * target model = the default):
 * <ol>
 *   <li>a PositionClosedEvent for an EXECUTED legacy trade re-arms IN_TRADE
 *       after {@code setup.rearmCooldownBars} (09-28: the 12:32 short closed
 *       at 13:08 but the machine sat IN_TRADE until 15:38 and missed G1);</li>
 *   <li>{@code setup.rearmAfterClose=false} keeps IN_TRADE terminal (A/B);</li>
 *   <li>the setup that emitted an unfilled entry ENDS (invalidated/expired)
 *       -> SetupCancelledEvent -> the SIM entry is cancelled with a
 *       GateDecisionEvent "ORDER: cancelled — setup &lt;reason&gt;" (09-24 14:03
 *       LONG rested until the TTL).</li>
 * </ol>
 */
class RearmAfterCloseAndOrderCancelTest {

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
        System.clearProperty("stdvote.detectorTimeframe");
        System.clearProperty(SessionConfig.REARM_AFTER_CLOSE);
        StdvOteRegistry.unregister(StdvOteGoldenFixture.SYMBOL);
    }

    private static Candle after(Candle last, int minutes) {
        return new Candle(last.getSymbol(), last.getTimestamp().plusSeconds(60L * minutes),
                last.getClose(), last.getClose() + 0.5, last.getClose() - 0.5, last.getClose(), 100);
    }

    private static Candle at(Candle last, int minutes, double price) {
        return new Candle(last.getSymbol(), last.getTimestamp().plusSeconds(60L * minutes),
                price, price + 0.5, price - 0.5, price, 100);
    }

    /** Drives the golden fixture to IN_TRADE, fills the entry, closes it (a REAL trade). */
    private static List<SetupState> executedTradeThenBars(EventBus bus, StdvOteRunnerStrategy s,
                                                          int barsAfterClose) {
        AccountState acct = new AccountState(50_000.0);
        DefaultStrategyContext ctx = new DefaultStrategyContext(acct);
        List<Candle> fixture = StdvOteGoldenFixture.fullFixture();
        for (Candle c : fixture) s.onCandle(c, ctx);
        assertThat(bus.awaitIdle(5_000)).isTrue();
        assertThat(s.getSetupContext().state).isEqualTo(SetupState.IN_TRADE);
        Candle last = fixture.get(fixture.size() - 1);
        acct.updatePosition("MNQ", 5, 21023.0);            // filled
        s.onCandle(after(last, 1), ctx);
        acct.updatePosition("MNQ", -5, 21011.0);           // closed at the stop
        acct.recordTradeCompleted(-120.0);
        bus.publish(new PositionClosedEvent("MNQ", -120.0, false, last.getTimestamp()));
        assertThat(bus.awaitIdle(5_000)).isTrue();
        List<SetupState> states = new java.util.ArrayList<>();
        for (int k = 0; k < barsAfterClose; k++) {
            s.onCandle(after(last, 2 + k), ctx);
            assertThat(bus.awaitIdle(5_000)).isTrue();
            states.add(s.getSetupContext().state);
        }
        return states;
    }

    @Test
    void legacyTradeReArmsWithinTheCooldownAfterThePositionCloses() {
        EventBus bus = new EventBus();
        bus.start();
        try {
            StdvOteRunnerStrategy s = new StdvOteRunnerStrategy(StdvOteGoldenFixture.SYMBOL, "MES", bus);
            s.initialize();
            int cooldown = SessionConfig.rearmCooldownBars();
            List<SetupState> states = executedTradeThenBars(bus, s, cooldown + 4);
            int firstOut = states.indexOf(states.stream().filter(st -> st != SetupState.IN_TRADE)
                    .findFirst().orElse(SetupState.IN_TRADE));
            assertThat(states.get(0)).as("detection bar: cooldown starts, still IN_TRADE").isEqualTo(SetupState.IN_TRADE);
            assertThat(firstOut).as("re-armed out of IN_TRADE: " + states).isBetween(1, cooldown + 1);
            assertThat(states.get(firstOut)).as("re-arm, not a death").isNotEqualTo(SetupState.INVALIDATED);
            assertThat(states.subList(firstOut, states.size())).doesNotContain(SetupState.IN_TRADE);
        } finally {
            bus.stop();
        }
    }

    @Test
    void rearmAfterCloseFalseKeepsInTradeTerminal() {
        System.setProperty(SessionConfig.REARM_AFTER_CLOSE, "false");
        EventBus bus = new EventBus();
        bus.start();
        try {
            StdvOteRunnerStrategy s = new StdvOteRunnerStrategy(StdvOteGoldenFixture.SYMBOL, "MES", bus);
            s.initialize();
            List<SetupState> states = executedTradeThenBars(bus, s, SessionConfig.rearmCooldownBars() + 15);
            assertThat(states).as("one trade per window (A/B)").containsOnly(SetupState.IN_TRADE);
        } finally {
            bus.stop();
        }
    }

    @Test
    void unfilledEntryIsCancelledWhenItsSetupEnds() {
        EventBus bus = new EventBus();
        List<StrategySignalEvent> signals = new CopyOnWriteArrayList<>();
        List<SetupCancelledEvent> cancels = new CopyOnWriteArrayList<>();
        List<GateDecisionEvent> orderGates = new CopyOnWriteArrayList<>();
        List<PositionClosedEvent> closes = new CopyOnWriteArrayList<>();
        AccountState acct = new AccountState(50_000.0);
        ExecutionEngine exec = new ExecutionEngine(acct);
        exec.setEventBus(bus);
        exec.setOrderTtlBars(0); // prove the cancel is the SETUP's, not the TTL backstop
        bus.subscribe(StrategySignalEvent.class, signals::add);
        bus.subscribe(SetupCancelledEvent.class, cancels::add);
        bus.subscribe(PositionClosedEvent.class, closes::add);
        bus.subscribe(GateDecisionEvent.class, g -> { if ("ORDER".equals(g.getGate())) orderGates.add(g); });
        bus.start();
        try {
            StdvOteRunnerStrategy s = new StdvOteRunnerStrategy(StdvOteGoldenFixture.SYMBOL, "MES", bus);
            s.initialize();
            DefaultStrategyContext ctx = new DefaultStrategyContext(acct);
            List<Candle> fixture = StdvOteGoldenFixture.fullFixture();
            for (Candle c : fixture) s.onCandle(c, ctx);
            assertThat(bus.awaitIdle(5_000)).isTrue();
            assertThat(s.getSetupContext().state).isEqualTo(SetupState.IN_TRADE);
            assertThat(signals).hasSize(1);
            StrategySignalEvent sig = signals.get(0);
            boolean isLong = sig.getSide() == OrderSide.BUY;
            exec.submitOrder(new Order("MNQ", sig.getSide(), OrderType.LIMIT, 1, sig.getEntryPrice()),
                    sig.getStopPrice(), sig.getTargetPrice());
            assertThat(exec.getActiveOrdersList("MNQ")).hasSize(1);

            // Price walks AWAY from the limit (never fills) until the setup ends.
            Candle last = fixture.get(fixture.size() - 1);
            double away = sig.getEntryPrice() + (isLong ? 40.0 : -40.0);
            int k = 1;
            while (s.getSetupContext().state == SetupState.IN_TRADE && k < 400) {
                Candle c = at(last, k++, away);
                exec.onNewCandle(c);
                s.onCandle(c, ctx);
                assertThat(bus.awaitIdle(5_000)).isTrue();
            }
            assertThat(s.getSetupContext().state).as("the setup ended").isNotEqualTo(SetupState.IN_TRADE);
            assertThat(cancels).hasSize(1);
            assertThat(exec.getActiveOrdersList("MNQ")).as("entry cancelled with its setup").isEmpty();
            assertThat(orderGates).hasSize(1);
            assertThat(orderGates.get(0).getReason()).startsWith("ORDER: cancelled — setup ");
            assertThat(orderGates.get(0).getReason()).contains(cancels.get(0).getReason());
            assertThat(closes).as("no synthetic PositionClosedEvent for a setup cancel").isEmpty();
            assertThat(acct.hasPosition("MNQ")).isFalse();
        } finally {
            bus.stop();
        }
    }
}
