package com.topstep.trading.execution;

import com.topstep.trading.domain.*;
import com.topstep.trading.strategy.TradeTier;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * ExecutionEngine handles order execution and position management.
 *
 * Enhanced features:
 * - Partial profit taking at R-multiples
 * - Trailing stops after hitting profit targets
 * - Dynamic R:R based on trade tier
 * - Position scaling
 *
 * In backtest mode:
 * - Simulates fills based on candle prices
 * - Updates positions and PnL
 * - Tracks completed trades
 *
 * In live/sim mode:
 * - Delegates to TradingConnector
 * - Reacts to real fill notifications
 */
public class ExecutionEngine {

    /**
     * Listener interface for execution events (fills, closes).
     * CRITICAL: Allows BacktestRunner/LiveRunner to track position state correctly.
     */
    public interface ExecutionListener {
        /** Called when an order is filled and position is opened. */
        void onPositionOpened(String symbol, OrderSide side, double entryPrice, int quantity);
        /** Called when a position is fully or partially closed. */
        void onPositionClosed(String symbol, double pnl, boolean isWin);
    }

    private final AccountState accountState;
    // CRITICAL: Changed to List<Order> to support multiple orders per symbol
    private final Map<String, List<Order>> activeOrders;
    private final Map<String, Double> tickValues;
    private final List<Trade> completedTrades;

    // Enhanced order management with partial profits and trailing stops
    private final Map<String, EnhancedOrderLevels> orderLevels;

    // Track last prices for live PnL calculations
    private final Map<String, Double> lastPrices;

    // Execution listener for external notifications
    private ExecutionListener executionListener;

    // Controls whether this engine simulates fills (true for backtest/sim, false for live)
    private boolean simulationEnabled = true;

    public ExecutionEngine(AccountState accountState) {
        this.accountState = accountState;
        this.activeOrders = new HashMap<>();
        this.completedTrades = new ArrayList<>();
        this.tickValues = new HashMap<>();
        this.orderLevels = new HashMap<>();
        this.lastPrices = new HashMap<>();

        // Set default tick values
        initializeTickValues();
    }

    /**
     * Initialize tick values for common futures symbols.
     */
    private void initializeTickValues() {
        // Index futures
        tickValues.put("ES", 12.50);   // E-mini S&P 500: $12.50 per tick (0.25 point)
        tickValues.put("NQ", 5.00);    // E-mini NASDAQ 100: $5.00 per tick (0.25 point)
        tickValues.put("MES", 1.25);   // Micro E-mini S&P 500: $1.25 per tick
        tickValues.put("MNQ", 0.50);   // Micro E-mini NASDAQ 100: $0.50 per tick
        tickValues.put("YM", 5.00);    // E-mini Dow: $5.00 per tick
        tickValues.put("RTY", 5.00);   // E-mini Russell 2000: $5.00 per tick

        // Metals
        tickValues.put("GC", 10.00);   // Gold: $10.00 per tick (100 oz × 0.10)
        tickValues.put("SI", 25.00);   // Silver: $25.00 per tick (5,000 oz × 0.005)

        // Energy
        tickValues.put("CL", 10.00);   // Crude Oil: $10.00 per tick (1,000 barrels × 0.01)
        tickValues.put("NG", 10.00);   // Natural Gas: $10.00 per tick (10,000 MMBtu × 0.001)

        // Currency futures
        tickValues.put("6E", 6.25);    // Euro FX: $6.25 per tick (125,000 EUR × 0.00005)
        tickValues.put("6J", 6.25);    // Japanese Yen: $6.25 per tick (12,500,000 JPY × 0.0000005)
        tickValues.put("6B", 6.25);    // British Pound: $6.25 per tick (62,500 GBP × 0.0001)
        tickValues.put("6C", 10.00);   // Canadian Dollar: $10.00 per tick (100,000 CAD × 0.0001)
    }

    /**
     * Set the execution listener for fill/close notifications.
     * CRITICAL: Must be set before processing candles to receive all notifications.
     */
    public void setExecutionListener(ExecutionListener listener) {
        this.executionListener = listener;
    }

    /**
     * Enable/disable simulation behaviors (limit fills, stop/target checks, trailing) for live mode.
     */
    public void setSimulationEnabled(boolean simulationEnabled) {
        this.simulationEnabled = simulationEnabled;
    }

