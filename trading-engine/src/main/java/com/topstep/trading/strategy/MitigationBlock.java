package com.topstep.trading.strategy;

import java.time.Instant;

/**
 * Represents a Mitigation Block - an unmitigated price area where orders remain.
 *
 * Mitigation Blocks are areas where:
 * 1. Price made a strong move (impulse)
 * 2. Left behind an inefficiency (gap, FVG, or OB)
 * 3. Price hasn't yet returned to "mitigate" that area
 *
 * When price returns to these zones, it often reacts because:
 * - Unfilled orders exist at these levels
 * - Institutions use these zones for entries
 * - Smart money concepts suggest price seeks these inefficiencies
 *
 * Entry on mitigation = Lower risk because you're entering where
 * institutions previously showed interest.
 */
public class MitigationBlock {
    private final boolean bullish;        // Direction for entry (not direction of original move)
    private final double high;
    private final double low;
    private final Instant createdAt;
    private final String source;          // "FVG", "OB", "IMPULSE"
    private boolean mitigated;
    private boolean invalidated;
    private int testCount;                // How many times price has tested this zone

    public MitigationBlock(boolean bullish, double high, double low, Instant createdAt, String source) {
        this.bullish = bullish;
        this.high = high;
        this.low = low;
        this.createdAt = createdAt;
        this.source = source;
        this.mitigated = false;
        this.invalidated = false;
        this.testCount = 0;
    }

    // Getters and setters
    public boolean isBullish() { return bullish; }
    public double getHigh() { return high; }
    public double getLow() { return low; }
    public Instant getCreatedAt() { return createdAt; }
    public String getSource() { return source; }
    public boolean isMitigated() { return mitigated; }
    public boolean isInvalidated() { return invalidated; }
    public int getTestCount() { return testCount; }

    public void setMitigated(boolean mitigated) { this.mitigated = mitigated; }
    public void setInvalidated(boolean invalidated) { this.invalidated = invalidated; }
    public void incrementTestCount() { this.testCount++; }

    /**
     * Get the midpoint of the mitigation zone.
     */
    public double getMidpoint() {
        return (high + low) / 2.0;
    }

    /**
     * Check if price is within the mitigation zone.
     */
    public boolean containsPrice(double price) {
        return price >= low && price <= high;
    }

    /**
     * Check if the mitigation block is valid for entry.
     * Valid = not yet mitigated and not invalidated
     */
    public boolean isValidForEntry() {
        return !mitigated && !invalidated;
    }

    /**
     * Check if the zone is "fresh" (never tested).
     * Fresh zones have higher probability.
     */
    public boolean isFresh() {
        return testCount == 0;
    }

    /**
     * Get zone strength based on test count and source.
     * Higher = stronger
     */
    public int getStrength() {
        int strength = 0;

        // Source-based strength
        switch (source) {
            case "BREAKER": strength += 3; break;
            case "FVG": strength += 2; break;
            case "OB": strength += 2; break;
            case "IMPULSE": strength += 1; break;
        }

        // Fresh zones are stronger
        if (isFresh()) {
            strength += 2;
        } else if (testCount == 1) {
            strength += 1;  // Second test is still okay
        }
        // More than 2 tests = weaker zone

        return strength;
    }

    @Override
    public String toString() {
        return String.format("MitigationBlock{%s, %.2f-%.2f, source=%s, tests=%d, valid=%s}",
                bullish ? "BULLISH" : "BEARISH", low, high, source, testCount, isValidForEntry());
    }
}
