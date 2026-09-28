package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.domain.Candle;
import com.topstep.trading.strategy.FairValueGap;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * V5 Agent 04 — PD arrays on the DETECTOR timeframe (RC-10, RC-12, D-19).
 *
 * <p>Holds the last {@value #MAX_BARS} detector bars (default 5m) and answers:
 * <ul>
 *   <li>{@link #linkedFvg} — the FVG created BY a displacement bar (the bar is
 *       the middle candle of the 3-bar gap) or by any 3-bar window whose middle
 *       candle lies within {@code linkBars} bars after it (or whose last candle
 *       IS the displacement). The gate-side {@code FvgDetector} uses the same
 *       3-bar rule and stamps the gap with the MIDDLE candle, so the two agree
 *       by construction (A-04 shows both on G1: bearish [30620.75, 30630.00]
 *       stamped 15:00).</li>
 *   <li>{@link #orderBlock} — the last opposite-close candle before the
 *       displacement, valid once a close within {@code linkBars} bars of the
 *       displacement breaks its far side (G1: 14:50 bar [30621.00, 30650.00]).</li>
 *   <li>{@link #candidates} — every PD array (FVG / OB / IFVG / BREAKER) the
 *       M7 alarm may use: unfilled same-direction FVGs, inverted opposite
 *       FVGs, breakers (opposite OBs later closed through), plus the linked
 *       FVG and OB of the current setup.</li>
 * </ul>
 * A candidate qualifies for the OTE band when it merely OVERLAPS the band
 * ({@link #overlaps}) — the D-19 fix; the entry level is then clamped INTO
 * the band ({@link #entryLevel}).
 */
public final class PdArrayLocator {

    /** A PD array zone. {@code kind} ∈ FVG | OB | IFVG | BREAKER. */
    public record PdArray(String kind, boolean bullish, double bottom, double top, Instant at) {
        /** Far edge (stop side): bearish → top, bullish → bottom. */
        public double farEdge() { return bullish ? bottom : top; }

        public FairValueGap asFairValueGap() {
            return new FairValueGap(bullish, top, bottom, at);
        }
    }

    private record Bar(long idx, Candle c) {}

    static final int MAX_BARS = 200;
    private static final int SCAN_BARS = 60;

    private final List<Bar> bars = new ArrayList<>();
    private long lastIdx = -1;

    /** Feed one completed detector-timeframe bar; returns its index. */
    public long onBar(Candle c) {
        lastIdx++;
        bars.add(new Bar(lastIdx, c));
        if (bars.size() > MAX_BARS) bars.remove(0);
        return lastIdx;
    }

    public long lastIndex() { return lastIdx; }

    public void reset() {
        bars.clear();
        lastIdx = -1;
    }

    /** Index of the bar stamped {@code ts}, or -1. */
    public long indexOf(Instant ts) {
        if (ts == null) return -1;
        for (int i = bars.size() - 1; i >= 0; i--) {
            if (ts.equals(bars.get(i).c().getTimestamp())) return bars.get(i).idx();
        }
        return -1;
    }

    private Candle at(long idx) {
        if (bars.isEmpty()) return null;
        long first = bars.get(0).idx();
        long off = idx - first;
        if (off < 0 || off >= bars.size()) return null;
        return bars.get((int) off).c();
    }

    /** 3-bar gap with middle candle {@code m}, in the given direction. */
    private Optional<PdArray> gapAt(long m, boolean bullish) {
        Candle c1 = at(m - 1);
        Candle c2 = at(m);
        Candle c3 = at(m + 1);
        if (c1 == null || c2 == null || c3 == null) return Optional.empty();
        if (bullish && c1.getHigh() < c3.getLow()) {
            return Optional.of(new PdArray("FVG", true, c1.getHigh(), c3.getLow(), c2.getTimestamp()));
        }
        if (!bullish && c1.getLow() > c3.getHigh()) {
            return Optional.of(new PdArray("FVG", false, c3.getHigh(), c1.getLow(), c2.getTimestamp()));
        }
        return Optional.empty();
    }

    /**
     * FVG linked to the displacement at {@code dispIdx}: created BY it (middle
     * candle), else by a window whose middle candle is within {@code linkBars}
     * after it, else by the window that ENDS on it.
     */
    public Optional<PdArray> linkedFvg(long dispIdx, boolean bullish, int linkBars) {
        if (dispIdx < 0) return Optional.empty();
        for (long m = dispIdx; m <= dispIdx + linkBars; m++) {
            Optional<PdArray> g = gapAt(m, bullish);
            if (g.isPresent()) return g;
        }
        return gapAt(dispIdx - 1, bullish);
    }

    /** True once the window needed by {@link #linkedFvg} is fully printed. */
    public boolean linkWindowClosed(long dispIdx, int linkBars) {
        return lastIdx >= dispIdx + linkBars + 1;
    }

    /**
     * Order block behind the displacement at {@code dispIdx}: the last
     * opposite-close candle within the 5 bars before it; valid once a close in
     * [dispIdx, dispIdx + linkBars] breaks its far side (bearish: below its low).
     */
    public Optional<PdArray> orderBlock(long dispIdx, boolean bullish, int linkBars) {
        for (long j = dispIdx - 1; j >= dispIdx - 5; j--) {
            Candle c = at(j);
            if (c == null) break;
            boolean opposite = bullish ? c.getClose() < c.getOpen() : c.getClose() > c.getOpen();
            if (!opposite) continue;
            for (long k = dispIdx; k <= Math.min(lastIdx, dispIdx + linkBars); k++) {
                Candle d = at(k);
                if (d == null) continue;
                boolean broke = bullish ? d.getClose() > c.getHigh() : d.getClose() < c.getLow();
                if (broke) {
                    return Optional.of(new PdArray("OB", bullish, c.getLow(), c.getHigh(), c.getTimestamp()));
                }
            }
            return Optional.empty();
        }
        return Optional.empty();
    }

    /**
     * Every PD array in the setup direction the alarm may use: unfilled FVGs,
     * inverted opposite FVGs (IFVG), breakers, plus the supplied setup arrays.
     */
    public List<PdArray> candidates(boolean bullish, PdArray... setupArrays) {
        List<PdArray> out = new ArrayList<>();
        for (PdArray p : setupArrays) if (p != null) out.add(p);
        if (bars.size() < 3) return out;
        long first = Math.max(bars.get(0).idx() + 1, lastIdx - SCAN_BARS);
        for (long mm = first; mm < lastIdx; mm++) {
            final long m = mm;
            // Same-direction FVG, still unfilled (bearish: no later high above top).
            gapAt(m, bullish).ifPresent(g -> {
                if (!filledAfter(g, m + 1)) out.add(g);
            });
            // Opposite FVG later closed THROUGH → inverted, acts in our direction.
            gapAt(m, !bullish).ifPresent(g -> {
                if (closedThroughAfter(g, m + 1, bullish)) {
                    out.add(new PdArray("IFVG", bullish, g.bottom(), g.top(), g.at()));
                }
            });
            // Opposite OB (opposite-close candle immediately followed by a close
            // beyond its extreme) later closed through its origin → BREAKER.
            Candle c = at(m);
            Candle n = at(m + 1);
            if (c != null && n != null) {
                boolean oppOb = bullish
                        ? (c.getClose() > c.getOpen() && n.getClose() < c.getLow())   // bearish OB
                        : (c.getClose() < c.getOpen() && n.getClose() > c.getHigh()); // bullish OB
                if (oppOb) {
                    PdArray ob = new PdArray("BREAKER", bullish, c.getLow(), c.getHigh(), c.getTimestamp());
                    if (closedThroughAfter(ob, m + 2, bullish)) out.add(ob);
                }
            }
        }
        return out;
    }

    private boolean filledAfter(PdArray g, long fromIdx) {
        for (long k = fromIdx + 1; k <= lastIdx; k++) {
            Candle c = at(k);
            if (c == null) continue;
            if (g.bullish() ? c.getLow() < g.bottom() : c.getHigh() > g.top()) return true;
        }
        return false;
    }

    /** A later close beyond the zone in {@code dirBullish}'s favour-reversal sense. */
    private boolean closedThroughAfter(PdArray z, long fromIdx, boolean dirBullish) {
        for (long k = fromIdx; k <= lastIdx; k++) {
            Candle c = at(k);
            if (c == null) continue;
            // An array that now supports a LONG was closed through upward
            // (close above its top); one that resists a SHORT, downward.
            if (dirBullish ? c.getClose() > z.top() : c.getClose() < z.bottom()) return true;
        }
        return false;
    }

    // ── band geometry ────────────────────────────────────────────────────

    /** True when the zone OVERLAPS the OTE band (containment not required). */
    public static boolean overlaps(PdArray p, OteZone zone) {
        double lo = Math.min(zone.f62(), zone.f79());
        double hi = Math.max(zone.f62(), zone.f79());
        return p.bottom() <= hi && p.top() >= lo;
    }

    /**
     * Entry level for a PD array, clamped INTO the band: OB → its mean
     * threshold (50 %); gaps / breakers → the edge the retrace touches first
     * (short: bottom, long: top).
     */
    public static double entryLevel(PdArray p, OteZone zone) {
        double lo = Math.min(zone.f62(), zone.f79());
        double hi = Math.max(zone.f62(), zone.f79());
        double raw = "OB".equals(p.kind())
                ? (p.bottom() + p.top()) / 2.0
                : (p.bullish() ? p.top() : p.bottom());
        return Math.max(lo, Math.min(hi, raw));
    }

    /**
     * Best band-overlapping candidate: entry level nearest the 0.705 sweet
     * spot; ties → OB, FVG, IFVG, BREAKER.
     */
    public static Optional<PdArray> bestInBand(List<PdArray> candidates, OteZone zone) {
        PdArray best = null;
        double bestDist = Double.MAX_VALUE;
        for (PdArray p : candidates) {
            if (p == null || p.bullish() != zone.bullish() || !overlaps(p, zone)) continue;
            double d = Math.abs(entryLevel(p, zone) - zone.f705());
            if (best == null || d < bestDist - 1e-9
                    || (Math.abs(d - bestDist) <= 1e-9 && rank(p) < rank(best))) {
                best = p;
                bestDist = d;
            }
        }
        return Optional.ofNullable(best);
    }

    private static int rank(PdArray p) {
        switch (p.kind()) {
            case "OB": return 0;
            case "FVG": return 1;
            case "IFVG": return 2;
            default: return 3;
        }
    }
}