    /**
     * Accept a new approved order for execution.
     */
    public void submitOrder(Order order) {
        if (order == null) {
            throw new IllegalArgumentException("Order cannot be null");
        }

        order.updateStatus(OrderStatus.SUBMITTED);
        // CRITICAL: Support multiple orders per symbol by using a list
        activeOrders.computeIfAbsent(order.getSymbol(), k -> new ArrayList<>()).add(order);
    }

    /**
     * Submit order with stop and target levels (original method for compatibility).
     */
    public void submitOrder(Order order, double stopPrice, double targetPrice) {
        submitOrder(order);

        EnhancedOrderLevels levels = new EnhancedOrderLevels(
            order.getLimitPrice(),
            stopPrice,
            targetPrice,
            order.getSide(),
            order.getQuantity(),
            TradeTier.TIER_2  // Default tier
        );
        orderLevels.put(order.getSymbol(), levels);
    }

    /**
     * Submit order with enhanced levels including tier and partial profit targets.
     */
    public void submitOrderEnhanced(Order order, double stopPrice, double targetPrice,
                                    TradeTier tier, double[][] partialProfitTargets) {
        submitOrder(order);

        EnhancedOrderLevels levels = new EnhancedOrderLevels(
            order.getLimitPrice(),
            stopPrice,
            targetPrice,
            order.getSide(),
            order.getQuantity(),
            tier,
            partialProfitTargets
        );
        orderLevels.put(order.getSymbol(), levels);
    }

    /**
     * Process a new candle - check for fills and update PnL.
     */
    public void onNewCandle(Candle candle) {
        // CRITICAL: Track last price for live PnL display
        lastPrices.put(candle.getSymbol(), candle.getClose());

        if (simulationEnabled) {
            // Check for entry fills
            checkOrderFills(candle);

            // Check for partial profit targets
            checkPartialProfitTargets(candle);

            // Check for trailing stop updates
            updateTrailingStops(candle);

            // Check for stop/target hits on existing positions
            checkStopTargetHits(candle);
        }

        // Update unrealized PnL
        updateUnrealizedPnl(candle);
    }

    /**
     * Get the last known price for a symbol.
     * Used for live PnL calculations in the dashboard.
     */
    public double getLastPrice(String symbol) {
        return lastPrices.getOrDefault(symbol, 0.0);
    }

    /**
     * Check if any active orders should fill based on current candle.
     */
    private void checkOrderFills(Candle candle) {
        List<Order> orders = activeOrders.get(candle.getSymbol());
        if (orders == null || orders.isEmpty()) {
            return;
        }

        // Iterate using Iterator to allow removal during iteration
        Iterator<Order> iterator = orders.iterator();
        while (iterator.hasNext()) {
            Order order = iterator.next();
            if (!order.isActive()) {
                iterator.remove();
                continue;
            }

            boolean filled = false;
            double fillPrice = 0;

            if (order.getSide() == OrderSide.BUY) {
                // Buy limit fills when price drops to or below limit
                if (candle.getLow() <= order.getLimitPrice()) {
                    filled = true;
                    fillPrice = order.getLimitPrice();
                }
            } else { // SELL
                // Sell limit fills when price rises to or above limit
                if (candle.getHigh() >= order.getLimitPrice()) {
                    filled = true;
                    fillPrice = order.getLimitPrice();
                }
            }

            if (filled) {
                executeFill(order, fillPrice, candle.getTimestamp());
                iterator.remove();
            }
        }

        // Clean up empty lists
        if (orders.isEmpty()) {
            activeOrders.remove(candle.getSymbol());
        }
    }

    /**
     * Print status of all active orders (for debugging).
     */
    public void printActiveOrderStatus() {
        if (activeOrders.isEmpty()) {
            System.out.println("  [ExecutionEngine] No active orders pending");
        } else {
            for (Map.Entry<String, List<Order>> entry : activeOrders.entrySet()) {
                for (Order order : entry.getValue()) {
                    System.out.println("  [ExecutionEngine] PENDING ORDER: " + order.getSymbol() + " " +
                                       order.getSide() + " @ limit " + String.format("%.2f", order.getLimitPrice()) +
                                       " (waiting for fill)");
                }
            }
        }
    }

