package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.strategy.FairValueGap;

import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Canonical ICT Optimal Trade Entry calculator.
 *
 * <p>Builds an {@link OteZone} from an anchored leg using the canonical
 * Fibonacci levels (0.50 equilibrium, 0.618, 0.705, 0.786, 1.0 — V5 Agent 04:
 * the chart-parity constants, read through {@link OteConfig}), and produces
 * an entry price + stop price for a valid setup. The previous statistical
 * empirical-pullback model (see {@code StatisticalRetracementEngine}) is
 * <strong>demoted</strong> by this refactor; it is no longer the entry
 * source.
 *
 * <p>The calculator is pure — given the same inputs it returns the same
 * outputs and has no mutable state.
 *
 * <p>SA3 implementation.
 */
public final class OteEntryCalculator {

    /** Default precise entry level (Fibonacci 0.705). */
    public static final double PRECISE_ENTRY = OteConfig.FIB_705;
    /** Near edge of the OTE band (0.618 — closer to impulse terminus). */
    public static final double ZONE_NEAR = OteConfig.FIB_62;
    /** Far edge of the OTE band (0.786 — deeper retracement). */
    public static final double ZONE_FAR = OteConfig.FIB_79;
    /** Equilibrium (50% of leg). */
    public static final double EQUILIBRIUM = 0.50;
    /** Invalidation level (origin of the impulse). */
    public static final double INVALIDATION = 1.00;

    /**
     * Build the canonical OTE zone from an LTF impulse leg.
     *
     * @param impulseLow  the lower extreme of the impulse leg
     * @param impulseHigh the upper extreme of the impulse leg
     * @param bullish     true if the impulse moved up (long setup)
     * @param tickSize    instrument tick size for rounding
     * @return the canonical OTE zone, or empty when the leg is degenerate
     *         (low &gt;= high) or so short that 0.62 / 0.705 / 0.79 collapse
     *         to the same tick.
     */
    public Optional<OteZone> buildZone(double impulseLow,
                                       double impulseHigh,
                                       boolean bullish,
                                       double tickSize) {
        if (!(impulseHigh > impulseLow)) {
            return Optional.empty();
        }
        double range = impulseHigh - impulseLow;
        double r62 = OteConfig.fib62();
        double r705 = OteConfig.fib705();
        double r79 = OteConfig.fib79();

        double eq50;
        double f62;
        double f705;
        double f79;
        double one00;
        if (bullish) {
            eq50  = impulseHigh - 0.50 * range;
            f62   = impulseHigh - r62  * range;
            f705  = impulseHigh - r705 * range;
            f79   = impulseHigh - r79  * range;
            one00 = impulseLow;
        } else {
            eq50  = impulseLow + 0.50 * range;
            f62   = impulseLow + r62  * range;
            f705  = impulseLow + r705 * range;
            f79   = impulseLow + r79  * range;
            one00 = impulseHigh;
        }

        eq50  = roundToTick(eq50,  tickSize);
        f62   = roundToTick(f62,   tickSize);
        f705  = roundToTick(f705,  tickSize);
        f79   = roundToTick(f79,   tickSize);
        one00 = roundToTick(one00, tickSize);

        // Reject too-tight leg: 0.62 / 0.705 / 0.79 must be tick-distinct.
        if (Double.compare(f62, f705) == 0 || Double.compare(f705, f79) == 0) {
            return Optional.empty();
        }

        return Optional.of(new OteZone(
                roundToTick(impulseLow, tickSize),
                roundToTick(impulseHigh, tickSize),
                bullish,
                eq50, f62, f705, f79, one00));
    }

    /**
     * Select the entry price for a setup. Defaults to {@code zone.f705()};
     * if a PD-array edge inside the zone is supplied, snaps to that edge.
     * An edge outside the OTE band is ignored.
     */
    public double chooseEntry(OteZone zone,
                              OptionalDouble pdArrayEdgeInsideZone,
                              double tickSize) {
        if (pdArrayEdgeInsideZone.isPresent()) {
            double edge = pdArrayEdgeInsideZone.getAsDouble();
            if (zone.contains(edge)) {
                return roundToTick(edge, tickSize);
            }
        }
        return roundToTick(zone.f705(), tickSize);
    }

