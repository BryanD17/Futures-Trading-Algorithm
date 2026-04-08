package com.topstep.trading.strategy;

import com.topstep.trading.domain.Candle;
import java.util.LinkedList;

/**
 * Detects volatility regime transitions to gate statistical model reliability.
 *
 * Compares short-term σ of candle ranges against long-term σ to classify
 * the current volatility regime:
 * - STABLE:        ratio ≈ 1.0 — statistical models are reliable
 * - EXPANDING:     ratio > 1.5 — volatility increasing, widen zones
 * - CONTRACTING:   ratio < 0.6 — volatility decreasing, tighten zones
 * - TRANSITIONING: σ of σ is high — regime shift in progress, models unreliable
 */
public class VolatilityRegimeDetector {

    private final String symbol;
    private final int shortWindow;
    private final int longWindow;
    private final LinkedList<Double> shortRanges;
    private final LinkedList<Double> longRanges;

    // For tracking regime stability (σ of σ)
    private final LinkedList<Double> recentShortStdDevs;
    private static final int STABILITY_WINDOW = 10;

    // Regime thresholds
    private static final double EXPANDING_THRESHOLD = 1.5;
    private static final double CONTRACTING_THRESHOLD = 0.6;
    private static final double TRANSITIONING_SIGMA_OF_SIGMA = 0.4;

    private boolean initialized;

    public VolatilityRegimeDetector(String symbol, int shortWindow, int longWindow) {
        this.symbol = symbol;
        this.shortWindow = shortWindow;
        this.longWindow = longWindow;
        this.shortRanges = new LinkedList<>();
        this.longRanges = new LinkedList<>();
        this.recentShortStdDevs = new LinkedList<>();
        this.initialized = false;
    }

    /**
     * Update with each new candle.
     */
    public void update(Candle candle) {
        double range = candle.getHigh() - candle.getLow();

        shortRanges.add(range);
        longRanges.add(range);

        while (shortRanges.size() > shortWindow) {
            shortRanges.removeFirst();
        }
        while (longRanges.size() > longWindow) {
            longRanges.removeFirst();
        }

        if (longRanges.size() >= longWindow) {
            if (!initialized) {
                initialized = true;
                System.out.println("[" + symbol + "] VolatilityRegimeDetector INITIALIZED: " +
                        "regime=" + getRegime() + ", ratio=" + String.format("%.2f", getRegimeRatio()));
            }

            // Track short-term stddev for stability measurement
            double shortStd = computeStdDev(shortRanges);
            recentShortStdDevs.add(shortStd);
            while (recentShortStdDevs.size() > STABILITY_WINDOW) {
                recentShortStdDevs.removeFirst();
            }
        }
    }

    /**
     * The core metric: ratio of short-term σ to long-term σ.
     *
     * - ratio ≈ 1.0: stable — statistical models are reliable
     * - ratio > 1.5: expanding — market transitioning, models less reliable
     * - ratio < 0.6: contracting — breakout imminent, tighten zones
     */
    public double getRegimeRatio() {
        if (!initialized) {
            return 1.0;
        }

        double shortStd = computeStdDev(shortRanges);
        double longStd = computeStdDev(longRanges);

        if (longStd == 0) {
            return 1.0;
        }
        return shortStd / longStd;
    }

    /**
     * Get the current regime classification.
     */
    public VolatilityRegime getRegime() {
        if (!initialized) {
            return VolatilityRegime.STABLE;
        }

        // Check for transitioning first (σ of σ is high)
        if (isTransitioning()) {
            return VolatilityRegime.TRANSITIONING;
        }

        double ratio = getRegimeRatio();

        if (ratio > EXPANDING_THRESHOLD) {
            return VolatilityRegime.EXPANDING;
        } else if (ratio < CONTRACTING_THRESHOLD) {
            return VolatilityRegime.CONTRACTING;
        }
        return VolatilityRegime.STABLE;
    }

    /**
     * Check if regime is transitioning by measuring σ of recent short-term σ values.
     */
    private boolean isTransitioning() {
        if (recentShortStdDevs.size() < STABILITY_WINDOW) {
            return false;
        }
        double sigmaOfSigma = computeStdDev(recentShortStdDevs);
        double meanSigma = computeMean(recentShortStdDevs);
        if (meanSigma == 0) {
            return false;
        }
        // Coefficient of variation of short-term σ
        return (sigmaOfSigma / meanSigma) > TRANSITIONING_SIGMA_OF_SIGMA;
    }

    /**
     * Get a multiplier to apply to statistical zone widths.
     *
     * STABLE: 1.0 (use zones as-is)
     * EXPANDING: 1.3 (widen zones by 30%)
     * CONTRACTING: 0.8 (tighten zones)
     * TRANSITIONING: 1.5 (maximum caution)
     */
    public double getZoneWidthMultiplier() {
        switch (getRegime()) {
            case EXPANDING:
                return 1.3;
            case CONTRACTING:
                return 0.8;
            case TRANSITIONING:
                return 1.5;
            default:
                return 1.0;
        }
    }

    /**
     * Should the strategy reduce confidence in statistical models?
     * True when regime is TRANSITIONING.
     */
    public boolean isModelUnreliable() {
        return getRegime() == VolatilityRegime.TRANSITIONING;
    }

    public boolean isInitialized() {
        return initialized;
    }

    private double computeStdDev(LinkedList<Double> values) {
        if (values.isEmpty()) return 0;
        double mean = computeMean(values);
        double sumSqDiff = 0;
        for (double v : values) {
            double diff = v - mean;
            sumSqDiff += diff * diff;
        }
        return Math.sqrt(sumSqDiff / values.size());
    }

    private double computeMean(LinkedList<Double> values) {
        if (values.isEmpty()) return 0;
        double sum = 0;
        for (double v : values) {
            sum += v;
        }
        return sum / values.size();
    }

    public enum VolatilityRegime {
        STABLE,         // Normal conditions — trust statistical models
        EXPANDING,      // Volatility increasing — widen zones, be cautious
        CONTRACTING,    // Volatility decreasing — tighten zones, breakout coming
        TRANSITIONING;  // Regime shift in progress — models unreliable

        public String getDescription() {
            switch (this) {
                case STABLE: return "Stable — statistical models reliable";
                case EXPANDING: return "Expanding — widen zones, increased noise";
                case CONTRACTING: return "Contracting — tighten zones, breakout imminent";
                case TRANSITIONING: return "Transitioning — models unreliable, use caution";
                default: return "Unknown";
            }
        }
    }
}
