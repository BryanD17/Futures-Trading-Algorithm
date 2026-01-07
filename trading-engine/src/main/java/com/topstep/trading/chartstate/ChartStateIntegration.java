package com.topstep.trading.chartstate;

import com.topstep.trading.domain.Candle;
import com.topstep.trading.strategy.LiquiditySweep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Integration helper for connecting ChartStateManager with existing strategies.
 *
 * This class provides bridging methods that convert between the existing
 * strategy components (LiquiditySweep, LiquidityDetector) and the new
 * ChartStateManager system (LiquidityRaid, RaidDetector).
 *
 * USAGE:
 * 1. Create integration in strategy constructor
 * 2. Call processCandle() on each candle
 * 3. Use getEnhancedSweepInfo() to get raid-enhanced sweep data
 * 4. Use getQualityBonus() to boost tier based on raid quality
 *
 * EXAMPLE:
 * <pre>
 * // In strategy constructor
 * this.chartIntegration = new ChartStateIntegration(symbol, smtSymbol);
 *
 * // On each candle
 * chartIntegration.processCandle(candle, hasSmtDivergence, htfBiasBullish);
 *
 * // When checking confluences
 * Optional<LiquidityRaid> raid = chartIntegration.getActiveBullishRaid();
 * if (raid.isPresent() && raid.get().getQualityScore() >= 6) {
 *     // High quality raid confirmed
 * }
 * </pre>
 */
public class ChartStateIntegration {

    private static final Logger log = LoggerFactory.getLogger(ChartStateIntegration.class);

    private final String primarySymbol;
    private final String smtSymbol;
    private final ChartStateManager chartStateManager;
    private final ChartStateQueryAPI queryAPI;

    /**
     * Create integration for a symbol pair.
     */
    public ChartStateIntegration(String primarySymbol, String smtSymbol, ChartStateManager sharedManager) {
        this.primarySymbol = primarySymbol.toUpperCase();
        this.smtSymbol = smtSymbol != null ? smtSymbol.toUpperCase() : null;
        this.chartStateManager = sharedManager != null ? sharedManager : new ChartStateManager();
        this.queryAPI = chartStateManager.getQueryAPI(primarySymbol);

        log.info("ChartStateIntegration initialized for {}", primarySymbol);
    }

    /**
     * Create integration with a new ChartStateManager.
     */
    public ChartStateIntegration(String primarySymbol, String smtSymbol) {
        this(primarySymbol, smtSymbol, new ChartStateManager());
    }

    // ═══════════════════════════════════════════════════════════════════
    // CANDLE PROCESSING
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Process a candle through the chart state system.
     * Should be called on every candle.
     *
     * @param candle The candle to process
     * @param hasSmtDivergence Whether SMT divergence is detected (from correlation tracker)
     * @param htfBiasBullish HTF bias direction (null = neutral)
     */
    public void processCandle(Candle candle, boolean hasSmtDivergence, Boolean htfBiasBullish) {
        if (candle == null) return;

        // Build context for raid scoring
        RaidDetector.RaidDetectionContext context = new RaidDetector.RaidDetectionContext(
                hasSmtDivergence, htfBiasBullish);

        // Process through chart state manager
        chartStateManager.processCandle(candle.getSymbol(), candle, context);
    }

    /**
     * Process candle without context (basic tracking).
     */
    public void processCandle(Candle candle) {
        processCandle(candle, false, null);
    }

    // ═══════════════════════════════════════════════════════════════════
    // RAID QUERIES
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Get active bullish raid (low sweep expecting price to go up).
     */
    public Optional<LiquidityRaid> getActiveBullishRaid() {
        return queryAPI.getActiveBullishRaid();
    }

    /**
     * Get active bearish raid (high sweep expecting price to go down).
     */
    public Optional<LiquidityRaid> getActiveBearishRaid() {
        return queryAPI.getActiveBearishRaid();
    }

    /**
     * Get the best active raid for entry.
     */
    public Optional<LiquidityRaid> getBestActiveRaid() {
        return queryAPI.getBestActiveRaid();
    }

