package com.topstep.trading.strategy;

import java.time.Instant;

/**
 * Represents a Fair Value Gap (FVG) - an imbalance in price action.
 * An IFVG (Inversion FVG) is an FVG that gets filled and then acts as support/resistance.
 */
public class FairValueGap {
    private final boolean isBullish;      // True if bullish FVG, false if bearish
    private final double top;             // Top of the gap
    private final double bottom;          // Bottom of the gap
    private final Instant timestamp;      // When the FVG was created
    private boolean isFilled;             // Has the gap been filled?
    private boolean isInverted;           // Has it inverted (IFVG)?

    public FairValueGap(boolean isBullish, double top, double bottom, Instant timestamp) {
        this.isBullish = isBullish;
        this.top = top;
        this.bottom = bottom;
        this.timestamp = timestamp;
        this.isFilled = false;
        this.isInverted = false;
    }

    public boolean isBullish() {
        return isBullish;
    }

    public boolean isBearish() {
        return !isBullish;
    }

    public double getTop() {
        return top;
    }

    public double getBottom() {
        return bottom;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public boolean isFilled() {
        return isFilled;
    }

    public void setFilled(boolean filled) {
        isFilled = filled;
    }

    public boolean isInverted() {
        return isInverted;
    }

    public void setInverted(boolean inverted) {
        isInverted = inverted;
    }

    public double getMidpoint() {
        return (top + bottom) / 2.0;
    }

    @Override
    public String toString() {
        String type = isBullish ? "Bullish" : "Bearish";
        String status = isInverted ? " (IFVG)" : isFilled ? " (Filled)" : "";
        return String.format("%s FVG [%.2f - %.2f]%s", type, bottom, top, status);
    }
}
