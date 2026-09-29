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
 * <h2>Carry across the 18:00 ET reopen ({@code bias.range.carryAcrossReopen}, V5 Agent 05.7)</h2>
 * With the flag on (default) the previous session's GOVERNING range (the
 * RTH impulse leg under AUTO / RTH_FIRST when it governed at the close, else
 * the session-day range, else the range that session itself carried) keeps
 * governing after the 18:00 ET roll: same high / low / direction, published
 * decisive. It is EXTENDED when price prints beyond it (a new high in a
 * bearish carried range raises the high; the direction does not flip on
 * it). It hands over only when the NEW session prints an impulse leg of at
 * least {@code bias.range.minLegTicks} (a leg with the same rules as the RTH
 * leg, seeded at the first bar after the roll; an opposite impulse of that
 * size is the only way the carried direction is replaced); from that bar on
 * the range is exactly the flag-off range. An AUTO RTH leg that becomes
 * decisive also governs over the carry (it is itself such an impulse).
 * WHY (LIVE 2026-09-28 18:00 ET): the session-day range restarted at the
 * reopen, the first 78-pt up-move (30537.00 -> 30615.25) read BULLISH and
 * armed a long on a day whose RTH impulse was 30759.25 -> 30356.75 BEARISH;
 * M2b refused it every bar until ~21:00 ET. Flag off = that behaviour (A/B).
 *
 * <p>WHY (golden case G2, 2026-09-25): the session-day range anchored on the
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
    public enum Window { SESSION_DAY, RTH_FIRST, AUTO,
        /** V5 Agent 05.9 - the LTF machine's range: the most recent confirmed
         *  5m fractal swing leg of at least minLegPrice (see {@link SwingRange}). */
        INTRADAY_SWINGS }

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

    // ── V5 Agent 05.7: carry across the 18:00 ET reopen ──
    private final boolean carryAcrossReopen;
    /** Previous session's governing range while it still governs (null = not carrying). */
    private Carried carried;
    /** The new session's impulse leg that ends the carry once decisive. */
    private Leg sessionLeg;

    // -- V5 Agent 05.9: the LTF machine's intraday-swing range --
    /** Non-null only for {@link Window#INTRADAY_SWINGS}; every call delegates to it. */
    private final SwingRange swings;

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
        this(minRangePct, reanchorFraction, window, minLegPrice, false);
    }

    /**
     * Full constructor with the reopen carry (V5 Agent 05.7).
     *
     * @param carryAcrossReopen keep the previous session's governing range
     *                          across the 18:00 ET roll until the new session
     *                          prints an impulse leg of {@code minLegPrice}
     */
    public DealingRangeTracker(double minRangePct, double reanchorFraction,
                               Window window, double minLegPrice, boolean carryAcrossReopen) {
        this.carryAcrossReopen = carryAcrossReopen;
        this.minRangePct = Math.max(0.0, minRangePct);
        this.reanchorFraction = Math.min(1.0, Math.max(0.0, reanchorFraction));
        this.window = window == null ? Window.SESSION_DAY : window;
        this.minLegPrice = Math.max(0.0, minLegPrice);
        this.swings = this.window == Window.INTRADAY_SWINGS
                ? new SwingRange(this.minLegPrice, LtfRangeConfig.SWING_STRENGTH) : null;
    }

    /**
     * V5 Agent 05.9: the LTF machine's tracker - {@link Window#INTRADAY_SWINGS}
     * with {@code range.ltf.minLegTicks x tick} as the minimum leg.
     */
    public static DealingRangeTracker intradaySwings(double minLegPrice) {
        return new DealingRangeTracker(BiasConfig.rangeMinPct(), BiasConfig.rangeReanchorFraction(),
                Window.INTRADAY_SWINGS, minLegPrice, false);
    }

    /** V5 Agent 05.9: the intraday-swing state (null unless INTRADAY_SWINGS). */
    public SwingRange swingRange() {
        return swings;
    }

    /** The runner's tracker: every parameter read from {@link BiasConfig}. */
    public static DealingRangeTracker fromConfig(String symbol, double tickSize) {
        return new DealingRangeTracker(BiasConfig.rangeMinPct(), BiasConfig.rangeReanchorFraction(),
                BiasConfig.rangeWindow(), BiasConfig.rangeMinLegTicks(symbol) * tickSize,
                BiasConfig.rangeCarryAcrossReopen());
    }

    public Window window() {
        return window;
    }

    public boolean carryAcrossReopen() {
        return carryAcrossReopen;
    }

    /** True while the previous session's range still governs (V5 Agent 05.7). */
    public boolean carryingPreviousRange() {
        return carried != null;
    }

    /** Warm the tracker from seeded history (e.g. H1 bars) in time order. */
    public void warm(List<Candle> history) {
        if (history == null) return;
        for (Candle c : history) onCandle(c);
    }

    /** True once any bar has been processed. */
    public boolean hasData() {
        if (swings != null) return swings.hasData();
        return !Double.isNaN(hi);
    }

    public void onCandle(Candle c) {
        if (c == null || c.getTimestamp() == null) return;
        ZonedDateTime et = c.getTimestamp().atZone(ET);
        LocalTime t = et.toLocalTime();
        if (!t.isBefore(HALT_START) && t.isBefore(DAY_ROLL)) {
            return; // settlement print inside the daily halt
        }
        if (swings != null) {
            swings.onCandle(c);
            return;
        }
        LocalDate day = t.isBefore(DAY_ROLL) ? et.toLocalDate() : et.toLocalDate().plusDays(1);
        double h = c.getHigh();
        double l = c.getLow();
        double close = c.getClose();
        seq++;
        if (tradingDay == null || !day.equals(tradingDay)) {
            boolean roll = tradingDay != null;
            Carried next = null;
            if (roll && carryAcrossReopen) {
                // The previous session's GOVERNING range as of its close.
                Snapshot prev = snapshot();
                if (prev.direction() != MarketBias.NEUTRAL
                        && !Double.isNaN(prev.high()) && !Double.isNaN(prev.low())) {
                    next = new Carried(prev.high(), prev.low(),
                            prev.direction() == MarketBias.BULLISH ? 1 : -1);
                    next.extend(h, l);
                }
            }
            carried = next;
            sessionLeg = next == null ? null : new Leg(h, l);
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
        onCarryCandle(h, l, close);
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

    /** Advance the reopen carry: the new session's leg, then hand over or extend. */
    private void onCarryCandle(double h, double l, double close) {
        if (carried == null) return;
        sessionLeg.onBar(h, l, close, minLegPrice, reanchorFraction);
        if (sessionLeg.dir != 0) {
            carried = null; // the new session printed its impulse: it governs
            sessionLeg = null;
            return;
        }
        carried.extend(h, l);
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
        if (swings != null) return swings.snapshot().direction();
        if (rthGoverns() && rth.dir != 0) {
            return rth.dir > 0 ? MarketBias.BULLISH : MarketBias.BEARISH;
        }
        return sessionDayDirection();
    }

    private MarketBias sessionDayDirection() {
        if (carried != null) return carried.bias();
        int d = dir != 0 ? dir : carry;
        return d > 0 ? MarketBias.BULLISH : d < 0 ? MarketBias.BEARISH : MarketBias.NEUTRAL;
    }

    /** True when today's range itself is decisive (not a carried direction). */
    public boolean decisiveToday() {
        if (swings != null) return swings.snapshot().decisive();
        return dir != 0;
    }

    public Snapshot snapshot() {
        if (swings != null) return swings.snapshot();
        if (!hasData()) return Snapshot.EMPTY;
        if (carried != null && !(rthGoverns() && rth.dir != 0)) {
            // V5 Agent 05.7: the previous session's range still governs
            // (RTH_FIRST below the minimum leg keeps publishing the carry).
            return new Snapshot(carried.bias(), carried.hi, carried.lo,
                    com.topstep.trading.strategy.HtfTrendAnalyzer.equilibriumOf(carried.hi, carried.lo),
                    true);
        }
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
        carried = null;
        sessionLeg = null;
        if (swings != null) swings.reset();
    }

    /** The previous session's governing range, carried across the reopen. */
    private static final class Carried {
        double hi;
        double lo;
        final int dir;

        Carried(double hi, double lo, int dir) {
            this.hi = hi;
            this.lo = lo;
            this.dir = dir;
        }

        void extend(double h, double l) {
            if (h > hi) hi = h;
            if (l < lo) lo = l;
        }

        MarketBias bias() {
            return dir > 0 ? MarketBias.BULLISH : MarketBias.BEARISH;
        }
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
    /**
     * V5 Agent 05.9 - the LOWER-TIMEFRAME dealing range: the HTF rules at the
     * intraday-swing scale. Pure function of the 1m candle sequence (the daily
     * 17:00-18:00 ET halt is filtered by the caller).
     * <ol>
     *   <li>1m bars are aggregated into 5m bars (epoch-aligned buckets; a bar is
     *       final when the first 1m bar of the next bucket arrives - the same
     *       moment the runner's detector timeframe completes it).</li>
     *   <li>A 5m bar is a swing high (low) when its high (low) is strictly beyond
     *       the {@code strength} bars on EACH side ({@link FractalSwings}' formula,
     *       strength 2): it is CONFIRMED {@code strength} bars later.</li>
     *   <li>On every confirmed swing the candidate leg is built: a swing HIGH H
     *       ends a bullish leg from the lowest 5m low since the previous confirmed
     *       swing high (the origin L); a swing LOW mirrors it (origin = highest
     *       high since the previous swing low). A candidate of at least
     *       {@code minLegPrice} REBUILDS the range to that leg: range = [L, H],
     *       direction = the leg's (this is also how the direction FLIPS on a
     *       swing - only on an opposite leg of the minimum size). A smaller
     *       candidate changes nothing (a pullback inside the range). A leg whose
     *       later bars already CLOSED back beyond its origin is not taken.</li>
     *   <li>Between swings (every 1m bar, the HTF tracker's rules): a new extreme
     *       beyond the leg terminus EXTENDS it; a wick beyond the origin with the
     *       close back inside extends the origin (a sweep); a 1m CLOSE beyond the
     *       origin FLIPS the direction (range = [new extreme, terminus]) - by
     *       construction an opposite move of at least the range size, itself
     *       at least minLegPrice.</li>
     *   <li>Carry: the swing history is not reset at the 18:00 ET roll - the
     *       LTF range carries across the reopen until a new qualifying leg
     *       replaces it (the HTF carry rule at the smaller scale).</li>
     * </ol>
     */
    public static final class SwingRange {
        private static final long BUCKET_SECONDS = 300L;
        private static final int MAX_BARS = 600;
        /** Bars walked back for a leg origin when no previous swing exists. */
        private static final int ORIGIN_LOOKBACK = 48;

        private final double minLeg;
        private final int strength;
        /** Finalized 5m bars: {bucketStartEpochSec, high, low, close}. */
        private final java.util.ArrayList<double[]> bars = new java.util.ArrayList<>();
        /** Absolute index of bars.get(0). */
        private long base = 0;
        private long curBucket = Long.MIN_VALUE;
        private double curH = Double.NaN;
        private double curL = Double.NaN;
        private double curC = Double.NaN;
        private long lastSwingHighIdx = -1;
        private long lastSwingLowIdx = -1;
        private int dir;
        private double hi = Double.NaN;
        private double lo = Double.NaN;
        private java.time.Instant legLowAt;
        private java.time.Instant legHighAt;
        private boolean data;
        private long rebuilds;
        private long flips;
        private String lastEvent;

        SwingRange(double minLeg, int strength) {
            this.minLeg = Math.max(0.0, minLeg);
            this.strength = Math.max(1, strength);
        }

        public double minLeg() { return minLeg; }
        public boolean hasData() { return data; }
        public long rebuilds() { return rebuilds; }
        public long flips() { return flips; }
        /** 5m bar that set the range low / high (null = extended by a 1m bar). */
        public java.time.Instant legLowAt() { return legLowAt; }
        public java.time.Instant legHighAt() { return legHighAt; }
        /** The last rebuild / flip, human-readable (null before the first). */
        public String lastEvent() { return lastEvent; }

        public Snapshot snapshot() {
            if (dir == 0 || Double.isNaN(hi) || Double.isNaN(lo)) return Snapshot.EMPTY;
            return new Snapshot(dir > 0 ? MarketBias.BULLISH : MarketBias.BEARISH, hi, lo,
                    com.topstep.trading.strategy.HtfTrendAnalyzer.equilibriumOf(hi, lo), true);
        }

        void onCandle(Candle c) {
            data = true;
            long bucket = Math.floorDiv(c.getTimestamp().getEpochSecond(), BUCKET_SECONDS);
            if (curBucket != Long.MIN_VALUE && bucket != curBucket) {
                finalizeBar();
            }
            if (bucket != curBucket) {
                curBucket = bucket;
                curH = c.getHigh();
                curL = c.getLow();
            } else {
                curH = Math.max(curH, c.getHigh());
                curL = Math.min(curL, c.getLow());
            }
            curC = c.getClose();
            onMinute(c.getHigh(), c.getLow(), c.getClose());
        }

        /** The between-swings rules on one 1m bar (HTF tracker semantics). */
        private void onMinute(double h, double l, double close) {
            if (dir == 0) return;
            if (dir > 0) {
                if (close < lo) {
                    dir = -1;
                    lo = l;
                    legLowAt = null;
                    flips++;
                    lastEvent = "FLIP BEARISH on a 1m close " + close + " below the origin -> [" + lo + "," + hi + "]";
                } else if (h > hi) {
                    hi = h;
                    legHighAt = null;
                } else if (l < lo) {
                    lo = l;
                }
            } else {
                if (close > hi) {
                    dir = 1;
                    hi = h;
                    legHighAt = null;
                    flips++;
                    lastEvent = "FLIP BULLISH on a 1m close " + close + " above the origin -> [" + lo + "," + hi + "]";
                } else if (l < lo) {
                    lo = l;
                    legLowAt = null;
                } else if (h > hi) {
                    hi = h;
                }
            }
        }

        private void finalizeBar() {
            bars.add(new double[] {curBucket * BUCKET_SECONDS, curH, curL, curC});
            while (bars.size() > MAX_BARS) {
                bars.remove(0);
                base++;
            }
            long n = base + bars.size() - 1;      // absolute index of the newest bar
            long i = n - strength;                // the bar this one confirms
            if (i - strength < base) return;
            if (isSwing(i, true)) onSwing(i, true, n);
            if (isSwing(i, false)) onSwing(i, false, n);
        }

        private double[] bar(long idx) {
            return bars.get((int) (idx - base));
        }

        private boolean isSwing(long i, boolean high) {
            int f = high ? 1 : 2;
            double v = bar(i)[f];
            for (int j = 1; j <= strength; j++) {
                double a = bar(i - j)[f];
                double b = bar(i + j)[f];
                if (high ? !(v > a && v > b) : !(v < a && v < b)) return false;
            }
            return true;
        }

        /** A confirmed swing at absolute index i (n = newest finalized bar). */
        private void onSwing(long i, boolean high, long n) {
            long prev = high ? lastSwingHighIdx : lastSwingLowIdx;
            if (high) lastSwingHighIdx = i; else lastSwingLowIdx = i;
            long from = prev >= base ? prev + 1 : Math.max(base, i - ORIGIN_LOOKBACK);
            long originIdx = -1;
            double origin = high ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
            for (long k = from; k < i; k++) {
                double v = bar(k)[high ? 2 : 1];
                if (high ? v < origin : v > origin) { origin = v; originIdx = k; }
            }
            if (originIdx < 0) return;
            double terminus = bar(i)[high ? 1 : 2];
            double size = high ? terminus - origin : origin - terminus;
            if (size < minLeg - 1e-9) return;
            // The bars after the swing (confirmation bars + the bar in progress)
            // must not have CLOSED back beyond the origin - that leg already failed.
            double postHi = curH;
            double postLo = curL;
            for (long k = i + 1; k <= n; k++) {
                double[] b = bar(k);
                if (high ? b[3] < origin : b[3] > origin) return;
                postHi = Math.max(postHi, b[1]);
                postLo = Math.min(postLo, b[2]);
            }
            if (high ? curC < origin : curC > origin) return;
            int newDir = high ? 1 : -1;
            boolean flip = dir != 0 && dir != newDir;
            dir = newDir;
            java.time.Instant originAt = java.time.Instant.ofEpochSecond((long) bar(originIdx)[0]);
            java.time.Instant termAt = java.time.Instant.ofEpochSecond((long) bar(i)[0]);
            if (high) {
                lo = Math.min(origin, postLo);
                hi = Math.max(terminus, postHi);
                legLowAt = originAt;
                legHighAt = termAt;
            } else {
                hi = Math.max(origin, postHi);
                lo = Math.min(terminus, postLo);
                legHighAt = originAt;
                legLowAt = termAt;
            }
            rebuilds++;
            if (flip) flips++;
            lastEvent = (flip ? "FLIP " : "REBUILD ") + (high ? "BULLISH" : "BEARISH")
                    + " [" + lo + "," + hi + "] leg "
                    + (high ? "low " + origin + "@" + originAt + " -> high " + terminus + "@" + termAt
                            : "high " + origin + "@" + originAt + " -> low " + terminus + "@" + termAt)
                    + String.format(" (%.2f >= %.2f)", size, minLeg);
        }

        void reset() {
            bars.clear();
            base = 0;
            curBucket = Long.MIN_VALUE;
            curH = curL = curC = Double.NaN;
            lastSwingHighIdx = lastSwingLowIdx = -1;
            dir = 0;
            hi = lo = Double.NaN;
            legLowAt = legHighAt = null;
            data = false;
            rebuilds = flips = 0;
            lastEvent = null;
        }
    }
}
