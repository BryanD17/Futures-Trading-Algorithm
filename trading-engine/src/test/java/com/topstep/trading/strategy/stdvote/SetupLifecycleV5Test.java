package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.chartstate.ChartStateQueryAPI;
import com.topstep.trading.domain.Candle;
import com.topstep.trading.event.Event;
import com.topstep.trading.event.EventBus;
import com.topstep.trading.strategy.DisplacementDetector;
import com.topstep.trading.strategy.ImpulseExtensionAnalyzer;
import com.topstep.trading.strategy.LiquiditySweep;
import com.topstep.trading.strategy.MarketBias;
import com.topstep.trading.strategy.MultiTimeframeAnalyzer;
import com.topstep.trading.strategy.session.RearmBiasGuard;
import com.topstep.trading.strategy.session.SessionConfig;
import com.topstep.trading.validation.MandatoryConfluenceValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * V5 Agent 02 task 7 — setup lifecycle (RC-03 / PF-10):
 * <ol>
 *   <li>expiry is measured N bars from SWEEP_DONE, not from BIAS_SET;</li>
 *   <li>INVALIDATED re-arms after the cooldown WITHOUT a killzone in
 *       SCORING (only NO_ENTRY / WEEKEND block), in the legacy target
 *       model (scalp mode off) — decoupled from scalp mode;</li>
 *   <li>a re-armed setup is not re-invalidated on the SAME bias event.</li>
 * </ol>
 */
@DisplayName("Setup lifecycle V5 (expiry from SWEEP_DONE, SCORING re-arm, no double-invalidate)")
class SetupLifecycleV5Test {

    static final class NullBus extends EventBus {
        @Override public void publish(Event event) { /* drop */ }
    }

    @org.junit.jupiter.api.BeforeEach
    void pinHysteresisOff() {
        // V5 Agent 01: engine-defaults.properties turns bias.hysteresis ON.
        // The re-arm fixtures below kill the setup through the REAL path
        // ("HTF bias became NEUTRAL" on a 15m close), which only exists with
        // hysteresis OFF — pin it (same pattern as StdvOteScalpRearmStuckTest).
        System.setProperty("bias.hysteresis", "false");
    }

    @AfterEach
    void cleanup() {
        System.clearProperty("bias.hysteresis");
        System.clearProperty(SessionConfig.GATE_MODE);
        System.clearProperty(ScalpConfig.ENABLED_PROPERTY);
        StdvOteRegistry.unregister("MNQ");
    }

    // ── 1. expiry from SWEEP_DONE ────────────────────────────────────────

    @Nested
    @DisplayName("1. expiry N bars after SWEEP_DONE")
    class ExpiryFromSweep {

        private StdvOteStrategy newCore(long legacyExpiryBars) {
            return new StdvOteStrategy("MNQ",
                    new StdvProjectionEngine(null, new ImpulseExtensionAnalyzer("MNQ", 30)),
                    new OteEntryCalculator(),
                    new MandatoryConfluenceValidator(mock(MultiTimeframeAnalyzer.class),
                            mock(DisplacementDetector.class), mock(ChartStateQueryAPI.class)),
                    null, legacyExpiryBars);
        }

        private static void bars(StdvOteStrategy s, int n) {
            for (int i = 0; i < n; i++) s.onCandle(null, null);
        }