    /**
     * Check if there's a high-quality raid for the expected direction.
     * High quality = score >= 6 (Premium or Elite).
     */
    public boolean hasHighQualityRaid(boolean expectBullish) {
        Optional<LiquidityRaid> raid = expectBullish ?
                getActiveBullishRaid() : getActiveBearishRaid();

        return raid.map(r -> r.getQualityScore() >= 6).orElse(false);
    }

    /**
     * Check if there's any active raid for the expected direction.
     */
    public boolean hasActiveRaid(boolean expectBullish) {
        return queryAPI.hasActiveRaidForDirection(expectBullish);
    }

    /**
     * Get quality bonus for tier calculation based on raid quality.
     *
     * This can be used to boost the trade tier:
     * - Score 8-10 (Elite): +2 bonus
     * - Score 6-7 (Premium): +1 bonus
     * - Score 4-5 (Standard): +0 bonus
     * - Score 1-3 (Low): -1 penalty (should probably skip)
     *
     * @param expectBullish Expected direction
     * @return Quality bonus/penalty
     */
    public int getQualityBonus(boolean expectBullish) {
        Optional<LiquidityRaid> raid = expectBullish ?
                getActiveBullishRaid() : getActiveBearishRaid();

        if (raid.isEmpty()) return 0;

        int score = raid.get().getQualityScore();
        if (score >= 8) return 2;   // Elite
        if (score >= 6) return 1;   // Premium
        if (score >= 4) return 0;   // Standard
        return -1;                   // Low quality
    }

    /**
     * Check if any raid is below minimum quality (should skip trade).
     */
    public boolean shouldSkipDueToLowQuality(boolean expectBullish) {
        Optional<LiquidityRaid> raid = expectBullish ?
                getActiveBullishRaid() : getActiveBearishRaid();

        // If no raid, don't skip (let other logic decide)
        if (raid.isEmpty()) return false;

        // If raid exists but is low quality, skip
        return raid.get().getQualityScore() < 4;
    }

    // ═══════════════════════════════════════════════════════════════════
    // SWEEP ENHANCEMENT
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Check if a sweep should be considered recent based on raid tracking.
     * This can replace the basic "hasRecentSweep(n)" check with more intelligent
     * raid lifecycle management.
     *
     * @param maxBarsAgo Maximum bars ago (for backward compat)
     * @param expectBullish Expected direction
     * @return true if there's an active raid within the timeframe
     */
    public boolean hasRecentQualifiedSweep(int maxBarsAgo, boolean expectBullish) {
        Optional<LiquidityRaid> raid = expectBullish ?
                getActiveBullishRaid() : getActiveBearishRaid();

        if (raid.isEmpty()) return false;

        // Check bars since raid
        LiquidityRaid r = raid.get();
        if (r.getBarsSinceRaid() > maxBarsAgo) return false;

        // Check minimum quality
        return r.getQualityScore() >= 4;  // At least Standard quality
    }

    /**
     * Convert a LiquidityRaid to enhanced sweep info for logging.
     */
    public String getRaidSummary(boolean expectBullish) {
        Optional<LiquidityRaid> raid = expectBullish ?
                getActiveBullishRaid() : getActiveBearishRaid();

        if (raid.isEmpty()) {
            return "No active " + (expectBullish ? "bullish" : "bearish") + " raid";
        }

        LiquidityRaid r = raid.get();
        return String.format("Raid: %s @ %s [Score=%d %s, Bars=%d]",
                r.getDirection().getDisplayName(),
                r.getTargetLevel().getType().getDisplayName(),
                r.getQualityScore(),
                r.getQualityClassification(),
                r.getBarsSinceRaid());
    }

    // ═══════════════════════════════════════════════════════════════════
    // LEVEL QUERIES
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Get PDH if available.
     */
    public Optional<Double> getPDH() {
        return queryAPI.getPDH();
    }

    /**
     * Get PDL if available.
     */
    public Optional<Double> getPDL() {
        return queryAPI.getPDL();
    }

