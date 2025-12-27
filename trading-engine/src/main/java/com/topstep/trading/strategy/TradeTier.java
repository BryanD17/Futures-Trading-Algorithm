package com.topstep.trading.strategy;

/**
 * Trade quality tiers that determine Risk-to-Reward ratios.
 *
 * Higher tiers have more confluences and warrant larger R:R targets.
 * The tier system allows the strategy to take more aggressive targets
 * when setup quality is high, while still taking lower-quality setups
 * with more conservative targets.
 */
public enum TradeTier {
    /**
     * Tier 3: Highest quality setup - best combination of confluences.
     * Requires: Breaker Block OR (IFVG + OB + Displacement) + Power of 3 confirmation
     * R:R Target: 1:4
     */
    TIER_3(3, 4.0, "Premium Setup"),

    /**
     * Tier 2: Medium quality setup.
     * Requires: Order Block + Displacement OR Unfilled FVG + SMT
     * R:R Target: 1:2
     */
    TIER_2(2, 2.0, "Standard Setup"),

    /**
     * Tier 1: Minimum viable setup.
     * Requires: Any FVG OR SMT divergence OR displacement at OTE
     * R:R Target: 1:1
     */
    TIER_1(1, 1.0, "Scalp Setup");

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
     */
    public double[][] getPartialProfitTargets() {
        switch (this) {
            case TIER_3:
                // At 1R close 50%, at 2R close 25%, at 4R close remaining 25%
                return new double[][] {{1.0, 0.50}, {2.0, 0.25}, {4.0, 0.25}};
            case TIER_2:
                // At 1R close 50%, at 2R close remaining 50%
                return new double[][] {{1.0, 0.50}, {2.0, 0.50}};
            case TIER_1:
                // At 1R close everything
                return new double[][] {{1.0, 1.0}};
            default:
                return new double[][] {{1.0, 1.0}};
        }
    }

    @Override
    public String toString() {
        return String.format("Tier %d (%s, R:R 1:%.1f)", level, description, riskRewardRatio);
    }
}
