package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.chartstate.LevelType;

/**
 * One STDV (Standard Deviation Projection) level produced from a
 * manipulation leg.
 *
 * <p>Sigma is the canonical ICT negative-fib multiplier of the leg
 * (e.g. {@code -2.0} = the primary reaction zone low). The raw price is
 * derived geometrically from the leg; the snapped price is the same value
 * after optionally aligning to a nearby real liquidity level (PDH/PDL,
 * equal high/low, session high/low, weekly) from
 * {@code ChartStateQueryAPI} / {@code LevelEngine}.
 *
 * <p>{@code isLiquidityBacked} is {@code true} when the level was snapped;
 * {@code snappedLevelType} carries which liquidity type backed the level
 * (or {@code null} when the raw price was kept).
 *
 * <p>{@code realismTag} is set only for {@code -2.0} (the primary target)
 * and reports {@code REALISTIC | AGGRESSIVE | UNREALISTIC} based on
 * {@code ImpulseExtensionAnalyzer}. It informs sizing/partials but never
 * moves the price.
 */
public record StdvProjection(
        double sigma,
        double rawPrice,
        double snappedPrice,
        LevelType snappedLevelType,
        boolean isLiquidityBacked,
        String realismTag) {

    /** Convenience: the price actually used downstream (snapped if backed, raw otherwise). */
    public double effectivePrice() {
        return isLiquidityBacked ? snappedPrice : rawPrice;
    }
}
