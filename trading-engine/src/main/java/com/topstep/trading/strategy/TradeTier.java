package com.topstep.trading.strategy;

/**
 * Trade quality tiers that determine Risk-to-Reward ratios.
 *
 * Higher tiers have more confluences and warrant larger R:R targets.
 * The tier system allows the strategy to take more aggressive targets
 * when setup quality is high, while requiring minimum confluence for lower tiers.
 *
 * IMPORTANT: Minimum R:R of 2.0 is required by PropFirmRiskEngine, so all tiers
 * must have R:R >= 2.0 to be executed.
 *
 * MULTI-TIMEFRAME TREND INTEGRATION:
 * Every tier now requires the 3-Layer Cascade to pass:
 *   Layer 1 (HTF Trend): Must allow trade direction — MANDATORY for ALL tiers
 *   Layer 2 (5m Zone):   Score >= 2 boosts to Tier 3+; Score >= 1 boosts quality
 *   Layer 3 (1m Displacement): Full trigger (FVG+MSS) boosts tier evaluation
 *
 * Tier 4: STRONG HTF Trend + Breaker + Power3 + SMT + Displacement + 5m Zone + Liquidity Target
 * Tier 3: HTF Trending + 5m Zone(2+) + Displacement + (SMT OR Power3)
 *      OR: HTF Trending + Breaker + Displacement + confirmation
 * Tier 2: HTF Not Opposing + OB/FVG + Displacement + SMT
 *      OR: HTF Trending + 5m Zone(1+) + Displacement
 * Tier 1: DISABLED
 */
public enum TradeTier {
    /**
     * Tier 4: Elite quality setup - maximum confluence combination.
     * Requires: STRONG HTF Trend + Breaker Block + Power of 3 + SMT Divergence + Displacement
     *           + 5m Continuation Zone (2+) + Liquidity Target Aligned
     * R:R Target: 1:5
     * This is the highest quality setup with all major confluences + multi-timeframe alignment.
     */
    TIER_4(4, 5.0, "Elite Setup"),

    /**
     * Tier 3: Premium quality setup - HTF trend confirmed + strong structure confluences.
     * Requires: HTF Trending + 5m Zone(2+) + Displacement + (SMT OR Power3)
     *       OR: HTF Trending + Breaker + Displacement + (SMT OR Power3)
     *       OR: HTF Trending + IFVG + OB + Displacement + Power3
     * R:R Target: 1:4
     */
    TIER_3(3, 4.0, "Premium Setup"),

    /**
     * Tier 2: Standard quality setup - strong confluence with HTF not opposing.
     * Requires: HTF Not Opposing + (Order Block + Displacement + SMT)
     *       OR: HTF Not Opposing + (Unfilled FVG + SMT + Displacement)
     *       OR: HTF Not Opposing + (Fresh Mitigation + SMT)
     *       OR: HTF Trending + 5m Zone(1+) + Displacement
     * R:R Target: 1:3
     */
    TIER_2(2, 3.0, "Standard Setup"),

    /**
     * Tier 1: Confirmed setup - double confluence required.
     * Requires: Any TWO of: (FVG + SMT), (FVG + Displacement), (SMT + Displacement)
     *       OR: (Order Block + any confirmation)
     * R:R Target: 1:2
     * Note: Single confluence is no longer accepted - must have confirmation.
     * Note: DISABLED in production — Tier 2 is the minimum acceptable tier.
     */
    TIER_1(1, 2.0, "Confirmed Setup");

    private final int level;
    private final double riskRewardRatio;
    private final String description;

    TradeTier(int level, double riskRewardRatio, String description) {
        this.level = level;
        this.riskRewardRatio = riskRewardRatio;
        this.description = description;
    }

    public int getLevel() {
        return level;
    }

    public double getRiskRewardRatio() {
        return riskRewardRatio;
    }

    public String getDescription() {
        return description;
    }

    /**
     * Get the partial profit targets for this tier.
     * Returns array of [R-multiple, percentage to close]
     *
     * Strategy:
     * - Take first profits at 1R to lock in gains and move stop to breakeven
     * - Scale out at intermediate levels
     * - Let remaining position run to full target
     */
    public double[][] getPartialProfitTargets() {
        switch (this) {
            case TIER_4:
                // Elite: At 1R close 40%, at 2R close 20%, at 3R close 20%, at 5R close remaining 20%
                return new double[][] {{1.0, 0.40}, {2.0, 0.20}, {3.0, 0.20}, {5.0, 0.20}};
            case TIER_3:
                // Premium: At 1R close 40%, at 2R close 30%, at 4R close remaining 30%
                return new double[][] {{1.0, 0.40}, {2.0, 0.30}, {4.0, 0.30}};
            case TIER_2:
                // Standard: At 1R close 50%, at 2R close 25%, at 3R close remaining 25%
                return new double[][] {{1.0, 0.50}, {2.0, 0.25}, {3.0, 0.25}};
            case TIER_1:
                // Confirmed: At 1R close 50%, at 2R close remaining 50%
                return new double[][] {{1.0, 0.50}, {2.0, 0.50}};
            default:
                return new double[][] {{1.0, 0.50}, {2.0, 0.50}};
        }
    }

    /**
     * Get the tier multiplier for adjusting R:R based on market conditions.
     * Higher tiers have slightly lower multipliers to account for longer hold times.
     */
    public double getTierMultiplier() {
        switch (this) {
            case TIER_4:
                return 0.85;  // 5.0 * 0.85 = 4.25 effective minimum
            case TIER_3:
                return 0.90;  // 4.0 * 0.90 = 3.6 effective minimum
            case TIER_2:
                return 0.95;  // 3.0 * 0.95 = 2.85 effective minimum
            case TIER_1:
                return 1.0;   // 2.0 * 1.0 = 2.0 effective minimum
            default:
                return 1.0;
        }
    }

    /**
     * Get the minimum number of confluences required for this tier.
     * Now includes HTF trend as a mandatory confluence for all tiers.
     */
    public int getMinConfluences() {
        switch (this) {
            case TIER_4:
                return 5;  // HTF Strong + Breaker + Power3 + SMT + Displacement (+ 5mZone + LiqTarget optional)
            case TIER_3:
                return 4;  // HTF Trending + 5mZone/Breaker + Displacement + confirmation
            case TIER_2:
                return 3;  // HTF Not Opposing + OB/FVG + Displacement + SMT
            case TIER_1:
                return 2;  // Double confluence required
            default:
                return 2;
        }
    }

    /**
     * Check if HTF trend alignment is required for this tier.
     * Answer: YES for ALL tiers — the 3-layer cascade is mandatory.
     */
    public boolean requiresHtfTrend() {
        return true;  // All tiers require Layer 1 (HTF trend) to pass
    }

    /**
     * Check if this tier requires a strong HTF trend (vs. weak trending).
     */
    public boolean requiresStrongHtfTrend() {
        return this == TIER_4;  // Only Tier 4 requires STRONG
    }

    /**
     * Check if this tier benefits from 5m continuation zone.
     */
    public boolean benefitsFrom5mZone() {
        return true;  // All tiers benefit; score >= 2 can promote to Tier 3
    }

    @Override
    public String toString() {
        return String.format("Tier %d (%s, R:R 1:%.1f)", level, description, riskRewardRatio);
    }
}
