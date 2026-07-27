package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.chartstate.LevelEngine;
import com.topstep.trading.strategy.HtfTrendAnalyzer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Premium/discount evaluator behind the M2b gate (V3 Agent 02).
 *
 * <p>WHY: the top-down rule "longs only at a DISCOUNT (below equilibrium of
 * the governing range), shorts only at a PREMIUM (above it)" existed in the
 * codebase solely as {@link HtfTrendAnalyzer#isInFavorableZone} — correct
 * math with one log-string call site on a strategy that is off by default.
 * This class gives the rule a REAL governing range and a REAL gate, behind
 * a three-position rollout switch.
 *
 * <h2>Governing-range resolution (until Agent 05 prepends R0/D1)</h2>
 * <ol>
 *   <li>R1 — previous-day range: PDH/PDL from {@link LevelEngine}, IF the
 *       judged price sits inside {@code [PDL, PDH]}. Default structural
 *       dealing range.</li>
 *   <li>R2 — breakout day (judged price outside yesterday's range, or
 *       PDH/PDL not yet known): the current day's developing range, but
 *       only once it spans {@code >= pd.minRangeTicks} — a 10-tick "range"
 *       has no meaningful equilibrium.</li>
 *   <li>R3 — ABSTAIN: pass + log + count. A gate must NEVER block on
 *       missing or degenerate data (Rollout Doctrine / B10).</li>
 * </ol>
 *
 * <h2>Modes ({@code pd.gate.mode}, DEFAULT LOG)</h2>
 * <ul>
 *   <li>{@code OFF} — {@link #gateCheck} returns immediately; no counters,
 *       no range resolution. Byte-identical to pre-V3 behavior.</li>
 *   <li>{@code LOG} — the verdict is computed and counted; an unfavorable
 *       verdict logs {@code WOULD-BLOCK} but the gate still passes.</li>
 *   <li>{@code BLOCK} — DISCOUNT required for longs, PREMIUM for shorts;
 *       EQUILIBRIUM (within {@code pd.eqBandTicks} of the midpoint) blocks
 *       BOTH directions; ABSTAIN always passes.</li>
 * </ul>
 *
 * <p>The judged price is the PROPOSED ENTRY (a resting limit at ~0.705 of
 * the leg), never the current tick — price being momentarily in premium
 * while the limit rests in discount is the normal geometry of the setup.
 *
 * <p>The equilibrium arithmetic is DELEGATED to
 * {@link HtfTrendAnalyzer#equilibriumOf(double, double)} — the single
 * midpoint formula in the codebase (anti-pattern C7: never fork a second
 * copy that can drift).
 *
 * <p>Counters are SESSION-SCOPED (JVM lifetime, like
 * {@link OteAgreementStats}) and thread-safe. Market-data thread writes,
 * API thread reads snapshots via {@link #toApiMap()}.
 */
public final class PremiumDiscountEvaluator {

    /** System property: gate mode. */
    public static final String MODE_PROPERTY = "pd.gate.mode";
    /** System property: equilibrium band width in ticks (default 2). */
    public static final String EQ_BAND_TICKS_PROPERTY = "pd.eqBandTicks";
    public static final int DEFAULT_EQ_BAND_TICKS = 2;
    /** System property: minimum developing-range span in ticks for R2. */
    public static final String MIN_RANGE_TICKS_PROPERTY = "pd.minRangeTicks";
    /** System property: minimum D1 depth (bars) before R0 governs (V3 Agent 05). */
    public static final String D1_MIN_BARS_PROPERTY = "pd.d1MinBars";
    public static final int DEFAULT_D1_MIN_BARS = 10;
    /** Fractal strength for the D1 dealing-range swings. */
    public static final int D1_SWING_STRENGTH = 2;

    /** Three-position rollout switch (Rollout Doctrine). */
    public enum PdMode { OFF, LOG, BLOCK }

    /** Verdict of a single evaluation. */
    public enum PdVerdict { DISCOUNT, PREMIUM, EQUILIBRIUM, ABSTAIN }

    /** Full context of one evaluation (for logs / API / tests). */
    public record PdContext(PdVerdict verdict, String rangeSource,
                            double rangeHigh, double rangeLow,
                            double equilibrium, double price, String detail) {}

    /** Outcome of the M2b gate check. */
    public record GateDecision(boolean passed, PdContext context, String reason) {}

    // Per-symbol registry so the API layer (SetupController) can read the
    // counters without a path from SetupContext — same pattern as
    // OteAgreementStats.forSymbol.
    private static final Map<String, PremiumDiscountEvaluator> REGISTRY =
            new ConcurrentHashMap<>();

    /** Build from system properties, register for API access, log config. */
    public static PremiumDiscountEvaluator install(String symbol, double tickSize,
                                                   LevelEngine levels) {
        PdMode mode = parseMode(System.getProperty(MODE_PROPERTY, "LOG"));
        int eqBand = Integer.getInteger(EQ_BAND_TICKS_PROPERTY, DEFAULT_EQ_BAND_TICKS);
        // Default minRangeTicks = 2x the symbol's chart minLegTicks
        // (chart.minLegTicks.<SYM>, ChartEngine default 40), overridable
        // globally (pd.minRangeTicks) or per symbol (pd.minRangeTicks.<SYM>).
        int chartMinLeg = Integer.getInteger("chart.minLegTicks." + symbol, 40);
        int minRange = Integer.getInteger(MIN_RANGE_TICKS_PROPERTY + "." + symbol,
                Integer.getInteger(MIN_RANGE_TICKS_PROPERTY, 2 * chartMinLeg));
        PremiumDiscountEvaluator e = new PremiumDiscountEvaluator(
                symbol, tickSize, levels, mode, eqBand, minRange);
        // R0 (V3 Agent 05): the D1 dealing range from the authoritative
        // ladder, once it is at least pd.d1MinBars deep. Wired ONLY here —
        // direct-constructed evaluators (tests, pre-Agent-05 wiring) keep
        // an empty D1 source and behave exactly as before (fallback proof).
        int d1MinBars = Integer.getInteger(D1_MIN_BARS_PROPERTY, DEFAULT_D1_MIN_BARS);
        e.configureD1Source(() -> com.topstep.trading.strategy.HtfSeriesRegistry
                .get(symbol)
                .map(m -> m.getCandlesSnapshot(
                        com.topstep.trading.strategy.BarAggregationManager.Timeframe.D1, 200))
                .orElse(java.util.List.of()), d1MinBars);
        REGISTRY.put(symbol, e);
        System.out.println("[PD " + symbol + "] config: mode=" + mode
                + " eqBandTicks=" + eqBand + " minRangeTicks=" + minRange
                + " d1MinBars=" + d1MinBars);
        return e;
    }

    /** Registered evaluator for a symbol (empty before the runner wires it). */
    public static Optional<PremiumDiscountEvaluator> get(String symbol) {
        return Optional.ofNullable(REGISTRY.get(symbol));
    }

    static PdMode parseMode(String raw) {
        if (raw == null) return PdMode.LOG;
        try {
            return PdMode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            System.out.println("[PD] WARN: invalid " + MODE_PROPERTY + "='" + raw
                    + "', using default LOG");
            return PdMode.LOG;
        }
    }

    private final String symbol;
    private final double tickSize;
    private final LevelEngine levels;
    private final PdMode mode;
    private final int eqBandTicks;
    private final int minRangeTicks;

    // ── Session-scoped counters (labeled as such; NOT persisted) ─────────
    private final AtomicLong evaluations = new AtomicLong();
    private final AtomicLong wouldBlockLong = new AtomicLong();
    private final AtomicLong wouldBlockShort = new AtomicLong();
    private final AtomicLong blockedLong = new AtomicLong();
    private final AtomicLong blockedShort = new AtomicLong();
    private final Map<String, AtomicLong> abstainsByReason = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> verdictsByRangeSource = new ConcurrentHashMap<>();

    /**
     * D1 series source for the R0 dealing range (V3 Agent 05). Default:
     * EMPTY — R0 never governs unless {@link #configureD1Source} wires a
     * real ladder, so every pre-Agent-05 behavior (and test) is unchanged.
     */
    private volatile java.util.function.Supplier<java.util.List<com.topstep.trading.domain.Candle>>
            d1Source = java.util.List::of;
    private volatile int d1MinBars = DEFAULT_D1_MIN_BARS;

    /** Wire the D1 ladder for R0 (production: install(); tests: direct). */
    public void configureD1Source(
            java.util.function.Supplier<java.util.List<com.topstep.trading.domain.Candle>> source,
            int minBars) {
        if (source != null) this.d1Source = source;
        this.d1MinBars = Math.max(1, minBars);
    }

    /** Token of the most recent gate event since the last [GATES] line. */
    private volatile String pendingGateEventToken;
    /** True while the last gate evaluation in BLOCK mode failed (UI hint). */
    private volatile boolean lastGateBlocked;

    /**
     * Direct construction with explicit config — used by tests and any
     * wiring that does not want the system-property resolution. Production
     * runners use {@link #install}, which also registers for API access.
     */
    public PremiumDiscountEvaluator(String symbol, double tickSize, LevelEngine levels,
                                    PdMode mode, int eqBandTicks, int minRangeTicks) {
        this.symbol = symbol;
        this.tickSize = tickSize;
        this.levels = levels;
        this.mode = mode;
        this.eqBandTicks = Math.max(0, eqBandTicks);
        this.minRangeTicks = Math.max(1, minRangeTicks);
    }

    public PdMode mode() {
        return mode;
    }

    /** Gate-evaluation count — the OFF-mode no-invocation assertion hook. */
    public long evaluationCount() {
        return evaluations.get();
    }

    /**
     * The M2b check, called by the validator between M2 and M3 with the
     * PROPOSED ENTRY price and the trade direction.
     */
    public GateDecision gateCheck(double entryPrice, boolean bullish) {
        if (mode == PdMode.OFF) {
            // Cheap no-op: no range resolution, no counters (tested).
            return new GateDecision(true, null, "OFF");
        }
        evaluations.incrementAndGet();
        PdContext ctx = classify(entryPrice);
        String side = bullish ? "LONG" : "SHORT";

        if (ctx.verdict() == PdVerdict.ABSTAIN) {
            abstainsByReason.computeIfAbsent(ctx.detail(), k -> new AtomicLong())
                    .incrementAndGet();
            verdictsByRangeSource.computeIfAbsent("R3", k -> new AtomicLong())
                    .incrementAndGet();
            pendingGateEventToken = "ABSTAIN(" + ctx.detail() + ")";
            lastGateBlocked = false;
            System.out.println("[PD " + symbol + "] ABSTAIN " + ctx.detail()
                    + " — gate passes (" + side + " entry=" + entryPrice + ")");
            return new GateDecision(true, ctx, "ABSTAIN " + ctx.detail());
        }

        verdictsByRangeSource.computeIfAbsent(ctx.rangeSource(), k -> new AtomicLong())
                .incrementAndGet();
        boolean favorable = (bullish && ctx.verdict() == PdVerdict.DISCOUNT)
                || (!bullish && ctx.verdict() == PdVerdict.PREMIUM);
        if (favorable) {
            pendingGateEventToken = ctx.verdict() + "(" + ctx.rangeSource() + ")";
            lastGateBlocked = false;
            return new GateDecision(true, ctx,
                    ctx.verdict() + " via " + ctx.rangeSource());
        }

        String describe = side + " entry=" + entryPrice
                + " verdict=" + ctx.verdict()
                + " eq=" + ctx.equilibrium()
                + " range=" + ctx.rangeSource()
                + " hi=" + ctx.rangeHigh() + "/lo=" + ctx.rangeLow();
        if (mode == PdMode.LOG) {
            (bullish ? wouldBlockLong : wouldBlockShort).incrementAndGet();
            pendingGateEventToken = "WOULD-BLOCK-" + side;
            lastGateBlocked = false;
            System.out.println("[PD " + symbol + "] WOULD-BLOCK " + describe);
            return new GateDecision(true, ctx, "LOG: would block (" + ctx.verdict() + ")");
        }
        // BLOCK mode.
        (bullish ? blockedLong : blockedShort).incrementAndGet();
        pendingGateEventToken = "BLOCKED-" + side;
        lastGateBlocked = true;
        System.out.println("[PD " + symbol + "] BLOCK " + describe);
        return new GateDecision(false, ctx,
                side + " entry " + entryPrice + " is " + ctx.verdict()
                + " vs equilibrium " + ctx.equilibrium()
                + " of " + ctx.rangeSource() + " range ["
                + ctx.rangeLow() + ", " + ctx.rangeHigh() + "]");
    }

    /**
     * Classify a price against the governing range. Pure given the same
     * {@link LevelEngine} state — no counters, no logging; used by the gate
     * and by the [GATES]-line preview.
     */
    public PdContext classify(double price) {
        Optional<Double> pdh = levels.getPDH();
        Optional<Double> pdl = levels.getPDL();

        double hi;
        double lo;
        String source;
        double[] d1Range = d1DealingRange();
        if (d1Range != null) {
            // R0 (V3 Agent 05): the last ESTABLISHED D1 swing high/low —
            // the dealing range where the methodology starts. Falls through
            // to R1/R2/R3 exactly as before whenever D1 is thin or has no
            // confirmed swings yet (safe degradation by construction).
            hi = d1Range[0];
            lo = d1Range[1];
            source = "R0-D1";
        } else if (pdh.isPresent() && pdl.isPresent() && pdh.get() > pdl.get()
                && price >= pdl.get() && price <= pdh.get()) {
            hi = pdh.get();
            lo = pdl.get();
            source = "R1";
        } else {
            Optional<Double> dayHi = levels.getDevelopingDayHigh();
            Optional<Double> dayLo = levels.getDevelopingDayLow();
            if (dayHi.isEmpty() || dayLo.isEmpty()) {
                return new PdContext(PdVerdict.ABSTAIN, "R3", Double.NaN,
                        Double.NaN, Double.NaN, price, "no-day-range-yet");
            }
            double span = dayHi.get() - dayLo.get();
            if (span < minRangeTicks * tickSize) {
                String why = (pdh.isEmpty() || pdl.isEmpty())
                        ? "no-pdh-pdl-and-day-range-thin"
                        : "breakout-day-range-thin";
                return new PdContext(PdVerdict.ABSTAIN, "R3", Double.NaN,
                        Double.NaN, Double.NaN, price, why);
            }
            hi = dayHi.get();
            lo = dayLo.get();
            source = "R2";
        }

        // Reused midpoint formula — never forked (C7).
        double eq = HtfTrendAnalyzer.equilibriumOf(hi, lo);
        return verdictFor(price, hi, lo, eq, source);
    }

    /**
     * The D1 dealing range: most recent fractal-strength-2 swing high and
     * low on the D1 series, once it is {@code pd.d1MinBars} deep. Null when
     * R0 cannot govern (thin series, no confirmed swings, degenerate range)
     * — STDV_OTE_MODEL.md gives no explicit D1 dealing-range formula (its
     * §H2 dealing range is the intraday manipulation leg), so the V3
     * document's fallback definition applies; recorded in Appendix A-05.
     */
    private double[] d1DealingRange() {
        java.util.List<com.topstep.trading.domain.Candle> d1 = d1Source.get();
        if (d1 == null || d1.size() < d1MinBars) return null;
        java.util.List<Double> highs = FractalSwings.swingHighs(d1, D1_SWING_STRENGTH);
        java.util.List<Double> lows = FractalSwings.swingLows(d1, D1_SWING_STRENGTH);
        if (highs.isEmpty() || lows.isEmpty()) return null;
        double hi = highs.get(highs.size() - 1);
        double lo = lows.get(lows.size() - 1);
        if (!(hi > lo)) return null;
        return new double[] {hi, lo};
    }

    private PdContext verdictFor(double price, double hi, double lo,
                                 double eq, String source) {
        PdVerdict verdict;
        if (Math.abs(price - eq) <= eqBandTicks * tickSize) {
            verdict = PdVerdict.EQUILIBRIUM;
        } else if (price < eq) {
            verdict = PdVerdict.DISCOUNT;
        } else {
            verdict = PdVerdict.PREMIUM;
        }
        return new PdContext(verdict, source, hi, lo, eq, price, "");
    }

    /**
     * Compact token for the 15m [GATES] line: the most recent gate event
     * (WOULD-BLOCK / BLOCKED / ABSTAIN) if one fired since the last call,
     * otherwise a live preview of the given price's verdict.
     */
    public String gatesToken(double previewPrice) {
        if (mode == PdMode.OFF) {
            return "pd=OFF"; // no range resolution in OFF mode (cheap no-op)
        }
        String event = pendingGateEventToken;
        if (event != null) {
            pendingGateEventToken = null;
            return "pd=" + event;
        }
        PdContext ctx = classify(previewPrice);
        if (ctx.verdict() == PdVerdict.ABSTAIN) {
            return "pd=ABSTAIN(" + ctx.detail() + ")";
        }
        return "pd=" + ctx.verdict() + "(" + ctx.rangeSource() + ")";
    }

    /** JSON-friendly snapshot for /api/setup (session-scoped counters). */
    public Map<String, Object> toApiMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", mode.name());
        m.put("eqBandTicks", eqBandTicks);
        m.put("minRangeTicks", minRangeTicks);
        m.put("gatePassing", !lastGateBlocked);
        m.put("wouldBlockLong", wouldBlockLong.get());
        m.put("wouldBlockShort", wouldBlockShort.get());
        m.put("blockedLong", blockedLong.get());
        m.put("blockedShort", blockedShort.get());
        Map<String, Long> abst = new LinkedHashMap<>();
        abstainsByReason.forEach((k, v) -> abst.put(k, v.get()));
        m.put("abstainsByReason", abst);
        Map<String, Long> byRange = new LinkedHashMap<>();
        verdictsByRangeSource.forEach((k, v) -> byRange.put(k, v.get()));
        m.put("verdictsByRangeSource", byRange);
        return m;
    }

    // Test hooks (package-private).
    long wouldBlockLongCount()  { return wouldBlockLong.get(); }
    long wouldBlockShortCount() { return wouldBlockShort.get(); }
    long blockedLongCount()     { return blockedLong.get(); }
    long blockedShortCount()    { return blockedShort.get(); }
    long abstainCount() {
        return abstainsByReason.values().stream().mapToLong(AtomicLong::get).sum();
    }
}
