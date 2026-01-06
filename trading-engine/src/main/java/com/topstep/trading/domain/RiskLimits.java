package com.topstep.trading.domain;

import java.time.LocalTime;
import java.util.Objects;

/**
 * Configurable risk limits for Topstep compliance and capital protection.
 * Immutable configuration object.
 */
public final class RiskLimits {
    private final double maxDailyLoss;           // Maximum daily loss (Topstep DLL)
    private final double maxLossLimit;           // Maximum loss limit (Topstep MLL)
    private final double profitTarget;           // Profit target to pass evaluation
    private final double trailingDrawdown;       // Trailing drawdown limit (Topstep rule)
    private final int maxContracts;              // Maximum contracts per position
    private final int maxTotalContracts;         // Maximum total contracts across all positions
    private final double riskPerTrade;           // Risk amount per trade (1R)
    private final double minRiskRewardRatio;     // Minimum R:R (e.g., 1.0 for 1:1)
    private final double maxRiskRewardRatio;     // Maximum R:R (e.g., 5.0 for 1:5)
    private final LocalTime flattenByTime;       // Must be flat by this time
    private final boolean allowWeekendTrading;   // Allow trading on weekends

    private RiskLimits(Builder builder) {
        this.maxDailyLoss = builder.maxDailyLoss;
        this.maxLossLimit = builder.maxLossLimit;
        this.profitTarget = builder.profitTarget;
        this.trailingDrawdown = builder.trailingDrawdown;
        this.maxContracts = builder.maxContracts;
        this.maxTotalContracts = builder.maxTotalContracts;
        this.riskPerTrade = builder.riskPerTrade;
        this.minRiskRewardRatio = builder.minRiskRewardRatio;
        this.maxRiskRewardRatio = builder.maxRiskRewardRatio;
        this.flattenByTime = builder.flattenByTime;
        this.allowWeekendTrading = builder.allowWeekendTrading;
    }

    // Getters
    public double getMaxDailyLoss() { return maxDailyLoss; }
    public double getDailyLossLimit() { return maxDailyLoss; } // Alias for backwards compatibility
    public double getMaxLossLimit() { return maxLossLimit; }
    public double getProfitTarget() { return profitTarget; }
    public double getTrailingDrawdown() { return trailingDrawdown; }
    public int getMaxContracts() { return maxContracts; }
    public int getMaxTotalContracts() { return maxTotalContracts; }
    /**
     * Get the maximum number of concurrent positions allowed.
     * Derived from maxTotalContracts / maxContracts, minimum of 1.
     */
    public int getMaxPositions() { return Math.max(1, maxTotalContracts / maxContracts); }
    public double getRiskPerTrade() { return riskPerTrade; }
    public double getMinRiskRewardRatio() { return minRiskRewardRatio; }
    public double getMaxRiskRewardRatio() { return maxRiskRewardRatio; }
    public LocalTime getFlattenByTime() { return flattenByTime; }
    public boolean isAllowWeekendTrading() { return allowWeekendTrading; }

    /**
     * Create default Topstep 50K evaluation account limits (Express Funded Account).
     * Based on official Topstep Express Funded Account rules:
     * - Starting balance: $0
     * - Daily Loss Limit: $1,000
     * - Max Loss Limit: $2,000
     * - Profit Target: $3,000 (to pass evaluation)
     */
    public static RiskLimits topstep50k() {
        return builder()
                .maxDailyLoss(1000.0)          // DLL: $1,000
                .maxLossLimit(2000.0)          // MLL: $2,000
                .profitTarget(3000.0)          // Profit target: $3,000
                .trailingDrawdown(2000.0)      // Same as MLL
                .maxContracts(5)
                .maxTotalContracts(10)
                .riskPerTrade(250.0)           // 25% of DLL per trade
                .minRiskRewardRatio(3.0)       // TIGHTENED: Minimum 3:1 R:R (was 2:1)
                .maxRiskRewardRatio(6.0)       // Maximum 6:1 R:R
                .flattenByTime(LocalTime.of(15, 10)) // 3:10 PM CT (Topstep rule)
                .allowWeekendTrading(false)
                .build();
    }

