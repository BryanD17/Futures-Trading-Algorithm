package com.topstep.trading.strategy.stdvote;

/**
 * V5 AGENT 04 — the ONE place the displacement / FVG / MSS / OTE / RR-band
 * keys are read.
 *
 * <p>A thin typed VIEW over {@link com.topstep.trading.config.EngineConfig}
 * (the BiasConfig pattern): every key is registered in {@code EngineConfig.KEYS}
 * with its historical alias, V5 product defaults live in
 * {@code engine-defaults.properties}, and reads happen at call time through
 * {@code EngineConfig.current()} so the {@code -D} layer keeps precedence.
 *
 * <pre>
 *   key                      legacy key                        default
 *   displacement.atrMult     stdvote.displacement.atrMult      1.2
 *   displacement.bodyPct     stdvote.displacement.bodyPct      0.50  (see note)
 *   displacement.recentBars  stdvote.displacement.recentBars   12    (detector bars)
 *   fvg.linkBars             -                                 3     (detector bars)
 *   ote.anchorMode           -                                 DEALING_RANGE
 *   ote.fib62 / fib705 / fib79                                 0.618 / 0.705 / 0.786
 *   ote.windowBars           stdvOte.oteWindowBars             8     (detector bars, in OTE_ARMED)
 *   ote.stopMode             -                                 BAND  (BAND | ORIGIN)
 *   ote.entryModel           -                                 IMPULSE_LEG (IMPULSE_LEG | POST_SWEEP) (Agent 05.2)
 *   ote.impulseLeg.minSweepFib -                               0.705 (fib the sweep must reach) (Agent 05.2)
 *   mss.freshBars            stdvOte.mssFreshBars              30    (detector bars)
 *   ote30m.mode              ote30m.confluence                 SCORING
 *   risk.rrFloor             -                                 1.0   (legacy profile)
 *   risk.rrFloor.scalp       -                                 0.8   (scalp profile)
 *   risk.rrCeiling           -                                 5.0   (both profiles)
 * </pre>
 *
 * <p>NOTE on {@code displacement.bodyPct}: the owner's live JVM runs 0.55, but
 * the golden G1 displacement (2026-09-28 15:00 ET 5m bar, body 23.5 / range
 * 44.0 = 0.534) must qualify and 0.55 rejects it by 1.6 percentage points.
 * 0.50 is the smallest round value that admits G1; on the 7-day tape it passes
 * 18.2 % of ALL 5m bars (vs 6.9 % at 1.5/0.65) and 85.5 % of real 3-bar
 * impulses (&ge; 1.5 &times; ATR14) — see A-04 "displacement pass-rate".
 *
 * <p>The RR band is the single source of truth for the validator's M7 gate;
 * Agent 05 points {@code PropFirmRiskEngine} at {@link #rrFloor(boolean)} /
 * {@link #rrCeiling()} so the two can never disagree again (PF-07 / RC-13).
 */
public final class OteConfig {

    private OteConfig() {}

    // ── displacement (RC-09) ─────────────────────────────────────────────
    public static final double DEFAULT_DISPLACEMENT_ATR_MULT = 1.2;
    public static final double DEFAULT_DISPLACEMENT_BODY_PCT = 0.50;
    public static final int DEFAULT_DISPLACEMENT_RECENT_BARS = 12;
    /** ATR / average-range lookback (prior bars, current bar excluded). */
    public static final int DISPLACEMENT_ATR_LEN = 14;

    public static double displacementAtrMult() {
        return dbl(DEFAULT_DISPLACEMENT_ATR_MULT,
                "displacement.atrMult", "stdvote.displacement.atrMult");
    }

    public static double displacementBodyPct() {
        return dbl(DEFAULT_DISPLACEMENT_BODY_PCT,
                "displacement.bodyPct", "stdvote.displacement.bodyPct");
    }

    public static int displacementRecentBars() {
        return Math.max(1, integer(DEFAULT_DISPLACEMENT_RECENT_BARS,
                "displacement.recentBars", "stdvote.displacement.recentBars"));
    }

    // ── FVG linkage (RC-10) ──────────────────────────────────────────────
    public static final int DEFAULT_FVG_LINK_BARS = 3;

    public static int fvgLinkBars() {
        return Math.max(0, integer(DEFAULT_FVG_LINK_BARS, "fvg.linkBars"));
    }

    // ── OTE anchors + band (RC-11/12) ───────────────────────────────────
    public static final double FIB_62 = 0.618;
    public static final double FIB_705 = 0.705;
    public static final double FIB_79 = 0.786;
    public static final double FIB_50 = 0.5;
    public static final double FIB_382 = 0.382;

    public static OteAnchorMode anchorMode() {
        return OteAnchorMode.parse(str(null, "ote.anchorMode"));
    }

    public static double fib62() { return dbl(FIB_62, "ote.fib62"); }
    public static double fib705() { return dbl(FIB_705, "ote.fib705"); }
    public static double fib79() { return dbl(FIB_79, "ote.fib79"); }

    /**
     * Stop placement for an anchored OTE: {@code BAND} (default) = beyond the
     * 0.786 edge or the PD array's far edge, whichever is further;
     * {@code ORIGIN} = beyond the anchored leg's 1.0 (the pre-V5 geometry,
     * wider stop / fewer micros).
     */
    public static String stopMode() {
        String m = str("BAND", "ote.stopMode");
        return "ORIGIN".equalsIgnoreCase(m) ? "ORIGIN" : "BAND";
    }

