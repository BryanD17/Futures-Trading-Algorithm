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
}