        @Test
        @DisplayName("SWEEP_DONE anchor: survives 100 bars in BIAS_SET/MANIP_DONE, then expires exactly 12 bars after SWEEP_DONE")
        void expiresTwelveBarsAfterSweepNotAfterBias() {
            StdvOteStrategy s = newCore(40);
            s.configureExpiry(SessionConfig.ExpiryAnchor.SWEEP_DONE, 12, 480);
            SetupContext ctx = s.getSetupContext();

            bars(s, 1);                               // barIndex 1
            s.recordHtfBias(MarketBias.BULLISH);      // BIAS_SET at bar 1
            assertThat(ctx.createdAtBar).isEqualTo(1);
            bars(s, 50);                              // 50 bars waiting — the pre-V5 40-bar budget is long gone
            s.recordManipulationLeg(19960.0, 20000.0, 0.25, 0);
            assertThat(ctx.state).isEqualTo(SetupState.MANIP_DONE);
            bars(s, 50);                              // barIndex 101
            assertThat(ctx.state).as("pre-sweep budget is 480 bars, not 40").isEqualTo(SetupState.MANIP_DONE);

            s.recordSweep(new LiquiditySweep(true, 19952.0, Instant.parse("2026-09-28T19:00:00Z"), false), 5);
            assertThat(ctx.state).isEqualTo(SetupState.SWEEP_DONE);

            bars(s, 12);                              // 12 bars after SWEEP_DONE: still alive
            assertThat(s.sweepAtBar()).isEqualTo(101);
            assertThat(ctx.state).isEqualTo(SetupState.SWEEP_DONE);
            assertThat(ctx.expiresAtBar).isEqualTo(113);

            bars(s, 1);                               // the 13th bar: expired
            assertThat(ctx.state).isEqualTo(SetupState.INVALIDATED);
            assertThat(ctx.lastGateFailed).isEqualTo("expired (12 bars after SWEEP_DONE without an entry)");
            assertThat(s.barIndex() - s.sweepAtBar()).isEqualTo(13);
        }

        @Test
        @DisplayName("control: the pre-V5 BIAS_SET anchor kills the same setup 41 bars after BIAS_SET, before any sweep")
        void biasSetAnchorIsThePreV5Behaviour() {
            StdvOteStrategy s = newCore(40);          // default anchor = BIAS_SET
            SetupContext ctx = s.getSetupContext();
            bars(s, 1);
            s.recordHtfBias(MarketBias.BULLISH);
            bars(s, 40);
            assertThat(ctx.state).isEqualTo(SetupState.BIAS_SET);
            bars(s, 1);
            assertThat(ctx.state).isEqualTo(SetupState.INVALIDATED);
            assertThat(ctx.lastGateFailed).isEqualTo("expired (40 bars without progress)");
        }

        @Test
        @DisplayName("pre-sweep phases have their own generous budget (480 bars from BIAS_SET)")
        void preSweepBudget() {
            StdvOteStrategy s = newCore(40);
            s.configureExpiry(SessionConfig.ExpiryAnchor.SWEEP_DONE, 12, 480);
            SetupContext ctx = s.getSetupContext();
            bars(s, 1);
            s.recordHtfBias(MarketBias.BULLISH);
            bars(s, 480);
            assertThat(ctx.state).isEqualTo(SetupState.BIAS_SET);
            bars(s, 1);
            assertThat(ctx.state).isEqualTo(SetupState.INVALIDATED);
            assertThat(ctx.lastGateFailed).isEqualTo("expired (480 bars before SWEEP_DONE)");
        }

        @Test
        @DisplayName("default budgets: 60 min from SWEEP_DONE = 60 bars on 1m, 12 detector bars on 5m; BLOCKING = 40 detector bars")
        void defaultBudgets() {
            assertThat(SessionConfig.expiryFeedBars(1, SessionConfig.ExpiryAnchor.SWEEP_DONE)).isEqualTo(60);
            assertThat(SessionConfig.expiryFeedBars(5, SessionConfig.ExpiryAnchor.SWEEP_DONE) / 5).isEqualTo(12);
            assertThat(SessionConfig.expiryFeedBars(5, SessionConfig.ExpiryAnchor.BIAS_SET)).isEqualTo(200);
            assertThat(SessionConfig.expiryAnchor(com.topstep.trading.strategy.session.SessionGateMode.SCORING))
                    .isEqualTo(SessionConfig.ExpiryAnchor.SWEEP_DONE);
            assertThat(SessionConfig.expiryAnchor(com.topstep.trading.strategy.session.SessionGateMode.BLOCKING))
                    .isEqualTo(SessionConfig.ExpiryAnchor.BIAS_SET);
            System.setProperty(SessionConfig.EXPIRY_BARS, "12");
            try {
                assertThat(SessionConfig.expiryFeedBars(5, SessionConfig.ExpiryAnchor.SWEEP_DONE)).isEqualTo(60);
                System.setProperty(SessionConfig.EXPIRY_MINUTES, "45");
                assertThat(SessionConfig.expiryFeedBars(5, SessionConfig.ExpiryAnchor.SWEEP_DONE)).isEqualTo(45);
            } finally {
                System.clearProperty(SessionConfig.EXPIRY_BARS);
                System.clearProperty(SessionConfig.EXPIRY_MINUTES);
            }
        }
    }

