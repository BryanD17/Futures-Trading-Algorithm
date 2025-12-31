package com.topstep.trading.strategy;

import com.topstep.trading.domain.Candle;
import com.topstep.trading.domain.TradingSession;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * Bar Aggregation Manager for multi-timeframe analysis.
 *
 * Aggregates 1-minute candles into higher timeframes (3m, 5m, 15m, 1h).
 * This enables true multi-timeframe confluence detection:
 * - 15m/1h: HTF context (bias, major zones, liquidity levels)
 * - 5m: Zone quality validation, SMT confirmation
 * - 1m/3m: Execution triggers (MSS, displacement, FVG entry)
 *
 * ICT Silver Bullet workflow:
 * 1. Use 15m for directional bias and major liquidity levels
 * 2. Use 5m to identify quality zones (OB, Breaker, FVG)
 * 3. Use 1m/3m for precise MSS trigger and entry FVG retrace
 */
public class BarAggregationManager {

    public enum Timeframe {
        M1(1, "1m"),
        M3(3, "3m"),
        M5(5, "5m"),
        M15(15, "15m"),
        M30(30, "30m"),
        H1(60, "1h");

        private final int minutes;
        private final String label;

        Timeframe(int minutes, String label) {
            this.minutes = minutes;
            this.label = label;
        }

        public int getMinutes() { return minutes; }
        public String getLabel() { return label; }
    }

    private final String symbol;
    private final ZoneId timezone = ZoneId.of("America/New_York");

    // Store candles by timeframe
    private final Map<Timeframe, List<Candle>> candlesByTimeframe;

    // Buffer for aggregating 1m candles into higher timeframes
    private final Map<Timeframe, AggregationBuffer> buffers;

    // Max candles to keep per timeframe
    private final int maxCandles;

    public BarAggregationManager(String symbol, int maxCandles) {
        this.symbol = symbol;
        this.maxCandles = maxCandles;
        this.candlesByTimeframe = new EnumMap<>(Timeframe.class);
        this.buffers = new EnumMap<>(Timeframe.class);

        // Initialize storage for each timeframe
        for (Timeframe tf : Timeframe.values()) {
            candlesByTimeframe.put(tf, new ArrayList<>());
            if (tf != Timeframe.M1) {
                buffers.put(tf, new AggregationBuffer(tf.getMinutes()));
            }
        }
    }

    /**
     * Process a new 1-minute candle.
     * This updates all higher timeframes appropriately.
     *
     * @return Map of completed candles by timeframe (only contains newly completed bars)
     */
    public Map<Timeframe, Candle> processCandle(Candle candle) {
        Map<Timeframe, Candle> completedCandles = new EnumMap<>(Timeframe.class);

        // Store the 1m candle directly
        addCandle(Timeframe.M1, candle);
        completedCandles.put(Timeframe.M1, candle);

        // Aggregate into higher timeframes
        for (Timeframe tf : Arrays.asList(Timeframe.M3, Timeframe.M5, Timeframe.M15, Timeframe.M30, Timeframe.H1)) {
            AggregationBuffer buffer = buffers.get(tf);
            Candle completed = buffer.addCandle(candle);

            if (completed != null) {
                addCandle(tf, completed);
                completedCandles.put(tf, completed);
            }
        }

        return completedCandles;
    }

    /**
     * Add a candle to the specified timeframe's list.
     */
    private void addCandle(Timeframe tf, Candle candle) {
        List<Candle> candles = candlesByTimeframe.get(tf);
        candles.add(candle);

        // Trim to max size
        while (candles.size() > maxCandles) {
            candles.remove(0);
        }
    }

    /**
     * Get candles for a specific timeframe.
     */
    public List<Candle> getCandles(Timeframe tf) {
        return Collections.unmodifiableList(candlesByTimeframe.get(tf));
    }

    /**
     * Get the last N candles for a specific timeframe.
     */
    public List<Candle> getLastCandles(Timeframe tf, int count) {
        List<Candle> candles = candlesByTimeframe.get(tf);
        int size = candles.size();
        if (size <= count) {
            return Collections.unmodifiableList(new ArrayList<>(candles));
        }
        return Collections.unmodifiableList(new ArrayList<>(candles.subList(size - count, size)));
    }

