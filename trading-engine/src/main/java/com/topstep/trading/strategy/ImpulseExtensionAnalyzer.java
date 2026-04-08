package com.topstep.trading.strategy;

import com.topstep.trading.domain.Candle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * Provides σ-based sanity checks on profit targets so the strategy doesn't
 * set unrealistic targets that never get hit.
 *
 * Tracks a rolling window of completed impulse move sizes and computes
 * mean/stdDev to classify proposed targets as REALISTIC, AGGRESSIVE, or UNREALISTIC.
 *
 * Uses empirical percentile rank (not normal distribution) for probability
 * estimates, since futures impulses have fat tails.
 *
 * Falls back to accepting all targets when uninitialized (< 15 impulses).
 */
public class ImpulseExtensionAnalyzer {

    private final String symbol;
    private final int windowSize;
    private final LinkedList<Double> impulseSizes;

    // Cached statistics
    private double meanImpulse;
    private double stdDevImpulse;
    private boolean initialized;

    private static final int MIN_SAMPLES = 15;

    // Target classification thresholds (in σ above mean)
    private static final double REALISTIC_SIGMA = 1.0;
    private static final double AGGRESSIVE_SIGMA = 2.0;
    private static final double UNREALISTIC_SIGMA = 2.5;

    public ImpulseExtensionAnalyzer(String symbol, int windowSize) {
        this.symbol = symbol;
        this.windowSize = windowSize;
        this.impulseSizes = new LinkedList<>();
        this.meanImpulse = 0;
        this.stdDevImpulse = 0;
        this.initialized = false;
    }

    /**
     * Record a completed impulse move.
     * Call this whenever a swing completes (new swing high in uptrend,
     * new swing low in downtrend).
     *
     * @param impulseSize Size of the impulse in points (always positive)
     */
    public void recordImpulse(double impulseSize) {
        if (impulseSize <= 0) return;

        impulseSizes.add(impulseSize);

        while (impulseSizes.size() > windowSize) {
            impulseSizes.removeFirst();
        }

        if (impulseSizes.size() >= MIN_SAMPLES) {
            recalculateStatistics();
            if (!initialized) {
                initialized = true;
                System.out.println("[" + symbol + "] ImpulseExtensionAnalyzer INITIALIZED: " +
                        "meanImpulse=" + String.format("%.2f", meanImpulse) +
                        " pts, σ=" + String.format("%.2f", stdDevImpulse) +
                        " pts, samples=" + impulseSizes.size());
            }
        }
    }

    /**
     * Update with candle (for tracking purposes — no-op for now;
     * impulse recording is done via recordImpulse from swing detection).
     */
    public void update(Candle candle) {
        // Impulse data is fed via recordImpulse() calls from swing detection.
        // This method exists for consistent interface with other detectors.
    }

    private void recalculateStatistics() {
        double sum = 0;
        for (double s : impulseSizes) {
            sum += s;
        }
        meanImpulse = sum / impulseSizes.size();

        double sumSqDiff = 0;
        for (double s : impulseSizes) {
            double diff = s - meanImpulse;
            sumSqDiff += diff * diff;
        }
        stdDevImpulse = Math.sqrt(sumSqDiff / impulseSizes.size());
    }

    /**
     * Validate a proposed target distance against measured impulse statistics.
     *
     * Returns a classification:
     * - REALISTIC:    target <= meanImpulse + 1.0σ
     * - AGGRESSIVE:   target <= meanImpulse + 2.0σ
     * - UNREALISTIC:  target > meanImpulse + 2.5σ
     *
     * @param targetDistance Distance from entry to target in points
     * @return TargetAssessment with classification and suggested alternatives
     */
    public TargetAssessment assessTarget(double targetDistance) {
        if (!initialized) {
            return new TargetAssessment(TargetRealism.UNINITIALIZED,
                    targetDistance, targetDistance, 0.5);
        }

        double conservativeTarget = meanImpulse + REALISTIC_SIGMA * stdDevImpulse;
        double aggressiveTarget = meanImpulse + AGGRESSIVE_SIGMA * stdDevImpulse;
        double probability = estimateTargetProbability(targetDistance);

        TargetRealism realism;
        if (targetDistance <= meanImpulse + REALISTIC_SIGMA * stdDevImpulse) {
            realism = TargetRealism.REALISTIC;
        } else if (targetDistance <= meanImpulse + AGGRESSIVE_SIGMA * stdDevImpulse) {
            realism = TargetRealism.AGGRESSIVE;
        } else {
            realism = TargetRealism.UNREALISTIC;
        }

        return new TargetAssessment(realism, conservativeTarget, aggressiveTarget, probability);
    }

    /**
     * Get a σ-based target price.
     *
     * @param entry      Entry price
     * @param bullish    Direction
     * @param sigmaLevel How many σ above mean impulse (1.0 = conservative, 2.0 = aggressive)
     * @return Target price
     */
    public double getSigmaTarget(double entry, boolean bullish, double sigmaLevel) {
        if (!initialized) {
            // Fallback: return entry (caller should use fixed R:R)
            return entry;
        }

        double targetDistance = meanImpulse + sigmaLevel * stdDevImpulse;
        return bullish ? entry + targetDistance : entry - targetDistance;
    }

    /**
     * Get the probability estimate that a target will be reached,
     * based on the empirical distribution of impulse sizes.
     *
     * Uses actual percentile rank, not normal distribution assumption.
     * Fat tails in futures data make normal distribution unreliable.
     *
     * @param targetDistance Target distance in points
     * @return Estimated probability (0.0 to 1.0)
     */
    public double estimateTargetProbability(double targetDistance) {
        if (!initialized || impulseSizes.isEmpty()) {
            return 0.5; // Unknown
        }

        // Count how many recorded impulses were >= targetDistance
        long countAbove = 0;
        for (double size : impulseSizes) {
            if (size >= targetDistance) {
                countAbove++;
            }
        }

        return (double) countAbove / impulseSizes.size();
    }

    public boolean isInitialized() {
        return initialized;
    }

    public double getMeanImpulse() {
        return meanImpulse;
    }

    public double getStdDevImpulse() {
        return stdDevImpulse;
    }

    public int getSampleCount() {
        return impulseSizes.size();
    }

    public static class TargetAssessment {
        private final TargetRealism realism;
        private final double suggestedConservative;
        private final double suggestedAggressive;
        private final double estimatedProbability;

        public TargetAssessment(TargetRealism realism, double suggestedConservative,
                                double suggestedAggressive, double estimatedProbability) {
            this.realism = realism;
            this.suggestedConservative = suggestedConservative;
            this.suggestedAggressive = suggestedAggressive;
            this.estimatedProbability = estimatedProbability;
        }

        public TargetRealism getRealism() {
            return realism;
        }

        public double getSuggestedConservative() {
            return suggestedConservative;
        }

        public double getSuggestedAggressive() {
            return suggestedAggressive;
        }

        public double getEstimatedProbability() {
            return estimatedProbability;
        }
    }

    public enum TargetRealism {
        REALISTIC,      // Within normal impulse range (mean + 1σ)
        AGGRESSIVE,     // Stretching but possible (mean + 2σ)
        UNREALISTIC,    // Very unlikely on this impulse (> mean + 2.5σ)
        UNINITIALIZED   // Not enough data yet
    }
}
