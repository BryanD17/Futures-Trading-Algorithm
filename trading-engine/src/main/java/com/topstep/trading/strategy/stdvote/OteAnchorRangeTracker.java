package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.domain.Candle;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Optional;

/**
 * V5 Agent 04 — standalone dealing-range tracker for the OTE anchor (RC-11).
 *
 * <p>The owner draws the OTE fib on the session's impulse leg: for a SHORT, from
 * the session's highest high (HH) down to the lowest low printed AFTER that high
 * (LL); for a LONG, from the session's lowest low up to the highest high printed
 * after it. "Since the last opposite extreme" falls out of the construction:
 * the leg starts at the extreme the move left from and ends at the furthest
 * point it reached since.
 *
 * <p>Sessions (ET): ASIA 18:00–02:00, LONDON 02:00–09:30, NY 09:30–18:00. When
 * the current session's leg is shorter than the caller's minimum (early in a
 * session), the window widens to the previous session, then to the whole CME
 * trading day (18:00 ET roll). {@link OteAnchorMode#TRADING_DAY} always uses the
 * whole trading day.
 *
 * <p>G1 (2026-09-28 15:05 ET, NY session): HH 30759.25 (09:32) → LL 30356.75
 * (10:48) → 0.618 = 30605.50, 0.786 = 30673.00. Agent 03's
 * {@code SetupContext.rangeHigh/rangeLow} supersede this tracker when present
 * (see {@link #fromContext}).
 *
 * <p>Pure candle-time logic; holds at most two trading days of 1m bars.
 */
public final class OteAnchorRangeTracker {

    /** One anchored leg. {@code high} is the 1.0 for shorts, {@code low} for longs. */
    public record Leg(double low, double high, Instant lowAt, Instant highAt, String source) {
        public double size() { return high - low; }
    }

    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final LocalTime ROLL = LocalTime.of(18, 0);
    private static final LocalTime LONDON_START = LocalTime.of(2, 0);
    private static final LocalTime NY_START = LocalTime.of(9, 30);
    private static final int MAX_BARS = 2 * 24 * 60 + 60;

    private final Deque<Candle> bars = new ArrayDeque<>();

    public void onCandle(Candle c) {
        if (c == null || c.getTimestamp() == null) return;
        Candle last = bars.peekLast();
        if (last != null && !c.getTimestamp().isAfter(last.getTimestamp())) return;
        bars.addLast(c);
        while (bars.size() > MAX_BARS) bars.removeFirst();
    }

    public void reset() {
        bars.clear();
    }

    /**
     * The anchored leg for a setup.
     *
     * @param now          candle time of the decision
     * @param bullish      true for a long (LL → HH), false for a short (HH → LL)
     * @param mode         {@link OteAnchorMode#DEALING_RANGE} (session, widening)
     *                     or {@link OteAnchorMode#TRADING_DAY}
     * @param minLegPoints minimum leg size; shorter legs widen the window
     */
    public Optional<Leg> leg(Instant now, boolean bullish, OteAnchorMode mode, double minLegPoints) {
        if (now == null || bars.isEmpty()) return Optional.empty();
        Instant dayStart = tradingDayStart(now);
        if (mode == OteAnchorMode.TRADING_DAY) {
            return legSince(dayStart, now, bullish, "TRADING_DAY");
        }
        Instant sessStart = sessionStart(now);
        Optional<Leg> leg = legSince(sessStart, now, bullish, "SESSION");
        if (leg.isPresent() && leg.get().size() >= minLegPoints) return leg;
        Instant prevStart = sessionStart(sessStart.minusSeconds(60));
        Optional<Leg> wider = legSince(prevStart, now, bullish, "SESSION+PREV");
        if (wider.isPresent() && wider.get().size() >= minLegPoints) return wider;
        Optional<Leg> day = legSince(dayStart.isBefore(prevStart) ? dayStart : prevStart,
                now, bullish, "TRADING_DAY");
        return day.isPresent() ? day : (wider.isPresent() ? wider : leg);
    }

