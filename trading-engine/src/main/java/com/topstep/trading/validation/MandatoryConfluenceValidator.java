package com.topstep.trading.validation;

import com.topstep.trading.chartstate.ChartStateQueryAPI;
import com.topstep.trading.chartstate.LiquidityRaid;
import com.topstep.trading.chartstate.RaidDirection;
import com.topstep.trading.chartstate.RaidQualityScorer;
import com.topstep.trading.strategy.DisplacementDetector;
import com.topstep.trading.strategy.HtfConfirmationResult;
import com.topstep.trading.strategy.HtfTrendAnalyzer;
import com.topstep.trading.strategy.HtfTrendAnalyzer.HtfTrendState;
import com.topstep.trading.strategy.MarketBias;
import com.topstep.trading.strategy.MultiTimeframeAnalyzer;
import com.topstep.trading.strategy.TradeTier;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Mandatory Confluence Validator - Enforces ALL required confluences for trade entry.
 *
 * Based on real trade analysis comparing winning vs losing setups, this validator
 * enforces 7 MANDATORY requirements:
 *
 * 1. HTF TREND DIRECTION: HTF trend must allow this trade direction (Layer 1 of cascade)
 * 2. BIAS/SWEEP ALIGNMENT: Bias and sweep must be opposite directions
 * 3. CONFIRMED DISPLACEMENT: Displacement must be confirmed (not partial)
 * 4. RAID QUALITY ≥ 5: Must have valid liquidity raid with score ≥ 5/10
 * 5. HTF CONFIRMATION: Must have higher timeframe confirmation
 * 6. MARKET CONDITION ≥ 0: Market condition score must be non-negative
 * 7. NOT PROMOTED: Trade must have natural confluence, not artificially promoted
 *
 * ALL checks must pass. If ANY check fails, the trade is REJECTED.
 *
 * USAGE:
 * <pre>
 * MandatoryConfluenceValidator validator = new MandatoryConfluenceValidator(...);
 * ValidationResult result = validator.validateEntry(...);
 * if (result.passed()) {
 *     // Generate trade signal
 * } else {
 *     // Log rejection reasons
 *     log.warn(result.getSummary());
 * }
 * </pre>
 */
public class MandatoryConfluenceValidator {

    // Minimum raid quality score required for entry (5/10)
    public static final int MINIMUM_RAID_QUALITY = 5;

    // Minimum market condition score required for entry (0 = NORMAL or better)
    public static final int MINIMUM_MARKET_CONDITION = 0;

    private final MultiTimeframeAnalyzer mtfAnalyzer;
    private final DisplacementDetector displacementDetector;
    private final ChartStateQueryAPI chartState;
    private final HtfTrendAnalyzer htfTrendAnalyzer;  // Layer 1 cascade gate

    /**
     * Active RiskLimits whose signal band ({@code getSignalMinRr()} /
     * {@code getSignalMaxRr()}) drives the M7 RR check in
     * {@link #validateStdvOte}. Null → the historical hardcoded band
     * [{@link #MIN_RR_FLOOR_STDV_OTE}, +infinity) applies, which is also
     * exactly the band the legacy profiles carry — legacy emission is
     * identical whether or not topstep50k() is injected.
     */
    private volatile com.topstep.trading.domain.RiskLimits activeRiskLimits;

    /** Inject the active RiskLimits so M7 reads its signal RR band. */
    public void setActiveRiskLimits(com.topstep.trading.domain.RiskLimits limits) {
        this.activeRiskLimits = limits;
    }

    /**
     * Premium/discount evaluator behind the M2b gate (V3 Agent 02). Null →
     * M2b is not evaluated at all (pre-V3 behavior, byte-identical); the
     * evaluator's own OFF mode is likewise a cheap no-op. Volatile for the
     * same wiring pattern as {@link #activeRiskLimits}.
     */
    private volatile com.topstep.trading.strategy.stdvote.PremiumDiscountEvaluator pdEvaluator;

    /** Inject the M2b premium/discount evaluator (null disables the gate). */
    public void setPremiumDiscountEvaluator(
            com.topstep.trading.strategy.stdvote.PremiumDiscountEvaluator evaluator) {
        this.pdEvaluator = evaluator;
    }

