package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.config.EngineConfig;

import java.time.LocalTime;

/**
 * V5 Agent 03 — a thin typed VIEW over {@link EngineConfig} for every key this
 * agent owns (bias vote rule / anchor / hysteresis, level windows, raid
 * scoring, rollback switches). Every key is registered in
 * {@code EngineConfig.KEYS} (so it appears in the boot table and
 * {@code /api/status effectiveConfig}); the V5 product defaults that differ
 * from the code default live in {@code engine-defaults.properties}.
 *
 * <p>Reads happen AT CALL TIME through {@link EngineConfig#current()} (no
 * caching here), so the {@code -D} layer keeps the highest precedence even in
 * tests that set a system property after the config was loaded.
 */
public final class BiasConfig {

    private BiasConfig() {
    }

    private static EngineConfig cfg() {
        return EngineConfig.current();
    }

    // ── Bias ────────────────────────────────────────────────────────────

    /** Vote aggregation rule ({@code bias.voteRule}). */
    public enum VoteRule { STRICT_3OF4, ADAPTIVE }

    /** Where the final bias comes from ({@code bias.source}). */
    public enum BiasSource {
        /** Dealing-range impulse direction decides; the vote is the
         *  fallback while the range is not yet decisive (DEFAULT). */
        RANGE,
        /** The vote alone decides (pre-V5 VOTE semantics). */
        VOTE
    }

    public static VoteRule voteRule() {
        String raw = cfg().getString("bias.voteRule", "ADAPTIVE");
        try {
            return VoteRule.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return VoteRule.ADAPTIVE;
        }
    }

    public static BiasSource biasSource() {
        String raw = cfg().getString("bias.source", "RANGE");
        try {
            return BiasSource.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return BiasSource.RANGE;
        }
    }

    /** Call-site default of {@code bias.vote.mode} (V5: VOTE, see A-03 DECISIONS). */
    public static String defaultVoteMode() {
        return "VOTE";
    }

    /** {@code bias.hysteresis} (alias {@code bias.hysteresis.enabled}); V5 default true. */
    public static boolean hysteresis() {
        return cfg().getBoolean("bias.hysteresis", true);
    }

    /** {@code bias.neutralGraceBars}; V5 default 3 (clamped [1,4] by the core). */
    public static int neutralGraceBars() {
        return cfg().getInt("bias.neutralGraceBars", 3);
    }

    /** Dealing-range minimum span as % of price ({@code bias.range.minRangePct}). */
    public static double rangeMinPct() {
        return cfg().getDouble("bias.range.minRangePct", 0.08);
    }

    /** Pullback fraction of the leg that re-anchors the range on a BOS
     *  ({@code bias.range.reanchorFraction}); default 0.5 (premium/discount). */
    public static double rangeReanchorFraction() {
        return cfg().getDouble("bias.range.reanchorFraction", 0.5);
    }

    /** Code default of {@code bias.range.window} (V5 Agent 05.6). */
    public static final DealingRangeTracker.Window DEFAULT_RANGE_WINDOW = DealingRangeTracker.Window.AUTO;
    /** Code default of {@code bias.range.minLegTicks} (MNQ 400 ticks = 100 pt,
     *  ~ 0.33 % at 30,800: above the 09:30 opening-bar noise, see A-05.6). */
    public static final int DEFAULT_RANGE_MIN_LEG_TICKS = 400;

    /** Where the dealing range is taken from ({@code bias.range.window}):
     *  SESSION_DAY | RTH_FIRST | AUTO (V5 Agent 05.6). */
    public static DealingRangeTracker.Window rangeWindow() {
        String raw = cfg().getString("bias.range.window", DEFAULT_RANGE_WINDOW.name());
        try {
            return DealingRangeTracker.Window.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            return DEFAULT_RANGE_WINDOW;
        }
    }

