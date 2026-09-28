package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.strategy.TradeTier;

/**
 * THE position sizer for the STDV+OTE pipeline (V5 RC-14: ONE sizer).
 *
 * <h2>The rule ({@link #riskDerived})</h2>
 * <pre>
 * stopTicks   = |entry - stop| / tickSize
 * perContract = stopTicks * tickValue                 ($ risk of ONE micro)
 * size        = floor(riskDollars / perContract)
 * size        = min(size, maxMicros)                  (size.maxMicros, default 20)
 * if size &lt; minMicros (size.minMicros, default 1) -&gt; DENY
 *     "SIZE: stop too wide for risk budget (need $X, have $Y)"
 * </pre>
 * {@code size.preferredMicros} (default 5) is a PREFERENCE used only when
 * geometry is unknown — never a floor. A killzone boost is applied AFTER
 * the risk-derived size ({@link #applyBoost}) and NEVER raises the dollar
 * risk above the budget (FABLE-REJECT #1): boosted = min(floor(boost x
 * size), floor(budget / $perMicro), cap). With a pure risk-derived size the
 * boost is therefore a no-op on risk — by design. PropFirmRiskEngine re-derives with this same function
 * and honours the strategy's requested size (it never silently re-sizes).
 *
 * <p>MNQ ($0.50/tick, 4 ticks/pt = $2/pt) at $250: stop 10/20/40/80 pts →
 * 12/6/3/1 micros; 160 pts ($320/micro) → DENY.
 *
 * <h2>Buffer-based wrapper ({@link #decide})</h2>
 * The STDV_OTE_MODEL.md §6 buffer formula only chooses the DOLLAR budget
 * ({@code available_room * riskFraction}); contracts come from
 * {@link #riskDerived}, then the tier cap / Topstep cap / news multiplier
 * apply. Its floor is the instrument spec's {@code minMicros}
 * (= size.minMicros, default 1) — the old hard [5, 20] floor that stood
 * the 2026-09-24 08:45 PRE_NY setup down for 41 bars is gone.
 */
public final class StdvOteSizer {

    /** Default fraction of available room risked per trade. */
    public static final double DEFAULT_RISK_FRACTION = 0.12;

    /** Default consistency-throttle threshold (40% best-day share). */
    public static final double DEFAULT_MAX_BEST_DAY_SHARE = 0.40;

    /** Inputs for one sizing decision. */
    public record SizeRequest(
            double entry,
            double stop,
            TradeableInstrument.Spec spec,
            TradeTier tier) {}

    /** Account/risk inputs that bind the sizing decision. */
    public record SizeContext(
            double equity,
            double mllFloor,
            double safetyCushion,
            double riskFraction,
            double newsMultiplier,
            int topstepMicroMax) {

        /** Convenience constructor with default risk fraction + news multiplier. */
        public static SizeContext of(double equity, double mllFloor,
                                     double safetyCushion, int topstepMicroMax) {
            return new SizeContext(equity, mllFloor, safetyCushion,
                    DEFAULT_RISK_FRACTION, 1.0, topstepMicroMax);
        }
    }

    /** Reason a sizing decision returned 0 micros, for logging. */
    public enum SkipReason {
        OK,
        NO_ROOM,
        BELOW_FLOOR,
        NEWS_MULTIPLIER_TOO_LOW,
        DEGENERATE_GEOMETRY
    }

    /** Full sizer outcome with the reason a 0-size happened. */
    public record SizingDecision(int contracts, SkipReason reason, String detail) {
        public boolean shouldTrade() {
            return reason == SkipReason.OK && contracts >= 1;
        }
    }

    /**
     * Outcome of the risk-derived rule. {@code denied} carries a
     * "SIZE: ..." reason with the two dollar numbers ({@code needDollars}
     * = minMicros x perContract, {@code haveDollars} = the budget).
     */
    public record RiskSize(int contracts, boolean denied, String reason,
                           double haveDollars, double needDollars,
                           double perContract, double stopTicks) {
        public boolean ok() { return !denied && contracts >= 1; }
    }

    /**
     * THE sizing rule (see class javadoc). Pure.
     *
     * @param riskDollars per-trade $ budget (already capped by DLL/MLL room by the caller)
     * @param minMicros   size.minMicros
     * @param maxMicros   hard ceiling (min of size.maxMicros, maxContracts, Topstep cap)
     */
    public static RiskSize riskDerived(double riskDollars, double entry, double stop,
                                       double tickSize, double tickValue,
                                       int minMicros, int maxMicros) {
        double stopPts = Math.abs(entry - stop);
        if (!(stopPts > 0) || !(tickSize > 0) || !(tickValue > 0)) {
            return new RiskSize(0, true,
                    "SIZE: degenerate geometry (stop distance " + stopPts + ")",
                    riskDollars, Double.NaN, Double.NaN, 0);
        }
        double stopTicks = stopPts / tickSize;
        // Guard float noise: 39.999999 ticks is 40 ticks.
        stopTicks = Math.round(stopTicks * 1e6) / 1e6;
        double perContract = stopTicks * tickValue;
        int floor = Math.max(1, minMicros);
        double need = floor * perContract;
        if (!(riskDollars > 0)) {
            return new RiskSize(0, true, String.format(
                    "SIZE: no risk budget (need $%.2f, have $%.2f)", need, riskDollars),
                    riskDollars, need, perContract, stopTicks);
        }
        long raw = (long) Math.floor(riskDollars / perContract + 1e-9);
        if (raw < floor) {
            return new RiskSize(0, true, String.format(
                    "SIZE: stop too wide for risk budget (need $%.2f, have $%.2f)", need, riskDollars),
                    riskDollars, need, perContract, stopTicks);
        }
        int cap = Math.max(floor, maxMicros);
        int size = (int) Math.min(raw, cap);
        return new RiskSize(size, false, String.format(
                "SIZE: %d micros = floor($%.2f / (%.2f ticks x $%.2f)) [band %d..%d]",
                size, riskDollars, stopTicks, perContract / stopTicks, floor, cap),
                riskDollars, need, perContract, stopTicks);
    }