    /**
     * M7b 30m-OTE confluence gate (V3 Agent 06). Null → not evaluated
     * (pre-V3 behavior); the gate's own OFF mode is likewise a no-op.
     */
    private volatile com.topstep.trading.strategy.stdvote.Ote30mConfluenceGate ote30mGate;

    /** Inject the M7b 30m-OTE confluence gate (null disables it). */
    public void setOte30mConfluenceGate(
            com.topstep.trading.strategy.stdvote.Ote30mConfluenceGate gate) {
        this.ote30mGate = gate;
    }

    public MandatoryConfluenceValidator(MultiTimeframeAnalyzer mtfAnalyzer,
                                        DisplacementDetector displacementDetector,
                                        ChartStateQueryAPI chartState) {
        this(mtfAnalyzer, displacementDetector, chartState, null);
    }

    public MandatoryConfluenceValidator(MultiTimeframeAnalyzer mtfAnalyzer,
                                        DisplacementDetector displacementDetector,
                                        ChartStateQueryAPI chartState,
                                        HtfTrendAnalyzer htfTrendAnalyzer) {
        this.mtfAnalyzer = mtfAnalyzer;
        this.displacementDetector = displacementDetector;
        this.chartState = chartState;
        this.htfTrendAnalyzer = htfTrendAnalyzer;
    }

    /**
     * Validates all mandatory confluences for trade entry.
     *
     * Returns ValidationResult with pass/fail and detailed reasons.
     *
     * @param symbol The instrument symbol
     * @param bias The market bias (BULLISH or BEARISH)
     * @param sweep The raid direction (HIGH_SWEEP or LOW_SWEEP)
     * @param isBullish True for bullish entry, false for bearish
     * @param marketConditionScore The market condition score
     * @param wasPromoted True if trade was promoted to higher tier
     * @return ValidationResult with pass/fail and detailed reasons
     */
    public ValidationResult validateEntry(
            String symbol,
            MarketBias bias,
            RaidDirection sweep,
            boolean isBullish,
            int marketConditionScore,
            boolean wasPromoted) {
        return validateEntry(symbol, bias, sweep, isBullish, marketConditionScore, wasPromoted, false);
    }

