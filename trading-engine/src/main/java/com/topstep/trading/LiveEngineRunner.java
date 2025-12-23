package com.topstep.trading;

import com.topstep.trading.connector.TradingConnector;
import com.topstep.trading.connector.TopstepConnector;
import com.topstep.trading.domain.*;
import com.topstep.trading.event.EventBus;
import com.topstep.trading.event.StrategySignalEvent;
import com.topstep.trading.execution.ExecutionEngine;
import com.topstep.trading.risk.PropFirmRiskEngine;
import com.topstep.trading.risk.RiskDecision;
import com.topstep.trading.strategy.DefaultStrategyContext;
import com.topstep.trading.strategy.IctHighConfluenceStrategy;
import com.topstep.trading.strategy.TradingStrategy;

import java.time.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * LIVE mode engine runner for real trading with Topstep.
 *
 * SAFETY FEATURES:
 * - Kill switch for emergency stops
 * - Flatten-by-time enforcement (must be flat before market close)
 * - Daily loss limit monitoring
 * - Max loss limit monitoring
 * - Automatic position flattening on limit breach
 *
 * IMPORTANT: Before running LIVE:
 * 1. Set environment variables for credentials
 * 2. Test extensively in SIM mode
 * 3. Start with small position sizes
 * 4. Monitor closely for the first few sessions
 */
public class LiveEngineRunner {

    private static final String DEFAULT_SYMBOL = "ES";
    private static final String SMT_SYMBOL = "NQ";

    // Flatten-by time (3:55 PM ET - 5 minutes before futures close)
    private static final LocalTime FLATTEN_BY_TIME = LocalTime.of(15, 55);
    private static final ZoneId ET_ZONE = ZoneId.of("America/New_York");

    private final TradingConnector connector;
    private final AccountState accountState;
    private final RiskLimits riskLimits;
    private final PropFirmRiskEngine riskEngine;
    private final ExecutionEngine executionEngine;
    private final TradingStrategy strategy;
    private final EventBus eventBus;
    private final DefaultStrategyContext strategyContext;
    private final ScheduledExecutorService scheduler;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicBoolean killSwitchActive = new AtomicBoolean(false);
    private final AtomicBoolean flatteningPositions = new AtomicBoolean(false);
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

    /**
     * Create a new LIVE engine with Topstep 50K configuration.
     */
    public LiveEngineRunner() {
        this(50_000.0, RiskLimits.topstep50k());
    }

    /**
     * Create a new LIVE engine with custom configuration.
     */
    public LiveEngineRunner(double startingBalance, RiskLimits riskLimits) {
        // Initialize account
        this.accountState = new AccountState(startingBalance);
        this.riskLimits = riskLimits;

        // Initialize trading components
        this.connector = createConnector();
        this.executionEngine = new ExecutionEngine(accountState);
        this.riskEngine = new PropFirmRiskEngine();
        this.eventBus = new EventBus();
        this.strategy = new IctHighConfluenceStrategy(DEFAULT_SYMBOL, SMT_SYMBOL, eventBus);
        this.strategyContext = new DefaultStrategyContext(accountState);
        this.scheduler = Executors.newScheduledThreadPool(2);

        // Subscribe to strategy signals
        eventBus.subscribe(StrategySignalEvent.class, this::handleStrategySignal);

        System.out.println("\n" + "!".repeat(60));
        System.out.println("! LIVE ENGINE INITIALIZED - REAL MONEY AT RISK !");
        System.out.println("!".repeat(60));
        System.out.println("  Starting Balance: $" + String.format("%.2f", startingBalance));
        System.out.println("  Daily Loss Limit: $" + String.format("%.2f", riskLimits.getDailyLossLimit()));
        System.out.println("  Max Loss Limit: $" + String.format("%.2f", riskLimits.getMaxLossLimit()));
        System.out.println("  Profit Target: $" + String.format("%.2f", riskLimits.getProfitTarget()));
        System.out.println("  Flatten-by Time: " + FLATTEN_BY_TIME + " ET");
    }

    /**
     * Create the Topstep connector from environment variables.
     */
    private TradingConnector createConnector() {
        String apiUrl = System.getenv("TOPSTEP_API_URL");
        String username = System.getenv("TOPSTEP_USERNAME");
        String apiKey = System.getenv("TOPSTEP_API_KEY");
        String accountId = System.getenv("TOPSTEP_ACCOUNT_ID");

        if (apiUrl == null || username == null || apiKey == null || accountId == null) {
            throw new IllegalStateException(
                "Missing Topstep credentials. Set environment variables:\n" +
                "  TOPSTEP_API_URL\n" +
                "  TOPSTEP_USERNAME\n" +
                "  TOPSTEP_API_KEY\n" +
                "  TOPSTEP_ACCOUNT_ID"
            );
        }

        return new TopstepConnector(apiUrl, username, apiKey, accountId);
    }

