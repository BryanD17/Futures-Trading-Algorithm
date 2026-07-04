package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.domain.AccountState;
import com.topstep.trading.domain.Candle;
import com.topstep.trading.domain.OrderSide;
import com.topstep.trading.event.EventBus;
import com.topstep.trading.event.StrategySignalEvent;
import com.topstep.trading.event.StrategySignalEvent.SignalType;
import com.topstep.trading.strategy.DefaultStrategyContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * SA3 end-to-end scalp-mode test: the SAME fixture that produces the legacy
 * golden emission (entry 21023 / stop 21011 / target 21058 / RR 2.92) must,
 * with {@code -DscalpMode.enabled=true}, produce the SAME entry/stop but a
 * 1R-capped scalp target — and the real RR must ride on the signal.
 *
 * <p>Geometry: risk = 21023 − 21011 = 12.0 → 1R cap = 21035.0. Whatever the
 * level pipeline offers as Candidate A, the emitted target can never exceed
 * 21035.0 and never sit closer than 2 ticks to entry; the validator band
 * [0.8, 1.5] (from topstep50kScalp) must accept the emission that the legacy
 * 2.0 floor would have rejected.
 */
@DisplayName("StdvOteScalpModeIntegrationTest (scalp branch end-to-end)")
class StdvOteScalpModeIntegrationTest {

    private static final double TICK = 0.25;
    private static final double EXPECTED_ENTRY = 21023.0;
    private static final double EXPECTED_STOP = 21011.0;
    private static final double RISK = EXPECTED_ENTRY - EXPECTED_STOP; // 12.0
    private static final double ONE_R_TARGET = EXPECTED_ENTRY + RISK;  // 21035.0

    @BeforeEach
    void enableScalpMode() {
        System.setProperty(ScalpConfig.ENABLED_PROPERTY, "true");
    }

    @AfterEach
    void cleanup() {
        System.clearProperty(ScalpConfig.ENABLED_PROPERTY);
        StdvOteRegistry.unregister(StdvOteGoldenFixture.SYMBOL);
    }

    private static final class CapturingEventBus extends EventBus {
        final List<StrategySignalEvent> signals = new ArrayList<>();

        @Override
        public void publish(com.topstep.trading.event.Event event) {
            if (event instanceof StrategySignalEvent sig) {
                signals.add(sig);
            }
        }
    }

    @Test
    @DisplayName("scalp mode emits a 1R-capped single-TP signal from the golden fixture")
    void scalpModeEmitsCappedTarget() {
        CapturingEventBus bus = new CapturingEventBus();
        StdvOteRunnerStrategy s = new StdvOteRunnerStrategy(
                StdvOteGoldenFixture.SYMBOL, "MES", bus);
        s.initialize();
        DefaultStrategyContext ctx = new DefaultStrategyContext(new AccountState(50_000.0));
        for (Candle c : StdvOteGoldenFixture.fullFixture()) {
            s.onCandle(c, ctx);
        }

        SetupContext sc = s.getSetupContext();
        assertThat(sc.state).isEqualTo(SetupState.IN_TRADE);
        assertThat(bus.signals).hasSize(1);
        StrategySignalEvent evt = bus.signals.get(0);

        // Entry/stop geometry identical to legacy — scalp changes ONLY the target.
        assertThat(evt.getSignalType()).isEqualTo(SignalType.LONG_ENTRY);
        assertThat(evt.getSide()).isEqualTo(OrderSide.BUY);
        assertThat(evt.getEntryPrice()).isEqualTo(EXPECTED_ENTRY);
        assertThat(evt.getStopPrice()).isEqualTo(EXPECTED_STOP);

        // Target: hard 1R cap, at least 2 ticks past entry, on the tick grid.
        assertThat(evt.getTargetPrice())
                .isLessThanOrEqualTo(ONE_R_TARGET)
                .isGreaterThanOrEqualTo(EXPECTED_ENTRY + 2 * TICK);
        assertThat(Math.abs(evt.getTargetPrice() / TICK
                - Math.round(evt.getTargetPrice() / TICK))).isLessThan(1e-6);

        // RR is in the scalp validator band [0.8, 1.5] — this emission would
        // have been KILLED by the legacy 2.0 floor, proving the band rewire.
        double rr = evt.getActualRR();
        assertThat(rr).isBetween(0.8, 1.5);
        assertThat(sc.rr).isCloseTo(rr, within(1e-9));

        // Blocker #9 fix: the signal carries the REAL RR, not the tier's
        // fictional default, and exactly ONE take-profit level (100% at the
        // real R-multiple).
        assertThat(evt.getRiskRewardRatio()).isCloseTo(rr, within(1e-9));
        assertThat(evt.getPartialProfitTargets()).hasDimensions(1, 2);
        assertThat(evt.getPartialProfitTargets()[0][0]).isCloseTo(rr, within(1e-9));
        assertThat(evt.getPartialProfitTargets()[0][1]).isEqualTo(1.0);
        assertThat(evt.getReason()).startsWith("STDV_OTE_SCALP:");
    }

    @Test
    @DisplayName("fixture emits exactly the 1R fallback (no valid opposing level within 1.5R)")
    void fixtureFallsBackToExactlyOneR() {
        // On this fixture the FVG origin (top 21023) equals entry (excluded:
        // < 2 ticks) and the level pipeline has no unraided opposing level
        // within 1.5R of entry, so the deterministic result is exactly 1R.
        CapturingEventBus bus = new CapturingEventBus();
        StdvOteRunnerStrategy s = new StdvOteRunnerStrategy(
                StdvOteGoldenFixture.SYMBOL, "MES", bus);
        s.initialize();
        DefaultStrategyContext ctx = new DefaultStrategyContext(new AccountState(50_000.0));
        for (Candle c : StdvOteGoldenFixture.fullFixture()) {
            s.onCandle(c, ctx);
        }

        assertThat(bus.signals).hasSize(1);
        StrategySignalEvent evt = bus.signals.get(0);
        assertThat(evt.getTargetPrice()).isEqualTo(ONE_R_TARGET); // 21035.0
        assertThat(evt.getActualRR()).isCloseTo(1.0, within(1e-9));
        assertThat(evt.getReason()).contains("ONE_R_FALLBACK");
    }
}
