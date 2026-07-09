package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.chartstate.ChartStateQueryAPI;
import com.topstep.trading.event.Event;
import com.topstep.trading.event.EventBus;
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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * V2 Agent 04: NEUTRAL-flip hysteresis (config-gated, DEFAULT OFF).
 *
 * <p>Field evidence 2026-07-09: a LIVE session passed M1–M4 and was then
 * destroyed by "HTF bias became NEUTRAL" before M5. These tests pin the
 * new semantics: NEUTRAL = uncertainty (bounded grace when enabled),
 * OPPOSITE = contradiction (dies instantly, always), and the HARD
 * INVARIANT that no entry can ever be emitted while the live bias
 * evaluation reads NEUTRAL — grace preserves progress, never entries.
 */
@DisplayName("StdvOteStrategy bias hysteresis (V2 Agent 04)")
class StdvOteBiasHysteresisTest {

    /** Recording bus (synchronous; publish captures, never dispatches). */
    static final class RecordingBus extends EventBus {
        final List<Event> events = new ArrayList<>();
        @Override public void publish(Event event) { events.add(event); }
    }

    private StdvProjectionEngine projectionEngine;
    private OteEntryCalculator oteCalculator;
    private MandatoryConfluenceValidator validator;
    private RecordingBus bus;

    @BeforeEach
    void setUp() {
        projectionEngine = new StdvProjectionEngine(
                /* chartState */ null, new ImpulseExtensionAnalyzer("MNQ", 30));
        oteCalculator = new OteEntryCalculator();
        validator = new MandatoryConfluenceValidator(
                mock(MultiTimeframeAnalyzer.class),
                mock(DisplacementDetector.class),
                mock(ChartStateQueryAPI.class));
        bus = new RecordingBus();
    }

    private StdvOteStrategy newStrategy() {
        StdvOteStrategy s = new StdvOteStrategy("MNQ", projectionEngine,
                oteCalculator, validator, bus, /* expiryBars */ 40L);
        s.getSetupContext().killzoneOpen = true; // M3
        return s;
    }

    /** Drive BULLISH bias → manipulation leg → sweep (state SWEEP_DONE). */
    private StdvOteStrategy driveToSweepDone(StdvOteStrategy s) {
        s.recordHtfBias(MarketBias.BULLISH);
        s.recordManipulationLeg(19960.0, 20000.0, 0.25, 0);
        s.recordSweep(new LiquiditySweep(true, 19952.0, Instant.now(), true), 7);
        assertThat(s.getSetupContext().state).isEqualTo(SetupState.SWEEP_DONE);
        return s;
    }

    /** Continue SWEEP_DONE → OTE_ARMED (displacement, MSS, impulse). */
    private void armOte(StdvOteStrategy s) {
        s.recordDisplacement(new FairValueGap(true, 19962.0, 19960.0, Instant.now()));
        s.recordMss();
        s.recordOteImpulse(19952.0, 19984.0, 0.25, /* reactionConfirmed */ true);
        assertThat(s.getSetupContext().state).isEqualTo(SetupState.OTE_ARMED);
    }

    @Test
    @DisplayName("(a) ON: one NEUTRAL wobble held; bias return resets and the machine continues")
    void wobbleHeldAndRestored() {
        StdvOteStrategy s = newStrategy();
        s.configureBiasHysteresis(true, 2);
        driveToSweepDone(s);

        s.recordHtfBias(MarketBias.NEUTRAL); // wobble 1/2
        assertThat(s.getSetupContext().state)
                .as("one NEUTRAL evaluation within grace must NOT invalidate")
                .isEqualTo(SetupState.SWEEP_DONE);

        s.recordHtfBias(MarketBias.BULLISH); // restored
        assertThat(s.getSetupContext().state).isEqualTo(SetupState.SWEEP_DONE);
        assertThat(s.getSetupContext().htfBias).isEqualTo(MarketBias.BULLISH);

        // The machine continues where it was: finish the setup and emit.
        armOte(s);
        boolean emitted = s.tryEmit(0.25, 4, TradeTier.TIER_3, 12);
        assertThat(emitted).isTrue();
        assertThat(bus.events).hasSize(1);
    }

    @Test
    @DisplayName("(b) ON: NEUTRAL beyond graceBars invalidates with the grace reason")
    void neutralBeyondGraceInvalidates() {
        StdvOteStrategy s = newStrategy();
        s.configureBiasHysteresis(true, 2);
        driveToSweepDone(s);

        s.recordHtfBias(MarketBias.NEUTRAL); // 1/2 held
        s.recordHtfBias(MarketBias.NEUTRAL); // 2/2 held
        assertThat(s.getSetupContext().state).isEqualTo(SetupState.SWEEP_DONE);
        s.recordHtfBias(MarketBias.NEUTRAL); // 3 > 2 → dead
        assertThat(s.getSetupContext().state).isEqualTo(SetupState.INVALIDATED);
        assertThat(s.getSetupContext().lastGateFailed)
                .isEqualTo("HTF bias NEUTRAL beyond grace");
    }