    /**
     * Check and execute partial profit targets.
     */
    private void checkPartialProfitTargets(Candle candle) {
        if (!accountState.hasPosition(candle.getSymbol())) {
            return;
        }

        Position position = accountState.getPosition(candle.getSymbol());
        EnhancedOrderLevels levels = orderLevels.get(candle.getSymbol());

        if (position == null || levels == null) {
            return;
        }

        // Check each partial target
        for (PartialTarget target : levels.partialTargets) {
            if (target.executed) {
                continue;
            }

            boolean targetHit = false;
            double exitPrice = target.price;

            if (levels.isLong) {
                if (candle.getHigh() >= target.price) {
                    targetHit = true;
                }
            } else {
                if (candle.getLow() <= target.price) {
                    targetHit = true;
                }
            }

            if (targetHit) {
                // Calculate quantity to close
                int closeQty = (int) Math.ceil(levels.originalQuantity * target.percentage);
                closeQty = Math.min(closeQty, Math.abs(position.getQuantity()));

                if (closeQty > 0) {
                    executePartialClose(position, closeQty, exitPrice, candle.getTimestamp(),
                            "Partial profit at " + target.rMultiple + "R");
                    target.executed = true;

                    // Move stop to breakeven after first partial
                    if (target.rMultiple >= 1.0 && !levels.stopMovedToBreakeven) {
                        moveStopToBreakeven(levels);
                    }
                }
            }
        }
    }

    /**
     * Update trailing stops based on price movement.
     */
    private void updateTrailingStops(Candle candle) {
        if (!accountState.hasPosition(candle.getSymbol())) {
            return;
        }

        EnhancedOrderLevels levels = orderLevels.get(candle.getSymbol());
        if (levels == null || !levels.trailingStopActive) {
            return;
        }

        // Trail stop at configured distance
        if (levels.isLong) {
            // For long positions, trail below price
            double newStop = candle.getHigh() - levels.trailingDistance;
            if (newStop > levels.currentStopPrice) {
                levels.currentStopPrice = newStop;
                System.out.println("TRAILING STOP: " + candle.getSymbol() +
                        " stop moved to " + String.format("%.2f", newStop));
            }
        } else {
            // For short positions, trail above price
            double newStop = candle.getLow() + levels.trailingDistance;
            if (newStop < levels.currentStopPrice) {
                levels.currentStopPrice = newStop;
                System.out.println("TRAILING STOP: " + candle.getSymbol() +
                        " stop moved to " + String.format("%.2f", newStop));
            }
        }
    }

    /**
     * Move stop to breakeven (+ small buffer for commissions).
     */
    private void moveStopToBreakeven(EnhancedOrderLevels levels) {
        double buffer = levels.riskDistance * 0.1;  // 10% of risk as buffer

        if (levels.isLong) {
            levels.currentStopPrice = levels.entryPrice + buffer;
        } else {
            levels.currentStopPrice = levels.entryPrice - buffer;
        }

        levels.stopMovedToBreakeven = true;
        levels.trailingStopActive = true;
        levels.trailingDistance = levels.riskDistance;  // Trail at 1R distance initially

        System.out.println("BREAKEVEN: Stop moved to " + String.format("%.2f", levels.currentStopPrice));
    }

    /**
     * Check if stop or target is hit for existing positions.
     */
    private void checkStopTargetHits(Candle candle) {
        if (!accountState.hasPosition(candle.getSymbol())) {
            return;
        }

        Position position = accountState.getPosition(candle.getSymbol());
        EnhancedOrderLevels levels = orderLevels.get(candle.getSymbol());

        if (position == null || levels == null) {
            return;
        }

        boolean exitTriggered = false;
        double exitPrice = 0;
        String exitReason = "";

        if (levels.isLong) {
            // Check stop hit (below stop price)
            if (candle.getLow() <= levels.currentStopPrice) {
                exitTriggered = true;
                exitPrice = levels.currentStopPrice;
                exitReason = levels.stopMovedToBreakeven ? "Breakeven stop hit" : "Stop hit";
            }
            // Check final target hit (above target price)
            else if (candle.getHigh() >= levels.finalTargetPrice) {
                exitTriggered = true;
                exitPrice = levels.finalTargetPrice;
                exitReason = "Final target hit";
            }
        } else {
            // Check stop hit (above stop price)
            if (candle.getHigh() >= levels.currentStopPrice) {
                exitTriggered = true;
                exitPrice = levels.currentStopPrice;
                exitReason = levels.stopMovedToBreakeven ? "Breakeven stop hit" : "Stop hit";
            }
            // Check final target hit (below target price)
            else if (candle.getLow() <= levels.finalTargetPrice) {
                exitTriggered = true;
                exitPrice = levels.finalTargetPrice;
                exitReason = "Final target hit";
            }
        }

        if (exitTriggered) {
            closePosition(position, exitPrice, candle.getTimestamp(), exitReason);
            orderLevels.remove(candle.getSymbol());
        }
    }

