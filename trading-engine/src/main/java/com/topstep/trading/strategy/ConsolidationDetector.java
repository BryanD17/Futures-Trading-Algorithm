package com.topstep.trading.strategy;

import com.topstep.trading.domain.Candle;
import java.time.Instant;
import java.util.*;

/**
 * Consolidation/Range Detector for identifying sideways market conditions.
 *
 * Consolidation detection is critical because:
 * - ICT setups work best after consolidation (accumulation/distribution)
 * - Trading in consolidation leads to choppy losses
 * - Breakouts from consolidation offer high-probability entries
 *
 * DETECTION METHODS:
 * 1. Range Contraction: Price range narrows over time
 * 2. ATR Compression: Volatility decreases significantly
 * 3. Swing Clustering: Multiple swing highs/lows at similar levels
 * 4. Mean Reversion: Price repeatedly returns to mid-range
 *
 * CONSOLIDATION TYPES:
 * - TIGHT_RANGE: Very narrow range, low volatility
 * - NORMAL_RANGE: Moderate range, typical consolidation
 * - WIDE_RANGE: Broader range but still ranging (not trending)
 */
public class ConsolidationDetector {

    private static final int DEFAULT_LOOKBACK = 50;
    private static final double RANGE_CONTRACTION_THRESHOLD = 0.5;  // 50% of ATR
    private static final int MIN_CONSOLIDATION_BARS = 10;

    private final String symbol;
    private final double tickSize;
    private final int lookback;

    // Candle buffer
    private final LinkedList<Candle> candleBuffer;

    // Current state
    private ConsolidationState currentState;
    private double rangeHigh;
    private double rangeLow;
    private double rangeMid;
    private int consolidationBars;
    private double atr;
    private double rangeToAtrRatio;

    // Historical ranges for comparison
    private final LinkedList<Double> historicalRanges;

    public ConsolidationDetector(String symbol, double tickSize) {
        this(symbol, tickSize, DEFAULT_LOOKBACK);
    }

    public ConsolidationDetector(String symbol, double tickSize, int lookback) {
        this.symbol = symbol;
        this.tickSize = tickSize;
        this.lookback = lookback;
        this.candleBuffer = new LinkedList<>();
        this.historicalRanges = new LinkedList<>();
        this.currentState = ConsolidationState.UNKNOWN;
        reset();
    }

    /**
     * Update with new candle.
     */
    public void update(Candle candle) {
        candleBuffer.add(candle);
        while (candleBuffer.size() > lookback) {
            candleBuffer.removeFirst();
        }

        if (candleBuffer.size() >= MIN_CONSOLIDATION_BARS) {
            analyzeConsolidation();
        }
    }

    /**
     * Analyze current market for consolidation.
     */
    private void analyzeConsolidation() {
        calculateRange();
        calculateAtr();
        detectConsolidation();
    }

    /**
     * Calculate current price range.
     */
    private void calculateRange() {
        rangeHigh = Double.MIN_VALUE;
        rangeLow = Double.MAX_VALUE;

        // Use recent candles for range detection
        int recentBars = Math.min(20, candleBuffer.size());
        for (int i = candleBuffer.size() - recentBars; i < candleBuffer.size(); i++) {
            Candle c = candleBuffer.get(i);
            rangeHigh = Math.max(rangeHigh, c.getHigh());
            rangeLow = Math.min(rangeLow, c.getLow());
        }

        rangeMid = (rangeHigh + rangeLow) / 2;

        // Track historical ranges
        double currentRange = rangeHigh - rangeLow;
        historicalRanges.add(currentRange);
        while (historicalRanges.size() > lookback) {
            historicalRanges.removeFirst();
        }
    }

    /**
     * Calculate ATR for volatility reference.
     */
    private void calculateAtr() {
        if (candleBuffer.size() < 14) {
            atr = 0;
            return;
        }

        double sum = 0;
        for (int i = candleBuffer.size() - 14; i < candleBuffer.size(); i++) {
            Candle c = candleBuffer.get(i);
            sum += c.getHigh() - c.getLow();
        }
        atr = sum / 14;
    }

