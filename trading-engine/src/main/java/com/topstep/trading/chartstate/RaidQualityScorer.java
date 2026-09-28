package com.topstep.trading.chartstate;

import com.topstep.trading.strategy.KillzoneClock;
import com.topstep.trading.strategy.KillzonePhase;
import com.topstep.trading.strategy.MultiTimeframeAnalyzer;
import com.topstep.trading.strategy.SilverBulletClock;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Calculates quality scores for liquidity raids.
 *
 * Not all raids are equal. A raid during the London killzone with SMT confirmation
 * and HTF alignment is far more likely to lead to a profitable reversal than a
 * raid during Asia with no confirmation.
 *
 * REVISED SCORING SYSTEM (1-10) - HTF TREND ANCHORED:
 *
 * The key insight: HTF trend alignment is the MOST IMPORTANT FACTOR.
 * Trading with institutional flow (HTF trend) dramatically increases win rate.
 * Trading against it, even with technically valid setups, fails more often.
 *
 * TIER 1: HTF TREND ALIGNMENT (max +3, penalty -4)
 * - HTF trend aligned (strong): +3  (up from +1 — this is THE dominant factor)
 * - HTF trend aligned (weak):   +2
 * - HTF trend opposing:         -4  (up from -2 — severely penalized)
 *
 * TIER 2: TIMING FACTORS (max +3)
 * - Killzone timing: +2
 * - Silver Bullet window: +1
 *
 * TIER 3: LEVEL SIGNIFICANCE (max +2)
 * - PDH/PDL/PWH/PWL levels: +2
 * - Session extremes: +1
 * - Strong equal levels (cluster >= 3): +1
 *
 * TIER 4: CONFIRMATION FACTORS (max +4)
 * - SMT divergence: +2
 * - 5m zone confluence (2+ structures overlapping): +2  (NEW)
 *
 * TIER 5: ENTRY QUALITY (max +2)
 * - 1m displacement confirmation: +2  (NEW)
 *
 * TIER 6: TARGET ALIGNMENT (max +2)
 * - PDH/PDL target alignment: +2  (NEW — trade points toward significant unswept level)
 *
 * PENALTIES:
 * - Opposing HTF bias: -4 (up from -2)
 * - Low-probability timing: -1
 *
 * QUALITY INTERPRETATION:
 * - 8-10: Elite Setup - full size, aggressive entry
 * - 6-7: Premium Setup - full size, standard entry
 * - 4-5: Standard Setup - reduced size
 * - 1-3: Low Quality - SKIP
 */
public class RaidQualityScorer {

    // ═══════════════════════════════════════════════════════════════════
    // V5 AGENT 03 RECALIBRATION (RC-07, D-10, D-11) — weight table
    //
    //   BASE: a detected sweep WITH rejection            +1  (NEW)
    //   HTF bias aligned (strong / weak)                 +3 / +2
    //   HTF bias opposing (SAME bias M2 uses)            -4
    //   Killzone / Silver Bullet                         +2 / +1
    //   PDH/PDL/PWH/PWL                                  +2
    //   Session extreme (Asia/London/NY AM/NY PM)        +2  (was +1)
    //   Equal level cluster >= 3                         +1
    //   Rejection strong / moderate                      +2 / +1  (NEW)
    //     strong   = wick >= 50% of the raid candle OR close back beyond
    //                the level by >= the instrument's strongPenetration ticks
    //     moderate = wick >= 30% OR close back beyond the level
    //   Premium/discount aligned (HIGH sweep above the   +1  (NEW)
    //     dealing-range EQ / LOW sweep below it)
    //   SMT +2, 5m zone +2/+1, displacement +2, target +2/+1,
    //   multi-touch +3/+1                                (unchanged)
    //   Low-probability timing                           -1  ONLY when
    //     session.gateMode=BLOCKING (a "wrong session" penalty contradicts
    //     SCORING mode — D-11)
    //
    // Reachability: a textbook sweep of a SESSION level with strong
    // rejection scores 1+2+2 = 5 = the MNQ floor with NO SMT, NO PDH/PDL,
    // NO killzone and even a NEUTRAL bias; bias-aligned it is 7.
    // ═══════════════════════════════════════════════════════════════════

