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
import com.topstep.trading.strategy.session.SessionConfig;
import com.topstep.trading.strategy.session.SessionGateMode;
import com.topstep.trading.validation.MandatoryConfluenceValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * V5 Agent 05.1 (starvation S2) — PHASE-AWARE setup expiry.
 *
 * <p>Real tape, Main ac5e602, cfg A: 85 of 104 swept setups died "expired"
 * exactly 61 feed bars after SWEEP_DONE (39 SWEEP_DONE, 19 DISPLACED,
 * 21 MSS_CONFIRMED, 6 OTE_ARMED). The PHASED anchor gives each post-sweep
 * phase its own budget, reset on the state transition.
 *
 * <p>BAR-LEVEL RULE under test: a transition recorded on bar B stamps the
 * phase at B; after n further feed bars elapsed = n; the phase survives
 * while elapsed &lt;= budget and dies on the first bar with elapsed &gt; budget
 * (the (budget+1)-th bar after the transition bar).
 */
@DisplayName("Phase-aware setup expiry (Agent 05.1: sweep->displacement->MSS->OTE budgets)")
class PhaseAwareExpiryTest {

    static final class NullBus extends EventBus {
        @Override public void publish(Event event) { /* drop */ }
    }

    @AfterEach
    void cleanup() {
        System.clearProperty(SessionConfig.EXPIRY_ANCHOR);
        System.clearProperty(SessionConfig.EXPIRY_SWEEP_TO_DISPLACEMENT);
        System.clearProperty(SessionConfig.EXPIRY_DISPLACEMENT_TO_MSS);
        System.clearProperty(SessionConfig.EXPIRY_MSS_TO_OTE);
        System.clearProperty(SessionConfig.GATE_MODE);
        StdvOteRegistry.unregister("MNQ");
    }

    private static StdvOteStrategy newCore(SessionConfig.ExpiryAnchor anchor) {
        StdvOteStrategy s = new StdvOteStrategy("MNQ",
                new StdvProjectionEngine(null, new ImpulseExtensionAnalyzer("MNQ", 30)),
                new OteEntryCalculator(),
                new MandatoryConfluenceValidator(mock(MultiTimeframeAnalyzer.class),
                        mock(DisplacementDetector.class), mock(ChartStateQueryAPI.class)),
                null, 200);
        // The runner's production wiring on a 1m feed: legacy total 60, pre-sweep 480.
        s.configureExpiry(anchor, 60, 480);
        s.configurePhaseBudgets(60, 60, 240);
        return s;
    }

    private static void bars(StdvOteStrategy s, int n) {
        for (int i = 0; i < n; i++) s.onCandle(null, null);
    }

    /** BIAS_SET -> MANIP_DONE -> SWEEP_DONE (bullish); returns the sweep bar index. */
    private static long sweep(StdvOteStrategy s) {
        bars(s, 1);
        s.recordHtfBias(MarketBias.BULLISH);
        bars(s, 5);
        s.recordManipulationLeg(19960.0, 20000.0, 0.25, 0);
        bars(s, 5);
        s.recordSweep(new LiquiditySweep(true, 19952.0, Instant.parse("2026-09-28T19:00:00Z"), false), 5);
        assertThat(s.getSetupContext().state).isEqualTo(SetupState.SWEEP_DONE);
        return s.barIndex();
    }

    private static void displace(StdvOteStrategy s) {
        s.recordDisplacement(new FairValueGap(true, 19990.0, 19980.0, Instant.parse("2026-09-28T19:05:00Z")));
        assertThat(s.getSetupContext().state).isEqualTo(SetupState.DISPLACED);
    }

    private static void mss(StdvOteStrategy s) {
        s.recordMss();
        assertThat(s.getSetupContext().state).isEqualTo(SetupState.MSS_CONFIRMED);
    }

    @Nested
    @DisplayName("PHASED: each phase budget survives at N bars, expires at N+1")
    class PhaseBudgets {

        @Test
        @DisplayName("SWEEP_DONE->DISPLACEMENT: alive 60 bars after the sweep, dead on the 61st")
        void sweepToDisplacement() {
            StdvOteStrategy s = newCore(SessionConfig.ExpiryAnchor.PHASED);
            SetupContext ctx = s.getSetupContext();
            long b = sweep(s);
            bars(s, 60);
            assertThat(ctx.state).isEqualTo(SetupState.SWEEP_DONE);
            assertThat(s.phaseAtBar()).isEqualTo(b);
            assertThat(ctx.expiresAtBar).isEqualTo(b + 60);
            bars(s, 1);
            assertThat(ctx.state).isEqualTo(SetupState.INVALIDATED);
            assertThat(ctx.lastGateFailed).isEqualTo("expired: SWEEP_DONE→DISPLACEMENT 61 > 60 min");
        }

