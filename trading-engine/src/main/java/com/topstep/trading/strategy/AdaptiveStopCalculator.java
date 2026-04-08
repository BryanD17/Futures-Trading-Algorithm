package com.topstep.trading.strategy;

import com.topstep.trading.domain.Candle;
import java.util.LinkedList;

/**
 * Adaptive stop-loss calculator based on standard deviation of recent candle noise.
 *
 * Replaces the fixed {@code 5.0 * stopMultiplier} formula with stops placed
 * at 1.5σ of recent candle ranges beyond the zone edge. This means:
 * - In quiet markets (low σ): stops tighten automatically
 * - In volatile markets (high σ): stops widen automatically
 * - Only genuine adverse moves trigger stops, not random wicks
 *
 * Falls back to the existing ATR-based stop formula when uninitialized
 * (fewer than {@code noiseLookback} candles recorded).
 */
public class AdaptiveStopCalculator {

    private final String symbol;
    private final int noiseLookback;
    private final LinkedList<Double> candleRanges;

    // Cached statistics
    private double meanRange;
    private double stdDevRange;
    private boolean initialized;

    // Noise buffer multiplier: how many σ beyond zone edge
    private static final double NOISE_SIGMA_MULTIPLIER = 1.5;

    // Stop validation bounds (in σ units)
    private static final double MIN_STOP_SIGMA = 1.0;
    private static final double MAX_STOP_SIGMA = 3.0;

    public AdaptiveStopCalculator(String symbol, int noiseLookback) {
        this.symbol = symbol;
        this.noiseLookback = noiseLookback;
        this.candleRanges = new LinkedList<>();
        this.meanRange = 0;
        this.stdDevRange = 0;
        this.initialized = false;
    }

    /**
     * Update with each new candle.
     */
    public void update(Candle candle) {
        double range = candle.getHigh() - candle.getLow();
        candleRanges.add(range);

        while (candleRanges.size() > noiseLookback) {
            candleRanges.removeFirst();
        }

        if (candleRanges.size() >= noiseLookback) {
            recalculateStatistics();
            if (!initialized) {
                initialized = true;
                System.out.println("[" + symbol + "] AdaptiveStopCalculator INITIALIZED: " +
                        "meanRange=" + String.format("%.2f", meanRange) +
                        ", σ=" + String.format("%.2f", stdDevRange) +
                        ", noiseBuffer=" + String.format("%.2f", getNoiseBuffer()) + " pts");
            }
        }
    }

    private void recalculateStatistics() {
        double sum = 0;
        for (double r : candleRanges) {
            sum += r;
        }
        meanRange = sum / candleRanges.size();

        double sumSqDiff = 0;
        for (double r : candleRanges) {
            double diff = r - meanRange;
            sumSqDiff += diff * diff;
        }
        stdDevRange = Math.sqrt(sumSqDiff / candleRanges.size());
    }

    /**
     * Calculate stop distance beyond the zone edge.
     *
     * Uses 1.5σ of recent candle ranges as the buffer.
     * The stop is placed beyond normal noise so only a genuine
     * adverse move triggers it.
     *
     * @param zoneEdge The structural edge (OB low, breaker low, FVG bottom, etc.)
     * @param bullish  Direction
     * @return The stop price
     */
    public double calculateStop(double zoneEdge, boolean bullish) {
        if (!initialized) {
            // Fallback: return zone edge as-is (caller will apply ATR-based formula)
            return zoneEdge;
        }

        double buffer = getNoiseBuffer();
        return bullish ? zoneEdge - buffer : zoneEdge + buffer;
    }

    /**
     * Get the raw noise buffer in points.
     * This is 1.5 * stdDevRange — the distance beyond the zone edge.
     */
    public double getNoiseBuffer() {
        if (!initialized || stdDevRange == 0) {
            return 0;
        }
        return NOISE_SIGMA_MULTIPLIER * stdDevRange;
    }

    /**
     * Validate a proposed stop distance.
     *
     * Rejects stops that are:
     * - Tighter than 1.0σ (will get stopped by noise constantly)
     * - Wider than 3.0σ (unnecessarily wide, eating into R:R)
     *
     * @param proposedStopDistance Distance from entry to stop in points
     * @return StopValidation with pass/fail and suggested adjustment
     */
    public StopValidation validateStop(double proposedStopDistance) {
        if (!initialized || stdDevRange == 0) {
            return new StopValidation(true, proposedStopDistance, "Not initialized — accepting proposed stop");
        }

        double sigmaUnits = proposedStopDistance / stdDevRange;

        if (sigmaUnits < MIN_STOP_SIGMA) {
            double suggested = MIN_STOP_SIGMA * stdDevRange;
            return new StopValidation(false, suggested,
                    String.format("Stop too tight (%.1fσ < %.1fσ minimum). " +
                            "Suggested: %.2f pts (%.1fσ)", sigmaUnits, MIN_STOP_SIGMA, suggested, MIN_STOP_SIGMA));
        }

        if (sigmaUnits > MAX_STOP_SIGMA) {
            double suggested = MAX_STOP_SIGMA * stdDevRange;
            return new StopValidation(false, suggested,
                    String.format("Stop too wide (%.1fσ > %.1fσ maximum). " +
                            "Suggested: %.2f pts (%.1fσ)", sigmaUnits, MAX_STOP_SIGMA, suggested, MAX_STOP_SIGMA));
        }

        return new StopValidation(true, proposedStopDistance,
                String.format("Valid (%.1fσ)", sigmaUnits));
    }

    public boolean isInitialized() {
        return initialized;
    }

    public double getMeanRange() {
        return meanRange;
    }

    public double getStdDevRange() {
        return stdDevRange;
    }

    public int getSampleCount() {
        return candleRanges.size();
    }

    public static class StopValidation {
        private final boolean valid;
        private final double suggestedDistance;
        private final String reason;

        public StopValidation(boolean valid, double suggestedDistance, String reason) {
            this.valid = valid;
            this.suggestedDistance = suggestedDistance;
            this.reason = reason;
        }

        public boolean isValid() {
            return valid;
        }

        public double getSuggestedDistance() {
            return suggestedDistance;
        }

        public String getReason() {
            return reason;
        }
    }
}
