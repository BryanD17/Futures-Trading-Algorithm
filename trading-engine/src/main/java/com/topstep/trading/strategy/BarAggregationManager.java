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
 * Aggregates 1-minute candles into higher timeframes (3m, 5m, 15m, 30m, 1h)
 * following strict clock-aligned boundary rules:
 *
 * AGGREGATION RULES:
 * 1. Clock-aligned boundaries: 15m starts at :00/:15/:30/:45, 30m at :00/:30, etc.
 *    No rolling windows — snaps to fixed clock boundaries.
 * 2. Open = first 1m open, High = max highs, Low = min lows, Close = last 1m close,
 *    Volume = sum of all 1m volumes.
 * 3. In-progress updates: Each 1m candle updates the running HTF candle in real time.
 * 4. Finalization on boundary: HTF candle emitted when the last 1m of its window arrives,
 *    or when the first 1m of the NEXT window arrives (whichever happens first).
 * 5. Partial candle handling: Session-end mid-window finalizes with available data,
 *    flagged as partial.
 * 6. Both completed and in-progress candles available for downstream consumers.
 *
 * This enables true multi-timeframe confluence detection:
 * - 15m/1h: HTF context (bias, major zones, liquidity levels)
 * - 5m: Zone quality validation, SMT confirmation
 * - 1m/3m: Execution triggers (MSS, displacement, FVG entry)
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

    // Store completed candles by timeframe
    private final Map<Timeframe, List<Candle>> candlesByTimeframe;

    // Buffer for aggregating 1m candles into higher timeframes
    private final Map<Timeframe, AggregationBuffer> buffers;

    // Max candles to keep per timeframe
    private final int maxCandles;

    // Higher timeframes that get aggregated (everything except M1)
    private static final Timeframe[] HTF_TIMEFRAMES = {
            Timeframe.M3, Timeframe.M5, Timeframe.M15, Timeframe.M30, Timeframe.H1
    };

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
     * This updates all higher timeframes appropriately:
     * - Stores the 1m candle directly
     * - Updates in-progress HTF candles with running high/low/close/volume
     * - Emits completed HTF candles when a period boundary is crossed
     *
     * @return Map of completed candles by timeframe (only contains newly completed bars)
     */
    public Map<Timeframe, Candle> processCandle(Candle candle) {
        Map<Timeframe, Candle> completedCandles = new EnumMap<>(Timeframe.class);

        // Store the 1m candle directly
        addCandle(Timeframe.M1, candle);
        completedCandles.put(Timeframe.M1, candle);

        // Aggregate into higher timeframes
        for (Timeframe tf : HTF_TIMEFRAMES) {
            AggregationBuffer buffer = buffers.get(tf);
            Candle completed = buffer.addCandle(candle);

            if (completed != null) {
                addCandle(tf, completed);
                completedCandles.put(tf, completed);
            }
        }

        return completedCandles;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // IN-PROGRESS HTF CANDLE ACCESS (Rule 3 & 6)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Get the in-progress (not yet finalized) HTF candle for a timeframe.
     *
     * This returns the running aggregation of 1m candles received so far in the
     * current HTF window. The candle is updated on every 1m candle with:
     * - High = max of all 1m highs so far
     * - Low = min of all 1m lows so far
     * - Close = latest 1m close
     * - Volume = sum of all 1m volumes so far
     *
     * Use for real-time context (e.g., current 15m candle showing rejection wick),
     * but NOT for confirmed structural analysis (swing points, FVGs, order blocks).
     *
     * @param tf The timeframe to get the in-progress candle for
     * @return The in-progress candle, or null if no data in the current window
     */
    public Candle getInProgressCandle(Timeframe tf) {
        if (tf == Timeframe.M1) {
            // 1m candles are always complete — return the latest
            List<Candle> m1Candles = candlesByTimeframe.get(Timeframe.M1);
            return m1Candles.isEmpty() ? null : m1Candles.get(m1Candles.size() - 1);
        }

        AggregationBuffer buffer = buffers.get(tf);
        if (buffer == null) {
            return null;
        }
        return buffer.buildInProgressCandle();
    }

    /**
     * Check if a timeframe has an in-progress candle being built.
     */
    public boolean hasInProgressCandle(Timeframe tf) {
        if (tf == Timeframe.M1) {
            return !candlesByTimeframe.get(Timeframe.M1).isEmpty();
        }
        AggregationBuffer buffer = buffers.get(tf);
        return buffer != null && !buffer.isEmpty();
    }

    /**
     * Get the number of 1m candles accumulated so far in the current HTF window.
     * Useful for knowing how "complete" the in-progress candle is.
     */
    public int getInProgressCandleCount(Timeframe tf) {
        if (tf == Timeframe.M1) {
            return candlesByTimeframe.get(Timeframe.M1).isEmpty() ? 0 : 1;
        }
        AggregationBuffer buffer = buffers.get(tf);
        return buffer != null ? buffer.getBufferSize() : 0;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SESSION BOUNDARY HANDLING (Rule 5)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Force-finalize all in-progress HTF candles as partial candles.
     *
     * Call this when the trading session ends before an HTF window completes.
     * For example, if ES closes at 5:00 PM ET and the current 15m window started
     * at 4:45 PM, this finalizes that partial candle.
     *
     * Partial candles are flagged so downstream analysis can optionally ignore them
     * for confirmed structural analysis while still using them for context.
     *
     * @return Map of partial candles that were finalized, by timeframe
     */
    public Map<Timeframe, Candle> forceCompleteAll() {
        Map<Timeframe, Candle> partialCandles = new EnumMap<>(Timeframe.class);

        for (Timeframe tf : HTF_TIMEFRAMES) {
            AggregationBuffer buffer = buffers.get(tf);
            if (buffer != null && !buffer.isEmpty()) {
                Candle partial = buffer.forceComplete();
                if (partial != null) {
                    addCandle(tf, partial);
                    partialCandles.put(tf, partial);
                }
            }
        }

        return partialCandles;
    }

    /**
     * Force-finalize the in-progress candle for a specific timeframe.
     *
     * @param tf The timeframe to force-complete
     * @return The partial candle, or null if no in-progress data
     */
    public Candle forceComplete(Timeframe tf) {
        if (tf == Timeframe.M1) {
            return null; // 1m candles are always complete
        }

        AggregationBuffer buffer = buffers.get(tf);
        if (buffer == null || buffer.isEmpty()) {
            return null;
        }

        Candle partial = buffer.forceComplete();
        if (partial != null) {
            addCandle(tf, partial);
        }
        return partial;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // COMPLETED CANDLE ACCESS (Rule 6 — completed side)
    // ═══════════════════════════════════════════════════════════════════════════

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
     * Get all completed candles for a specific timeframe.
     */
    public List<Candle> getCandles(Timeframe tf) {
        return Collections.unmodifiableList(candlesByTimeframe.get(tf));
    }

    /**
     * Get the last N completed candles for a specific timeframe.
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
     * Get the latest completed candle for a timeframe.
     */
    public Candle getLatestCandle(Timeframe tf) {
        List<Candle> candles = candlesByTimeframe.get(tf);
        if (candles.isEmpty()) {
            return null;
        }
        return candles.get(candles.size() - 1);
    }

    /**
     * Get the latest non-partial completed candle for a timeframe.
     * Skips any trailing partial candles — useful for confirmed structural analysis.
     */
    public Candle getLatestConfirmedCandle(Timeframe tf) {
        List<Candle> candles = candlesByTimeframe.get(tf);
        for (int i = candles.size() - 1; i >= 0; i--) {
            if (!candles.get(i).isPartial()) {
                return candles.get(i);
            }
        }
        return null;
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
     * Get the count of completed candles for a timeframe.
     */
    public int getCandleCount(Timeframe tf) {
        return candlesByTimeframe.get(tf).size();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // UTILITY
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Reset all data (completed candles and in-progress buffers).
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
     * Get a diagnostic summary of aggregation state across all timeframes.
     */
    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("BarAggregation[").append(symbol).append("]:");
        for (Timeframe tf : Timeframe.values()) {
            int completed = candlesByTimeframe.get(tf).size();
            if (tf == Timeframe.M1) {
                sb.append(String.format(" %s=%d", tf.getLabel(), completed));
            } else {
                AggregationBuffer buf = buffers.get(tf);
                sb.append(String.format(" %s=%d(+%d)", tf.getLabel(), completed,
                        buf != null ? buf.getBufferSize() : 0));
            }
        }
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // AGGREGATION BUFFER (Internal — implements Rules 1-5)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Buffer for aggregating 1-minute candles into a higher timeframe candle.
     *
     * Maintains running OHLCV state that is updated on every 1m candle arrival.
     * Clock-aligned: period start snaps to fixed boundaries (:00, :15, :30, :45 for 15m).
     */
    private class AggregationBuffer {
        private final int periodMinutes;
        private final List<Candle> buffer;
        private Instant periodStart;

        // Running aggregation state for real-time in-progress candle access
        private double runningOpen;
        private double runningHigh;
        private double runningLow;
        private double runningClose;
        private long runningVolume;
        private TradingSession runningSession;

        AggregationBuffer(int periodMinutes) {
            this.periodMinutes = periodMinutes;
            this.buffer = new ArrayList<>();
            this.periodStart = null;
            resetRunningState();
        }

        /**
         * Add a 1m candle to the buffer.
         * Updates the running OHLCV aggregation state on every call.
         * Returns a completed aggregated candle if the period boundary is crossed, null otherwise.
         */
        Candle addCandle(Candle candle) {
            // Determine the period start time for this candle (clock-aligned)
            Instant candlePeriodStart = getPeriodStart(candle.getTimestamp());

            // If this is a new period, complete the previous one
            if (periodStart != null && !candlePeriodStart.equals(periodStart)) {
                Candle completed = buildCompletedCandle(false);
                buffer.clear();
                resetRunningState();
                periodStart = candlePeriodStart;
                addToBuffer(candle);
                return completed;
            }

            // First candle or same period
            if (periodStart == null) {
                periodStart = candlePeriodStart;
            }
            addToBuffer(candle);

            return null;
        }

        /**
         * Add a 1m candle to the buffer and update running aggregation state.
         */
        private void addToBuffer(Candle candle) {
            if (buffer.isEmpty()) {
                // First candle in window: set open and initialize high/low
                runningOpen = candle.getOpen();
                runningHigh = candle.getHigh();
                runningLow = candle.getLow();
                runningSession = candle.getSession();
            } else {
                // Subsequent candles: update running high/low
                if (candle.getHigh() > runningHigh) {
                    runningHigh = candle.getHigh();
                }
                if (candle.getLow() < runningLow) {
                    runningLow = candle.getLow();
                }
            }
            // Always update close and accumulate volume
            runningClose = candle.getClose();
            runningVolume += candle.getVolume();

            buffer.add(candle);
        }

        /**
         * Build the completed candle from running state.
         * @param partial true if this is a partial candle (session boundary)
         */
        private Candle buildCompletedCandle(boolean partial) {
            if (buffer.isEmpty()) {
                return null;
            }
            return new Candle(symbol, periodStart, runningOpen, runningHigh, runningLow,
                    runningClose, runningVolume, runningSession, partial);
        }

        /**
         * Build an in-progress candle from the current running state.
         * This is a snapshot of the HTF candle as it's being built.
         * Returns null if no data in the current window.
         */
        Candle buildInProgressCandle() {
            if (buffer.isEmpty() || periodStart == null) {
                return null;
            }
            // In-progress candles are always marked as partial since they're incomplete
            return new Candle(symbol, periodStart, runningOpen, runningHigh, runningLow,
                    runningClose, runningVolume, runningSession, true);
        }

        /**
         * Force-complete the current buffer as a partial candle.
         * Used when the session ends mid-window.
         */
        Candle forceComplete() {
            Candle partial = buildCompletedCandle(true);
            buffer.clear();
            resetRunningState();
            periodStart = null;
            return partial;
        }

        /**
         * Get the clock-aligned period start time for a given timestamp.
         *
         * Clock alignment rules:
         * - 3m: starts at :00, :03, :06, ..., :57
         * - 5m: starts at :00, :05, :10, ..., :55
         * - 15m: starts at :00, :15, :30, :45
         * - 30m: starts at :00, :30
         * - 1h: starts at :00
         */
        private Instant getPeriodStart(Instant timestamp) {
            ZonedDateTime zdt = timestamp.atZone(timezone);
            int minute = zdt.getMinute();
            int periodMinute = (minute / periodMinutes) * periodMinutes;
            return zdt.withMinute(periodMinute).withSecond(0).withNano(0).toInstant();
        }

        boolean isEmpty() {
            return buffer.isEmpty();
        }

        int getBufferSize() {
            return buffer.size();
        }

        private void resetRunningState() {
            runningOpen = 0;
            runningHigh = Double.MIN_VALUE;
            runningLow = Double.MAX_VALUE;
            runningClose = 0;
            runningVolume = 0;
            runningSession = null;
        }

        void reset() {
            buffer.clear();
            periodStart = null;
            resetRunningState();
        }
    }
}
