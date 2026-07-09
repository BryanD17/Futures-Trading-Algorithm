package com.topstep.trading.chart;

import com.topstep.trading.domain.Candle;
import com.topstep.trading.strategy.BarAggregationManager;
import com.topstep.trading.strategy.BarAggregationManager.Timeframe;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ChartEngine — the bot's "chart in memory".
 *
 * <p>This is the component that makes the algorithm see what a human sees on
 * the TopstepX 30m chart: a full candle series per instrument, the current
 * significant swing leg, and the OTE (Optimal Trade Entry) fib zone drawn on
 * that leg (0.62 / 0.705 / 0.786), exactly like the retracement tool in the
 * screenshot.
 *
 * <h2>How it works</h2>
 * <ol>
 *   <li>Every 1m candle (backfilled history first, then live) flows into
 *       {@link #onCandle(Candle)}.</li>
 *   <li>An internal {@link BarAggregationManager} rebuilds clock-aligned
 *       15m and 30m candles from the 1m stream.</li>
 *   <li>On every completed 30m bar, fractal swing points are recomputed and
 *       the most recent significant leg is identified.</li>
 *   <li>The OTE zone for that leg is computed and kept live: state moves
 *       FORMING → ARMED (price tagged the 0.62–0.79 band) → REACTED (price
 *       rejected back out of the band toward the origin) → INVALIDATED
 *       (price closed beyond the leg origin / 1.0 level).</li>
 * </ol>
 *
 * <p>Thread-safety: all public methods are safe to call from the market-data
 * thread and the API thread concurrently (per-symbol state is confined and
 * snapshots are immutable copies).
 */
public final class ChartEngine {

    /** Fib ratios of the canonical ICT OTE band. */
    public static final double OTE_START = 0.62;
    public static final double OTE_SWEET = 0.705;
    public static final double OTE_END   = 0.79;

    /** Bars on each side required to confirm a 30m fractal swing. */
    private final int swingStrength;

    /** Minimum leg size in ticks for a swing leg to be considered significant. */
    private final int minLegTicks;

    /** 30m bars an OTE zone stays valid before it expires without a tag. */
    private final int zoneExpiryBars;

    private final Map<String, SymbolChart> charts = new ConcurrentHashMap<>();
    private final Map<String, Double> tickSizes = new ConcurrentHashMap<>();

    public ChartEngine() {
        this(2, 40, 32); // 2-bar fractals, >=10pt legs on MNQ (40 * 0.25), ~16h expiry
    }

    public ChartEngine(int swingStrength, int minLegTicks, int zoneExpiryBars) {
        if (swingStrength < 1) throw new IllegalArgumentException("swingStrength >= 1");
        if (minLegTicks < 1) throw new IllegalArgumentException("minLegTicks >= 1");
        this.swingStrength = swingStrength;
        this.minLegTicks = minLegTicks;
        this.zoneExpiryBars = Math.max(1, zoneExpiryBars);
    }

    /** Register an instrument's tick size (required before its OTE math is meaningful). */
    public void registerInstrument(String symbol, double tickSize) {
        if (symbol == null || tickSize <= 0) return;
        tickSizes.put(symbol, tickSize);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // INGEST
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Feed a 1m candle (historical backfill or live — same path, which is what
     * guarantees the in-memory chart matches the broker chart).
     */
    public void onCandle(Candle candle) {
        if (candle == null || candle.getSymbol() == null) return;
        SymbolChart chart = charts.computeIfAbsent(
                candle.getSymbol(), s -> new SymbolChart(s));
        synchronized (chart) {
            chart.ingest(candle);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // QUERY API (strategy + dashboard)
    // ═══════════════════════════════════════════════════════════════════════

    /** Completed 30m candles, oldest → newest (immutable copy). */
    public List<Candle> get30mCandles(String symbol, int lookback) {
        SymbolChart chart = charts.get(symbol);
        if (chart == null) return List.of();
        synchronized (chart) {
            List<Candle> all = chart.bars.getCandles(Timeframe.M30);
            int from = Math.max(0, all.size() - Math.max(1, lookback));
            return List.copyOf(all.subList(from, all.size()));
        }
    }

    /** The live OTE zone for the symbol, if one exists and is not invalidated. */
    public Optional<OteZoneSnapshot> getActiveOteZone(String symbol) {
        SymbolChart chart = charts.get(symbol);
        if (chart == null) return Optional.empty();
        synchronized (chart) {
            OteZoneSnapshot z = chart.activeZone;
            if (z == null || z.state() == OteState.INVALIDATED || z.state() == OteState.EXPIRED) {
                return Optional.empty();
            }
            return Optional.of(z);
        }
    }

    /**
     * PRIMARY STRATEGY GATE — true when the 30m chart shows what the
     * screenshot shows: a significant leg whose 0.62–0.79 retracement has
     * been tagged and rejected in the leg's direction.
     *
     * @param bullish true = leg is up, we want a long entry off the OTE
     */
    public boolean hasReactedOte(String symbol, boolean bullish) {
        return getActiveOteZone(symbol)
                .filter(z -> z.bullish() == bullish)
                .filter(z -> z.state() == OteState.REACTED)
                .isPresent();
    }

    /** Full snapshot for the dashboard: candles + zone + swing anchors. */
    public ChartSnapshot snapshot(String symbol, int lookback30m) {
        SymbolChart chart = charts.get(symbol);
        if (chart == null) {
            return new ChartSnapshot(symbol, List.of(), null, 0, null);
        }
        synchronized (chart) {
            List<Candle> bars30 = get30mCandles(symbol, lookback30m);
            return new ChartSnapshot(
                    symbol,
                    bars30,
                    chart.activeZone,
                    chart.oneMinuteCount,
                    chart.lastCandleTime);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // INTERNAL — per-symbol chart state
    // ═══════════════════════════════════════════════════════════════════════

    private final class SymbolChart {
        final String symbol;
        final BarAggregationManager bars;
        OteZoneSnapshot activeZone;
        long oneMinuteCount;
        Instant lastCandleTime;
        int barsSinceZoneCreated;

        SymbolChart(String symbol) {
            this.symbol = symbol;
            // 2000 bars/timeframe: >5 weeks of 30m, ~1.4 days of 1m — enough
            // for swing detection with bounded memory (~2MB/instrument).
            this.bars = new BarAggregationManager(symbol, 2000);
        }

        void ingest(Candle c1m) {
            oneMinuteCount++;
            lastCandleTime = c1m.getTimestamp();
            Map<Timeframe, Candle> completed = bars.processCandle(c1m);

            // Update zone state on every 1m bar (tags/reactions happen intra-30m).
            if (activeZone != null) {
                activeZone = advanceZone(activeZone, c1m);
            }

            // Recompute swing structure only when a 30m bar completes.
            Candle done30 = completed.get(Timeframe.M30);
            if (done30 != null) {
                if (activeZone != null
                        && activeZone.state() == OteState.FORMING
                        && ++barsSinceZoneCreated > zoneExpiryBars) {
                    activeZone = activeZone.withState(OteState.EXPIRED);
                }
                rebuildZoneIfNeeded();
            }
        }

        /** Find the latest significant 30m leg and (re)draw the OTE on it. */
        void rebuildZoneIfNeeded() {
            List<Candle> series = bars.getCandles(Timeframe.M30);
            int n = series.size();
            if (n < swingStrength * 2 + 3) return;

            List<Swing> highs = fractals(series, true);
            List<Swing> lows = fractals(series, false);
            if (highs.isEmpty() || lows.isEmpty()) return;

            Swing lastHigh = highs.get(highs.size() - 1);
            Swing lastLow = lows.get(lows.size() - 1);
            double tick = tickSizes.getOrDefault(symbol, 0.25);
            double minLeg = minLegTicks * tick;

            // Most recent leg = the later swing defines direction.
            boolean bullishLeg = lastHigh.index > lastLow.index; // low → high = up leg
            Swing origin = bullishLeg ? lastLow : lastHigh;
            Swing extreme = bullishLeg ? lastHigh : lastLow;
            double legSize = Math.abs(extreme.price - origin.price);
            if (legSize < minLeg) return;

            // Don't replace a still-live zone with the SAME leg.
            if (activeZone != null
                    && activeZone.state() != OteState.INVALIDATED
                    && activeZone.state() != OteState.EXPIRED
                    && activeZone.legOrigin() == origin.price
                    && activeZone.legExtreme() == extreme.price) {
                return;
            }

            activeZone = OteZoneSnapshot.forLeg(
                    symbol, bullishLeg, origin.price, extreme.price,
                    origin.time, extreme.time);
            barsSinceZoneCreated = 0;
        }

        /** Progress FORMING → ARMED → REACTED → INVALIDATED on each 1m bar. */
        OteZoneSnapshot advanceZone(OteZoneSnapshot z, Candle c) {
            if (z.state() == OteState.INVALIDATED || z.state() == OteState.EXPIRED) return z;

            // Invalidation: close beyond the leg origin (the 1.0 level).
            boolean brokeOrigin = z.bullish()
                    ? c.getClose() < z.legOrigin()
                    : c.getClose() > z.legOrigin();
            if (brokeOrigin) return z.withState(OteState.INVALIDATED);

            // New extreme beyond the leg: leg extends; rebuild happens on
            // the next completed 30m bar. Keep zone but do not arm on stale fibs.
            boolean newExtreme = z.bullish()
                    ? c.getHigh() > z.legExtreme()
                    : c.getLow() < z.legExtreme();
            if (newExtreme && z.state() == OteState.FORMING) return z;

            if (z.state() == OteState.FORMING) {
                // ARMED: price traded into the 0.62–0.79 band.
                boolean tagged = z.bullish()
                        ? c.getLow() <= z.fib(OTE_START)
                        : c.getHigh() >= z.fib(OTE_START);
                if (tagged) return z.withState(OteState.ARMED).withTagTime(c.getTimestamp());
            } else if (z.state() == OteState.ARMED) {
                // REACTED: a close back on the extreme-side of the 0.62 line
                // after the tag = the rejection you see on the screenshot.
                boolean rejected = z.bullish()
                        ? c.getClose() > z.fib(OTE_START)
                        : c.getClose() < z.fib(OTE_START);
                if (rejected) return z.withState(OteState.REACTED);
            }
            return z;
        }

        /** Classic fractal swings on the 30m series. */
        List<Swing> fractals(List<Candle> s, boolean highs) {
            List<Swing> out = new ArrayList<>();
            for (int i = swingStrength; i < s.size() - swingStrength; i++) {
                double v = highs ? s.get(i).getHigh() : s.get(i).getLow();
                boolean is = true;
                for (int k = 1; k <= swingStrength && is; k++) {
                    double left = highs ? s.get(i - k).getHigh() : s.get(i - k).getLow();
                    double right = highs ? s.get(i + k).getHigh() : s.get(i + k).getLow();
                    is = highs ? (v >= left && v > right) : (v <= left && v < right);
                }
                if (is) out.add(new Swing(i, v, s.get(i).getTimestamp()));
            }
            return Collections.unmodifiableList(out);
        }
    }

    private record Swing(int index, double price, Instant time) {}
}