        @Test
        @DisplayName("DISPLACED->MSS: counter resets on the displacement; alive 60 bars after it (110 after the sweep), dead on the 61st")
        void displacementToMss() {
            StdvOteStrategy s = newCore(SessionConfig.ExpiryAnchor.PHASED);
            SetupContext ctx = s.getSetupContext();
            long b = sweep(s);
            bars(s, 50);
            displace(s);
            long d = s.barIndex();
            bars(s, 60);
            assertThat(ctx.state).as("110 bars after the sweep - the old single budget died at 61")
                    .isEqualTo(SetupState.DISPLACED);
            assertThat(s.barIndex() - b).isEqualTo(110);
            assertThat(s.phaseAtBar()).isEqualTo(d);
            bars(s, 1);
            assertThat(ctx.state).isEqualTo(SetupState.INVALIDATED);
            assertThat(ctx.lastGateFailed).isEqualTo("expired: DISPLACED→MSS 61 > 60 min");
        }

        @Test
        @DisplayName("MSS_CONFIRMED->OTE: counter resets on the MSS; alive 240 bars waiting for the retrace, dead on the 241st")
        void mssToOte() {
            StdvOteStrategy s = newCore(SessionConfig.ExpiryAnchor.PHASED);
            SetupContext ctx = s.getSetupContext();
            sweep(s);
            bars(s, 30);
            displace(s);
            bars(s, 30);
            mss(s);
            long m = s.barIndex();
            bars(s, 240);
            assertThat(ctx.state).isEqualTo(SetupState.MSS_CONFIRMED);
            assertThat(ctx.expiresAtBar).isEqualTo(m + 240);
            bars(s, 1);
            assertThat(ctx.state).isEqualTo(SetupState.INVALIDATED);
            assertThat(ctx.lastGateFailed).isEqualTo("expired: MSS_CONFIRMED→OTE 241 > 240 min");
        }

        @Test
        @DisplayName("OTE_ARMED has no core time budget (the runner's ote.windowBars owns that phase)")
        void oteArmedOwnedByRunnerWindow() {
            StdvOteStrategy s = newCore(SessionConfig.ExpiryAnchor.PHASED);
            SetupContext ctx = s.getSetupContext();
            sweep(s);
            bars(s, 10);
            displace(s);
            bars(s, 10);
            mss(s);
            bars(s, 200);
            ctx.state = SetupState.OTE_ARMED;   // = armOte(): the band was touched
            bars(s, 500);
            assertThat(ctx.state).isEqualTo(SetupState.OTE_ARMED);
            assertThat(ctx.expiresAtBar).isZero();
        }

        @Test
        @DisplayName("several transitions on ONE bar stamp once, for the state finally reached")
        void multiTransitionBar() {
            StdvOteStrategy s = newCore(SessionConfig.ExpiryAnchor.PHASED);
            SetupContext ctx = s.getSetupContext();
            sweep(s);
            displace(s);                         // same bar as the sweep
            long d = s.barIndex();
            bars(s, 60);
            assertThat(ctx.state).isEqualTo(SetupState.DISPLACED);
            assertThat(s.phaseAtBar()).isEqualTo(d);
            bars(s, 1);
            assertThat(ctx.lastGateFailed).isEqualTo("expired: DISPLACED→MSS 61 > 60 min");
        }

        @Test
        @DisplayName("pre-sweep 480-min budget (Agent 02) is kept under PHASED")
        void preSweepKept() {
            StdvOteStrategy s = newCore(SessionConfig.ExpiryAnchor.PHASED);
            SetupContext ctx = s.getSetupContext();
            bars(s, 1);
            s.recordHtfBias(MarketBias.BULLISH);
            bars(s, 480);
            assertThat(ctx.state).isEqualTo(SetupState.BIAS_SET);
            bars(s, 1);
            assertThat(ctx.lastGateFailed).isEqualTo("expired (480 bars before SWEEP_DONE)");
        }

        @Test
        @DisplayName("a budget of 0 disables that phase")
        void zeroDisables() {
            StdvOteStrategy s = newCore(SessionConfig.ExpiryAnchor.PHASED);
            s.configurePhaseBudgets(0, 60, 240);
            sweep(s);
            bars(s, 1000);
            assertThat(s.getSetupContext().state).isEqualTo(SetupState.SWEEP_DONE);
        }
    }

    @Nested
    @DisplayName("legacy SWEEP_DONE_TOTAL reproduces the old 61-bar death")
    class Legacy {

        /** sweep -> 20 bars -> displacement -> 20 bars -> MSS -> waiting for the retrace. */
        private StdvOteStrategy tapeLikeEpisode(SessionConfig.ExpiryAnchor anchor, int barsAfterMss) {
            StdvOteStrategy s = newCore(anchor);
            sweep(s);
            bars(s, 20);
            displace(s);
            bars(s, 20);
            mss(s);
            bars(s, barsAfterMss);
            return s;
        }

        @Test
        @DisplayName("SWEEP_DONE_TOTAL: the MSS_CONFIRMED setup dies exactly 61 bars after the sweep (the tape's S2 death)")
        void legacyKillsAt61() {
            StdvOteStrategy s = tapeLikeEpisode(SessionConfig.ExpiryAnchor.SWEEP_DONE_TOTAL, 20);
            assertThat(s.getSetupContext().state).isEqualTo(SetupState.MSS_CONFIRMED);   // 60 after sweep
            bars(s, 1);                                                                   // 61
            assertThat(s.getSetupContext().state).isEqualTo(SetupState.INVALIDATED);
            assertThat(s.barIndex() - s.sweepAtBar()).isEqualTo(61);
            assertThat(s.getSetupContext().lastGateFailed)
                    .isEqualTo("expired (60 bars after SWEEP_DONE without an entry)");
        }

