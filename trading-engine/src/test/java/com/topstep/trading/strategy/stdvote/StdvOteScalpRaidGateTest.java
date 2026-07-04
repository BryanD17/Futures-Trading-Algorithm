package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.chartstate.ChartStateQueryAPI;
import com.topstep.trading.domain.RiskLimits;
import com.topstep.trading.event.Event;
import com.topstep.trading.event.EventBus;
import com.topstep.trading.event.StrategySignalEvent;
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
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * SA4 deliverable (b): the BINARY quality gate. In scalp mode the go/no-go
 * gate is {@code raidScore >= scalp.minRaidScore} (default 6) applied to
 * real raid-pipeline scores at sweep time:
 *
 * <ul>
 *   <li>a raidScore-5 sweep is REJECTED (machine stays MANIP_DONE, no
 *       emission);</li>
 *   <li>a raidScore-6 sweep PASSES and the identical setup emits;</li>
 *   <li>a starved-pipeline FALLBACK score bypasses the floor (SA2 base-score
 *       semantics preserved — no existing scoring lowered);</li>
 *   <li>legacy mode has NO floor (behaviour byte-for-byte).</li>
 * </ul>
 */
@DisplayName("StdvOteScalpRaidGateTest (SA4 binary raid-score gate)")
class StdvOteScalpRaidGateTest {

    private static final int SCALP_FLOOR = 6;

    private StdvProjectionEngine projectionEngine;
    private OteEntryCalculator oteCalculator;
    private MandatoryConfluenceValidator validator;
    private TestBus bus;

    static final class TestBus extends EventBus {
        final List<StrategySignalEvent> events = new ArrayList<>();
        @Override
        public void publish(Event event) {
            if (event instanceof StrategySignalEvent sig) {
                events.add(sig);
            }
        }
    }

    @BeforeEach
    void setUp() {
        projectionEngine = new StdvProjectionEngine(
                null, new ImpulseExtensionAnalyzer("MNQ", 30));
        oteCalculator = new OteEntryCalculator();
        validator = new MandatoryConfluenceValidator(
                mock(MultiTimeframeAnalyzer.class),
                mock(DisplacementDetector.class),
                mock(ChartStateQueryAPI.class));
        bus = new TestBus();
    }

    private StdvOteStrategy newScalpStrategy() {
        StdvOteStrategy s = new StdvOteStrategy("MNQ", projectionEngine, oteCalculator,
                validator, bus, 40L);
        s.enableScalpMode(new ScalpTargetCalculator(
                ScalpConfig.DEFAULT_MIN_TARGET_CLEARANCE_TICKS,
                ScalpConfig.DEFAULT_CANDIDATE_WINDOW_R), SCALP_FLOOR);
        validator.setActiveRiskLimits(RiskLimits.topstep50kScalp());
        return s;
    }

    /** Drive bias + manipulation leg (same geometry as the core happy path). */
    private static void driveToManipDone(StdvOteStrategy s) {
        s.getSetupContext().killzoneOpen = true;
        s.recordHtfBias(MarketBias.BULLISH);
        s.recordManipulationLeg(19960.0, 20000.0, 0.25, 0);
        assertThat(s.getSetupContext().state).isEqualTo(SetupState.MANIP_DONE);
    }

    /** Drive the remaining gates to emission (all genuinely satisfied). */
    private static boolean driveRestAndEmit(StdvOteStrategy s) {
        s.recordDisplacement(new FairValueGap(true, 19962.0, 19960.0, Instant.now()));
        s.recordMss();
        s.recordOteImpulse(19952.0, 19984.0, 0.25, true);
        return s.tryEmit(0.25, 4, TradeTier.TIER_1, 6);
    }

    @Test
    @DisplayName("raidScore 5 from the raid pipeline is REJECTED in scalp mode")
    void raidScoreFiveRejected() {
        StdvOteStrategy s = newScalpStrategy();
        driveToManipDone(s);

        s.recordSweep(new LiquiditySweep(true, 19952.0, Instant.now(), true),
                SCALP_FLOOR - 1);

        // Gate holds: the sweep did not arm; nothing downstream can fire.
        assertThat(s.getSetupContext().state).isEqualTo(SetupState.MANIP_DONE);
        assertThat(s.getSetupContext().sweep).isNull();
        assertThat(driveRestAndEmit(s)).isFalse();
        assertThat(bus.events).isEmpty();
    }

    @Test
    @DisplayName("raidScore 6 PASSES in scalp mode and the setup emits")
    void raidScoreSixPasses() {
        StdvOteStrategy s = newScalpStrategy();
        driveToManipDone(s);

        s.recordSweep(new LiquiditySweep(true, 19952.0, Instant.now(), true), SCALP_FLOOR);
        assertThat(s.getSetupContext().state).isEqualTo(SetupState.SWEEP_DONE);

        assertThat(driveRestAndEmit(s)).isTrue();
        assertThat(bus.events).hasSize(1);
        // Scalp target model: exactly-1R fallback (no opposing liquidity fed).
        assertThat(bus.events.get(0).getActualRR()).isCloseTo(1.0,
                org.assertj.core.api.Assertions.within(1e-9));
    }

    @Test
    @DisplayName("a rejected low-score sweep does NOT kill the window — a later 6+ sweep still arms")
    void betterSweepAfterRejectionStillArms() {
        StdvOteStrategy s = newScalpStrategy();
        driveToManipDone(s);

        s.recordSweep(new LiquiditySweep(true, 19952.0, Instant.now(), true), 3);
        assertThat(s.getSetupContext().state).isEqualTo(SetupState.MANIP_DONE);

        s.recordSweep(new LiquiditySweep(true, 19951.0, Instant.now(), true), 7);
        assertThat(s.getSetupContext().state).isEqualTo(SetupState.SWEEP_DONE);
        assertThat(s.getSetupContext().raidScore).isEqualTo(7);
    }

    @Test
    @DisplayName("starved-pipeline FALLBACK score bypasses the floor (SA2 semantics preserved)")
    void fallbackScoreBypassesFloor() {
        StdvOteStrategy s = newScalpStrategy();
        driveToManipDone(s);

        // scoreFromRaidPipeline=false — the instrument-base fallback the
        // runner uses when the raid pipeline has no tracked raid.
        s.recordSweep(new LiquiditySweep(true, 19952.0, Instant.now(), true),
                5, /* scoreFromRaidPipeline */ false);
        assertThat(s.getSetupContext().state).isEqualTo(SetupState.SWEEP_DONE);
    }

    @Test
    @DisplayName("LEGACY mode: no floor — a raidScore-5 sweep arms exactly as before")
    void legacyModeHasNoFloor() {
        StdvOteStrategy s = new StdvOteStrategy("MNQ", projectionEngine, oteCalculator,
                validator, bus, 40L); // no scalp mode
        driveToManipDone(s);

        s.recordSweep(new LiquiditySweep(true, 19952.0, Instant.now(), true), 5);
        assertThat(s.getSetupContext().state).isEqualTo(SetupState.SWEEP_DONE);
        assertThat(s.getSetupContext().raidScore).isEqualTo(5);
    }
}