    /**
     * Execute a fill for an order.
     */
    private void executeFill(Order order, double fillPrice, Instant fillTime) {
        order.recordFill(order.getQuantity(), fillPrice);

        // Update position in account
        int positionDelta = order.getSide() == OrderSide.BUY ? order.getQuantity() : -order.getQuantity();
        accountState.updatePosition(order.getSymbol(), positionDelta, fillPrice);

        // Update entry price in order levels
        EnhancedOrderLevels levels = orderLevels.get(order.getSymbol());
        if (levels != null) {
            levels.entryPrice = fillPrice;
            levels.riskDistance = Math.abs(fillPrice - levels.originalStopPrice);

            // Calculate partial target prices based on R-multiples
            if (levels.partialTargets != null) {
                for (PartialTarget target : levels.partialTargets) {
                    if (levels.isLong) {
                        target.price = fillPrice + (levels.riskDistance * target.rMultiple);
                    } else {
                        target.price = fillPrice - (levels.riskDistance * target.rMultiple);
                    }
                }
            }
        }

        System.out.println("ENTRY FILLED: " + order.getSymbol() + " " + order.getSide() +
                          " " + order.getQuantity() + " @ " + String.format("%.2f", fillPrice));

        // CRITICAL: Notify listener that position is opened
        if (executionListener != null) {
            executionListener.onPositionOpened(order.getSymbol(), order.getSide(), fillPrice, order.getQuantity());
        }
    }

    /**
     * Execute a partial close of position.
     */
    private void executePartialClose(Position position, int quantity, double exitPrice,
                                     Instant exitTime, String reason) {
        String symbol = position.getSymbol();
        double entryPrice = position.getAvgEntryPrice();

        // Calculate realized PnL for this partial
        double tickValue = tickValues.getOrDefault(symbol, 12.50);
        double priceDiff = position.isLong() ? (exitPrice - entryPrice) : (entryPrice - exitPrice);
        double realizedPnl = priceDiff * quantity * tickValue;

        // Update account with realized PnL
        accountState.recordRealizedPnL(realizedPnl);

        // Update position quantity
        int closeQty = position.isLong() ? -quantity : quantity;
        accountState.updatePosition(symbol, closeQty, exitPrice);

        System.out.println("PARTIAL EXIT: " + symbol + " " + quantity + " @ " +
                          String.format("%.2f", exitPrice) + " | PnL: $" +
                          String.format("%.2f", realizedPnl) + " | " + reason);
    }

    /**
     * Close a position at the given price.
     */
    private void closePosition(Position position, double exitPrice, Instant exitTime, String reason) {
        String symbol = position.getSymbol();
        double entryPrice = position.getAvgEntryPrice();
        int quantity = Math.abs(position.getQuantity());
        OrderSide side = position.getSide();

        // Calculate realized PnL
        double tickValue = tickValues.getOrDefault(symbol, 12.50);
        double priceDiff = position.isLong() ? (exitPrice - entryPrice) : (entryPrice - exitPrice);
        double realizedPnl = priceDiff * quantity * tickValue;

        // Create trade record
        Trade trade = Trade.builder()
                .symbol(symbol)
                .side(side)
                .quantity(quantity)
                .entryPrice(entryPrice)
                .exitPrice(exitPrice)
                .entryTime(position.getOpenedAt())
                .exitTime(exitTime)
                .realizedPnL(realizedPnl)
                .notes(reason)
                .build();

        completedTrades.add(trade);

        // Update account with realized PnL
        accountState.recordRealizedPnL(realizedPnl);

        // Close position
        int closeQuantity = position.isLong() ? -quantity : quantity;
        accountState.updatePosition(symbol, closeQuantity, exitPrice);

        System.out.println("EXIT FILLED: " + symbol + " @ " + String.format("%.2f", exitPrice) +
                          " | PnL: $" + String.format("%.2f", realizedPnl) + " | " + reason);

        // CRITICAL: Notify listener that position is closed
        if (executionListener != null) {
            boolean isWin = realizedPnl > 0;
            executionListener.onPositionClosed(symbol, realizedPnl, isWin);
        }
    }

    /**
     * Update unrealized PnL based on current candle prices.
     */
    private void updateUnrealizedPnl(Candle candle) {
        Map<String, Double> currentPrices = new HashMap<>();
        currentPrices.put(candle.getSymbol(), candle.getClose());

        accountState.updateUnrealizedPnL(currentPrices, tickValues);
    }

