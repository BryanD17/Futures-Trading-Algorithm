package com.topstep.trading.ictlib;

import com.topstep.trading.chart.ChartEngine;
import com.topstep.trading.domain.Candle;
import com.topstep.trading.strategy.BarAggregationManager;
import com.topstep.trading.strategy.BarAggregationManager.Timeframe;
import com.topstep.trading.strategy.TradingSessionCalendar;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The ICT library's engine: one bounded {@link DetectionRegistry} per symbol,
 * fed by the SAME closed 1m candles the {@link ChartEngine} sees.
 *
 * <h2>One ingest seam</h2>
 * ictlib does not subscribe to the market-data bus, and it is not wired into
 * the three runner call sites separately. It hangs off
 * {@link ChartEngine#setCandleTap} via {@link #attachTo(ChartEngine)} — a
 * single seam. The reason is not tidiness: the Bot Chart's whole purpose is to
 * prove the bot sees what the broker chart shows, and a detection library fed
 * from a DIFFERENT tap could disagree with the candles drawn beneath it and
 * nobody would be able to tell which was lying. Backfill and live both flow
 * through {@code ChartEngine.onCandle}, so both flow through here.
 *
 * <h2>Timeframes</h2>
 * §S1 and §S2 register on the 1m feed AND the 15m aggregate. The 15m series is
 * rebuilt from the same 1m stream by the repo's existing clock-aligned
 * {@link BarAggregationManager}, so ictlib's 15m bars are the same bars the
 * rest of the engine reasons about.
 *
 * <p>SPEC DECISION (§S3 timeframe): the spec does not name one for BPR. It is
 * derived purely from FVGs, so it runs on every timeframe the gaps run on and
 * pairs only same-timeframe gaps — mixing a 1m gap with a 15m gap would produce
 * a region neither series can invalidate.
 *
 * <p>Determinism: everything keys on candle timestamps and bar indices; there
 * is no wall-clock read anywhere in this package, so the same feed always
 * yields the same detections (V4 critical rule 7).
 */
public final class IctLibEngine {

    /** Timeframe labels ictlib registers detections under. */
    public static final String TF_1M = "1m";
    public static final String TF_15M = "15m";

    private final IctLibConfig config;
    private final Map<String, SymbolState> states = new ConcurrentHashMap<>();

    public IctLibEngine() {
        this(IctLibConfig.fromSystemProperties());
    }

    public IctLibEngine(IctLibConfig config) {
        this.config = config;
    }

    public IctLibConfig config() {
        return config;
    }

    /**
     * Install this library on the chart's candle tap — THE ingest seam.
     * Returns the engine so runners can wire it in one line.
     */
    public static IctLibEngine attachTo(ChartEngine chart) {
        IctLibEngine lib = new IctLibEngine();
        chart.setCandleTap(lib::onCandle);
        System.out.println("[ICTLIB] " + lib.config().describe());
        return lib;
    }

    /** Feed one CLOSED 1m candle (backfill or live — identical path). */
    public void onCandle(Candle candle) {
        if (!config.enabled) return;
        if (candle == null || candle.getSymbol() == null) return;
        // Detections evaluate on CLOSED candles only: an in-progress bar would
        // let a zone form and un-form as the bar ticked (V4 critical rule 7).
        if (candle.isPartial()) return;

        SymbolState st = states.computeIfAbsent(candle.getSymbol(),
                s -> new SymbolState(s, config));
        synchronized (st) {
            st.ingest(candle);
        }
    }

    /** The symbol's registry, created empty on first ask (never null). */
    public DetectionRegistry registry(String symbol) {
        return states.computeIfAbsent(symbol, s -> new SymbolState(s, config)).registry;
    }

    /** The symbol's registry only if candles have been seen for it. */
    public Optional<DetectionRegistry> registryIfPresent(String symbol) {
        SymbolState st = states.get(symbol);
        return Optional.ofNullable(st == null ? null : st.registry);
    }

    /** Symbols this engine has ingested candles for. */
    public List<String> symbols() {
        return new ArrayList<>(states.keySet());
    }

    /** Emit the [ICTLIB-DIFF] line for every known symbol, on demand. */
    public void logDiffLines() {
        for (String symbol : states.keySet()) {
            System.out.println(IctLibDiffStats.forSymbol(symbol).logLine());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PER-SYMBOL STATE
    // ═══════════════════════════════════════════════════════════════════════

    private static final class SymbolState {
        final String symbol;
        final IctLibConfig config;
        final DetectionRegistry registry;
        final BarAggregationManager bars;
        final Map<String, TimeframeSeries> series = new LinkedHashMap<>();
        final Map<String, List<FamilyDetector>> detectors = new LinkedHashMap<>();
        final IctLibDiffStats diff;
        LocalDate sessionDate;

        SymbolState(String symbol, IctLibConfig config) {
            this.symbol = symbol;
            this.config = config;
            this.registry = new DetectionRegistry(symbol, config.retentions());
            // 400 bars is plenty: ictlib re-derives 15m here only to run its own
            // detectors; the engine's authoritative aggregation lives elsewhere.
            this.bars = new BarAggregationManager(symbol, 400);
            this.diff = IctLibDiffStats.forSymbol(symbol);
            for (String tf : new String[]{TF_1M, TF_15M}) {
                series.put(tf, new TimeframeSeries(tf));
                detectors.put(tf, List.of(
                        new DisplacementScanner(config),
                        new FairValueGapDetector(config),
                        new BprDetector()));
            }
        }

        void ingest(Candle c1m) {
            rollSessionIfNeeded(c1m);

            runTimeframe(TF_1M, c1m);

            Map<Timeframe, Candle> completed = bars.processCandle(c1m);
            Candle done15 = completed.get(Timeframe.M15);
            if (done15 != null) {
                runTimeframe(TF_15M, done15);
            }
        }

        private void runTimeframe(String tf, Candle candle) {
            TimeframeSeries s = series.get(tf);
            s.push(candle);
            for (FamilyDetector d : detectors.get(tf)) {
                d.onBar(s, registry);
            }
            diff.compareAtCurrentBar(s, config);
        }

        /**
         * Sessions key on the CME session date derived from the CANDLE's
         * timestamp — never the wall clock, so a replay of last Tuesday emits
         * last Tuesday's boundaries (V4 B6 / critical rule 10).
         */
        private void rollSessionIfNeeded(Candle c) {
            LocalDate sd = TradingSessionCalendar.sessionDate(c.getTimestamp());
            if (sessionDate == null) {
                sessionDate = sd;
                return;
            }
            if (!sd.equals(sessionDate)) {
                System.out.println(diff.logLine());
                diff.resetSession();
                sessionDate = sd;
            }
        }
    }
}
