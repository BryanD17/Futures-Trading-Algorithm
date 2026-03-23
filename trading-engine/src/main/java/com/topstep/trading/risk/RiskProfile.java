package com.topstep.trading.risk;

import com.topstep.trading.lifecycle.AccountPhase;

/**
 * Phase-specific risk configuration that replaces static risk parameters.
 * Each account phase gets a different RiskProfile with appropriate
 * risk limits, quality gates, and zone multipliers.
 */
public class RiskProfile {

    private final AccountPhase phase;
    private final double baseRiskPct;           // Base risk as % of starting balance
    private final double maxDailyLossPct;        // Self-imposed daily loss limit as % of DLL
    private final int maxTradesPerDay;
    private final int maxConsecutiveLosses;      // Pause trading after this many consecutive losses
    private final double minRewardToRisk;
    private final double maxRewardToRisk;
    private final int minSetupQuality;           // Minimum quality score to take a trade (0-10)
    private final int cruiseMinQuality;          // Quality gate when in CRUISE zone

    // Zone risk multipliers
    private final double cruiseRiskMultiplier;
    private final double protectionRiskMultiplier;
    private final double cautionRiskMultiplier;
    private final double dangerRiskMultiplier;

    // Hard floors
    private final double maxRiskBudgetPct;       // Max % of remaining risk budget per trade
    private final double maxDailyLimitPct;       // Max % of remaining daily limit per trade

    // Partial profit configuration
    private final boolean partialsEnabled;
    private final double partialExitRMultiple;   // R-multiple at which to take partial
    private final double partialExitPct;         // Percentage of position to close

    private RiskProfile(Builder builder) {
        this.phase = builder.phase;
        this.baseRiskPct = builder.baseRiskPct;
        this.maxDailyLossPct = builder.maxDailyLossPct;
        this.maxTradesPerDay = builder.maxTradesPerDay;
        this.maxConsecutiveLosses = builder.maxConsecutiveLosses;
        this.minRewardToRisk = builder.minRewardToRisk;
        this.maxRewardToRisk = builder.maxRewardToRisk;
        this.minSetupQuality = builder.minSetupQuality;
        this.cruiseMinQuality = builder.cruiseMinQuality;
        this.cruiseRiskMultiplier = builder.cruiseRiskMultiplier;
        this.protectionRiskMultiplier = builder.protectionRiskMultiplier;
        this.cautionRiskMultiplier = builder.cautionRiskMultiplier;
        this.dangerRiskMultiplier = builder.dangerRiskMultiplier;
        this.maxRiskBudgetPct = builder.maxRiskBudgetPct;
        this.maxDailyLimitPct = builder.maxDailyLimitPct;
        this.partialsEnabled = builder.partialsEnabled;
        this.partialExitRMultiple = builder.partialExitRMultiple;
        this.partialExitPct = builder.partialExitPct;
    }

    // ==================== Factory Methods ====================

    /**
     * Evaluation phase profile for Topstep 50K.
     * More aggressive: lower quality gate, partials enabled, tighter targets.
     */
    public static RiskProfile topstep50kEvaluation() {
        return builder()
            .phase(AccountPhase.EVALUATION)
            .baseRiskPct(0.50)              // 0.5% of $50K = $250 per trade
            .maxDailyLossPct(0.75)          // Self-imposed: 75% of DLL ($750 vs $1000)
            .maxTradesPerDay(4)
            .maxConsecutiveLosses(3)
            .minRewardToRisk(3.0)
            .maxRewardToRisk(6.0)
            .minSetupQuality(4)
            .cruiseMinQuality(7)
            .cruiseRiskMultiplier(0.3)
            .protectionRiskMultiplier(0.6)
            .cautionRiskMultiplier(0.7)
            .dangerRiskMultiplier(0.4)
            .maxRiskBudgetPct(0.25)
            .maxDailyLimitPct(0.40)
            .partialsEnabled(true)
            .partialExitRMultiple(1.0)
            .partialExitPct(0.50)
            .build();
    }

