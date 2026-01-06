package com.topstep.trading.chartstate;

import com.topstep.trading.domain.Candle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Central manager for multi-instrument chart state and liquidity raid detection.
 *
 * This is the main entry point for the Enhanced Liquidity Raid Detection System.
 * It manages:
 * - Per-instrument candle series (ring buffers)
 * - Per-instrument level engines (PDH/PDL, session levels)
 * - Per-instrument equal level detectors
 * - Per-instrument raid detectors
 *
 * USAGE:
 * <pre>
 * ChartStateManager manager = new ChartStateManager();
 *
 * // Process candles (call from your data feed)
 * manager.processCandle("ES", candle, context);
 *
 * // Query state via API
 * ChartStateQueryAPI api = manager.getQueryAPI("ES");
 * Optional<LiquidityRaid> raid = api.getBestActiveRaid();
 * </pre>
 *
 * THREAD SAFETY:
 * All operations are thread-safe. Each instrument has its own independent
 * state with synchronized access. Multiple instruments can be processed
 * concurrently.
 *
 * MEMORY:
 * Each instrument uses ~1MB (5000 candles × ~200 bytes per candle).
 * For 10 instruments = ~10MB memory footprint.
 */
public class ChartStateManager {

    private static final Logger log = LoggerFactory.getLogger(ChartStateManager.class);

    // Per-instrument state
    private final Map<String, InstrumentState> instrumentStates = new ConcurrentHashMap<>();

    // Global raid listeners
    private final List<Consumer<LiquidityRaid>> globalRaidListeners = new ArrayList<>();

    public ChartStateManager() {
        log.info("ChartStateManager initialized");
    }

    // ═══════════════════════════════════════════════════════════════════
    // INSTRUMENT REGISTRATION
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Register an instrument for tracking.
     * If already registered, returns existing state.
     */
    public synchronized InstrumentState registerInstrument(String symbol) {
        return instrumentStates.computeIfAbsent(symbol.toUpperCase(), this::createInstrumentState);
    }

    /**
     * Create state for a new instrument.
     */
    private InstrumentState createInstrumentState(String symbol) {
        log.info("Registering instrument: {}", symbol);

        CandleSeries candleSeries = new CandleSeries(symbol);
        LevelEngine levelEngine = new LevelEngine(symbol, candleSeries);
        EqualLevelDetector equalLevelDetector = new EqualLevelDetector(symbol, candleSeries);
        RaidDetector raidDetector = new RaidDetector(symbol, levelEngine, equalLevelDetector, candleSeries);

        // Connect raid detector to global listeners
        raidDetector.addRaidListener(this::onRaidDetected);

        InstrumentState state = new InstrumentState(symbol, candleSeries, levelEngine, equalLevelDetector, raidDetector);
        return state;
    }

    /**
     * Check if an instrument is registered.
     */
    public boolean isInstrumentRegistered(String symbol) {
        return instrumentStates.containsKey(symbol.toUpperCase());
    }

    /**
     * Get all registered instruments.
     */
    public Set<String> getRegisteredInstruments() {
        return new HashSet<>(instrumentStates.keySet());
    }

    // ═══════════════════════════════════════════════════════════════════
    // CANDLE PROCESSING
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Process a new candle for an instrument.
     * Auto-registers the instrument if not already registered.
     *
     * @param symbol The instrument symbol
     * @param candle The completed candle
     * @param context Optional context (SMT, HTF bias)
     */
    public void processCandle(String symbol, Candle candle, RaidDetector.RaidDetectionContext context) {
        if (candle == null) return;

        String upperSymbol = symbol.toUpperCase();
        InstrumentState state = registerInstrument(upperSymbol);

        // Add candle to series
        state.candleSeries.addCandle(candle);

        // Update levels
        state.levelEngine.processCandle(candle);

        // Check for raids
        state.raidDetector.processCandle(candle, context != null ? context : RaidDetector.RaidDetectionContext.empty());
    }

    /**
     * Process candle without context.
     */
    public void processCandle(String symbol, Candle candle) {
        processCandle(symbol, candle, null);
    }

    // ═══════════════════════════════════════════════════════════════════
    // QUERY API
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Get query API for an instrument.
     * Auto-registers the instrument if not already registered.
     */
    public ChartStateQueryAPI getQueryAPI(String symbol) {
        InstrumentState state = registerInstrument(symbol.toUpperCase());
        return new InstrumentQueryAPI(state);
    }

    /**
     * Get direct access to instrument state (for advanced use).
     */
    public Optional<InstrumentState> getInstrumentState(String symbol) {
        return Optional.ofNullable(instrumentStates.get(symbol.toUpperCase()));
    }

