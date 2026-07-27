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
        H1(60, "1h"),
        // V3 Agent 04: session-aware frames. H4 anchors to the CME session
        // open (18/22/02/06/10/14 ET wall-clock); D1 keys on the SESSION
        // date (18:00 ET -> 17:00 ET next day). Bucketing lives in
        // TradingSessionCalendar — never clock-modulo like the intraday
        // frames, never fixed UTC offsets.
        H4(240, "4h"),
        D1(1440, "1d");

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
            Timeframe.M3, Timeframe.M5, Timeframe.M15, Timeframe.M30,
            Timeframe.H1, Timeframe.H4, Timeframe.D1
    };

    /** Timestamp of the newest 1m candle processed on the LIVE path. */
    private Instant liveWatermark;

    public BarAggregationManager(String symbol, int maxCandles) {
        this.symbol = symbol;
        this.maxCandles = maxCandles;
        this.candlesByTimeframe = new EnumMap<>(Timeframe.class);
        this.buffers = new EnumMap<>(Timeframe.class);

        // Initialize storage for each timeframe
        for (Timeframe tf : Timeframe.values()) {
            candlesByTimeframe.put(tf, new ArrayList<>());
            if (tf != Timeframe.M1) {
                buffers.put(tf, new AggregationBuffer(tf));
            }
        }
    }

    /**
     * Ring capacity per timeframe (V3 Agent 04). Intraday frames keep the
     * constructor's shared cap exactly as before; H4/D1 get explicit floors
     * so weeks of seeded history are never silently trimmed: ~90 trading
     * days needs >= 540 H4 and >= 90 D1 bars (criterion G-R10).
     */
    int capacityFor(Timeframe tf) {
        if (tf == Timeframe.H4) return Math.max(maxCandles, 600);
        if (tf == Timeframe.D1) return Math.max(maxCandles, 120);
        return maxCandles;
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
    public synchronized Map<Timeframe, Candle> processCandle(Candle candle) {
        Map<Timeframe, Candle> completedCandles = new EnumMap<>(Timeframe.class);

        liveWatermark = candle.getTimestamp();

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
    // HTF SEEDING API (V3 Agent 04 — TIER 2 of the two-tier backfill)
    // ═══════════════════════════════════════════════════════════════════════════

    /** Outcome of one {@link #seedHigherTimeframe} call, for the log line. */
    public record SeedResult(int h1Seeded, int h4Derived, int d1Derived, int refused) {}

    /**
     * Seed weeks of HISTORICAL H1 bars into the H1 series and derive the
     * H4/D1 buckets from them. This is the ONLY path historical HTF bars
     * may take — never {@code listener.onCandle}, never the ChartEngine,
     * never lastCandleTs (Critical Rule 8: an H1 candle entering the 1m
     * pipeline corrupts every aggregated series).
     *
     * <p>Semantics (each tested):
     * <ul>
     *   <li>input is sorted ascending and de-duped by timestamp;</li>
     *   <li>bars at/after the LIVE watermark (newest 1m processed) are
     *       REFUSED — seed must never overwrite live-built data;</li>
     *   <li>derived buckets that would collide with (or come after) any
     *       bucket the LIVE path has already started for that timeframe
     *       are dropped — the seeded series strictly PRECEDES the live
     *       series, and the first live bucket after seeding extends it;</li>
     *   <li>maintenance-break bars are excluded from bucketing;</li>
     *   <li>live in-progress aggregation buffers are NEVER touched.</li>
     * </ul>
     */
    public synchronized SeedResult seedHigherTimeframe(List<Candle> h1Bars) {
        if (h1Bars == null || h1Bars.isEmpty()) {
            return new SeedResult(0, 0, 0, 0);
        }
        List<Candle> sorted = new ArrayList<>(h1Bars);
        sorted.sort(Comparator.comparing(Candle::getTimestamp));

        List<Candle> accepted = new ArrayList<>(sorted.size());
        int refused = 0;
        Instant prev = null;
        for (Candle c : sorted) {
            Instant ts = c.getTimestamp();
            if (ts == null) { refused++; continue; }
            if (prev != null && !ts.isAfter(prev)) { refused++; continue; } // de-dup
            if (liveWatermark != null && !ts.isBefore(liveWatermark)) {
                refused++; // never overwrite live-built bars
                continue;
            }
            accepted.add(c);
            prev = ts;
        }

        int h1Seeded = prependSeeded(Timeframe.H1, accepted.stream()
                .filter(c -> !TradingSessionCalendar.inMaintenanceBreak(c.getTimestamp()))
                .toList(), Candle::getTimestamp);
        int h4 = prependSeeded(Timeframe.H4,
                composeBuckets(accepted, TradingSessionCalendar::h4BucketStart),
                Candle::getTimestamp);
        int d1 = prependSeeded(Timeframe.D1,
                composeBuckets(accepted, TradingSessionCalendar::d1BucketStart),
                Candle::getTimestamp);
        return new SeedResult(h1Seeded, h4, d1, refused);
    }

    /** Compose OHLCV buckets from ascending bars using a bucket-start fn. */
    private List<Candle> composeBuckets(List<Candle> bars,
                                        java.util.function.Function<Instant, Instant> bucketFn) {
        List<Candle> out = new ArrayList<>();
        Instant bucketStart = null;
        double o = 0, h = 0, l = 0, c = 0;
        long v = 0;
        for (Candle bar : bars) {
            if (TradingSessionCalendar.inMaintenanceBreak(bar.getTimestamp())) {
                continue; // no-man's-land: belongs to no session bucket
            }
            Instant start = bucketFn.apply(bar.getTimestamp());
            if (bucketStart == null || !start.equals(bucketStart)) {
                if (bucketStart != null) {
                    out.add(new Candle(symbol, bucketStart, o, h, l, c, v));
                }
                bucketStart = start;
                o = bar.getOpen(); h = bar.getHigh(); l = bar.getLow();
                c = bar.getClose(); v = bar.getVolume();
            } else {
                h = Math.max(h, bar.getHigh());
                l = Math.min(l, bar.getLow());
                c = bar.getClose();
                v += bar.getVolume();
            }
        }
        if (bucketStart != null) {
            out.add(new Candle(symbol, bucketStart, o, h, l, c, v));
        }
        return out;
    }

    /**
     * Prepend seeded bars/buckets BEFORE everything the live path built.
     * Anything at/after the live path's first bucket for the timeframe
     * (completed or in-progress) is dropped — the live series is
     * authoritative from its first bucket onward.
     */
    private int prependSeeded(Timeframe tf, List<Candle> seeded,
                              java.util.function.Function<Candle, Instant> keyFn) {
        if (seeded.isEmpty()) return 0;
        List<Candle> existing = candlesByTimeframe.get(tf);
        Instant liveCutoff = earliestLiveInstant(tf);
        List<Candle> kept = new ArrayList<>(seeded.size());
        for (Candle c : seeded) {
            if (liveCutoff != null && !keyFn.apply(c).isBefore(liveCutoff)) continue;
            kept.add(c);
        }
        if (kept.isEmpty()) return 0;
        existing.addAll(0, kept);
        int cap = capacityFor(tf);
        while (existing.size() > cap) {
            existing.remove(0);
        }
        return kept.size();
    }

    /** Earliest instant the LIVE path owns for a timeframe (or null). */
    private Instant earliestLiveInstant(Timeframe tf) {
        Instant fromList = null;
        AggregationBuffer buf = buffers.get(tf);
        // The live list may already contain seeded bars from an earlier
        // call; the buffer's firstPeriodStart is purely live-built, and the
        // list's first LIVE element is bounded below by it. Use the
        // buffer's first-ever period start when present.
        if (buf != null && buf.firstPeriodStart != null) {
            fromList = buf.firstPeriodStart;
        } else if (tf == Timeframe.M1 && !candlesByTimeframe.get(tf).isEmpty()) {
            fromList = candlesByTimeframe.get(tf).get(0).getTimestamp();
        }
        return fromList;
    }

    /**
     * Thread-safe snapshot of completed candles for cross-thread readers
     * (the API layer). Same-thread strategy code may keep using
     * {@link #getCandles}.
     */
    public synchronized List<Candle> getCandlesSnapshot(Timeframe tf, int lookback) {
        List<Candle> src = candlesByTimeframe.get(tf);
        int from = Math.max(0, src.size() - Math.max(1, lookback));
        return new ArrayList<>(src.subList(from, src.size()));
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
     * Add a candle to the specified timeframe's list. If the tail already
     * holds a bucket with the SAME period start (a seeded bucket for the
     * session the live feed then continued), the live completion MERGES
     * into it instead of duplicating — the seed→live seam stays one
     * strictly-ascending series (V3 Agent 04).
     */
    private void addCandle(Timeframe tf, Candle candle) {
        List<Candle> candles = candlesByTimeframe.get(tf);
        if (!candles.isEmpty() && tf != Timeframe.M1) {
            Candle tail = candles.get(candles.size() - 1);
            if (tail.getTimestamp().equals(candle.getTimestamp())) {
                candles.set(candles.size() - 1, new Candle(symbol,
                        tail.getTimestamp(),
                        tail.getOpen(),
                        Math.max(tail.getHigh(), candle.getHigh()),
                        Math.min(tail.getLow(), candle.getLow()),
                        candle.getClose(),
                        tail.getVolume() + candle.getVolume(),
                        candle.getSession(), candle.isPartial()));
                return;
            }
        }
        candles.add(candle);

        // Trim to the timeframe's capacity
        int cap = capacityFor(tf);
        while (candles.size() > cap) {
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
        private final Timeframe timeframe;
        private final int periodMinutes;
        private final List<Candle> buffer;
        private Instant periodStart;
        /** First period start the LIVE path ever established (never reset
         *  by seeding — the seed/live boundary marker). */
        private Instant firstPeriodStart;

        // Running aggregation state for real-time in-progress candle access
        private double runningOpen;
        private double runningHigh;
        private double runningLow;
        private double runningClose;
        private long runningVolume;
        private TradingSession runningSession;

        AggregationBuffer(Timeframe timeframe) {
            this.timeframe = timeframe;
            this.periodMinutes = timeframe.getMinutes();
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
            // Session-aware frames exclude the 17:00-18:00 ET maintenance
            // break entirely — a stray bar there belongs to NO bucket and
            // must not extend the just-closed session (V3 Agent 04).
            if ((timeframe == Timeframe.H4 || timeframe == Timeframe.D1)
                    && TradingSessionCalendar.inMaintenanceBreak(candle.getTimestamp())) {
                return null;
            }
            // Determine the period start time for this candle (clock-aligned)
            Instant candlePeriodStart = getPeriodStart(candle.getTimestamp());
            if (firstPeriodStart == null) {
                firstPeriodStart = candlePeriodStart;
            }

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
            // Session-aware frames (V3 Agent 04): the CME session calendar
            // does the bucketing, not clock-modulo arithmetic.
            if (timeframe == Timeframe.H4) {
                return TradingSessionCalendar.h4BucketStart(timestamp);
            }
            if (timeframe == Timeframe.D1) {
                return TradingSessionCalendar.d1BucketStart(timestamp);
            }
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
            firstPeriodStart = null;
            resetRunningState();
        }
    }
}
