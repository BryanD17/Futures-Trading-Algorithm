package com.topstep.trading.strategy;

/**
 * Enhanced tier system with multiple variants per tier level.
 * Each variant has different R:R targets and trailing stop configurations.
 *
 * VARIANT NAMING CONVENTION:
 * - TIER_[level][letter]_[style]
 * - A variants = Conservative (lower R:R, no trail or minimal trail)
 * - B variants = Aggressive (higher R:R, active trailing)
 *
 * Based on real trade analysis and probability research:
 * - 72% of setups reach 1R
 * - 38% of setups reach 3R
 * - 34% "almost winners" (reach 1R but not 3R) can be captured with trailing stops
 * - Expected value improvement: 0.52R → 1.11R per trade (+113%)
 */
public enum TradeTierVariant {

    // ═══════════════════════════════════════════════════════════════════════════
    // TIER 2 VARIANTS (Standard Setup - 3 confluences required)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * TIER 2A: Scalp/Conservative
     * - Quick exit, no trailing
     * - Use when: Late killzone, high volatility, near DLL, uncertain conditions
     */
    TIER_2A_SCALP(
        2,                          // tierLevel
        2.0,                        // riskRewardRatio
        "Scalp Exit",               // description
        TrailMode.NONE,             // trailMode
        new double[][]{{1.0, 0.50}, {2.0, 0.50}}  // partials: 50% at 1R, 50% at 2R
    ),

    /**
     * TIER 2B: Standard with Trail
     * - Normal R:R with protective trailing
     * - Use when: Normal conditions, adequate time in killzone
     */
    TIER_2B_STANDARD(
        2,
        3.0,
        "Standard Managed",
        TrailMode.NORMAL,           // Activate at 1.5R, trail 0.5R behind
        new double[][]{{1.0, 0.40}, {2.0, 0.30}, {3.0, 0.30}}
    ),

    // ═══════════════════════════════════════════════════════════════════════════
    // TIER 3 VARIANTS (Premium Setup - Breaker or 4+ confluences)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * TIER 3A: Secure/Conservative
     * - Locked profit target, no trail risk
     * - Use when: Late killzone, protecting gains, uncertain HTF
     */
    TIER_3A_SECURE(
        3,
        3.0,
        "Secure Exit",
        TrailMode.NONE,
        new double[][]{{1.0, 0.50}, {2.0, 0.25}, {3.0, 0.25}}
    ),

    /**
     * TIER 3B: Runner with Trail
     * - Extended target with trailing protection
     * - Use when: Strong displacement, high raid quality, early killzone
     */
    TIER_3B_RUNNER(
        3,
        4.0,
        "Runner Managed",
        TrailMode.STANDARD,         // Activate at 1.5R, trail 0.75R behind
        new double[][]{{1.0, 0.33}, {2.0, 0.33}, {4.0, 0.34}}
    ),

    // ═══════════════════════════════════════════════════════════════════════════
    // TIER 4 VARIANTS (Elite Setup - All confluences aligned)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * TIER 4A: Managed with Lenient Trail
     * - High target with wide trailing stop
     * - Use when: Elite setup but moderate confidence
     */
    TIER_4A_MANAGED(
        4,
        4.0,
        "Elite Managed",
        TrailMode.LENIENT,          // Activate at 2R, trail 1R behind
        new double[][]{{1.0, 0.25}, {2.0, 0.25}, {3.0, 0.25}, {4.0, 0.25}}
    ),

