package com.topstep.trading.chartstate;

/**
 * Direction of a liquidity raid.
 *
 * HIGH_SWEEP: Buy stops above highs were swept → expect bearish reversal
 * LOW_SWEEP: Sell stops below lows were swept → expect bullish reversal
 */
public enum RaidDirection {
    /**
     * Swept a high level (buy stops taken).
     * Expect bearish reversal after smart money filled longs.
     */
    HIGH_SWEEP("High Sweep", false),

    /**
     * Swept a low level (sell stops taken).
     * Expect bullish reversal after smart money filled shorts.
     */
    LOW_SWEEP("Low Sweep", true);

    private final String displayName;
    private final boolean expectBullish;  // Expected direction after raid

    RaidDirection(String displayName, boolean expectBullish) {
        this.displayName = displayName;
        this.expectBullish = expectBullish;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * Returns true if we expect bullish price action after this raid.
     */
    public boolean expectsBullish() {
        return expectBullish;
    }

    /**
     * Returns true if we expect bearish price action after this raid.
     */
    public boolean expectsBearish() {
        return !expectBullish;
    }

    /**
     * Get the opposite direction.
     */
    public RaidDirection opposite() {
        return this == HIGH_SWEEP ? LOW_SWEEP : HIGH_SWEEP;
    }
}
