package com.topstep.trading.trade;

import com.topstep.trading.confluence.ConfluenceField;
import com.topstep.trading.confluence.ConfluenceSnapshot;
import com.topstep.trading.confluence.Tri;
import com.topstep.trading.strategy.MarketBias;
import com.topstep.trading.strategy.stdvote.SetupContext;
import com.topstep.trading.strategy.stdvote.TradeableInstrument;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Evaluates the STANDARD and MINIMAL required-confluence sets (V4 Agent 08).
 *
 * <p>STRICT is deliberately absent: it is not re-implemented here, it IS the
 * existing M1..M9 chain. This class is only reached for the other two, and the
 * simulator feeds it STRICT's own result so nothing is computed twice.
 *
 * <h2>Why nothing here calls a gate object</h2>
 * The M2b evaluator and the M7b gate maintain LOG-mode COUNTERS that the owner
 * reads to decide unrelated flips. Calling them a second time per evaluation
 * would inflate those counters and quietly corrupt evidence for a different
 * decision. So this evaluator reads only pure state: the setup context, the
 * instrument spec, the confluence snapshot, and STRICT's own verdict.
 *
 * <h2>How M2b is honoured without re-running it</h2>
 * The gate chain has a fixed order, so STRICT's failing gate says exactly how
 * far it got. If STRICT failed AFTER M2b, M2b passed. If it failed AT M2b, M2b
 * rejected. If it failed BEFORE M2b, M2b was never evaluated — and "no verdict"
 * is precisely the ABSTAIN case, which the doctrine says always passes. No
 * second call, no double counting, no invented answer.
 */
public final class ProfileEvaluator {

    /** The mandatory chain's order — the basis of the reachability argument. */
    private static final List<String> GATE_ORDER = List.of(
            "M1", "M2", "M2b", "M3", "M4", "M5", "M6", "M7", "M7b", "M8", "M9");

    private ProfileEvaluator() {}

    /**
     * @param profile   STANDARD or MINIMAL (STRICT returns {@code strictPassed})
     * @param ctx       the in-flight setup
     * @param snapshot  the confluence stack for the setup's direction
     * @param strictFailedGate the gate STRICT stopped at, or null if it passed
     */
    public static ProfileDecision evaluate(TradeProfile profile, SetupContext ctx,
                                           ConfluenceSnapshot snapshot,
                                           String strictFailedGate) {
        if (profile == TradeProfile.STRICT) {
            return new ProfileDecision(TradeProfile.STRICT, strictFailedGate == null,
                    strictFailedGate == null ? List.of() : List.of(strictFailedGate));
        }
        List<String> blocking = new ArrayList<>();

        // ── Always, both profiles: instrument, killzone, size, risk pre-flight ──
        Optional<TradeableInstrument.Symbol> sym = (ctx == null || ctx.symbol == null)
                ? Optional.empty() : TradeableInstrument.resolve(ctx.symbol);
        if (sym.isEmpty()) {
            // Nothing else is meaningful without an instrument spec.
            return new ProfileDecision(profile, false, List.of("M1:instrument"));
        }
        TradeableInstrument.Spec spec = TradeableInstrument.of(sym.get());

        // EVERY profile requires the TRADING killzone. A profile that could
        // trade outside it would be a bug, not a looser setting (Appendix J).
        if (!ctx.killzoneOpen) blocking.add("killzone");

        if (ctx.sweep == null) blocking.add("sweep");

        boolean bullish = directionOf(ctx);

        if (profile == TradeProfile.STANDARD) {
            // Bias: legacy OR the V3 vote, whichever is aligned.
            if (!isTrue(snapshot, ConfluenceField.HTF_BIAS_ALIGNED)
                    && !isTrue(snapshot, ConfluenceField.VOTE_BIAS_ALIGNED)) {
                blocking.add("bias");
            }
            // The instrument's own raid base — never the scalp floor.
            if (ctx.raidScore < spec.raidMinQuality()) {
                blocking.add("raidScore<" + spec.raidMinQuality());
            }
            // A PD array to enter against: an ictlib gap OR an order-block zone.
            if (!isTrue(snapshot, ConfluenceField.ACTIVE_FVG_IN_DIRECTION)
                    && !isTrue(snapshot, ConfluenceField.PRICE_INSIDE_FVG)
                    && !isTrue(snapshot, ConfluenceField.NEAREST_OB_ZONE)) {
                blocking.add("pdArray");
            }
        }

        // Structure: the gate's own MSS, or ictlib's structure read.
        if (!ctx.mss && !isTrue(snapshot, ConfluenceField.STRUCTURE_STATE)) {
            blocking.add("structure");
        }

        // OTE band touch: the machine's leg, or the 30m chart zone.
        boolean machineOte = ctx.ote != null && ctx.ote.contains(ctx.entry);
        if (!machineOte && !isTrue(snapshot, ConfluenceField.CHART_OTE_STATE)) {
            blocking.add("oteBand");
        }

        // M2b semantics unchanged (STANDARD only — MINIMAL's set does not
        // include it, per the profile definitions).
        if (profile == TradeProfile.STANDARD && m2bRejected(strictFailedGate)) {
            blocking.add("M2b");
        }

        // ── RISK-ADJACENT GATES: IDENTICAL IN EVERY PROFILE ────────────────
        // M8 sizing bounds and M9 risk pre-flight are evaluated here exactly as
        // the STRICT chain evaluates them. A profile cannot skip, widen or
        // reorder them, which is the whole of risk G-R2 in four lines.
        if (ctx.sizeRequest < spec.minMicros()) blocking.add("M8:size<min");
        if (ctx.sizeRequest > spec.maxMicros()) blocking.add("M8:size>max");
        if (ctx.lastGateFailed != null) blocking.add("M9:riskPreflight");

        return new ProfileDecision(profile, blocking.isEmpty(), blocking);
    }

    /** Direction the setup is working toward. */
    public static boolean directionOf(SetupContext ctx) {
        if (ctx == null) return true;
        if (ctx.ote != null) return ctx.ote.bullish();
        if (ctx.htfBias == MarketBias.BULLISH) return true;
        if (ctx.htfBias == MarketBias.BEARISH) return false;
        return ctx.legBullish;
    }

    private static boolean isTrue(ConfluenceSnapshot snapshot, ConfluenceField field) {
        return snapshot != null && snapshot.values().getOrDefault(field, Tri.UNKNOWN).isTrue();
    }

    /**
     * True only when STRICT actually reached M2b and it REJECTED. Never
     * reaching it is the ABSTAIN case, which passes.
     */
    static boolean m2bRejected(String strictFailedGate) {
        return "M2b".equals(strictFailedGate);
    }

    /** True when STRICT got at least as far as {@code gate}. */
    static boolean reached(String strictFailedGate, String gate) {
        if (strictFailedGate == null) return true;                 // passed everything
        int failedAt = GATE_ORDER.indexOf(strictFailedGate);
        int target = GATE_ORDER.indexOf(gate);
        if (failedAt < 0 || target < 0) return false;
        return failedAt >= target;
    }
}
