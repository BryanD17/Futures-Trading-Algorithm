package com.topstep.trading.ictlib;

import com.topstep.trading.domain.Candle;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session-scoped comparison counters between ictlib's Appendix S rules and the
 * gate detectors they run beside.
 *
 * <p>WHY this exists: V4 forbids ictlib from silently replacing a gate detector
 * (anti-pattern C3). Two truths are allowed to coexist only if their divergence
 * is MEASURED, so the owner can later unify from evidence instead of taste.
 * The line looks like:
 *
 * <pre>
 *   [ICTLIB-DIFF MNQ] fvg: ictlib=14 existing=181 overlap=14
 * </pre>
 *
 * <p>Counts aggregate the timeframes ictlib registers (1m and 15m), because
 * both are ictlib detections; the legacy rule is evaluated on the same two
 * series so the comparison is like-for-like.
 *
 * <p>{@code overlap} is an INVARIANT CHECK, not a coincidence: in FVG mode the
 * §S2 rule is the legacy rule AND a displacement requirement, so every ictlib
 * gap must also be a legacy gap and {@code overlap == ictlib}. If it ever is
 * not, either the mode is IFVG (where the gap comparison inverts) or something
 * has drifted and the delta is the alarm.
 *
 * <p>METHOD NOTE — why the legacy rule is MIRRORED here rather than driving a
 * shadow {@code strategy.FvgDetector}: that class prints a line on every gap it
 * finds, and a five-day backfill would emit tens of thousands of them. The
 * mirror is pinned to the real class by
 * {@code IctLibDiffStatsTest#mirrorMatchesLegacyFvgDetector}, so drift fails a
 * test rather than quietly making the diff meaningless.
 */
public final class IctLibDiffStats {

    private static final Map<String, IctLibDiffStats> INSTANCES = new ConcurrentHashMap<>();

    private final String symbol;

    private long ictlibFvg;
    private long legacyFvg;
    private long overlapFvg;

    private IctLibDiffStats(String symbol) {
        this.symbol = symbol;
    }

    public static IctLibDiffStats forSymbol(String symbol) {
        return INSTANCES.computeIfAbsent(symbol == null ? "?" : symbol, IctLibDiffStats::new);
    }

    /** Test hook — drops every symbol's counters. */
    public static void resetAll() {
        INSTANCES.clear();
    }

    public synchronized void resetSession() {
        ictlibFvg = 0;
        legacyFvg = 0;
        overlapFvg = 0;
    }

    public synchronized long ictlibFvg() { return ictlibFvg; }
    public synchronized long legacyFvg() { return legacyFvg; }
    public synchronized long overlapFvg() { return overlapFvg; }

    synchronized void record(boolean ictlibFired, boolean legacyFired) {
        if (ictlibFired) ictlibFvg++;
        if (legacyFired) legacyFvg++;
        if (ictlibFired && legacyFired) overlapFvg++;
    }

    /** Compact rollup, the shape the [ICTLIB-DIFF] line carries. */
    public synchronized String rollup() {
        return "fvg: ictlib=" + ictlibFvg + " existing=" + legacyFvg
                + " overlap=" + overlapFvg;
    }

    /** The full log line, emitted once per session and on demand. */
    public synchronized String logLine() {
        return "[ICTLIB-DIFF " + symbol + "] " + rollup();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // THE COMPARISON ITSELF
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Evaluate both rules on the newest closed bar of {@code series} and fold
     * the result into this session's counters. Pure measurement — it creates
     * nothing and gates nothing.
     */
    void compareAtCurrentBar(TimeframeSeries series, IctLibConfig config) {
        Candle c0 = series.at(0);
        Candle c2 = series.at(2);
        if (c0 == null || c2 == null) return;
        boolean inverse = config.gapMode == IctLibConfig.GapMode.IFVG;

        // Bullish read.
        boolean legacyBull = legacyBullishGap(c0, c2);
        boolean gapBull = inverse ? (c0.getLow() < c2.getHigh()) : legacyBull;
        boolean ictlibBull = gapBull && DisplacementRule.isDisplacementUp(
                series, 1, config.displacementMeanLen, config.displacementWickRatioMax);
        record(ictlibBull, legacyBull);

        // Bearish read.
        boolean legacyBear = legacyBearishGap(c0, c2);
        boolean gapBear = inverse ? (c0.getHigh() > c2.getLow()) : legacyBear;
        boolean ictlibBear = gapBear && DisplacementRule.isDisplacementDown(
                series, 1, config.displacementMeanLen, config.displacementWickRatioMax);
        record(ictlibBear, legacyBear);
    }

    /**
     * The gate-side detector's bullish rule, mirrored:
     * {@code strategy/FvgDetector.java:57-66} — a gap between candle 1's high
     * and candle 3's low, with NO displacement requirement.
     */
    public static boolean legacyBullishGap(Candle c0, Candle c2) {
        return c0.getLow() > c2.getHigh();
    }

    /** The gate-side detector's bearish rule, mirrored ({@code FvgDetector.java:68-77}). */
    public static boolean legacyBearishGap(Candle c0, Candle c2) {
        return c2.getLow() > c0.getHigh();
    }
}
