package com.topstep.trading.strategy;

import java.time.Instant;

/**
 * Represents an Order Block - a key ICT concept.
 * An Order Block is the last candle of the opposite color before a significant move.
 * - Bullish OB: Last bearish candle before a bullish move (support zone)
 * - Bearish OB: Last bullish candle before a bearish move (resistance zone)
 */
public class OrderBlock {

    private final boolean bullish;       // Direction of expected bounce
    private final double high;           // Top of the OB zone
    private final double low;            // Bottom of the OB zone
    private final Instant timestamp;     // When the OB was formed
    private boolean mitigated;           // Has price returned and filled the OB?
    private boolean breached;            // Has price closed through the OB (invalidated)?

    public OrderBlock(boolean bullish, double high, double low, Instant timestamp) {
        this.bullish = bullish;
        this.high = high;
        this.low = low;
        this.timestamp = timestamp;
        this.mitigated = false;
        this.breached = false;
    }

    public boolean isBullish() {
        return bullish;
    }

    public double getHigh() {
        return high;
    }

    public double getLow() {
        return low;
    }

    public double getMidpoint() {
        return (high + low) / 2.0;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public boolean isMitigated() {
        return mitigated;
    }

    public void setMitigated(boolean mitigated) {
        this.mitigated = mitigated;
    }

    public boolean isBreached() {
        return breached;
    }

    public void setBreached(boolean breached) {
        this.breached = breached;
    }

    /**
     * Check if a price is within the order block zone.
     */
    public boolean containsPrice(double price) {
        return price >= low && price <= high;
    }

    /**
     * Check if the order block is still valid for entry.
     */
    public boolean isValid() {
        return !breached;
    }

    @Override
    public String toString() {
        return String.format("OrderBlock[%s, %.2f-%.2f, mitigated=%s, breached=%s]",
                bullish ? "BULLISH" : "BEARISH", low, high, mitigated, breached);
    }
}
