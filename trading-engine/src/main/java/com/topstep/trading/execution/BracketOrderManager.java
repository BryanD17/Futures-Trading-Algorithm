package com.topstep.trading.execution;

import com.topstep.trading.connector.TopstepConnector;
import com.topstep.trading.domain.OrderSide;
import com.topstep.trading.domain.OrderStatus;
import com.topstep.trading.strategy.TradeTier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages OCO (One Cancels Other) bracket orders for position protection.
 *
 * ENHANCED: Now supports multi-level take profits based on tier configuration.
 *
 * When an entry order fills, this manager:
 * 1. Submits a Stop Loss (Stop Market) order for full quantity
 * 2. Submits multiple Take Profit (Limit) orders at different R-multiples
 * 3. When first TP fills, moves SL to breakeven
 * 4. When SL fills, cancels all remaining TP orders (OCO)
 *
 * Directional rules:
 * - LONG position: SL = Sell Stop Market (below entry), TP = Sell Limit (above entry)
 * - SHORT position: SL = Buy Stop Market (above entry), TP = Buy Limit (below entry)
 */
public class BracketOrderManager {

    /**
     * Represents a single take profit level.
     */
    public static class TakeProfitLevel {
        public final double rMultiple;      // e.g., 1.0 = 1R, 2.0 = 2R
        public final double percentage;     // e.g., 0.50 = 50% of position
        public final double price;          // calculated price level
        public final int quantity;          // contracts to close at this level
        public String orderId;              // assigned after submission
        public boolean filled = false;

        public TakeProfitLevel(double rMultiple, double percentage, double price, int quantity) {
            this.rMultiple = rMultiple;
            this.percentage = percentage;
            this.price = price;
            this.quantity = quantity;
        }
    }

    /**
     * Represents a linked bracket (SL + multiple TPs) for a position.
     */
    public static class BracketOrder {
        public final String symbol;
        public final String entryOrderId;
        public final double entryPrice;
        public final int totalQuantity;
        public final OrderSide entrySide;
        public final TradeTier tier;

        // CRITICAL: volatile for thread-safe access from callback threads
        public volatile String stopOrderId;
        public volatile double stopPrice;
        public double originalStopPrice;    // Keep track for breakeven calculation
        public volatile int remainingQuantity;       // Contracts still protected by SL

        // Multi-level take profits
        public List<TakeProfitLevel> takeProfitLevels = new ArrayList<>();

        // CRITICAL: volatile flags for thread-safe checks from callback threads
        public volatile boolean stopFilled = false;
        public volatile boolean allTakesProfitFilled = false;
        public volatile boolean canceled = false;
        public volatile boolean movedToBreakeven = false;

        // Price-based breakeven trigger for single-contract positions
        // When only 1 contract runs to full target, move stop to breakeven at 1R price level
        public volatile double breakevenTriggerPrice = 0.0;  // 0 = no trigger
        public volatile boolean isSingleContractRunner = false;

        // Legacy single TP (for backward compatibility)
        public String takeProfitOrderId;
        public double takeProfitPrice;
        public boolean takeProfitFilled = false;

        public BracketOrder(String symbol, String entryOrderId, double entryPrice,
                           int quantity, OrderSide entrySide, TradeTier tier) {
            this.symbol = symbol;
            this.entryOrderId = entryOrderId;
            this.entryPrice = entryPrice;
            this.totalQuantity = quantity;
            this.remainingQuantity = quantity;
            this.entrySide = entrySide;
            this.tier = tier != null ? tier : TradeTier.TIER_1;
        }

        public boolean isLong() {
            return entrySide == OrderSide.BUY;
        }

        public OrderSide getExitSide() {
            return isLong() ? OrderSide.SELL : OrderSide.BUY;
        }

        public int getTotalFilledTpQuantity() {
            int filled = 0;
            for (TakeProfitLevel tp : takeProfitLevels) {
                if (tp.filled) {
                    filled += tp.quantity;
                }
            }
            return filled;
        }

