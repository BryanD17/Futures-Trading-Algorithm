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
 * <p>Not thread-safe; confined to the candle thread like the runner.
 */
public final class DealingRangeTracker {

    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final LocalTime HALT_START = LocalTime.of(17, 0);
    private static final LocalTime DAY_ROLL = LocalTime.of(18, 0);

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

    public DealingRangeTracker() {
        this(BiasConfig.rangeMinPct(), BiasConfig.rangeReanchorFraction());
    }

    public DealingRangeTracker(double minRangePct, double reanchorFraction) {
        this.minRangePct = Math.max(0.0, minRangePct);
        this.reanchorFraction = Math.min(1.0, Math.max(0.0, reanchorFraction));
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
            return;
        }
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

    /** Effective direction: today's leg, else the carried direction. */
    public MarketBias direction() {
        int d = dir != 0 ? dir : carry;
        return d > 0 ? MarketBias.BULLISH : d < 0 ? MarketBias.BEARISH : MarketBias.NEUTRAL;
    }

    /** True when today's range itself is decisive (not a carried direction). */
    public boolean decisiveToday() {
        return dir != 0;
    }

    public Snapshot snapshot() {
        if (!hasData()) return Snapshot.EMPTY;
        MarketBias d = direction();
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
    }
}
