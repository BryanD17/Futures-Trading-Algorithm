package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.strategy.TradeTier;

/**
 * Buffer-based position sizer for the STDV+OTE strategy.
 *
 * <p>Implements the canonical formula from {@code STDV_OTE_MODEL.md} §6 /
 * Appendix W.1:
 *
 * <pre>
 * available_room   = equity - mll_floor - safety_cushion
 * risk_per_trade$  = available_room * riskFraction              (default 0.12)
 * stop_points      = |entry - stop|
 * per_contract_risk = stop_points * pointValue
 * raw_contracts    = floor(risk_per_trade$ / per_contract_risk)
 * sized = clamp(min(raw_contracts, tierCap), 5, 20)
 * sized = min(sized, topstepMicroMax)
 * sized = floor(sized * newsMultiplier)
 * if sized &lt; 5  -&gt; SKIP (returns 0)
 * </pre>
 *
 * <p>The sizer is pure — given the same inputs it returns the same output.
 * It never reads global state. The instrument's hard ceiling
 * ({@link TradeableInstrument.Spec#maxMicros()}) AND the configurable
 * {@code topstepMicroMax} both apply; the lower wins. A tier with no
 * cap (TIER_4) defaults to the instrument ceiling.
 *
 * <p>The sizer NEVER returns 1..4 micros. Either it returns &gt;= 5 or it
 * returns 0 (skip). Sub-floor results are the strategy's signal to log the
 * block and stand down.
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
        public boolean shouldTrade() { return contracts >= 5; }
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
        double perContractRisk = stopPts * spec.pointValue();

        long raw = (long) Math.floor(riskBudget / perContractRisk);
        int tierCap = tierCap(req.tier, ceiling);
        int topstepCap = (ctx.topstepMicroMax() > 0)
                ? Math.min(ctx.topstepMicroMax(), ceiling)
                : ceiling;
        int effectiveCap = Math.min(tierCap, topstepCap);

        int sized = (int) Math.min(raw, effectiveCap);
        sized = clamp(sized, floor, effectiveCap);

        // Apply the news multiplier; rounds DOWN by floor().
        double multiplied = sized * Math.max(0.0, ctx.newsMultiplier());
        int afterNews = (int) Math.floor(multiplied);

        if (raw < floor) {
            return new SizingDecision(0, SkipReason.BELOW_FLOOR,
                    "raw=" + raw + " < floor=" + floor
                            + " (risk_budget=" + riskBudget
                            + " per_contract=" + perContractRisk + ")");
        }
        if (afterNews < floor) {
            return new SizingDecision(0, SkipReason.NEWS_MULTIPLIER_TOO_LOW,
                    "after_news=" + afterNews + " < floor=" + floor
                            + " (multiplier=" + ctx.newsMultiplier() + ")");
        }

        // Final clamp again to be safe vs the cap.
        int contracts = clamp(afterNews, floor, effectiveCap);
        return new SizingDecision(contracts, SkipReason.OK,
                "raw=" + raw + " tierCap=" + tierCap + " topstepCap=" + topstepCap);
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
