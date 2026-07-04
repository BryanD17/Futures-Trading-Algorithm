package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.strategy.stdvote.ScalpTargetCalculator.Decision;
import com.topstep.trading.strategy.stdvote.ScalpTargetCalculator.Source;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * SA3 unit tests for the pure {@link ScalpTargetCalculator}.
 *
 * MNQ geometry throughout: tick 0.25. Default config: 2-tick minimum
 * clearance, 1.5R candidate window, hard 1R cap.
 */
@DisplayName("ScalpTargetCalculator (SA3 scalp target model)")
class ScalpTargetCalculatorTest {

    private static final double TICK = 0.25;

    private ScalpTargetCalculator calc() {
        return new ScalpTargetCalculator(
                ScalpConfig.DEFAULT_MIN_TARGET_CLEARANCE_TICKS,
                ScalpConfig.DEFAULT_CANDIDATE_WINDOW_R);
    }

    // ──────────────────────────────────────────────────────────────────────
    // (a) The target NEVER exceeds 1R — randomized sweep
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("randomized: accepted target never exceeds 1R, long or short")
    void targetNeverExceedsOneR_randomized() {
        Random rnd = new Random(20260703L); // fixed seed — deterministic
        ScalpTargetCalculator c = calc();
        int accepted = 0;
        for (int i = 0; i < 5_000; i++) {
            boolean bullish = rnd.nextBoolean();
            double entry = 21000.0 + rnd.nextInt(400) * TICK;
            int riskTicks = 1 + rnd.nextInt(80);                 // 0.25 .. 20 pts
            double risk = riskTicks * TICK;
            double stop = bullish ? entry - risk : entry + risk;
            // Candidates anywhere within +/- 3R of entry, either side, or absent.
            Double a = rnd.nextInt(5) == 0 ? null
                    : entry + (rnd.nextDouble() * 6 - 3) * risk;
            Double b = rnd.nextInt(5) == 0 ? null
                    : entry + (rnd.nextDouble() * 6 - 3) * risk;

            Decision d = c.computeTarget(entry, stop, bullish, TICK, a, b);
            if (!d.accepted()) continue;
            accepted++;
            double dist = bullish ? d.targetPrice() - entry : entry - d.targetPrice();
            assertThat(dist)
                    .as("case %d: dist %s must be in (0, 1R=%s]", i, dist, risk)
                    .isGreaterThan(0)
                    .isLessThanOrEqualTo(risk + 1e-9);
            assertThat(d.rMultiple()).isLessThanOrEqualTo(
                    ScalpTargetCalculator.TARGET_CAP_R + 1e-9);
            // Target always on the tick grid.
            assertThat(Math.abs(d.targetPrice() / TICK
                    - Math.round(d.targetPrice() / TICK))).isLessThan(1e-6);
        }
        assertThat(accepted).as("sweep must exercise accepted cases").isGreaterThan(1000);
    }

    @Test
    @DisplayName("candidate between 1R and 1.5R is valid but hard-capped to exactly 1R")
    void candidateBeyondOneRIsCapped() {
        // Long: entry 21023, stop 21011 → risk 12. Liquidity at +1.25R (21038).
        Decision d = calc().computeTarget(21023.0, 21011.0, true, TICK, 21038.0, null);
        assertThat(d.accepted()).isTrue();
        assertThat(d.source()).isEqualTo(Source.OPPOSING_LIQUIDITY);
        assertThat(d.targetPrice()).isEqualTo(21035.0); // entry + exactly 1R
        assertThat(d.rMultiple()).isCloseTo(1.0, within(1e-9));
    }

    // ──────────────────────────────────────────────────────────────────────
    // (b) Closer-candidate selection
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("closer candidate wins: liquidity nearer than FVG origin")
    void closerCandidateWins_liquidity() {
        // Long, risk 12: A at +6 (0.5R), B at +9 (0.75R) → A.
        Decision d = calc().computeTarget(21023.0, 21011.0, true, TICK, 21029.0, 21032.0);
        assertThat(d.accepted()).isTrue();
        assertThat(d.source()).isEqualTo(Source.OPPOSING_LIQUIDITY);
        assertThat(d.targetPrice()).isEqualTo(21029.0);
        assertThat(d.rMultiple()).isCloseTo(0.5, within(1e-9));
    }

    @Test
    @DisplayName("closer candidate wins: FVG origin nearer than liquidity")
    void closerCandidateWins_fvgOrigin() {
        // Long, risk 12: A at +10, B at +4 (0.333R) → B.
        Decision d = calc().computeTarget(21023.0, 21011.0, true, TICK, 21033.0, 21027.0);
        assertThat(d.accepted()).isTrue();
        assertThat(d.source()).isEqualTo(Source.FVG_ORIGIN);
        assertThat(d.targetPrice()).isEqualTo(21027.0);
    }

