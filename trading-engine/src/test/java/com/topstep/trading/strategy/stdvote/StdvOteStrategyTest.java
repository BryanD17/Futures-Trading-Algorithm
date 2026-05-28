package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.chartstate.ChartStateQueryAPI;
import com.topstep.trading.domain.Candle;
import com.topstep.trading.domain.OrderSide;
import com.topstep.trading.event.EventBus;
import com.topstep.trading.event.StrategySignalEvent;
import com.topstep.trading.event.StrategySignalEvent.SignalType;
import com.topstep.trading.strategy.DisplacementDetector;
import com.topstep.trading.strategy.FairValueGap;
import com.topstep.trading.strategy.ImpulseExtensionAnalyzer;
import com.topstep.trading.strategy.LiquiditySweep;
import com.topstep.trading.strategy.MarketBias;
import com.topstep.trading.strategy.MultiTimeframeAnalyzer;
import com.topstep.trading.strategy.TradeTier;
import com.topstep.trading.validation.MandatoryConfluenceValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * SA4 tests for {@link StdvOteStrategy} state machine.
 *
 * <p>The strategy is driven through its package-private {@code record*}
 * hooks (which SA5 will later call from {@link StdvOteStrategy#onCandle}
 * after polling detectors). The tests verify:
 *
 * <ul>
 *   <li>The happy MNQ-bullish sequence
 *       {@code IDLE → BIAS_SET → MANIP_DONE → SWEEP_DONE → DISPLACED →
 *       MSS_CONFIRMED → OTE_ARMED → IN_TRADE} produces exactly one
 *       {@link StrategySignalEvent} with the correct entry, stop, target,
 *       tier, and size.</li>
 *   <li>Each individual gate failure does NOT advance state past the gate
 *       and does NOT emit.</li>
 *   <li>The HTF bias flip mid-setup invalidates the in-flight context.</li>
 *   <li>A counter-bias sweep is ignored.</li>
 * </ul>
 */
@DisplayName("StdvOteStrategy state machine")
class StdvOteStrategyTest {

    private StdvProjectionEngine projectionEngine;
    private OteEntryCalculator oteCalculator;
    private MandatoryConfluenceValidator validator;
    private TestEventBus bus;

    @BeforeEach
    void setUp() {
        projectionEngine = new StdvProjectionEngine(
                /* chartState */ null, new ImpulseExtensionAnalyzer("MNQ", 30));
        oteCalculator = new OteEntryCalculator();
        validator = new MandatoryConfluenceValidator(
                mock(MultiTimeframeAnalyzer.class),
                mock(DisplacementDetector.class),
                mock(ChartStateQueryAPI.class));
        bus = new TestEventBus();
    }

    private StdvOteStrategy newStrategy(String symbol) {
        return new StdvOteStrategy(symbol, projectionEngine, oteCalculator,
                validator, bus, /* expiryBars */ 40L);
    }

    /** Drive the strategy through the happy bullish MNQ sequence. */
    private StdvOteStrategy driveHappyPath() {
        StdvOteStrategy s = newStrategy("MNQ");
        SetupContext ctx = s.getSetupContext();
        ctx.killzoneOpen = true; // M3 (set by the engine clock in production)

        s.recordHtfBias(MarketBias.BULLISH);
        assertThat(ctx.state).isEqualTo(SetupState.BIAS_SET);

        // manipulation leg 19960 → 20000 (range 40)
        s.recordManipulationLeg(19960.0, 20000.0, 0.25, 0);
        assertThat(ctx.state).isEqualTo(SetupState.MANIP_DONE);
        assertThat(ctx.projections).hasSize(5);

        // sweep of lows at 19952 with raid score 7
        LiquiditySweep sweep = new LiquiditySweep(true, 19952.0, Instant.now(), true);
        s.recordSweep(sweep, 7);
        assertThat(ctx.state).isEqualTo(SetupState.SWEEP_DONE);

        // bullish displacement candle + bullish FVG that overlaps the upcoming OTE band
        // For an impulse 19952 → 19984 (range 32), bullish OTE band ~[19961.5, 19958.75]
        // FVG with bottom 19960, top 19962 — bottom inside the band.
        FairValueGap fvg = new FairValueGap(true, 19962.0, 19960.0, Instant.now());
        s.recordDisplacement(fvg);
        assertThat(ctx.state).isEqualTo(SetupState.DISPLACED);

        s.recordMss();
        assertThat(ctx.state).isEqualTo(SetupState.MSS_CONFIRMED);

        // OTE impulse 19952 → 19984
        s.recordOteImpulse(19952.0, 19984.0, 0.25, /* reactionConfirmed */ true);
        assertThat(ctx.state).isEqualTo(SetupState.OTE_ARMED);

        // emit
        boolean emitted = s.tryEmit(0.25, /* stopBufferTicks */ 4,
                TradeTier.TIER_3, /* sizeRequest */ 12);
        assertThat(emitted).isTrue();
        assertThat(ctx.state).isEqualTo(SetupState.IN_TRADE);

        return s;
    }

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("bullish MNQ: IDLE → IN_TRADE emits exactly one LONG_ENTRY signal")
        void bullishMnqEmitsOnce() {
            StdvOteStrategy s = driveHappyPath();
            assertThat(bus.events).hasSize(1);
            StrategySignalEvent evt = bus.events.get(0);
            assertThat(evt.getSignalType()).isEqualTo(SignalType.LONG_ENTRY);
            assertThat(evt.getSymbol()).isEqualTo("MNQ");
            assertThat(evt.getSide()).isEqualTo(OrderSide.BUY);
            assertThat(evt.getQuantity()).isEqualTo(12);
            assertThat(evt.getTier()).isEqualTo(TradeTier.TIER_3);
            // The validator passed, so RR was already >= 2.0 (we don't
            // re-derive here — the validator gate test covers the floor).
            assertThat(evt.getActualRR()).isGreaterThanOrEqualTo(2.0);
        }

        @Test
        @DisplayName("stop sits beyond the OTE 1.0 by the buffer ticks")
        void stopBeyondOne00() {
            StdvOteStrategy s = driveHappyPath();
            StrategySignalEvent evt = bus.events.get(0);
            // bullish; one00 = swing low 19952.00, buffer 4 ticks * 0.25 = 1.00
            assertThat(evt.getStopPrice()).isEqualTo(19951.00);
        }

        @Test
        @DisplayName("target price equals the -2.0 STDV projection")
        void targetIsStdvMinus2() {
            StdvOteStrategy s = driveHappyPath();
            StrategySignalEvent evt = bus.events.get(0);
            // Manipulation leg 19960 → 20000 (range 40), bullish
            // -2.0 raw = 19960 + 2*40 = 20040.00. No snapping (null chart).
            assertThat(evt.getTargetPrice()).isEqualTo(20040.00);
        }

        @Test
        @DisplayName("entry equals the PD-array edge inside the OTE band")
        void entryIsPdEdge() {
            StdvOteStrategy s = driveHappyPath();
            StrategySignalEvent evt = bus.events.get(0);
            // OTE on impulse 19952 → 19984 (range 32), bullish:
            //   f62  = 19984 - 0.62*32  = 19964.16  → round 0.25 → 19964.25
            //   f705 = 19984 - 0.705*32 = 19961.44  → round 0.25 → 19961.50
            //   f79  = 19984 - 0.79*32  = 19958.72  → round 0.25 → 19958.75
            // OTE band is [19958.75, 19964.25] (between f79 and f62, not just f705).
            // FVG top 19962 is inside the band → preferred edge for a bullish setup.
            // Entry = 19962.00, rounded to the 0.25 grid unchanged.
            assertThat(evt.getEntryPrice()).isEqualTo(19962.00);
        }
    }

    @Nested
    @DisplayName("gate failures: state does not advance, no emit")
    class GateFailures {

        @Test
        @DisplayName("NEUTRAL bias keeps state IDLE")
        void neutralBiasStaysIdle() {
            StdvOteStrategy s = newStrategy("MNQ");
            s.recordHtfBias(MarketBias.NEUTRAL);
            assertThat(s.getSetupContext().state).isEqualTo(SetupState.IDLE);
        }

        @Test
        @DisplayName("sweep before bias is ignored")
        void sweepBeforeBiasIgnored() {
            StdvOteStrategy s = newStrategy("MNQ");
            s.recordSweep(new LiquiditySweep(true, 19952.0, Instant.now(), false), 7);
            assertThat(s.getSetupContext().state).isEqualTo(SetupState.IDLE);
        }

        @Test
        @DisplayName("counter-bias sweep is ignored")
        void counterBiasSweepIgnored() {
            StdvOteStrategy s = newStrategy("MNQ");
            SetupContext ctx = s.getSetupContext();
            ctx.killzoneOpen = true;
            s.recordHtfBias(MarketBias.BULLISH);
            s.recordManipulationLeg(19960, 20000, 0.25, 0);
            // sweep of HIGHS (bullish=false in LiquiditySweep semantics) while bias is BULLISH
            s.recordSweep(new LiquiditySweep(false, 20010.0, Instant.now(), false), 9);
            assertThat(ctx.state)
                    .as("counter-bias sweep must NOT advance the state machine")
                    .isEqualTo(SetupState.MANIP_DONE);
            assertThat(ctx.sweep).isNull();
        }

        @Test
        @DisplayName("displacement before sweep is ignored")
        void displacementBeforeSweepIgnored() {
            StdvOteStrategy s = newStrategy("MNQ");
            SetupContext ctx = s.getSetupContext();
            ctx.killzoneOpen = true;
            s.recordHtfBias(MarketBias.BULLISH);
            s.recordManipulationLeg(19960, 20000, 0.25, 0);
            s.recordDisplacement(new FairValueGap(true, 19962, 19960, Instant.now()));
            assertThat(ctx.state).isEqualTo(SetupState.MANIP_DONE);
            assertThat(ctx.fvg).isNull();
        }

        @Test
        @DisplayName("missing reaction at OTE keeps state at MSS_CONFIRMED")
        void noReactionStaysAtMss() {
            StdvOteStrategy s = newStrategy("MNQ");
            SetupContext ctx = s.getSetupContext();
            ctx.killzoneOpen = true;
            s.recordHtfBias(MarketBias.BULLISH);
            s.recordManipulationLeg(19960, 20000, 0.25, 0);
            s.recordSweep(new LiquiditySweep(true, 19952, Instant.now(), true), 7);
            s.recordDisplacement(new FairValueGap(true, 19962, 19960, Instant.now()));
            s.recordMss();
            // reaction NOT confirmed
            s.recordOteImpulse(19952, 19984, 0.25, false);
            assertThat(ctx.state).isEqualTo(SetupState.MSS_CONFIRMED);
            // OTE zone IS built (so the UI can show it), and PD array set, but no advance.
            assertThat(ctx.ote).isNotNull();
        }

        @Test
        @DisplayName("size below 5 makes tryEmit() return false (M8) and not emit")
        void belowFloorBlocksEmit() {
            StdvOteStrategy s = newStrategy("MNQ");
            SetupContext ctx = s.getSetupContext();
            ctx.killzoneOpen = true;
            s.recordHtfBias(MarketBias.BULLISH);
            s.recordManipulationLeg(19960, 20000, 0.25, 0);
            s.recordSweep(new LiquiditySweep(true, 19952, Instant.now(), true), 7);
            s.recordDisplacement(new FairValueGap(true, 19962, 19960, Instant.now()));
            s.recordMss();
            s.recordOteImpulse(19952, 19984, 0.25, true);

            boolean emitted = s.tryEmit(0.25, 4, TradeTier.TIER_2, /* below floor */ 3);
            assertThat(emitted).isFalse();
            assertThat(ctx.state).isEqualTo(SetupState.OTE_ARMED);
            assertThat(ctx.lastGateFailed).isEqualTo("M8");
            assertThat(bus.events).isEmpty();
        }
    }

    @Nested
    @DisplayName("invalidation")
    class Invalidation {

        @Test
        @DisplayName("HTF bias flip mid-setup invalidates")
        void biasFlipInvalidates() {
            StdvOteStrategy s = newStrategy("MNQ");
            SetupContext ctx = s.getSetupContext();
            ctx.killzoneOpen = true;
            s.recordHtfBias(MarketBias.BULLISH);
            s.recordManipulationLeg(19960, 20000, 0.25, 0);
            s.recordSweep(new LiquiditySweep(true, 19952, Instant.now(), false), 7);

            // Flip to bearish mid-setup.
            s.recordHtfBias(MarketBias.BEARISH);
            assertThat(ctx.state).isEqualTo(SetupState.INVALIDATED);
            assertThat(ctx.lastGateFailed).contains("bias flip");
        }

        @Test
        @DisplayName("session end forces INVALIDATED for an in-flight setup")
        void sessionEndForcesInvalidated() {
            StdvOteStrategy s = newStrategy("MNQ");
            SetupContext ctx = s.getSetupContext();
            ctx.killzoneOpen = true;
            s.recordHtfBias(MarketBias.BULLISH);
            s.recordManipulationLeg(19960, 20000, 0.25, 0);
            s.onSessionEnd();
            assertThat(ctx.state).isEqualTo(SetupState.INVALIDATED);
        }

        @Test
        @DisplayName("resetForNextWindow returns the machine to IDLE")
        void resetReturnsToIdle() {
            StdvOteStrategy s = newStrategy("MNQ");
            SetupContext ctx = s.getSetupContext();
            ctx.killzoneOpen = true;
            s.recordHtfBias(MarketBias.BULLISH);
            s.resetForNextWindow();
            assertThat(ctx.state).isEqualTo(SetupState.IDLE);
            assertThat(ctx.htfBias).isEqualTo(MarketBias.NEUTRAL);
        }
    }

    @Nested
    @DisplayName("strategy identity + lifecycle")
    class Identity {

        @Test
        @DisplayName("strategy name is STDV_OTE")
        void name() {
            assertThat(newStrategy("MNQ").getName()).isEqualTo(StdvOteStrategy.NAME);
        }

        @Test
        @DisplayName("constructor rejects null collaborators")
        void rejectsNulls() {
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> new StdvOteStrategy("MNQ", null, oteCalculator, validator, bus, 40))
                    .isInstanceOf(IllegalArgumentException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> new StdvOteStrategy("MNQ", projectionEngine, null, validator, bus, 40))
                    .isInstanceOf(IllegalArgumentException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> new StdvOteStrategy("MNQ", projectionEngine, oteCalculator, null, bus, 40))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("onCandle with no setup is safe and a no-op")
        void onCandleSafeWhenIdle() {
            StdvOteStrategy s = newStrategy("MNQ");
            Candle c = new Candle("MNQ", Instant.now(), 20000, 20010, 19995, 20005, 100);
            s.onCandle(c, null);
            assertThat(s.getSetupContext().state).isEqualTo(SetupState.IDLE);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Tiny EventBus stub that captures emitted events without subscribing.
    // ──────────────────────────────────────────────────────────────────────

    private static final class TestEventBus extends EventBus {
        final java.util.List<StrategySignalEvent> events = new java.util.ArrayList<>();

        @Override
        public void publish(com.topstep.trading.event.Event event) {
            if (event instanceof StrategySignalEvent s) {
                events.add(s);
            }
        }
    }
}