    /**
     * Validates all mandatory confluences for trade entry.
     * Overloaded version that accepts AMD override flag.
     *
     * @param amdOverride True if AMD override is active (HTF gate bypassed)
     */
    public ValidationResult validateEntry(
            String symbol,
            MarketBias bias,
            RaidDirection sweep,
            boolean isBullish,
            int marketConditionScore,
            boolean wasPromoted,
            boolean amdOverride) {

        List<String> failures = new ArrayList<>();
        List<String> confirmations = new ArrayList<>();

        // ═══════════════════════════════════════════════════════════════
        // CHECK 0: HTF Trend Direction (MANDATORY — Layer 1 of cascade)
        // Skipped when AMD override is active (sweep + displacement confirmed)
        // ═══════════════════════════════════════════════════════════════
        if (htfTrendAnalyzer != null && !amdOverride) {
            HtfTrendState trendState = htfTrendAnalyzer.getTrendState();
            if (!htfTrendAnalyzer.allowsDirection(isBullish)) {
                failures.add(String.format("✗ HTF Trend blocks %s. State=%s. Trading WITH the trend is mandatory.",
                        isBullish ? "LONGS" : "SHORTS", trendState.getDisplayName()));
            } else {
                confirmations.add(String.format("★ HTF Trend: %s (allows %s, size=%.0f%%)",
                        trendState.getDisplayName(), isBullish ? "longs" : "shorts",
                        trendState.getSizeMultiplier() * 100));
            }
        } else if (amdOverride) {
            confirmations.add("★ AMD Override active — HTF gate bypassed (sweep + displacement confirmed)");
        }

        // ═══════════════════════════════════════════════════════════════
        // CHECK 1: Bias/Sweep Alignment (MANDATORY)
        // ═══════════════════════════════════════════════════════════════
        if (!TradeEntryValidator.validateBiasSweepAlignment(bias, sweep)) {
            failures.add(TradeEntryValidator.getBiasSweepRejectionReason(bias, sweep));
        } else {
            confirmations.add(String.format("✓ Bias/Sweep aligned: %s/%s (opposite directions)", bias, sweep));
        }

        // ═══════════════════════════════════════════════════════════════
        // CHECK 2: Displacement Confirmation (MANDATORY)
        // ═══════════════════════════════════════════════════════════════
        boolean hasDisplacement = displacementDetector.hasRecentDisplacement(5, isBullish);
        if (!hasDisplacement) {
            failures.add("✗ Displacement not confirmed. No recent displacement detected in trade direction.");
        } else {
            DisplacementDetector.Displacement disp = displacementDetector.getLastDisplacement();
            confirmations.add(String.format("✓ Displacement: Confirmed (%.2f pts over %d candles)",
                    disp.getMoveSize(), disp.getCandleCount()));
        }

        // ═══════════════════════════════════════════════════════════════
        // CHECK 3: Raid Quality Score ≥ 5 (MANDATORY)
        // ═══════════════════════════════════════════════════════════════
        Optional<LiquidityRaid> raidOpt = chartState.getBestActiveRaid();
        if (raidOpt.isEmpty()) {
            failures.add("✗ No liquidity raid detected. Raid quality ≥ 5 required.");
        } else {
            LiquidityRaid raid = raidOpt.get();
            int qualityScore = raid.getQualityScore();
            if (qualityScore < MINIMUM_RAID_QUALITY) {
                failures.add(String.format(
                        "✗ Raid quality %d/10 (%s) is below minimum (%d). Factors: %s",
                        qualityScore, raid.getQualityClassification(), MINIMUM_RAID_QUALITY,
                        String.join(", ", raid.getQualityFactors())));
            } else {
                confirmations.add(String.format(
                        "✓ ★ Raid Quality: %d/10 (%s) @ %s",
                        qualityScore, raid.getQualityClassification(),
                        raid.getTargetLevel().getType().getDisplayName()));
            }
        }

        // ═══════════════════════════════════════════════════════════════
        // CHECK 4: HTF Confirmation (MANDATORY)
        // ═══════════════════════════════════════════════════════════════
        HtfConfirmationResult htf = mtfAnalyzer.checkHtfConfirmation(symbol, isBullish);
        if (!htf.isConfirmed()) {
            failures.add("✗ No HTF confirmation. Requires: 15mFVG, 5mFVG, 5mOB, HTF_MSS, or HTF_BIAS.");
        } else {
            confirmations.add(String.format("✓ HTF: %s (%s)", htf.getConfirmationType(), htf.getDetails()));
        }

        // ═══════════════════════════════════════════════════════════════
        // CHECK 5: Market Condition ≥ 0 (MANDATORY)
        // ═══════════════════════════════════════════════════════════════
        if (!TradeEntryValidator.validateMarketCondition(marketConditionScore, MINIMUM_MARKET_CONDITION)) {
            failures.add(TradeEntryValidator.getMarketConditionRejectionReason(
                    marketConditionScore, MINIMUM_MARKET_CONDITION));
        } else {
            String conditionName = marketConditionScore >= 4 ? "OPTIMAL" :
                                   marketConditionScore >= 2 ? "FAVORABLE" : "NORMAL";
            confirmations.add(String.format("✓ Market: %s (score: %d)", conditionName, marketConditionScore));
        }

        // ═══════════════════════════════════════════════════════════════
        // CHECK 6: Not Promoted (MANDATORY)
        // ═══════════════════════════════════════════════════════════════
        if (!TradeEntryValidator.validateNotPromoted(wasPromoted)) {
            failures.add("✗ [PROMOTED] trades are rejected. Natural confluence required.");
        } else {
            confirmations.add("✓ Organic confluence (not promoted)");
        }

        // ═══════════════════════════════════════════════════════════════
        // BUILD RESULT
        // ═══════════════════════════════════════════════════════════════
        boolean passed = failures.isEmpty();

        if (passed) {
            String summary = buildApprovalSummary(symbol, confirmations);
            return ValidationResult.pass(confirmations, summary);
        } else {
            String summary = buildRejectionSummary(symbol, failures);
            return ValidationResult.fail(failures, summary);
        }
    }

