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
 * <p>The exact emission values below were captured by running the
 * {@code StdvOteWiringIntegrationTest} fixture at commit {@code 36f07c2}
 * (current HEAD, BEFORE any SA3 change was made):
 *
 * <pre>
 *   signalType = LONG_ENTRY, side = BUY, tier = TIER_1, quantity = 6
 *   entry  = 21023.0
 *   stop   = 21011.0
 *   target = 21058.0        (the −2σ STDV projection off leg [21012, 21035])
 *   rr     = 2.9166666666666665   (= 35/12, both ctx.rr and getActualRR())
 *   signal riskRewardRatio = 2.0  (tier default — legacy signal constructor)
 *   signal partials = [[1.0, 0.5], [2.0, 0.5]]  (TIER_1 ladder)
 * </pre>
 *
 * With {@code scalpMode.enabled=false} (or absent — the default) every one
 * of those numbers must still be produced EXACTLY after the SA3 changes.
 */
@DisplayName("StdvOteLegacyGoldenTest (legacy emission byte-for-byte after SA3)")
class StdvOteLegacyGoldenTest {

    // Golden values captured at HEAD (36f07c2) before the SA3 change.
    private static final double GOLDEN_ENTRY = 21023.0;
    private static final double GOLDEN_STOP = 21011.0;
    private static final double GOLDEN_TARGET = 21058.0;
    private static final double GOLDEN_RR = 2.9166666666666665;
    private static final int GOLDEN_QUANTITY = 6;

    @BeforeEach
    void forceLegacyMode() {
        // Explicit OFF (also covers the absent-property default elsewhere).
        System.setProperty(ScalpConfig.ENABLED_PROPERTY, "false");
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

        // RR at the −2σ target: 35/12.
        assertThat(evt.getActualRR()).isCloseTo(GOLDEN_RR, within(1e-12));

        // Legacy signal metadata unchanged: tier-default RR and TIER_1 ladder.
        assertThat(evt.getRiskRewardRatio()).isEqualTo(2.0);
        assertThat(evt.getPartialProfitTargets()).isDeepEqualTo(
                new double[][] {{1.0, 0.5}, {2.0, 0.5}});
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
