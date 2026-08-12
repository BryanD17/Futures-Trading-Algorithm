package com.topstep.trading.chart;

/**
 * The retracement band an OTE zone is armed on, carried BY the zone rather than
 * read from a global constant (V4 Agent 05).
 *
 * <p>WHY on the zone: the band is configurable from {@code chart.oteBand}, and
 * a zone that was tagged under one band must keep reporting the levels it was
 * actually tagged on. A band read from engine state at render time would let a
 * config change silently rewrite the fib levels of zones that already exist —
 * the same class of history-rewriting the §S9 post-ARM rule exists to prevent.
 *
 * <p>The engine default is {@code [0.62, 0.79]}. The widely-published variant
 * is {@code [0.618, 0.786]}; the owner may switch with
 * {@code -Dchart.oteBand=0.618,0.786}.
 */
public record OteBand(double start, double sweet, double end) {

    public OteBand {
        if (!(start > 0) || !(end > 0)) {
            throw new IllegalArgumentException("band ratios must be positive");
        }
    }

    /** The engine's historical constants — what every zone gets unless overridden. */
    public static OteBand engineDefault() {
        return new OteBand(ChartEngine.OTE_START, ChartEngine.OTE_SWEET, ChartEngine.OTE_END);
    }

    /**
     * Build from a {@code "start,end"} string; the sweet spot is the midpoint,
     * which reproduces the engine's 0.705 from 0.62/0.79 exactly and yields
     * 0.702 for the 0.618/0.786 variant. Malformed input falls back to the
     * default rather than throwing — a bad flag must not stop the engine.
     */
    public static OteBand parse(String spec) {
        if (spec == null || spec.isBlank()) return engineDefault();
        String[] parts = spec.split(",");
        if (parts.length != 2) return engineDefault();
        try {
            double start = Double.parseDouble(parts[0].trim());
            double end = Double.parseDouble(parts[1].trim());
            if (!(start > 0) || !(end > start)) return engineDefault();
            return new OteBand(start, (start + end) / 2.0, end);
        } catch (NumberFormatException e) {
            return engineDefault();
        }
    }

    /** True when this is the engine's untouched default band. */
    public boolean isEngineDefault() {
        return start == ChartEngine.OTE_START
                && sweet == ChartEngine.OTE_SWEET
                && end == ChartEngine.OTE_END;
    }

    @Override
    public String toString() {
        return start + "/" + sweet + "/" + end;
    }
}
