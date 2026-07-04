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

    // ── Signal-validator RR band (STDV+OTE M7 gate) ─────────────────────────
    // Decoupled from min/maxRiskRewardRatio on purpose: the legacy validator
    // floor has always been 2.0 while topstep50k().minRiskRewardRatio is 3.0.
    // Wiring the validator to minRiskRewardRatio would silently tighten legacy
    // emission from 2.0 → 3.0. The builder defaults [2.0, +infinity) reproduce
    // today's effective legacy behaviour byte-for-byte; only the scalp profile
    // overrides them.
    private final double signalMinRr;            // Validator M7 RR floor
    private final double signalMaxRr;            // Validator M7 RR ceiling

    // ── Trade-frequency gates (scalp discipline; ported semantics from the
    //    Monte Carlo RiskProfile). 0 = gate disabled (legacy profiles). ─────
    private final int maxTradesPerDay;           // Block trade N+1 of the day (0 = off)
    private final int maxConsecutiveLosses;      // Block after N straight losses (0 = off)

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
        this.signalMinRr = builder.signalMinRr;
        this.signalMaxRr = builder.signalMaxRr;
        this.maxTradesPerDay = builder.maxTradesPerDay;
        this.maxConsecutiveLosses = builder.maxConsecutiveLosses;
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
    /** Validator (M7) RR floor for signal emission; independent of the risk-engine band. */
    public double getSignalMinRr() { return signalMinRr; }
    /** Validator (M7) RR ceiling for signal emission; {@code Double.POSITIVE_INFINITY} = no ceiling. */
    public double getSignalMaxRr() { return signalMaxRr; }
    /** Max trades allowed per trading day; {@code 0} disables the gate (legacy profiles). */
    public int getMaxTradesPerDay() { return maxTradesPerDay; }
    /** Max consecutive losses before trading is blocked; {@code 0} disables the gate. */
    public int getMaxConsecutiveLosses() { return maxConsecutiveLosses; }

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

    /**
     * Topstep 50K limits for SCALP mode (1R-capped targets, multiple trades/day).
     *
     * <p>Same Topstep account rails as {@link #topstep50k()} (DLL $1,000,
     * MLL $2,000, 15:10 CT flatten) — those are NEVER weakened. What changes:
     *
     * <ul>
     *   <li>RR band [0.8, 1.5] — both the risk-engine band and the validator
     *       signal band, matching the 1R-capped scalp target model.</li>
     *   <li>{@code riskPerTrade} $150 — deliberately NOT the legacy $250:
     *       with a $1,000 DLL and multiple trades per day, $250+ per trade
     *       makes a DLL breach a near-certainty on a normal losing streak
     *       (4 losses). $150 x 6 trades caps the worst day at $900 &lt; DLL.
     *       SA5 Monte-Carlos this choice.</li>
     *   <li>{@code maxContracts} 20 micros — the instrument band is [5, 20]
     *       micros; the $150 risk cap dominates actual sizing.</li>
     *   <li>{@code maxTradesPerDay} 6 and {@code maxConsecutiveLosses} 3 —
     *       blocking gates enforced by {@code PropFirmRiskEngine}.</li>
     * </ul>
     */
    public static RiskLimits topstep50kScalp() {
        return builder()
                .maxDailyLoss(1000.0)          // DLL: $1,000 (unchanged Topstep rail)
                .maxLossLimit(2000.0)          // MLL: $2,000 (unchanged Topstep rail)
                .profitTarget(3000.0)          // Profit target: $3,000
                .trailingDrawdown(2000.0)      // Same as MLL
                .maxContracts(20)              // Instrument micro band ceiling [5, 20]
                .maxTotalContracts(20)         // One position at a time at full size
                .riskPerTrade(150.0)           // See javadoc: DLL survival at 6 trades/day
                .minRiskRewardRatio(0.8)       // Scalp band floor
                .maxRiskRewardRatio(1.5)       // Scalp band ceiling (1R-capped targets)
                .signalMinRr(0.8)              // Validator M7 band = same scalp band
                .signalMaxRr(1.5)
                .maxTradesPerDay(6)            // Trade #7 of the day is rejected
                .maxConsecutiveLosses(3)       // 4th trade after 3 straight losses rejected
                .flattenByTime(LocalTime.of(15, 10)) // 3:10 PM CT (Topstep rule, unchanged)
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
        // Validator band defaults reproduce the legacy effective behaviour
        // exactly: floor 2.0 (the historical MIN_RR_FLOOR_STDV_OTE), no
        // ceiling. Legacy factory methods inherit these without any change
        // to their source.
        private double signalMinRr = 2.0;
        private double signalMaxRr = Double.POSITIVE_INFINITY;
        // Frequency gates default OFF (0) so legacy profiles enforce neither.
        private int maxTradesPerDay = 0;
        private int maxConsecutiveLosses = 0;

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

        public Builder signalMinRr(double signalMinRr) {
            this.signalMinRr = signalMinRr;
            return this;
        }

        public Builder signalMaxRr(double signalMaxRr) {
            this.signalMaxRr = signalMaxRr;
            return this;
        }

        public Builder maxTradesPerDay(int maxTradesPerDay) {
            this.maxTradesPerDay = maxTradesPerDay;
            return this;
        }

        public Builder maxConsecutiveLosses(int maxConsecutiveLosses) {
            this.maxConsecutiveLosses = maxConsecutiveLosses;
            return this;
        }

        public RiskLimits build() {
            return new RiskLimits(this);
        }
    }
}
