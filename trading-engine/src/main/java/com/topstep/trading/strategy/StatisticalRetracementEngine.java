package com.topstep.trading.strategy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * Replaces fixed Fibonacci OTE (0.62–0.705) with empirically measured
 * retracement statistics per instrument.
 *
 * Tracks a rolling window of completed pullbacks within trending moves,
 * computing mean/stdDev/percentiles of retracement depth. The entry zone
 * is derived from statistical reality rather than arbitrary Fibonacci ratios.
 *
 * Key design decisions:
 * - Pullbacks are detected retrospectively (only after trend resumes) to avoid look-ahead bias
 * - Only pullbacks where the trend continued are used for entry zone calculation
 * - Falls back to classic Fibonacci OTE when insufficient data (< 15 pullbacks)
 * - Fibonacci confluence is tracked as a bonus signal (other traders watch Fib levels)
 *
 * Fed by {@link SwingPointListener} callbacks from {@link IctStructureDetector}.
 */
public class StatisticalRetracementEngine implements SwingPointListener {

    private final String symbol;
    private final int windowSize;
    private final LinkedList<PullbackRecord> pullbackHistory;

    // Cached statistics (recalculated on each new pullback)
    private double meanDepth;
    private double stdDevDepth;
    private double p25Depth;
    private double p75Depth;
    private double meanImpulseSize;
    private double stdDevImpulseSize;
    private boolean initialized;

    // Fallback Fibonacci values
    private static final double FIB_LOW = 0.62;
    private static final double FIB_HIGH = 0.705;
    private static final int MIN_SAMPLES = 15;

    // Fibonacci levels for confluence check
    private static final double[] FIB_LEVELS = {0.5, 0.618, 0.705, 0.786};
    private static final double FIB_CONFLUENCE_TOLERANCE = 0.05;

    // Confidence thresholds
    private static final double HIGH_CONFIDENCE_STDDEV = 0.08;
    private static final double LOW_CONFIDENCE_STDDEV = 0.15;

    // For regime stability: track recent stddev values
    private final LinkedList<Double> recentStdDevs;
    private static final int STABILITY_WINDOW = 10;

    public StatisticalRetracementEngine(String symbol, int windowSize) {
        this.symbol = symbol;
        this.windowSize = windowSize;
        this.pullbackHistory = new LinkedList<>();
        this.recentStdDevs = new LinkedList<>();
        this.meanDepth = 0;
        this.stdDevDepth = 0;
        this.p25Depth = 0;
        this.p75Depth = 0;
        this.meanImpulseSize = 0;
        this.stdDevImpulseSize = 0;
        this.initialized = false;
    }

    /**
     * Record a completed pullback within a trend.
     *
     * Call this whenever IctStructureDetector confirms a new swing point
     * that completes a pullback cycle.
     *
     * @param impulseRange The size of the impulse move (in points) before the pullback
     * @param pullbackRange The size of the pullback (in points)
     * @param durationBars How many 1m candles the pullback lasted
     * @param continued Did price resume the trend after the pullback?
     */
    public void recordPullback(double impulseRange, double pullbackRange,
                                int durationBars, boolean continued) {
        if (impulseRange <= 0) return;

        double depthPct = pullbackRange / impulseRange;

        PullbackRecord record = new PullbackRecord(
                depthPct, impulseRange, durationBars, continued, Instant.now());

        pullbackHistory.add(record);

        while (pullbackHistory.size() > windowSize) {
            pullbackHistory.removeFirst();
        }

        // Recalculate using only pullbacks where trend continued
        long continuedCount = pullbackHistory.stream()
                .filter(p -> p.didContinue)
                .count();

        if (continuedCount >= MIN_SAMPLES) {
            recalculateStatistics();
            if (!initialized) {
                initialized = true;
                System.out.println("[" + symbol + "] StatisticalRetracementEngine INITIALIZED: " +
                        "meanDepth=" + String.format("%.3f", meanDepth) +
                        ", σ=" + String.format("%.3f", stdDevDepth) +
                        ", IQR=[" + String.format("%.3f", p25Depth) + "-" + String.format("%.3f", p75Depth) + "]" +
                        ", fibConfluence=" + hasFibConfluence() +
                        ", samples=" + continuedCount);
            }
        }
    }

