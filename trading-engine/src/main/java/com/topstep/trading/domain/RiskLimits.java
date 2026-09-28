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
    // -- ONE RR band (V5 RC-13, AGENT-05 risk half / AGENT-04 validator half)
    // rrFloor / rrCeiling are the SINGLE truth read by the validator (M7)
    // and PropFirmRiskEngine. The historical pairs
    // minRiskRewardRatio/maxRiskRewardRatio and signalMinRr/signalMaxRr are
    // kept as ALIASES (getters and builder setters) so existing callers
    // compile; they can no longer disagree.
    private final double rrFloor;                // risk.rrFloor (1.0 legacy / 0.8 scalp)
    private final double rrCeiling;              // risk.rrCeiling (5.0)
    private final LocalTime flattenByTime;       // Must be flat by this time
    private final boolean allowWeekendTrading;   // Allow trading on weekends

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
        this.rrFloor = builder.rrFloor;
        this.rrCeiling = builder.rrCeiling;
        this.flattenByTime = builder.flattenByTime;
        this.allowWeekendTrading = builder.allowWeekendTrading;
        this.maxTradesPerDay = builder.maxTradesPerDay;
        this.maxConsecutiveLosses = builder.maxConsecutiveLosses;
    }

    /**
     * Copy this configuration into a Builder so individual fields can be
     * overridden (used by the dashboard risk-settings endpoint).
     */
    public Builder toBuilder() {
        return builder()
                .maxDailyLoss(maxDailyLoss)
                .maxLossLimit(maxLossLimit)
                .profitTarget(profitTarget)
                .trailingDrawdown(trailingDrawdown)
                .maxContracts(maxContracts)
                .maxTotalContracts(maxTotalContracts)
                .riskPerTrade(riskPerTrade)
                .rrFloor(rrFloor)
                .rrCeiling(rrCeiling)
                .flattenByTime(flattenByTime)
                .allowWeekendTrading(allowWeekendTrading)
                .maxTradesPerDay(maxTradesPerDay)
                .maxConsecutiveLosses(maxConsecutiveLosses);
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
    /** THE RR floor (validator M7 vs T1 and PropFirmRiskEngine). */
    public double getRrFloor() { return rrFloor; }
    /** THE RR ceiling (validator M7 vs the final target). */
    public double getRrCeiling() { return rrCeiling; }
    /** Alias of {@link #getRrFloor()} (V5: one band). */
    public double getMinRiskRewardRatio() { return rrFloor; }
    /** Alias of {@link #getRrCeiling()} (V5: one band). */
    public double getMaxRiskRewardRatio() { return rrCeiling; }
    public LocalTime getFlattenByTime() { return flattenByTime; }
    public boolean isAllowWeekendTrading() { return allowWeekendTrading; }
    /** Alias of {@link #getRrFloor()} (V5: validator and risk engine share one band). */
    public double getSignalMinRr() { return rrFloor; }
    /** Alias of {@link #getRrCeiling()} (V5: validator and risk engine share one band). */
    public double getSignalMaxRr() { return rrCeiling; }
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
                .rrFloor(com.topstep.trading.risk.RiskConfig.rrFloorLegacy())   // V5 one band: 1.0
                .rrCeiling(com.topstep.trading.risk.RiskConfig.rrCeiling())     // V5 one band: 5.0
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
                .rrFloor(com.topstep.trading.risk.RiskConfig.rrFloorLegacy())   // V5 one band: 1.0
                .rrCeiling(com.topstep.trading.risk.RiskConfig.rrCeiling())     // V5 one band: 5.0
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
                .rrFloor(com.topstep.trading.risk.RiskConfig.rrFloorLegacy())   // V5 one band: 1.0
                .rrCeiling(com.topstep.trading.risk.RiskConfig.rrCeiling())     // V5 one band: 5.0
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
     *   <li>RR band: floor 0.8 (risk.rrFloor.scalp), ceiling 5.0
     *       (risk.rrCeiling) - V5 ONE band shared by the validator and the
     *       risk engine (the old [0.8, 1.5] second band is gone).</li>
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
                .rrFloor(com.topstep.trading.risk.RiskConfig.rrFloorScalp())    // V5 one band: 0.8
                .rrCeiling(com.topstep.trading.risk.RiskConfig.rrCeiling())     // V5 one band: 5.0
                .maxTradesPerDay(6)            // Trade #7 of the day is rejected
                .maxConsecutiveLosses(3)       // 4th trade after 3 straight losses rejected
                .flattenByTime(LocalTime.of(15, 10)) // 3:10 PM CT (Topstep rule, unchanged)
                .allowWeekendTrading(false)
                .build();
    }

    @Override
    public String toString() {
        return String.format("RiskLimits{maxDailyLoss=%.2f, trailingDrawdown=%.2f, maxContracts=%d, riskPerTrade=%.2f, rr=[%.2f, %.2f]}",
                maxDailyLoss, trailingDrawdown, maxContracts, riskPerTrade, rrFloor, rrCeiling);
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
        // V5 one band: defaults from risk.rrFloor / risk.rrCeiling.
        private double rrFloor = com.topstep.trading.risk.RiskConfig.rrFloorLegacy();
        private double rrCeiling = com.topstep.trading.risk.RiskConfig.rrCeiling();
        private LocalTime flattenByTime = LocalTime.of(15, 10);
        private boolean allowWeekendTrading = false;
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

        /** THE RR floor (validator + risk engine). */
        public Builder rrFloor(double rrFloor) {
            this.rrFloor = rrFloor;
            return this;
        }

        /** THE RR ceiling (validator, final target). */
        public Builder rrCeiling(double rrCeiling) {
            this.rrCeiling = rrCeiling;
            return this;
        }

        /** Alias of {@link #rrFloor(double)} (V5 one band). */
        public Builder minRiskRewardRatio(double minRiskRewardRatio) {
            return rrFloor(minRiskRewardRatio);
        }

        /** Alias of {@link #rrCeiling(double)} (V5 one band). */
        public Builder maxRiskRewardRatio(double maxRiskRewardRatio) {
            return rrCeiling(maxRiskRewardRatio);
        }

        public Builder flattenByTime(LocalTime flattenByTime) {
            this.flattenByTime = flattenByTime;
            return this;
        }

        public Builder allowWeekendTrading(boolean allowWeekendTrading) {
            this.allowWeekendTrading = allowWeekendTrading;
            return this;
        }

        /** Alias of {@link #rrFloor(double)} (V5 one band). */
        public Builder signalMinRr(double signalMinRr) {
            return rrFloor(signalMinRr);
        }

        /** Alias of {@link #rrCeiling(double)} (V5 one band). */
        public Builder signalMaxRr(double signalMaxRr) {
            return rrCeiling(signalMaxRr);
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
