package com.topstep.trading.strategy;

/**
 * Represents the phase within a killzone for time-based entry refinement.
 *
 * Each killzone has three phases:
 * 1. OPENING (First 30 min): Wait for liquidity sweep, don't enter yet
 * 2. PRIME (Middle): Optimal entry window after sweep
 * 3. CLOSING (Last 30 min): No new entries, manage existing positions
 *
 * This follows ICT principles of:
 * - Waiting for manipulation (liquidity sweep) in opening phase
 * - Entering during the "meat" of the killzone (prime phase)
 * - Avoiding late entries that may not reach target before session change
 */
public enum KillzonePhase {
    /**
     * Opening phase: First 30 minutes of killzone.
     * - Wait for liquidity sweep to occur
     * - Observe market direction
     * - Do NOT enter trades yet
     * - Watch for Power of 3 manipulation
     */
    OPENING("Opening Phase", false, true),

    /**
     * Prime phase: Middle portion of killzone.
     * - Best time to enter trades
     * - Liquidity sweep should have occurred
     * - Maximum time for trade to work
     * - Ideal for Tier 3 (highest quality) entries
     */
    PRIME("Prime Phase", true, false),

    /**
     * Closing phase: Last 30 minutes of killzone.
     * - No new entries
     * - Manage existing positions
     * - Consider taking partial profits
     * - Trail stops on winners
     */
    CLOSING("Closing Phase", false, false),

    /**
     * Outside killzone - no entries allowed.
     */
    OUTSIDE("Outside Killzone", false, false);

    private final String description;
    private final boolean allowNewEntries;
    private final boolean waitForSweep;

    KillzonePhase(String description, boolean allowNewEntries, boolean waitForSweep) {
        this.description = description;
        this.allowNewEntries = allowNewEntries;
        this.waitForSweep = waitForSweep;
    }

    public String getDescription() {
        return description;
    }

    public boolean allowsNewEntries() {
        return allowNewEntries;
    }

    public boolean shouldWaitForSweep() {
        return waitForSweep;
    }

    /**
     * Check if this phase is good for trading.
     */
    public boolean isGoodForTrading() {
        return allowNewEntries;
    }

    /**
     * Get R:R adjustment based on phase.
     * Late entries get lower R:R targets.
     */
    public double getRRMultiplier() {
        switch (this) {
            case PRIME: return 1.0;      // Full R:R targets
            case OPENING: return 0.0;    // No entries
            case CLOSING: return 0.75;   // Reduced targets (if we allow late entries)
            default: return 0.0;
        }
    }

    @Override
    public String toString() {
        return String.format("%s (entries: %s)", description, allowNewEntries ? "YES" : "NO");
    }
}