    @Test
    @DisplayName("exact tie prefers the liquidity candidate (deterministic)")
    void tiePrefersLiquidity() {
        Decision d = calc().computeTarget(21023.0, 21011.0, true, TICK, 21030.0, 21030.0);
        assertThat(d.accepted()).isTrue();
        assertThat(d.source()).isEqualTo(Source.OPPOSING_LIQUIDITY);
        assertThat(d.targetPrice()).isEqualTo(21030.0);
    }

    @Test
    @DisplayName("short: closer candidate below entry wins and price mirrors")
    void shortCloserCandidate() {
        // Short: entry 21000, stop 21012 → risk 12. A at -8 (20992), B at -5 (20995).
        Decision d = calc().computeTarget(21000.0, 21012.0, false, TICK, 20992.0, 20995.0);
        assertThat(d.accepted()).isTrue();
        assertThat(d.source()).isEqualTo(Source.FVG_ORIGIN);
        assertThat(d.targetPrice()).isEqualTo(20995.0);
    }

    // ──────────────────────────────────────────────────────────────────────
    // (c) No valid candidate within 1.5R → exactly 1R fallback
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("no candidates at all → exactly 1R fallback")
    void noCandidatesFallsBackToOneR() {
        Decision d = calc().computeTarget(21023.0, 21011.0, true, TICK, null, null);
        assertThat(d.accepted()).isTrue();
        assertThat(d.source()).isEqualTo(Source.ONE_R_FALLBACK);
        assertThat(d.targetPrice()).isEqualTo(21035.0); // entry + exactly 1R (12.0)
        assertThat(d.rMultiple()).isCloseTo(1.0, within(1e-9));
    }

    @Test
    @DisplayName("all candidates beyond 1.5R → exactly 1R fallback")
    void candidatesBeyondWindowFallBackToOneR() {
        // risk 12 → window 18. Candidates at +19 and +25: both invalid.
        Decision d = calc().computeTarget(21023.0, 21011.0, true, TICK, 21042.0, 21048.0);
        assertThat(d.accepted()).isTrue();
        assertThat(d.source()).isEqualTo(Source.ONE_R_FALLBACK);
        assertThat(d.targetPrice()).isEqualTo(21035.0);
    }

    @Test
    @DisplayName("candidate exactly at the 1.5R window edge is still valid (then capped)")
    void candidateExactlyAtWindowEdge() {
        // risk 12 → window 18; candidate at exactly +18 → valid → capped to 1R.
        Decision d = calc().computeTarget(21023.0, 21011.0, true, TICK, 21041.0, null);
        assertThat(d.accepted()).isTrue();
        assertThat(d.source()).isEqualTo(Source.OPPOSING_LIQUIDITY);
        assertThat(d.targetPrice()).isEqualTo(21035.0);
    }

    @Test
    @DisplayName("short fallback mirrors: entry - exactly 1R")
    void shortFallback() {
        Decision d = calc().computeTarget(21000.0, 21012.0, false, TICK, null, null);
        assertThat(d.accepted()).isTrue();
        assertThat(d.source()).isEqualTo(Source.ONE_R_FALLBACK);
        assertThat(d.targetPrice()).isEqualTo(20988.0);
    }

    // ──────────────────────────────────────────────────────────────────────
    // (d) Rejection edge cases (all reason-logged)
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("stop distance zero → rejected with reason")
    void zeroStopDistanceRejected() {
        Decision d = calc().computeTarget(21023.0, 21023.0, true, TICK, 21030.0, null);
        assertThat(d.accepted()).isFalse();
        assertThat(d.source()).isEqualTo(Source.NONE);
        assertThat(d.reason()).contains("stop distance zero/negative");
        assertThat(d.targetPrice()).isNaN();
    }

    @Test
    @DisplayName("stop on the wrong side (negative risk) → rejected, long and short")
    void negativeStopDistanceRejected() {
        // Long with stop ABOVE entry.
        Decision dl = calc().computeTarget(21023.0, 21030.0, true, TICK, 21040.0, null);
        assertThat(dl.accepted()).isFalse();
        assertThat(dl.reason()).contains("stop distance zero/negative");
        // Short with stop BELOW entry.
        Decision ds = calc().computeTarget(21023.0, 21015.0, false, TICK, 21000.0, null);
        assertThat(ds.accepted()).isFalse();
        assertThat(ds.reason()).contains("stop distance zero/negative");
    }