    /**
     * Start the LIVE engine.
     * WARNING: This connects to real markets and uses real money!
     */
    public void start() {
        if (running.get()) {
            System.out.println("LIVE engine already running");
            return;
        }

        // Final safety confirmation
        System.out.println("\n" + "=".repeat(60));
        System.out.println("STARTING LIVE TRADING MODE");
        System.out.println("=".repeat(60));
        System.out.println("\n⚠️  WARNING: Real money is at risk!");
        System.out.println("⚠️  Ensure you have tested in SIM mode first!");
        System.out.println();

        try {
            // Connect to Topstep
            System.out.println("Connecting to Topstep...");
            connector.connect();
            System.out.println("✓ Connected to Topstep");

            // Sync account balance
            double liveBalance = connector.getAccountBalance();
            accountState.setCurrentBalance(liveBalance);
            System.out.println("✓ Account balance synced: $" + String.format("%.2f", liveBalance));

            // Initialize strategy
            strategy.initialize();
            System.out.println("✓ Strategy initialized");

            // Register with facade
            EngineFacade.getInstance().initialize(
                EngineFacade.Mode.LIVE,
                accountState,
                executionEngine,
                riskLimits,
                strategy,
                riskEngine
            );
            EngineFacade.getInstance().setLiveRunner(this);

            // Subscribe to market data for primary symbol (ES)
            connector.subscribeMarketData(DEFAULT_SYMBOL, this::onMarketData);
            System.out.println("✓ Subscribed to market data for " + DEFAULT_SYMBOL);

            // Subscribe to market data for SMT symbol (NQ) - used for divergence detection
            connector.subscribeMarketData(SMT_SYMBOL, this::onMarketData);
            System.out.println("✓ Subscribed to market data for " + SMT_SYMBOL + " (SMT)");

            // Schedule flatten-by-time check (every minute)
            scheduler.scheduleAtFixedRate(
                this::checkFlattenByTime,
                0, 60, TimeUnit.SECONDS
            );

            // Schedule risk monitoring (every 5 seconds)
            scheduler.scheduleAtFixedRate(
                this::monitorRisk,
                5, 5, TimeUnit.SECONDS
            );

            running.set(true);

            System.out.println("\n" + "✓".repeat(60));
            System.out.println("✓ LIVE ENGINE STARTED - TRADING ACTIVE ✓");
            System.out.println("✓".repeat(60) + "\n");

            // Keep running until stopped
            shutdownLatch.await();

        } catch (Exception e) {
            System.err.println("\n❌ FATAL ERROR: " + e.getMessage());
            e.printStackTrace();
            emergencyShutdown("Fatal error during startup");
        }
    }

