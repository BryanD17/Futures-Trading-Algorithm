package com.topstep.trading;

import com.topstep.trading.connector.MockConnector;
import com.topstep.trading.connector.TradingConnector;
import com.topstep.trading.domain.AccountState;
import com.topstep.trading.domain.Candle;
import com.topstep.trading.domain.Order;
import com.topstep.trading.domain.RiskLimits;
import com.topstep.trading.event.EventBus;
import com.topstep.trading.event.StrategySignalEvent;
import com.topstep.trading.execution.ExecutionEngine;
import com.topstep.trading.risk.PropFirmRiskEngine;
import com.topstep.trading.risk.RiskDecision;
import com.topstep.trading.strategy.IctHighConfluenceStrategy;
import com.topstep.trading.strategy.StrategyContext;
import com.topstep.trading.strategy.TradingStrategy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SIM mode engine runner for live trading simulation.
 *
 * Uses MockConnector to generate streaming market data and
 * processes it through the full trading pipeline:
 * - Strategy analysis
 * - Risk engine evaluation
 * - Execution engine order management
 *
 * This provides a realistic testing environment without
 * connecting to real markets.
 */
public class SimEngineRunner {

    private static final String DEFAULT_SYMBOL = "ES";

    private final TradingConnector connector;
    private final AccountState accountState;
    private final RiskLimits riskLimits;
    private final PropFirmRiskEngine riskEngine;
    private final ExecutionEngine executionEngine;
    private final TradingStrategy strategy;
    private final EventBus eventBus;
    private final StrategyContext strategyContext;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

    /**
     * Create a new SIM engine with default Topstep 50K configuration.
     */
    public SimEngineRunner() {
        this(50_000.0, RiskLimits.topstep50k());
    }

    /**
     * Create a new SIM engine with custom configuration.
     */
    public SimEngineRunner(double startingBalance, RiskLimits riskLimits) {
        // Initialize account
        this.accountState = new AccountState(startingBalance);
        this.riskLimits = riskLimits;

        // Initialize trading components
        this.connector = new MockConnector(startingBalance);
        this.executionEngine = new ExecutionEngine(accountState);
        this.riskEngine = new PropFirmRiskEngine();
        this.eventBus = new EventBus();
        this.strategy = new IctHighConfluenceStrategy(DEFAULT_SYMBOL, "NQ", eventBus);
        this.strategyContext = new StrategyContext(accountState);

        // Subscribe to strategy signals
        eventBus.subscribe(StrategySignalEvent.class, this::handleStrategySignal);

        System.out.println("SIM Engine initialized");
        System.out.println("  Starting Balance: $" + String.format("%.2f", startingBalance));
        System.out.println("  Daily Loss Limit: $" + String.format("%.2f", riskLimits.getDailyLossLimit()));
        System.out.println("  Max Loss Limit: $" + String.format("%.2f", riskLimits.getMaxLossLimit()));
        System.out.println("  Profit Target: $" + String.format("%.2f", riskLimits.getProfitTarget()));
    }

    /**
     * Start the SIM engine.
     */
    public void start() {
        if (running.get()) {
            System.out.println("SIM engine already running");
            return;
        }

        try {
            System.out.println("\n" + "=".repeat(60));
            System.out.println("Starting SIM Mode");
            System.out.println("=".repeat(60));

            // Connect to mock market data
            connector.connect();

            // Initialize strategy
            strategy.initialize();

            // Register with facade
            EngineFacade.getInstance().initialize(
                    EngineFacade.Mode.SIM,
                    accountState,
                    executionEngine,
                    riskLimits,
                    strategy,
                    riskEngine
            );
            EngineFacade.getInstance().setSimRunner(this);

            // Subscribe to market data
            connector.subscribeMarketData(DEFAULT_SYMBOL, this::onMarketData);

            running.set(true);

            System.out.println("\n✓ SIM engine started successfully");
            System.out.println("  Trading symbol: " + DEFAULT_SYMBOL);
            System.out.println("  Connector: " + connector.getName());
            System.out.println("\nWaiting for market data...\n");

            // Keep running until stopped
            shutdownLatch.await();

        } catch (Exception e) {
            System.err.println("Failed to start SIM engine: " + e.getMessage());
            e.printStackTrace();
            stop();
        }
    }

    /**
     * Pause the SIM engine (stops processing signals but keeps receiving data).
     */
    public void pause() {
        if (!running.get()) {
            System.out.println("SIM engine not running");
            return;
        }

        paused.set(true);
        System.out.println("\n⏸ SIM engine PAUSED");
        System.out.println("  No new signals will be processed");
        System.out.println("  Current equity: $" + String.format("%.2f", accountState.getEquity()));
    }

    /**
     * Resume the SIM engine.
     */
    public void resume() {
        if (!running.get()) {
            System.out.println("SIM engine not running");
            return;
        }

        if (!paused.get()) {
            System.out.println("SIM engine not paused");
            return;
        }

        paused.set(false);
        System.out.println("\n▶ SIM engine RESUMED");
    }