    /** Wick share of the raid candle that counts as a STRONG rejection. */
    public static final double STRONG_WICK = 0.50;
    /** Wick share that counts as a MODERATE rejection. */
    public static final double MODERATE_WICK = 0.30;
    /** Close-back distance (ticks) that counts as strong when no config. */
    public static final double DEFAULT_STRONG_CLOSE_BACK_TICKS = 8.0;

    private final KillzoneClock killzoneClock;
    private final SilverBulletClock silverBulletClock;

    public RaidQualityScorer() {
        this.killzoneClock = new KillzoneClock();
        this.silverBulletClock = new SilverBulletClock();
    }

    /**
     * Calculate quality score for a raid.
     *
     * @param raid The liquidity raid to score
     * @param context Scoring context with market state
     * @return Quality score (1-10)
     */
    public int calculateScore(LiquidityRaid raid, RaidScoringContext context) {
        // Rollback: raid.weights=V4 restores the pre-V5 table exactly.
        final boolean v5 = !com.topstep.trading.strategy.stdvote.BiasConfig.legacyRaidWeights();
        int score = v5 ? 1 : 0;
        List<String> factors = new ArrayList<>();
        if (v5) factors.add("✓ BASE: level swept with rejection (+1)");

        // ═══════════════════════════════════════════════════════════════════
        // TIER 1: HTF TREND ALIGNMENT (THE DOMINANT FACTOR)
        // This is the most important factor based on multi-timeframe research.
        // Trading WITH institutional flow is the #1 determinant of success.
        // ═══════════════════════════════════════════════════════════════════

        boolean expectBullish = raid.getDirection().expectsBullish();

        if (context.isHtfTrendStrong() && context.htfBiasAligns(expectBullish)) {
            // Strong HTF trend aligned — highest weight
            score += 3;
            factors.add("★ HTF Strong Trend Aligned (+3)");
        } else if (context.htfBiasAligns(expectBullish)) {
            // Weak HTF alignment (bias matches but not strong trend)
            score += 2;
            factors.add("✓ HTF Bias Aligned (+2)");
        }

        // ═══════════════════════════════════════════════════════════════════
        // TIER 2: TIMING FACTORS (max +3)
        // ═══════════════════════════════════════════════════════════════════

        // Killzone timing (+2)
        if (context.isInKillzone()) {
            score += 2;
            factors.add("✓ Killzone: " + context.getKillzoneName());
        }

        // Silver Bullet window (+1)
        if (context.isInSilverBulletWindow()) {
            score += 1;
            factors.add("✓ Silver Bullet: " + context.getSilverBulletWindowName());
        }

        // ═══════════════════════════════════════════════════════════════════
        // TIER 3: LEVEL SIGNIFICANCE (max +2)
        // ═══════════════════════════════════════════════════════════════════

        LevelType levelType = raid.getTargetLevel().getType();

        // PDH/PDL are highest significance (+2)
        if (levelType == LevelType.PDH || levelType == LevelType.PDL) {
            score += 2;
            factors.add("✓ PDH/PDL Level (highest significance)");
        }
        // PWH/PWL also high significance (+2)
        else if (levelType == LevelType.PWH || levelType == LevelType.PWL) {
            score += 2;
            factors.add("✓ PWH/PWL Level (weekly)");
        }
        // Session extremes (+2 — V5 Agent 03: the owner's model trades
        // session highs/lows as primary liquidity)
        else if (levelType.isSessionExtreme()) {
            score += v5 ? 2 : 1;
            factors.add("✓ Session Extreme: " + levelType.getDisplayName() + (v5 ? " (+2)" : ""));
        }
        // Equal highs/lows with cluster size 3+ (+1)
        else if (levelType.isEqualLevel() && raid.getTargetLevel().getClusterSize() >= 3) {
            score += 1;
            factors.add("✓ Strong Equal Level (cluster=" + raid.getTargetLevel().getClusterSize() + ")");
        }

        // ═══════════════════════════════════════════════════════════════════
        // TIER 3b: REJECTION QUALITY (V5 Agent 03, max +2) — the wick /
        // close-back of the raid candle itself: the one thing every real
        // sweep has, in every session.
        // ═══════════════════════════════════════════════════════════════════
        boolean highSweep = raid.getDirection() == RaidDirection.HIGH_SWEEP;
        double level = raid.getTargetLevel().getPrice();
        if (v5) {
        boolean closedBack = highSweep
                ? raid.getRaidCandleClose() <= level
                : raid.getRaidCandleClose() >= level;
        double wick = raid.getWickToBodyRatio();
        double strongCloseBack = context.getStrongCloseBackTicks();
        if (wick >= STRONG_WICK || (closedBack && raid.getRejectionTicks() >= strongCloseBack)) {
            score += 2;
            factors.add(String.format("✓ Strong rejection (wick %.0f%%, close-back %.0f ticks) (+2)",
                    wick * 100, closedBack ? raid.getRejectionTicks() : 0.0));
        } else if (wick >= MODERATE_WICK || closedBack) {
            score += 1;
            factors.add(String.format("✓ Rejection (wick %.0f%%, closed back=%s) (+1)",
                    wick * 100, closedBack));
        }

        // Premium/discount location vs the dealing-range equilibrium (+1).
        double eq = context.getRangeEquilibrium();
        if (!Double.isNaN(eq)) {
            if (highSweep && level > eq) {
                score += 1;
                factors.add("✓ High swept in PREMIUM of dealing range (eq " + eq + ") (+1)");
            } else if (!highSweep && level < eq) {
                score += 1;
                factors.add("✓ Low swept in DISCOUNT of dealing range (eq " + eq + ") (+1)");
            }
        }
        } // v5

        // ═══════════════════════════════════════════════════════════════════
        // TIER 4: CONFIRMATION FACTORS (max +4)
        // ═══════════════════════════════════════════════════════════════════

        // SMT divergence (+2)
        if (context.hasSmtDivergence()) {
            score += 2;
            factors.add("✓ SMT Divergence");
        }

        // 5m zone confluence — 2+ structures overlapping at pullback zone (+2) (NEW)
        if (context.getZoneConfluenceScore() >= 2) {
            score += 2;
            factors.add("✓ 5m Zone Confluence (score=" + context.getZoneConfluenceScore() + ")");
        } else if (context.getZoneConfluenceScore() >= 1) {
            score += 1;
            factors.add("✓ 5m Zone Present (score=" + context.getZoneConfluenceScore() + ")");
        }

        // ═══════════════════════════════════════════════════════════════════
        // TIER 5: ENTRY QUALITY (max +2) (NEW)
        // ═══════════════════════════════════════════════════════════════════

        // 1m displacement confirmation with FVG + MSS (+2)
        if (context.hasDisplacementEntry()) {
            score += 2;
            factors.add("✓ 1m Displacement Entry (FVG+MSS)");
        }

        // ═══════════════════════════════════════════════════════════════════
        // TIER 6: TARGET ALIGNMENT (max +2) (NEW)
        // ═══════════════════════════════════════════════════════════════════

        // Trade points toward significant unswept liquidity (+2)
        if (context.getTargetAlignmentBonus() >= 2) {
            score += 2;
            factors.add("✓ PDH/PDL Target Aligned");
        } else if (context.getTargetAlignmentBonus() >= 1) {
            score += 1;
            factors.add("✓ Session Target Aligned");
        }

        // ═══════════════════════════════════════════════════════════════════
        // TIER 7: MULTI-TOUCH BONUS (max +3) (FIX 5)
        // ═══════════════════════════════════════════════════════════════════

        int touchCount = raid.getTargetLevel().getTouchCount();
        if (touchCount >= 3) {
            score += 3;
            factors.add("★ 3rd+ touch (" + touchCount + " touches) — highest probability (+3)");
        } else if (touchCount == 2) {
            score += 1;
            factors.add("✓ 2nd touch (" + touchCount + " touches) (+1)");
        }

        // ═══════════════════════════════════════════════════════════════════
        // PENALTY FACTORS (can reduce score)
        // ═══════════════════════════════════════════════════════════════════

        // Opposing HTF trend (-4) — severely penalized (up from -2)
        if (context.htfBiasOpposes(expectBullish)) {
            score -= 4;
            factors.add("✗ PENALTY: Opposing HTF Trend (-4)");
        }

        // Outside killzone and not session overlap and not SB window (-1) —
        // V5 Agent 03 (D-11): ONLY when session.gateMode=BLOCKING. In SCORING
        // mode the session is a bonus, never a penalty.
        if (!context.isInKillzone() && !context.isSessionOverlap() && !context.isInSilverBulletWindow()
                && (!v5 || !com.topstep.trading.strategy.stdvote.BiasConfig.sessionGateScoring())) {
            score -= 1;
            factors.add("✗ PENALTY: Low-probability timing (-1, session.gateMode=BLOCKING)");
        }

        // Clamp to 1-10 range
        score = Math.max(1, Math.min(10, score));

        // Update the raid with score and factors
        raid.setQualityScore(score, factors);

        return score;
    }

