package com.topstep.trading.validation;

import com.topstep.trading.chartstate.RaidDirection;
import com.topstep.trading.strategy.MarketBias;

/**
 * Basic validation utilities for trade entry rules.
 *
 * This class contains static validation methods for checking
 * fundamental trade entry requirements like bias/sweep alignment.
 */
public class TradeEntryValidator {

    /**
     * Validates that bias and sweep are in opposite directions.
     *
     * RULE: Bullish bias requires bearish sweep (LOW_SWEEP = swept lows).
     *       Bearish bias requires bullish sweep (HIGH_SWEEP = swept highs).
     *
     * This ensures liquidity was taken from the opposite side before entry,
     * which is a core ICT concept for high-probability setups.
     *
     * @param bias The market bias (BULLISH, BEARISH, or NEUTRAL)
     * @param sweep The raid direction (HIGH_SWEEP or LOW_SWEEP)
     * @return true if bias and sweep are properly aligned (opposite), false otherwise
     */
    public static boolean validateBiasSweepAlignment(MarketBias bias, RaidDirection sweep) {
        if (bias == null || sweep == null) {
            return false; // Cannot validate without both
        }

        // Bullish bias requires bearish sweep (LOW_SWEEP = swept lows, expect bounce up)
        if (bias == MarketBias.BULLISH) {
            return sweep == RaidDirection.LOW_SWEEP;
        }

        // Bearish bias requires bullish sweep (HIGH_SWEEP = swept highs, expect drop down)
        if (bias == MarketBias.BEARISH) {
            return sweep == RaidDirection.HIGH_SWEEP;
        }

        // NEUTRAL bias doesn't have a valid direction
        return false;
    }

    /**
     * Get detailed rejection reason if bias/sweep don't align.
     *
     * @param bias The market bias
     * @param sweep The raid direction
     * @return Human-readable rejection reason
     */
    public static String getBiasSweepRejectionReason(MarketBias bias, RaidDirection sweep) {
        if (bias == null && sweep == null) {
            return "REJECTED: Both Bias and Sweep are null. Cannot validate.";
        }
        if (bias == null) {
            return "REJECTED: Bias is null. Cannot validate against sweep: " + sweep;
        }
        if (sweep == null) {
            return "REJECTED: Sweep is null. Cannot validate against bias: " + bias;
        }
        if (bias == MarketBias.NEUTRAL) {
            return "REJECTED: Bias is NEUTRAL. Cannot trade without directional bias.";
        }

        return String.format(
                "REJECTED: Bias (%s) and Sweep (%s) must be opposite directions. " +
                "Bullish trades require LOW_SWEEP (swept lows), Bearish trades require HIGH_SWEEP (swept highs).",
                bias, sweep);
    }

    /**
     * Check if raid direction matches expected trade direction.
     *
     * This is a complementary check - the raid direction should EXPECT
     * the same direction as our trade bias.
     *
     * @param isBullish True if we're looking for bullish entry
     * @param raidDirection The raid direction
     * @return true if raid expects the same direction as our trade
     */
    public static boolean raidDirectionMatchesExpectation(boolean isBullish, RaidDirection raidDirection) {
        if (raidDirection == null) {
            return false;
        }

        // For bullish entry, we need LOW_SWEEP (which expects bullish)
        // For bearish entry, we need HIGH_SWEEP (which expects bearish)
        return raidDirection.expectsBullish() == isBullish;
    }

    /**
     * Validate that market condition score meets minimum threshold.
     *
     * @param marketConditionScore The market condition score
     * @param minimumScore The minimum acceptable score (typically 0)
     * @return true if score meets minimum, false otherwise
     */
    public static boolean validateMarketCondition(int marketConditionScore, int minimumScore) {
        return marketConditionScore >= minimumScore;
    }

    /**
     * Get market condition rejection reason.
     *
     * @param marketConditionScore The market condition score
     * @param minimumScore The minimum required score
     * @return Human-readable rejection reason
     */
    public static String getMarketConditionRejectionReason(int marketConditionScore, int minimumScore) {
        return String.format(
                "REJECTED: Market condition score %d is below minimum (%d). " +
                "Waiting for FAVORABLE or NEUTRAL conditions.",
                marketConditionScore, minimumScore);
    }

    /**
     * Validate that a trade was not artificially promoted.
     *
     * Promoted trades bypass natural confluence requirements and have
     * historically lower win rates.
     *
     * @param wasPromoted True if trade was promoted to higher tier
     * @return true if not promoted, false if promoted
     */
    public static boolean validateNotPromoted(boolean wasPromoted) {
        return !wasPromoted;
    }

    /**
     * Get promotion rejection reason.
     *
     * @param originalTier The original tier before promotion
     * @param promotedTier The tier after promotion
     * @param promotionReason Why it was promoted
     * @return Human-readable rejection reason
     */
    public static String getPromotionRejectionReason(String originalTier, String promotedTier, String promotionReason) {
        return String.format(
                "REJECTED: Trade was [PROMOTED] from %s to %s. " +
                "Reason: %s. Promoted trades bypass natural confluence and have lower win rate.",
                originalTier, promotedTier, promotionReason);
    }
}
