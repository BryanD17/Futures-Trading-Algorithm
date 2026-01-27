package com.topstep.trading.execution;

import com.topstep.trading.domain.Candle;
import com.topstep.trading.strategy.TradeTierVariant;
import com.topstep.trading.strategy.VariantSelector;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages trailing stop logic for active positions.
 *
 * RESPONSIBILITIES:
 * 1. Track highest/lowest price since entry for each position
 * 2. Determine when to move stop to breakeven
 * 3. Determine when to activate trailing
 * 4. Calculate optimal trail stop price based on variant configuration
 * 5. Apply instrument-specific multipliers
 * 6. Apply time-based decay if configured
 *
 * USAGE:
 * - Call onPositionOpened() when a new position is established
 * - Call updateTrail() on each new candle
 * - Check getRecommendedStopPrice() to get the current optimal stop
 * - Call onPositionClosed() when position is fully closed
 *
 * STATE MACHINE:
 * INITIAL → (reach BE activation R) → BREAKEVEN → (reach trail activation R) → TRAILING
 */
public class TrailingStopManager {

    /**
     * Represents the trailing state for a single position.
     */
    public static class TrailState {
        // Position info
        public final String symbol;
        public final double entryPrice;
        public final double originalStopPrice;
        public final double riskDistance;
        public final boolean isLong;
        public final TradeTierVariant variant;
        public final Instant entryTime;
        public final double instrumentMultiplier;

        // Current state
        public double currentStopPrice;
        public double highestPriceSinceEntry;   // For longs
        public double lowestPriceSinceEntry;    // For shorts
        public double currentRMultiple;         // Current unrealized R

        // Phase tracking
        public TrailPhase phase;
        public boolean breakevenActivated;
        public boolean trailingActivated;
        public Instant lastUpdateTime;

        public TrailState(String symbol, double entryPrice, double stopPrice,
                         boolean isLong, TradeTierVariant variant) {
            this.symbol = symbol;
            this.entryPrice = entryPrice;
            this.originalStopPrice = stopPrice;
            this.currentStopPrice = stopPrice;
            this.riskDistance = Math.abs(entryPrice - stopPrice);
            this.isLong = isLong;
            this.variant = variant;
            this.entryTime = Instant.now();
            this.instrumentMultiplier = VariantSelector.getInstrumentTrailMultiplier(symbol);

            // Initialize extremes
            this.highestPriceSinceEntry = entryPrice;
            this.lowestPriceSinceEntry = entryPrice;
            this.currentRMultiple = 0;

            // Initialize phase
            this.phase = TrailPhase.INITIAL;
            this.breakevenActivated = false;
            this.trailingActivated = false;
            this.lastUpdateTime = Instant.now();
        }

        /**
         * Calculate current R-multiple based on price.
         */
        public double calculateRMultiple(double currentPrice) {
            if (riskDistance <= 0) return 0;

            double priceDiff = isLong ? (currentPrice - entryPrice) : (entryPrice - currentPrice);
            return priceDiff / riskDistance;
        }
    }

    /**
     * Trail phase enumeration.
     */
    public enum TrailPhase {
        INITIAL,        // Before breakeven activation
        BREAKEVEN,      // Stop at breakeven, waiting for trail activation
        TRAILING        // Actively trailing
    }

    /**
     * Result of a trail update.
     */
    public static class TrailUpdateResult {
        public final boolean stopMoved;
        public final double oldStopPrice;
        public final double newStopPrice;
        public final TrailPhase newPhase;
        public final String reason;

        public TrailUpdateResult(boolean stopMoved, double oldStopPrice, double newStopPrice,
                                TrailPhase newPhase, String reason) {
            this.stopMoved = stopMoved;
            this.oldStopPrice = oldStopPrice;
            this.newStopPrice = newStopPrice;
            this.newPhase = newPhase;
            this.reason = reason;
        }

        public static TrailUpdateResult noChange(double currentStop, TrailPhase phase) {
            return new TrailUpdateResult(false, currentStop, currentStop, phase, "No change needed");
        }
    }

    // Track trail state for each symbol
    private final Map<String, TrailState> trailStates = new ConcurrentHashMap<>();

    // Listener for stop updates (to trigger actual order modifications)
    private TrailUpdateListener listener;

    /**
     * Listener interface for trail updates.
     */
    public interface TrailUpdateListener {
        void onStopMoveRequested(String symbol, double newStopPrice, String reason);
        void onBreakevenActivated(String symbol, double breakevenPrice);
        void onTrailingActivated(String symbol, double activationPrice);
    }

    public void setListener(TrailUpdateListener listener) {
        this.listener = listener;
    }