        @Test
        @DisplayName("PHASED: the SAME episode is alive 61 bars after the sweep and keeps waiting for the retrace")
        void phasedKeepsIt() {
            StdvOteStrategy s = tapeLikeEpisode(SessionConfig.ExpiryAnchor.PHASED, 21);
            assertThat(s.getSetupContext().state).isEqualTo(SetupState.MSS_CONFIRMED);
            bars(s, 219);                                                                  // 240 after MSS
            assertThat(s.getSetupContext().state).isEqualTo(SetupState.MSS_CONFIRMED);
        }
    }

    @Nested
    @DisplayName("configuration (EngineConfig keys, runner wiring)")
    class Config {

        @Test
        @DisplayName("defaults 60/60/240; anchor PHASED in SCORING, BIAS_SET in BLOCKING; SWEEP_DONE is an alias of SWEEP_DONE_TOTAL")
        void defaultsAndParsing() {
            assertThat(SessionConfig.phaseBudgets()).isEqualTo(new SessionConfig.PhaseBudgets(60, 60, 240));
            assertThat(SessionConfig.expiryAnchor(SessionGateMode.SCORING)).isEqualTo(SessionConfig.ExpiryAnchor.PHASED);
            assertThat(SessionConfig.expiryAnchor(SessionGateMode.BLOCKING)).isEqualTo(SessionConfig.ExpiryAnchor.BIAS_SET);
            System.setProperty(SessionConfig.EXPIRY_ANCHOR, "SWEEP_DONE");
            assertThat(SessionConfig.expiryAnchor(SessionGateMode.SCORING)).isEqualTo(SessionConfig.ExpiryAnchor.SWEEP_DONE_TOTAL);
            System.setProperty(SessionConfig.EXPIRY_ANCHOR, "sweep_done_total");
            assertThat(SessionConfig.expiryAnchor(SessionGateMode.SCORING)).isEqualTo(SessionConfig.ExpiryAnchor.SWEEP_DONE_TOTAL);
            System.setProperty(SessionConfig.EXPIRY_ANCHOR, "PHASED");
            assertThat(SessionConfig.expiryAnchor(SessionGateMode.BLOCKING)).isEqualTo(SessionConfig.ExpiryAnchor.PHASED);
            System.setProperty(SessionConfig.EXPIRY_SWEEP_TO_DISPLACEMENT, "45");
            System.setProperty(SessionConfig.EXPIRY_DISPLACEMENT_TO_MSS, "30");
            System.setProperty(SessionConfig.EXPIRY_MSS_TO_OTE, "180");
            assertThat(SessionConfig.phaseBudgets()).isEqualTo(new SessionConfig.PhaseBudgets(45, 30, 180));
            assertThat(com.topstep.trading.config.EngineConfig.registered(SessionConfig.EXPIRY_MSS_TO_OTE)).isNotNull();
            assertThat(com.topstep.trading.config.EngineConfig.registered(SessionConfig.EXPIRY_ANCHOR)).isNotNull();
        }

        @Test
        @DisplayName("the REAL runner wires PHASED + the budgets by default, SWEEP_DONE_TOTAL on request")
        void runnerWiring() {
            new StdvOteRunnerStrategy("MNQ", null, new NullBus());
            StdvOteStrategy core = StdvOteRegistry.get("MNQ").orElseThrow();
            assertThat(core.expiryAnchor()).isEqualTo(SessionConfig.ExpiryAnchor.PHASED);
            assertThat(core.phaseBudgetBars()).containsExactly(60L, 60L, 240L);
            StdvOteRegistry.unregister("MNQ");

            System.setProperty(SessionConfig.EXPIRY_ANCHOR, "SWEEP_DONE_TOTAL");
            System.setProperty(SessionConfig.EXPIRY_MSS_TO_OTE, "120");
            new StdvOteRunnerStrategy("MNQ", null, new NullBus());
            core = StdvOteRegistry.get("MNQ").orElseThrow();
            assertThat(core.expiryAnchor()).isEqualTo(SessionConfig.ExpiryAnchor.SWEEP_DONE_TOTAL);
            assertThat(core.phaseBudgetBars()[2]).isEqualTo(120L);
        }

        @Test
        @DisplayName("BLOCKING keeps the pre-V5 BIAS_SET anchor (A/B unchanged)")
        void blockingUnchanged() {
            System.setProperty(SessionConfig.GATE_MODE, "BLOCKING");
            new StdvOteRunnerStrategy("MNQ", null, new NullBus());
            assertThat(StdvOteRegistry.get("MNQ").orElseThrow().expiryAnchor())
                    .isEqualTo(SessionConfig.ExpiryAnchor.BIAS_SET);
        }
    }
}