    /** Minimum RTH impulse leg in ticks ({@code bias.range.minLegTicks.<SYM>},
     *  else {@code bias.range.minLegTicks}). */
    public static int rangeMinLegTicks(String symbol) {
        int base = cfg().getInt("bias.range.minLegTicks", DEFAULT_RANGE_MIN_LEG_TICKS);
        int v = symbol == null ? base : cfg().getInt("bias.range.minLegTicks." + symbol, base);
        return Math.max(1, v);
    }

    /** Code default of {@code bias.range.carryAcrossReopen} (V5 Agent 05.7). */
    public static final boolean DEFAULT_RANGE_CARRY_ACROSS_REOPEN = true;

    /** Keep the previous session's governing dealing range across the
     *  18:00 ET reopen until the new session prints an impulse leg of
     *  {@code bias.range.minLegTicks} ({@code bias.range.carryAcrossReopen});
     *  false = the range restarts at the reopen (A/B). */
    public static boolean rangeCarryAcrossReopen() {
        return cfg().getBoolean("bias.range.carryAcrossReopen", DEFAULT_RANGE_CARRY_ACROSS_REOPEN);
    }

    // ── Levels ──────────────────────────────────────────────────────────

    /** A trading day with fewer bars is a phantom (settlement print) and
     *  never becomes PDH/PDL ({@code levels.minBarsPerDay}); default 60. */
    public static int levelsMinBarsPerDay() {
        return cfg().getInt("levels.minBarsPerDay", 60);
    }

    /** A raided level re-arms once a whole candle has left it by this many
     *  ticks on the far side ({@code levels.rearmDistanceTicks}); default 100
     *  (MNQ 25 pt). Real tape 2026-09-28: the 30640 London high is taken at
     *  12:24, 13:18 and 14:05 and re-arms each time price leaves it by 25 pt,
     *  so the 14:53 spike to 30650 is again a raid of that level. */
    public static int levelsRearmDistanceTicks() {
        return cfg().getInt("levels.rearmDistanceTicks", 100);
    }

    /** {@code levels.<session>.<start|end>} (HH:mm ET). */
    public static LocalTime levelWindow(String session, String edge, LocalTime dflt) {
        String raw = cfg().getString("levels." + session + "." + edge, null);
        if (raw == null || raw.isBlank()) return dflt;
        try {
            return LocalTime.parse(raw.trim());
        } catch (RuntimeException e) {
            return dflt;
        }
    }

    // ── Rollback switches (R3: every change can be reverted by config) ──

    /** {@code raid.sweepMode}: PIPELINE (default) or LEGACY (pre-V5 sweep path). */
    public static boolean legacySweepMode() {
        return "LEGACY".equalsIgnoreCase(cfg().getString("raid.sweepMode", "PIPELINE").trim());
    }

    /** {@code manip.legMode}: SESSION (default) or KILLZONE (pre-V5 leg). */
    public static boolean legacyManipLegMode() {
        return "KILLZONE".equalsIgnoreCase(cfg().getString("manip.legMode", "SESSION").trim());
    }

    /** {@code raid.weights}: V5 (default) or V4 (the pre-V5 weight table). */
    public static boolean legacyRaidWeights() {
        return "V4".equalsIgnoreCase(cfg().getString("raid.weights", "V5").trim());
    }

    // ── Raid scoring ────────────────────────────────────────────────────

    /** {@code session.gateMode} (Agent 01 key, Agent 02 consumer): SCORING (default) or BLOCKING. */
    public static boolean sessionGateScoring() {
        return !"BLOCKING".equalsIgnoreCase(cfg().getString("session.gateMode", "SCORING").trim());
    }

    /** Documented score for a sweep the pipeline cannot score at all
     *  ({@code raid.starvedScore}); default -1 = "instrument floor - 1", i.e.
     *  an unscoreable sweep never passes M4. */
    public static int starvedScore(int instrumentFloor) {
        int v = cfg().getInt("raid.starvedScore", -1);
        return v < 0 ? instrumentFloor - 1 : v;
    }
}