    // ── 2 + 3. runner re-arm ────────────────────────────────────────────

    /** Tue 2026-07-07 20:00 EDT = ASIA — outside every legacy killzone. */
    private static final Instant ASIA_T0 = Instant.parse("2026-07-08T00:00:00Z");
    /** Wed 2026-07-08 16:00 EDT = NO_ENTRY (15:00 CT). */
    private static final Instant NO_ENTRY_T0 = Instant.parse("2026-07-08T20:00:00Z");

    private static Candle flat(Instant ts) {
        return new Candle("MNQ", ts, 20000, 20000, 20000, 20000, 100);
    }

    /**
     * Drive the REAL runner into INVALIDATED ("HTF bias became NEUTRAL" on
     * the 15m close after T0) and return it; then {@code extraBars} 1m bars.
     */
    private static StdvOteRunnerStrategy invalidateThenRun(Instant t0, int extraBars) {
        StdvOteRunnerStrategy runner = new StdvOteRunnerStrategy("MNQ", null, new NullBus());
        StdvOteStrategy core = StdvOteRegistry.get("MNQ").orElseThrow();
        SetupContext ctx = runner.getSetupContext();
        runner.onCandle(flat(t0), null);
        core.recordHtfBias(MarketBias.BULLISH);
        assertThat(ctx.state).isEqualTo(SetupState.BIAS_SET);
        runner.onCandle(flat(t0.plus(Duration.ofMinutes(15))), null);
        assertThat(ctx.state).isEqualTo(SetupState.INVALIDATED);
        assertThat(ctx.lastGateFailed).isEqualTo("HTF bias became NEUTRAL");
        for (int i = 1; i <= extraBars; i++) {
            runner.onCandle(flat(t0.plus(Duration.ofMinutes(15 + i))), null);
        }
        return runner;
    }

    @Nested
    @DisplayName("2. re-arm after cooldown without a killzone (SCORING)")
    class RearmWithoutKillzone {

        @Test
        @DisplayName("SCORING, legacy target model, 20:00 ET ASIA: re-arms to IDLE after the 5-bar cooldown")
        void scoringRearmsInAsia() {
            // scalpMode OFF (legacy target model) + default session.gateMode (SCORING)
            StdvOteRunnerStrategy runner = invalidateThenRun(ASIA_T0, 10);
            SetupContext ctx = runner.getSetupContext();
            assertThat(ctx.sessionWindow).isEqualTo("ASIA");
            assertThat(ctx.primeKillzone).isFalse();
            assertThat(ctx.killzoneOpen).as("SCORING: gate open outside NO_ENTRY/WEEKEND").isTrue();
            assertThat(ctx.state).isEqualTo(SetupState.IDLE);
        }

        @Test
        @DisplayName("SCORING in scalp mode: same re-arm in ASIA (decoupled from scalp mode)")
        void scoringRearmsInAsiaScalp() {
            System.setProperty(ScalpConfig.ENABLED_PROPERTY, "true");
            StdvOteRunnerStrategy runner = invalidateThenRun(ASIA_T0, 10);
            assertThat(runner.getSetupContext().state).isEqualTo(SetupState.IDLE);
        }

        @Test
        @DisplayName("control — BLOCKING: the same ASIA setup stays INVALIDATED (killzone closed), the pre-V5 behaviour")
        void blockingStaysInvalidatedInAsia() {
            System.setProperty(SessionConfig.GATE_MODE, "BLOCKING");
            StdvOteRunnerStrategy runner = invalidateThenRun(ASIA_T0, 10);
            SetupContext ctx = runner.getSetupContext();
            assertThat(ctx.killzoneOpen).isFalse();
            assertThat(ctx.state).isEqualTo(SetupState.INVALIDATED);
        }

