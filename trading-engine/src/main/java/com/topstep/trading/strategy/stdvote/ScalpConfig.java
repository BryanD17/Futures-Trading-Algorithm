package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.domain.RiskLimits;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;

/**
 * Scalp-mode configuration — the single selection point for the 1R-capped
 * scalp target / risk model (SA3).
 *
 * <p>Mirrors the {@code stdvOte.enabled} pattern from {@link StdvOteFactory}
 * exactly: plain system properties, runtime-toggleable via {@code -D} flags,
 * no code changes needed to flip. The master switch is:
 *
 * <pre>-DscalpMode.enabled=true</pre>
 *
 * <p><strong>Default is OFF.</strong> With the flag absent or false, every
 * consumer behaves byte-for-byte as before this change (legacy −2σ extension
 * targets, {@code RiskLimits.topstep50k()}, tiered TP ladder).
 *
 * <h2>Properties ({@code scalp.*} prefix)</h2>
 * <ul>
 *   <li>{@code scalpMode.enabled} — master switch (default {@code false}).</li>
 *   <li>{@code scalp.breakevenAtHalfR} — move the stop to entry once price
 *       reaches +0.5R (default {@code true}). Implemented via the existing
 *       {@code BracketOrderManager} price-based breakeven trigger.</li>
 *   <li>{@code scalp.minTargetClearanceTicks} — a target must clear entry by
 *       at least this many ticks to be tradeable (default {@code 2}).</li>
 *   <li>{@code scalp.candidateWindowR} — candidates farther than this many R
 *       from entry are not "valid"; with none valid the target falls back to
 *       exactly 1R (default {@code 1.5}).</li>
 *   <li>{@code scalp.minRaidScore} — binary quality gate (SA4): a sweep whose
 *       REAL raid-pipeline score is below this floor never arms a scalp setup
 *       (default {@code 6}). The tier ladder no longer blocks emission in
 *       scalp mode; tier only informs sizing.</li>
 *   <li>{@code scalp.rearmCooldownBars} — bars (feed timeframe, 1m in the
 *       runners) that must elapse after a position close / setup invalidation
 *       before a new setup may arm in the same killzone (default {@code 5}).</li>
 *   <li>{@code scalp.londonPrimeStartEt} / {@code scalp.londonPrimeEndEt} —
 *       the London prime window (ET, HH:mm) that gates MGC scalp entries
 *       (defaults {@code 03:00} / {@code 05:00}). KillzoneClock has no phase
 *       API for the London session (its phases cover NY AM/PM only), so the
 *       prime restriction is config-driven per the SA4 fallback clause.</li>
 *   <li>{@code scalp.sizerSafetyCushion} — dollars held back from available
 *       room in the {@code StdvOteSizer} wiring (default {@code 200}).</li>
 * </ul>
 *
 * <p>The 1R target cap itself is NOT configurable — it is the definition of
 * the scalp model ({@link ScalpTargetCalculator#TARGET_CAP_R}) and the number
 * SA5's Monte Carlo depends on.
 */
public final class ScalpConfig {

    /** System property: master scalp-mode switch. Default false (legacy). */
    public static final String ENABLED_PROPERTY = "scalpMode.enabled";

    /** System property: breakeven-at-+0.5R flag. Default true. */
    public static final String BREAKEVEN_AT_HALF_R_PROPERTY = "scalp.breakevenAtHalfR";
    public static final boolean DEFAULT_BREAKEVEN_AT_HALF_R = true;

    /** System property: minimum target clearance past entry, in ticks. Default 2. */
    public static final String MIN_TARGET_CLEARANCE_TICKS_PROPERTY = "scalp.minTargetClearanceTicks";
    public static final int DEFAULT_MIN_TARGET_CLEARANCE_TICKS = 2;

    /** System property: candidate validity window in R. Default 1.5. */
    public static final String CANDIDATE_WINDOW_R_PROPERTY = "scalp.candidateWindowR";
    public static final double DEFAULT_CANDIDATE_WINDOW_R = 1.5;

    /** R-multiple at which the scalp breakeven trigger arms (+0.5R). */
    public static final double BREAKEVEN_TRIGGER_R = 0.5;

    /** System property: binary raid-score floor for scalp setups. Default 6. */
    public static final String MIN_RAID_SCORE_PROPERTY = "scalp.minRaidScore";
    public static final int DEFAULT_MIN_RAID_SCORE = 6;

    /** System property: re-arm cooldown in feed bars. Default 5. */
    public static final String REARM_COOLDOWN_BARS_PROPERTY = "scalp.rearmCooldownBars";
    public static final int DEFAULT_REARM_COOLDOWN_BARS = 5;

    /** System property: London prime window start (ET, HH:mm). Default 03:00. */
    public static final String LONDON_PRIME_START_ET_PROPERTY = "scalp.londonPrimeStartEt";
    public static final LocalTime DEFAULT_LONDON_PRIME_START_ET = LocalTime.of(3, 0);

    /** System property: London prime window end (ET, HH:mm). Default 05:00. */
    public static final String LONDON_PRIME_END_ET_PROPERTY = "scalp.londonPrimeEndEt";
    public static final LocalTime DEFAULT_LONDON_PRIME_END_ET = LocalTime.of(5, 0);

    /** System property: sizer safety cushion in dollars. Default 200. */
    public static final String SIZER_SAFETY_CUSHION_PROPERTY = "scalp.sizerSafetyCushion";
    public static final double DEFAULT_SIZER_SAFETY_CUSHION = 200.0;

