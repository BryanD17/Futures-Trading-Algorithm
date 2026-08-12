package com.topstep.trading.chart;

/**
 * How the {@link ChartEngine} chooses the leg it draws the OTE fibs on
 * (V4 Agent 05, §S9).
 *
 * <p>This is a Rollout-Doctrine switch: {@link #FRACTAL_LEG} is the default and
 * is byte-identical to pre-V4 behaviour, {@link #TREND_SHIFT} is owner-flipped
 * from evidence. Set {@code -Dchart.anchorMode=TREND_SHIFT} globally or
 * {@code -Dchart.anchorMode.MNQ=TREND_SHIFT} per instrument.
 */
public enum AnchorMode {

    /**
     * The historical behaviour: the most recent significant fractal leg —
     * whichever of the last confirmed swing high / swing low came later defines
     * the direction, and the fibs stretch between them.
     */
    FRACTAL_LEG,

    /**
     * §S9: fibs anchored the way a human draws them — from the swing low that
     * STARTED the trend leg to the confirmed higher high that SHIFTED
     * structure, with the extreme anchor EXTENDING as the trend prints new
     * confirmed highs.
     */
    TREND_SHIFT;

    /** Parse a property value, falling back to {@link #FRACTAL_LEG}. */
    public static AnchorMode parse(String value) {
        if (value == null) return FRACTAL_LEG;
        return "TREND_SHIFT".equalsIgnoreCase(value.trim()) ? TREND_SHIFT : FRACTAL_LEG;
    }

    /** The other mode — what the {@code chart.anchorCompare} shadow runs. */
    public AnchorMode other() {
        return this == FRACTAL_LEG ? TREND_SHIFT : FRACTAL_LEG;
    }
}