        @Test
        @DisplayName("SACRED: SCORING never re-arms inside NO_ENTRY (14:45-17:00 CT)")
        void scoringDoesNotRearmInNoEntry() {
            StdvOteRunnerStrategy runner = invalidateThenRun(NO_ENTRY_T0, 10);
            SetupContext ctx = runner.getSetupContext();
            assertThat(ctx.sessionWindow).isEqualTo("NO_ENTRY");
            assertThat(ctx.killzoneOpen).isFalse();
            assertThat(ctx.state).isEqualTo(SetupState.INVALIDATED);
        }
    }

    @Nested
    @DisplayName("3. no double-invalidate on the same bias event")
    class NoDoubleInvalidate {

        @Test
        @DisplayName("a re-armed setup killed again by a bias reason with NO new bias event is restored at once (no second cooldown)")
        void sameBiasEventDoesNotKillTwice() {
            StdvOteRunnerStrategy runner = invalidateThenRun(ASIA_T0, 10);
            StdvOteStrategy core = StdvOteRegistry.get("MNQ").orElseThrow();
            SetupContext ctx = runner.getSetupContext();
            assertThat(ctx.state).isEqualTo(SetupState.IDLE);   // re-armed (event key K)

            // The re-armed setup progresses …
            core.recordHtfBias(MarketBias.BULLISH);
            assertThat(ctx.state).isEqualTo(SetupState.BIAS_SET);
            // … and a stale re-delivery of the SAME bias event (no new 15m
            // evaluation, runner bias unchanged) tries to kill it again.
            core.invalidate("HTF bias flip BEARISH -> BULLISH");
            Instant t = ASIA_T0.plus(Duration.ofMinutes(26));
            runner.onCandle(flat(t), null);

            assertThat(runner.suppressedDuplicateInvalidationsForTest()).isEqualTo(1);
            assertThat(ctx.state).as("restored on the next candle, not parked in a second cooldown")
                    .isNotEqualTo(SetupState.INVALIDATED);
        }

        @Test
        @DisplayName("a NON-bias death of a re-armed setup still pays the normal cooldown")
        void nonBiasDeathIsNotSuppressed() {
            StdvOteRunnerStrategy runner = invalidateThenRun(ASIA_T0, 10);
            StdvOteStrategy core = StdvOteRegistry.get("MNQ").orElseThrow();
            SetupContext ctx = runner.getSetupContext();
            core.recordHtfBias(MarketBias.BULLISH);
            core.invalidate("impulse origin violated before OTE entry");
            runner.onCandle(flat(ASIA_T0.plus(Duration.ofMinutes(26))), null);
            assertThat(runner.suppressedDuplicateInvalidationsForTest()).isZero();
            assertThat(ctx.state).isEqualTo(SetupState.INVALIDATED);
        }

        @Test
        @DisplayName("RearmBiasGuard: a NEW bias event after the re-arm makes the death legitimate")
        void guardKeyLogic() {
            RearmBiasGuard g = new RearmBiasGuard();
            SetupContext ctx = new SetupContext();
            g.observeBias(MarketBias.BULLISH);
            g.onRearm(ctx);
            g.observeBias(MarketBias.BULLISH);                       // same evaluation repeated
            assertThat(g.isDuplicateInvalidation(ctx, "HTF bias flip BULLISH -> BEARISH")).isTrue();
            assertThat(g.isDuplicateInvalidation(ctx, "expired (60 bars after SWEEP_DONE without an entry)")).isFalse();
            g.observeBias(MarketBias.BEARISH);                       // a real new event
            // Agent 03: with SetupContext.biasEpoch present the guard keys on it;
            // the core bumps it on every REAL flip (StdvOteStrategy.recordHtfBias).
            if (g.usesEpoch()) ctx.biasEpoch++;
            assertThat(g.isDuplicateInvalidation(ctx, "HTF bias flip BULLISH -> BEARISH")).isFalse();
            g.reset();
            assertThat(g.isDuplicateInvalidation(ctx, "HTF bias flip BULLISH -> BEARISH"))
                    .as("never re-armed -> never a duplicate").isFalse();
        }
    }
}