    /**
     * Quick check if timing is favorable for raid detection.
     * Used to pre-filter before detailed scoring.
     */
    public boolean isTimingFavorable(Instant timestamp) {
        return killzoneClock.isInKillzone(timestamp) ||
               silverBulletClock.isInSilverBulletWindow(timestamp);
    }

    /**
     * Get quality classification string.
     */
    public static String getClassification(int score) {
        if (score >= 8) return "ELITE";
        if (score >= 6) return "PREMIUM";
        if (score >= 4) return "STANDARD";
        return "LOW";
    }

    /**
     * Check if score meets minimum threshold for trading.
     */
    public static boolean meetsMinimumThreshold(int score, int minThreshold) {
        return score >= minThreshold;
    }

    // ═══════════════════════════════════════════════════════════════════
    // SCORING CONTEXT - Captures market state for scoring
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Context object containing market state for raid scoring.
     * Enhanced with multi-timeframe trend data, 5m zone confluence,
     * and liquidity target alignment.
     */
    public static class RaidScoringContext {
        private final Instant timestamp;
        private final boolean inKillzone;
        private final String killzoneName;
        private final KillzonePhase killzonePhase;
        private final boolean inSilverBulletWindow;
        private final String silverBulletWindowName;
        private final boolean sessionOverlap;
        private final boolean hasSmtDivergence;
        private final Boolean htfBiasBullish;  // null = neutral

