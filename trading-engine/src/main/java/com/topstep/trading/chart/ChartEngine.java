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
    // PER-INSTRUMENT TUNING (V2 Agent 05 — additive; defaults preserved)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Per-instrument threshold overrides. Exists because one global
     * minLegTicks means very different leg sizes across instruments
     * (40 ticks = 10.0 pts on MNQ/MES but 4.0 pts on MGC). Symbols without
     * an override use the constructor values EXACTLY — with no overrides
     * set, behavior is identical to pre-V2.
     */
    public record InstrumentTuning(int minLegTicks, int swingStrength, int zoneExpiryBars) {}

    private final Map<String, InstrumentTuning> tunings = new ConcurrentHashMap<>();

    /** Override the leg thresholds for ONE symbol (others keep defaults). */
    public void configureInstrument(String symbol, int minLegTicks,
                                    int swingStrength, int zoneExpiryBars) {
        if (symbol == null) return;
        tunings.put(symbol, new InstrumentTuning(
                Math.max(1, minLegTicks),
                Math.max(1, swingStrength),
                Math.max(1, zoneExpiryBars)));
    }

    /**
     * Resolve the per-symbol system-property overrides
     * ({@code chart.minLegTicks.<SYM>}, {@code chart.swingStrength.<SYM>},
     * {@code chart.zoneExpiryBars.<SYM>}; defaults = this engine's
     * constructor values) and log the resolved config. Runners call this
     * at wiring time, once per registered instrument.
     */
    public void applySystemPropertyTuning(String symbol) {
        if (symbol == null) return;
        int mlt = Integer.getInteger("chart.minLegTicks." + symbol, minLegTicks);
        int ss  = Integer.getInteger("chart.swingStrength." + symbol, swingStrength);
        int zeb = Integer.getInteger("chart.zoneExpiryBars." + symbol, zoneExpiryBars);
        configureInstrument(symbol, mlt, ss, zeb);
        // V4 Agent 05: resolve the anchoring switch ONCE at wiring time and
        // print it, so Agent 09's default-behaviour audit reads the resolved
        // value rather than trusting that no property was set.
        AnchorMode mode = anchorModeFor(symbol);
        OteBand band = bandFor(symbol);
        anchorModes.put(symbol, mode);
        bands.put(symbol, band);
        System.out.println("[CHART CFG " + symbol + "] minLegTicks=" + mlt
                + " swingStrength=" + ss + " expiryBars=" + zeb
                + " anchorMode=" + mode
                + " oteBand=" + band.start() + "," + band.end()
                + (band.isEngineDefault() ? " (engine default)" : " (OVERRIDDEN)")
                + " anchorCompare=" + anchorCompare);
    }

    private InstrumentTuning tuningFor(String symbol) {
        InstrumentTuning t = tunings.get(symbol);
        return (t != null) ? t
                : new InstrumentTuning(minLegTicks, swingStrength, zoneExpiryBars);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ANCHORING (V4 Agent 05, §S9) — a Rollout-Doctrine switch
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * §S9 pivot width, both sides, on the 30m series. A pivot is therefore
     * confirmed ten 30m bars (five hours) after it printed — that lag is the
     * price of never repainting an anchor.
     */
    static final int TREND_SHIFT_PIVOT_LEN = 10;

    private final Map<String, AnchorMode> anchorModes = new ConcurrentHashMap<>();
    private final Map<String, OteBand> bands = new ConcurrentHashMap<>();

    /**
     * {@code chart.anchorCompare} — when true, BOTH anchoring modes run and
     * their zone states are logged whenever they diverge. Default FALSE: this
     * is evidence-gathering for a future owner decision, not a behaviour.
     */
    private volatile boolean anchorCompare =
            Boolean.parseBoolean(System.getProperty("chart.anchorCompare", "false"));

    /** Which leg-selection strategy this symbol uses. Default FRACTAL_LEG. */
    public AnchorMode anchorModeFor(String symbol) {
        AnchorMode m = anchorModes.get(symbol);
        if (m != null) return m;
        return AnchorMode.parse(System.getProperty("chart.anchorMode." + symbol,
                System.getProperty("chart.anchorMode", "FRACTAL_LEG")));
    }

    /** The retracement band this symbol's zones are armed on. */
    public OteBand bandFor(String symbol) {
        OteBand b = bands.get(symbol);
        if (b != null) return b;
        return OteBand.parse(System.getProperty("chart.oteBand." + symbol,
                System.getProperty("chart.oteBand", null)));
    }

    /** True when the dual-mode comparison log is on. */
    public boolean isAnchorCompareEnabled() {
        return anchorCompare;
    }

    /** Programmatic override of the anchoring config for ONE symbol (tests, tuning). */
    public void configureAnchoring(String symbol, AnchorMode mode, OteBand band) {
        if (symbol == null) return;
        if (mode != null) anchorModes.put(symbol, mode);
        if (band != null) bands.put(symbol, band);
    }

    /** Programmatic override of the dual-mode comparison log. */
    public void setAnchorCompare(boolean enabled) {
        this.anchorCompare = enabled;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // INGEST
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Optional downstream consumer of the SAME 1m candles this chart sees
     * (V4 Agent 02). The ICT detection library hangs off this tap rather than
     * subscribing to the market-data bus separately, so the detections drawn on
     * the Bot Chart are provably derived from the candles drawn beneath them.
     * Null (the default) = nothing attached, zero cost.
     */
    private volatile java.util.function.Consumer<Candle> candleTap;

    /** Install the single downstream candle tap (null clears it). */
    public void setCandleTap(java.util.function.Consumer<Candle> tap) {
        this.candleTap = tap;
    }

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
        // Outside the per-symbol lock: the tap must never be able to stall the
        // chart's own ingest, and it keeps its own state.
        java.util.function.Consumer<Candle> tap = candleTap;
        if (tap != null) {
            try {
                tap.accept(candle);
            } catch (RuntimeException e) {
                // Observation-grade consumers never take down the candle path.
                System.out.println("[CHART " + candle.getSymbol()
                        + "] candle tap failed: " + e);
            }
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

    /**
     * The zone the OTHER anchoring mode would have produced, when
     * {@code chart.anchorCompare=true}. Empty otherwise — this is evidence,
     * never a gate input.
     */
    public Optional<OteZoneSnapshot> getShadowOteZone(String symbol) {
        SymbolChart chart = charts.get(symbol);
        if (chart == null) return Optional.empty();
        synchronized (chart) {
            return Optional.ofNullable(chart.shadowZone);
        }
    }

    /**
     * The most recently invalidated PRIMARY zone, if any. §S9's post-ARM rule
     * replaces a zone rather than re-stretching it, and an invalidation that
     * leaves no trace is not auditable against the chart.
     */
    public Optional<OteZoneSnapshot> getLastInvalidatedZone(String symbol) {
        SymbolChart chart = charts.get(symbol);
        if (chart == null) return Optional.empty();
        synchronized (chart) {
            return Optional.ofNullable(chart.lastInvalidated);
        }
    }

    /** Full snapshot for the dashboard: candles + zone + swing anchors. */
    public ChartSnapshot snapshot(String symbol, int lookback30m) {
        SymbolChart chart = charts.get(symbol);
        if (chart == null) {
            return new ChartSnapshot(symbol, List.of(), null, 0, null, null);
        }
        synchronized (chart) {
            List<Candle> bars30 = get30mCandles(symbol, lookback30m);
            return new ChartSnapshot(
                    symbol,
                    bars30,
                    chart.activeZone,
                    chart.oneMinuteCount,
                    chart.lastCandleTime,
                    // The forming 30m bar — display-only, so the Bot Chart's
                    // right edge matches the broker chart. Swing/fractal
                    // analysis never sees it (confirmed bars only).
                    chart.bars.getInProgressCandle(Timeframe.M30));
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

        // ── V4 Agent 05 ────────────────────────────────────────────────────
        /** The other mode's zone, kept only when chart.anchorCompare is on. */
        OteZoneSnapshot shadowZone;
        int shadowBarsSinceCreated;
        /** Last primary zone that was invalidated (§S9 post-ARM auditability). */
        OteZoneSnapshot lastInvalidated;
        /** Last logged (primary, shadow) state pair — dedup for the compare log. */
        String lastComparePair;

        // §S9 trend-shift state. Only ever driven by whichever track uses
        // TREND_SHIFT, so there is exactly one regime, never two competing.
        Swing tsLastHigh, tsPrevHigh, tsLastLow, tsPrevLow;
        Swing tsOrigin, tsExtreme;
        int tsTrend;
        int tsProcessedPivotIndex = -1;
        // Leg-telemetry dedup: only log a REJECTED/ACCEPTED leg once until
        // the candidate pair changes (tuning telemetry, not per-bar spam —
        // an expired zone redrawn from the SAME leg logs nothing new).
        double lastRejectedOrigin = Double.NaN;
        double lastRejectedExtreme = Double.NaN;
        double lastAcceptedOrigin = Double.NaN;
        double lastAcceptedExtreme = Double.NaN;

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
                // Record a price-driven invalidation the same way the §S9
                // replacement paths do. advanceZone only set the state, so a
                // zone that died by closing beyond its origin left NO trace:
                // getActiveOteZone filters INVALIDATED out and lastInvalidated
                // was never written, making the dead zone unreachable through
                // any public accessor on the default FRACTAL_LEG mode. That is
                // contrary to this field's stated purpose ("an invalidation
                // that leaves no trace is not auditable against the chart") and
                // it is why the notification layer could never announce one.
                if (activeZone.state() == OteState.INVALIDATED
                        && activeZone != lastInvalidated) {
                    lastInvalidated = activeZone;
                }
            }
            if (shadowZone != null) {
                shadowZone = advanceZone(shadowZone, c1m);
            }

            // Recompute swing structure only when a 30m bar completes.
            Candle done30 = completed.get(Timeframe.M30);
            if (done30 != null) {
                int expiry = tuningFor(symbol).zoneExpiryBars();
                if (activeZone != null
                        && activeZone.state() == OteState.FORMING
                        && ++barsSinceZoneCreated > expiry) {
                    activeZone = activeZone.withState(OteState.EXPIRED);
                }
                if (shadowZone != null
                        && shadowZone.state() == OteState.FORMING
                        && ++shadowBarsSinceCreated > expiry) {
                    shadowZone = shadowZone.withState(OteState.EXPIRED);
                }

                AnchorMode mode = anchorModeFor(symbol);
                rebuild(mode, true);
                if (anchorCompare) {
                    rebuild(mode.other(), false);
                    logCompare(mode);
                }
            }
        }

        /** Dispatch to the anchoring strategy that owns this track. */
        void rebuild(AnchorMode mode, boolean primary) {
            if (mode == AnchorMode.TREND_SHIFT) {
                rebuildTrendShift(primary);
            } else {
                rebuildZoneIfNeeded(primary);
            }
        }

        OteZoneSnapshot zoneOf(boolean primary) {
            return primary ? activeZone : shadowZone;
        }

        void setZone(boolean primary, OteZoneSnapshot z) {
            if (primary) {
                activeZone = z;
                barsSinceZoneCreated = 0;
            } else {
                shadowZone = z;
                shadowBarsSinceCreated = 0;
            }
        }

        /** Record an invalidation before the zone is replaced, so it stays auditable. */
        void invalidateCurrent(boolean primary, String why) {
            OteZoneSnapshot z = zoneOf(primary);
            if (z == null || z.state() == OteState.INVALIDATED
                    || z.state() == OteState.EXPIRED) return;
            OteZoneSnapshot dead = z.withState(OteState.INVALIDATED);
            if (primary) {
                lastInvalidated = dead;
                activeZone = dead;
                System.out.println("[CHART " + symbol + "] zone INVALIDATED (" + why + ")");
            } else {
                shadowZone = dead;
            }
        }

        /**
         * One line per completed 30m bar, but only when the two modes' verdicts
         * actually differ AND the pair changed — the point is divergence
         * evidence, not a heartbeat.
         */
        void logCompare(AnchorMode primaryMode) {
            String a = describe(activeZone);
            String b = describe(shadowZone);
            if (a.equals(b)) return;
            String pair = a + "|" + b;
            if (pair.equals(lastComparePair)) return;
            lastComparePair = pair;
            System.out.println("[CHART-ANCHOR " + symbol + "] "
                    + primaryMode + "=" + a + " " + primaryMode.other() + "=" + b);
        }

        String describe(OteZoneSnapshot z) {
            if (z == null) return "NONE";
            return z.state().name() + (z.bullish() ? "/BULL" : "/BEAR")
                    + "@" + z.legOrigin() + "-" + z.legExtreme();
        }

        /**
         * FRACTAL_LEG (pre-V4 behaviour). The body below is unchanged except
         * that it reads and writes the track it was given, so the same code
         * serves the primary run and the chart.anchorCompare shadow.
         */
        void rebuildZoneIfNeeded(boolean primary) {
            InstrumentTuning tune = tuningFor(symbol);
            int strength = tune.swingStrength();
            List<Candle> series = bars.getCandles(Timeframe.M30);
            int n = series.size();
            if (n < strength * 2 + 3) return;

            List<Swing> highs = fractals(series, true, strength);
            List<Swing> lows = fractals(series, false, strength);
            if (highs.isEmpty() || lows.isEmpty()) return;

            Swing lastHigh = highs.get(highs.size() - 1);
            Swing lastLow = lows.get(lows.size() - 1);
            double tick = tickSizes.getOrDefault(symbol, 0.25);
            double minLeg = tune.minLegTicks() * tick;

            // Most recent leg = the later swing defines direction.
            boolean bullishLeg = lastHigh.index > lastLow.index; // low → high = up leg
            Swing origin = bullishLeg ? lastLow : lastHigh;
            Swing extreme = bullishLeg ? lastHigh : lastLow;
            double legSize = Math.abs(extreme.price - origin.price);
            long legTicks = Math.round(legSize / tick);
            if (legSize < minLeg) {
                // Tuning telemetry: once per candidate pair, never per-bar spam.
                if (origin.price != lastRejectedOrigin
                        || extreme.price != lastRejectedExtreme) {
                    lastRejectedOrigin = origin.price;
                    lastRejectedExtreme = extreme.price;
                    System.out.println("[CHART " + symbol + "] leg REJECTED size="
                            + legTicks + "t < minLegTicks=" + tune.minLegTicks());
                }
                return;
            }

            // Don't replace a still-live zone with the SAME leg.
            OteZoneSnapshot live = zoneOf(primary);
            if (live != null
                    && live.state() != OteState.INVALIDATED
                    && live.state() != OteState.EXPIRED
                    && live.legOrigin() == origin.price
                    && live.legExtreme() == extreme.price) {
                return;
            }

            setZone(primary, OteZoneSnapshot.forLeg(
                    symbol, bullishLeg, origin.price, extreme.price,
                    origin.time, extreme.time,
                    bandFor(symbol), AnchorMode.FRACTAL_LEG));
            lastRejectedOrigin = Double.NaN;
            lastRejectedExtreme = Double.NaN;
            if (primary && (origin.price != lastAcceptedOrigin
                    || extreme.price != lastAcceptedExtreme)) {
                lastAcceptedOrigin = origin.price;
                lastAcceptedExtreme = extreme.price;
                System.out.println("[CHART " + symbol + "] leg ACCEPTED origin="
                        + origin.price + " extreme=" + extreme.price
                        + " size=" + legTicks + "t -> OTE drawn (0.62="
                        + zoneOf(primary).oteStart() + ", 0.705="
                        + zoneOf(primary).oteSweet() + ")");
            }
        }

        // ═══════════════════════════════════════════════════════════════════
        // §S9 — TREND_SHIFT ANCHORING
        // ═══════════════════════════════════════════════════════════════════

        /**
         * Anchors the fibs where a human would: from the swing low that STARTED
         * the leg to the confirmed higher high that SHIFTED structure, with the
         * extreme extending as the trend prints new confirmed highs.
         *
         * <p>Pivots are confirmed with {@link #TREND_SHIFT_PIVOT_LEN} bars on
         * BOTH sides, so an anchor never repaints; the candidate bar is always
         * exactly that far behind the newest confirmed 30m bar, and each index
         * is processed once.
         */
        void rebuildTrendShift(boolean primary) {
            List<Candle> series = bars.getCandles(Timeframe.M30);
            int p = series.size() - 1 - TREND_SHIFT_PIVOT_LEN;
            if (p < TREND_SHIFT_PIVOT_LEN) return;
            if (p <= tsProcessedPivotIndex) return;
            tsProcessedPivotIndex = p;

            Candle c = series.get(p);
            if (isPivot(series, p, true, TREND_SHIFT_PIVOT_LEN)) {
                onTrendPivot(primary, new Swing(p, c.getHigh(), c.getTimestamp()), true);
            }
            if (isPivot(series, p, false, TREND_SHIFT_PIVOT_LEN)) {
                onTrendPivot(primary, new Swing(p, c.getLow(), c.getTimestamp()), false);
            }
        }

        private void onTrendPivot(boolean primary, Swing sw, boolean high) {
            Swing previous = high ? tsLastHigh : tsLastLow;
            if (high) {
                tsPrevHigh = tsLastHigh;
                tsLastHigh = sw;
            } else {
                tsPrevLow = tsLastLow;
                tsLastLow = sw;
            }

            boolean shift = previous != null
                    && (high ? sw.price > previous.price : sw.price < previous.price)
                    && (high ? tsTrend <= 0 : tsTrend >= 0);

            if (shift) {
                Swing origin = high ? tsLastLow : tsLastHigh;
                // ABSTAIN: a shift with no opposite swing yet has no origin to
                // anchor to. Record the regime, draw nothing (doctrine C6).
                tsTrend = high ? 1 : -1;
                if (origin == null) return;
                tsOrigin = origin;
                tsExtreme = sw;
                invalidateCurrent(primary, "opposite trend shift");
                setZone(primary, newTrendZone(high));
                return;
            }

            boolean extends_ = (high ? tsTrend == 1 : tsTrend == -1)
                    && tsExtreme != null
                    && (high ? sw.price > tsExtreme.price : sw.price < tsExtreme.price);
            if (!extends_ || tsOrigin == null) return;

            tsExtreme = sw;
            OteZoneSnapshot z = zoneOf(primary);
            if (z == null || z.state() == OteState.FORMING
                    || z.state() == OteState.EXPIRED
                    || z.state() == OteState.INVALIDATED) {
                // Still forming (or gone): re-stretching is free — no fact has
                // been recorded against the old fibs yet.
                setZone(primary, newTrendZone(high));
            } else {
                // ARMED or REACTED: those are HISTORICAL FACTS about prices that
                // were actually traded. Re-stretching would let the zone chase
                // price and make REACTED unfalsifiable (Appendix E7), so the old
                // zone is invalidated and a fresh one forms on the new anchors.
                invalidateCurrent(primary, "post-ARM anchor extension");
                setZone(primary, newTrendZone(high));
            }
        }

        private OteZoneSnapshot newTrendZone(boolean bullish) {
            return OteZoneSnapshot.forLeg(symbol, bullish,
                    tsOrigin.price, tsExtreme.price, tsOrigin.time, tsExtreme.time,
                    bandFor(symbol), AnchorMode.TREND_SHIFT);
        }

        /** Confirmed pivot: strictly more extreme than {@code len} bars each side. */
        private boolean isPivot(List<Candle> s, int i, boolean high, int len) {
            double v = high ? s.get(i).getHigh() : s.get(i).getLow();
            for (int k = 1; k <= len; k++) {
                int l = i - k;
                int r = i + k;
                if (l < 0 || r >= s.size()) return false;
                double lv = high ? s.get(l).getHigh() : s.get(l).getLow();
                double rv = high ? s.get(r).getHigh() : s.get(r).getLow();
                if (high ? !(v > lv && v > rv) : !(v < lv && v < rv)) return false;
            }
            return true;
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
                        ? c.getLow() <= z.oteStart()
                        : c.getHigh() >= z.oteStart();
                if (tagged) return z.withState(OteState.ARMED).withTagTime(c.getTimestamp());
            } else if (z.state() == OteState.ARMED) {
                // REACTED: a close back on the extreme-side of the 0.62 line
                // after the tag = the rejection you see on the screenshot.
                boolean rejected = z.bullish()
                        ? c.getClose() > z.oteStart()
                        : c.getClose() < z.oteStart();
                if (rejected) return z.withState(OteState.REACTED);
            }
            return z;
        }

        /** Classic fractal swings on the 30m series. */
        List<Swing> fractals(List<Candle> s, boolean highs, int swingStrength) {
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
