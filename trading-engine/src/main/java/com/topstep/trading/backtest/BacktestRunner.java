package com.topstep.trading.backtest;

import com.topstep.trading.domain.*;
import com.topstep.trading.event.EventBus;
import com.topstep.trading.event.StrategySignalEvent;
import com.topstep.trading.risk.PropFirmRiskEngine;
import com.topstep.trading.risk.RiskDecision;
import com.topstep.trading.strategy.TradingStrategy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Backtest runner that simulates trading strategy on historical data.
 *
 * Flow:
 * 1. Load historical candles
 * 2. For each candle:
 *    - Update strategy
 *    - Strategy emits signals
 *    - Risk engine evaluates signals
 *    - Execute approved trades
 *    - Track PnL
 * 3. Generate backtest report
 */
public class BacktestRunner {

    private final TradingStrategy strategy;
    private final PropFirmRiskEngine riskEngine;
    private final EventBus eventBus;
    private final AccountState accountState;
    private final RiskLimits riskLimits;
    private final BacktestContext context;

    private final List<Trade> completedTrades;
    private final Map<String, Order> activeOrders;
    private final Map<String, Double> tickValues;

    public BacktestRunner(TradingStrategy strategy, AccountState accountState, RiskLimits riskLimits) {
        this.strategy = strategy;
        this.accountState = accountState;
        this.riskLimits = riskLimits;
        this.riskEngine = new PropFirmRiskEngine();
        this.eventBus = new EventBus();
        this.context = new BacktestContext(accountState);

        this.completedTrades = new ArrayList<>();
        this.activeOrders = new HashMap<>();
        this.tickValues = new HashMap<>();

        // Set default tick values
        this.tickValues.put("ES", 12.50);
        this.tickValues.put("NQ", 5.00);
        this.tickValues.put("MES", 1.25);
        this.tickValues.put("MNQ", 0.50);

        // Subscribe to strategy signals
        eventBus.subscribe(StrategySignalEvent.class, this::handleStrategySignal);
    }

    /**
     * Run the backtest with historical candles.
     */
    public BacktestReport run(List<Candle> candles) {
        System.out.println("Starting backtest with " + candles.size() + " candles...");

        strategy.initialize();

        int candleCount = 0;
        for (Candle candle : candles) {
            candleCount++;

            // Update current time in context
            context.setCurrentTime(candle.getTimestamp());

            // Check if any active orders should fill
            checkOrderFills(candle);

            // Update unrealized PnL
            updateUnrealizedPnl(candle);

            // Feed candle to strategy
            strategy.onCandle(candle, context);

            // Check for daily reset
            if (candleCount % 100 == 0) {
                accountState.resetDailyCounters();
            }

            // Break if account is breached
            if (!riskEngine.isAccountInGoodStanding(accountState, riskLimits)) {
                System.out.println("Account breached limits at candle " + candleCount);
                break;
            }

            // Break if profit target met
            if (riskEngine.hasMetProfitTarget(accountState, riskLimits)) {
                System.out.println("Profit target reached at candle " + candleCount);
                break;
            }
        }

        strategy.shutdown();

        System.out.println("Backtest completed. Processed " + candleCount + " candles.");
        return generateReport();
    }

    /**
     * Handle strategy signal event.
     */
    private void handleStrategySignal(StrategySignalEvent signal) {
        // Evaluate against risk limits
        RiskDecision decision = riskEngine.evaluate(signal, accountState, riskLimits);

        if (decision.isAllowed()) {
            System.out.println("Signal APPROVED: " + signal.getReason());
            System.out.println("  " + decision.getReason());

            // Place the order
            Order order = decision.getOrder();
            activeOrders.put(order.getSymbol(), order);
        } else {
            System.out.println("Signal DENIED: " + signal.getReason());
            System.out.println("  Reason: " + decision.getReason());
        }
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
            if (candle.getLow() <= order.getLimitPrice()) {
                filled = true;
                fillPrice = order.getLimitPrice();
            }
        } else { // SELL
            if (candle.getHigh() >= order.getLimitPrice()) {
                filled = true;
                fillPrice = order.getLimitPrice();
            }
        }

        if (filled) {
            // Record fill
            order.recordFill(order.getQuantity(), fillPrice);
            accountState.updatePosition(candle.getSymbol(),
                    order.getSide() == OrderSide.BUY ? order.getQuantity() : -order.getQuantity(),
                    fillPrice);

            System.out.println("Order FILLED: " + order);

            // Create trade record (simplified - would need stop/target tracking in real backtest)
            Trade trade = new Trade(
                    candle.getSymbol(),
                    order.getSide(),
                    order.getQuantity(),
                    fillPrice,
                    fillPrice, // Exit price (placeholder)
                    candle.getTimestamp(),
                    candle.getTimestamp(),
                    0.0 // PnL (placeholder)
            );
            completedTrades.add(trade);

            activeOrders.remove(candle.getSymbol());
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
     * Generate backtest report.
     */
    private BacktestReport generateReport() {
        return new BacktestReport(completedTrades, accountState, riskLimits);
    }
}