    // ── V5 Agent 05.2: entry model ──────────────────────────────────────
    public static final String ENTRY_MODEL_IMPULSE_LEG = "IMPULSE_LEG";
    public static final String ENTRY_MODEL_POST_SWEEP = "POST_SWEEP";
    public static final double DEFAULT_IMPULSE_MIN_SWEEP_FIB = 0.705;

    /**
     * {@code IMPULSE_LEG} (default): when the recorded sweep sits inside the
     * OTE band of the dealing range, M5/M6 are satisfied by the range's own
     * impulse leg (its displacement + FVG and its structure break) and the
     * setup ARMs on the sweep bar. {@code POST_SWEEP}: the pre-05.2 sequence
     * (a NEW displacement + MSS after the sweep, then a retrace into the band)
     * — kept for A/B and as the fallback when the sweep is not in the band.
     */
    public static String entryModel() {
        String m = str(ENTRY_MODEL_IMPULSE_LEG, "ote.entryModel");
        return ENTRY_MODEL_POST_SWEEP.equalsIgnoreCase(m) ? ENTRY_MODEL_POST_SWEEP : ENTRY_MODEL_IMPULSE_LEG;
    }

    /**
     * IMPULSE_LEG: the fib of the dealing range the sweep's extreme must
     * reach (0.705 = the OTE sweet spot). G1: the 14:45 / 14:49 raids tagged
     * only 30627.75 / 30638.00 (below 0.705 = 30640.50); the 14:53 London-high
     * raid reached 30650.00. 0.618 = any touch of the band.
     */
    public static double impulseMinSweepFib() {
        return dbl(DEFAULT_IMPULSE_MIN_SWEEP_FIB, "ote.impulseLeg.minSweepFib");
    }

    public static final int DEFAULT_OTE_WINDOW_BARS = 8;

    /** OTE_ARMED window in DETECTOR bars (the runner scales to feed bars). */
    public static int oteWindowBars() {
        return Math.max(1, integer(DEFAULT_OTE_WINDOW_BARS, "ote.windowBars", "stdvOte.oteWindowBars"));
    }

    // ── MSS (M6) ─────────────────────────────────────────────────────────
    public static final int DEFAULT_MSS_FRESH_BARS = 30;

    /** MSS freshness in DETECTOR bars (the runner scales to feed bars). */
    public static int mssFreshBars() {
        return Math.max(1, integer(DEFAULT_MSS_FRESH_BARS, "mss.freshBars", "stdvOte.mssFreshBars"));
    }

    // ── M7b (RC-18) ──────────────────────────────────────────────────────
    /** Raw M7b mode string: {@code ote30m.mode} first, then the V3 key. */
    public static String ote30mModeRaw() {
        return str("SCORING", "ote30m.mode", "ote30m.confluence");
    }

    // ── ONE RR band (RC-13, PF-07) ───────────────────────────────────────
    public static final double DEFAULT_RR_FLOOR_LEGACY = 1.0;
    public static final double DEFAULT_RR_FLOOR_SCALP = 0.8;
    public static final double DEFAULT_RR_CEILING = 5.0;

    /** RR floor, evaluated against T1. Legacy 1.0R, scalp 0.8R. */
    public static double rrFloor(boolean scalp) {
        return scalp
                ? dbl(DEFAULT_RR_FLOOR_SCALP, "risk.rrFloor.scalp")
                : dbl(DEFAULT_RR_FLOOR_LEGACY, "risk.rrFloor");
    }

    /** RR ceiling, evaluated against the FINAL target. Both profiles 5.0R. */
    public static double rrCeiling() {
        return dbl(DEFAULT_RR_CEILING, "risk.rrCeiling");
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static com.topstep.trading.config.EngineConfig cfg() {
        return com.topstep.trading.config.EngineConfig.current();
    }

    // Every key is registered in EngineConfig.KEYS with its legacy alias
    // (EngineConfig resolves canonical before alias within each layer); only
    // the FIRST name is looked up, the rest document the alias.
    private static String str(String dflt, String... keys) {
        String v = cfg().getString(keys[0], dflt);
        return v == null || v.isBlank() ? dflt : v.trim();
    }

    private static double dbl(double dflt, String... keys) {
        return cfg().getDouble(keys[0], dflt);
    }

    private static int integer(int dflt, String... keys) {
        return cfg().getInt(keys[0], dflt);
    }

    /** One-line effective-config summary for the boot log. */
    public static String describe() {
        return "[OteConfig] displacement atrMult=" + displacementAtrMult()
                + " bodyPct=" + displacementBodyPct()
                + " recentBars=" + displacementRecentBars()
                + " | fvg.linkBars=" + fvgLinkBars()
                + " | ote.anchorMode=" + anchorMode()
                + " fibs=" + fib62() + "/" + fib705() + "/" + fib79()
                + " windowBars=" + oteWindowBars()
                + " stopMode=" + stopMode()
                + " entryModel=" + entryModel()
                + " impulseLeg.minSweepFib=" + impulseMinSweepFib()
                + " | mss.freshBars=" + mssFreshBars()
                + " | ote30m.mode=" + ote30mModeRaw()
                + " | RR band legacy [" + rrFloor(false) + ", " + rrCeiling() + "]"
                + " scalp [" + rrFloor(true) + ", " + rrCeiling() + "]";
    }
}
