package com.topstep.trading.chartstate;

import com.topstep.trading.domain.Candle;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Ring buffer for storing candle history per instrument.
 *
 * Uses a fixed-size circular buffer for memory efficiency.
 * Default capacity of 5000 candles = ~3.5 days of 1-minute data.
 *
 * Memory usage: ~200 bytes per candle × 5000 = ~1MB per instrument.
 */
public class CandleSeries {

    private static final int DEFAULT_CAPACITY = 5000;

    private final String symbol;
    private final int capacity;
    private final Candle[] candles;
    private int head;          // Next write position
    private int size;          // Current number of candles
    private int totalAdded;    // Total candles ever added (for bar indexing)

    public CandleSeries(String symbol) {
        this(symbol, DEFAULT_CAPACITY);
    }

    public CandleSeries(String symbol, int capacity) {
        this.symbol = symbol;
        this.capacity = capacity;
        this.candles = new Candle[capacity];
        this.head = 0;
        this.size = 0;
        this.totalAdded = 0;
    }

    // ═══════════════════════════════════════════════════════════════════
    // CORE OPERATIONS
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Add a new candle to the series.
     */
    public synchronized void addCandle(Candle candle) {
        candles[head] = candle;
        head = (head + 1) % capacity;
        if (size < capacity) {
            size++;
        }
        totalAdded++;
    }

    /**
     * Get candle at N bars ago (0 = most recent).
     * Returns null if out of range.
     */
    public synchronized Candle getCandle(int barsAgo) {
        if (barsAgo < 0 || barsAgo >= size) {
            return null;
        }
        int index = (head - 1 - barsAgo + capacity) % capacity;
        return candles[index];
    }

    /**
     * Get the most recent candle.
     */
    public synchronized Candle getLatestCandle() {
        return getCandle(0);
    }

    /**
     * Get the close price of the most recent candle.
     * @return Optional containing the close price, or empty if no candles
     */
    public synchronized java.util.Optional<Double> getLatestClose() {
        Candle latest = getLatestCandle();
        if (latest != null) {
            return java.util.Optional.of(latest.getClose());
        }
        return java.util.Optional.empty();
    }

    /**
     * Get a range of candles (most recent first).
     * @param startBarsAgo Start position (0 = most recent)
     * @param endBarsAgo End position (exclusive)
     */
    public synchronized List<Candle> getRange(int startBarsAgo, int endBarsAgo) {
        List<Candle> result = new ArrayList<>();
        for (int i = startBarsAgo; i < endBarsAgo && i < size; i++) {
            Candle candle = getCandle(i);
            if (candle != null) {
                result.add(candle);
            }
        }
        return result;
    }

    /**
     * Get the last N candles (most recent first).
     */
    public synchronized List<Candle> getLast(int n) {
        return getRange(0, Math.min(n, size));
    }

    // ═══════════════════════════════════════════════════════════════════
    // PRICE ANALYSIS
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Get highest high in last N bars.
     */
    public synchronized double getHighest(int lookback) {
        double highest = Double.MIN_VALUE;
        int count = Math.min(lookback, size);
        for (int i = 0; i < count; i++) {
            Candle c = getCandle(i);
            if (c != null && c.getHigh() > highest) {
                highest = c.getHigh();
            }
        }
        return highest == Double.MIN_VALUE ? 0 : highest;
    }

    /**
     * Get lowest low in last N bars.
     */
    public synchronized double getLowest(int lookback) {
        double lowest = Double.MAX_VALUE;
        int count = Math.min(lookback, size);
        for (int i = 0; i < count; i++) {
            Candle c = getCandle(i);
            if (c != null && c.getLow() < lowest) {
                lowest = c.getLow();
            }
        }
        return lowest == Double.MAX_VALUE ? 0 : lowest;
    }

    /**
     * Get highest high with bar index.
     * @return [0] = price, [1] = bars ago
     */
    public synchronized double[] getHighestWithIndex(int lookback) {
        double highest = Double.MIN_VALUE;
        int highestIndex = 0;
        int count = Math.min(lookback, size);
        for (int i = 0; i < count; i++) {
            Candle c = getCandle(i);
            if (c != null && c.getHigh() > highest) {
                highest = c.getHigh();
                highestIndex = i;
            }
        }
        return new double[]{highest == Double.MIN_VALUE ? 0 : highest, highestIndex};
    }

    /**
     * Get lowest low with bar index.
     * @return [0] = price, [1] = bars ago
     */
    public synchronized double[] getLowestWithIndex(int lookback) {
        double lowest = Double.MAX_VALUE;
        int lowestIndex = 0;
        int count = Math.min(lookback, size);
        for (int i = 0; i < count; i++) {
            Candle c = getCandle(i);
            if (c != null && c.getLow() < lowest) {
                lowest = c.getLow();
                lowestIndex = i;
            }
        }
        return new double[]{lowest == Double.MAX_VALUE ? 0 : lowest, lowestIndex};
    }

