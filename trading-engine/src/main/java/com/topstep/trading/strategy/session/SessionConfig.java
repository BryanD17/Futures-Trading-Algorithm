package com.topstep.trading.strategy.session;

import com.topstep.trading.strategy.stdvote.ScalpConfig;

/**
 * Agent 02's configuration keys, read in ONE place.
 *
 * <p>Every read goes through {@link com.topstep.trading.config.EngineConfig}
 * (V5 Agent 01: -D &gt; ENGINE_* env &gt; engine.properties &gt; classpath
 * engine-defaults.properties &gt; code default; legacy aliases such as
 * {@code scalp.allSessions} / {@code scalp.rearmCooldownBars} /
 * {@code stdvOte.setupExpiryBars} resolve to the canonical keys). System
 * properties are resolved live, so tests / the harness may set them at runtime.
 *
 * <pre>
 *   session.gateMode             SCORING | BLOCKING          default SCORING
 *   session.allSessions          true | false                default scalp.allSessions (true)
 *   setup.expiryMinutes          minutes from the anchor     (wins over expiryBars)
 *   setup.expiryBars             DETECTOR bars from anchor   default 12 on 5m (= 60 min)
 *   setup.preSweepExpiryMinutes  BIAS_SET/MANIP_DONE budget  default 480 (8 h)
 *   setup.expiryAnchor           SWEEP_DONE | BIAS_SET       default SWEEP_DONE (SCORING) / BIAS_SET (BLOCKING)
 *   setup.rearmCooldownBars      feed bars                   default scalp.rearmCooldownBars (5)
 * </pre>
 * Read at call time (never cached statically) so harness/test JVMs that set
 * properties before building a runner see them.
 */
public final class SessionConfig {

    private SessionConfig() {}

    public static final String GATE_MODE = "session.gateMode";
    public static final String ALL_SESSIONS = "session.allSessions";
    public static final String EXPIRY_MINUTES = "setup.expiryMinutes";
    public static final String EXPIRY_BARS = "setup.expiryBars";
    public static final String PRE_SWEEP_EXPIRY_MINUTES = "setup.preSweepExpiryMinutes";
    public static final String EXPIRY_ANCHOR = "setup.expiryAnchor";
    public static final String REARM_COOLDOWN_BARS = "setup.rearmCooldownBars";
    /** Pre-V5 key, still honoured (now measured from the configured anchor). */
    public static final String LEGACY_EXPIRY_BARS = "stdvOte.setupExpiryBars";

    /** Default post-sweep budget: 60 minutes (60 bars on 1m, 12 detector bars on 5m). */
    public static final int DEFAULT_EXPIRY_MINUTES = 60;
    /** Default pre-sweep budget (BIAS_SET / MANIP_DONE): 480 minutes. */
    public static final int DEFAULT_PRE_SWEEP_EXPIRY_MINUTES = 480;
    /** Pre-V5 expiry: 40 DETECTOR bars from BIAS_SET. */
    public static final int LEGACY_DEFAULT_EXPIRY_DETECTOR_BARS = 40;

    /** Where the hunting budget is measured from. */
    public enum ExpiryAnchor { SWEEP_DONE, BIAS_SET }

    /** Configured mode ({@code session.gateMode}, default SCORING). */
    public static SessionGateMode gateMode() {
        return SessionGateMode.parse(cfg().getRaw(GATE_MODE));
    }

    /** {@code session.allSessions}, falling back to {@code scalp.allSessions} (default true). */
    public static boolean allSessions() {
        String raw = cfg().getRaw(ALL_SESSIONS); // alias: scalp.allSessions
        if (raw == null || raw.isBlank()) return ScalpConfig.allSessions();
        return !"false".equalsIgnoreCase(raw.trim());
    }

    /**
     * Mode actually applied: SCORING needs all-sessions entry; with
     * {@code session.allSessions=false} the gate falls back to BLOCKING
     * (killzones only), exactly the pre-V5 meaning of that flag.
     */
    public static SessionGateMode effectiveGateMode() {
        SessionGateMode m = gateMode();
        return (m == SessionGateMode.SCORING && allSessions()) ? SessionGateMode.SCORING
                : SessionGateMode.BLOCKING;
    }

    public static int rearmCooldownBars() {
        Integer v = intOrNull(REARM_COOLDOWN_BARS); // alias: scalp.rearmCooldownBars
        return v != null ? Math.max(0, v) : ScalpConfig.rearmCooldownBars();
    }

    public static ExpiryAnchor expiryAnchor(SessionGateMode mode) {
        String raw = cfg().getRaw(EXPIRY_ANCHOR);
        if (raw != null && !raw.isBlank()) {
            try {
                return ExpiryAnchor.valueOf(raw.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                System.out.println("[SESSION] unknown setup.expiryAnchor '" + raw
                        + "' — using the mode default");
            }
        }
        return mode == SessionGateMode.BLOCKING ? ExpiryAnchor.BIAS_SET : ExpiryAnchor.SWEEP_DONE;
    }

    /**
     * Hunting budget in FEED (1m) bars = minutes. Precedence:
     * setup.expiryMinutes &gt; setup.expiryBars (alias stdvOte.setupExpiryBars)
     * x detector minutes &gt; anchor default
     * (SWEEP_DONE: 60 min; BIAS_SET: 40 detector bars, the pre-V5 value).
     */
    public static int expiryFeedBars(int detectorMinutes, ExpiryAnchor anchor) {
        int det = Math.max(1, detectorMinutes);
        Integer minutes = intOrNull(EXPIRY_MINUTES);
        if (minutes != null) return Math.max(0, minutes);
        Integer bars = intOrNull(EXPIRY_BARS); // alias: stdvOte.setupExpiryBars
        if (bars != null) return Math.max(0, bars) * det;
        return anchor == ExpiryAnchor.BIAS_SET
                ? LEGACY_DEFAULT_EXPIRY_DETECTOR_BARS * det
                : DEFAULT_EXPIRY_MINUTES;
    }

    /** Pre-sweep budget in feed bars (minutes); only used with the SWEEP_DONE anchor. */
    public static int preSweepExpiryFeedBars() {
        Integer v = intOrNull(PRE_SWEEP_EXPIRY_MINUTES);
        return v != null ? Math.max(0, v) : DEFAULT_PRE_SWEEP_EXPIRY_MINUTES;
    }

    private static com.topstep.trading.config.EngineConfig cfg() {
        return com.topstep.trading.config.EngineConfig.current();
    }

    private static Integer intOrNull(String key) {
        String raw = cfg().getRaw(key);
        if (raw == null || raw.isBlank()) return null;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            System.out.println("[SESSION] ignoring non-integer " + key + "=" + raw);
            return null;
        }
    }
}