    /**
     * Funded probation profile for Topstep 50K.
     * More conservative: higher quality gate, no partials, wider targets.
     */
    public static RiskProfile topstep50kFunded() {
        return builder()
            .phase(AccountPhase.FUNDED_PROBATION)
            .baseRiskPct(0.40)              // 0.4% of $50K = $200 per trade
            .maxDailyLossPct(0.60)          // Self-imposed: 60% of DLL ($600 vs $1000)
            .maxTradesPerDay(3)
            .maxConsecutiveLosses(2)
            .minRewardToRisk(3.0)
            .maxRewardToRisk(6.0)
            .minSetupQuality(5)
            .cruiseMinQuality(7)
            .cruiseRiskMultiplier(0.3)
            .protectionRiskMultiplier(0.5)
            .cautionRiskMultiplier(0.6)
            .dangerRiskMultiplier(0.3)
            .maxRiskBudgetPct(0.20)
            .maxDailyLimitPct(0.35)
            .partialsEnabled(false)
            .partialExitRMultiple(1.0)
            .partialExitPct(0.0)
            .build();
    }

    /**
     * Funded scaling profile for Topstep 50K.
     * Balanced: moderate risk, wider targets for higher EV per trade.
     */
    public static RiskProfile topstep50kScaling() {
        return builder()
            .phase(AccountPhase.FUNDED_SCALING)
            .baseRiskPct(0.45)
            .maxDailyLossPct(0.65)
            .maxTradesPerDay(3)
            .maxConsecutiveLosses(2)
            .minRewardToRisk(3.0)
            .maxRewardToRisk(6.0)
            .minSetupQuality(5)
            .cruiseMinQuality(7)
            .cruiseRiskMultiplier(0.3)
            .protectionRiskMultiplier(0.5)
            .cautionRiskMultiplier(0.6)
            .dangerRiskMultiplier(0.3)
            .maxRiskBudgetPct(0.20)
            .maxDailyLimitPct(0.35)
            .partialsEnabled(false)
            .partialExitRMultiple(1.0)
            .partialExitPct(0.0)
            .build();
    }

    /**
     * Create a copy of this profile with a different base risk percentage.
     * Used by the risk optimizer to sweep risk levels.
     */
    public RiskProfile withBaseRiskPct(double newBaseRiskPct) {
        return builder()
            .phase(this.phase)
            .baseRiskPct(newBaseRiskPct)
            .maxDailyLossPct(this.maxDailyLossPct)
            .maxTradesPerDay(this.maxTradesPerDay)
            .maxConsecutiveLosses(this.maxConsecutiveLosses)
            .minRewardToRisk(this.minRewardToRisk)
            .maxRewardToRisk(this.maxRewardToRisk)
            .minSetupQuality(this.minSetupQuality)
            .cruiseMinQuality(this.cruiseMinQuality)
            .cruiseRiskMultiplier(this.cruiseRiskMultiplier)
            .protectionRiskMultiplier(this.protectionRiskMultiplier)
            .cautionRiskMultiplier(this.cautionRiskMultiplier)
            .dangerRiskMultiplier(this.dangerRiskMultiplier)
            .maxRiskBudgetPct(this.maxRiskBudgetPct)
            .maxDailyLimitPct(this.maxDailyLimitPct)
            .partialsEnabled(this.partialsEnabled)
            .partialExitRMultiple(this.partialExitRMultiple)
            .partialExitPct(this.partialExitPct)
            .build();
    }

    // ==================== Getters ====================

