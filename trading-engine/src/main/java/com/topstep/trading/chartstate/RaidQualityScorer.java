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
 * SCORING SYSTEM (1-10):
 *
 * TIER 1: TIMING FACTORS (max +3)
 * - Killzone timing: +2
 * - Silver Bullet window: +1
 * - Session overlap: +1
 *
 * TIER 2: LEVEL SIGNIFICANCE (max +2)
 * - PDH/PDL levels: +2
 * - Session extremes: +1
 * - Strong equal levels (cluster ≥3): +1
 *
 * TIER 3: CONFIRMATION FACTORS (max +3)
 * - SMT divergence: +2
 * - HTF bias alignment: +1
 *
 * TIER 4: PRICE ACTION QUALITY (max +2)
 * - Strong penetration: +1
 * - Strong rejection (wick ratio): +1
 *
 * PENALTIES:
 * - Opposing HTF bias: -2
 * - Low-probability timing: -1
 *
 * QUALITY INTERPRETATION:
 * - 8-10: Elite Setup - full size, aggressive entry
 * - 6-7: Premium Setup - full size, standard entry
 * - 4-5: Standard Setup - reduced size
 * - 1-3: Low Quality - SKIP
 */
public class RaidQualityScorer {

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
        int score = 0;
        List<String> factors = new ArrayList<>();

        // ═══════════════════════════════════════════════════════════════════
        // TIER 1: TIMING FACTORS (max +3)
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

        // Session overlap (+1, but don't double-count with killzone)
        if (context.isSessionOverlap() && !context.isInKillzone()) {
            score += 1;
            factors.add("✓ Session Overlap");
        }

        // ═══════════════════════════════════════════════════════════════════
        // TIER 2: LEVEL SIGNIFICANCE (max +2)
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
        // Session extremes (+1)
        else if (levelType.isSessionExtreme()) {
            score += 1;
            factors.add("✓ Session Extreme: " + levelType.getDisplayName());
        }
        // Equal highs/lows with cluster size 3+ (+1)
        else if (levelType.isEqualLevel() && raid.getTargetLevel().getClusterSize() >= 3) {
            score += 1;
            factors.add("✓ Strong Equal Level (cluster=" + raid.getTargetLevel().getClusterSize() + ")");
        }

        // ═══════════════════════════════════════════════════════════════════
        // TIER 3: CONFIRMATION FACTORS (max +3)
        // ═══════════════════════════════════════════════════════════════════

        // SMT divergence (+2)
        if (context.hasSmtDivergence()) {
            score += 2;
            factors.add("✓ SMT Divergence");
        }

        // HTF bias alignment (+1)
        boolean expectBullish = raid.getDirection().expectsBullish();
        if (context.htfBiasAligns(expectBullish)) {
            score += 1;
            factors.add("✓ HTF Bias Aligned");
        }

        // ═══════════════════════════════════════════════════════════════════
        // TIER 4: PRICE ACTION QUALITY (max +2)
        // ═══════════════════════════════════════════════════════════════════

        InstrumentRaidConfig config = InstrumentRaidConfig.forSymbol(raid.getInstrument());

        // Strong penetration (+1)
        if (raid.getPenetrationTicks() >= config.getStrongPenetrationTicks()) {
            score += 1;
            factors.add("✓ Strong Penetration (" + String.format("%.1f", raid.getPenetrationTicks()) + " ticks)");
        }

        // Strong rejection (wick ratio ≥ 0.6) (+1)
        if (raid.getWickToBodyRatio() >= 0.6) {
            score += 1;
            factors.add("✓ Strong Rejection (wick=" + (int)(raid.getWickToBodyRatio() * 100) + "%)");
        }

        // ═══════════════════════════════════════════════════════════════════
        // PENALTY FACTORS (can reduce score)
        // ═══════════════════════════════════════════════════════════════════

        // Opposing HTF bias (-2)
        if (context.htfBiasOpposes(expectBullish)) {
            score -= 2;
            factors.add("✗ PENALTY: Opposing HTF Bias (-2)");
        }

        // Outside killzone and not session overlap and not SB window (-1)
        if (!context.isInKillzone() && !context.isSessionOverlap() && !context.isInSilverBulletWindow()) {
            score -= 1;
            factors.add("✗ PENALTY: Low-probability timing (-1)");
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
     * This decouples the scorer from direct dependencies on other components.
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

        public RaidScoringContext(Instant timestamp,
                                  boolean inKillzone, String killzoneName, KillzonePhase killzonePhase,
                                  boolean inSilverBulletWindow, String silverBulletWindowName,
                                  boolean sessionOverlap,
                                  boolean hasSmtDivergence,
                                  Boolean htfBiasBullish) {
            this.timestamp = timestamp;
            this.inKillzone = inKillzone;
            this.killzoneName = killzoneName;
            this.killzonePhase = killzonePhase;
            this.inSilverBulletWindow = inSilverBulletWindow;
            this.silverBulletWindowName = silverBulletWindowName;
            this.sessionOverlap = sessionOverlap;
            this.hasSmtDivergence = hasSmtDivergence;
            this.htfBiasBullish = htfBiasBullish;
        }

        public Instant getTimestamp() { return timestamp; }
        public boolean isInKillzone() { return inKillzone; }
        public String getKillzoneName() { return killzoneName; }
        public KillzonePhase getKillzonePhase() { return killzonePhase; }
        public boolean isInSilverBulletWindow() { return inSilverBulletWindow; }
        public String getSilverBulletWindowName() { return silverBulletWindowName; }
        public boolean isSessionOverlap() { return sessionOverlap; }
        public boolean hasSmtDivergence() { return hasSmtDivergence; }

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

            public RaidScoringContext build() {
                return new RaidScoringContext(
                        timestamp, inKillzone, killzoneName, killzonePhase,
                        inSilverBulletWindow, silverBulletWindowName,
                        sessionOverlap, hasSmtDivergence, htfBiasBullish
                );
            }
        }
    }
}
