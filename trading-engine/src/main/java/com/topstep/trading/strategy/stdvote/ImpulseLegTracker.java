package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.domain.Candle;

/**
 * Tracks the post-MSS impulse leg for the STDV+OTE state machine.
 *
 * <p>The OTE zone must be built on the <em>impulse the MSS produced</em>, not
 * on whatever swing pair the LTF structure detector happens to hold (which may
 * pre-date the MSS entirely). This tracker is armed exactly once per setup,
 * when the MSS is consumed:
 *
 * <ul>
 *   <li><b>Bullish:</b> {@code origin} = lowest low since the sweep (the true
 *       start of the reversal leg), {@code terminus} = the MSS candle high.
 *       Each subsequent candle may only <em>extend the terminus upward</em>;
 *       a trade below the origin marks the leg {@link #isViolated() violated}
 *       (the impulse origin — the OTE 1.0 invalidation — was taken out before
 *       entry).</li>
 *   <li><b>Bearish:</b> mirrored.</li>
 * </ul>
 *
 * <p>The tracker also answers the {@code reactionConfirmed} question for
 * {@code recordOteImpulse}: {@link #isRejectionReaction} is an observable
 * price-action test (candle traded into the prospective 0.62–0.79 band,
 * closed back in the impulse direction, and left a rejection wick of at
 * least {@code minWickTicks}). The runner must pass this real boolean to the
 * core — never a literal.
 *
 * <p>Pure and allocation-free per bar; no detector dependencies. Thread
 * confinement follows the runner (single engine loop).
 */
public final class ImpulseLegTracker {

    /** Canonical OTE band edges (must match {@link OteEntryCalculator}). */
    private static final double ZONE_NEAR = 0.62;
    private static final double ZONE_FAR = 0.79;

    private boolean armed;
    private boolean bullish;
    /** Fixed start of the impulse (bullish: low; bearish: high). */
    private double origin;
    /** Extending end of the impulse (bullish: high; bearish: low). */
    private double terminus;
    private boolean violated;

    /**
     * Arm the tracker on MSS confirmation.
     *
     * @param bullish  impulse direction (matches HTF bias)
     * @param origin   fixed leg start — bullish: lowest low since the sweep;
     *                 bearish: highest high since the sweep
     * @param terminus initial leg end — bullish: MSS candle high; bearish:
     *                 MSS candle low
     */
    public void arm(boolean bullish, double origin, double terminus) {
        this.armed = true;
        this.bullish = bullish;
        this.origin = origin;
        this.terminus = terminus;
        this.violated = false;
    }

    /**
     * Update the leg with a new candle's extremes. Extends the terminus in
     * the impulse direction; flags violation when price takes out the origin.
     * No-op when not armed.
     */
    public void onCandle(double high, double low) {
        if (!armed) return;
        if (bullish) {
            if (high > terminus) terminus = high;
            if (low < origin) violated = true;
        } else {
            if (low < terminus) terminus = low;
            if (high > origin) violated = true;
        }
    }

    public boolean isArmed() {
        return armed;
    }

    /** True once price traded beyond the impulse origin (leg no longer valid). */
    public boolean isViolated() {
        return violated;
    }

    /** True when armed, not violated, and the leg has positive extent. */
    public boolean hasValidLeg() {
        return armed && !violated && impulseHigh() > impulseLow();
    }

    /** Lower extreme of the leg (0 when not armed). */
    public double impulseLow() {
        if (!armed) return 0.0;
        return bullish ? origin : terminus;
    }

    /** Upper extreme of the leg (0 when not armed). */
    public double impulseHigh() {
        if (!armed) return 0.0;
        return bullish ? terminus : origin;
    }

    public boolean isBullish() {
        return bullish;
    }

    /**
     * Prospective OTE retracement band {@code {low, high}} for the current
     * leg (raw, un-rounded — the core rounds when it builds the real
     * {@link OteZone}). Returns {@code null} when there is no valid leg.
     */
    public double[] oteBand() {
        if (!hasValidLeg()) return null;
        double lo = impulseLow();
        double hi = impulseHigh();
        double range = hi - lo;
        double f62;
        double f79;
        if (bullish) {
            f62 = hi - ZONE_NEAR * range;
            f79 = hi - ZONE_FAR * range;
            return new double[] { f79, f62 };
        } else {
            f62 = lo + ZONE_NEAR * range;
            f79 = lo + ZONE_FAR * range;
            return new double[] { f62, f79 };
        }
    }

    /**
     * Observable rejection reaction at the OTE zone.
     *
     * <p>Bullish: the candle traded down into the band (low at or below the
     * 0.62 edge), closed back up (close &gt; open), and left a lower
     * rejection wick of at least {@code minWickTicks}. Bearish mirrored.
     *
     * @param candle       the just-closed LTF candle
     * @param tickSize     instrument tick size
     * @param minWickTicks minimum rejection-wick length in ticks
     * @return true when all three conditions hold; false otherwise (including
     *         when the leg is invalid or violated)
     */
    public boolean isRejectionReaction(Candle candle, double tickSize, int minWickTicks) {
        if (candle == null) return false;
        double[] band = oteBand();
        if (band == null) return false;
        double minWick = Math.max(0, minWickTicks) * tickSize;
        if (bullish) {
            boolean touched = candle.getLow() <= band[1];
            boolean directionalClose = candle.getClose() > candle.getOpen();
            double lowerWick = Math.min(candle.getOpen(), candle.getClose()) - candle.getLow();
            return touched && directionalClose && lowerWick >= minWick;
        } else {
            boolean touched = candle.getHigh() >= band[0];
            boolean directionalClose = candle.getClose() < candle.getOpen();
            double upperWick = candle.getHigh() - Math.max(candle.getOpen(), candle.getClose());
            return touched && directionalClose && upperWick >= minWick;
        }
    }

    /** Disarm and clear all state (new setup / session boundary). */
    public void reset() {
        armed = false;
        bullish = false;
        origin = 0.0;
        terminus = 0.0;
        violated = false;
    }
}
