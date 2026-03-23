package com.topstep.trading.strategy;

import com.topstep.trading.lifecycle.AccountPhase;

/**
 * Phase-dependent take-profit target placement.
 *
 * EVALUATION: Tighter targets for higher win rate and faster progress
 *   - Standard: 1.5R, Elite: 2R
 *
 * FUNDED: Wider targets for higher expected value per trade
 *   - Standard: 2R, Elite: 3R
 */
public class TargetPlacement {

    public enum SetupGrade {
        STANDARD,  // Normal quality setup
        ELITE      // High quality setup (breaker + SMT + power3)
    }

    /**
     * Get the target R-multiple for a given phase and setup grade.
     */
    public static double getTargetRMultiple(AccountPhase phase, SetupGrade grade) {
        if (phase.isEvaluation()) {
            return grade == SetupGrade.ELITE ? 2.0 : 1.5;
        } else if (phase.isFunded()) {
            return grade == SetupGrade.ELITE ? 3.0 : 2.0;
        }
        return 2.0; // Default
    }

    /**
     * Calculate the target price from entry and stop, adjusted for phase.
     */
    public static double calculateTargetPrice(double entryPrice, double stopPrice,
                                                AccountPhase phase, SetupGrade grade,
                                                boolean isBuy) {
        double riskDistance = Math.abs(entryPrice - stopPrice);
        double targetRMultiple = getTargetRMultiple(phase, grade);
        double targetDistance = riskDistance * targetRMultiple;

        if (isBuy) {
            return entryPrice + targetDistance;
        } else {
            return entryPrice - targetDistance;
        }
    }

    /**
     * Determine setup grade based on confluence factors.
     */
    public static SetupGrade gradeSetup(boolean hasBreakerBlock, boolean hasSmtDivergence,
                                          boolean hasPower3, boolean hasDisplacement,
                                          int qualityScore) {
        int eliteFactors = 0;
        if (hasBreakerBlock) eliteFactors++;
        if (hasSmtDivergence) eliteFactors++;
        if (hasPower3) eliteFactors++;
        if (hasDisplacement) eliteFactors++;

        // Elite if 3+ confluence factors or quality >= 8
        if (eliteFactors >= 3 || qualityScore >= 8) {
            return SetupGrade.ELITE;
        }
        return SetupGrade.STANDARD;
    }
}
