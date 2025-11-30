package com.topstep.trading.execution;

import com.topstep.trading.domain.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ExecutionEngine handles order execution and position management.
 *
 * In backtest mode:
 * - Simulates fills based on candle prices
 * - Updates positions and PnL
 * - Tracks completed trades
 *
 * In live/sim mode (future):
 * - Will delegate to TradingConnector
 * - React to real fill notifications
 *
 * Week 2: Focuses on backtest mode with simulated fills.
 */
public class ExecutionEngine {

    private final AccountState accountState;
    private final Map<String, Order> activeOrders;
    private final Map<String, Double> tickValues;
    private final List<Trade> completedTrades;

    // Track stop/target levels for each position
    private final Map<String, OrderLevels> orderLevels;

    public ExecutionEngine(AccountState accountState) {
        this.accountState = accountState;
        this.activeOrders = new HashMap<>();
        this.completedTrades = new ArrayList<>();
        this.tickValues = new HashMap<>();
        this.orderLevels = new HashMap<>();

        // Set default tick values
        initializeTickValues();
    }

    /**
     * Initialize tick values for common futures symbols.
     */
    private void initializeTickValues() {
        tickValues.put("ES", 12.50);   // E-mini S&P 500
        tickValues.put("NQ", 5.00);    // E-mini NASDAQ 100
        tickValues.put("MES", 1.25);   // Micro E-mini S&P 500
        tickValues.put("MNQ", 0.50);   // Micro E-mini NASDAQ 100
        tickValues.put("YM", 5.00);    // E-mini Dow
        tickValues.put("RTY", 5.00);   // E-mini Russell 2000
    }

    /**
     * Accept a new approved order for execution.
     *
     * @param order Approved order from risk engine
     */
    public void submitOrder(Order order) {
        if (order == null) {
            throw new IllegalArgumentException("Order cannot be null");
        }

        order.updateStatus(OrderStatus.SUBMITTED);
        activeOrders.put(order.getSymbol(), order);
    }

    /**
     * Submit order with stop and target levels.
     *
     * @param order Approved order
     * @param stopPrice Stop loss price
     * @param targetPrice Target profit price
     */
    public void submitOrder(Order order, double stopPrice, double targetPrice) {
        submitOrder(order);

        OrderLevels levels = new OrderLevels(stopPrice, targetPrice);
        orderLevels.put(order.getSymbol(), levels);
    }

    /**
     * Process a new candle - check for fills and update PnL.
     *
     * @param candle New market candle
     */
    public void onNewCandle(Candle candle) {
        // Check for entry fills
        checkOrderFills(candle);

        // Check for stop/target hits on existing positions
        checkStopTargetHits(candle);

        // Update unrealized PnL
        updateUnrealizedPnl(candle);
    }

    /**
     * Check if any active orders should fill based on current candle.
     */
    private void checkOrderFills(Candle candle) {
        Order order = activeOrders.get(candle.getSymbol());
        if (order == null || !order.isActive()) {
            return;
        }

        // Simple fill logic: if candle touches limit price, fill at limit price
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
            activeOrders.remove(candle.getSymbol());
        }
    }

    /**
     * Check if stop or target is hit for existing positions.
     */
    private void checkStopTargetHits(Candle candle) {
        if (!accountState.hasPosition(candle.getSymbol())) {
            return;
        }

        Position position = accountState.getPosition(candle.getSymbol());
        OrderLevels levels = orderLevels.get(candle.getSymbol());

        if (position == null || levels == null) {
            return;
        }

        boolean exitTriggered = false;
        double exitPrice = 0;
        String exitReason = "";

        if (position.isLong()) {
            // Check stop hit (below stop price)
            if (candle.getLow() <= levels.stopPrice) {
                exitTriggered = true;
                exitPrice = levels.stopPrice;
                exitReason = "Stop hit";
            }
            // Check target hit (above target price)
            else if (candle.getHigh() >= levels.targetPrice) {
                exitTriggered = true;
                exitPrice = levels.targetPrice;
                exitReason = "Target hit";
            }
        } else if (position.isShort()) {
            // Check stop hit (above stop price)
            if (candle.getHigh() >= levels.stopPrice) {
                exitTriggered = true;
                exitPrice = levels.stopPrice;
                exitReason = "Stop hit";
            }
            // Check target hit (below target price)
            else if (candle.getLow() <= levels.targetPrice) {
                exitTriggered = true;
                exitPrice = levels.targetPrice;
                exitReason = "Target hit";
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
        // Record fill in order
        order.recordFill(order.getQuantity(), fillPrice);

        // Update position in account
        int positionDelta = order.getSide() == OrderSide.BUY ? order.getQuantity() : -order.getQuantity();
        accountState.updatePosition(order.getSymbol(), positionDelta, fillPrice);

        System.out.println("ENTRY FILLED: " + order.getSymbol() + " " + order.getSide() +
                          " " + order.getQuantity() + " @ " + String.format("%.2f", fillPrice));
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
     * Helper class to store stop and target levels.
     */
    private static class OrderLevels {
        final double stopPrice;
        final double targetPrice;

        OrderLevels(double stopPrice, double targetPrice) {
            this.stopPrice = stopPrice;
            this.targetPrice = targetPrice;
        }
    }
}