    public AccountPhase getPhase() { return phase; }
    public double getBaseRiskPct() { return baseRiskPct; }
    public double getMaxDailyLossPct() { return maxDailyLossPct; }
    public int getMaxTradesPerDay() { return maxTradesPerDay; }
    public int getMaxConsecutiveLosses() { return maxConsecutiveLosses; }
    public double getMinRewardToRisk() { return minRewardToRisk; }
    public double getMaxRewardToRisk() { return maxRewardToRisk; }
    public int getMinSetupQuality() { return minSetupQuality; }
    public int getCruiseMinQuality() { return cruiseMinQuality; }
    public double getCruiseRiskMultiplier() { return cruiseRiskMultiplier; }
    public double getProtectionRiskMultiplier() { return protectionRiskMultiplier; }
    public double getCautionRiskMultiplier() { return cautionRiskMultiplier; }
    public double getDangerRiskMultiplier() { return dangerRiskMultiplier; }
    public double getMaxRiskBudgetPct() { return maxRiskBudgetPct; }
    public double getMaxDailyLimitPct() { return maxDailyLimitPct; }
    public boolean isPartialsEnabled() { return partialsEnabled; }
    public double getPartialExitRMultiple() { return partialExitRMultiple; }
    public double getPartialExitPct() { return partialExitPct; }

    @Override
    public String toString() {
        return String.format("RiskProfile{phase=%s, baseRisk=%.2f%%, maxDailyLoss=%.0f%%, quality>=%d, trades/day<=%d}",
            phase, baseRiskPct * 100, maxDailyLossPct * 100, minSetupQuality, maxTradesPerDay);
    }

    // ==================== Builder ====================

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private AccountPhase phase = AccountPhase.EVALUATION;
        private double baseRiskPct = 0.005;
        private double maxDailyLossPct = 0.75;
        private int maxTradesPerDay = 4;
        private int maxConsecutiveLosses = 3;
        private double minRewardToRisk = 3.0;
        private double maxRewardToRisk = 6.0;
        private int minSetupQuality = 4;
        private int cruiseMinQuality = 7;
        private double cruiseRiskMultiplier = 0.3;
        private double protectionRiskMultiplier = 0.6;
        private double cautionRiskMultiplier = 0.7;
        private double dangerRiskMultiplier = 0.4;
        private double maxRiskBudgetPct = 0.25;
        private double maxDailyLimitPct = 0.40;
        private boolean partialsEnabled = false;
        private double partialExitRMultiple = 1.0;
        private double partialExitPct = 0.50;

        public Builder phase(AccountPhase phase) { this.phase = phase; return this; }
        public Builder baseRiskPct(double v) { this.baseRiskPct = v; return this; }
        public Builder maxDailyLossPct(double v) { this.maxDailyLossPct = v; return this; }
        public Builder maxTradesPerDay(int v) { this.maxTradesPerDay = v; return this; }
        public Builder maxConsecutiveLosses(int v) { this.maxConsecutiveLosses = v; return this; }
        public Builder minRewardToRisk(double v) { this.minRewardToRisk = v; return this; }
        public Builder maxRewardToRisk(double v) { this.maxRewardToRisk = v; return this; }
        public Builder minSetupQuality(int v) { this.minSetupQuality = v; return this; }
        public Builder cruiseMinQuality(int v) { this.cruiseMinQuality = v; return this; }
        public Builder cruiseRiskMultiplier(double v) { this.cruiseRiskMultiplier = v; return this; }
        public Builder protectionRiskMultiplier(double v) { this.protectionRiskMultiplier = v; return this; }
        public Builder cautionRiskMultiplier(double v) { this.cautionRiskMultiplier = v; return this; }
        public Builder dangerRiskMultiplier(double v) { this.dangerRiskMultiplier = v; return this; }
        public Builder maxRiskBudgetPct(double v) { this.maxRiskBudgetPct = v; return this; }
        public Builder maxDailyLimitPct(double v) { this.maxDailyLimitPct = v; return this; }
        public Builder partialsEnabled(boolean v) { this.partialsEnabled = v; return this; }
        public Builder partialExitRMultiple(double v) { this.partialExitRMultiple = v; return this; }
        public Builder partialExitPct(double v) { this.partialExitPct = v; return this; }

        public RiskProfile build() { return new RiskProfile(this); }
    }
}