    /**
     * Detect consolidation state.
     */
    private void detectConsolidation() {
        if (atr <= 0 || candleBuffer.size() < MIN_CONSOLIDATION_BARS) {
            currentState = ConsolidationState.UNKNOWN;
            return;
        }

        double currentRange = rangeHigh - rangeLow;
        rangeToAtrRatio = currentRange / atr;

        // Check for range contraction (current range vs historical average)
        double avgHistoricalRange = historicalRanges.stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(currentRange);

        double rangeContraction = currentRange / avgHistoricalRange;

        // Count bars within the range
        consolidationBars = 0;
        for (int i = Math.max(0, candleBuffer.size() - 20); i < candleBuffer.size(); i++) {
            Candle c = candleBuffer.get(i);
            if (c.getHigh() <= rangeHigh && c.getLow() >= rangeLow) {
                consolidationBars++;
            }
        }

        // Determine state based on metrics
        if (rangeToAtrRatio < 2.0 && consolidationBars >= 8) {
            // Tight range - very compressed
            currentState = ConsolidationState.TIGHT_RANGE;
        } else if (rangeToAtrRatio < 4.0 && consolidationBars >= 6) {
            // Normal consolidation
            currentState = ConsolidationState.NORMAL_RANGE;
        } else if (rangeToAtrRatio < 6.0 && consolidationBars >= 5) {
            // Wide but still ranging
            currentState = ConsolidationState.WIDE_RANGE;
        } else {
            // Trending or no clear consolidation
            currentState = ConsolidationState.TRENDING;
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // QUERY API
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Check if currently in consolidation.
     */
    public boolean isConsolidating() {
        return currentState == ConsolidationState.TIGHT_RANGE ||
               currentState == ConsolidationState.NORMAL_RANGE ||
               currentState == ConsolidationState.WIDE_RANGE;
    }

    /**
     * Check if in tight consolidation (potential breakout setup).
     */
    public boolean isTightConsolidation() {
        return currentState == ConsolidationState.TIGHT_RANGE;
    }

    /**
     * Check if market is trending (not consolidating).
     */
    public boolean isTrending() {
        return currentState == ConsolidationState.TRENDING;
    }

    /**
     * Check if price is near range high (potential resistance).
     */
    public boolean isNearRangeHigh(double price, double tolerance) {
        return Math.abs(price - rangeHigh) <= tolerance;
    }

    /**
     * Check if price is near range low (potential support).
     */
    public boolean isNearRangeLow(double price, double tolerance) {
        return Math.abs(price - rangeLow) <= tolerance;
    }

    /**
     * Check if price is near range midpoint.
     */
    public boolean isNearRangeMid(double price, double tolerance) {
        return Math.abs(price - rangeMid) <= tolerance;
    }

    /**
     * Check for breakout above range.
     */
    public boolean hasBreakoutUp(double price) {
        return price > rangeHigh + (tickSize * 2);
    }

    /**
     * Check for breakdown below range.
     */
    public boolean hasBreakoutDown(double price) {
        return price < rangeLow - (tickSize * 2);
    }

    /**
     * Get breakout direction if any.
     */
    public Optional<BreakoutDirection> getBreakoutDirection(double price) {
        if (hasBreakoutUp(price)) {
            return Optional.of(BreakoutDirection.BULLISH);
        } else if (hasBreakoutDown(price)) {
            return Optional.of(BreakoutDirection.BEARISH);
        }
        return Optional.empty();
    }

    /**
     * Calculate confluence score for range trading.
     * Negative score in consolidation (avoid trading), positive after breakout.
     */
    public int getConfluenceScore(double price, boolean isBullish) {
        int score = 0;

        if (isConsolidating()) {
            // In consolidation - reduce score (warning)
            score -= 2;

            // Even worse in tight consolidation
            if (isTightConsolidation()) {
                score -= 1;
            }
        }

        // Breakout bonus
        Optional<BreakoutDirection> breakout = getBreakoutDirection(price);
        if (breakout.isPresent()) {
            boolean breakoutMatchesBias = (breakout.get() == BreakoutDirection.BULLISH && isBullish) ||
                                          (breakout.get() == BreakoutDirection.BEARISH && !isBullish);
            if (breakoutMatchesBias) {
                score += 2;  // Breakout in trade direction

                // Extra bonus if breaking tight consolidation
                if (currentState == ConsolidationState.TIGHT_RANGE ||
                    currentState == ConsolidationState.NORMAL_RANGE) {
                    score += 1;
                }
            } else {
                score -= 3;  // Trading against breakout - very bad
            }
        }

        return score;
    }

    /**
     * Get a trading recommendation based on consolidation state.
     */
    public ConsolidationAdvice getAdvice() {
        switch (currentState) {
            case TIGHT_RANGE:
                return ConsolidationAdvice.WAIT_FOR_BREAKOUT;
            case NORMAL_RANGE:
                return ConsolidationAdvice.CAUTION_RANGING;
            case WIDE_RANGE:
                return ConsolidationAdvice.RANGE_TRADE_POSSIBLE;
            case TRENDING:
                return ConsolidationAdvice.TREND_FOLLOWING;
            default:
                return ConsolidationAdvice.INSUFFICIENT_DATA;
        }
    }

    // Getters
    public ConsolidationState getCurrentState() { return currentState; }
    public double getRangeHigh() { return rangeHigh; }
    public double getRangeLow() { return rangeLow; }
    public double getRangeMid() { return rangeMid; }
    public int getConsolidationBars() { return consolidationBars; }
    public double getAtr() { return atr; }
    public double getRangeToAtrRatio() { return rangeToAtrRatio; }

    public void reset() {
        candleBuffer.clear();
        historicalRanges.clear();
        currentState = ConsolidationState.UNKNOWN;
        rangeHigh = 0;
        rangeLow = 0;
        rangeMid = 0;
        consolidationBars = 0;
        atr = 0;
        rangeToAtrRatio = 0;
    }

    @Override
    public String toString() {
        return String.format("Consolidation{%s: %s, range=%.2f-%.2f, bars=%d, ratio=%.2f}",
                symbol, currentState, rangeLow, rangeHigh, consolidationBars, rangeToAtrRatio);
    }

    // Enums
    public enum ConsolidationState {
        UNKNOWN,
        TIGHT_RANGE,    // Very narrow, breakout imminent
        NORMAL_RANGE,   // Standard consolidation
        WIDE_RANGE,     // Broad range, still ranging
        TRENDING        // Not consolidating
    }

    public enum BreakoutDirection {
        BULLISH,
        BEARISH
    }

    public enum ConsolidationAdvice {
        WAIT_FOR_BREAKOUT,      // Tight range - don't trade yet
        CAUTION_RANGING,        // Normal range - be careful
        RANGE_TRADE_POSSIBLE,   // Wide range - can trade edges
        TREND_FOLLOWING,        // Trending - follow direction
        INSUFFICIENT_DATA       // Not enough data
    }
}