    /**
     * SwingPointListener callback — called when IctStructureDetector detects
     * a confirmed pullback completion.
     */
    @Override
    public void onPullbackCompleted(double impulseRange, double pullbackRange,
                                     int durationBars, boolean continued) {
        recordPullback(impulseRange, pullbackRange, durationBars, continued);
    }

    /**
     * SwingPointListener callback — called when a new impulse completes.
     * Not directly used by this engine (ImpulseExtensionAnalyzer handles this).
     */
    @Override
    public void onImpulseCompleted(double impulseSize, boolean bullish) {
        // No-op: impulse data is handled by ImpulseExtensionAnalyzer
    }

    private void recalculateStatistics() {
        List<Double> continuedDepths = new ArrayList<>();
        List<Double> impulseSizes = new ArrayList<>();

        for (PullbackRecord p : pullbackHistory) {
            if (p.didContinue) {
                continuedDepths.add(p.retracementDepthPct);
                impulseSizes.add(p.impulseSize);
            }
        }

        if (continuedDepths.isEmpty()) return;

        // Mean and stddev of retracement depth
        meanDepth = computeMean(continuedDepths);
        stdDevDepth = computeStdDev(continuedDepths, meanDepth);

        // Track stddev for regime stability
        recentStdDevs.add(stdDevDepth);
        while (recentStdDevs.size() > STABILITY_WINDOW) {
            recentStdDevs.removeFirst();
        }

        // Percentiles (P25, P75)
        List<Double> sorted = new ArrayList<>(continuedDepths);
        Collections.sort(sorted);
        p25Depth = percentile(sorted, 25);
        p75Depth = percentile(sorted, 75);

        // Impulse size statistics
        meanImpulseSize = computeMean(impulseSizes);
        stdDevImpulseSize = computeStdDev(impulseSizes, meanImpulseSize);
    }

    /**
     * PRIMARY METHOD: Get the statistically-derived entry zone.
     *
     * For a bullish entry after a sweep of swingLow:
     *   entryZoneLow  = swingLevel + impulseRange * (meanDepth - 0.5σ)
     *   entryZoneHigh = swingLevel + impulseRange * (meanDepth + 0.5σ)
     *   optimalEntry  = midpoint of zone
     *
     * Falls back to Fibonacci OTE (0.62–0.705) when not initialized.
     *
     * @param swingLevel   The swept swing level (low for bullish, high for bearish)
     * @param impulseRange The range of the impulse move
     * @param bullish      Direction
     * @return double[3]: {entryZoneLow, optimalEntry, entryZoneHigh}
     */
    public double[] getEntryZone(double swingLevel, double impulseRange, boolean bullish) {
        double zoneLowPct;
        double zoneHighPct;

        if (!initialized) {
            // Fallback to Fibonacci OTE
            zoneLowPct = FIB_LOW;
            zoneHighPct = FIB_HIGH;
        } else {
            zoneLowPct = meanDepth - 0.5 * stdDevDepth;
            zoneHighPct = meanDepth + 0.5 * stdDevDepth;

            // Clamp to reasonable bounds (0.3 to 1.0)
            zoneLowPct = Math.max(0.3, Math.min(zoneLowPct, 0.95));
            zoneHighPct = Math.max(zoneLowPct + 0.02, Math.min(zoneHighPct, 1.0));
        }

        double entryZoneLow;
        double entryZoneHigh;
        double optimalEntry;

        if (bullish) {
            entryZoneLow = swingLevel + impulseRange * zoneLowPct;
            entryZoneHigh = swingLevel + impulseRange * zoneHighPct;
        } else {
            entryZoneHigh = swingLevel - impulseRange * zoneLowPct;
            entryZoneLow = swingLevel - impulseRange * zoneHighPct;
        }

        optimalEntry = (entryZoneLow + entryZoneHigh) / 2.0;

        return new double[]{entryZoneLow, optimalEntry, entryZoneHigh};
    }