    @Test
    @DisplayName("target inside entry: sub-clearance 1R fallback is rejected")
    void targetInsideEntryRejected() {
        // Risk of 1 tick → fallback target 1 tick past entry < 2-tick clearance.
        Decision d = calc().computeTarget(21023.0, 21022.75, true, TICK, null, null);
        assertThat(d.accepted()).isFalse();
        assertThat(d.reason()).contains("target at/inside entry");
    }

    @Test
    @DisplayName("candidate under 2 ticks past entry does not count as a target")
    void candidateInsideClearanceExcluded() {
        // Candidate at +1 tick — not valid; risk 12 → fallback to 1R instead.
        Decision d = calc().computeTarget(21023.0, 21011.0, true, TICK, 21023.25, null);
        assertThat(d.accepted()).isTrue();
        assertThat(d.source()).isEqualTo(Source.ONE_R_FALLBACK);
        assertThat(d.targetPrice()).isEqualTo(21035.0);
    }

    @Test
    @DisplayName("candidate at exactly 2 ticks past entry is a valid target")
    void candidateAtExactClearanceAccepted() {
        Decision d = calc().computeTarget(21023.0, 21011.0, true, TICK, 21023.5, null);
        assertThat(d.accepted()).isTrue();
        assertThat(d.source()).isEqualTo(Source.OPPOSING_LIQUIDITY);
        assertThat(d.targetPrice()).isEqualTo(21023.5);
    }

    @Test
    @DisplayName("wrong-side candidate is excluded (not a rejection) — other candidate used")
    void wrongSideExcludedNotRejected() {
        // Long: liquidity BELOW entry (wrong side), FVG origin above → FVG used.
        Decision d = calc().computeTarget(21023.0, 21011.0, true, TICK, 21015.0, 21028.0);
        assertThat(d.accepted()).isTrue();
        assertThat(d.source()).isEqualTo(Source.FVG_ORIGIN);
        assertThat(d.targetPrice()).isEqualTo(21028.0);
    }

    @Test
    @DisplayName("both candidates wrong side → 1R fallback, not a rejection")
    void bothWrongSideFallsBack() {
        Decision d = calc().computeTarget(21023.0, 21011.0, true, TICK, 21015.0, 21020.0);
        assertThat(d.accepted()).isTrue();
        assertThat(d.source()).isEqualTo(Source.ONE_R_FALLBACK);
        assertThat(d.targetPrice()).isEqualTo(21035.0);
    }

    @Test
    @DisplayName("invalid tick size → rejected")
    void invalidTickSizeRejected() {
        Decision d = calc().computeTarget(21023.0, 21011.0, true, 0.0, 21030.0, null);
        assertThat(d.accepted()).isFalse();
        assertThat(d.reason()).contains("invalid tick size");
    }

    // ──────────────────────────────────────────────────────────────────────
    // Tick-grid + config plumbing
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("off-grid candidate distance floors to the tick grid (never rounds past)")
    void offGridCandidateFloorsToTick() {
        // Candidate at +5.10 on a 0.25 grid → distance floors to 5.00.
        Decision d = calc().computeTarget(21023.0, 21011.0, true, TICK, 21028.10, null);
        assertThat(d.accepted()).isTrue();
        assertThat(d.targetPrice()).isEqualTo(21028.0);
    }

    @Test
    @DisplayName("MGC tick (0.10) geometry works the same")
    void mgcTickSize() {
        // Long gold micro: entry 3305.0, stop 3303.0 → risk 2.0.
        // Liquidity at 3306.4 (0.7R) → taken as-is.
        Decision d = calc().computeTarget(3305.0, 3303.0, true, 0.10, 3306.4, null);
        assertThat(d.accepted()).isTrue();
        assertThat(d.targetPrice()).isCloseTo(3306.4, within(1e-9));
        assertThat(d.rMultiple()).isCloseTo(0.7, within(1e-6));
    }

    @Test
    @DisplayName("constructor rejects nonsense configuration")
    void constructorValidation() {
        assertThatThrownBy(() -> new ScalpTargetCalculator(-1, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScalpTargetCalculator(2, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("wider candidate window admits farther candidates (config-driven)")
    void configurableWindow() {
        // Window 3R: candidate at 2R valid → capped to 1R (still never >1R).
        ScalpTargetCalculator wide = new ScalpTargetCalculator(2, 3.0);
        Decision d = wide.computeTarget(21023.0, 21011.0, true, TICK, 21047.0, null);
        assertThat(d.accepted()).isTrue();
        assertThat(d.source()).isEqualTo(Source.OPPOSING_LIQUIDITY);
        assertThat(d.targetPrice()).isEqualTo(21035.0); // capped at 1R
    }
}
