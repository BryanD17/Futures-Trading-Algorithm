package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.chartstate.ChartStateQueryAPI;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Regression tests for the SA5 core fix: a failed {@code tryEmit} attempt
 * (or a transient "M7: no PD array" hint from {@code recordOteImpulse}) must
 * not poison the M9 gate on subsequent attempts.
 *
 * <p>Before the fix, the first rejection wrote {@code ctx.lastGateFailed},
 * and every later attempt then failed M9 forever — a single M8 hiccup
 * permanently blocked emission for the whole setup.
 */
@DisplayName("StdvOteStrategy emission retry (M9 poisoning regression)")
class StdvOteEmissionRetryTest {

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

    /** Drive to OTE_ARMED using the same fixture as StdvOteStrategyTest. */
    private StdvOteStrategy armedStrategy() {
        StdvOteStrategy s = new StdvOteStrategy("MNQ", projectionEngine, oteCalculator,
                validator, bus, 40L);
        SetupContext ctx = s.getSetupContext();
        ctx.killzoneOpen = true;
        s.recordHtfBias(MarketBias.BULLISH);
        s.recordManipulationLeg(19960.0, 20000.0, 0.25, 0);
        s.recordSweep(new LiquiditySweep(true, 19952.0, Instant.now(), true), 7);
        s.recordDisplacement(new FairValueGap(true, 19962.0, 19960.0, Instant.now()));
        s.recordMss();
        s.recordOteImpulse(19952.0, 19984.0, 0.25, true);
        assertThat(ctx.state).isEqualTo(SetupState.OTE_ARMED);
        return s;
    }

    @Test
    @DisplayName("a failed M8 attempt does not block a later valid attempt via M9")
    void failedAttemptDoesNotPoisonRetry() {
        StdvOteStrategy s = armedStrategy();
        SetupContext ctx = s.getSetupContext();

        // First attempt: size below the 5-micro floor → M8 rejection.
        assertThat(s.tryEmit(0.25, 4, TradeTier.TIER_2, 3)).isFalse();
        assertThat(ctx.lastGateFailed).isEqualTo("M8");
        assertThat(ctx.state).isEqualTo(SetupState.OTE_ARMED);

        // Retry with a valid size: must succeed (previously failed M9 forever).
        assertThat(s.tryEmit(0.25, 4, TradeTier.TIER_2, 10)).isTrue();
        assertThat(ctx.state).isEqualTo(SetupState.IN_TRADE);
        assertThat(ctx.lastGateFailed).isNull();
        assertThat(bus.events).hasSize(1);
    }

    @Test
    @DisplayName("a transient recordOteImpulse M7 hint does not poison the emission")
    void m7HintDoesNotPoisonEmission() {
        StdvOteStrategy s = new StdvOteStrategy("MNQ", projectionEngine, oteCalculator,
                validator, bus, 40L);
        SetupContext ctx = s.getSetupContext();
        ctx.killzoneOpen = true;
        s.recordHtfBias(MarketBias.BULLISH);
        s.recordManipulationLeg(19960.0, 20000.0, 0.25, 0);
        s.recordSweep(new LiquiditySweep(true, 19952.0, Instant.now(), true), 7);
        s.recordDisplacement(new FairValueGap(true, 19962.0, 19960.0, Instant.now()));
        s.recordMss();

        // First impulse read: FVG entirely outside the OTE band → M7 hint set.
        s.recordOteImpulse(19952.0, 19964.0, 0.25, true);
        assertThat(ctx.state).isEqualTo(SetupState.MSS_CONFIRMED);
        assertThat(ctx.lastGateFailed).startsWith("M7");

        // Leg extends; the FVG's edge is now inside the band → OTE_ARMED.
        s.recordOteImpulse(19952.0, 19984.0, 0.25, true);
        assertThat(ctx.state).isEqualTo(SetupState.OTE_ARMED);

        // Emission must pass — the stale M7 hint must not trip M9.
        assertThat(s.tryEmit(0.25, 4, TradeTier.TIER_2, 10)).isTrue();
        assertThat(ctx.state).isEqualTo(SetupState.IN_TRADE);
        assertThat(bus.events).hasSize(1);
    }

    @Test
    @DisplayName("an externally-written pre-flight failure is still honored by M9")
    void externalPreflightStillFailsM9() {
        StdvOteStrategy s = armedStrategy();
        SetupContext ctx = s.getSetupContext();

        // Simulate a risk pre-flight (SA3+) rejecting on the same bar.
        ctx.lastGateFailed = "risk pre-flight: daily loss limit";
        assertThat(s.tryEmit(0.25, 4, TradeTier.TIER_2, 10)).isFalse();
        assertThat(ctx.state).isEqualTo(SetupState.OTE_ARMED);
        assertThat(ctx.lastGateFailed).contains("M9");
        assertThat(bus.events).isEmpty();
    }

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
