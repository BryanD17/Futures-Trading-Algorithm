package com.topstep.trading.strategy.stdvote;

/**
 * V5 Agent 03 — pins the PRE-V5 bias / sweep / manipulation-leg / M2b
 * behaviour through the documented rollback switches.
 *
 * <p>The synthetic runner fixtures (legacy golden, scalp frequency, re-arm,
 * determinism, wiring) were built around the 15m-structure bias, the
 * LiquidityDetector-only sweep and the killzone Judas buffer. What they
 * TEST is Agent 02/04/05 territory (target model, re-arm engine, windows,
 * emission) — so they keep running on the exact inputs they were designed
 * for, and the switches themselves are thereby proven to restore the
 * pre-V5 path. The V5 behaviour is proven on the REAL tape
 * ({@code Agent03TapeEvidenceTest}) and by the Agent 03 unit tests.
 */
final class PreV5BiasCompat {

    private static final String[][] KEYS = {
            {"bias.vote.mode", "LEGACY"},
            {"bias.hysteresis", "false"},
            {"pd.gate.mode", "LOG"},
            {"raid.sweepMode", "LEGACY"},
            {"manip.legMode", "KILLZONE"},
            {"raid.weights", "V4"},
    };

    private PreV5BiasCompat() {
    }

    static void apply() {
        for (String[] kv : KEYS) System.setProperty(kv[0], kv[1]);
    }

    static void clear() {
        for (String[] kv : KEYS) System.clearProperty(kv[0]);
    }
}
