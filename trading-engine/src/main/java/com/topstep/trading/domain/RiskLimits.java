package com.topstep.trading.domain;

import java.time.LocalTime;
import java.util.Objects;

/**
 * Configurable risk limits for Topstep compliance and capital protection.
 * Immutable configuration object.
 */
public final class RiskLimits {
    private final double maxDailyLoss;           // Maximum daily loss (Topstep rule)
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
    public double getTrailingDrawdown() { return trailingDrawdown; }
    public int getMaxContracts() { return maxContracts; }
    public int getMaxTotalContracts() { return maxTotalContracts; }
    public double getRiskPerTrade() { return riskPerTrade; }
    public double getMinRiskRewardRatio() { return minRiskRewardRatio; }
    public double getMaxRiskRewardRatio() { return maxRiskRewardRatio; }
    public LocalTime getFlattenByTime() { return flattenByTime; }
    public boolean isAllowWeekendTrading() { return allowWeekendTrading; }

    /**
     * Create default Topstep 50K evaluation account limits.
     */
    public static RiskLimits topstep50k() {
        return builder()
                .maxDailyLoss(1000.0)
                .trailingDrawdown(2000.0)
                .maxContracts(5)
                .maxTotalContracts(10)
                .riskPerTrade(100.0)
                .minRiskRewardRatio(1.0)
                .maxRiskRewardRatio(5.0)
                .flattenByTime(LocalTime.of(15, 45)) // 3:45 PM CT
                .allowWeekendTrading(false)
                .build();
    }

    /**
     * Create default Topstep 100K funded account limits.
     */
    public static RiskLimits topstep100k() {
        return builder()
                .maxDailyLoss(2000.0)
                .trailingDrawdown(3000.0)
                .maxContracts(10)
                .maxTotalContracts(20)
                .riskPerTrade(200.0)
                .minRiskRewardRatio(1.0)
                .maxRiskRewardRatio(5.0)
                .flattenByTime(LocalTime.of(15, 45))
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
        private double trailingDrawdown = 2000.0;
        private int maxContracts = 5;
        private int maxTotalContracts = 10;
        private double riskPerTrade = 100.0;
        private double minRiskRewardRatio = 1.0;
        private double maxRiskRewardRatio = 5.0;
        private LocalTime flattenByTime = LocalTime.of(15, 45);
        private boolean allowWeekendTrading = false;

        public Builder maxDailyLoss(double maxDailyLoss) {
            this.maxDailyLoss = maxDailyLoss;
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
