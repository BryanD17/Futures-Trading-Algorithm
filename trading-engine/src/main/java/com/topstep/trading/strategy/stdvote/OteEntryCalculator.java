package com.topstep.trading.strategy.stdvote;

import java.util.OptionalDouble;

/**
 * Canonical ICT Optimal Trade Entry calculator.
 *
 * <p>Builds an {@link OteZone} from an LTF impulse leg using the canonical
 * Fibonacci levels (0.50 equilibrium, 0.62, 0.705, 0.79, 1.0), and produces
 * an entry price + stop price for a valid setup. The previous statistical
 * empirical-pullback model (see {@code StatisticalRetracementEngine}) is
 * <strong>demoted</strong> by this refactor; it is no longer the entry
 * source.
 *
 * <p>This class is a stub in SA1. The implementation lands in
 * <strong>SA3</strong>.
 */
public final class OteEntryCalculator {

    /** Default precise entry level (Fibonacci 0.705). */
    public static final double PRECISE_ENTRY = 0.705;
    /** Near edge of the OTE band (0.62). */
    public static final double ZONE_NEAR = 0.62;
    /** Far edge of the OTE band (0.79). */
    public static final double ZONE_FAR = 0.79;
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
     * @throws UnsupportedOperationException SA1 stub. Implemented in SA3.
     */
    public OteZone buildZone(double impulseLow,
                             double impulseHigh,
                             boolean bullish,
                             double tickSize) {
        throw new UnsupportedOperationException("OteEntryCalculator.buildZone: SA3");
    }

    /**
     * Select the entry price for a setup. Defaults to {@code zone.f705()};
     * if a PD-array edge inside the band is supplied, snaps to that edge.
     *
     * @throws UnsupportedOperationException SA1 stub. Implemented in SA3.
     */
    public double chooseEntry(OteZone zone,
                              OptionalDouble pdArrayEdgeInsideZone,
                              double tickSize) {
        throw new UnsupportedOperationException("OteEntryCalculator.chooseEntry: SA3");
    }

    /**
     * Stop price = just beyond the OTE 1.0 (the swept extreme) + an
     * instrument-specific buffer in ticks.
     *
     * @throws UnsupportedOperationException SA1 stub. Implemented in SA3.
     */
    public double stopPrice(OteZone zone, double tickSize, int bufferTicks) {
        throw new UnsupportedOperationException("OteEntryCalculator.stopPrice: SA3");
    }
}