    /**
     * Get all completed trades.
     */
    public List<Trade> getCompletedTrades() {
        return new ArrayList<>(completedTrades);
    }

    /**
     * Get current account state.
     */
    public AccountState getAccountState() {
        return accountState;
    }

    /**
     * Get tick value for a symbol.
     */
    public double getTickValue(String symbol) {
        return tickValues.getOrDefault(symbol, 12.50);
    }

    /**
     * Set custom tick value for a symbol.
     */
    public void setTickValue(String symbol, double tickValue) {
        tickValues.put(symbol, tickValue);
    }

    /**
     * Get all active (pending) orders as a flat map (first order per symbol for compatibility).
     * Use getActiveOrdersList() for full list.
     */
    public Map<String, Order> getActiveOrders() {
        Map<String, Order> result = new HashMap<>();
        for (Map.Entry<String, List<Order>> entry : activeOrders.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                result.put(entry.getKey(), entry.getValue().get(0));
            }
        }
        return result;
    }

    /**
     * Get all active orders for a symbol.
     */
    public List<Order> getActiveOrdersList(String symbol) {
        return activeOrders.getOrDefault(symbol, new ArrayList<>());
    }

    /**
     * Remove all orders for a symbol (e.g., after cancellation).
     */
    public void removeOrder(String symbol) {
        activeOrders.remove(symbol);
    }

    /**
     * Remove a specific order by order ID.
     */
    public void removeOrderById(String symbol, String orderId) {
        List<Order> orders = activeOrders.get(symbol);
        if (orders != null) {
            orders.removeIf(o -> o.getOrderId().equals(orderId));
            if (orders.isEmpty()) {
                activeOrders.remove(symbol);
            }
        }
    }

    /**
     * Get current order levels for a symbol.
     */
    public EnhancedOrderLevels getOrderLevels(String symbol) {
        return orderLevels.get(symbol);
    }

    /**
     * Enhanced order levels with partial profit and trailing stop support.
     */
    public static class EnhancedOrderLevels {
        double entryPrice;
        double originalStopPrice;
        double currentStopPrice;
        double finalTargetPrice;
        double riskDistance;
        double trailingDistance;
        boolean isLong;
        int originalQuantity;
        TradeTier tier;
        List<PartialTarget> partialTargets;
        boolean stopMovedToBreakeven;
        boolean trailingStopActive;

        public EnhancedOrderLevels(double entryPrice, double stopPrice, double targetPrice,
                                   OrderSide side, int quantity, TradeTier tier) {
            this(entryPrice, stopPrice, targetPrice, side, quantity, tier, tier.getPartialProfitTargets());
        }

        public EnhancedOrderLevels(double entryPrice, double stopPrice, double targetPrice,
                                   OrderSide side, int quantity, TradeTier tier,
                                   double[][] partialProfitTargets) {
            this.entryPrice = entryPrice;
            this.originalStopPrice = stopPrice;
            this.currentStopPrice = stopPrice;
            this.finalTargetPrice = targetPrice;
            this.isLong = (side == OrderSide.BUY);
            this.originalQuantity = quantity;
            this.tier = tier;
            this.riskDistance = Math.abs(entryPrice - stopPrice);
            this.stopMovedToBreakeven = false;
            this.trailingStopActive = false;
            this.trailingDistance = riskDistance;

            // Initialize partial targets
            this.partialTargets = new ArrayList<>();
            if (partialProfitTargets != null) {
                for (double[] target : partialProfitTargets) {
                    double rMultiple = target[0];
                    double percentage = target[1];
                    double price;

                    if (isLong) {
                        price = entryPrice + (riskDistance * rMultiple);
                    } else {
                        price = entryPrice - (riskDistance * rMultiple);
                    }

                    partialTargets.add(new PartialTarget(rMultiple, percentage, price));
                }
            }
        }

        // Getters
        public double getEntryPrice() { return entryPrice; }
        public double getCurrentStopPrice() { return currentStopPrice; }
        public double getFinalTargetPrice() { return finalTargetPrice; }
        public boolean isStopMovedToBreakeven() { return stopMovedToBreakeven; }
        public TradeTier getTier() { return tier; }
    }

    /**
     * Represents a partial profit target.
     */
    private static class PartialTarget {
        final double rMultiple;
        final double percentage;
        double price;
        boolean executed;

        PartialTarget(double rMultiple, double percentage, double price) {
            this.rMultiple = rMultiple;
            this.percentage = percentage;
            this.price = price;
            this.executed = false;
        }
    }
}
