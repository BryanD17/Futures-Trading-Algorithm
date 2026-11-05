package com.topstep.trading.lifecycle;

import java.util.List;

/**
 * V6 Agent 05.2: one Lucid account profile exactly as config/lucid_profiles.yaml
 * (Agent 01, sources in docs/LUCID_RULES.md) states it. Built ONLY by
 * {@link com.topstep.trading.config.LucidProfiles}; no numbers are typed in Java.
 *
 * <p>Nullable YAML numbers are mapped to: {@code profitTarget = 0} (funded:
 * no target), {@code consistencyMaxPct = 0} (no consistency rule),
 * {@code minTradingDays = 0}.
 */
public record LucidAccountProfile(
        String id,
        String plan,
        String stage,
        double startingBalance,
        double profitTarget,
        double maxLossLimit,
        double initialTrailBalance,
        double lockedMllBalance,
        double softDailyLoss,
        int maxContractsMicros,
        int scalingCapMicros,
        List<ScalingBand> scalingPlan,
        double consistencyMaxPct,
        int minTradingDays,
        String drawdownType,
        double profitSplitTrader) {

    /** A funded scaling band: profit in [from, to] (to = +inf when null) allows {@code micros}. */
    public record ScalingBand(double profitFrom, double profitTo, int micros) {}

    public boolean isEval() { return "EVAL".equalsIgnoreCase(stage); }

    public boolean isEodTrailing() { return "EOD_TRAILING".equalsIgnoreCase(drawdownType); }

    /**
     * Size cap in micros at the given simulated profit (LUCID_RULES §10: funded
     * scaling plan, updated at the end of each session; eval has no scaling plan
     * -> the max size).
     */
    public int scalingCapMicrosAt(double profit) {
        if (scalingPlan == null || scalingPlan.isEmpty()) return scalingCapMicros;
        int cap = scalingPlan.get(0).micros();
        for (ScalingBand b : scalingPlan) {
            if (profit >= b.profitFrom()) cap = b.micros();
        }
        return Math.min(cap, maxContractsMicros);
    }
}
