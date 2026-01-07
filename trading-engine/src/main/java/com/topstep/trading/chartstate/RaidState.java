package com.topstep.trading.chartstate;

/**
 * Lifecycle state of a liquidity raid.
 *
 * Raids progress through states:
 * ACTIVE → CONFIRMED → (EXPIRED or INVALIDATED)
 *
 * Only ACTIVE and CONFIRMED raids should be used for trade entry.
 */
public enum RaidState {
    /**
     * Recently detected, waiting for confirmation.
     * The sweep has occurred but we haven't yet seen displacement/MSS/FVG.
     */
    ACTIVE("Active - Awaiting Confirmation"),

    /**
     * Confirmed by displacement, MSS, and/or FVG formation.
     * This is the highest probability state for trade entry.
     */
    CONFIRMED("Confirmed - Ready for Entry"),

    /**
     * Exceeded maxBarsSinceRaid without a valid trade entry.
     * The setup has gone stale and should not be traded.
     */
    EXPIRED("Expired - Too Old"),

    /**
     * Price made a new extreme, invalidating the raid.
     * The sweep failed to reverse - likely continuation.
     */
    INVALIDATED("Invalidated - New Extreme Made");

    private final String displayName;

    RaidState(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * Check if this state allows trade entry.
     */
    public boolean allowsEntry() {
        return this == ACTIVE || this == CONFIRMED;
    }

    /**
     * Check if this raid is still being tracked.
     */
    public boolean isTracked() {
        return this == ACTIVE || this == CONFIRMED;
    }

    /**
     * Check if this raid is finished (no longer valid).
     */
    public boolean isFinished() {
        return this == EXPIRED || this == INVALIDATED;
    }
}