    /**
     * Stop the SIM engine.
     */
    public void stop() {
        if (!running.get()) {
            System.out.println("SIM engine not running");
            return;
        }

        System.out.println("\n" + "=".repeat(60));
        System.out.println("Stopping SIM Mode");
        System.out.println("=".repeat(60));

        running.set(false);

        // Shutdown strategy
        strategy.shutdown();

        // Disconnect from market data
        connector.disconnect();

        // Print final stats
        printFinalStats();

        // Release shutdown latch
        shutdownLatch.countDown();

        System.out.println("\n✓ SIM engine stopped");
    }

    /**
     * Handle incoming market data candle.
     */
    private void onMarketData(Candle candle) {
        if (!running.get()) {
            return;
        }

        try {
            // Update context time
            strategyContext.setCurrentTime(candle.getTimestamp());

            // Process through execution engine first (fills, stops, targets)
            executionEngine.onNewCandle(candle);

            // Feed to strategy (only if not paused)
            if (!paused.get()) {
                strategy.onCandle(candle, strategyContext);
            }

            // Check risk limits
            checkRiskLimits();

        } catch (Exception e) {
            System.err.println("Error processing candle: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Handle strategy signal event.
     */
    private void handleStrategySignal(StrategySignalEvent signal) {
        if (paused.get()) {
            System.out.println("\n⏸ Signal ignored (paused): " + signal.getReason());
            return;
        }

        // Evaluate against risk limits
        RiskDecision decision = riskEngine.evaluate(signal, accountState, riskLimits);

        if (decision.isAllowed()) {
            System.out.println("\n✓ Signal APPROVED: " + signal.getReason());
            System.out.println("  " + decision.getReason());

            // Submit order to execution engine
            Order order = decision.getOrder();
            executionEngine.submitOrder(order, signal.getStopPrice(), signal.getTargetPrice());

            // Print account status
            printAccountStatus();

        } else {
            System.out.println("\n❌ Signal DENIED: " + signal.getReason());
            System.out.println("  Reason: " + decision.getReason());
        }
    }

    /**
     * Check if any risk limits are breached.
     */
    private void checkRiskLimits() {
        // Check if account breached limits
        if (!riskEngine.isAccountInGoodStanding(accountState, riskLimits)) {
            System.out.println("\n❌ RISK LIMIT BREACHED!");
            System.out.println("  Daily PnL: $" + String.format("%.2f", accountState.getNetDailyPnl()));
            System.out.println("  Daily Loss Limit: $" + String.format("%.2f", riskLimits.getDailyLossLimit()));
            System.out.println("  Total Drawdown: $" + String.format("%.2f",
                accountState.getHighestEndOfDayBalance() - accountState.getEquity()));
            System.out.println("  Max Loss Limit: $" + String.format("%.2f", riskLimits.getMaxLossLimit()));

            stop();
            return;
        }

        // Check if profit target met
        if (riskEngine.hasMetProfitTarget(accountState, riskLimits)) {
            System.out.println("\n✓ PROFIT TARGET REACHED!");
            System.out.println("  Total PnL: $" + String.format("%.2f", accountState.getRealizedPnL()));
            System.out.println("  Profit Target: $" + String.format("%.2f", riskLimits.getProfitTarget()));

            stop();
        }
    }

    /**
     * Print current account status.
     */
    private void printAccountStatus() {
        System.out.println("  Account Status:");
        System.out.println("    Balance: $" + String.format("%.2f", accountState.getCurrentBalance()));
        System.out.println("    Equity: $" + String.format("%.2f", accountState.getEquity()));
        System.out.println("    Daily PnL: $" + String.format("%.2f", accountState.getNetDailyPnl()));
        System.out.println("    Open Positions: " + accountState.getPositions().size());
    }

    /**
     * Print final statistics.
     */
    private void printFinalStats() {
        System.out.println("\nFinal Statistics:");
        System.out.println("  Starting Balance: $" + String.format("%.2f", accountState.getStartingBalance()));
        System.out.println("  Ending Balance: $" + String.format("%.2f", accountState.getCurrentBalance()));
        System.out.println("  Ending Equity: $" + String.format("%.2f", accountState.getEquity()));
        System.out.println("  Total Realized PnL: $" + String.format("%.2f", accountState.getRealizedPnL()));
        System.out.println("  Total Unrealized PnL: $" + String.format("%.2f", accountState.getUnrealizedPnL()));
        System.out.println("  Completed Trades: " + executionEngine.getCompletedTrades().size());
        System.out.println("  Open Positions: " + accountState.getPositions().size());
    }

    /**
     * Get the account state.
     */
    public AccountState getAccountState() {
        return accountState;
    }

    /**
     * Get the execution engine.
     */
    public ExecutionEngine getExecutionEngine() {
        return executionEngine;
    }

    /**
     * Get the risk limits.
     */
    public RiskLimits getRiskLimits() {
        return riskLimits;
    }

    /**
     * Check if engine is running.
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Check if engine is paused.
     */
    public boolean isPaused() {
        return paused.get();
    }

    /**
     * Main entry point for SIM mode.
     */
    public static void run() {
        SimEngineRunner runner = new SimEngineRunner();

        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nReceived shutdown signal...");
            runner.stop();
        }));

        // Start the engine
        runner.start();
    }
}