    /**
     * Stop price = just beyond the OTE 1.0 (the swept extreme) + an
     * instrument-specific buffer in ticks. For a bullish setup the stop sits
     * below the swept low; for a bearish setup, above the swept high.
     */
    public double stopPrice(OteZone zone, double tickSize, int bufferTicks) {
        double buffer = Math.max(0, bufferTicks) * tickSize;
        double raw = zone.bullish() ? (zone.one00() - buffer) : (zone.one00() + buffer);
        return roundToTick(raw, tickSize);
    }

    /**
     * Reward-to-risk at a given target price. Uses absolute distances so a
     * caller using either bullish or bearish geometry gets a positive R-value.
     * Returns 0 when the stop and entry are identical (degenerate).
     */
    public double rewardToRisk(double entry, double stop, double targetPrice) {
        double risk = Math.abs(entry - stop);
        if (risk <= 0) return 0.0;
        double reward = Math.abs(targetPrice - entry);
        return reward / risk;
    }

    /**
     * Find the best PD-array edge inside the OTE zone for a {@link FairValueGap}.
     *
     * <p>For a bullish setup, the FVG's TOP is the first price hit as price
     * retraces down. If the top is inside the OTE band, prefer it; otherwise
     * fall back to the bottom if the bottom is inside. For a bearish setup,
     * mirror: prefer the FVG's BOTTOM, fall back to the top.
     *
     * <p>V5 Agent 04 (D-19 / RC-11): a gap that merely OVERLAPS the band
     * qualifies — when neither edge is inside, the entry is the band edge the
     * retrace reaches first inside the gap (clamped). Returns empty only when
     * the gap does not overlap the band at all.
     */
    public OptionalDouble bestFvgEdgeInZone(OteZone zone, FairValueGap fvg) {
        if (zone == null || fvg == null) return OptionalDouble.empty();
        double primary;
        double fallback;
        if (zone.bullish()) {
            primary  = fvg.getTop();
            fallback = fvg.getBottom();
        } else {
            primary  = fvg.getBottom();
            fallback = fvg.getTop();
        }
        if (zone.contains(primary))  return OptionalDouble.of(primary);
        if (zone.contains(fallback)) return OptionalDouble.of(fallback);
        double lo = Math.min(zone.f62(), zone.f79());
        double hi = Math.max(zone.f62(), zone.f79());
        double gLo = Math.min(fvg.getBottom(), fvg.getTop());
        double gHi = Math.max(fvg.getBottom(), fvg.getTop());
        if (gLo <= hi && gHi >= lo) {
            // Band sits entirely inside the gap: the retrace meets the near
            // edge (0.618) first.
            return OptionalDouble.of(zone.f62());
        }
        return OptionalDouble.empty();
    }

    /**
     * Price at retracement ratio {@code r} of the zone's leg, measured from the
     * leg terminus (0.0) back toward the origin (1.0): bearish
     * {@code legLow + r·range}, bullish {@code legHigh − r·range}; tick-rounded.
     */
    public double fibLevel(OteZone zone, double r, double tickSize) {
        double range = zone.legHigh() - zone.legLow();
        double raw = zone.bullish() ? zone.legHigh() - r * range : zone.legLow() + r * range;
        return roundToTick(raw, tickSize);
    }

    /**
     * Owner's target ladder for an anchored leg (V5 Agent 04): T1 = 0.5
     * (equilibrium), T2 = 0.382, T3 = the leg terminus (0.0 — the range
     * low for shorts / high for longs).
     */
    public double[] targetLadder(OteZone zone, double tickSize) {
        return new double[] {
                fibLevel(zone, OteConfig.FIB_50, tickSize),
                fibLevel(zone, OteConfig.FIB_382, tickSize),
                fibLevel(zone, 0.0, tickSize)
        };
    }

    private static double roundToTick(double price, double tickSize) {
        if (tickSize <= 0) return price;
        return Math.round(price / tickSize) * tickSize;
    }
}