    /**
     * Register a new position for trailing management.
     */
    public void onPositionOpened(String symbol, double entryPrice, double stopPrice,
                                 boolean isLong, TradeTierVariant variant) {
        TrailState state = new TrailState(symbol, entryPrice, stopPrice, isLong, variant);
        trailStates.put(symbol, state);

        System.out.println(String.format("[TRAIL] Registered %s position for %s: entry=%.2f, stop=%.2f, variant=%s",
            isLong ? "LONG" : "SHORT", symbol, entryPrice, stopPrice, variant.toLogString()));

        if (!variant.hasTrailingStop()) {
            System.out.println(String.format("[TRAIL] %s: Trailing DISABLED - will only move to BE at %.1fR",
                symbol, variant.getBreakevenActivationR()));
        } else {
            System.out.println(String.format("[TRAIL] %s: Trail config - BE@%.1fR, Activate@%.1fR, Distance=%.2fR, TimeDecay=%s",
                symbol,
                variant.getBreakevenActivationR(),
                variant.getTrailActivationR(),
                variant.getTrailDistanceR(),
                variant.hasTimeDecay() ? "YES" : "NO"));
        }
    }

    /**
     * Update trail state based on new candle.
     * Should be called on every candle close.
     */
    public TrailUpdateResult updateTrail(String symbol, Candle candle) {
        TrailState state = trailStates.get(symbol);
        if (state == null) {
            return TrailUpdateResult.noChange(0, TrailPhase.INITIAL);
        }

        double oldStop = state.currentStopPrice;

        // Update extreme prices
        if (state.isLong) {
            state.highestPriceSinceEntry = Math.max(state.highestPriceSinceEntry, candle.getHigh());
        } else {
            state.lowestPriceSinceEntry = Math.min(state.lowestPriceSinceEntry, candle.getLow());
        }

        // Calculate current R-multiple based on extreme (not current price)
        double extremePrice = state.isLong ? state.highestPriceSinceEntry : state.lowestPriceSinceEntry;
        state.currentRMultiple = state.calculateRMultiple(extremePrice);

        state.lastUpdateTime = Instant.now();

        // Process based on current phase
        switch (state.phase) {
            case INITIAL:
                return processInitialPhase(state);
            case BREAKEVEN:
                return processBreakevenPhase(state);
            case TRAILING:
                return processTrailingPhase(state);
            default:
                return TrailUpdateResult.noChange(oldStop, state.phase);
        }
    }

    /**
     * Process INITIAL phase - waiting for breakeven activation.
     */
    private TrailUpdateResult processInitialPhase(TrailState state) {
        double breakevenR = state.variant.getBreakevenActivationR();

        // Check if we've reached breakeven activation point
        if (state.currentRMultiple >= breakevenR) {
            // Calculate breakeven price with small buffer for commissions
            double buffer = VariantSelector.getBreakevenBuffer(state.symbol, state.riskDistance);
            double breakevenPrice;

            if (state.isLong) {
                breakevenPrice = state.entryPrice + buffer;
            } else {
                breakevenPrice = state.entryPrice - buffer;
            }

            state.currentStopPrice = breakevenPrice;
            state.breakevenActivated = true;
            state.phase = TrailPhase.BREAKEVEN;

            String reason = String.format("Reached %.1fR - moved stop to breakeven (%.2f)",
                breakevenR, breakevenPrice);

            System.out.println(String.format("[TRAIL] %s: %s", state.symbol, reason));

            if (listener != null) {
                listener.onBreakevenActivated(state.symbol, breakevenPrice);
                listener.onStopMoveRequested(state.symbol, breakevenPrice, reason);
            }

            return new TrailUpdateResult(true, state.originalStopPrice, breakevenPrice,
                TrailPhase.BREAKEVEN, reason);
        }

        return TrailUpdateResult.noChange(state.currentStopPrice, TrailPhase.INITIAL);
    }

    /**
     * Process BREAKEVEN phase - waiting for trail activation.
     */
    private TrailUpdateResult processBreakevenPhase(TrailState state) {
        // If trailing is not enabled, stay in breakeven phase
        if (!state.variant.hasTrailingStop()) {
            return TrailUpdateResult.noChange(state.currentStopPrice, TrailPhase.BREAKEVEN);
        }

        double activationR = state.variant.getTrailActivationR();

        // Check if we've reached trail activation point
        if (state.currentRMultiple >= activationR) {
            state.trailingActivated = true;
            state.phase = TrailPhase.TRAILING;

            String reason = String.format("Reached %.1fR - trailing activated", activationR);
            System.out.println(String.format("[TRAIL] %s: %s", state.symbol, reason));

            if (listener != null) {
                double extremePrice = state.isLong ? state.highestPriceSinceEntry : state.lowestPriceSinceEntry;
                listener.onTrailingActivated(state.symbol, extremePrice);
            }

            // Immediately calculate first trail stop
            return processTrailingPhase(state);
        }

        return TrailUpdateResult.noChange(state.currentStopPrice, TrailPhase.BREAKEVEN);
    }

