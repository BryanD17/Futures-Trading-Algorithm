package com.topstep.trading.strategy;

/**
 * Automatically selects the optimal TradeTierVariant based on market conditions.
 *
 * SELECTION FACTORS:
 * 1. Market Condition Score (-5 to +5)
 * 2. Raid Quality Score (5-10)
 * 3. Killzone Phase (EARLY, MID, LATE)
 * 4. DLL Proximity (% of daily loss limit used)
 * 5. ATR Ratio (volatility spike detection)
 * 6. HTF Momentum (STRONG, MODERATE, WEAK)
 *
 * The selector uses conservative defaults and upgrades to aggressive variants
 * only when multiple positive factors align. This protects capital while
 * allowing the system to pursue higher R-multiples in optimal conditions.
 */
public class VariantSelector {

    /**
     * Killzone phase enumeration.
     */
    public enum KillzonePhase {
        EARLY,      // First 33% of killzone
        MID,        // Middle 33% of killzone
        LATE,       // Final 33% of killzone
        OUTSIDE     // Not in killzone
    }

    /**
     * HTF momentum classification.
     */
    public enum HtfMomentum {
        STRONG,     // Clear trending on HTF
        MODERATE,   // Mixed signals
        WEAK        // Counter-trend or no momentum
    }

    /**
     * Input context for variant selection.
     */
    public static class SelectionContext {
        public final int baseTierLevel;           // 2, 3, or 4
        public final int marketConditionScore;    // -5 to +5
        public final int raidQualityScore;        // 5 to 10
        public final KillzonePhase killzonePhase;
        public final double dllProximityPercent;  // 0-100 (% of DLL used)
        public final double atrRatio;             // Current ATR / Avg ATR
        public final HtfMomentum htfMomentum;
        public final String instrument;           // For instrument-specific logic

        public SelectionContext(int baseTierLevel, int marketConditionScore, int raidQualityScore,
                               KillzonePhase killzonePhase, double dllProximityPercent,
                               double atrRatio, HtfMomentum htfMomentum, String instrument) {
            this.baseTierLevel = baseTierLevel;
            this.marketConditionScore = marketConditionScore;
            this.raidQualityScore = raidQualityScore;
            this.killzonePhase = killzonePhase;
            this.dllProximityPercent = dllProximityPercent;
            this.atrRatio = atrRatio;
            this.htfMomentum = htfMomentum;
            this.instrument = instrument;
        }
    }

    /**
     * Result of variant selection with reasoning.
     */
    public static class SelectionResult {
        public final TradeTierVariant variant;
        public final String reason;
        public final boolean isConservative;

        public SelectionResult(TradeTierVariant variant, String reason, boolean isConservative) {
            this.variant = variant;
            this.reason = reason;
            this.isConservative = isConservative;
        }
    }

    /**
     * Select the optimal variant for the given context.
     */
    public static SelectionResult selectVariant(SelectionContext ctx) {
        switch (ctx.baseTierLevel) {
            case 2:
                return selectTier2Variant(ctx);
            case 3:
                return selectTier3Variant(ctx);
            case 4:
                return selectTier4Variant(ctx);
            default:
                // Default to most conservative
                return new SelectionResult(TradeTierVariant.TIER_2A_SCALP,
                    "Unknown tier level, defaulting to conservative", true);
        }
    }

    /**
     * Select Tier 2 variant.
     *
     * Decision tree:
     * - Force SCALP if: DLL > 70%, late killzone, high volatility (ATR > 1.5)
     * - Otherwise: STANDARD with trailing
     */
    private static SelectionResult selectTier2Variant(SelectionContext ctx) {
        // Force conservative if:
        // - DLL proximity > 70%
        // - Late in killzone
        // - High volatility spike (ATR ratio > 1.5)
        // - Outside killzone entirely
        if (ctx.dllProximityPercent > 70) {
            return new SelectionResult(TradeTierVariant.TIER_2A_SCALP,
                String.format("DLL proximity %.0f%% > 70%% - protecting capital", ctx.dllProximityPercent),
                true);
        }

        if (ctx.killzonePhase == KillzonePhase.LATE || ctx.killzonePhase == KillzonePhase.OUTSIDE) {
            return new SelectionResult(TradeTierVariant.TIER_2A_SCALP,
                "Late/outside killzone - quick exit preferred",
                true);
        }

        if (ctx.atrRatio > 1.5) {
            return new SelectionResult(TradeTierVariant.TIER_2A_SCALP,
                String.format("High volatility (ATR ratio %.2f) - reducing exposure", ctx.atrRatio),
                true);
        }

        // Otherwise use standard with trailing
        return new SelectionResult(TradeTierVariant.TIER_2B_STANDARD,
            "Normal conditions - standard managed exit with trail",
            false);
    }

