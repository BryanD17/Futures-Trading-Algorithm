package com.topstep.trading.risk;

/**
 * AGENT-05 (V5) — the post-signal pipeline's configuration keys, read in ONE
 * place through {@link com.topstep.trading.config.EngineConfig#current()} at
 * call time (-D keeps the highest precedence, so tests can flip a key without
 * a restart), falling back to the documented default.
 *
 * <pre>
 *   warmup.timeoutSeconds    120   warmup completes after this many seconds even
 *                                  if a REQUIRED feed never delivered a live candle
 *   size.minMicros           1     risk-derived size below this is DENIED ("SIZE: …")
 *   size.preferredMicros     5     fallback size when the risk-derived size cannot
 *                                  be computed (no geometry); NEVER a floor
 *   size.maxMicros           20    hard ceiling (the instrument band top)
 *   risk.rrFloor             1.0 legacy / 0.8 scalp   single RR floor (validator M7
 *                                  AND PropFirmRiskEngine)
 *   risk.rrFloor.scalp       0.8   scalp-profile floor when risk.rrFloor is unset
 *   risk.rrCeiling           5.0   single RR ceiling (validator, final target)
 *   risk.haltOnProfitTarget  LIVE true / SIM false
 *   news.blockWithoutCalendar false  news gate blocks only with a REAL calendar
 *   order.ttlBars            OTE window x 2 (in 1m feed bars; default 80)
 *   flatten.safetyNetCt      14:45 CT — execution-path flatten/no-entry safety net
 * </pre>
 */
// V5: a thin typed VIEW over EngineConfig.current() (BiasConfig pattern); every
// key is registered in EngineConfig.KEYS and printed in the boot table.
public final class RiskConfig {

    public static final String WARMUP_TIMEOUT_SECONDS = "warmup.timeoutSeconds";
    public static final String SIZE_MIN_MICROS = "size.minMicros";
    public static final String SIZE_PREFERRED_MICROS = "size.preferredMicros";
    public static final String SIZE_MAX_MICROS = "size.maxMicros";
    public static final String RR_FLOOR = "risk.rrFloor";
    public static final String RR_FLOOR_SCALP = "risk.rrFloor.scalp";
    public static final String RR_CEILING = "risk.rrCeiling";
    public static final String HALT_ON_PROFIT_TARGET = "risk.haltOnProfitTarget";
    public static final String NEWS_BLOCK_WITHOUT_CALENDAR = "news.blockWithoutCalendar";
    public static final String ORDER_TTL_BARS = "order.ttlBars";

    /** Absolute band the instrument registry accepts for [minMicros, maxMicros]. */
    public static final int ABS_MIN_MICROS = 1;
    public static final int ABS_MAX_MICROS = 20;

    public static final double DEFAULT_RR_FLOOR_LEGACY = 1.0;
    public static final double DEFAULT_RR_FLOOR_SCALP = 0.8;
    public static final double DEFAULT_RR_CEILING = 5.0;

    /** Topstep flatten / no-entry block start (CT) — SACRED, not configurable. */
    public static final java.time.LocalTime FLATTEN_SAFETY_NET_CT = java.time.LocalTime.of(14, 45);
    /** Topstep no-entry block end (CT) — Globex reopen. */
    public static final java.time.LocalTime NO_ENTRY_END_CT = java.time.LocalTime.of(17, 0);

    private RiskConfig() {}

    public static long warmupTimeoutSeconds() {
        return Math.max(1L, longProp(WARMUP_TIMEOUT_SECONDS, 120L));
    }

    /** Minimum micros a risk-derived size may be; clamped to [1, 20]. */
    public static int minMicros() {
        return clamp(intProp(SIZE_MIN_MICROS, 1), ABS_MIN_MICROS, ABS_MAX_MICROS);
    }

    /** Maximum micros; clamped to [minMicros, 20]. */
    public static int maxMicros() {
        return clamp(intProp(SIZE_MAX_MICROS, 20), minMicros(), ABS_MAX_MICROS);
    }

    /** Preferred (fallback) micros — used only when geometry is unknown. Never a floor. */
    public static int preferredMicros() {
        return clamp(intProp(SIZE_PREFERRED_MICROS, 5), minMicros(), maxMicros());
    }

    /** Legacy-profile RR floor (risk.rrFloor, default 1.0). */
    public static double rrFloorLegacy() {
        return doubleProp(RR_FLOOR, DEFAULT_RR_FLOOR_LEGACY);
    }

    /** Scalp-profile RR floor (risk.rrFloor.scalp, else risk.rrFloor, else 0.8). */
    public static double rrFloorScalp() {
        String v = cfg().getRaw(RR_FLOOR_SCALP);
        if (v != null && !v.isBlank()) return doubleProp(RR_FLOOR_SCALP, DEFAULT_RR_FLOOR_SCALP);
        return doubleProp(RR_FLOOR, DEFAULT_RR_FLOOR_SCALP);
    }

    /** Single RR ceiling (risk.rrCeiling, default 5.0). */
    public static double rrCeiling() {
        return doubleProp(RR_CEILING, DEFAULT_RR_CEILING);
    }

    /** risk.haltOnProfitTarget; default depends on mode (LIVE true, SIM false). */
    public static boolean haltOnProfitTarget(boolean live) {
        String v = cfg().getRaw(HALT_ON_PROFIT_TARGET);
        if (v == null || v.isBlank()) return live;
        return Boolean.parseBoolean(v.trim());
    }

    /** news.blockWithoutCalendar (default false): a Mock/absent calendar never blocks. */
    public static boolean newsBlockWithoutCalendar() {
        return cfg().getBoolean(NEWS_BLOCK_WITHOUT_CALENDAR, false);
    }

    /**
     * order.ttlBars — SIM resting-order time-to-live in 1m feed bars. Default
     * = OTE window x 2 = stdvOte.oteWindowBars (8) x detector TF minutes (5) x 2
     * = 80, the same budget as the runner's entry-fill timeout.
     */
    public static int orderTtlBars() {
        int ote = intProp("ote.windowBars", 8);
        int tf = intProp("detector.timeframe", 5);
        int def = Math.max(1, ote * Math.max(1, tf) * 2);
        return Math.max(1, intProp(ORDER_TTL_BARS, def));
    }

    /**
     * True inside the Topstep daily no-entry / flatten block
     * [14:45, 17:00) America/Chicago (SACRED; DST-correct via the zone).
     * Null time -> false.
     */
    public static boolean inNoEntryBlock(java.time.Instant t) {
        if (t == null) return false;
        java.time.LocalTime ct = t.atZone(java.time.ZoneId.of("America/Chicago")).toLocalTime();
        return !ct.isBefore(FLATTEN_SAFETY_NET_CT) && ct.isBefore(NO_ENTRY_END_CT);
    }

    // ── helpers (EngineConfig view) ─────────────────────────────────────
    private static com.topstep.trading.config.EngineConfig cfg() {
        return com.topstep.trading.config.EngineConfig.current();
    }

    static int intProp(String key, int def) {
        return cfg().getInt(key, def);
    }

    static long longProp(String key, long def) {
        return cfg().getLong(key, def);
    }

    static double doubleProp(String key, double def) {
        return cfg().getDouble(key, def);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