    /**
     * Create default Topstep 100K funded account limits (Express Funded Account).
     * Based on official Topstep Express Funded Account rules:
     * - Starting balance: $0
     * - Daily Loss Limit: $2,000
     * - Max Loss Limit: $3,000
     * - Profit Target: $6,000 (to pass evaluation)
     */
    public static RiskLimits topstep100k() {
        return builder()
                .maxDailyLoss(2000.0)          // DLL: $2,000
                .maxLossLimit(3000.0)          // MLL: $3,000
                .profitTarget(6000.0)          // Profit target: $6,000
                .trailingDrawdown(3000.0)      // Same as MLL
                .maxContracts(10)
                .maxTotalContracts(20)
                .riskPerTrade(500.0)           // 25% of DLL per trade
                .minRiskRewardRatio(3.0)       // TIGHTENED: Minimum 3:1 R:R (was 2:1)
                .maxRiskRewardRatio(6.0)       // Maximum 6:1 R:R
                .flattenByTime(LocalTime.of(15, 10)) // 3:10 PM CT
                .allowWeekendTrading(false)
                .build();
    }

    /**
     * Create default Topstep 150K funded account limits (Express Funded Account).
     * Based on official Topstep Express Funded Account rules:
     * - Starting balance: $0
     * - Daily Loss Limit: $3,000
     * - Max Loss Limit: $4,500
     * - Profit Target: $9,000 (to pass evaluation)
     */
    public static RiskLimits topstep150k() {
        return builder()
                .maxDailyLoss(3000.0)          // DLL: $3,000
                .maxLossLimit(4500.0)          // MLL: $4,500
                .profitTarget(9000.0)          // Profit target: $9,000
                .trailingDrawdown(4500.0)      // Same as MLL
                .maxContracts(15)
                .maxTotalContracts(30)
                .riskPerTrade(750.0)           // 25% of DLL per trade
                .minRiskRewardRatio(3.0)       // TIGHTENED: Minimum 3:1 R:R (was 2:1)
                .maxRiskRewardRatio(6.0)       // Maximum 6:1 R:R
                .flattenByTime(LocalTime.of(15, 10)) // 3:10 PM CT
                .allowWeekendTrading(false)
                .build();
    }

    @Override
    public String toString() {
        return String.format("RiskLimits{maxDailyLoss=%.2f, trailingDrawdown=%.2f, maxContracts=%d, riskPerTrade=%.2f}",
                maxDailyLoss, trailingDrawdown, maxContracts, riskPerTrade);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private double maxDailyLoss = 1000.0;
        private double maxLossLimit = 2000.0;
        private double profitTarget = 3000.0;
        private double trailingDrawdown = 2000.0;
        private int maxContracts = 5;
        private int maxTotalContracts = 10;
        private double riskPerTrade = 250.0;
        private double minRiskRewardRatio = 2.0;
        private double maxRiskRewardRatio = 5.0;
        private LocalTime flattenByTime = LocalTime.of(15, 10);
        private boolean allowWeekendTrading = false;

        public Builder maxDailyLoss(double maxDailyLoss) {
            this.maxDailyLoss = maxDailyLoss;
            return this;
        }

        public Builder maxLossLimit(double maxLossLimit) {
            this.maxLossLimit = maxLossLimit;
            return this;
        }

        public Builder profitTarget(double profitTarget) {
            this.profitTarget = profitTarget;
            return this;
        }

        public Builder trailingDrawdown(double trailingDrawdown) {
            this.trailingDrawdown = trailingDrawdown;
            return this;
        }

        public Builder maxContracts(int maxContracts) {
            this.maxContracts = maxContracts;
            return this;
        }

        public Builder maxTotalContracts(int maxTotalContracts) {
            this.maxTotalContracts = maxTotalContracts;
            return this;
        }

        public Builder riskPerTrade(double riskPerTrade) {
            this.riskPerTrade = riskPerTrade;
            return this;
        }

        public Builder minRiskRewardRatio(double minRiskRewardRatio) {
            this.minRiskRewardRatio = minRiskRewardRatio;
            return this;
        }

        public Builder maxRiskRewardRatio(double maxRiskRewardRatio) {
            this.maxRiskRewardRatio = maxRiskRewardRatio;
            return this;
        }

        public Builder flattenByTime(LocalTime flattenByTime) {
            this.flattenByTime = flattenByTime;
            return this;
        }

        public Builder allowWeekendTrading(boolean allowWeekendTrading) {
            this.allowWeekendTrading = allowWeekendTrading;
            return this;
        }

        public RiskLimits build() {
            return new RiskLimits(this);
        }
    }
}
