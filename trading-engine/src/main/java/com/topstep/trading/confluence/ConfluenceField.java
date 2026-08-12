package com.topstep.trading.confluence;

/**
 * The canonical confluence field set (V4 Agent 07).
 *
 * <p>Each constant names its OWNER — the component that actually computes the
 * fact. {@code ConfluenceService} reads those owners and records the answer;
 * it derives nothing. That is what keeps "one source of truth per fact" (B13)
 * true in practice rather than in intention: if a field's meaning ever changes,
 * it changes in one place and the snapshot follows.
 *
 * <p>The default weight is what the field is worth when TRUE. Every weight is
 * overridable with {@code -Dconfluence.weight.<key>=N} and must be &gt;= 0.
 */
public enum ConfluenceField {

    // ── ENGINE FACTS ───────────────────────────────────────────────────────
    IN_TRADING_KILLZONE("inTradingKillzone", "KillzoneClock", 3.0),
    HTF_BIAS_ALIGNED("htfBiasAligned", "HtfTrendAnalyzer (legacy bias)", 2.0),
    VOTE_BIAS_ALIGNED("voteBiasAligned", "BiasVoteEngine (V3 3-of-4)", 2.0),
    PD_VERDICT("pdVerdict", "PremiumDiscountEvaluator (M2b)", 1.0),
    RECENT_SWEEP("recentSweep", "RaidDetector / raid pipeline", 3.0),
    RAID_SCORE("raidScore", "RaidQualityScorer", 2.0),
    MACHINE_OTE_STATE("machineOteState", "StdvOte state machine", 2.0),

    // ── ICTLIB FACTS ───────────────────────────────────────────────────────
    ACTIVE_FVG_IN_DIRECTION("activeFvgInDirection", "ictlib §S2 registry", 2.0),
    PRICE_INSIDE_FVG("priceInsideFvg", "ictlib §S2 registry", 2.0),
    NEAREST_OB_ZONE("nearestObZone", "ictlib §S7 registry", 2.0),
    BPR_PRESENT("bprPresent", "ictlib §S3 registry", 1.0),
    VI_NEARBY("viNearby", "ictlib §S4 registry", 1.0),
    OPENING_GAP_MAGNET("openingGapMagnet", "ictlib §S5 registry", 1.0),
    POOL_SWEPT_RECENTLY("poolSweptRecently", "ictlib §S6 registry", 2.0),
    STRUCTURE_STATE("structureState", "ictlib §S8 StructureEngine", 2.0),

    // ── CHART FACTS ────────────────────────────────────────────────────────
    CHART_OTE_STATE("chartOteState", "ChartEngine 30m zone", 2.0);

    private final String key;
    private final String owner;
    private final double defaultWeight;

    ConfluenceField(String key, String owner, double defaultWeight) {
        this.key = key;
        this.owner = owner;
        this.defaultWeight = defaultWeight;
    }

    /** Stable key used in the API, the log line and the weight property. */
    public String key() {
        return key;
    }

    /** The component that owns this fact. Documentation that lives in code. */
    public String owner() {
        return owner;
    }

    public double defaultWeight() {
        return defaultWeight;
    }

    /** Resolved weight: {@code confluence.weight.<key>}, clamped to >= 0. */
    public double weight() {
        String raw = System.getProperty("confluence.weight." + key);
        if (raw == null) return defaultWeight;
        try {
            return Math.max(0.0, Double.parseDouble(raw.trim()));
        } catch (NumberFormatException e) {
            return defaultWeight;
        }
    }
}
