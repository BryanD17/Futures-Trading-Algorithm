package com.topstep.trading.strategy;

import java.time.Instant;

/**
 * Represents a Breaker Block - a failed Order Block that has flipped.
 *
 * Breaker Blocks occur when:
 * 1. An Order Block is established (last candle before displacement)
 * 2. Price returns to the OB zone
 * 3. Instead of holding, price breaks through the OB (fails)
 * 4. The failed OB now acts as the OPPOSITE type of S/R
 *
 * Example:
 * - Bullish OB fails (price breaks below) -> Becomes Bearish Breaker (resistance)
 * - Bearish OB fails (price breaks above) -> Becomes Bullish Breaker (support)
 *
 * Breaker Blocks are considered HIGHER PROBABILITY than regular Order Blocks
 * because they represent trapped traders and institutional repositioning.
 */
public class BreakerBlock {
    private final boolean bullish;        // Direction of the breaker (not the original OB)
    private final double high;
    private final double low;
    private final Instant createdAt;
    private final OrderBlock originalOb;  // The failed order block
    private boolean mitigated;
    private boolean invalidated;

    public BreakerBlock(boolean bullish, double high, double low, Instant createdAt, OrderBlock originalOb) {
        this.bullish = bullish;
        this.high = high;
        this.low = low;
        this.createdAt = createdAt;
        this.originalOb = originalOb;
        this.mitigated = false;
        this.invalidated = false;
    }

    // Getters and setters
    public boolean isBullish() { return bullish; }
    public double getHigh() { return high; }
    public double getLow() { return low; }
    public Instant getCreatedAt() { return createdAt; }
    public OrderBlock getOriginalOb() { return originalOb; }
    public boolean isMitigated() { return mitigated; }
    public boolean isInvalidated() { return invalidated; }

    public void setMitigated(boolean mitigated) { this.mitigated = mitigated; }
    public void setInvalidated(boolean invalidated) { this.invalidated = invalidated; }

    /**
     * Get the midpoint of the breaker block zone.
     */
    public double getMidpoint() {
        return (high + low) / 2.0;
    }

    /**
     * Check if price is within the breaker block zone.
     */
    public boolean containsPrice(double price) {
        return price >= low && price <= high;
    }

    /**
     * Check if the breaker block is still valid (not invalidated).
     */
    public boolean isValid() {
        return !invalidated;
    }

    /**
     * Get the optimal entry price within this breaker block.
     * For bullish breakers, enter near the low (support).
     * For bearish breakers, enter near the high (resistance).
     */
    public double getOptimalEntry() {
        if (bullish) {
            // Enter near the low of the breaker zone (better risk)
            return low + (high - low) * 0.25;
        } else {
            // Enter near the high of the breaker zone
            return high - (high - low) * 0.25;
        }
    }

    @Override
    public String toString() {
        return String.format("BreakerBlock{%s, %.2f-%.2f, mitigated=%s, valid=%s}",
                bullish ? "BULLISH" : "BEARISH", low, high, mitigated, !invalidated);
    }
}
