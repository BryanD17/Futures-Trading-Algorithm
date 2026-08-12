package com.topstep.trading.ictlib;

import com.topstep.trading.domain.Candle;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A bounded rolling window of CLOSED candles for one (symbol, timeframe), plus
 * the monotonic bar index every detection anchors to.
 *
 * <p>Shared by every family detector on that timeframe so the whole library
 * agrees on what "bar i-2" means — the alternative (each detector keeping its
 * own buffer) is how off-by-one bugs get in.
 *
 * <p>Indexing convention matches Appendix S: {@code at(0)} is the most
 * recently CLOSED candle (i), {@code at(1)} is i-1, and so on.
 */
public final class TimeframeSeries {

    /** 300 bars: the deepest §S lookback is the §S6 50-swing scan; 300 covers it. */
    static final int WINDOW = 300;

    private final String timeframe;
    private final Deque<Candle> window = new ArrayDeque<>();
    private long barIndex = -1;

    TimeframeSeries(String timeframe) {
        this.timeframe = timeframe;
    }

    public String timeframe() {
        return timeframe;
    }

    /** Bar index of {@link #at(int) at(0)}; -1 before the first candle. */
    public long barIndex() {
        return barIndex;
    }

    /** Bar index of the candle {@code back} bars before the newest one. */
    public long barIndexOf(int back) {
        return barIndex - back;
    }

    public int size() {
        return window.size();
    }

    void push(Candle c) {
        window.addLast(c);
        barIndex++;
        while (window.size() > WINDOW) window.removeFirst();
    }

    /** The candle {@code back} bars back (0 = newest closed), or null if absent. */
    public Candle at(int back) {
        if (back < 0 || back >= window.size()) return null;
        int idx = window.size() - 1 - back;
        int i = 0;
        for (Candle c : window) {
            if (i++ == idx) return c;
        }
        return null;
    }

    /** Oldest → newest copy of the window (tests and the §S6/§S7 scans). */
    public List<Candle> asList() {
        return new ArrayList<>(window);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Appendix S candle arithmetic — one definition, used by every family
    // ═══════════════════════════════════════════════════════════════════════

    public static double body(Candle c) { return Math.abs(c.getClose() - c.getOpen()); }

    public static double bodyTop(Candle c) { return Math.max(c.getOpen(), c.getClose()); }

    public static double bodyBot(Candle c) { return Math.min(c.getOpen(), c.getClose()); }

    public static double wickTop(Candle c) { return c.getHigh() - bodyTop(c); }

    public static double wickBot(Candle c) { return bodyBot(c) - c.getLow(); }
}
