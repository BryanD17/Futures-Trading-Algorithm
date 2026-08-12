package com.topstep.trading.ictlib;

/** Directional polarity of a detection. */
public enum DetectionDirection {
    BULLISH,
    BEARISH,
    /** Direction-free detections (e.g. a magnet level with no polarity). */
    NEUTRAL;

    /** {@code true} for {@link #BULLISH}. Convenience for direction filters. */
    public boolean isBullish() {
        return this == BULLISH;
    }

    /** The opposite polarity ({@link #NEUTRAL} maps to itself). */
    public DetectionDirection opposite() {
        return switch (this) {
            case BULLISH -> BEARISH;
            case BEARISH -> BULLISH;
            case NEUTRAL -> NEUTRAL;
        };
    }

    public static DetectionDirection of(boolean bullish) {
        return bullish ? BULLISH : BEARISH;
    }
}
