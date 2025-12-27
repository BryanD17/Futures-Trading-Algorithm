package com.topstep.trading.strategy;

import com.topstep.trading.domain.Candle;
import java.util.LinkedList;

/**
 * Calculates Average True Range (ATR) for volatility-adaptive position sizing.
 *
 * ATR measures market volatility by decomposing the entire range of an asset
 * for that period. True Range is the greatest of:
 * - Current High minus Current Low
 * - Absolute value of Current High minus Previous Close
 * - Absolute value of Current Low minus Previous Close
 *
 * ATR is then the moving average of the True Range values.
 *
 * Position sizing based on ATR:
 * - Low volatility (ATR < 20 pts NQ)  -> 2 contracts, tighter stops
 * - Normal volatility                  -> 1 contract, standard stops
 * - High volatility (ATR > 40 pts NQ) -> 1 contract, wider stops OR skip
 */
public class ATRCalculator {

    private final int period;
    private final LinkedList<Double> trueRanges;
    private double currentAtr;
    private double previousClose;
    private boolean initialized;

    // Volatility thresholds for NQ (can be adjusted per instrument)
    private double lowVolatilityThreshold;
    private double highVolatilityThreshold;

    public ATRCalculator(int period) {
        this.period = period;
        this.trueRanges = new LinkedList<>();
        this.currentAtr = 0;
        this.previousClose = 0;
        this.initialized = false;

        // Default thresholds for NQ
        this.lowVolatilityThreshold = 20.0;
        this.highVolatilityThreshold = 40.0;
    }

    /**
     * Create ATR calculator with custom volatility thresholds.
     */
    public ATRCalculator(int period, double lowThreshold, double highThreshold) {
        this(period);
        this.lowVolatilityThreshold = lowThreshold;
        this.highVolatilityThreshold = highThreshold;
    }

    /**
     * Update ATR with a new candle.
     */
    public void update(Candle candle) {
        double trueRange;

        if (previousClose == 0) {
            // First candle - use high-low range
            trueRange = candle.getHigh() - candle.getLow();
        } else {
            // Calculate True Range
            double highLow = candle.getHigh() - candle.getLow();
            double highPrevClose = Math.abs(candle.getHigh() - previousClose);
            double lowPrevClose = Math.abs(candle.getLow() - previousClose);

            trueRange = Math.max(highLow, Math.max(highPrevClose, lowPrevClose));
        }

        trueRanges.add(trueRange);

        // Keep only the period number of true ranges
        while (trueRanges.size() > period) {
            trueRanges.removeFirst();
        }

        // Calculate ATR (simple moving average of true ranges)
        if (trueRanges.size() >= period) {
            currentAtr = trueRanges.stream()
                    .mapToDouble(Double::doubleValue)
                    .average()
                    .orElse(0);
            initialized = true;
        }

        previousClose = candle.getClose();
    }

    /**
     * Get the current ATR value.
     */
    public double getAtr() {
        return currentAtr;
    }

    /**
     * Alias for getAtr() - more descriptive name.
     */
    public double getCurrentAtr() {
        return currentAtr;
    }

    /**
     * Check if ATR has been initialized (enough data).
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Get volatility level based on current ATR.
     */
    public VolatilityLevel getVolatilityLevel() {
        if (!initialized) {
            return VolatilityLevel.NORMAL;
        }

        if (currentAtr < lowVolatilityThreshold) {
            return VolatilityLevel.LOW;
        } else if (currentAtr > highVolatilityThreshold) {
            return VolatilityLevel.HIGH;
        }
        return VolatilityLevel.NORMAL;
    }

    /**
     * Get recommended position size based on volatility.
     *
     * @param baseSize Base position size (e.g., 1 contract)
     * @param maxSize Maximum position size (e.g., 2 contracts)
     * @return Recommended position size
     */
    public int getRecommendedPositionSize(int baseSize, int maxSize) {
        VolatilityLevel level = getVolatilityLevel();

        switch (level) {
            case LOW:
                // Low volatility - can increase size
                return Math.min(baseSize * 2, maxSize);
            case HIGH:
                // High volatility - reduce or maintain size
                return baseSize;
            case EXTREME:
                // Extreme volatility - consider skipping
                return 0;
            default:
                return baseSize;
        }
    }

    /**
     * Get stop loss multiplier based on volatility.
     * Higher volatility = wider stops.
     */
    public double getStopMultiplier() {
        VolatilityLevel level = getVolatilityLevel();

        switch (level) {
            case LOW:
                return 0.8;  // Tighter stops in low volatility
            case HIGH:
                return 1.5;  // Wider stops in high volatility
            case EXTREME:
                return 2.0;  // Much wider stops
            default:
                return 1.0;  // Standard stops
        }
    }

    /**
     * Calculate ATR-based stop distance.
     *
     * @param multiplier ATR multiplier (e.g., 1.5 for 1.5x ATR stop)
     * @return Stop distance in points
     */
    public double getAtrStopDistance(double multiplier) {
        return currentAtr * multiplier;
    }

    /**
     * Check if current volatility is suitable for trading.
     */
    public boolean isTradeable() {
        VolatilityLevel level = getVolatilityLevel();
        return level != VolatilityLevel.EXTREME;
    }

    /**
     * Get ATR as percentage of price.
     */
    public double getAtrPercent(double currentPrice) {
        if (currentPrice == 0) return 0;
        return (currentAtr / currentPrice) * 100;
    }

    /**
     * Set custom volatility thresholds.
     */
    public void setThresholds(double lowThreshold, double highThreshold) {
        this.lowVolatilityThreshold = lowThreshold;
        this.highVolatilityThreshold = highThreshold;
    }

    /**
     * Reset the calculator.
     */
    public void reset() {
        trueRanges.clear();
        currentAtr = 0;
        previousClose = 0;
        initialized = false;
    }

    /**
     * Get a summary of current volatility state.
     */
    public String getVolatilitySummary() {
        return String.format("ATR: %.2f | Level: %s | Stop Mult: %.1fx | Tradeable: %s",
                currentAtr, getVolatilityLevel(), getStopMultiplier(), isTradeable());
    }

    /**
     * Volatility levels for position sizing and stop adjustment.
     */
    public enum VolatilityLevel {
        LOW("Low volatility - can increase size"),
        NORMAL("Normal volatility - standard sizing"),
        HIGH("High volatility - wider stops needed"),
        EXTREME("Extreme volatility - consider skipping");

        private final String description;

        VolatilityLevel(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }
}
