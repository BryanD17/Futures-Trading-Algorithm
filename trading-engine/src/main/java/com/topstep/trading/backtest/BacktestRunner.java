package com.topstep.trading.backtest;

import com.topstep.trading.domain.*;
import com.topstep.trading.event.EventBus;
import com.topstep.trading.event.StrategySignalEvent;
import com.topstep.trading.execution.ExecutionEngine;
import com.topstep.trading.risk.PropFirmRiskEngine;
import com.topstep.trading.risk.RiskDecision;
import com.topstep.trading.risk.TradingRiskManager;
import com.topstep.trading.strategy.TradingStrategy;

import java.util.List;

/**
 * Backtest runner that simulates trading strategy on historical data.
 *
 * Flow:
 * 1. Load historical candles
 * 2. For each candle:
 *    - Check for new trading session (daily reset)
 *    - Update strategy
 *    - Strategy emits signals
 *    - TradingRiskManager validates (correlation, consecutive loss, position limits)
 *    - PropFirmRiskEngine evaluates (DLL, MLL, position sizing)
 *    - ExecutionEngine processes approved orders
 *    - Track PnL
 * 3. Generate backtest report
 *
 * CRITICAL: Implements ExecutionEngine.ExecutionListener to receive fill/close notifications
 * and properly track position state in TradingRiskManager.
 */
public class BacktestRunner implements ExecutionEngine.ExecutionListener {

    private final TradingStrategy strategy;
    private final PropFirmRiskEngine riskEngine;
    private final TradingRiskManager tradingRiskManager;  // NEW: For correlation/loss tracking
    private final ExecutionEngine executionEngine;
    private final TradingSessionManager sessionManager;
    private final EventBus eventBus;
    private final AccountState accountState;
    private final RiskLimits riskLimits;
    private final BacktestContext context;

    public BacktestRunner(TradingStrategy strategy, AccountState accountState, RiskLimits riskLimits) {
        this(strategy, accountState, riskLimits, null);
    }

    public BacktestRunner(TradingStrategy strategy, AccountState accountState, RiskLimits riskLimits, EventBus sharedEventBus) {
        this.strategy = strategy;
        this.accountState = accountState;
        this.riskLimits = riskLimits;
        this.riskEngine = new PropFirmRiskEngine();
        this.executionEngine = new ExecutionEngine(accountState);
        this.sessionManager = new TradingSessionManager();
        // CRITICAL: Use shared EventBus if provided, otherwise create new one
        this.eventBus = (sharedEventBus != null) ? sharedEventBus : new EventBus();
        this.context = new BacktestContext(accountState);
        this.tradingRiskManager = new TradingRiskManager(context);

        // CRITICAL: Register as execution listener to receive fill/close notifications
        // This ensures TradingRiskManager is notified ONLY when orders actually fill
        executionEngine.setExecutionListener(this);

        // Publish PositionClosedEvent from the backtest close funnel (scalp
        // re-arm subscribes; no-op for legacy consumers).
        executionEngine.setEventBus(eventBus);

        // Subscribe to strategy signals on the SAME EventBus the strategy uses
        eventBus.subscribe(StrategySignalEvent.class, this::handleStrategySignal);
    }

    /**
     * Run the backtest with historical candles (single instrument).
     */
    public BacktestReport run(List<Candle> candles) {
        return run(candles, null);
    }

    /**
     * Run the backtest with primary and SMT instrument candles.
     * Both candle lists are interleaved by timestamp for proper SMT divergence detection.
     */
    public BacktestReport run(List<Candle> primaryCandles, List<Candle> smtCandles) {
        // Merge and sort candles by timestamp if SMT candles provided
        List<Candle> allCandles;
        if (smtCandles != null && !smtCandles.isEmpty()) {
            allCandles = new java.util.ArrayList<>(primaryCandles.size() + smtCandles.size());
            allCandles.addAll(primaryCandles);
            allCandles.addAll(smtCandles);
            allCandles.sort((a, b) -> a.getTimestamp().compareTo(b.getTimestamp()));
            System.out.println("Starting backtest with " + primaryCandles.size() + " primary + " +
                             smtCandles.size() + " SMT candles (" + allCandles.size() + " total)...");
        } else {
            allCandles = primaryCandles;
            System.out.println("Starting backtest with " + allCandles.size() + " candles (no SMT data)...");
        }

        // CRITICAL: Start EventBus BEFORE strategy initialization
        // Without this, all signals published by strategy are silently dropped!
        eventBus.start();
        System.out.println("✓ EventBus started");

        strategy.initialize();

        int candleCount = 0;
        for (Candle candle : allCandles) {
            candleCount++;

            // Update current time in context
            context.setCurrentTime(candle.getTimestamp());

            // Check for new trading session and reset if needed
            if (sessionManager.hasNewSessionStarted(candle.getTimestamp())) {
                accountState.startNewTradingDay(sessionManager.getCurrentSessionDate());
                System.out.println("\n=== NEW TRADING SESSION: " + sessionManager.getCurrentSessionDate() + " ===");
                System.out.println("Starting Balance: $" + String.format("%.2f", accountState.getCurrentBalance()));
                System.out.println();
            }

            // ExecutionEngine processes fills and updates
            executionEngine.onNewCandle(candle);

            // Feed candle to strategy (strategy routes based on symbol)
            strategy.onCandle(candle, context);

            // Break if account is breached
            if (!riskEngine.isAccountInGoodStanding(accountState, riskLimits)) {
                System.out.println("\n❌ ACCOUNT BREACHED LIMITS at candle " + candleCount);
                System.out.println("   Daily PnL: $" + String.format("%.2f", accountState.getNetDailyPnl()));
                System.out.println("   Total Drawdown: $" + String.format("%.2f",
                    accountState.getHighestEndOfDayBalance() - accountState.getEquity()));
                break;
            }

            // Break if profit target met
            if (riskEngine.hasMetProfitTarget(accountState, riskLimits)) {
                System.out.println("\n✓ PROFIT TARGET REACHED at candle " + candleCount);
                System.out.println("   Total PnL: $" + String.format("%.2f", accountState.getRealizedPnL()));
                break;
            }
        }

        strategy.onSessionEnd();
        strategy.shutdown();

        // CRITICAL: Stop EventBus to prevent thread leaks
        eventBus.stop();
        System.out.println("✓ EventBus stopped");

        // Show any unfilled orders at end of backtest
        System.out.println("\n--- End of Backtest Order Status ---");
        executionEngine.printActiveOrderStatus();

        System.out.println("\nBacktest completed. Processed " + candleCount + " candles.");
        return generateReport();
    }