        // NEW: Multi-timeframe trend enhancement fields
        private final boolean htfTrendStrong;           // Is the HTF trend strong (not weak/ranging)?
        private final int zoneConfluenceScore;          // 5m zone confluence count (0-5)
        private final boolean hasDisplacementEntry;     // 1m displacement with FVG+MSS
        private final int targetAlignmentBonus;         // Liquidity target alignment (0-2)
        // V5 Agent 03: dealing-range equilibrium (NaN = unknown) and the
        // instrument's strong close-back distance in ticks.
        private double rangeEquilibrium = Double.NaN;
        private double strongCloseBackTicks = DEFAULT_STRONG_CLOSE_BACK_TICKS;

        public RaidScoringContext(Instant timestamp,
                                  boolean inKillzone, String killzoneName, KillzonePhase killzonePhase,
                                  boolean inSilverBulletWindow, String silverBulletWindowName,
                                  boolean sessionOverlap,
                                  boolean hasSmtDivergence,
                                  Boolean htfBiasBullish) {
            this(timestamp, inKillzone, killzoneName, killzonePhase,
                 inSilverBulletWindow, silverBulletWindowName,
                 sessionOverlap, hasSmtDivergence, htfBiasBullish,
                 false, 0, false, 0);
        }

        public RaidScoringContext(Instant timestamp,
                                  boolean inKillzone, String killzoneName, KillzonePhase killzonePhase,
                                  boolean inSilverBulletWindow, String silverBulletWindowName,
                                  boolean sessionOverlap,
                                  boolean hasSmtDivergence,
                                  Boolean htfBiasBullish,
                                  boolean htfTrendStrong,
                                  int zoneConfluenceScore,
                                  boolean hasDisplacementEntry,
                                  int targetAlignmentBonus) {
            this.timestamp = timestamp;
            this.inKillzone = inKillzone;
            this.killzoneName = killzoneName;
            this.killzonePhase = killzonePhase;
            this.inSilverBulletWindow = inSilverBulletWindow;
            this.silverBulletWindowName = silverBulletWindowName;
            this.sessionOverlap = sessionOverlap;
            this.hasSmtDivergence = hasSmtDivergence;
            this.htfBiasBullish = htfBiasBullish;
            this.htfTrendStrong = htfTrendStrong;
            this.zoneConfluenceScore = zoneConfluenceScore;
            this.hasDisplacementEntry = hasDisplacementEntry;
            this.targetAlignmentBonus = targetAlignmentBonus;
        }

