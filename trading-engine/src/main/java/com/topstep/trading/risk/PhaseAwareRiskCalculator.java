package com.topstep.trading.risk;

import com.topstep.trading.lifecycle.AccountLifecycle;
import com.topstep.trading.lifecycle.RiskZone;

/**
 * Dynamic risk calculator that adjusts position sizing based on:
 * - Account phase (evaluation vs funded)
 * - Risk zone (cruise/protection/normal/caution/danger)
 * - Setup quality score
 * - Remaining risk budget and daily loss room
 *
 * Replaces static 1% per-trade risk with adaptive sizing.
 */
public class PhaseAwareRiskCalculator {

    /**
     * Result of a risk calculation containing the adjusted risk amount
     * and the factors that went into the calculation.
     */
    public static class RiskCalculation {
        private final double riskDollars;
        private final double effectiveRiskPct;
        private final RiskZone zone;
        private final double zoneMultiplier;
        private final double qualityMultiplier;
        private final String breakdown;
        private final boolean tradingAllowed;
        private final String blockReason;

        public RiskCalculation(double riskDollars, double effectiveRiskPct, RiskZone zone,
                               double zoneMultiplier, double qualityMultiplier,
                               String breakdown, boolean tradingAllowed, String blockReason) {
            this.riskDollars = riskDollars;
            this.effectiveRiskPct = effectiveRiskPct;
            this.zone = zone;
            this.zoneMultiplier = zoneMultiplier;
            this.qualityMultiplier = qualityMultiplier;
            this.breakdown = breakdown;
            this.tradingAllowed = tradingAllowed;
            this.blockReason = blockReason;
        }

        public double getRiskDollars() { return riskDollars; }
        public double getEffectiveRiskPct() { return effectiveRiskPct; }
        public RiskZone getZone() { return zone; }
        public double getZoneMultiplier() { return zoneMultiplier; }
        public double getQualityMultiplier() { return qualityMultiplier; }
        public String getBreakdown() { return breakdown; }
        public boolean isTradingAllowed() { return tradingAllowed; }
        public String getBlockReason() { return blockReason; }

        public static RiskCalculation blocked(String reason) {
            return new RiskCalculation(0, 0, RiskZone.NORMAL, 0, 0, reason, false, reason);
        }

        @Override
        public String toString() {
            if (!tradingAllowed) return "BLOCKED: " + blockReason;
            return String.format("Risk=$%.2f (%.3f%%), zone=%s(%.1fx), quality=%.2fx",
                riskDollars, effectiveRiskPct * 100, zone, zoneMultiplier, qualityMultiplier);
        }
    }

