package com.topstep.trading.execution;

import com.topstep.trading.connector.TopstepConnector;
import com.topstep.trading.domain.OrderSide;
import com.topstep.trading.domain.OrderStatus;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages OCO (One Cancels Other) bracket orders for position protection.
 *
 * When an entry order fills, this manager:
 * 1. Submits a Stop Loss (Stop Market) order
 * 2. Submits a Take Profit (Limit) order
 * 3. Links them as OCO - when one fills, the other is canceled
 *
 * Directional rules:
 * - LONG position: SL = Sell Stop Market (below entry), TP = Sell Limit (above entry)
 * - SHORT position: SL = Buy Stop Market (above entry), TP = Buy Limit (below entry)
 */
public class BracketOrderManager {

    /**
     * Represents a linked bracket (SL + TP) for a position.
     */
    public static class BracketOrder {
        public final String symbol;
        public final String entryOrderId;
        public final double entryPrice;
        public final int quantity;
        public final OrderSide entrySide;

        public String stopOrderId;
        public String takeProfitOrderId;
        public double stopPrice;
        public double takeProfitPrice;

        public boolean stopFilled = false;
        public boolean takeProfitFilled = false;
        public boolean canceled = false;

        public BracketOrder(String symbol, String entryOrderId, double entryPrice,
                           int quantity, OrderSide entrySide) {
            this.symbol = symbol;
            this.entryOrderId = entryOrderId;
            this.entryPrice = entryPrice;
            this.quantity = quantity;
            this.entrySide = entrySide;
        }

        public boolean isLong() {
            return entrySide == OrderSide.BUY;
        }

        public OrderSide getExitSide() {
            return isLong() ? OrderSide.SELL : OrderSide.BUY;
        }
    }

    /**
     * Callback interface for bracket events.
     */
    public interface BracketListener {
        void onStopLossFilled(BracketOrder bracket, double fillPrice);
        void onTakeProfitFilled(BracketOrder bracket, double fillPrice);
        void onBracketCanceled(BracketOrder bracket, String reason);
    }

    // Track active brackets by symbol
    private final Map<String, BracketOrder> activeBrackets = new ConcurrentHashMap<>();

    // Track order ID to bracket mapping for quick lookup
    private final Map<String, BracketOrder> orderIdToBracket = new ConcurrentHashMap<>();

    private final TopstepConnector connector;
    private BracketListener listener;

    public BracketOrderManager(TopstepConnector connector) {
        this.connector = connector;
    }

    public void setListener(BracketListener listener) {
        this.listener = listener;
    }

    /**
     * Create and submit a bracket (SL + TP) for a filled entry.
     *
     * @param symbol The trading symbol
     * @param entryOrderId The entry order ID
     * @param entryPrice The actual fill price (use average fill, not limit price)
     * @param quantity The filled quantity
     * @param entrySide BUY for long, SELL for short
     * @param stopPrice The stop loss price
     * @param takeProfitPrice The take profit price
     */
    public void createBracket(String symbol, String entryOrderId, double entryPrice,
                              int quantity, OrderSide entrySide,
                              double stopPrice, double takeProfitPrice) {

        // Check if bracket already exists for this symbol (idempotency guard)
        if (activeBrackets.containsKey(symbol)) {
            System.out.println("[BRACKET] Warning: Bracket already exists for " + symbol + ", skipping");
            return;
        }

        BracketOrder bracket = new BracketOrder(symbol, entryOrderId, entryPrice, quantity, entrySide);
        bracket.stopPrice = stopPrice;
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
            // Critical failure - position is unprotected!
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
            // Cancel the stop loss since we don't have a complete bracket
            cancelOrder(bracket.stopOrderId, "Take Profit submission failed");
            return;
        }

        // Register the active bracket
        activeBrackets.put(symbol, bracket);
        System.out.println("[BRACKET] OCO bracket active for " + symbol);
    }

    /**
     * Handle stop loss order status update.
     */
    private void handleStopOrderUpdate(BracketOrder bracket, OrderStatus status, Double fillPrice) {
        if (bracket.canceled || bracket.stopFilled) {
            return; // Already processed
        }

        if (status == OrderStatus.FILLED) {
            bracket.stopFilled = true;
            System.out.println("\n⛔ STOP LOSS FILLED: " + bracket.symbol + " @ " + fillPrice);

            // OCO: Cancel the take profit order
            if (bracket.takeProfitOrderId != null && !bracket.takeProfitFilled) {
                cancelOrder(bracket.takeProfitOrderId, "Stop Loss filled (OCO)");
            }

            // Clean up
            removeBracket(bracket);

            // Notify listener
            if (listener != null) {
                listener.onStopLossFilled(bracket, fillPrice != null ? fillPrice : bracket.stopPrice);
            }
        }
    }

    /**
     * Handle take profit order status update.
     */
    private void handleTakeProfitOrderUpdate(BracketOrder bracket, OrderStatus status, Double fillPrice) {
        if (bracket.canceled || bracket.takeProfitFilled) {
            return; // Already processed
        }

        if (status == OrderStatus.FILLED) {
            bracket.takeProfitFilled = true;
            System.out.println("\n🎯 TAKE PROFIT FILLED: " + bracket.symbol + " @ " + fillPrice);

            // OCO: Cancel the stop loss order
            if (bracket.stopOrderId != null && !bracket.stopFilled) {
                cancelOrder(bracket.stopOrderId, "Take Profit filled (OCO)");
            }

            // Clean up
            removeBracket(bracket);

            // Notify listener
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
            // Order might already be filled or canceled
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
}