        public Instant getTimestamp() { return timestamp; }
        public boolean isInKillzone() { return inKillzone; }
        public String getKillzoneName() { return killzoneName; }
        public KillzonePhase getKillzonePhase() { return killzonePhase; }
        public boolean isInSilverBulletWindow() { return inSilverBulletWindow; }
        public String getSilverBulletWindowName() { return silverBulletWindowName; }
        public boolean isSessionOverlap() { return sessionOverlap; }
        public boolean hasSmtDivergence() { return hasSmtDivergence; }
        public boolean isHtfTrendStrong() { return htfTrendStrong; }
        public int getZoneConfluenceScore() { return zoneConfluenceScore; }
        public boolean hasDisplacementEntry() { return hasDisplacementEntry; }
        public int getTargetAlignmentBonus() { return targetAlignmentBonus; }
        public double getRangeEquilibrium() { return rangeEquilibrium; }
        public double getStrongCloseBackTicks() { return strongCloseBackTicks; }

        public boolean htfBiasAligns(boolean expectBullish) {
            if (htfBiasBullish == null) return false;  // Neutral doesn't align
            return htfBiasBullish == expectBullish;
        }

        public boolean htfBiasOpposes(boolean expectBullish) {
            if (htfBiasBullish == null) return false;  // Neutral doesn't oppose
            return htfBiasBullish != expectBullish;
        }

        /**
         * Builder for creating context from live market state.
         */
        public static class Builder {
            private Instant timestamp;
            private boolean inKillzone;
            private String killzoneName;
            private KillzonePhase killzonePhase;
            private boolean inSilverBulletWindow;
            private String silverBulletWindowName;
            private boolean sessionOverlap;
            private boolean hasSmtDivergence;
            private Boolean htfBiasBullish;
            private boolean htfTrendStrong;
            private int zoneConfluenceScore;
            private boolean hasDisplacementEntry;
            private int targetAlignmentBonus;
            private double rangeEquilibrium = Double.NaN;
            private double strongCloseBackTicks = DEFAULT_STRONG_CLOSE_BACK_TICKS;

            /** V5 Agent 03: dealing-range equilibrium (NaN = unknown). */
            public Builder rangeEquilibrium(double eq) {
                this.rangeEquilibrium = eq;
                return this;
            }

            /** V5 Agent 03: strong close-back distance in ticks. */
            public Builder strongCloseBackTicks(double ticks) {
                this.strongCloseBackTicks = ticks;
                return this;
            }

            public Builder timestamp(Instant timestamp) {
                this.timestamp = timestamp;
                return this;
            }

            public Builder killzone(boolean inKillzone, String name, KillzonePhase phase) {
                this.inKillzone = inKillzone;
                this.killzoneName = name;
                this.killzonePhase = phase;
                return this;
            }

            public Builder silverBullet(boolean inWindow, String windowName) {
                this.inSilverBulletWindow = inWindow;
                this.silverBulletWindowName = windowName;
                return this;
            }

            public Builder sessionOverlap(boolean overlap) {
                this.sessionOverlap = overlap;
                return this;
            }

            public Builder smtDivergence(boolean hasSmt) {
                this.hasSmtDivergence = hasSmt;
                return this;
            }

            public Builder htfBias(Boolean bullish) {
                this.htfBiasBullish = bullish;
                return this;
            }

            public Builder htfTrendStrong(boolean strong) {
                this.htfTrendStrong = strong;
                return this;
            }

            public Builder zoneConfluence(int score) {
                this.zoneConfluenceScore = score;
                return this;
            }

            public Builder displacementEntry(boolean hasEntry) {
                this.hasDisplacementEntry = hasEntry;
                return this;
            }

            public Builder targetAlignment(int bonus) {
                this.targetAlignmentBonus = bonus;
                return this;
            }

            public RaidScoringContext build() {
                RaidScoringContext c = new RaidScoringContext(
                        timestamp, inKillzone, killzoneName, killzonePhase,
                        inSilverBulletWindow, silverBulletWindowName,
                        sessionOverlap, hasSmtDivergence, htfBiasBullish,
                        htfTrendStrong, zoneConfluenceScore, hasDisplacementEntry,
                        targetAlignmentBonus
                );
                c.rangeEquilibrium = rangeEquilibrium;
                c.strongCloseBackTicks = strongCloseBackTicks;
                return c;
            }
        }
    }
}