    /**
     * Get PWH if available.
     */
    public Optional<Double> getPWH() {
        return queryAPI.getPWH();
    }

    /**
     * Get PWL if available.
     */
    public Optional<Double> getPWL() {
        return queryAPI.getPWL();
    }

    /**
     * Check if price is near a significant level.
     */
    public boolean isNearSignificantLevel(double price) {
        return !queryAPI.getLevelsNearPrice(price).isEmpty();
    }

    /**
     * Get description of nearest level for logging.
     */
    public String getNearestLevelDescription(double price, boolean lookAbove) {
        Optional<KnownLevel> level = lookAbove ?
                queryAPI.getNearestLevelAbove(price) :
                queryAPI.getNearestLevelBelow(price);

        if (level.isEmpty()) {
            return "No level " + (lookAbove ? "above" : "below");
        }

        KnownLevel l = level.get();
        return String.format("%s @ %.2f", l.getType().getDisplayName(), l.getPrice());
    }

    // ═══════════════════════════════════════════════════════════════════
    // CONFIRMATION
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Confirm a raid when displacement/MSS is detected.
     * This should be called when the strategy detects displacement.
     */
    public void confirmRaidWithDisplacement(boolean expectBullish) {
        Optional<LiquidityRaid> raid = expectBullish ?
                getActiveBullishRaid() : getActiveBearishRaid();

        if (raid.isPresent()) {
            chartStateManager.confirmRaid(primarySymbol, raid.get().getId(), true, false, false);
            log.info("[{}] Raid confirmed with displacement: {}", primarySymbol, raid.get().getId());
        }
    }

    /**
     * Confirm a raid when MSS is detected.
     */
    public void confirmRaidWithMSS(boolean expectBullish) {
        Optional<LiquidityRaid> raid = expectBullish ?
                getActiveBullishRaid() : getActiveBearishRaid();

        if (raid.isPresent()) {
            chartStateManager.confirmRaid(primarySymbol, raid.get().getId(), false, true, false);
            log.info("[{}] Raid confirmed with MSS: {}", primarySymbol, raid.get().getId());
        }
    }

    /**
     * Confirm a raid when FVG forms in expected direction.
     */
    public void confirmRaidWithFVG(boolean expectBullish) {
        Optional<LiquidityRaid> raid = expectBullish ?
                getActiveBullishRaid() : getActiveBearishRaid();

        if (raid.isPresent()) {
            chartStateManager.confirmRaid(primarySymbol, raid.get().getId(), false, false, true);
            log.info("[{}] Raid confirmed with FVG: {}", primarySymbol, raid.get().getId());
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // DIAGNOSTICS
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Get full diagnostic summary.
     */
    public String getDiagnosticSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n").append("=".repeat(60)).append("\n");
        sb.append("ChartState Integration for ").append(primarySymbol).append("\n");
        sb.append("=".repeat(60)).append("\n");

        // Levels
        sb.append(queryAPI.getLevelsSummary()).append("\n");

        // Raids
        sb.append(queryAPI.getRaidsSummary()).append("\n");

        // Equal levels
        var equalHighs = queryAPI.getEqualHighs();
        var equalLows = queryAPI.getEqualLows();
        if (!equalHighs.isEmpty() || !equalLows.isEmpty()) {
            sb.append("Equal Levels:\n");
            for (var eh : equalHighs) {
                sb.append(String.format("  EQ_HIGH @ %.2f (cluster=%d)\n", eh.getPrice(), eh.getClusterSize()));
            }
            for (var el : equalLows) {
                sb.append(String.format("  EQ_LOW @ %.2f (cluster=%d)\n", el.getPrice(), el.getClusterSize()));
            }
        }

        return sb.toString();
    }

    /**
     * Get the underlying query API for advanced queries.
     */
    public ChartStateQueryAPI getQueryAPI() {
        return queryAPI;
    }

    /**
     * Get the underlying chart state manager.
     */
    public ChartStateManager getChartStateManager() {
        return chartStateManager;
    }

    public String getPrimarySymbol() {
        return primarySymbol;
    }
}
