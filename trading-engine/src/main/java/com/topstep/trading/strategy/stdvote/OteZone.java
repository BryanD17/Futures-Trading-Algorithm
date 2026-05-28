package com.topstep.trading.strategy.stdvote;

/**
 * Canonical ICT Optimal Trade Entry (OTE) zone built from an LTF impulse
 * leg.
 *
 * <p>Bullish impulse: leg drawn low → high; price retraces DOWN into the
 * {@code [0.62, 0.79]} band; {@code one00} = swing low (the invalidation
 * point and origin of the impulse).
 *
 * <p>Bearish impulse: leg drawn high → low; price retraces UP into the
 * band; {@code one00} = swing high.
 *
 * <p>{@code zoneNear} is the 0.62 edge (closer to the impulse terminus);
 * {@code zoneFar} is the 0.79 edge (deeper into the leg). {@code f705} is
 * the precise 0.705 entry. {@code eq50} is the equilibrium midpoint of
 * the leg.
 *
 * <p>OTE is the entry tool only. Stops sit just beyond {@code one00}
 * plus an instrument-specific buffer; targets come from
 * {@link StdvProjection}, never from this zone.
 */
public record OteZone(
        double legLow,
        double legHigh,
        boolean bullish,
        double eq50,
        double f62,
        double f705,
        double f79,
        double one00) {

    /** True when {@code price} sits between {@code f62} and {@code f79} inclusive. */
    public boolean contains(double price) {
        double lo = Math.min(f62, f79);
        double hi = Math.max(f62, f79);
        return price >= lo && price <= hi;
    }

    /** The 0.62 edge of the zone (band edge closer to the impulse terminus). */
    public double zoneNear() {
        return f62;
    }

    /** The 0.79 edge of the zone (deeper into the leg, closer to invalidation). */
    public double zoneFar() {
        return f79;
    }
}