    /**
     * Get the latest candle for a timeframe.
     */
    public Candle getLatestCandle(Timeframe tf) {
        List<Candle> candles = candlesByTimeframe.get(tf);
        if (candles.isEmpty()) {
            return null;
        }
        return candles.get(candles.size() - 1);
    }

    /**
     * Get swing high for a timeframe (highest high in lookback period).
     */
    public double getSwingHigh(Timeframe tf, int lookback) {
        List<Candle> candles = candlesByTimeframe.get(tf);
        int size = candles.size();
        int start = Math.max(0, size - lookback);

        return candles.subList(start, size).stream()
                .mapToDouble(Candle::getHigh)
                .max()
                .orElse(0.0);
    }

    /**
     * Get swing low for a timeframe (lowest low in lookback period).
     */
    public double getSwingLow(Timeframe tf, int lookback) {
        List<Candle> candles = candlesByTimeframe.get(tf);
        int size = candles.size();
        int start = Math.max(0, size - lookback);

        return candles.subList(start, size).stream()
                .mapToDouble(Candle::getLow)
                .min()
                .orElse(Double.MAX_VALUE);
    }

    /**
     * Check if we have enough candles for analysis on a timeframe.
     */
    public boolean hasEnoughCandles(Timeframe tf, int required) {
        return candlesByTimeframe.get(tf).size() >= required;
    }

    /**
     * Get the count of candles for a timeframe.
     */
    public int getCandleCount(Timeframe tf) {
        return candlesByTimeframe.get(tf).size();
    }

    /**
     * Reset all data.
     */
    public void reset() {
        for (Timeframe tf : Timeframe.values()) {
            candlesByTimeframe.get(tf).clear();
            if (buffers.containsKey(tf)) {
                buffers.get(tf).reset();
            }
        }
    }

    public String getSymbol() {
        return symbol;
    }

    /**
     * Buffer for aggregating candles into a higher timeframe.
     */
    private class AggregationBuffer {
        private final int periodMinutes;
        private final List<Candle> buffer;
        private Instant periodStart;

        AggregationBuffer(int periodMinutes) {
            this.periodMinutes = periodMinutes;
            this.buffer = new ArrayList<>();
            this.periodStart = null;
        }

        /**
         * Add a 1m candle to the buffer.
         * Returns a completed aggregated candle if the period is complete, null otherwise.
         */
        Candle addCandle(Candle candle) {
            // Determine the period start time for this candle
            Instant candlePeriodStart = getPeriodStart(candle.getTimestamp());

            // If this is a new period, complete the previous one
            if (periodStart != null && !candlePeriodStart.equals(periodStart)) {
                Candle completed = completeCandle();
                buffer.clear();
                periodStart = candlePeriodStart;
                buffer.add(candle);
                return completed;
            }

            // First candle or same period
            if (periodStart == null) {
                periodStart = candlePeriodStart;
            }
            buffer.add(candle);

            return null;
        }

        /**
         * Complete the current buffer into an aggregated candle.
         */
        private Candle completeCandle() {
            if (buffer.isEmpty()) {
                return null;
            }

            double open = buffer.get(0).getOpen();
            double close = buffer.get(buffer.size() - 1).getClose();
            double high = buffer.stream().mapToDouble(Candle::getHigh).max().orElse(0);
            double low = buffer.stream().mapToDouble(Candle::getLow).min().orElse(0);
            long volume = buffer.stream().mapToLong(Candle::getVolume).sum();
            TradingSession session = buffer.get(0).getSession();

            return new Candle(symbol, periodStart, open, high, low, close, volume, session);
        }

        /**
         * Get the period start time for a given timestamp.
         */
        private Instant getPeriodStart(Instant timestamp) {
            ZonedDateTime zdt = timestamp.atZone(timezone);
            int minute = zdt.getMinute();
            int periodMinute = (minute / periodMinutes) * periodMinutes;
            return zdt.withMinute(periodMinute).withSecond(0).withNano(0).toInstant();
        }

        void reset() {
            buffer.clear();
            periodStart = null;
        }
    }
}
