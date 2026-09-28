package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.domain.Candle;
import com.topstep.trading.strategy.MarketBias;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * V5 Agent 03 (RC-06) — the DAY'S DEALING RANGE and the direction of its
 * impulse leg: the bias the OTE model trades.
 *
 * <p>WHY: the pre-V5 bias was the 15m/30m BOS structure
 * ({@code HtfTrendAnalyzer}). During the very retrace the OTE model sells
 * (G1, 2026-09-28: HH 30759.25 → LL 30356.75, retrace to 30640) that
 * structure reads BULLISH, so the engine hunted longs on the owner's short
 * day. The dealing range reads the IMPULSE, not the retrace.
 *
 * <h2>Rules (pure function of the 1m candle sequence)</h2>
 * <ol>
 *   <li>The range is built per TRADING DAY (CME Globex, rolls at 18:00 ET).
 *       Bars inside the 17:00–18:00 ET halt are settlement prints and are
 *       ignored.</li>
 *   <li>Until the day's range spans {@code bias.range.minRangePct} of price
 *       the direction CARRIES from the previous day (NEUTRAL only on a true
 *       cold start). Once it does, direction = the most recent extreme
 *       (high more recent → BULLISH, low more recent → BEARISH).</li>
 *   <li>BEARISH leg (high → low): a new low EXTENDS the leg. If, before that
 *       new low, price had pulled back at least
 *       {@code bias.range.reanchorFraction} (default 0.5 = into premium) of
 *       the leg, the range RE-ANCHORS: range high = that pullback high (the
 *       ICT "new dealing range after a break of structure"). A wick above
 *       the range high with a close back inside only extends the high (a
 *       sweep); a 1m CLOSE above the range high flips the leg BULLISH
 *       (range = old low → new high). BULLISH mirrors.</li>
 * </ol>
 * G1 on the real tape: BEARISH all of 2026-09-28; from 10:48 ET the range is
 * [30356.75, 30759.25], EQ 30558.00 — the owner's fib anchors to the tick.
 *
 * <h2>Window ({@code bias.range.window}, V5 Agent 05.6)</h2>
 * <ul>
 *   <li>{@link Window#SESSION_DAY} — the range above, built over the whole
 *       Globex trading day from 18:00 ET (Agent 03 behaviour).</li>
 *   <li>{@link Window#RTH_FIRST} — from 09:30 ET until the 17:00 halt the
 *       range is REBUILT from the RTH session only: the same leg rules run on
 *       a second leg seeded at the first bar at/after 09:30; it is decisive
 *       once it spans {@code bias.range.minLegTicks} ticks (the first HH→LL
 *       or LL→HH swing of the minimum size). Before 09:30 (Asia, London,
 *       pre-NY) the session-day range applies unchanged. While the RTH leg is
 *       still below the minimum the RTH extremes are published NON-decisive
 *       (direction carried from the session day).</li>
 *   <li>{@link Window#AUTO} — the RTH leg once it is decisive, else the
 *       session-day range (identical to SESSION_DAY until the RTH impulse
 *       exists).</li>
 * </ul>
 * WHY (golden case G2, 2026-09-25): the session-day range anchored on the
 * OVERNIGHT high 30999.50 (03:35 ET) and the Thu-evening low 30679.00, read
 * the day BULLISH and bought 10:06 ET; the owner anchors on the NY-AM impulse
 * HH 30926.50 (09:55) → LL 30684.00 (10:19): BEARISH. On G1 (09-28) both
 * windows give [30356.75, 30759.25] because both extremes form after 09:30.
 *
 * <p>Not thread-safe; confined to the candle thread like the runner.
 */
public final class DealingRangeTracker {

    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final LocalTime HALT_START = LocalTime.of(17, 0);
    private static final LocalTime DAY_ROLL = LocalTime.of(18, 0);
    private static final LocalTime RTH_OPEN = LocalTime.of(9, 30);

    /** Which part of the trading day the dealing range is taken from
     *  ({@code bias.range.window}). */
    public enum Window { SESSION_DAY, RTH_FIRST, AUTO }

    /** Immutable view published to SetupContext / M2b / the vote. */
    public record Snapshot(MarketBias direction, double high, double low,
                           double equilibrium, boolean decisive) {
        public static final Snapshot EMPTY =
                new Snapshot(MarketBias.NEUTRAL, Double.NaN, Double.NaN, Double.NaN, false);
    }

    private final double minRangePct;
    private final double reanchorFraction;

    private LocalDate tradingDay;
    private double hi = Double.NaN;
    private double lo = Double.NaN;
    private long hiSeq;
    private long loSeq;
    private long seq;
    /** +1 bullish, -1 bearish, 0 undecided today. */
    private int dir;
    /** Direction carried from the previous day while today is undecided. */
    private int carry;
    /** Pullback extreme since the leg terminus (NaN = none yet). */
    private double pullback = Double.NaN;
    /** Previous day's final range — published while today is undecided. */
    private double carryHi = Double.NaN;
    private double carryLo = Double.NaN;

    // ── V5 Agent 05.6: RTH impulse leg (bias.range.window) ──
    private final Window window;
    /** Minimum RTH leg span in PRICE units (minLegTicks x tick). */
    private final double minLegPrice;
    /** The RTH leg of the current trading day (null before 09:30 ET). */
    private Leg rth;

    /** SESSION_DAY tracker (Agent 03 constructor, kept for tests / A/B). */
    public DealingRangeTracker() {
        this(BiasConfig.rangeMinPct(), BiasConfig.rangeReanchorFraction());
    }

    public DealingRangeTracker(double minRangePct, double reanchorFraction) {
        this(minRangePct, reanchorFraction, Window.SESSION_DAY, 0.0);
    }

    /**
     * Full constructor.
     *
     * @param window      where the range is taken from (see class doc)
     * @param minLegPrice minimum RTH leg span in price units (ticks x tick size)
     */
    public DealingRangeTracker(double minRangePct, double reanchorFraction,
                               Window window, double minLegPrice) {
        this.minRangePct = Math.max(0.0, minRangePct);
        this.reanchorFraction = Math.min(1.0, Math.max(0.0, reanchorFraction));
        this.window = window == null ? Window.SESSION_DAY : window;
        this.minLegPrice = Math.max(0.0, minLegPrice);
    }

    /** The runner's tracker: every parameter read from {@link BiasConfig}. */
    public static DealingRangeTracker fromConfig(String symbol, double tickSize) {
        return new DealingRangeTracker(BiasConfig.rangeMinPct(), BiasConfig.rangeReanchorFraction(),
                BiasConfig.rangeWindow(), BiasConfig.rangeMinLegTicks(symbol) * tickSize);
    }

    public Window window() {
        return window;
    }

    /** Warm the tracker from seeded history (e.g. H1 bars) in time order. */
    public void warm(List<Candle> history) {
        if (history == null) return;
        for (Candle c : history) onCandle(c);
    }

    /** True once any bar has been processed. */
    public boolean hasData() {
        return !Double.isNaN(hi);
    }

    public void onCandle(Candle c) {
        if (c == null || c.getTimestamp() == null) return;
        ZonedDateTime et = c.getTimestamp().atZone(ET);
        LocalTime t = et.toLocalTime();
        if (!t.isBefore(HALT_START) && t.isBefore(DAY_ROLL)) {
            return; // settlement print inside the daily halt
        }
        LocalDate day = t.isBefore(DAY_ROLL) ? et.toLocalDate() : et.toLocalDate().plusDays(1);
        double h = c.getHigh();
        double l = c.getLow();
        double close = c.getClose();
        seq++;
        if (tradingDay == null || !day.equals(tradingDay)) {
            tradingDay = day;
            if (dir != 0) {
                carry = dir;
                carryHi = hi;
                carryLo = lo;
            }
            dir = 0;
            hi = h;
            lo = l;
            hiSeq = seq;
            loSeq = seq;
            pullback = Double.NaN;
            rth = null;
            onRthCandle(t, h, l, close);
            return;
        }
        onRthCandle(t, h, l, close);
        if (dir == 0) {
            if (h > hi) { hi = h; hiSeq = seq; }
            if (l < lo) { lo = l; loSeq = seq; }
            if (hi - lo >= minRangePct / 100.0 * close) {
                dir = hiSeq > loSeq ? 1 : -1;
                pullback = Double.NaN;
            }
            return;
        }
        if (dir < 0) {
            if (close > hi) {
                // Break of the bearish range high on a CLOSE → bullish leg
                // from the range low to the new high.
                dir = 1;
                hi = h;
                pullback = Double.NaN;
            } else if (l < lo) {
                if (!Double.isNaN(pullback) && pullback - lo >= reanchorFraction * (hi - lo)) {
                    hi = pullback; // new dealing range after a BOS
                }
                lo = l;
                pullback = Double.NaN;
            } else {
                if (h > hi) hi = h; // sweep of the range high, closed back inside
                pullback = Double.isNaN(pullback) ? h : Math.max(pullback, h);
            }
        } else {
            if (close < lo) {
                dir = -1;
                lo = l;
                pullback = Double.NaN;
            } else if (h > hi) {
                if (!Double.isNaN(pullback) && hi - pullback >= reanchorFraction * (hi - lo)) {
                    lo = pullback;
                }
                hi = h;
                pullback = Double.NaN;
            } else {
                if (l < lo) lo = l;
                pullback = Double.isNaN(pullback) ? l : Math.min(pullback, l);
            }
        }
    }

    /** Feed the RTH leg (from 09:30 ET; the halt is filtered by the caller). */
    private void onRthCandle(LocalTime t, double h, double l, double close) {
        if (window == Window.SESSION_DAY) return;
        if (t.isBefore(RTH_OPEN) || !t.isBefore(HALT_START)) return;
        if (rth == null) {
            rth = new Leg(h, l);
            return;
        }
        rth.onBar(h, l, close, minLegPrice, reanchorFraction);
    }

    /** True when the RTH leg governs the published range right now. */
    public boolean rthGoverns() {
        if (rth == null) return false;
        return window == Window.RTH_FIRST || (window == Window.AUTO && rth.dir != 0);
    }

    /** Effective direction: the governing leg, else the session day's
     *  leg, else the carried direction. */
    public MarketBias direction() {
        if (rthGoverns() && rth.dir != 0) {
            return rth.dir > 0 ? MarketBias.BULLISH : MarketBias.BEARISH;
        }
        return sessionDayDirection();
    }

    private MarketBias sessionDayDirection() {
        int d = dir != 0 ? dir : carry;
        return d > 0 ? MarketBias.BULLISH : d < 0 ? MarketBias.BEARISH : MarketBias.NEUTRAL;
    }

    /** True when today's range itself is decisive (not a carried direction). */
    public boolean decisiveToday() {
        return dir != 0;
    }

    public Snapshot snapshot() {
        if (!hasData()) return Snapshot.EMPTY;
        if (rthGoverns()) {
            // RTH leg decisive: its direction and extremes. RTH_FIRST below
            // the minimum leg: the RTH extremes, NON-decisive, session-day
            // direction.
            MarketBias rd = rth.dir != 0
                    ? (rth.dir > 0 ? MarketBias.BULLISH : MarketBias.BEARISH)
                    : sessionDayDirection();
            return new Snapshot(rd, rth.hi, rth.lo,
                    com.topstep.trading.strategy.HtfTrendAnalyzer.equilibriumOf(rth.hi, rth.lo),
                    rth.dir != 0);
        }
        MarketBias d = sessionDayDirection();
        boolean useCarry = dir == 0 && carry != 0 && !Double.isNaN(carryHi);
        double h = useCarry ? carryHi : hi;
        double l = useCarry ? carryLo : lo;
        // THE single midpoint formula (anti-pattern C7).
        return new Snapshot(d, h, l,
                com.topstep.trading.strategy.HtfTrendAnalyzer.equilibriumOf(h, l),
                d != MarketBias.NEUTRAL);
    }

    public void reset() {
        tradingDay = null;
        hi = lo = Double.NaN;
        hiSeq = loSeq = seq = 0;
        dir = carry = 0;
        pullback = Double.NaN;
        carryHi = carryLo = Double.NaN;
        rth = null;
    }

    /**
     * One impulse leg with the SAME rules as the session-day range (a close
     * beyond the far extreme flips, a new extreme extends, a pullback of
     * {@code reanchorFraction} re-anchors), but decisive on an ABSOLUTE span
     * ({@code bias.range.minLegTicks}) and with no carry.
     */
    private static final class Leg {
        double hi;
        double lo;
        long hiSeq;
        long loSeq;
        long seq;
        int dir;
        double pullback = Double.NaN;

        Leg(double h, double l) {
            hi = h;
            lo = l;
        }

        void onBar(double h, double l, double close, double minLeg, double reanchorFraction) {
            seq++;
            if (dir == 0) {
                if (h > hi) { hi = h; hiSeq = seq; }
                if (l < lo) { lo = l; loSeq = seq; }
                if (hi - lo >= minLeg && hiSeq != loSeq) {
                    dir = hiSeq > loSeq ? 1 : -1;
                    pullback = Double.NaN;
                }
                return;
            }
            if (dir < 0) {
                if (close > hi) {
                    dir = 1;
                    hi = h;
                    pullback = Double.NaN;
                } else if (l < lo) {
                    if (!Double.isNaN(pullback) && pullback - lo >= reanchorFraction * (hi - lo)) {
                        hi = pullback;
                    }
                    lo = l;
                    pullback = Double.NaN;
                } else {
                    if (h > hi) hi = h;
                    pullback = Double.isNaN(pullback) ? h : Math.max(pullback, h);
                }
            } else {
                if (close < lo) {
                    dir = -1;
                    lo = l;
                    pullback = Double.NaN;
                } else if (h > hi) {
                    if (!Double.isNaN(pullback) && hi - pullback >= reanchorFraction * (hi - lo)) {
                        lo = pullback;
                    }
                    hi = h;
                    pullback = Double.NaN;
                } else {
                    if (l < lo) lo = l;
                    pullback = Double.isNaN(pullback) ? l : Math.min(pullback, l);
                }
            }
        }
    }
}