    /**
     * Process TRAILING phase - actively updating trail stop.
     */
    private TrailUpdateResult processTrailingPhase(TrailState state) {
        double baseTrailDistance = state.variant.getTrailDistanceR();

        // Apply instrument multiplier
        double adjustedTrailDistance = baseTrailDistance * state.instrumentMultiplier;

        // Apply time decay if enabled
        if (state.variant.hasTimeDecay()) {
            adjustedTrailDistance = applyTimeDecay(state, adjustedTrailDistance);
        }

        // Calculate trail stop price
        double trailDistancePoints = adjustedTrailDistance * state.riskDistance;
        double newStopPrice;

        if (state.isLong) {
            newStopPrice = state.highestPriceSinceEntry - trailDistancePoints;
            // Never move stop backward
            newStopPrice = Math.max(newStopPrice, state.currentStopPrice);
        } else {
            newStopPrice = state.lowestPriceSinceEntry + trailDistancePoints;
            // Never move stop backward (for shorts, lower is better)
            newStopPrice = Math.min(newStopPrice, state.currentStopPrice);
        }

        // Check if stop should move
        boolean shouldMove = state.isLong ?
            (newStopPrice > state.currentStopPrice) :
            (newStopPrice < state.currentStopPrice);

        if (shouldMove) {
            double oldStop = state.currentStopPrice;
            state.currentStopPrice = newStopPrice;

            double extremePrice = state.isLong ? state.highestPriceSinceEntry : state.lowestPriceSinceEntry;
            String reason = String.format("Trail update: extreme=%.2f, distance=%.2fR, new stop=%.2f",
                extremePrice, adjustedTrailDistance, newStopPrice);

            System.out.println(String.format("[TRAIL] %s: Stop moved %.2f -> %.2f (%s)",
                state.symbol, oldStop, newStopPrice, reason));

            if (listener != null) {
                listener.onStopMoveRequested(state.symbol, newStopPrice, reason);
            }

            return new TrailUpdateResult(true, oldStop, newStopPrice, TrailPhase.TRAILING, reason);
        }

        return TrailUpdateResult.noChange(state.currentStopPrice, TrailPhase.TRAILING);
    }

    /**
     * Apply time decay to trail distance.
     * Trail tightens over time, bottoming out at 50% of original.
     */
    private double applyTimeDecay(TrailState state, double baseDistance) {
        Duration timeInTrade = Duration.between(state.entryTime, Instant.now());
        long minutesInTrade = timeInTrade.toMinutes();

        // Decay formula: max(0.5, 1.0 - minutes/120)
        // At 0 min: 100%, at 60 min: 75%, at 120+ min: 50% (floor)
        double decayFactor = Math.max(0.5, 1.0 - (minutesInTrade / 120.0));

        return baseDistance * decayFactor;
    }

    /**
     * Remove position from trail management.
     */
    public void onPositionClosed(String symbol) {
        TrailState state = trailStates.remove(symbol);
        if (state != null) {
            System.out.println(String.format("[TRAIL] %s: Position closed, trail state removed", symbol));
        }
    }

    /**
     * Get current recommended stop price for a symbol.
     */
    public double getRecommendedStopPrice(String symbol) {
        TrailState state = trailStates.get(symbol);
        return state != null ? state.currentStopPrice : 0;
    }

    /**
     * Get current trail phase for a symbol.
     */
    public TrailPhase getTrailPhase(String symbol) {
        TrailState state = trailStates.get(symbol);
        return state != null ? state.phase : null;
    }

    /**
     * Get current R-multiple for a symbol.
     */
    public double getCurrentRMultiple(String symbol) {
        TrailState state = trailStates.get(symbol);
        return state != null ? state.currentRMultiple : 0;
    }

    /**
     * Check if trailing is active for a symbol.
     */
    public boolean isTrailingActive(String symbol) {
        TrailState state = trailStates.get(symbol);
        return state != null && state.phase == TrailPhase.TRAILING;
    }

    /**
     * Check if breakeven is active for a symbol.
     */
    public boolean isBreakevenActive(String symbol) {
        TrailState state = trailStates.get(symbol);
        return state != null && state.breakevenActivated;
    }

    /**
     * Get trail state summary for logging.
     */
    public String getTrailSummary(String symbol) {
        TrailState state = trailStates.get(symbol);
        if (state == null) return "No trail state";

        return String.format("Phase=%s, R=%.2f, Stop=%.2f, BE=%s, Trail=%s",
            state.phase,
            state.currentRMultiple,
            state.currentStopPrice,
            state.breakevenActivated ? "YES" : "NO",
            state.trailingActivated ? "YES" : "NO");
    }

    /**
     * Check if we have trail state for a symbol.
     */
    public boolean hasTrailState(String symbol) {
        return trailStates.containsKey(symbol);
    }

    /**
     * Get all symbols being managed.
     */
    public Set<String> getManagedSymbols() {
        return trailStates.keySet();
    }

    /**
     * Get trail state for a symbol (for testing/debugging).
     */
    public TrailState getTrailState(String symbol) {
        return trailStates.get(symbol);
    }
}