        public boolean hasUnfilledTakeProfits() {
            for (TakeProfitLevel tp : takeProfitLevels) {
                if (!tp.filled && tp.orderId != null) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Callback interface for bracket events.
     */
    public interface BracketListener {
        void onStopLossFilled(BracketOrder bracket, double fillPrice);
        void onTakeProfitFilled(BracketOrder bracket, double fillPrice);
        void onPartialTakeProfitFilled(BracketOrder bracket, TakeProfitLevel level, double fillPrice);
        void onBracketCanceled(BracketOrder bracket, String reason);
        void onStopMovedToBreakeven(BracketOrder bracket, double newStopPrice);
    }

    // Track active brackets by symbol
    private final Map<String, BracketOrder> activeBrackets = new ConcurrentHashMap<>();

    // Track order ID to bracket mapping for quick lookup
    private final Map<String, BracketOrder> orderIdToBracket = new ConcurrentHashMap<>();

    // Track TP order ID to level mapping
    private final Map<String, TakeProfitLevel> tpOrderIdToLevel = new ConcurrentHashMap<>();

    private final TopstepConnector connector;
    private BracketListener listener;

    // Breakeven buffer (in ticks or minimum move)
    private static final double BREAKEVEN_BUFFER_TICKS = 2;

    public BracketOrderManager(TopstepConnector connector) {
        this.connector = connector;
    }

    public void setListener(BracketListener listener) {
        this.listener = listener;
    }

    /**
     * Create and submit an ENHANCED bracket with multi-level take profits.
     *
     * @param symbol The trading symbol
     * @param entryOrderId The entry order ID
     * @param entryPrice The actual fill price
     * @param quantity The filled quantity
     * @param entrySide BUY for long, SELL for short
     * @param stopPrice The stop loss price
     * @param finalTargetPrice The final take profit price (full R:R target)
     * @param tier The trade tier (determines partial profit levels)
     * @param tickSize The instrument's tick size for breakeven calculation
     */
    public void createBracketWithPartials(String symbol, String entryOrderId, double entryPrice,
                                          int quantity, OrderSide entrySide,
                                          double stopPrice, double finalTargetPrice,
                                          TradeTier tier, double tickSize) {

        // Check if bracket already exists
        if (activeBrackets.containsKey(symbol)) {
            System.out.println("[BRACKET] Warning: Bracket already exists for " + symbol + ", skipping");
            return;
        }

        BracketOrder bracket = new BracketOrder(symbol, entryOrderId, entryPrice, quantity, entrySide, tier);
        bracket.stopPrice = stopPrice;
        bracket.originalStopPrice = stopPrice;
        bracket.takeProfitPrice = finalTargetPrice;

        OrderSide exitSide = bracket.getExitSide();
        double riskDistance = Math.abs(entryPrice - stopPrice);

        System.out.println("\n[BRACKET] Creating TIERED bracket for " + symbol + " (" + tier + "):");
        System.out.println("  Entry: " + (bracket.isLong() ? "LONG" : "SHORT") + " " + quantity + " @ " + entryPrice);
        System.out.println("  Stop Loss: " + exitSide + " STOP @ " + stopPrice);
        System.out.println("  Risk Distance: " + String.format("%.2f", riskDistance));

        // Calculate take profit levels based on tier
        double[][] partialTargets = tier.getPartialProfitTargets();
        int remainingQty = quantity;
        List<TakeProfitLevel> levels = new ArrayList<>();

        System.out.println("  Take Profit Levels:");

        // SMALL POSITION OPTIMIZATION: For 1-2 contracts, use simplified distribution
        // to ensure a "runner" always reaches the tier's full R:R target.
        // Without this fix, Math.max(1,...) consumes all contracts at early TP levels.
        if (quantity <= 2 && partialTargets.length > 1) {
            double lastRMultiple = partialTargets[partialTargets.length - 1][0];  // Tier's full target

            if (quantity == 1) {
                // 1 CONTRACT: Single TP at the tier's FULL R:R target
                // Run for the big target instead of closing at 1R
                double levelPrice;
                if (bracket.isLong()) {
                    levelPrice = entryPrice + (riskDistance * lastRMultiple);
                } else {
                    levelPrice = entryPrice - (riskDistance * lastRMultiple);
                }
                TakeProfitLevel level = new TakeProfitLevel(lastRMultiple, 1.0, levelPrice, 1);
                levels.add(level);
                remainingQty = 0;

                // Set price-based breakeven trigger at 1R for protection
                bracket.isSingleContractRunner = true;
                if (bracket.isLong()) {
                    bracket.breakevenTriggerPrice = entryPrice + riskDistance;  // 1R above entry
                } else {
                    bracket.breakevenTriggerPrice = entryPrice - riskDistance;  // 1R below entry
                }

                System.out.println("    TP1: 1 contract @ " + String.format("%.2f", levelPrice) +
                    " (100% at " + lastRMultiple + "R) [single-contract: full target]");
                System.out.println("    Breakeven trigger: " +
                    String.format("%.2f", bracket.breakevenTriggerPrice) + " (1R price-based)");

            } else {
                // 2 CONTRACTS: 1 at 1R (lock-in + breakeven move), 1 at full target (runner)
                double firstR = partialTargets[0][0];  // Always 1R

                // TP1: Lock-in at 1R
                double price1;
                if (bracket.isLong()) {
                    price1 = entryPrice + (riskDistance * firstR);
                } else {
                    price1 = entryPrice - (riskDistance * firstR);
                }
                levels.add(new TakeProfitLevel(firstR, 0.50, price1, 1));
                System.out.println("    TP1: 1 contract @ " + String.format("%.2f", price1) +
                    " (50% at " + firstR + "R) [lock-in + breakeven]");

                // TP2: Runner at full tier target
                double price2;
                if (bracket.isLong()) {
                    price2 = entryPrice + (riskDistance * lastRMultiple);
                } else {
                    price2 = entryPrice - (riskDistance * lastRMultiple);
                }
                levels.add(new TakeProfitLevel(lastRMultiple, 0.50, price2, 1));
                System.out.println("    TP2: 1 contract @ " + String.format("%.2f", price2) +
                    " (50% at " + lastRMultiple + "R) [runner]");

                remainingQty = 0;
            }
        } else {
            // STANDARD DISTRIBUTION: 3+ contracts use normal tier-based partials
            for (int i = 0; i < partialTargets.length && remainingQty > 0; i++) {
                double rMultiple = partialTargets[i][0];
                double percentage = partialTargets[i][1];

                // Calculate quantity for this level
                int levelQty;
                if (i == partialTargets.length - 1) {
                    // Last level gets remaining quantity
                    levelQty = remainingQty;
                } else {
                    levelQty = Math.max(1, (int) Math.round(quantity * percentage));
                    levelQty = Math.min(levelQty, remainingQty);
                }

                // Calculate price for this level
                double levelPrice;
                if (bracket.isLong()) {
                    levelPrice = entryPrice + (riskDistance * rMultiple);
                } else {
                    levelPrice = entryPrice - (riskDistance * rMultiple);
                }

                TakeProfitLevel level = new TakeProfitLevel(rMultiple, percentage, levelPrice, levelQty);
                levels.add(level);
                remainingQty -= levelQty;

                System.out.println("    TP" + (i + 1) + ": " + levelQty + " contracts @ " +
                    String.format("%.2f", levelPrice) + " (" + String.format("%.0f%%", percentage * 100) +
                    " at " + rMultiple + "R)");

                if (remainingQty <= 0) break;
            }
        }

        bracket.takeProfitLevels = levels;

        // Submit Stop Loss order (full quantity)
        try {
            String stopOrderId = connector.submitStopOrder(
                symbol,
                exitSide,
                quantity,
                stopPrice,
                (id, status, price, qty) -> handleStopOrderUpdate(bracket, status, price)
            );
            bracket.stopOrderId = stopOrderId;
            orderIdToBracket.put(stopOrderId, bracket);
            System.out.println("  ✓ Stop Loss submitted: " + stopOrderId + " (qty: " + quantity + ")");
        } catch (Exception e) {
            System.err.println("  ❌ Failed to submit Stop Loss: " + e.getMessage());
            return;
        }

        // Submit Take Profit orders for each level
        for (int i = 0; i < levels.size(); i++) {
            TakeProfitLevel level = levels.get(i);
            try {
                String tpOrderId = connector.submitTakeProfitOrder(
                    symbol,
                    exitSide,
                    level.quantity,
                    level.price,
                    (id, status, price, qty) -> handlePartialTakeProfitUpdate(bracket, level, status, price, tickSize)
                );
                level.orderId = tpOrderId;
                orderIdToBracket.put(tpOrderId, bracket);
                tpOrderIdToLevel.put(tpOrderId, level);
                System.out.println("  ✓ TP" + (i + 1) + " submitted: " + tpOrderId);
            } catch (Exception e) {
                System.err.println("  ❌ Failed to submit TP" + (i + 1) + ": " + e.getMessage());
                // Continue with other TPs
            }
        }

        // Register the active bracket
        activeBrackets.put(symbol, bracket);
        System.out.println("[BRACKET] Tiered bracket active for " + symbol);
    }

    /**
     * Legacy method - Create single-level bracket (backward compatible).
     */
    public void createBracket(String symbol, String entryOrderId, double entryPrice,
                              int quantity, OrderSide entrySide,
                              double stopPrice, double takeProfitPrice) {

        // Check if bracket already exists for this symbol (idempotency guard)
        if (activeBrackets.containsKey(symbol)) {
            System.out.println("[BRACKET] Warning: Bracket already exists for " + symbol + ", skipping");
            return;
        }

        BracketOrder bracket = new BracketOrder(symbol, entryOrderId, entryPrice, quantity, entrySide, null);
        bracket.stopPrice = stopPrice;
        bracket.originalStopPrice = stopPrice;
        bracket.takeProfitPrice = takeProfitPrice;

        OrderSide exitSide = bracket.getExitSide();

        System.out.println("[BRACKET] Creating OCO bracket for " + symbol + ":");
        System.out.println("  Entry: " + (bracket.isLong() ? "LONG" : "SHORT") + " @ " + entryPrice);
        System.out.println("  Stop Loss: " + exitSide + " STOP @ " + stopPrice);
        System.out.println("  Take Profit: " + exitSide + " LIMIT @ " + takeProfitPrice);

        // Submit Stop Loss order
        try {
            String stopOrderId = connector.submitStopOrder(
                symbol,
                exitSide,
                quantity,
                stopPrice,
                (id, status, price, qty) -> handleStopOrderUpdate(bracket, status, price)
            );
            bracket.stopOrderId = stopOrderId;
            orderIdToBracket.put(stopOrderId, bracket);
            System.out.println("  ✓ Stop Loss submitted: " + stopOrderId);
        } catch (Exception e) {
            System.err.println("  ❌ Failed to submit Stop Loss: " + e.getMessage());
            return;
        }

        // Submit Take Profit order
        try {
            String tpOrderId = connector.submitTakeProfitOrder(
                symbol,
                exitSide,
                quantity,
                takeProfitPrice,
                (id, status, price, qty) -> handleTakeProfitOrderUpdate(bracket, status, price)
            );
            bracket.takeProfitOrderId = tpOrderId;
            orderIdToBracket.put(tpOrderId, bracket);
            System.out.println("  ✓ Take Profit submitted: " + tpOrderId);
        } catch (Exception e) {
            System.err.println("  ❌ Failed to submit Take Profit: " + e.getMessage());
            cancelOrder(bracket.stopOrderId, "Take Profit submission failed");
            return;
        }

        // Register the active bracket
        activeBrackets.put(symbol, bracket);
        System.out.println("[BRACKET] OCO bracket active for " + symbol);
    }

    /**
     * Handle partial take profit fill (from multi-level TP).
     */
    private void handlePartialTakeProfitUpdate(BracketOrder bracket, TakeProfitLevel level,
                                                OrderStatus status, Double fillPrice, double tickSize) {
        if (bracket.canceled || level.filled) {
            return;
        }

        if (status == OrderStatus.FILLED) {
            level.filled = true;
            bracket.remainingQuantity -= level.quantity;

            System.out.println("\n🎯 PARTIAL TP FILLED: " + bracket.symbol);
            System.out.println("  Level: " + level.rMultiple + "R @ " + fillPrice);
            System.out.println("  Quantity: " + level.quantity + " contracts");
            System.out.println("  Remaining: " + bracket.remainingQuantity + " contracts");

            // Notify listener
            if (listener != null) {
                listener.onPartialTakeProfitFilled(bracket, level, fillPrice != null ? fillPrice : level.price);
            }

            // Move stop to breakeven after FIRST partial fill
            if (!bracket.movedToBreakeven && bracket.remainingQuantity > 0) {
                moveStopToBreakeven(bracket, tickSize);
            }

            // Check if all TPs are filled
            if (bracket.remainingQuantity <= 0 || !bracket.hasUnfilledTakeProfits()) {
                bracket.allTakesProfitFilled = true;
                System.out.println("  ✓ All take profits filled for " + bracket.symbol);

                // Cancel stop loss since position is fully closed
                if (bracket.stopOrderId != null && !bracket.stopFilled) {
                    cancelOrder(bracket.stopOrderId, "All take profits filled");
                }

                removeBracket(bracket);

                if (listener != null) {
                    listener.onTakeProfitFilled(bracket, fillPrice != null ? fillPrice : level.price);
                }
            } else {
                // Update stop loss quantity to match remaining position
                updateStopLossQuantity(bracket);
            }
        }
    }

    /**
     * Move stop loss to breakeven (entry price + small buffer).
     */
    private void moveStopToBreakeven(BracketOrder bracket, double tickSize) {
        double buffer = tickSize * BREAKEVEN_BUFFER_TICKS;
        double newStopPrice;

        if (bracket.isLong()) {
            newStopPrice = bracket.entryPrice + buffer;
            // Only move if it's actually better (higher stop for long)
            if (newStopPrice <= bracket.stopPrice) {
                System.out.println("[BRACKET] Breakeven not needed - stop already at or above entry");
                return;
            }
        } else {
            newStopPrice = bracket.entryPrice - buffer;
            // Only move if it's actually better (lower stop for short)
            if (newStopPrice >= bracket.stopPrice) {
                System.out.println("[BRACKET] Breakeven not needed - stop already at or below entry");
                return;
            }
        }

        System.out.println("\n[BRACKET] 🔒 MOVING STOP TO BREAKEVEN: " + bracket.symbol);
        System.out.println("  Entry: " + bracket.entryPrice);
        System.out.println("  Old Stop: " + bracket.stopPrice);
        System.out.println("  New Stop: " + newStopPrice + " (breakeven + " + BREAKEVEN_BUFFER_TICKS + " ticks)");

        // Cancel old stop and submit new one
        try {
            if (bracket.stopOrderId != null) {
                connector.cancelOrder(bracket.stopOrderId);
                orderIdToBracket.remove(bracket.stopOrderId);
            }

            String newStopOrderId = connector.submitStopOrder(
                bracket.symbol,
                bracket.getExitSide(),
                bracket.remainingQuantity,
                newStopPrice,
                (id, status, price, qty) -> handleStopOrderUpdate(bracket, status, price)
            );

            bracket.stopOrderId = newStopOrderId;
            bracket.stopPrice = newStopPrice;
            bracket.movedToBreakeven = true;
            orderIdToBracket.put(newStopOrderId, bracket);

            System.out.println("  ✓ New breakeven stop submitted: " + newStopOrderId);

            if (listener != null) {
                listener.onStopMovedToBreakeven(bracket, newStopPrice);
            }

        } catch (Exception e) {
            System.err.println("  ❌ Failed to move stop to breakeven: " + e.getMessage());
            // Keep old stop in place
        }
    }

    /**
     * Update stop loss quantity after partial TP fills.
     */
    private void updateStopLossQuantity(BracketOrder bracket) {
        if (bracket.remainingQuantity <= 0 || bracket.stopOrderId == null) {
            return;
        }

        System.out.println("[BRACKET] Updating stop quantity to " + bracket.remainingQuantity + " for " + bracket.symbol);

        try {
            // Cancel old stop
            connector.cancelOrder(bracket.stopOrderId);
            orderIdToBracket.remove(bracket.stopOrderId);

            // Submit new stop with reduced quantity
            String newStopOrderId = connector.submitStopOrder(
                bracket.symbol,
                bracket.getExitSide(),
                bracket.remainingQuantity,
                bracket.stopPrice,
                (id, status, price, qty) -> handleStopOrderUpdate(bracket, status, price)
            );

            bracket.stopOrderId = newStopOrderId;
            orderIdToBracket.put(newStopOrderId, bracket);
            System.out.println("  ✓ Stop updated: " + newStopOrderId + " (qty: " + bracket.remainingQuantity + ")");

        } catch (Exception e) {
            System.err.println("  ❌ Failed to update stop quantity: " + e.getMessage());
        }
    }

    /**
     * Handle stop loss order status update.
     */
    private void handleStopOrderUpdate(BracketOrder bracket, OrderStatus status, Double fillPrice) {
        if (bracket.canceled || bracket.stopFilled) {
            return;
        }

        if (status == OrderStatus.FILLED) {
            bracket.stopFilled = true;
            System.out.println("\n⛔ STOP LOSS FILLED: " + bracket.symbol + " @ " + fillPrice);

            // OCO: Cancel all remaining take profit orders
            for (TakeProfitLevel tp : bracket.takeProfitLevels) {
                if (tp.orderId != null && !tp.filled) {
                    cancelOrder(tp.orderId, "Stop Loss filled (OCO)");
                }
            }

            // Legacy single TP
            if (bracket.takeProfitOrderId != null && !bracket.takeProfitFilled) {
                cancelOrder(bracket.takeProfitOrderId, "Stop Loss filled (OCO)");
            }

            removeBracket(bracket);

            if (listener != null) {
                listener.onStopLossFilled(bracket, fillPrice != null ? fillPrice : bracket.stopPrice);
            }
        }
    }

    /**
     * Handle legacy single take profit order status update.
     */
    private void handleTakeProfitOrderUpdate(BracketOrder bracket, OrderStatus status, Double fillPrice) {
        if (bracket.canceled || bracket.takeProfitFilled) {
            return;
        }

        if (status == OrderStatus.FILLED) {
            bracket.takeProfitFilled = true;
            System.out.println("\n🎯 TAKE PROFIT FILLED: " + bracket.symbol + " @ " + fillPrice);

            if (bracket.stopOrderId != null && !bracket.stopFilled) {
                cancelOrder(bracket.stopOrderId, "Take Profit filled (OCO)");
            }

            removeBracket(bracket);

            if (listener != null) {
                listener.onTakeProfitFilled(bracket, fillPrice != null ? fillPrice : bracket.takeProfitPrice);
            }
        }
    }

    /**
     * Cancel an order with logging.
     */
    private void cancelOrder(String orderId, String reason) {
        if (orderId == null) return;

        try {
            System.out.println("[BRACKET] Canceling order " + orderId + ": " + reason);
            connector.cancelOrder(orderId);
            System.out.println("[BRACKET] ✓ Order " + orderId + " canceled");
        } catch (Exception e) {
            System.out.println("[BRACKET] Cancel failed for " + orderId + ": " + e.getMessage());
        }
    }

    /**
     * Remove a bracket from tracking.
     */
    private void removeBracket(BracketOrder bracket) {
        activeBrackets.remove(bracket.symbol);

        if (bracket.stopOrderId != null) {
            orderIdToBracket.remove(bracket.stopOrderId);
        }

        for (TakeProfitLevel tp : bracket.takeProfitLevels) {
            if (tp.orderId != null) {
                orderIdToBracket.remove(tp.orderId);
                tpOrderIdToLevel.remove(tp.orderId);
            }
        }

        // Legacy
        if (bracket.takeProfitOrderId != null) {
            orderIdToBracket.remove(bracket.takeProfitOrderId);
        }
    }

    /**
     * Cancel all orders in a bracket (e.g., when position is manually closed).
     */
    public void cancelBracket(String symbol, String reason) {
        BracketOrder bracket = activeBrackets.get(symbol);
        if (bracket == null) {
            return;
        }

        bracket.canceled = true;
        System.out.println("[BRACKET] Canceling bracket for " + symbol + ": " + reason);

        if (bracket.stopOrderId != null && !bracket.stopFilled) {
            cancelOrder(bracket.stopOrderId, reason);
        }

        for (TakeProfitLevel tp : bracket.takeProfitLevels) {
            if (tp.orderId != null && !tp.filled) {
                cancelOrder(tp.orderId, reason);
            }
        }

        // Legacy
        if (bracket.takeProfitOrderId != null && !bracket.takeProfitFilled) {
            cancelOrder(bracket.takeProfitOrderId, reason);
        }

        removeBracket(bracket);

        if (listener != null) {
            listener.onBracketCanceled(bracket, reason);
        }
    }

    /**
     * Check if a symbol has an active bracket.
     */
    public boolean hasBracket(String symbol) {
        return activeBrackets.containsKey(symbol);
    }

    /**
     * Cancel all active brackets (used when shutting down without flattening).
     */
    public void cancelAllBrackets(String reason) {
        for (String symbol : activeBrackets.keySet()) {
            cancelBracket(symbol, reason);
        }
    }

    /**
     * Get the active bracket for a symbol.
     */
    public BracketOrder getBracket(String symbol) {
        return activeBrackets.get(symbol);
    }

    /**
     * Get count of active brackets.
     */
    public int getActiveBracketCount() {
        return activeBrackets.size();
    }

    /**
     * Check price-based breakeven trigger for single-contract runner positions.
     *
     * For 1-contract positions targeting the full tier R:R (e.g., 3R, 4R, 5R),
     * there is no partial TP fill to trigger the normal breakeven move.
     * Instead, we monitor price and move to breakeven when price reaches 1R.
     *
     * Call this method on each market data update for symbols with active brackets.
     *
     * @param symbol The trading symbol
     * @param currentPrice The current market price
     * @param tickSize The instrument's tick size for breakeven buffer calculation
     */
    public void checkPriceBreakevenTrigger(String symbol, double currentPrice, double tickSize) {
        BracketOrder bracket = activeBrackets.get(symbol);
        if (bracket == null || bracket.canceled || bracket.stopFilled || bracket.movedToBreakeven) {
            return;
        }

        // Only applies to single-contract runner positions
        if (!bracket.isSingleContractRunner || bracket.breakevenTriggerPrice == 0.0) {
            return;
        }

        // Check if price has reached the breakeven trigger level (1R)
        boolean triggered = false;
        if (bracket.isLong() && currentPrice >= bracket.breakevenTriggerPrice) {
            triggered = true;
        } else if (!bracket.isLong() && currentPrice <= bracket.breakevenTriggerPrice) {
            triggered = true;
        }

        if (triggered) {
            System.out.println("\n[BRACKET] Price reached 1R breakeven trigger for " + symbol +
                " (price: " + currentPrice + ", trigger: " + bracket.breakevenTriggerPrice + ")");
            moveStopToBreakeven(bracket, tickSize);
        }
    }
}