    /**
     * Build approval summary for logging.
     */
    private String buildApprovalSummary(String symbol, List<String> confirmations) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[%s] ✅ ENTRY APPROVED - All mandatory confluences confirmed:%n", symbol));
        for (String conf : confirmations) {
            sb.append("  ").append(conf).append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * Build rejection summary for logging.
     */
    private String buildRejectionSummary(String symbol, List<String> failures) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[%s] ❌ ENTRY REJECTED - Failed mandatory confluences:%n", symbol));
        for (String failure : failures) {
            sb.append("  ").append(failure).append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * Get the minimum raid quality for a specific tier (FIX 6: tiered minimums).
     * Tier 4 (Elite) requires quality >= 6
     * Tier 3 (Premium) requires quality >= 5
     * Tier 2 (Standard) requires quality >= 3
     * Tier 1 requires quality >= 3
     */
    public static int getMinimumRaidQuality(TradeTier tier) {
        if (tier == null) return MINIMUM_RAID_QUALITY;
        switch (tier) {
            case TIER_4: return 6;
            case TIER_3: return 5;
            case TIER_2: return 3;
            case TIER_1: return 3;
            default: return 5;
        }
    }

    /**
     * Quick check if raid quality meets minimum threshold.
     */
    public boolean raidMeetsMinimumQuality() {
        Optional<LiquidityRaid> raid = chartState.getBestActiveRaid();
        return raid.isPresent() && raid.get().getQualityScore() >= MINIMUM_RAID_QUALITY;
    }

    /**
     * Quick check if raid quality meets tiered minimum threshold (FIX 6).
     */
    public boolean raidMeetsMinimumQuality(TradeTier tier) {
        int minQuality = getMinimumRaidQuality(tier);
        Optional<LiquidityRaid> raid = chartState.getBestActiveRaid();
        return raid.isPresent() && raid.get().getQualityScore() >= minQuality;
    }

    /**
     * Quick check if HTF confirmation is present.
     */
    public boolean hasHtfConfirmation(String symbol, boolean isBullish) {
        HtfConfirmationResult htf = mtfAnalyzer.checkHtfConfirmation(symbol, isBullish);
        return htf.isConfirmed();
    }

    /**
     * Quick check if displacement is confirmed.
     */
    public boolean hasConfirmedDisplacement(boolean isBullish) {
        return displacementDetector.hasRecentDisplacement(5, isBullish);
    }

    // ════════════════════════════════════════════════════════════════════
    // STDV+OTE refactor — sequential mandatory-gate validator (M1..M9)
    // ════════════════════════════════════════════════════════════════════

    /**
     * Validate a STDV+OTE setup against the mandatory M1..M9 gates from
     * {@code STDV_OTE_MODEL.md}. Short-circuits on the first failing gate;
     * the failing gate's identifier (e.g. {@code "M3"}) is recorded in the
     * returned result's failures list AND in its summary.
     *
     * <p>Optional confluences (O1..O8) are <strong>not</strong> evaluated
     * here — they belong to tier/size computation in
     * {@code StdvOteStrategy.computeTier()}. This validator only answers
     * "may we emit an order at all?"
     *
     * <p>Gate sequence (each blocks the next):
     * <ol>
     *   <li>M1 — instrument is MNQ/MES/MGC.</li>
     *   <li>M2 — HTF bias is not NEUTRAL AND trade direction matches.</li>
     *   <li>M3 — inside a killzone.</li>
     *   <li>M4 — liquidity sweep present AND raid score >= instrument minimum.</li>
     *   <li>M5 — displacement candle AND a FairValueGap present.</li>
     *   <li>M6 — Market Structure Shift / CHoCH confirmed.</li>
     *   <li>M7 — OTE zone built, PD-array edge inside the band, and
     *            geometry yields RR &gt;= {@link #MIN_RR_FLOOR_STDV_OTE} at the
     *            -2.0 target.</li>
     *   <li>M8 — size request &gt;= instrument minimum (5 micros).</li>
     *   <li>M9 — caller has set {@code ctx.lastGateFailed == null} after the
     *            risk engine review (the validator does not call the risk
     *            engine directly; it trusts the strategy's pre-flight).</li>
     * </ol>
     *
     * @param ctx the in-flight setup context
     * @return {@link ValidationResult} with {@code passed = true} when every
     *         gate is satisfied, otherwise {@code passed = false} with the
     *         failed gate id ({@code "M1".."M9"}) as the summary
     */
    public ValidationResult validateStdvOte(com.topstep.trading.strategy.stdvote.SetupContext ctx) {
        // ═══════════════════════════════════════════════════════════════════
        // THE PROFILE SEAM (V4 Agent 08) — the ONE place gating is
        // parameterised. Everything above and below this method is unaware
        // that profiles exist.
        //
        // STRICT is the default and is BYTE-IDENTICAL: it returns the chain's
        // own result, unmodified, from the same call that always ran. The
        // chain is evaluated FIRST and EXACTLY ONCE in every profile, which
        // matters for more than tidiness — M2b and M7b keep LOG-mode counters,
        // and evaluating them twice would inflate evidence the owner reads to
        // make a different decision.
        //
        // The simulator then judges ALL profiles, always, and records what the
        // others would have done. It returns void; nothing below reads it.
        // ═══════════════════════════════════════════════════════════════════
        ValidationResult strict = validateStdvOteStrict(ctx);
        com.topstep.trading.trade.TradeProfile active =
                com.topstep.trading.trade.TradeProfile.active();

        ValidationResult decided = strict;
        if (active != com.topstep.trading.trade.TradeProfile.STRICT) {
            com.topstep.trading.confluence.ConfluenceSnapshot snapshot =
                    com.topstep.trading.trade.ProfileSimulator.snapshotFor(
                            confluenceService, ctx == null ? null : ctx.symbol, ctx);
            com.topstep.trading.trade.ProfileDecision d =
                    com.topstep.trading.trade.ProfileEvaluator.evaluate(
                            active, ctx, snapshot, failedGateOf(strict));
            decided = d.satisfied()
                    ? ValidationResult.pass(java.util.List.of(active + " satisfied"),
                            active + " profile satisfied")
                    : ValidationResult.fail(d.blocking(), String.join(",", d.blocking()));
        }

        recordProfileSimulation(ctx, strict, decided.passed());
        return decided;
    }

    /**
     * The confluence stack, used ONLY to score the non-STRICT profiles and to
     * feed the simulator. Null → profiles score against an all-UNKNOWN stack,
     * which is the honest cold read. No gate reads this.
     */
    private volatile com.topstep.trading.confluence.ConfluenceService confluenceService;

    /** Inject the confluence stack (V4 Agent 08). */
    public void setConfluenceService(
            com.topstep.trading.confluence.ConfluenceService service) {
        this.confluenceService = service;
    }

    /** The gate id a failed chain stopped at ({@code "M4"}), or null if it passed. */
    private static String failedGateOf(ValidationResult result) {
        if (result == null || result.passed()) return null;
        return result.getSummary();
    }

    /**
     * Always-on measurement: judge every profile and record would-trade events.
     * Wrapped so a simulator fault can never take down the emission path — a
     * measurement that can break trading is not worth having.
     */
    private void recordProfileSimulation(com.topstep.trading.strategy.stdvote.SetupContext ctx,
                                         ValidationResult strict, boolean emitted) {
        if (ctx == null) return;
        try {
            com.topstep.trading.confluence.ConfluenceSnapshot snapshot =
                    com.topstep.trading.trade.ProfileSimulator.snapshotFor(
                            confluenceService, ctx.symbol, ctx);
            java.time.Instant at = (confluenceService == null) ? null
                    : confluenceService.factsFor(ctx.symbol).at();
            com.topstep.trading.trade.ProfileSimulator.forSymbol(ctx.symbol)
                    .evaluate(ctx, failedGateOf(strict), emitted, snapshot, at);
        } catch (RuntimeException e) {
            System.out.println("[PROFILE] simulator skipped an evaluation: " + e);
        }
    }

    /**
     * The mandatory M1..M9 chain — unchanged from V3, and the definition of
     * the STRICT profile.
     */
    private ValidationResult validateStdvOteStrict(
            com.topstep.trading.strategy.stdvote.SetupContext ctx) {
        java.util.List<String> confirmations = new java.util.ArrayList<>();

        if (ctx == null) {
            return ValidationResult.fail(java.util.List.of("M1: null context"), "M1");
        }

        // M1 — instrument tradeable.
        java.util.Optional<com.topstep.trading.strategy.stdvote.TradeableInstrument.Symbol> sym =
                com.topstep.trading.strategy.stdvote.TradeableInstrument.resolve(ctx.symbol);
        if (sym.isEmpty()) {
            return ValidationResult.fail(
                    java.util.List.of("M1: instrument '" + ctx.symbol + "' not in {MNQ,MES,MGC}"),
                    "M1");
        }
        com.topstep.trading.strategy.stdvote.TradeableInstrument.Spec spec =
                com.topstep.trading.strategy.stdvote.TradeableInstrument.of(sym.get());
        confirmations.add("M1: instrument=" + ctx.symbol);

        // M2 — bias non-neutral AND direction matches.
        if (ctx.htfBias == null
                || ctx.htfBias == com.topstep.trading.strategy.MarketBias.NEUTRAL) {
            return ValidationResult.fail(
                    java.util.List.of("M2: HTF bias is NEUTRAL"), "M2");
        }
        boolean biasBullish = (ctx.htfBias == com.topstep.trading.strategy.MarketBias.BULLISH);
        if (ctx.ote != null && ctx.ote.bullish() != biasBullish) {
            return ValidationResult.fail(
                    java.util.List.of("M2: trade direction mismatches HTF bias"), "M2");
        }
        confirmations.add("M2: bias=" + ctx.htfBias);

        // M2b — premium/discount: the proposed ENTRY price (a resting limit,
        // never the current tick) must sit at a DISCOUNT for longs / a
        // PREMIUM for shorts relative to the governing range's equilibrium.
        // LOG mode (default) counts + passes; BLOCK mode rejects; ABSTAIN
        // (missing/degenerate range) ALWAYS passes (Rollout Doctrine).
        com.topstep.trading.strategy.stdvote.PremiumDiscountEvaluator pd = pdEvaluator;
        if (pd != null) {
            com.topstep.trading.strategy.stdvote.PremiumDiscountEvaluator.GateDecision d =
                    pd.gateCheck(ctx.entry, biasBullish);
            if (!d.passed()) {
                return ValidationResult.fail(
                        java.util.List.of("M2b: " + d.reason()), "M2b");
            }
            confirmations.add("M2b: Premium/Discount (entry vs equilibrium) — "
                    + d.reason());
        }

        // M3 — killzone open.
        if (!ctx.killzoneOpen) {
            return ValidationResult.fail(
                    java.util.List.of("M3: outside killzone"), "M3");
        }
        confirmations.add("M3: killzone open");

        // M4 — sweep + raid score.
        if (ctx.sweep == null) {
            return ValidationResult.fail(
                    java.util.List.of("M4: no liquidity sweep"), "M4");
        }
        if (ctx.raidScore < spec.raidMinQuality()) {
            return ValidationResult.fail(
                    java.util.List.of("M4: raid score " + ctx.raidScore
                            + " < instrument minimum " + spec.raidMinQuality()), "M4");
        }
        confirmations.add("M4: raid score " + ctx.raidScore);

        // M5 — displacement + FVG.
        if (!ctx.displacement || ctx.fvg == null) {
            return ValidationResult.fail(
                    java.util.List.of("M5: displacement candle / FVG absent"), "M5");
        }
        confirmations.add("M5: displacement + FVG present");

        // M6 — MSS / CHoCH.
        if (!ctx.mss) {
            return ValidationResult.fail(
                    java.util.List.of("M6: MSS / CHoCH not confirmed"), "M6");
        }
        confirmations.add("M6: MSS confirmed");

        // M7 — entry geometry.
        if (ctx.ote == null) {
            return ValidationResult.fail(
                    java.util.List.of("M7: OTE zone not built"), "M7");
        }
        if (Double.isNaN(ctx.pdArrayInOte)) {
            return ValidationResult.fail(
                    java.util.List.of("M7: no PD array inside OTE band"), "M7");
        }
        if (!ctx.ote.contains(ctx.entry)) {
            return ValidationResult.fail(
                    java.util.List.of("M7: planned entry " + ctx.entry
                            + " not in OTE band [" + ctx.ote.f79() + "," + ctx.ote.f62() + "]"),
                    "M7");
        }
        // RR band from the ACTIVE RiskLimits' signal band when injected.
        // Legacy safety: with no RiskLimits injected the historical constants
        // apply — floor 2.0, no ceiling. RiskLimits' builder defaults carry
        // the very same [2.0, +infinity) band, so wiring topstep50k() through
        // here is behaviour-identical to the old hardcoded floor. Only the
        // scalp profile carries a different band ([0.8, 1.5]). The validator
        // deliberately does NOT read minRiskRewardRatio (3.0 on topstep50k),
        // which would have tightened legacy emission from 2.0 → 3.0.
        double rrFloor = (activeRiskLimits != null)
                ? activeRiskLimits.getSignalMinRr() : MIN_RR_FLOOR_STDV_OTE;
        double rrCeiling = (activeRiskLimits != null)
                ? activeRiskLimits.getSignalMaxRr() : Double.POSITIVE_INFINITY;
        if (ctx.rr < rrFloor) {
            return ValidationResult.fail(
                    java.util.List.of("M7: RR " + ctx.rr
                            + " < floor " + rrFloor), "M7");
        }
        if (ctx.rr > rrCeiling) {
            return ValidationResult.fail(
                    java.util.List.of("M7: RR " + ctx.rr
                            + " > ceiling " + rrCeiling), "M7");
        }
        confirmations.add("M7: in-zone, PD-array, RR=" + ctx.rr);

        // M7b — 30m OTE confluence (V3 Agent 06): the chart's zone for the
        // signal direction must be REACTED (GATE mode only; LOG counts and
        // passes; ABSTAIN on no-zone ALWAYS passes — Rollout Doctrine).
        com.topstep.trading.strategy.stdvote.Ote30mConfluenceGate m7b = ote30mGate;
        if (m7b != null) {
            com.topstep.trading.strategy.stdvote.Ote30mConfluenceGate.Decision d =
                    m7b.gateCheck(biasBullish);
            if (!d.passed()) {
                return ValidationResult.fail(
                        java.util.List.of("M7b: " + d.reason()), "M7b");
            }
            confirmations.add("M7b: 30m OTE confluence — " + d.reason());
        }

        // M8 — sized order at floor or above.
        if (ctx.sizeRequest < spec.minMicros()) {
            return ValidationResult.fail(
                    java.util.List.of("M8: size " + ctx.sizeRequest
                            + " < instrument floor " + spec.minMicros()), "M8");
        }
        if (ctx.sizeRequest > spec.maxMicros()) {
            return ValidationResult.fail(
                    java.util.List.of("M8: size " + ctx.sizeRequest
                            + " > instrument ceiling " + spec.maxMicros()), "M8");
        }
        confirmations.add("M8: size=" + ctx.sizeRequest);

        // M9 — risk pre-flight (the strategy is the gatekeeper; validator
        // trusts the pre-check via ctx.lastGateFailed staying null at this
        // point. Real risk engine integration lands in SA5.)
        if (ctx.lastGateFailed != null) {
            return ValidationResult.fail(
                    java.util.List.of("M9: risk pre-flight failed earlier (" + ctx.lastGateFailed + ")"),
                    "M9");
        }
        confirmations.add("M9: risk pre-flight clear");

        return ValidationResult.pass(confirmations, "STDV+OTE M1..M9 all passed");
    }

    /** Minimum reward-to-risk at the -2.0 STDV target for the M7 geometry gate. */
    public static final double MIN_RR_FLOOR_STDV_OTE = 2.0;
}