    /**
     * Calculate the risk amount for a trade based on account lifecycle state.
     *
     * @param lifecycle  Current account lifecycle state
     * @param profile    Active risk profile for current phase
     * @param setupQuality  Quality score of the trade setup (0-10)
     * @param tradesToday   Number of trades already taken today
     * @return RiskCalculation with the adjusted risk amount and breakdown
     */
    public RiskCalculation calculateRisk(AccountLifecycle lifecycle, RiskProfile profile,
                                          int setupQuality, int tradesToday) {
        // Pre-trade checks
        if (lifecycle.isTradingPausedForDay()) {
            return RiskCalculation.blocked("Trading paused for the day");
        }

        if (lifecycle.getConsecutiveLosses() >= profile.getMaxConsecutiveLosses()) {
            return RiskCalculation.blocked("Consecutive loss limit reached: " +
                lifecycle.getConsecutiveLosses() + " >= " + profile.getMaxConsecutiveLosses());
        }

        if (tradesToday >= profile.getMaxTradesPerDay()) {
            return RiskCalculation.blocked("Max trades per day reached: " +
                tradesToday + " >= " + profile.getMaxTradesPerDay());
        }

        // Check self-imposed daily loss buffer
        double dailyLossRoom = lifecycle.dailyLossRoomRemaining();
        double selfImposedDailyLimit = lifecycle.getDailyLossLimit() * profile.getMaxDailyLossPct();
        double dailyUsed = lifecycle.getDailyLossLimit() - dailyLossRoom;
        if (dailyUsed >= selfImposedDailyLimit) {
            return RiskCalculation.blocked("Self-imposed daily loss buffer hit: $" +
                String.format("%.2f", dailyUsed) + " >= $" + String.format("%.2f", selfImposedDailyLimit));
        }

        // Quality gate
        RiskZone zone = lifecycle.getCurrentRiskZone();
        int requiredQuality = (zone == RiskZone.CRUISE)
            ? profile.getCruiseMinQuality()
            : profile.getMinSetupQuality();

        // Cruise control timeout: relax quality gate by 1 after 5 days with no progress
        if (zone == RiskZone.CRUISE && lifecycle.getConsecutiveCruiseDays() > 5) {
            requiredQuality = Math.max(profile.getMinSetupQuality(), requiredQuality - 1);
        }

        if (setupQuality < requiredQuality) {
            return RiskCalculation.blocked("Setup quality " + setupQuality +
                " < required " + requiredQuality + " (zone=" + zone + ")");
        }

        // Calculate base risk in dollars
        double baseRiskDollars = lifecycle.getStartingBalance() * profile.getBaseRiskPct();

        // Apply zone multiplier
        double zoneMultiplier = getZoneMultiplier(zone, profile);
        double zonedRisk = baseRiskDollars * zoneMultiplier;

        // Apply quality multiplier
        double qualityMultiplier = getQualityMultiplier(setupQuality);
        double adjustedRisk = zonedRisk * qualityMultiplier;

        // Apply hard floors
        // Floor 1: Max 25% of remaining risk budget
        double riskBudgetFloor = lifecycle.riskBudgetRemaining() * profile.getMaxRiskBudgetPct();
        adjustedRisk = Math.min(adjustedRisk, riskBudgetFloor);

        // Floor 2: Max 40% of remaining daily limit
        double dailyLimitFloor = dailyLossRoom * profile.getMaxDailyLimitPct();
        adjustedRisk = Math.min(adjustedRisk, dailyLimitFloor);

        // Never risk more than base risk
        adjustedRisk = Math.min(adjustedRisk, baseRiskDollars);

        // Minimum viable risk: at least $25 to place any trade
        if (adjustedRisk < 25.0) {
            return RiskCalculation.blocked("Calculated risk too small: $" +
                String.format("%.2f", adjustedRisk) + " < $25 minimum");
        }

        double effectivePct = adjustedRisk / lifecycle.getStartingBalance();

        String breakdown = String.format(
            "base=$%.0f * zone=%.1f(%s) * quality=%.2f(q=%d) = $%.0f, " +
            "floors: budget=$%.0f(%.0f%%), daily=$%.0f(%.0f%%)",
            baseRiskDollars, zoneMultiplier, zone, qualityMultiplier, setupQuality,
            adjustedRisk, riskBudgetFloor, profile.getMaxRiskBudgetPct() * 100,
            dailyLimitFloor, profile.getMaxDailyLimitPct() * 100);

        return new RiskCalculation(adjustedRisk, effectivePct, zone,
            zoneMultiplier, qualityMultiplier, breakdown, true, null);
    }

    /**
     * Get the zone-specific risk multiplier from the profile.
     */
    private double getZoneMultiplier(RiskZone zone, RiskProfile profile) {
        switch (zone) {
            case CRUISE:     return profile.getCruiseRiskMultiplier();
            case PROTECTION: return profile.getProtectionRiskMultiplier();
            case CAUTION:    return profile.getCautionRiskMultiplier();
            case DANGER:     return profile.getDangerRiskMultiplier();
            case NORMAL:
            default:         return 1.0;
        }
    }

    /**
     * Quality multiplier: scales risk based on setup quality score.
     * Quality 10 -> 1.0x, 8 -> 1.0x, 6 -> 0.75x, 4 -> 0.5x, <4 -> 0.0x
     */
    private double getQualityMultiplier(int quality) {
        if (quality >= 8) return 1.0;
        if (quality >= 6) return 0.75;
        if (quality >= 4) return 0.50;
        return 0.0; // Should be caught by quality gate before reaching here
    }
}
