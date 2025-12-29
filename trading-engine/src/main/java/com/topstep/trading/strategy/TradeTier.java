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
 */
public enum TradeTier {
    /**
     * Tier 4: Elite quality setup - maximum confluence combination.
     * Requires: Breaker Block + Power of 3 + SMT Divergence + Displacement (ALL required)
     * R:R Target: 1:5
     * This is the highest quality setup with all major confluences aligned.
     */
    TIER_4(4, 5.0, "Elite Setup"),

    /**
     * Tier 3: Premium quality setup - best combination of confluences.
     * Requires: Breaker Block + (SMT OR Displacement OR Power3)
     *       OR: (IFVG + OB + Displacement + Power3)
     * R:R Target: 1:4
     */
    TIER_3(3, 4.0, "Premium Setup"),

    /**
     * Tier 2: Standard quality setup - strong confluence.
     * Requires: (Order Block + Displacement + SMT)
     *       OR: (Unfilled FVG + SMT + Displacement)
     *       OR: (Fresh Mitigation + SMT)
     * R:R Target: 1:3
     */
    TIER_2(2, 3.0, "Standard Setup"),

    /**
     * Tier 1: Confirmed setup - double confluence required.
     * Requires: Any TWO of: (FVG + SMT), (FVG + Displacement), (SMT + Displacement)
     *       OR: (Order Block + any confirmation)
     * R:R Target: 1:2
     * Note: Single confluence is no longer accepted - must have confirmation.
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
     */
    public int getMinConfluences() {
        switch (this) {
            case TIER_4:
                return 4;  // Breaker + Power3 + SMT + Displacement
            case TIER_3:
                return 3;  // Breaker + 1 confirmation OR IFVG + OB + Displacement + Power3
            case TIER_2:
                return 3;  // OB + Displacement + SMT OR FVG + SMT + Displacement
            case TIER_1:
                return 2;  // Double confluence required
            default:
                return 2;
        }
    }

    @Override
    public String toString() {
        return String.format("Tier %d (%s, R:R 1:%.1f)", level, description, riskRewardRatio);
    }
}