    // ═══════════════════════════════════════════════════════════════════
    // RAID CONFIRMATION
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Confirm a raid (called when displacement/MSS detected).
     */
    public void confirmRaid(String symbol, String raidId, boolean hasDisplacement, boolean hasMss, boolean hasFvg) {
        InstrumentState state = instrumentStates.get(symbol.toUpperCase());
        if (state != null) {
            state.raidDetector.confirmRaid(raidId, hasDisplacement, hasMss, hasFvg);
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // EVENT LISTENERS
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Add global listener for raid events across all instruments.
     */
    public void addGlobalRaidListener(Consumer<LiquidityRaid> listener) {
        globalRaidListeners.add(listener);
    }

    /**
     * Remove global listener.
     */
    public void removeGlobalRaidListener(Consumer<LiquidityRaid> listener) {
        globalRaidListeners.remove(listener);
    }

    /**
     * Internal callback when raid is detected.
     */
    private void onRaidDetected(LiquidityRaid raid) {
        // Notify global listeners
        for (Consumer<LiquidityRaid> listener : globalRaidListeners) {
            try {
                listener.accept(raid);
            } catch (Exception e) {
                log.error("Error notifying global raid listener", e);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // UTILITY
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Reset state for an instrument.
     */
    public void resetInstrument(String symbol) {
        InstrumentState state = instrumentStates.get(symbol.toUpperCase());
        if (state != null) {
            state.candleSeries.clear();
            state.raidDetector.reset();
            log.info("Reset state for instrument: {}", symbol);
        }
    }

    /**
     * Reset all instruments.
     */
    public void resetAll() {
        for (String symbol : instrumentStates.keySet()) {
            resetInstrument(symbol);
        }
    }

    /**
     * Get summary for logging.
     */
    public String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("ChartStateManager:\n");
        for (InstrumentState state : instrumentStates.values()) {
            sb.append(String.format("  %s: %d candles, %d levels, %d active raids\n",
                    state.symbol,
                    state.candleSeries.getSize(),
                    state.levelEngine.getAllLevels().size(),
                    state.raidDetector.getActiveRaidCount()));
        }
        return sb.toString();
    }

    // ═══════════════════════════════════════════════════════════════════
    // INSTRUMENT STATE HOLDER
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Holds all state components for a single instrument.
     */
    public static class InstrumentState {
        public final String symbol;
        public final CandleSeries candleSeries;
        public final LevelEngine levelEngine;
        public final EqualLevelDetector equalLevelDetector;
        public final RaidDetector raidDetector;

        InstrumentState(String symbol, CandleSeries candleSeries, LevelEngine levelEngine,
                        EqualLevelDetector equalLevelDetector, RaidDetector raidDetector) {
            this.symbol = symbol;
            this.candleSeries = candleSeries;
            this.levelEngine = levelEngine;
            this.equalLevelDetector = equalLevelDetector;
            this.raidDetector = raidDetector;
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // QUERY API IMPLEMENTATION
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Implementation of ChartStateQueryAPI for a single instrument.
     */
    private static class InstrumentQueryAPI implements ChartStateQueryAPI {

        private final InstrumentState state;

        InstrumentQueryAPI(InstrumentState state) {
            this.state = state;
        }

        @Override
        public String getSymbol() {
            return state.symbol;
        }

        @Override
        public InstrumentRaidConfig getConfig() {
            return InstrumentRaidConfig.forSymbol(state.symbol);
        }

        // ═══════════════════════════════════════════════════════════════════
        // RAID QUERIES
        // ═══════════════════════════════════════════════════════════════════

        @Override
        public List<LiquidityRaid> getActiveRaids() {
            return state.raidDetector.getActiveRaids();
        }

        @Override
        public List<LiquidityRaid> getEntryValidRaids() {
            return state.raidDetector.getEntryValidRaids();
        }

        @Override
        public List<LiquidityRaid> getConfirmedRaids() {
            return state.raidDetector.getConfirmedRaids();
        }

        @Override
        public Optional<LiquidityRaid> getBestActiveRaid() {
            return state.raidDetector.getBestRaidForEntry();
        }

        @Override
        public Optional<LiquidityRaid> getActiveBullishRaid() {
            return state.raidDetector.getActiveBullishRaid();
        }

        @Override
        public Optional<LiquidityRaid> getActiveBearishRaid() {
            return state.raidDetector.getActiveBearishRaid();
        }

        @Override
        public boolean hasActiveRaidForDirection(boolean expectBullish) {
            return state.raidDetector.hasActiveRaidForDirection(expectBullish);
        }

        @Override
        public Optional<LiquidityRaid> getRaidById(String raidId) {
            return state.raidDetector.getRaidById(raidId);
        }

        // ═══════════════════════════════════════════════════════════════════
        // LEVEL QUERIES
        // ═══════════════════════════════════════════════════════════════════

        @Override
        public Optional<Double> getPDH() {
            return state.levelEngine.getPDH();
        }

        @Override
        public Optional<Double> getPDL() {
            return state.levelEngine.getPDL();
        }

        @Override
        public Optional<Double> getPWH() {
            return state.levelEngine.getPWH();
        }

        @Override
        public Optional<Double> getPWL() {
            return state.levelEngine.getPWL();
        }

        @Override
        public List<KnownLevel> getAllLevels() {
            return state.levelEngine.getAllLevels();
        }

        @Override
        public List<KnownLevel> getUnraidedLevels() {
            return state.levelEngine.getUnraidedLevels();
        }

        @Override
        public List<KnownLevel> getLevelsNearPrice(double price) {
            return state.levelEngine.getLevelsNearPrice(price);
        }

        @Override
        public Optional<KnownLevel> getNearestLevelAbove(double price) {
            return state.levelEngine.getNearestLevelAbove(price);
        }

        @Override
        public Optional<KnownLevel> getNearestLevelBelow(double price) {
            return state.levelEngine.getNearestLevelBelow(price);
        }

        @Override
        public Optional<KnownLevel> getLevel(LevelType type) {
            return state.levelEngine.getLevel(type);
        }

        // ═══════════════════════════════════════════════════════════════════
        // EQUAL LEVEL QUERIES
        // ═══════════════════════════════════════════════════════════════════

        @Override
        public List<EqualLevelDetector.EqualLevel> getEqualHighs() {
            return state.equalLevelDetector.getEqualHighs();
        }

        @Override
        public List<EqualLevelDetector.EqualLevel> getEqualLows() {
            return state.equalLevelDetector.getEqualLows();
        }

        @Override
        public Optional<EqualLevelDetector.EqualLevel> getStrongestEqualHigh() {
            return state.equalLevelDetector.getStrongestEqualHigh();
        }

        @Override
        public Optional<EqualLevelDetector.EqualLevel> getStrongestEqualLow() {
            return state.equalLevelDetector.getStrongestEqualLow();
        }

        @Override
        public List<EqualLevelDetector.EqualLevel> getEqualHighsAbove(double price) {
            return state.equalLevelDetector.getEqualHighsAbove(price);
        }

        @Override
        public List<EqualLevelDetector.EqualLevel> getEqualLowsBelow(double price) {
            return state.equalLevelDetector.getEqualLowsBelow(price);
        }

        // ═══════════════════════════════════════════════════════════════════
        // PRICE QUERIES
        // ═══════════════════════════════════════════════════════════════════

        @Override
        public Optional<Double> getLatestClose() {
            var candle = state.candleSeries.getLatestCandle();
            return candle != null ? Optional.of(candle.getClose()) : Optional.empty();
        }

        @Override
        public double getHighest(int lookback) {
            return state.candleSeries.getHighest(lookback);
        }

        @Override
        public double getLowest(int lookback) {
            return state.candleSeries.getLowest(lookback);
        }

        @Override
        public double getAverageRange(int lookback) {
            return state.candleSeries.getAverageRange(lookback);
        }

        @Override
        public boolean hasMinimumData(int required) {
            return state.candleSeries.hasMinimumData(required);
        }

        // ═══════════════════════════════════════════════════════════════════
        // SESSION QUERIES
        // ═══════════════════════════════════════════════════════════════════

        @Override
        public boolean isInAsia() {
            return state.levelEngine.isInAsia();
        }

        @Override
        public boolean isInLondon() {
            return state.levelEngine.isInLondon();
        }

        @Override
        public boolean isInNY() {
            return state.levelEngine.isInNY();
        }

        // ═══════════════════════════════════════════════════════════════════
        // DIAGNOSTIC
        // ═══════════════════════════════════════════════════════════════════

        @Override
        public String getLevelsSummary() {
            return state.levelEngine.getLevelsSummary();
        }

        @Override
        public String getRaidsSummary() {
            List<LiquidityRaid> raids = getActiveRaids();
            if (raids.isEmpty()) {
                return "No active raids for " + state.symbol;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Active raids for ").append(state.symbol).append(":\n");
            for (LiquidityRaid raid : raids) {
                sb.append(String.format("  %s @ %s: score=%d (%s), state=%s, bars=%d\n",
                        raid.getDirection().getDisplayName(),
                        raid.getTargetLevel().getType().getDisplayName(),
                        raid.getQualityScore(),
                        raid.getQualityClassification(),
                        raid.getState().name(),
                        raid.getBarsSinceRaid()));
            }
            return sb.toString();
        }
    }
}