    /**
     * Select Tier 3 variant.
     *
     * Decision tree:
     * - Force SECURE if: DLL > 60%, late killzone, market condition < 1, weak HTF
     * - Use RUNNER if: Raid quality >= 7, not late, HTF not weak
     * - Default: SECURE
     */
    private static SelectionResult selectTier3Variant(SelectionContext ctx) {
        // Force conservative if:
        // - DLL proximity > 60%
        // - Late in killzone
        // - Market condition < 1
        // - Weak HTF momentum
        if (ctx.dllProximityPercent > 60) {
            return new SelectionResult(TradeTierVariant.TIER_3A_SECURE,
                String.format("DLL proximity %.0f%% > 60%% - securing profits", ctx.dllProximityPercent),
                true);
        }

        if (ctx.killzonePhase == KillzonePhase.LATE) {
            return new SelectionResult(TradeTierVariant.TIER_3A_SECURE,
                "Late killzone - secure exit",
                true);
        }

        if (ctx.marketConditionScore < 1) {
            return new SelectionResult(TradeTierVariant.TIER_3A_SECURE,
                String.format("Market condition %d < 1 - conservative approach", ctx.marketConditionScore),
                true);
        }

        // Use aggressive/runner if:
        // - Raid quality >= 7
        // - Not late in killzone
        // - HTF momentum not weak
        if (ctx.raidQualityScore >= 7 &&
            ctx.killzonePhase != KillzonePhase.LATE &&
            ctx.htfMomentum != HtfMomentum.WEAK) {
            return new SelectionResult(TradeTierVariant.TIER_3B_RUNNER,
                String.format("High quality setup (raid %d, HTF %s) - runner with trail",
                    ctx.raidQualityScore, ctx.htfMomentum),
                false);
        }

        // Default to secure
        return new SelectionResult(TradeTierVariant.TIER_3A_SECURE,
            "Standard premium conditions - secure exit",
            true);
    }

    /**
     * Select Tier 4 variant.
     *
     * Decision tree:
     * - Force MANAGED if: DLL > 50%, high volatility (ATR > 1.3)
     * - Use HOMERUN if: Raid >= 8, market >= 3, HTF STRONG, early/mid killzone
     * - Default: MANAGED
     */
    private static SelectionResult selectTier4Variant(SelectionContext ctx) {
        // Force managed (lenient trail) if:
        // - DLL proximity > 50%
        // - High volatility
        if (ctx.dllProximityPercent > 50) {
            return new SelectionResult(TradeTierVariant.TIER_4A_MANAGED,
                String.format("DLL proximity %.0f%% > 50%% - managed exit", ctx.dllProximityPercent),
                true);
        }

        if (ctx.atrRatio > 1.3) {
            return new SelectionResult(TradeTierVariant.TIER_4A_MANAGED,
                String.format("Elevated volatility (ATR ratio %.2f) - lenient trail", ctx.atrRatio),
                true);
        }

        // Use home run if:
        // - Raid quality >= 8
        // - Market condition >= 3 (FAVORABLE)
        // - HTF momentum STRONG
        // - Early or mid killzone
        if (ctx.raidQualityScore >= 8 &&
            ctx.marketConditionScore >= 3 &&
            ctx.htfMomentum == HtfMomentum.STRONG &&
            (ctx.killzonePhase == KillzonePhase.EARLY || ctx.killzonePhase == KillzonePhase.MID)) {
            return new SelectionResult(TradeTierVariant.TIER_4B_HOMERUN,
                String.format("Elite conditions (raid %d, market %d, HTF STRONG) - home run mode",
                    ctx.raidQualityScore, ctx.marketConditionScore),
                false);
        }

        // Default to managed
        return new SelectionResult(TradeTierVariant.TIER_4A_MANAGED,
            "Standard elite conditions - managed exit with lenient trail",
            false);
    }

    /**
     * Get instrument-specific trail distance multiplier.
     * Accounts for different volatility characteristics.
     *
     * Based on typical ATR behavior and stop hunt frequency:
     * - GC/MGC (Gold): Very volatile, frequent stop hunts → 1.25x
     * - NQ/MNQ/ES/MES: Standard index behavior → 1.00x
     */
    public static double getInstrumentTrailMultiplier(String instrument) {
        if (instrument == null) return 1.0;

        String symbol = instrument.toUpperCase();

        // Gold futures
        if (symbol.contains("GC")) return 1.25;

        // Index futures (standard)
        if (symbol.contains("NQ") || symbol.contains("ES")) return 1.00;

        // Default for unknown instruments
        return 1.00;
    }

    /**
     * Get recommended breakeven buffer based on instrument.
     * This buffer accounts for commissions and slippage.
     *
     * @param instrument The instrument symbol
     * @param riskDistance The initial risk distance in points
     * @return Buffer distance in points
     */
    public static double getBreakevenBuffer(String instrument, double riskDistance) {
        // Standard buffer is 5% of risk
        double baseBuffer = riskDistance * 0.05;

        // Adjust for instrument-specific commission costs
        if (instrument == null) return baseBuffer;

        String symbol = instrument.toUpperCase();

        // Metals have higher tick values, smaller buffer needed
        if (symbol.contains("GC") || symbol.contains("SI")) {
            return baseBuffer * 0.75;
        }

        return baseBuffer;
    }
}