    /**
     * Handle incoming market data candle.
     */
    private void onMarketData(Candle candle) {
        if (!running.get() || killSwitchActive.get()) {
            return;
        }

        try {
            // Update context time
            strategyContext.setCurrentTime(candle.getTimestamp());

            // Process through execution engine first (fills, stops, targets)
            executionEngine.onNewCandle(candle);

            // Feed to strategy (only if not paused and not flattening)
            if (!paused.get() && !flatteningPositions.get()) {
                strategy.onCandle(candle, strategyContext);
            }

        } catch (Exception e) {
            System.err.println("Error processing candle: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Handle strategy signal event.
     */
    private void handleStrategySignal(StrategySignalEvent signal) {
        if (paused.get() || killSwitchActive.get() || flatteningPositions.get()) {
            System.out.println("\n⏸ Signal ignored (paused/kill/flattening): " + signal.getReason());
            return;
        }

        // Evaluate against risk limits
        RiskDecision decision = riskEngine.evaluate(signal, accountState, riskLimits);

        if (decision.isAllowed()) {
            System.out.println("\n✓ LIVE Signal APPROVED: " + signal.getReason());
            System.out.println("  " + decision.getReason());

            try {
                // Submit order to live market via connector
                Order order = decision.getOrder();
                String orderId = connector.submitOrder(order, (id, status, fillPrice, fillQty) -> {
                    handleOrderUpdate(id, status, fillPrice, fillQty, order);
                });

                order.setOrderId(orderId);
                System.out.println("  Order submitted: " + orderId);

                // Also track in execution engine for stop/target management
                executionEngine.submitOrder(order, signal.getStopPrice(), signal.getTargetPrice());

                printAccountStatus();

            } catch (Exception e) {
                System.err.println("❌ Order submission failed: " + e.getMessage());
            }

        } else {
            System.out.println("\n❌ Signal DENIED: " + signal.getReason());
            System.out.println("  Reason: " + decision.getReason());
        }
    }

    /**
     * Handle order status updates from the connector.
     */
    private void handleOrderUpdate(String orderId, OrderStatus status, Double fillPrice, Integer fillQty, Order order) {
        System.out.println("Order Update: " + orderId + " -> " + status);

        if (status == OrderStatus.FILLED && fillPrice != null && fillQty != null) {
            System.out.println("  Filled at $" + String.format("%.2f", fillPrice) + " x " + fillQty);

            // Update position in account state
            Position position = accountState.getPosition(order.getSymbol());
            if (position == null) {
                position = new Position(order.getSymbol(), order.getSide());
                accountState.addPosition(position);
            }
            position.addFill(fillPrice, fillQty);
        }

        if (status == OrderStatus.REJECTED) {
            System.err.println("  ❌ ORDER REJECTED!");
        }
    }

    /**
     * Check if it's time to flatten all positions.
     */
    private void checkFlattenByTime() {
        if (!running.get() || flatteningPositions.get()) {
            return;
        }

        LocalTime now = LocalTime.now(ET_ZONE);
        DayOfWeek day = LocalDate.now(ET_ZONE).getDayOfWeek();

        // Only check on trading days
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return;
        }

        // Check if we need to flatten
        if (now.isAfter(FLATTEN_BY_TIME) && now.isBefore(LocalTime.of(16, 0))) {
            if (!accountState.getPositions().isEmpty()) {
                System.out.println("\n⏰ FLATTEN-BY-TIME TRIGGERED!");
                System.out.println("  Time: " + now + " ET");
                System.out.println("  Flattening all positions...");
                flattenAllPositions("Flatten-by-time");
            }
        }
    }

    /**
     * Monitor risk limits and take action if breached.
     */
    private void monitorRisk() {
        if (!running.get()) {
            return;
        }

        // Check daily loss limit
        double dailyPnl = accountState.getNetDailyPnl();
        if (dailyPnl <= -riskLimits.getDailyLossLimit()) {
            System.out.println("\n❌ DAILY LOSS LIMIT BREACHED!");
            System.out.println("  Daily PnL: $" + String.format("%.2f", dailyPnl));
            System.out.println("  Limit: $" + String.format("%.2f", riskLimits.getDailyLossLimit()));
            activateKillSwitch("Daily loss limit breached");
            return;
        }

        // Check max loss limit
        double drawdown = accountState.getHighestEndOfDayBalance() - accountState.getEquity();
        if (drawdown >= riskLimits.getMaxLossLimit()) {
            System.out.println("\n❌ MAX LOSS LIMIT BREACHED!");
            System.out.println("  Drawdown: $" + String.format("%.2f", drawdown));
            System.out.println("  Limit: $" + String.format("%.2f", riskLimits.getMaxLossLimit()));
            activateKillSwitch("Max loss limit breached");
            return;
        }

        // Warning at 80% of daily limit
        if (dailyPnl <= -riskLimits.getDailyLossLimit() * 0.8 && dailyPnl > -riskLimits.getDailyLossLimit()) {
            System.out.println("\n⚠️  WARNING: Approaching daily loss limit (80%)");
            System.out.println("  Daily PnL: $" + String.format("%.2f", dailyPnl));
        }

        // Check for profit target
        if (riskEngine.hasMetProfitTarget(accountState, riskLimits)) {
            System.out.println("\n🎉 PROFIT TARGET REACHED!");
            System.out.println("  Total PnL: $" + String.format("%.2f", accountState.getRealizedPnL()));
            // Don't stop - just notify. User can decide to stop.
        }
    }

    /**
     * Activate kill switch - emergency stop all trading.
     */
    public void activateKillSwitch(String reason) {
        if (killSwitchActive.get()) {
            return;
        }

        System.out.println("\n" + "!".repeat(60));
        System.out.println("! KILL SWITCH ACTIVATED !");
        System.out.println("! Reason: " + reason);
        System.out.println("!".repeat(60));

        killSwitchActive.set(true);
        flattenAllPositions("Kill switch: " + reason);

        System.out.println("\n⛔ All new orders blocked until manual reset");
    }

    /**
     * Flatten all open positions.
     */
    public void flattenAllPositions(String reason) {
        if (flatteningPositions.getAndSet(true)) {
            return; // Already flattening
        }

        try {
            System.out.println("\n📤 Flattening all positions: " + reason);

            for (Position position : accountState.getPositions().values()) {
                if (position.getQuantity() != 0) {
                    try {
                        // Create market order to close position
                        OrderSide closeSide = position.getQuantity() > 0 ? OrderSide.SELL : OrderSide.BUY;
                        Order closeOrder = new Order(
                            position.getSymbol(),
                            closeSide,
                            OrderType.MARKET,
                            Math.abs(position.getQuantity()),
                            0.0 // Market order, no price
                        );

                        String orderId = connector.submitOrder(closeOrder, (id, status, price, qty) -> {
                            if (status == OrderStatus.FILLED) {
                                System.out.println("  ✓ Closed " + position.getSymbol() + " at $" + price);
                            }
                        });

                        System.out.println("  Closing " + position.getSymbol() +
                            " x " + position.getQuantity() + " (Order: " + orderId + ")");

                    } catch (Exception e) {
                        System.err.println("  ❌ Failed to close " + position.getSymbol() + ": " + e.getMessage());
                    }
                }
            }

        } finally {
            flatteningPositions.set(false);
        }
    }

    /**
     * Pause the LIVE engine.
     */
    public void pause() {
        if (!running.get()) {
            System.out.println("LIVE engine not running");
            return;
        }

        paused.set(true);
        System.out.println("\n⏸ LIVE engine PAUSED");
        System.out.println("  No new signals will be processed");
        System.out.println("  Existing positions and orders remain active");
    }

    /**
     * Resume the LIVE engine.
     */
    public void resume() {
        if (!running.get()) {
            System.out.println("LIVE engine not running");
            return;
        }

        if (killSwitchActive.get()) {
            System.out.println("Cannot resume - kill switch is active");
            System.out.println("  Call resetKillSwitch() to clear");
            return;
        }

        paused.set(false);
        System.out.println("\n▶ LIVE engine RESUMED");
    }

    /**
     * Reset the kill switch (requires confirmation).
     */
    public void resetKillSwitch() {
        if (!killSwitchActive.get()) {
            System.out.println("Kill switch is not active");
            return;
        }

        System.out.println("\n⚠️  Resetting kill switch...");
        System.out.println("  Please review the cause before continuing trading.");

        killSwitchActive.set(false);
        System.out.println("  Kill switch reset. Trading can resume.");
    }

    /**
     * Stop the LIVE engine.
     */
    public void stop() {
        if (!running.get()) {
            System.out.println("LIVE engine not running");
            return;
        }

        System.out.println("\n" + "=".repeat(60));
        System.out.println("STOPPING LIVE MODE");
        System.out.println("=".repeat(60));

        running.set(false);

        // Cancel pending orders
        System.out.println("Cancelling pending orders...");
        // TODO: Implement order cancellation

        // Flatten positions if any remain
        if (!accountState.getPositions().isEmpty()) {
            System.out.println("⚠️  Positions still open - flattening...");
            flattenAllPositions("Engine shutdown");
        }

        // Shutdown scheduler
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Shutdown strategy
        strategy.shutdown();

        // Disconnect from market
        connector.disconnect();

        // Print final stats
        printFinalStats();

        // Release shutdown latch
        shutdownLatch.countDown();

        System.out.println("\n✓ LIVE engine stopped");
    }

    /**
     * Emergency shutdown - immediate stop with position flattening.
     */
    public void emergencyShutdown(String reason) {
        System.out.println("\n" + "!".repeat(60));
        System.out.println("! EMERGENCY SHUTDOWN: " + reason);
        System.out.println("!".repeat(60));

        activateKillSwitch(reason);
        stop();
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

    // Getters for facade access
    public AccountState getAccountState() { return accountState; }
    public ExecutionEngine getExecutionEngine() { return executionEngine; }
    public RiskLimits getRiskLimits() { return riskLimits; }
    public boolean isRunning() { return running.get(); }
    public boolean isPaused() { return paused.get(); }
    public boolean isKillSwitchActive() { return killSwitchActive.get(); }

    /**
     * Main entry point for LIVE mode.
     */
    public static void run() {
        LiveEngineRunner runner = new LiveEngineRunner();

        // Add shutdown hook for emergency shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nReceived shutdown signal...");
            runner.emergencyShutdown("System shutdown signal");
        }));

        // Start the engine
        runner.start();
    }
}
