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
 */
public class BacktestRunner {

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
        this.strategy = strategy;
        this.accountState = accountState;
        this.riskLimits = riskLimits;
        this.riskEngine = new PropFirmRiskEngine();
        this.executionEngine = new ExecutionEngine(accountState);
        this.sessionManager = new TradingSessionManager();
        this.eventBus = new EventBus();
        this.context = new BacktestContext(accountState);
        this.tradingRiskManager = new TradingRiskManager(context);  // NEW

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

            // Check for new trading session and reset if needed
            if (sessionManager.hasNewSessionStarted(candle.getTimestamp())) {
                accountState.startNewTradingDay(sessionManager.getCurrentSessionDate());
                System.out.println("\n=== NEW TRADING SESSION: " + sessionManager.getCurrentSessionDate() + " ===");
                System.out.println("Starting Balance: $" + String.format("%.2f", accountState.getCurrentBalance()));
                System.out.println();
            }

            // ExecutionEngine processes fills and updates
            executionEngine.onNewCandle(candle);

            // Feed candle to strategy
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

        strategy.shutdown();

        System.out.println("\nBacktest completed. Processed " + candleCount + " candles.");
        return generateReport();
    }

    /**
     * Handle strategy signal event.
     */
    private void handleStrategySignal(StrategySignalEvent signal) {
        // STEP 1: Validate with TradingRiskManager (correlation, consecutive loss, position limits)
        RiskDecision riskManagerDecision = tradingRiskManager.validateSignal(signal);
        if (!riskManagerDecision.isApproved()) {
            System.out.println("\n❌ Signal BLOCKED by TradingRiskManager: " + signal.getReason());
            System.out.println("  Reason: " + riskManagerDecision.getReason());
            return;
        }

        // STEP 2: Evaluate against prop firm risk limits (DLL, MLL, position sizing)
        RiskDecision decision = riskEngine.evaluate(signal, accountState, riskLimits);

        if (decision.isAllowed()) {
            System.out.println("\n✓ Signal APPROVED: " + signal.getReason());
            System.out.println("  " + decision.getReason());

            // Record position opened in risk manager (for tracking)
            boolean isBullish = signal.getSide() == OrderSide.BUY;
            tradingRiskManager.recordPositionOpened(signal.getSymbol(), isBullish, signal.getEntryPrice());

            // Submit order to execution engine with stop/target levels
            Order order = decision.getOrder();
            executionEngine.submitOrder(order, signal.getStopPrice(), signal.getTargetPrice());
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
}