    /**
     * Handle strategy signal event.
     */
    private void handleStrategySignal(StrategySignalEvent signal) {
        System.out.println("\n>>> SIGNAL RECEIVED by BacktestRunner <<<");
        System.out.println("  Symbol: " + signal.getSymbol());
        System.out.println("  Side: " + signal.getSide());
        System.out.println("  Entry: " + String.format("%.2f", signal.getEntryPrice()));
        System.out.println("  Stop: " + String.format("%.2f", signal.getStopPrice()));
        System.out.println("  Target: " + String.format("%.2f", signal.getTargetPrice()));
        System.out.println("  Reason: " + signal.getReason());

        // STEP 1: Validate with TradingRiskManager (correlation, consecutive loss, position limits)
        RiskDecision riskManagerDecision = tradingRiskManager.validateSignal(signal);
        if (!riskManagerDecision.isApproved()) {
            System.out.println("\n❌ Signal BLOCKED by TradingRiskManager: " + signal.getReason());
            System.out.println("  Reason: " + riskManagerDecision.getReason());
            return;
        }
        System.out.println("  ✓ TradingRiskManager APPROVED");

        // STEP 2: Evaluate against prop firm risk limits (DLL, MLL, position sizing)
        RiskDecision decision = riskEngine.evaluate(signal, accountState, riskLimits);

        if (decision.isAllowed()) {
            System.out.println("  ✓ PropFirmRiskEngine APPROVED");
            System.out.println("  " + decision.getReason());

            // CRITICAL FIX: Removed early recordPositionOpened call here
            // Position will be recorded when ExecutionEngine actually fills the order
            // via the ExecutionListener.onPositionOpened callback

            // Submit order to execution engine with stop/target levels
            Order order = decision.getOrder();
            if (order != null) {
                System.out.println("  Order submitted: " + order.getSide() + " " + order.getQuantity() +
                                   " @ limit " + String.format("%.2f", order.getLimitPrice()));
                executionEngine.submitOrder(order, signal.getStopPrice(), signal.getTargetPrice());
            } else {
                System.out.println("  ⚠ WARNING: decision.getOrder() returned null!");
            }
        } else {
            System.out.println("\n❌ Signal DENIED by PropFirmRiskEngine: " + signal.getReason());
            System.out.println("  Reason: " + decision.getReason());
        }
    }

    /**
     * Notify risk manager of position close (for consecutive loss tracking).
     * Should be called when a trade completes.
     */
    public void notifyPositionClosed(String symbol, double pnl) {
        boolean isWin = pnl > 0;
        tradingRiskManager.recordPositionClosed(symbol, pnl, isWin);
    }

    /**
     * Get the trading risk manager for status reporting.
     */
    public TradingRiskManager getTradingRiskManager() {
        return tradingRiskManager;
    }

    /**
     * Generate backtest report.
     */
    private BacktestReport generateReport() {
        List<Trade> trades = executionEngine.getCompletedTrades();
        return new BacktestReport(trades, accountState, riskLimits);
    }

    // ============================================
    // ExecutionEngine.ExecutionListener implementation
    // CRITICAL: These methods are called when orders fill/close,
    // ensuring TradingRiskManager has accurate position state.
    // ============================================

    @Override
    public void onPositionOpened(String symbol, OrderSide side, double entryPrice, int quantity) {
        // Now we notify risk manager when order actually fills, not when signal is approved
        boolean isBullish = side == OrderSide.BUY;
        tradingRiskManager.recordPositionOpened(symbol, isBullish, entryPrice);
        System.out.println("  [RiskManager] Position opened: " + symbol + " " + side + " @ " + entryPrice);
    }

    @Override
    public void onPositionClosed(String symbol, double pnl, boolean isWin) {
        // Notify risk manager of position close for consecutive loss/win tracking
        tradingRiskManager.recordPositionClosed(symbol, pnl, isWin);
        System.out.println("  [RiskManager] Position closed: " + symbol + " PnL=$" +
                          String.format("%.2f", pnl) + " (" + (isWin ? "WIN" : "LOSS") + ")");
    }
}
