package com.topstep.trading.confluence;

/**
 * Three-valued confluence answer (V4 Agent 07).
 *
 * <p>{@link #UNKNOWN} is the whole reason this is not a boolean. A source that
 * has not warmed up yet has NOT said "no" — reporting it as FALSE would poison
 * the score and quietly recreate the starved-input failure class this project
 * has already been bitten by (Appendix E6). UNKNOWN is excluded from BOTH the
 * score and the maximum, so a cold stack reads as visibly cold (2/4) rather
 * than as a bad one (2/16).
 */
public enum Tri {
    TRUE,
    FALSE,
    UNKNOWN;

    /** {@code null} means the source could not answer — that is UNKNOWN. */
    public static Tri of(Boolean value) {
        if (value == null) return UNKNOWN;
        return value ? TRUE : FALSE;
    }

    public boolean isTrue() {
        return this == TRUE;
    }

    public boolean isKnown() {
        return this != UNKNOWN;
    }

    /** Compact glyph for the dashboard and the log line. */
    public String glyph() {
        return switch (this) {
            case TRUE -> "✓";
            case FALSE -> "✗";
            case UNKNOWN -> "—";
        };
    }
}
