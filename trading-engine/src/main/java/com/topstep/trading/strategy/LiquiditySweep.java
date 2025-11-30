package com.topstep.trading.strategy;

import java.time.Instant;

/**
 * Represents a liquidity sweep event where price runs a high/low and then rejects.
 */
public class LiquiditySweep {
    private final boolean isBullish;        // True if sweeping lows (bullish), false if sweeping highs (bearish)
    private final double sweptLevel;        // The price level that was swept
    private final Instant timestamp;        // When the sweep occurred
    private final boolean hasSmtDivergence; // True if SMT divergence confirmed this sweep

    public LiquiditySweep(boolean isBullish, double sweptLevel, Instant timestamp, boolean hasSmtDivergence) {
        this.isBullish = isBullish;
        this.sweptLevel = sweptLevel;
        this.timestamp = timestamp;
        this.hasSmtDivergence = hasSmtDivergence;
    }

    public boolean isBullish() {
        return isBullish;
    }

    public boolean isBearish() {
        return !isBullish;
    }

    public double getSweptLevel() {
        return sweptLevel;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public boolean hasSmtDivergence() {
        return hasSmtDivergence;
    }

    @Override
    public String toString() {
        String type = isBullish ? "Bullish" : "Bearish";
        String smt = hasSmtDivergence ? " with SMT" : "";
        return String.format("%s Liquidity Sweep @ %.2f%s", type, sweptLevel, smt);
    }
}