    /**
     * SECONDARY METHOD: Get confidence level for entry zone.
     *
     * Based on how tight the standard deviation is:
     * - stdDev < 0.08: HIGH — pullbacks are consistent
     * - stdDev 0.08–0.15: NORMAL
     * - stdDev > 0.15: LOW — pullbacks are erratic
     *
     * Also factors in Fibonacci confluence as a bonus.
     */
    public EntryConfidence getConfidence() {
        if (!initialized) {
            return EntryConfidence.UNINITIALIZED;
        }

        if (stdDevDepth < HIGH_CONFIDENCE_STDDEV) {
            return EntryConfidence.HIGH;
        } else if (stdDevDepth > LOW_CONFIDENCE_STDDEV) {
            return EntryConfidence.LOW;
        }
        return EntryConfidence.NORMAL;
    }

    /**
     * Check if the measured statistics align with Fibonacci levels.
     * When they do, there's a confluence between statistical reality
     * and the levels other traders are watching.
     *
     * @return true if meanDepth is within 0.05 of any major Fib level
     */
    public boolean hasFibConfluence() {
        if (!initialized) return false;

        for (double fib : FIB_LEVELS) {
            if (Math.abs(meanDepth - fib) <= FIB_CONFLUENCE_TOLERANCE) {
                return true;
            }
        }
        return false;
    }

    /**
     * Get regime stability: coefficient of variation of recent stddev values.
     * High values mean the market is transitioning between regimes and the
     * retracement model is unreliable.
     *
     * @return regimeStability score (0 = very stable, 1+ = transitioning)
     */
    public double getRegimeStability() {
        if (recentStdDevs.size() < 3) return 0;

        double mean = computeMean(new ArrayList<>(recentStdDevs));
        if (mean == 0) return 0;

        double stddev = computeStdDev(new ArrayList<>(recentStdDevs), mean);
        return stddev / mean; // Coefficient of variation
    }

    // ---- Getters ----

    public double getMeanDepth() {
        return meanDepth;
    }

    public double getStdDevDepth() {
        return stdDevDepth;
    }

    public double getP25Depth() {
        return p25Depth;
    }

    public double getP75Depth() {
        return p75Depth;
    }

    public double getMeanImpulseSize() {
        return meanImpulseSize;
    }

    public double getStdDevImpulseSize() {
        return stdDevImpulseSize;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public int getSampleCount() {
        return (int) pullbackHistory.stream().filter(p -> p.didContinue).count();
    }

    // ---- Internal helpers ----

    private double computeMean(List<Double> values) {
        if (values.isEmpty()) return 0;
        double sum = 0;
        for (double v : values) sum += v;
        return sum / values.size();
    }

    private double computeStdDev(List<Double> values, double mean) {
        if (values.isEmpty()) return 0;
        double sumSqDiff = 0;
        for (double v : values) {
            double diff = v - mean;
            sumSqDiff += diff * diff;
        }
        return Math.sqrt(sumSqDiff / values.size());
    }

    private double percentile(List<Double> sortedValues, int percentile) {
        if (sortedValues.isEmpty()) return 0;
        double idx = (percentile / 100.0) * (sortedValues.size() - 1);
        int lower = (int) Math.floor(idx);
        int upper = (int) Math.ceil(idx);
        if (lower == upper) return sortedValues.get(lower);
        double frac = idx - lower;
        return sortedValues.get(lower) * (1 - frac) + sortedValues.get(upper) * frac;
    }

    // ---- Inner classes ----

    /**
     * Record of a single completed pullback within a trend.
     */
    public static class PullbackRecord {
        public final double retracementDepthPct;  // 0.0 to 1.0+
        public final double impulseSize;           // points
        public final int pullbackDurationBars;
        public final boolean didContinue;
        public final Instant timestamp;

        public PullbackRecord(double retracementDepthPct, double impulseSize,
                              int pullbackDurationBars, boolean didContinue,
                              Instant timestamp) {
            this.retracementDepthPct = retracementDepthPct;
            this.impulseSize = impulseSize;
            this.pullbackDurationBars = pullbackDurationBars;
            this.didContinue = didContinue;
            this.timestamp = timestamp;
        }
    }

    public enum EntryConfidence {
        HIGH,           // stdDev < 0.08 — pullbacks are very consistent
        NORMAL,         // stdDev 0.08–0.15
        LOW,            // stdDev > 0.15 — pullbacks are erratic
        UNINITIALIZED   // Not enough data yet
    }
}
