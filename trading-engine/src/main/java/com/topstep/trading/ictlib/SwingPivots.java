package com.topstep.trading.ictlib;

import com.topstep.trading.domain.Candle;

import java.time.Instant;

/**
 * Confirmed pivot detection, shared by every family that needs swings
 * (§S6 liquidity pools, §S7 order blocks, §S8 structure).
 *
 * <p>A pivot is only ever reported once it is CONFIRMED — i.e. the bars to its
 * right that the rule requires have already closed. A "pivot" that can still be
 * cancelled by the next tick is a repaint, and repainting is precisely what
 * makes a chart indicator untrustworthy as a trade input.
 *
 * <p>The confirmation lag is the {@code right} parameter: with {@code right=1},
 * the pivot sits one bar behind the newest closed bar.
 */
final class SwingPivots {

    /** A confirmed swing point. */
    record Pivot(long bar, double price, Instant time, boolean high) {}

    private SwingPivots() {}

    /**
     * Confirm a pivot HIGH {@code right} bars behind the newest closed bar.
     *
     * <p>§S6: strictly greater than the prior {@code left} highs, and greater
     * than the {@code right} following highs.
     *
     * @return the pivot, or null when the bar is not one (or history is short)
     */
    static Pivot confirmHigh(TimeframeSeries s, int left, int right) {
        return confirm(s, left, right, true);
    }

    /** Confirm a pivot LOW — the mirror of {@link #confirmHigh}. */
    static Pivot confirmLow(TimeframeSeries s, int left, int right) {
        return confirm(s, left, right, false);
    }

    private static Pivot confirm(TimeframeSeries s, int left, int right, boolean high) {
        Candle p = s.at(right);
        if (p == null) return null;
        if (s.at(right + left) == null) return null;   // ABSTAIN while cold
        double v = high ? p.getHigh() : p.getLow();

        for (int k = 1; k <= left; k++) {
            Candle c = s.at(right + k);
            double o = high ? c.getHigh() : c.getLow();
            if (high ? !(v > o) : !(v < o)) return null;
        }
        for (int k = 1; k <= right; k++) {
            Candle c = s.at(right - k);
            if (c == null) return null;
            double o = high ? c.getHigh() : c.getLow();
            if (high ? !(v > o) : !(v < o)) return null;
        }
        return new Pivot(s.barIndexOf(right), v, p.getTimestamp(), high);
    }

    /**
     * Simple-mean ATR over the last {@code period} closed bars, or NaN while
     * there is not enough history (ABSTAIN — the caller then forms no pool).
     *
     * <p>A simple mean, not Wilder smoothing: §S6 uses ATR only to SIZE a
     * tolerance band, and a plain mean of the same window is fully determined
     * by that window. Wilder's recursive form depends on where the series
     * started, which would make a replay of the same 50 bars produce a
     * different tolerance than the live run did.
     */
    static double atr(TimeframeSeries s, int period) {
        if (period < 1 || s.at(period) == null) return Double.NaN;
        double sum = 0.0;
        for (int i = 0; i < period; i++) {
            Candle c = s.at(i);
            Candle prev = s.at(i + 1);
            double tr = c.getHigh() - c.getLow();
            if (prev != null) {
                tr = Math.max(tr, Math.abs(c.getHigh() - prev.getClose()));
                tr = Math.max(tr, Math.abs(c.getLow() - prev.getClose()));
            }
            sum += tr;
        }
        return sum / period;
    }
}