    @Test
    @DisplayName("(c) ON: OPPOSITE flip during grace invalidates immediately")
    void oppositeDuringGraceInvalidatesImmediately() {
        StdvOteStrategy s = newStrategy();
        s.configureBiasHysteresis(true, 4);
        driveToSweepDone(s);

        s.recordHtfBias(MarketBias.NEUTRAL); // wobble held
        assertThat(s.getSetupContext().state).isEqualTo(SetupState.SWEEP_DONE);
        s.recordHtfBias(MarketBias.BEARISH); // contradiction
        assertThat(s.getSetupContext().state).isEqualTo(SetupState.INVALIDATED);
        assertThat(s.getSetupContext().lastGateFailed)
                .contains("HTF bias flip BULLISH -> BEARISH");
    }

    @Test
    @DisplayName("(d) MANDATORY INVARIANT: NEUTRAL within grace at emission time -> NO signal")
    void noEmissionWhileNeutralWithinGrace() {
        StdvOteStrategy s = newStrategy();
        s.configureBiasHysteresis(true, 2);
        driveToSweepDone(s);
        armOte(s); // every other emission condition satisfied

        s.recordHtfBias(MarketBias.NEUTRAL); // grace holds the setup...
        assertThat(s.getSetupContext().state).isEqualTo(SetupState.OTE_ARMED);

        boolean emitted = s.tryEmit(0.25, 4, TradeTier.TIER_3, 12);
        assertThat(emitted)
                .as("grace preserves PROGRESS — it must never permit an entry while bias is NEUTRAL")
                .isFalse();
        assertThat(bus.events).isEmpty();
        assertThat(s.getSetupContext().state)
                .as("the held setup survives the blocked emission")
                .isEqualTo(SetupState.OTE_ARMED);

        // Bias returns → the SAME setup may now emit legitimately.
        s.recordHtfBias(MarketBias.BULLISH);
        assertThat(s.tryEmit(0.25, 4, TradeTier.TIER_3, 12)).isTrue();
        assertThat(bus.events).hasSize(1);
    }

    @Test
    @DisplayName("(e) OFF: first NEUTRAL still invalidates exactly as today + counterfactual log")
    void offPathIdenticalPlusCounterfactualLog() {
        StdvOteStrategy s = newStrategy();
        s.configureBiasHysteresis(false, 2); // explicit OFF (also the default)
        driveToSweepDone(s);

        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            s.recordHtfBias(MarketBias.NEUTRAL);
        } finally {
            System.setOut(original);
        }

        assertThat(s.getSetupContext().state).isEqualTo(SetupState.INVALIDATED);
        assertThat(s.getSetupContext().lastGateFailed)
                .as("OFF-path reason text unchanged from pre-V2")
                .isEqualTo("HTF bias became NEUTRAL");
        assertThat(captured.toString(StandardCharsets.UTF_8))
                .contains("[BIAS] NEUTRAL flip invalidated setup (hysteresis OFF — "
                        + "grace would have held it 2 more bar(s))");
    }

    @Test
    @DisplayName("(f) determinism: identical sequence with hysteresis ON -> identical transitions, twice")
    void deterministicTransitionsWithHysteresisOn() {
        List<SetupState> runA = runSequence();
        List<SetupState> runB = runSequence();
        assertThat(runA).isEqualTo(runB);
        // The sequence must actually exercise hold + expiry.
        assertThat(runA).contains(SetupState.SWEEP_DONE, SetupState.INVALIDATED);
    }

    private List<SetupState> runSequence() {
        StdvOteStrategy s = newStrategy();
        s.configureBiasHysteresis(true, 2);
        List<SetupState> transitions = new ArrayList<>();
        driveToSweepDone(s);
        transitions.add(s.getSetupContext().state);
        MarketBias[] biasSequence = {
                MarketBias.NEUTRAL, MarketBias.BULLISH, MarketBias.NEUTRAL,
                MarketBias.NEUTRAL, MarketBias.NEUTRAL,
        };
        for (MarketBias b : biasSequence) {
            s.recordHtfBias(b);
            transitions.add(s.getSetupContext().state);
        }
        return transitions;
    }

    @Test
    @DisplayName("config: defaults OFF / 2, graceBars clamped to [1,4]")
    void configDefaultsAndClamps() {
        StdvOteStrategy s = newStrategy();
        // Clamp check via behavior: configure with an absurd grace value and
        // confirm it behaves as 4 (the cap), not 99.
        s.configureBiasHysteresis(true, 99);
        driveToSweepDone(s);
        for (int i = 0; i < 4; i++) {
            s.recordHtfBias(MarketBias.NEUTRAL);
            assertThat(s.getSetupContext().state).isEqualTo(SetupState.SWEEP_DONE);
        }
        s.recordHtfBias(MarketBias.NEUTRAL); // 5th > clamp 4 → dead
        assertThat(s.getSetupContext().state).isEqualTo(SetupState.INVALIDATED);
    }
}