    /**
     * TIER 4B: Home Run with Aggressive Trail
     * - Maximum target with tight trailing for runners
     * - Use when: Perfect setup, all factors maxed, impulse start
     */
    TIER_4B_HOMERUN(
        4,
        5.0,
        "Home Run",
        TrailMode.AGGRESSIVE,       // Activate at 1.5R, trail 0.5R, time decay
        new double[][]{{1.0, 0.20}, {2.0, 0.20}, {3.0, 0.20}, {4.0, 0.20}, {5.0, 0.20}}
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // TRAIL MODE DEFINITIONS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Defines trailing stop behavior for each variant.
     */
    public enum TrailMode {
        /**
         * No trailing - fixed stop until target or stop hit.
         * Best for: Scalps, uncertain conditions, protecting capital.
         */
        NONE(
            0,      // activationR: N/A
            0,      // trailDistanceR: N/A
            1.0,    // breakevenR: Move to BE at 1R (still protects entry)
            false   // timeDecay: N/A
        ),

        /**
         * Normal trailing - balanced protection and room to run.
         * Best for: Standard setups, normal volatility.
         */
        NORMAL(
            1.5,    // activationR: Start trailing at 1.5R
            0.5,    // trailDistanceR: Trail 0.5R behind highest point
            1.0,    // breakevenR: Move to BE at 1R
            false   // timeDecay: No time-based tightening
        ),

        /**
         * Standard trailing - more room for setups that need it.
         * Best for: Higher tier setups, premium confluences.
         */
        STANDARD(
            1.5,    // activationR: Start trailing at 1.5R
            0.75,   // trailDistanceR: Trail 0.75R behind
            1.0,    // breakevenR: Move to BE at 1R
            false   // timeDecay: No time-based tightening
        ),

        /**
         * Lenient trailing - wide trail for volatile setups.
         * Best for: Elite setups, volatile instruments (GC, CL).
         */
        LENIENT(
            2.0,    // activationR: Start trailing at 2R (more room)
            1.0,    // trailDistanceR: Trail 1R behind (wide)
            1.0,    // breakevenR: Move to BE at 1R
            false   // timeDecay: No time-based tightening
        ),

        /**
         * Aggressive trailing - tight trail with time decay.
         * Best for: Perfect setups where you want to capture maximum move.
         */
        AGGRESSIVE(
            1.5,    // activationR: Start trailing at 1.5R
            0.5,    // trailDistanceR: Trail 0.5R behind (tight)
            1.0,    // breakevenR: Move to BE at 1R
            true    // timeDecay: Trail tightens over time
        );

        private final double activationR;
        private final double trailDistanceR;
        private final double breakevenR;
        private final boolean timeDecay;

        TrailMode(double activationR, double trailDistanceR, double breakevenR, boolean timeDecay) {
            this.activationR = activationR;
            this.trailDistanceR = trailDistanceR;
            this.breakevenR = breakevenR;
            this.timeDecay = timeDecay;
        }

        public double getActivationR() { return activationR; }
        public double getTrailDistanceR() { return trailDistanceR; }
        public double getBreakevenR() { return breakevenR; }
        public boolean hasTimeDecay() { return timeDecay; }
        public boolean isTrailingEnabled() { return this != NONE; }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // INSTANCE FIELDS
    // ═══════════════════════════════════════════════════════════════════════════

    private final int tierLevel;
    private final double riskRewardRatio;
    private final String description;
    private final TrailMode trailMode;
    private final double[][] partialProfitTargets;

    TradeTierVariant(int tierLevel, double riskRewardRatio, String description,
                     TrailMode trailMode, double[][] partialProfitTargets) {
        this.tierLevel = tierLevel;
        this.riskRewardRatio = riskRewardRatio;
        this.description = description;
        this.trailMode = trailMode;
        this.partialProfitTargets = partialProfitTargets;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GETTERS
    // ═══════════════════════════════════════════════════════════════════════════

    public int getTierLevel() { return tierLevel; }
    public double getRiskRewardRatio() { return riskRewardRatio; }
    public String getDescription() { return description; }
    public TrailMode getTrailMode() { return trailMode; }
    public double[][] getPartialProfitTargets() { return partialProfitTargets; }

    /**
     * Check if this variant uses trailing stops.
     */
    public boolean hasTrailingStop() {
        return trailMode.isTrailingEnabled();
    }

    /**
     * Get the R-multiple at which to move stop to breakeven.
     */
    public double getBreakevenActivationR() {
        return trailMode.getBreakevenR();
    }

    /**
     * Get the R-multiple at which trailing begins.
     */
    public double getTrailActivationR() {
        return trailMode.getActivationR();
    }

    /**
     * Get the trail distance as R-multiple.
     */
    public double getTrailDistanceR() {
        return trailMode.getTrailDistanceR();
    }

    /**
     * Check if time-based trail decay is enabled.
     */
    public boolean hasTimeDecay() {
        return trailMode.hasTimeDecay();
    }

    /**
     * Get minimum number of confluences required.
     * Updated: HTF trend counts as a mandatory confluence for all tiers.
     */
    public int getMinConfluences() {
        switch (tierLevel) {
            case 4: return 5;  // HTF Strong + Breaker + Power3 + SMT + Displacement
            case 3: return 4;  // HTF Trending + 5mZone/Breaker + Displacement + confirmation
            case 2: return 3;  // HTF Not Opposing + structure + Displacement + SMT
            default: return 2;
        }
    }

    @Override
    public String toString() {
        String trailInfo = trailMode == TrailMode.NONE ? "No Trail" :
            String.format("Trail@%.1fR (%.2fR dist)", trailMode.getActivationR(), trailMode.getTrailDistanceR());
        return String.format("Tier %d - %s (R:R 1:%.1f, %s)",
            tierLevel, description, riskRewardRatio, trailInfo);
    }

    /**
     * Get log-friendly display string.
     */
    public String toLogString() {
        return String.format("%s, R:R 1:%.1f", description, riskRewardRatio);
    }
}
