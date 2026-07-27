package com.topstep.trading.chartstate;

/**
 * Types of known liquidity levels that smart money targets.
 *
 * These levels represent areas where stop losses cluster and where
 * institutional traders hunt for liquidity before major moves.
 */
public enum LevelType {
    // ═══════════════════════════════════════════════════════════════════
    // DAILY LEVELS (highest significance - reset at session rollover)
    // ═══════════════════════════════════════════════════════════════════
    PDH("Previous Day High", true, 10),
    PDL("Previous Day Low", false, 10),
    PWH("Previous Week High", true, 9),
    PWL("Previous Week Low", false, 9),
    PMH("Previous Month High", true, 8),
    PML("Previous Month Low", false, 8),

    // ═══════════════════════════════════════════════════════════════════
    // SESSION LEVELS (computed during/after each session)
    // ═══════════════════════════════════════════════════════════════════
    ASIA_HIGH("Asia Session High", true, 7),
    ASIA_LOW("Asia Session Low", false, 7),
    LONDON_HIGH("London Session High", true, 7),
    LONDON_LOW("London Session Low", false, 7),
    NY_AM_HIGH("NY AM Session High", true, 7),
    NY_AM_LOW("NY AM Session Low", false, 7),
    NY_PM_HIGH("NY PM Session High", true, 6),
    NY_PM_LOW("NY PM Session Low", false, 6),

    // ═══════════════════════════════════════════════════════════════════
    // DYNAMIC LEVELS (current session)
    // ═══════════════════════════════════════════════════════════════════
    SESSION_HIGH("Current Session High", true, 5),
    SESSION_LOW("Current Session Low", false, 5),

    // ═══════════════════════════════════════════════════════════════════
    // EQUAL HIGHS/LOWS (clustered swing points - liquidity pools)
    // ═══════════════════════════════════════════════════════════════════
    EQUAL_HIGH("Equal Highs", true, 6),
    EQUAL_LOW("Equal Lows", false, 6),

    // ═══════════════════════════════════════════════════════════════════
    // NY FULL SESSION LEVELS (computed at end of full NY session)
    // ═══════════════════════════════════════════════════════════════════
    NY_HIGH("NY Session High", true, 7),
    NY_LOW("NY Session Low", false, 7),

    // ═══════════════════════════════════════════════════════════════════
    // OPENING PRICES (for gap analysis)
    // ═══════════════════════════════════════════════════════════════════
    DAILY_OPEN("Daily Open Price", false, 4),    // Neutral - not a high or low
    // ICT "true day open": the first traded price at/after midnight ET.
    // Distinct from DAILY_OPEN (the 18:00 ET session open of the PREVIOUS
    // day) — added for the §H1 V3 bias vote (V3 Agent 03).
    MIDNIGHT_OPEN("True Day Open (midnight ET)", false, 4),
    WEEKLY_OPEN("Weekly Open Price", false, 4),  // Neutral - not a high or low
    NY_OPEN("NY Open Price", false, 4),          // Neutral - can be swept either way
    LONDON_OPEN("London Open Price", false, 4),
    ASIA_OPEN("Asia Open Price", false, 3);

    private final String displayName;
    private final boolean isHighLevel;  // true = high, false = low
    private final int significance;      // 1-10, higher = more important

    LevelType(String displayName, boolean isHighLevel, int significance) {
        this.displayName = displayName;
        this.isHighLevel = isHighLevel;
        this.significance = significance;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isHighLevel() {
        return isHighLevel;
    }

    public boolean isLowLevel() {
        return !isHighLevel;
    }

    /**
     * Alias for isHighLevel() for compatibility.
     */
    public boolean isHigh() {
        return isHighLevel;
    }

    /**
     * Alias for isLowLevel() for compatibility.
     */
    public boolean isLow() {
        return !isHighLevel;
    }

    public int getSignificance() {
        return significance;
    }

    /**
     * Check if this is a session extreme (Asia/London/NY high or low).
     */
    public boolean isSessionExtreme() {
        return this == ASIA_HIGH || this == ASIA_LOW ||
               this == LONDON_HIGH || this == LONDON_LOW ||
               this == NY_AM_HIGH || this == NY_AM_LOW ||
               this == NY_PM_HIGH || this == NY_PM_LOW;
    }

    /**
     * Check if this is a daily level (PDH/PDL/PWH/PWL/PMH/PML).
     */
    public boolean isDailyLevel() {
        return this == PDH || this == PDL ||
               this == PWH || this == PWL ||
               this == PMH || this == PML;
    }

    /**
     * Check if this is an equal highs/lows level.
     */
    public boolean isEqualLevel() {
        return this == EQUAL_HIGH || this == EQUAL_LOW;
    }
}