    /**
     * Killzone boost AFTER the risk-derived size (FABLE-REJECT #1 fix):
     * {@code min(floor(size x boost), floor(budget / perContract), cap)} and
     * never below the input. The dollar risk of the result NEVER exceeds
     * {@code budgetDollars}: the boost can only fill headroom left by a
     * cap below the budget-implied maximum, never add risk.
     */
    public static int applyBoost(int size, double boost, int cap,
                                 double budgetDollars, double perContract) {
        if (size <= 0 || !(boost > 1.0)) return size;
        int boosted = (int) Math.floor(size * Math.min(2.0, boost));
        int budgetMax = (perContract > 0)
                ? (int) Math.floor(budgetDollars / perContract + 1e-9)
                : size;
        return Math.max(size, Math.min(boosted, Math.min(budgetMax, cap)));
    }

    /**
     * The per-trade $ budget both the strategy and the risk engine size
     * against: min(riskPerTrade, DLL room, MLL room). Never loosens a rail.
     */
    public static double riskBudget(double riskPerTrade, double dllRoom, double mllRoom) {
        double b = riskPerTrade;
        if (!Double.isNaN(dllRoom)) b = Math.min(b, dllRoom);
        if (!Double.isNaN(mllRoom)) b = Math.min(b, mllRoom);
        return b;
    }

    /**
     * Size a single trade. Convenience wrapper that returns the integer
     * micros directly; call {@link #decide(SizeRequest, SizeContext)} for
     * the structured result including the skip reason.
     */
    public int size(SizeRequest req, SizeContext ctx) {
        return decide(req, ctx).contracts();
    }

    /** Full sizing decision including the skip reason for logs/UI. */
    public SizingDecision decide(SizeRequest req, SizeContext ctx) {
        if (req == null || req.spec == null) {
            return new SizingDecision(0, SkipReason.DEGENERATE_GEOMETRY, "null inputs");
        }
        TradeableInstrument.Spec spec = req.spec;
        int floor   = spec.minMicros();
        int ceiling = spec.maxMicros();

        double availableRoom = ctx.equity() - ctx.mllFloor() - ctx.safetyCushion();
        if (availableRoom <= 0) {
            return new SizingDecision(0, SkipReason.NO_ROOM,
                    "available_room=" + availableRoom);
        }
        double riskBudget = availableRoom * ctx.riskFraction();

        double stopPts = Math.abs(req.entry - req.stop);
        if (stopPts <= 0) {
            return new SizingDecision(0, SkipReason.DEGENERATE_GEOMETRY,
                    "stop equals entry");
        }
        int tierCap = tierCap(req.tier, ceiling);
        int topstepCap = (ctx.topstepMicroMax() > 0)
                ? Math.min(ctx.topstepMicroMax(), ceiling)
                : ceiling;
        int effectiveCap = Math.min(tierCap, topstepCap);

        // ONE rule: contracts come from riskDerived(); this wrapper only
        // chose the dollar budget above.
        RiskSize rs = riskDerived(riskBudget, req.entry, req.stop,
                spec.tickSize(), spec.tickValue(), floor, effectiveCap);
        if (rs.denied()) {
            return new SizingDecision(0, SkipReason.BELOW_FLOOR,
                    rs.reason() + " (risk_budget=" + riskBudget
                            + " per_contract=" + rs.perContract() + " floor=" + floor + ")");
        }
        int sized = rs.contracts();

        // Apply the news multiplier; rounds DOWN by floor().
        double multiplied = sized * Math.max(0.0, ctx.newsMultiplier());
        int afterNews = (int) Math.floor(multiplied);
        if (afterNews < floor) {
            return new SizingDecision(0, SkipReason.NEWS_MULTIPLIER_TOO_LOW,
                    "after_news=" + afterNews + " < floor=" + floor
                            + " (multiplier=" + ctx.newsMultiplier() + ")");
        }

        // Final clamp again to be safe vs the cap.
        int contracts = clamp(afterNews, floor, effectiveCap);
        return new SizingDecision(contracts, SkipReason.OK,
                "risk_budget=" + riskBudget + " per_contract=" + rs.perContract()
                        + " tierCap=" + tierCap + " topstepCap=" + topstepCap);
    }

    /**
     * Tier-driven upper bound on size. Tiers 1..4 map to bands 8 / 12 / 16 /
     * 20. A null tier defaults to the instrument ceiling (no tier cap).
     */
    static int tierCap(TradeTier tier, int instrumentMax) {
        if (tier == null) return instrumentMax;
        switch (tier) {
            case TIER_4: return Math.min(20, instrumentMax);
            case TIER_3: return Math.min(16, instrumentMax);
            case TIER_2: return Math.min(12, instrumentMax);
            case TIER_1: return Math.min(8,  instrumentMax);
            default:     return instrumentMax;
        }
    }

    private static int clamp(int v, int lo, int hi) {
        if (hi < lo) return 0;
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }
}
