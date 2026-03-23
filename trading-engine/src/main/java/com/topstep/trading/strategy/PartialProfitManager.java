package com.topstep.trading.strategy;

import com.topstep.trading.lifecycle.AccountPhase;
import com.topstep.trading.risk.RiskProfile;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages partial profit exits for evaluation phase.
 *
 * Evaluation phase only:
 *   - At 1R unrealized profit, close 50% of position
 *   - Move stop to breakeven on remainder
 *   - Track whether partial has been taken (prevent duplicates)
 *   - No partials in funded mode (wider targets, fewer trades)
 */
public class PartialProfitManager {

    /**
     * State of a partial exit for a specific position.
     */
    public static class PartialState {
        private final String symbol;
        private final double entryPrice;
        private final double stopPrice;
        private final double riskDistance;
        private final boolean isBuy;
        private boolean partialTaken;
        private boolean stopMovedToBreakeven;

        public PartialState(String symbol, double entryPrice, double stopPrice, boolean isBuy) {
            this.symbol = symbol;
            this.entryPrice = entryPrice;
            this.stopPrice = stopPrice;
            this.isBuy = isBuy;
            this.riskDistance = Math.abs(entryPrice - stopPrice);
            this.partialTaken = false;
            this.stopMovedToBreakeven = false;
        }

        public String getSymbol() { return symbol; }
        public double getEntryPrice() { return entryPrice; }
        public double getStopPrice() { return stopPrice; }
        public double getRiskDistance() { return riskDistance; }
        public boolean isBuy() { return isBuy; }
        public boolean isPartialTaken() { return partialTaken; }
        public boolean isStopMovedToBreakeven() { return stopMovedToBreakeven; }
    }

    /**
     * Decision from the partial profit check.
     */
    public static class PartialDecision {
        private final boolean shouldTakePartial;
        private final int quantityToClose;
        private final double newStopPrice;
        private final String reason;

        private PartialDecision(boolean shouldTakePartial, int quantityToClose,
                                double newStopPrice, String reason) {
            this.shouldTakePartial = shouldTakePartial;
            this.quantityToClose = quantityToClose;
            this.newStopPrice = newStopPrice;
            this.reason = reason;
        }

        public static PartialDecision noAction(String reason) {
            return new PartialDecision(false, 0, 0, reason);
        }

        public static PartialDecision takePartial(int quantity, double newStop, String reason) {
            return new PartialDecision(true, quantity, newStop, reason);
        }

        public boolean shouldTakePartial() { return shouldTakePartial; }
        public int getQuantityToClose() { return quantityToClose; }
        public double getNewStopPrice() { return newStopPrice; }
        public String getReason() { return reason; }
    }

    // Active partial tracking by symbol
    private final Map<String, PartialState> activePartials;

    public PartialProfitManager() {
        this.activePartials = new ConcurrentHashMap<>();
    }

    /**
     * Register a new position for partial profit tracking.
     */
    public void registerPosition(String symbol, double entryPrice, double stopPrice, boolean isBuy) {
        activePartials.put(symbol, new PartialState(symbol, entryPrice, stopPrice, isBuy));
    }

    /**
     * Remove tracking for a closed position.
     */
    public void removePosition(String symbol) {
        activePartials.remove(symbol);
    }

    /**
     * Check if a partial exit should be taken based on current price.
     *
     * @param symbol         Position symbol
     * @param currentPrice   Current market price
     * @param currentQuantity Current position quantity
     * @param phase          Current account phase
     * @param profile        Current risk profile
     * @return PartialDecision indicating whether to take a partial
     */
    public PartialDecision checkPartial(String symbol, double currentPrice, int currentQuantity,
                                         AccountPhase phase, RiskProfile profile) {
        // Only take partials in evaluation phase
        if (!profile.isPartialsEnabled()) {
            return PartialDecision.noAction("Partials disabled for " + phase);
        }

        PartialState state = activePartials.get(symbol);
        if (state == null) {
            return PartialDecision.noAction("No partial tracking for " + symbol);
        }

        if (state.partialTaken) {
            return PartialDecision.noAction("Partial already taken for " + symbol);
        }

        if (currentQuantity <= 1) {
            return PartialDecision.noAction("Only 1 contract - cannot split");
        }

        // Calculate unrealized R
        double unrealizedMove;
        if (state.isBuy) {
            unrealizedMove = currentPrice - state.entryPrice;
        } else {
            unrealizedMove = state.entryPrice - currentPrice;
        }

        double unrealizedR = unrealizedMove / state.riskDistance;

        // Check if at partial exit R-multiple
        if (unrealizedR >= profile.getPartialExitRMultiple()) {
            int quantityToClose = (int) Math.ceil(currentQuantity * profile.getPartialExitPct());
            quantityToClose = Math.min(quantityToClose, currentQuantity - 1); // Keep at least 1

            // Move stop to breakeven (entry + small buffer for commissions)
            double buffer = state.riskDistance * 0.1;
            double breakevenStop = state.isBuy
                ? state.entryPrice + buffer
                : state.entryPrice - buffer;

            // Mark partial as taken
            state.partialTaken = true;
            state.stopMovedToBreakeven = true;

            return PartialDecision.takePartial(quantityToClose, breakevenStop,
                String.format("Partial at %.1fR: close %d of %d, move stop to BE",
                    unrealizedR, quantityToClose, currentQuantity));
        }

        return PartialDecision.noAction("Not at partial level: " +
            String.format("%.2fR < %.1fR", unrealizedR, profile.getPartialExitRMultiple()));
    }

    /**
     * Get the current partial state for a symbol.
     */
    public PartialState getState(String symbol) {
        return activePartials.get(symbol);
    }

    /**
     * Clear all tracking state.
     */
    public void reset() {
        activePartials.clear();
    }
}
