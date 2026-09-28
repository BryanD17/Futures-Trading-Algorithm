package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.domain.AccountState;
import com.topstep.trading.domain.Candle;
import com.topstep.trading.domain.OrderSide;
import com.topstep.trading.event.EventBus;
import com.topstep.trading.event.StrategySignalEvent;
import com.topstep.trading.event.StrategySignalEvent.SignalType;
import com.topstep.trading.strategy.DefaultStrategyContext;
import com.topstep.trading.strategy.TradeTier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * SA3 GOLDEN-FILE regression test — the proof that legacy mode is untouched.
 *
 * <p>RE-CAPTURED by V5 Agent 04 (chart-parity OTE, one RR band). The SA3
 * values (stop 21011 / target 21058 −2σ / rr 2.92 / tier-default ladder) were
 * the pre-V5 geometry; V5 intentionally changes legacy planning:
 *
 * <pre>
 *   signalType = LONG_ENTRY, side = BUY, tier = TIER_1, quantity = 6
 *   zone   = dealing range [21012, 21052] → 0.618 21027.25 / 0.786 21020.50
 *   entry  = 21023.0        (displacement FVG top, unchanged)
 *   stop   = 21019.0        (FVG far edge 21020 − 4 ticks; beyond the 0.786)
 *   T1     = 21032.0 (0.5)  RR(T1) = 2.25
 *   target = 21036.75       (T2 = 0.382 — furthest rung within 5.0R; T3 21052 = 7.25R)
 *   rr     = 3.4375         (= 13.75 / 4, both ctx.rr and getActualRR())
 *   signal riskRewardRatio = 3.4375 (the REAL RR)
 *   signal partials = [[2.25, 0.5], [3.4375, 0.5]]  (T1 half, T2 half)
 * </pre>
 *
 * With {@code scalpMode.enabled=false} (or absent — the default) every one
 * of those numbers must be produced EXACTLY.
 */
@DisplayName("StdvOteLegacyGoldenTest (legacy emission byte-for-byte after SA3)")
class StdvOteLegacyGoldenTest {

    // Golden values captured at HEAD (36f07c2) before the SA3 change.
    private static final double GOLDEN_ENTRY = 21023.0;
    private static final double GOLDEN_STOP = 21019.0;
    private static final double GOLDEN_TARGET = 21036.75;
    private static final double GOLDEN_RR = 3.4375;
    // V5 RC-14 (AGENT-05): quantity is RISK-DERIVED — floor($250 / $per-micro),
    // capped at topstep50k().maxContracts = 5 (the pre-V5 tier table sent 6,
    // which the risk engine then silently re-sized). Prices/RR are Agent 04's.
    private static final int GOLDEN_QUANTITY = 5;

    @BeforeEach
    void forceLegacyMode() {
        PreV5BiasCompat.apply(); // V5 Agent 03: pre-V5 bias/sweep inputs
        // Explicit OFF (also covers the absent-property default elsewhere).
        System.setProperty(ScalpConfig.ENABLED_PROPERTY, "false");
        // Golden fixture encodes 1m entry anatomy — pin the detector
        // timeframe (LIVE default is 5m, field fix 2026-07-09).
        System.setProperty("stdvote.detectorTimeframe", "1");
    }

    @AfterEach
    void cleanup() {
        PreV5BiasCompat.clear();
        System.clearProperty(ScalpConfig.ENABLED_PROPERTY);
        System.clearProperty("stdvote.detectorTimeframe");
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

    private CapturingEventBus runFixture() {
        CapturingEventBus bus = new CapturingEventBus();
        StdvOteRunnerStrategy s = new StdvOteRunnerStrategy(
                StdvOteGoldenFixture.SYMBOL, "MES", bus);
        s.initialize();
        DefaultStrategyContext ctx = new DefaultStrategyContext(new AccountState(50_000.0));
        for (Candle c : StdvOteGoldenFixture.fullFixture()) {
            s.onCandle(c, ctx);
        }
        return bus;
    }

    @Test
    @DisplayName("legacy mode emits EXACTLY the pre-SA3 golden values")
    void legacyEmissionMatchesGoldenValues() {
        CapturingEventBus bus = runFixture();
        assertThat(bus.signals).hasSize(1);
        StrategySignalEvent evt = bus.signals.get(0);

        assertThat(evt.getSignalType()).isEqualTo(SignalType.LONG_ENTRY);
        assertThat(evt.getSide()).isEqualTo(OrderSide.BUY);
        assertThat(evt.getTier()).isEqualTo(TradeTier.TIER_1);
        assertThat(evt.getQuantity()).isEqualTo(GOLDEN_QUANTITY);

        // Exact price geometry — no tolerance.
        assertThat(evt.getEntryPrice()).isEqualTo(GOLDEN_ENTRY);
        assertThat(evt.getStopPrice()).isEqualTo(GOLDEN_STOP);
        assertThat(evt.getTargetPrice()).isEqualTo(GOLDEN_TARGET);

        // RR at the T2 target: 13.75/4.
        assertThat(evt.getActualRR()).isCloseTo(GOLDEN_RR, within(1e-12));

        // V5: the signal carries the REAL RR and the real T1 / final ladder.
        assertThat(evt.getRiskRewardRatio()).isCloseTo(GOLDEN_RR, within(1e-12));
        assertThat(evt.getPartialProfitTargets()).isDeepEqualTo(
                new double[][] {{2.25, 0.5}, {3.4375, 0.5}});
        assertThat(evt.getReason()).startsWith("STDV_OTE:");
    }

    @Test
    @DisplayName("legacy setup context carries the golden rr and prices")
    void legacyContextMatchesGoldenValues() {
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
        assertThat(sc.entry).isEqualTo(GOLDEN_ENTRY);
        assertThat(sc.stop).isEqualTo(GOLDEN_STOP);
        assertThat(sc.rr).isCloseTo(GOLDEN_RR, within(1e-12));
        assertThat(sc.legLow).isEqualTo(21012.0);
        assertThat(sc.legHigh).isEqualTo(21035.0);
        assertThat(sc.sizeFilled).isEqualTo(GOLDEN_QUANTITY);
    }
}
