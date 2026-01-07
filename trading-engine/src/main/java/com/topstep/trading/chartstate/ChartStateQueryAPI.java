package com.topstep.trading.chartstate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Query API for chart state and liquidity raid detection.
 *
 * This interface provides a clean abstraction for strategies to query
 * the chart state without direct access to internals. All methods are
 * stateless queries that return current state.
 *
 * USAGE:
 * Strategies should use this API instead of directly accessing
 * candle series, levels, or raid detectors. This ensures:
 * - Clean separation of concerns
 * - Thread-safe access
 * - Consistent view of state
 *
 * EXAMPLE:
 * <pre>
 * ChartStateQueryAPI api = chartStateManager.getQueryAPI("ES");
 *
 * // Check for active raids
 * Optional<LiquidityRaid> raid = api.getBestActiveRaid();
 * if (raid.isPresent() && raid.get().getQualityScore() >= 6) {
 *     // Consider entry
 * }
 *
 * // Query levels
 * Optional<Double> pdh = api.getPDH();
 * List<KnownLevel> nearbyLevels = api.getLevelsNearPrice(currentPrice);
 * </pre>
 */
public interface ChartStateQueryAPI {

    // ═══════════════════════════════════════════════════════════════════
    // INSTRUMENT INFO
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Get the instrument symbol.
     */
    String getSymbol();

    /**
     * Get configuration for this instrument.
     */
    InstrumentRaidConfig getConfig();

    // ═══════════════════════════════════════════════════════════════════
    // RAID QUERIES
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Get all active (trackable) raids.
     */
    List<LiquidityRaid> getActiveRaids();

    /**
     * Get raids valid for trade entry.
     */
    List<LiquidityRaid> getEntryValidRaids();

    /**
     * Get confirmed raids (highest probability).
     */
    List<LiquidityRaid> getConfirmedRaids();

    /**
     * Get the best raid for entry (highest score among valid).
     */
    Optional<LiquidityRaid> getBestActiveRaid();

    /**
     * Get active bullish raid (if any).
     * A bullish raid is a LOW_SWEEP expecting price to go up.
     */
    Optional<LiquidityRaid> getActiveBullishRaid();

    /**
     * Get active bearish raid (if any).
     * A bearish raid is a HIGH_SWEEP expecting price to go down.
     */
    Optional<LiquidityRaid> getActiveBearishRaid();

    /**
     * Check if there's an active raid matching expected direction.
     */
    boolean hasActiveRaidForDirection(boolean expectBullish);

    /**
     * Get raid by ID.
     */
    Optional<LiquidityRaid> getRaidById(String raidId);

    // ═══════════════════════════════════════════════════════════════════
    // LEVEL QUERIES
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Get PDH (Previous Day High).
     */
    Optional<Double> getPDH();

    /**
     * Get PDL (Previous Day Low).
     */
    Optional<Double> getPDL();

    /**
     * Get PWH (Previous Week High).
     */
    Optional<Double> getPWH();

    /**
     * Get PWL (Previous Week Low).
     */
    Optional<Double> getPWL();

    /**
     * Get all known levels.
     */
    List<KnownLevel> getAllLevels();

    /**
     * Get unraided levels.
     */
    List<KnownLevel> getUnraidedLevels();

    /**
     * Get levels within tolerance of a price.
     */
    List<KnownLevel> getLevelsNearPrice(double price);

    /**
     * Get nearest level above current price.
     */
    Optional<KnownLevel> getNearestLevelAbove(double price);

    /**
     * Get nearest level below current price.
     */
    Optional<KnownLevel> getNearestLevelBelow(double price);

    /**
     * Get a specific level by type.
     */
    Optional<KnownLevel> getLevel(LevelType type);

    // ═══════════════════════════════════════════════════════════════════
    // EQUAL LEVEL QUERIES
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Get all detected equal highs.
     */
    List<EqualLevelDetector.EqualLevel> getEqualHighs();

    /**
     * Get all detected equal lows.
     */
    List<EqualLevelDetector.EqualLevel> getEqualLows();

    /**
     * Get strongest equal high.
     */
    Optional<EqualLevelDetector.EqualLevel> getStrongestEqualHigh();

    /**
     * Get strongest equal low.
     */
    Optional<EqualLevelDetector.EqualLevel> getStrongestEqualLow();

    /**
     * Get equal highs above a price.
     */
    List<EqualLevelDetector.EqualLevel> getEqualHighsAbove(double price);

    /**
     * Get equal lows below a price.
     */
    List<EqualLevelDetector.EqualLevel> getEqualLowsBelow(double price);

    // ═══════════════════════════════════════════════════════════════════
    // PRICE QUERIES (from candle series)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Get latest close price.
     */
    Optional<Double> getLatestClose();

    /**
     * Get highest high in last N bars.
     */
    double getHighest(int lookback);

    /**
     * Get lowest low in last N bars.
     */
    double getLowest(int lookback);

    /**
     * Get average range over last N bars.
     */
    double getAverageRange(int lookback);

    /**
     * Check if we have minimum data for analysis.
     */
    boolean hasMinimumData(int required);

    // ═══════════════════════════════════════════════════════════════════
    // SESSION QUERIES
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Check if currently in Asia session.
     */
    boolean isInAsia();

    /**
     * Check if currently in London session.
     */
    boolean isInLondon();

    /**
     * Check if currently in NY session.
     */
    boolean isInNY();

    // ═══════════════════════════════════════════════════════════════════
    // DIAGNOSTIC
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Get summary of current levels for logging.
     */
    String getLevelsSummary();

    /**
     * Get summary of active raids for logging.
     */
    String getRaidsSummary();
}