    /**
     * Get average close in last N bars.
     */
    public synchronized double getAverageClose(int lookback) {
        double sum = 0;
        int count = 0;
        int limit = Math.min(lookback, size);
        for (int i = 0; i < limit; i++) {
            Candle c = getCandle(i);
            if (c != null) {
                sum += c.getClose();
                count++;
            }
        }
        return count > 0 ? sum / count : 0;
    }

    /**
     * Get average range (high - low) in last N bars.
     */
    public synchronized double getAverageRange(int lookback) {
        double sum = 0;
        int count = 0;
        int limit = Math.min(lookback, size);
        for (int i = 0; i < limit; i++) {
            Candle c = getCandle(i);
            if (c != null) {
                sum += (c.getHigh() - c.getLow());
                count++;
            }
        }
        return count > 0 ? sum / count : 0;
    }

    // ═══════════════════════════════════════════════════════════════════
    // SWING DETECTION (for equal highs/lows)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Detect swing highs (fractals).
     * A swing high is a bar where high > high of N bars on each side.
     *
     * @param lookback Number of bars to scan
     * @param swingSize Number of bars on each side for fractal detection
     * @return List of swing high prices with their bar indices
     */
    public synchronized List<double[]> detectSwingHighs(int lookback, int swingSize) {
        List<double[]> swings = new ArrayList<>();
        int count = Math.min(lookback, size);

        for (int i = swingSize; i < count - swingSize; i++) {
            Candle center = getCandle(i);
            if (center == null) continue;

            boolean isSwingHigh = true;
            double centerHigh = center.getHigh();

            // Check bars on left side
            for (int j = 1; j <= swingSize && isSwingHigh; j++) {
                Candle left = getCandle(i + j);
                if (left == null || left.getHigh() >= centerHigh) {
                    isSwingHigh = false;
                }
            }

            // Check bars on right side
            for (int j = 1; j <= swingSize && isSwingHigh; j++) {
                Candle right = getCandle(i - j);
                if (right == null || right.getHigh() >= centerHigh) {
                    isSwingHigh = false;
                }
            }

            if (isSwingHigh) {
                swings.add(new double[]{centerHigh, i});
            }
        }

        return swings;
    }

    /**
     * Detect swing lows (fractals).
     * A swing low is a bar where low < low of N bars on each side.
     *
     * @param lookback Number of bars to scan
     * @param swingSize Number of bars on each side for fractal detection
     * @return List of swing low prices with their bar indices
     */
    public synchronized List<double[]> detectSwingLows(int lookback, int swingSize) {
        List<double[]> swings = new ArrayList<>();
        int count = Math.min(lookback, size);

        for (int i = swingSize; i < count - swingSize; i++) {
            Candle center = getCandle(i);
            if (center == null) continue;

            boolean isSwingLow = true;
            double centerLow = center.getLow();

            // Check bars on left side
            for (int j = 1; j <= swingSize && isSwingLow; j++) {
                Candle left = getCandle(i + j);
                if (left == null || left.getLow() <= centerLow) {
                    isSwingLow = false;
                }
            }

            // Check bars on right side
            for (int j = 1; j <= swingSize && isSwingLow; j++) {
                Candle right = getCandle(i - j);
                if (right == null || right.getLow() <= centerLow) {
                    isSwingLow = false;
                }
            }

            if (isSwingLow) {
                swings.add(new double[]{centerLow, i});
            }
        }

        return swings;
    }

    // ═══════════════════════════════════════════════════════════════════
    // UTILITY
    // ═══════════════════════════════════════════════════════════════════

    public String getSymbol() {
        return symbol;
    }

    public int getCapacity() {
        return capacity;
    }

    public synchronized int getSize() {
        return size;
    }

    /**
     * Get the current number of candles.
     * This is an alias for getSize() for API compatibility.
     */
    public synchronized int size() {
        return size;
    }

    public synchronized int getTotalAdded() {
        return totalAdded;
    }

    public synchronized boolean isEmpty() {
        return size == 0;
    }

    public synchronized boolean hasMinimumData(int required) {
        return size >= required;
    }

    /**
     * Get timestamp of most recent candle, or null if empty.
     */
    public synchronized Instant getLatestTimestamp() {
        Candle latest = getLatestCandle();
        return latest != null ? latest.getTimestamp() : null;
    }

    /**
     * Clear all candles.
     */
    public synchronized void clear() {
        for (int i = 0; i < capacity; i++) {
            candles[i] = null;
        }
        head = 0;
        size = 0;
        // Note: totalAdded is NOT reset (for debugging/stats)
    }

    @Override
    public String toString() {
        return String.format("CandleSeries{%s: size=%d/%d, total=%d}",
                symbol, size, capacity, totalAdded);
    }
}
