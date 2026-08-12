package com.topstep.trading.ictlib;

/**
 * The detection families of the ICT library (Appendix S of
 * {@code ICT_STACK_MASTER_PROMPT_V4.txt}).
 *
 * <p>WHY a dedicated enum rather than reusing the strategy package's types:
 * ictlib is the CHART/CONFLUENCE-grade library. It runs in parallel with the
 * gate detectors (which stay tuned to the 2022-model state machine) so the
 * owner can measure divergence before unifying anything. Sharing a type
 * vocabulary with the gates would blur exactly the line this design keeps
 * sharp (V4 anti-pattern C3).
 *
 * <p>Families are added by the agent that implements their spec section, so
 * this enum grows across V4 agents 02–04.
 */
public enum DetectionType {

    /** §S1 — expansion candle whose body dominates its range. Point, no zone. */
    DISPLACEMENT("displacement"),

    /** §S2 — fair value gap (three-candle imbalance behind a displacement). */
    FVG("fvg"),

    /** §S3 — balanced price range: overlap of an active bullish and bearish FVG. */
    BPR("bpr");

    private final String jsonKey;

    DetectionType(String jsonKey) {
        this.jsonKey = jsonKey;
    }

    /** Stable lower-case key used in API payloads and log lines. */
    public String jsonKey() {
        return jsonKey;
    }
}