    private Optional<Leg> legSince(Instant from, Instant now, boolean bullish, String src) {
        double hi = Double.NEGATIVE_INFINITY;
        double lo = Double.POSITIVE_INFINITY;
        Instant hiAt = null;
        Instant loAt = null;
        // Shorts: HH then the lowest low at/after it. Longs: LL then the
        // highest high at/after it. One forward pass each.
        Iterator<Candle> it = bars.iterator();
        while (it.hasNext()) {
            Candle c = it.next();
            Instant t = c.getTimestamp();
            if (t.isBefore(from) || t.isAfter(now)) continue;
            if (!bullish) {
                if (c.getHigh() > hi) {
                    hi = c.getHigh();
                    hiAt = t;
                    lo = c.getLow();
                    loAt = t;
                } else if (c.getLow() < lo) {
                    lo = c.getLow();
                    loAt = t;
                }
            } else {
                if (c.getLow() < lo) {
                    lo = c.getLow();
                    loAt = t;
                    hi = c.getHigh();
                    hiAt = t;
                } else if (c.getHigh() > hi) {
                    hi = c.getHigh();
                    hiAt = t;
                }
            }
        }
        if (hiAt == null || loAt == null || !(hi > lo)) return Optional.empty();
        return Optional.of(new Leg(lo, hi, loAt, hiAt, src));
    }

    /** Start (inclusive) of the ET session containing {@code t}. */
    static Instant sessionStart(Instant t) {
        ZonedDateTime z = t.atZone(ET);
        LocalTime lt = z.toLocalTime();
        LocalDate d = z.toLocalDate();
        if (!lt.isBefore(ROLL)) return d.atTime(ROLL).atZone(ET).toInstant();
        if (lt.isBefore(LONDON_START)) return d.minusDays(1).atTime(ROLL).atZone(ET).toInstant();
        if (lt.isBefore(NY_START)) return d.atTime(LONDON_START).atZone(ET).toInstant();
        return d.atTime(NY_START).atZone(ET).toInstant();
    }

    /** Start of the CME trading day (18:00 ET roll) containing {@code t}. */
    static Instant tradingDayStart(Instant t) {
        ZonedDateTime z = t.atZone(ET);
        LocalDate d = z.toLocalDate();
        if (z.toLocalTime().isBefore(ROLL)) d = d.minusDays(1);
        return d.atTime(ROLL).atZone(ET).toInstant();
    }

    /**
     * Agent 03's published dealing range, read DEFENSIVELY: the fields
     * {@code rangeHigh} / {@code rangeLow} are added to {@link SetupContext} by
     * Agent 03 and do not exist on every branch, so they are looked up by
     * reflection. Absent, non-numeric or non-positive → empty (the caller then
     * uses this tracker).
     */
    public static Optional<Leg> fromContext(SetupContext ctx) {
        // Agent 03 (#156) publishes the dealing range on SetupContext; it is
        // the PREFERRED anchor. NaN / non-positive → this tracker is used.
        if (ctx == null) return Optional.empty();
        double hi = ctx.rangeHigh;
        double lo = ctx.rangeLow;
        if (Double.isNaN(hi) || Double.isNaN(lo) || !(hi > 0) || !(lo > 0) || !(hi > lo)) {
            return Optional.empty();
        }
        return Optional.of(new Leg(lo, hi, null, null, "CONTEXT(Agent03)"));
    }

    /** Reflection core of {@link #fromContext} (package-private for tests). */
    static Optional<Leg> fromObject(Object ctx) {
        if (ctx == null) return Optional.empty();
        Double hi = readDouble(ctx, "rangeHigh");
        Double lo = readDouble(ctx, "rangeLow");
        if (hi == null || lo == null || !(hi > 0) || !(lo > 0) || !(hi > lo)) return Optional.empty();
        return Optional.of(new Leg(lo, hi, null, null, "CONTEXT(Agent03)"));
    }

    private static Double readDouble(Object o, String field) {
        try {
            java.lang.reflect.Field f = o.getClass().getField(field);
            Object v = f.get(o);
            if (v instanceof Number n) {
                double d = n.doubleValue();
                return Double.isNaN(d) ? null : d;
            }
            return null;
        } catch (NoSuchFieldException | IllegalAccessException e) {
            return null;
        }
    }
}