    /**
     * System property: take entries in ANY open session, not just the prime
     * killzones. Default TRUE (owner directive 2026-07-08). The M3 time gate
     * still applies — its window becomes "market open minus the daily
     * 14:45–17:00 CT no-entry block" instead of the killzone union. Set
     * {@code -Dscalp.allSessions=false} to restore killzone-only entries.
     */
    public static final String ALL_SESSIONS_PROPERTY = "scalp.allSessions";
    public static final boolean DEFAULT_ALL_SESSIONS = true;

    /**
     * System property: position-size multiplier applied INSIDE the prime
     * killzones (NY AM/PM, MGC London prime). Default 1.5, clamped to
     * [1.0, 2.0]. The boosted size still passes through every existing cap
     * (tier cap, topstepMicroMax, the [5, 20] micro band) and the
     * PropFirmRiskEngine — the boost can request more, never bypass.
     */
    public static final String KILLZONE_SIZE_BOOST_PROPERTY = "scalp.killzoneSizeBoost";
    public static final double DEFAULT_KILLZONE_SIZE_BOOST = 1.5;

    private ScalpConfig() {}

    /** True when scalp mode is on. Absent property means OFF (opt-in). */
    public static boolean isEnabled() {
        String prop = System.getProperty(ENABLED_PROPERTY);
        if (prop == null) return false; // default OFF — additive opt-in
        return "true".equalsIgnoreCase(prop.trim());
    }

    /** True when the stop should move to entry at +0.5R (scalp mode only). */
    public static boolean breakevenAtHalfR() {
        String prop = System.getProperty(BREAKEVEN_AT_HALF_R_PROPERTY);
        if (prop == null) return DEFAULT_BREAKEVEN_AT_HALF_R;
        return "true".equalsIgnoreCase(prop.trim());
    }

    /** Minimum ticks a scalp target must clear entry by. */
    public static int minTargetClearanceTicks() {
        return intProperty(MIN_TARGET_CLEARANCE_TICKS_PROPERTY,
                DEFAULT_MIN_TARGET_CLEARANCE_TICKS);
    }

    /** Candidate validity window in R (beyond it, fall back to exactly 1R). */
    public static double candidateWindowR() {
        return doubleProperty(CANDIDATE_WINDOW_R_PROPERTY, DEFAULT_CANDIDATE_WINDOW_R);
    }

    /**
     * The single RiskLimits selection point for the Topstep 50K runners.
     * Scalp mode ON → {@link RiskLimits#topstep50kScalp()}; otherwise the
     * unchanged legacy {@link RiskLimits#topstep50k()}.
     */
    public static RiskLimits activeRiskLimits() {
        return isEnabled() ? RiskLimits.topstep50kScalp() : RiskLimits.topstep50k();
    }

    /** Build the target calculator from the current property values. */
    public static ScalpTargetCalculator targetCalculator() {
        return new ScalpTargetCalculator(minTargetClearanceTicks(), candidateWindowR());
    }

    /** Binary raid-score floor for scalp setups (SA4 quality gate). */
    public static int minRaidScore() {
        return intProperty(MIN_RAID_SCORE_PROPERTY, DEFAULT_MIN_RAID_SCORE);
    }

    /** Re-arm cooldown in feed bars after a close/invalidation (SA4). */
    public static int rearmCooldownBars() {
        return Math.max(0, intProperty(REARM_COOLDOWN_BARS_PROPERTY,
                DEFAULT_REARM_COOLDOWN_BARS));
    }

    /** London prime window start (ET) for MGC scalp entries. */
    public static LocalTime londonPrimeStartEt() {
        return timeProperty(LONDON_PRIME_START_ET_PROPERTY, DEFAULT_LONDON_PRIME_START_ET);
    }

    /** London prime window end (ET) for MGC scalp entries. */
    public static LocalTime londonPrimeEndEt() {
        return timeProperty(LONDON_PRIME_END_ET_PROPERTY, DEFAULT_LONDON_PRIME_END_ET);
    }

    /** Safety cushion (dollars) held back in the sizer wiring. */
    public static double sizerSafetyCushion() {
        return doubleProperty(SIZER_SAFETY_CUSHION_PROPERTY, DEFAULT_SIZER_SAFETY_CUSHION);
    }

    /** True when scalp entries are allowed in any open session (see property doc). */
    public static boolean allSessions() {
        String prop = System.getProperty(ALL_SESSIONS_PROPERTY);
        if (prop == null) return DEFAULT_ALL_SESSIONS;
        return "true".equalsIgnoreCase(prop.trim());
    }

    /** Killzone size multiplier, clamped to [1.0, 2.0] (1.0 = no boost). */
    public static double killzoneSizeBoost() {
        double v = doubleProperty(KILLZONE_SIZE_BOOST_PROPERTY, DEFAULT_KILLZONE_SIZE_BOOST);
        return Math.min(2.0, Math.max(1.0, v));
    }

    private static int intProperty(String name, int defaultValue) {
        String raw = System.getProperty(name);
        if (raw == null) return defaultValue;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            System.out.println("[ScalpConfig] WARN: invalid " + name + "='" + raw
                    + "', using default " + defaultValue);
            return defaultValue;
        }
    }

    private static LocalTime timeProperty(String name, LocalTime defaultValue) {
        String raw = System.getProperty(name);
        if (raw == null) return defaultValue;
        try {
            return LocalTime.parse(raw.trim());
        } catch (DateTimeParseException e) {
            System.out.println("[ScalpConfig] WARN: invalid " + name + "='" + raw
                    + "', using default " + defaultValue);
            return defaultValue;
        }
    }

    private static double doubleProperty(String name, double defaultValue) {
        String raw = System.getProperty(name);
        if (raw == null) return defaultValue;
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            System.out.println("[ScalpConfig] WARN: invalid " + name + "='" + raw
                    + "', using default " + defaultValue);
            return defaultValue;
        }
    }
}
