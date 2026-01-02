package com.topstep.trading;

import com.topstep.trading.connector.TradingConnector;
import com.topstep.trading.connector.TopstepConnector;
import com.topstep.trading.domain.*;
import com.topstep.trading.engine.MultiInstrumentEngine;
import com.topstep.trading.event.EventBus;
import com.topstep.trading.event.StrategySignalEvent;
import com.topstep.trading.execution.ExecutionEngine;
import com.topstep.trading.execution.BracketOrderManager;
import com.topstep.trading.risk.PropFirmRiskEngine;
import com.topstep.trading.risk.RiskDecision;
import com.topstep.trading.risk.TradingRiskManager;
import com.topstep.trading.strategy.DefaultStrategyContext;
import com.topstep.trading.strategy.IctHighConfluenceStrategy;
import com.topstep.trading.strategy.SessionManager;
import com.topstep.trading.strategy.TradeTier;
import com.topstep.trading.strategy.TradingStrategy;

import java.time.*;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
 * MULTI-INSTRUMENT AUTO-SWITCHING:
 * - Automatically switches between instruments based on optimal trading sessions
 * - NQ/ES during NY session, Gold during London, Yen during Asian session
 * - Instrument-specific ICT strategy parameters (OTE zones, reliability rates)
 * - Shared correlation tracker for cross-instrument SMT divergence
 *
 * IMPORTANT: Before running LIVE:
 * 1. Set environment variables for credentials
 * 2. Test extensively in SIM mode
 * 3. Start with small position sizes
 * 4. Monitor closely for the first few sessions
 */
public class LiveEngineRunner {

    private static final String DEFAULT_SYMBOL = "NQ";
    private static final String SMT_SYMBOL = "ES";

    // Multi-instrument mode flag
    private static final boolean MULTI_INSTRUMENT_MODE = true;

    // Timezone for Topstep (Chicago - Central Time)
    // Note: Topstep requires being flat by 3:10 PM CT
    private static final ZoneId CT_ZONE = ZoneId.of("America/Chicago");

    private final TradingConnector connector;
    private final AccountState accountState;
    private final RiskLimits riskLimits;
    private final PropFirmRiskEngine riskEngine;
    private final ExecutionEngine executionEngine;
    private final BracketOrderManager bracketManager;  // OCO bracket order management
    private final TradingStrategy strategy;           // Fallback single-instrument strategy
    private final MultiInstrumentEngine multiEngine;  // Multi-instrument auto-switching engine
    private final EventBus eventBus;
    private final DefaultStrategyContext strategyContext;
    private final ScheduledExecutorService scheduler;

    // Track subscribed symbols for multi-instrument mode
    private final Set<String> subscribedSymbols = ConcurrentHashMap.newKeySet();

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

        // Initialize bracket order manager for OCO SL/TP management
        if (this.connector instanceof TopstepConnector) {
            this.bracketManager = new BracketOrderManager((TopstepConnector) this.connector);
            this.bracketManager.setListener(new BracketOrderManager.BracketListener() {
                @Override
                public void onStopLossFilled(BracketOrderManager.BracketOrder bracket, double fillPrice) {
                    double pnl = calculatePnl(bracket.symbol, bracket.entryPrice, fillPrice,
                                             bracket.quantity, bracket.entrySide);
                    System.out.println("  PnL: $" + String.format("%.2f", pnl));
                    notifyPositionClosed(bracket.symbol, pnl);
                    // Clear position from account state
                    accountState.closePosition(bracket.symbol);
                }

                @Override
                public void onTakeProfitFilled(BracketOrderManager.BracketOrder bracket, double fillPrice) {
                    double pnl = calculatePnl(bracket.symbol, bracket.entryPrice, fillPrice,
                                             bracket.quantity, bracket.entrySide);
                    System.out.println("  PnL: $" + String.format("%.2f", pnl));
                    notifyPositionClosed(bracket.symbol, pnl);
                    // Clear position from account state
                    accountState.closePosition(bracket.symbol);
                }

                @Override
                public void onBracketCanceled(BracketOrderManager.BracketOrder bracket, String reason) {
                    System.out.println("[BRACKET] Bracket canceled for " + bracket.symbol + ": " + reason);
                }
            });
        } else {
            this.bracketManager = null;
        }
        this.strategyContext = new DefaultStrategyContext(accountState);
        this.scheduler = Executors.newScheduledThreadPool(2);

        // CRITICAL: Set execution listener to track position opens/closes
        this.executionEngine.setExecutionListener(new ExecutionEngine.ExecutionListener() {
            @Override
            public void onPositionOpened(String symbol, OrderSide side, double entryPrice, int quantity) {
                System.out.println("[LIVE] Position opened: " + symbol + " " + side + " x" + quantity + " @ " + entryPrice);
                // Note: For live mode, we track via connector callbacks, so this is for logging only
            }

            @Override
            public void onPositionClosed(String symbol, double pnl, boolean isWin) {
                System.out.println("[LIVE] Position closed: " + symbol + " | PnL: $" + String.format("%.2f", pnl) +
                                  " | " + (isWin ? "WIN" : "LOSS"));
                // Notify risk manager
                notifyPositionClosed(symbol, pnl);
            }
        });

        // Initialize single-instrument fallback strategy
        this.strategy = new IctHighConfluenceStrategy(DEFAULT_SYMBOL, SMT_SYMBOL, eventBus);

        // Initialize multi-instrument engine for auto-switching
        if (MULTI_INSTRUMENT_MODE) {
            this.multiEngine = new MultiInstrumentEngine(connector, eventBus, strategyContext);
            // Set up subscription callback to manage connector subscriptions
            this.multiEngine.setSubscriptionCallback(new MultiInstrumentEngine.SubscriptionCallback() {
                @Override
                public boolean onSubscribe(String symbol) {
                    return subscribeToMarketData(symbol);
                }

                @Override
                public void onUnsubscribe(String symbol) {
                    unsubscribeFromMarketData(symbol);
                }
            });
        } else {
            this.multiEngine = null;
        }

        // Subscribe to strategy signals
        eventBus.subscribe(StrategySignalEvent.class, this::handleStrategySignal);

        System.out.println("\n" + "!".repeat(60));
        System.out.println("! LIVE ENGINE INITIALIZED - REAL MONEY AT RISK !");
        System.out.println("!".repeat(60));
        System.out.println("  Starting Balance: $" + String.format("%.2f", startingBalance));
        System.out.println("  Daily Loss Limit: $" + String.format("%.2f", riskLimits.getDailyLossLimit()));
        System.out.println("  Max Loss Limit: $" + String.format("%.2f", riskLimits.getMaxLossLimit()));
        System.out.println("  Profit Target: $" + String.format("%.2f", riskLimits.getProfitTarget()));
        System.out.println("  Flatten-by Time: " + riskLimits.getFlattenByTime() + " CT");
        System.out.println("\n  ENHANCED FEATURES:");
        System.out.println("  - Tiered R:R: Tier 3 (1:4) | Tier 2 (1:2) | Tier 1 (1:1)");
        System.out.println("  - Partial Profit Taking: 50% at 1R, trail remaining");
        System.out.println("  - ICT Concepts: Breaker Blocks, Mitigation, Power of 3");
        System.out.println("  - Volatility Sizing: ATR-based position adjustment");
        if (MULTI_INSTRUMENT_MODE) {
            System.out.println("  - MULTI-INSTRUMENT MODE: Auto-switching based on session");
            System.out.println("    • NY Session: NQ, ES");
            System.out.println("    • London Session: GC (Gold), 6E (Euro), CL (Oil)");
            System.out.println("    • Asian Session: 6J (Yen)");
        }
    }

    /**
     * Subscribe to market data for a symbol via the connector.
     * @return true if subscription was successful, false otherwise
     */
    private boolean subscribeToMarketData(String symbol) {
        if (subscribedSymbols.contains(symbol)) {
            return true;
        }
        try {
            connector.subscribeMarketData(symbol, this::onMarketData);
            subscribedSymbols.add(symbol);
            System.out.println("✓ Subscribed to market data for " + symbol);
            return true;
        } catch (Exception e) {
            System.err.println("❌ Failed to subscribe to " + symbol + ": " + e.getMessage());
            // Don't add to subscribedSymbols if subscription failed
            return false;
        }
    }

    /**
     * Unsubscribe from market data for a symbol.
     */
    private void unsubscribeFromMarketData(String symbol) {
        if (!subscribedSymbols.contains(symbol)) {
            return;
        }
        try {
            connector.unsubscribeMarketData(symbol);
            subscribedSymbols.remove(symbol);
            System.out.println("✓ Unsubscribed from market data for " + symbol);
        } catch (Exception e) {
            System.err.println("❌ Failed to unsubscribe from " + symbol + ": " + e.getMessage());
        }
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

            // Start the EventBus to process trading signals
            eventBus.start();
            System.out.println("✓ EventBus started");

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

            // Start the appropriate engine mode
            if (MULTI_INSTRUMENT_MODE && multiEngine != null) {
                // Multi-instrument mode: start the auto-switching engine
                System.out.println("\n[MULTI-INSTRUMENT] Starting auto-switching engine...");
                multiEngine.start();
                System.out.println("✓ Multi-instrument engine started");
                System.out.println("✓ Session-based auto-switching ACTIVE");
                System.out.println("  Current: " + multiEngine.getSessionInfo());
            } else {
                // Single-instrument mode: initialize strategy and subscribe directly
                strategy.initialize();
                System.out.println("✓ Strategy initialized (single-instrument mode)");

                // Subscribe to market data for primary symbol (NQ) - this is what we trade
                connector.subscribeMarketData(DEFAULT_SYMBOL, this::onMarketData);
                subscribedSymbols.add(DEFAULT_SYMBOL);
                System.out.println("✓ Subscribed to market data for " + DEFAULT_SYMBOL + " (TRADING)");

                // Subscribe to market data for SMT symbol (ES) - used for divergence detection
                connector.subscribeMarketData(SMT_SYMBOL, this::onMarketData);
                subscribedSymbols.add(SMT_SYMBOL);
                System.out.println("✓ Subscribed to market data for " + SMT_SYMBOL + " (SMT)");
            }

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
     * Routes to either multi-instrument engine or single-instrument strategy.
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

            // Feed to appropriate engine (only if not paused and not flattening)
            if (!paused.get() && !flatteningPositions.get()) {
                if (MULTI_INSTRUMENT_MODE && multiEngine != null) {
                    // Multi-instrument mode: route to auto-switching engine
                    multiEngine.onMarketData(candle);
                } else {
                    // Single-instrument mode: use fallback strategy
                    strategy.onCandle(candle, strategyContext);
                }
            }

        } catch (Exception e) {
            System.err.println("Error processing candle for " + candle.getSymbol() + ": " + e.getMessage());
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

        String symbol = signal.getSymbol();
        TradeTier newTier = signal.getTier();

        // STEP 0: Check for duplicate orders - but allow higher-tier signals to replace lower-tier pending orders
        java.util.List<Order> existingOrders = executionEngine.getActiveOrdersList(symbol);
        if (!existingOrders.isEmpty()) {
            // Get the tier of the existing pending order
            ExecutionEngine.EnhancedOrderLevels existingLevels = executionEngine.getOrderLevels(symbol);
            TradeTier existingTier = (existingLevels != null) ? existingLevels.getTier() : TradeTier.TIER_1;

            // Compare tiers - higher tier level = better quality signal
            if (newTier.getLevel() > existingTier.getLevel()) {
                // NEW SIGNAL IS HIGHER TIER - Cancel existing and allow new one
                System.out.println("\n🔄 TIER UPGRADE: Cancelling " + existingTier + " for " + newTier);
                System.out.println("  Symbol: " + symbol);

                // Cancel all existing orders for this symbol
                for (Order pending : existingOrders) {
                    try {
                        String orderId = pending.getOrderId();
                        if (orderId != null && !orderId.isEmpty()) {
                            connector.cancelOrder(orderId);
                            System.out.println("  ✓ Cancelled pending order: " + orderId +
                                " (" + pending.getSide() + " @ " + String.format("%.5f", pending.getLimitPrice()) + ")");
                        }
                    } catch (Exception e) {
                        System.err.println("  ❌ Failed to cancel order: " + e.getMessage());
                        // Even if cancel fails, we'll continue - the old order may have already filled
                    }
                }

                // Remove from execution engine
                executionEngine.removeOrder(symbol);
                System.out.println("  Proceeding with higher-tier signal...");

                // Continue to process the new higher-tier signal
            } else {
                // EXISTING ORDER IS EQUAL OR HIGHER TIER - Keep it
                System.out.println("\n⏭ Signal SKIPPED - pending order already exists for " + symbol);
                System.out.println("  Pending tier: " + existingTier + " | New signal tier: " + newTier);
                System.out.println("  (Only higher-tier signals can replace lower-tier pending orders)");
                for (Order pending : existingOrders) {
                    System.out.println("    - " + pending.getSide() + " @ " +
                        String.format("%.5f", pending.getLimitPrice()) + " (status: " + pending.getStatus() + ")");
                }
                return;
            }
        }

        // Also check if we already have a position (belt and suspenders)
        if (accountState.hasPosition(symbol)) {
            Position existingPos = accountState.getPosition(symbol);
            if (!existingPos.isFlat()) {
                System.out.println("\n⏭ Signal SKIPPED - already have position in " + symbol);
                System.out.println("  Current: " + existingPos);
                return;
            }
        }

        // STEP 1: Validate with TradingRiskManager (correlation, consecutive loss, position limits)
        if (MULTI_INSTRUMENT_MODE && multiEngine != null) {
            RiskDecision riskManagerDecision = multiEngine.validateSignalWithRiskManager(signal);
            if (!riskManagerDecision.isApproved()) {
                System.out.println("\n❌ LIVE Signal BLOCKED by TradingRiskManager: " + signal.getReason());
                System.out.println("  Reason: " + riskManagerDecision.getReason());
                return;
            }

            // Check market conditions
            String conditionIssue = multiEngine.checkMarketConditions(signal);
            if (conditionIssue != null) {
                System.out.println("\n❌ LIVE Signal SKIPPED: " + signal.getReason());
                System.out.println("  Reason: " + conditionIssue);
                return;
            }
        }

        // STEP 2: Evaluate against prop firm risk limits (DLL, MLL, position sizing)
        RiskDecision decision = riskEngine.evaluate(signal, accountState, riskLimits);

        if (decision.isAllowed()) {
            System.out.println("\n✓ LIVE Signal APPROVED: " + signal.getReason());
            System.out.println("  Tier: " + signal.getTier() + " | R:R: 1:" + signal.getRiskRewardRatio());
            System.out.println("  Quantity: " + signal.getQuantity() + " | " + decision.getReason());

            try {
                Order order = decision.getOrder();

                // CRITICAL: Register order with execution engine FIRST (before connector)
                // This prevents race condition where connector callback fires before order is tracked
                executionEngine.submitOrderEnhanced(
                    order,
                    signal.getStopPrice(),
                    signal.getTargetPrice(),
                    signal.getTier(),
                    signal.getPartialProfitTargets()
                );

                // THEN submit to live market via connector
                String orderId = connector.submitOrder(order, (id, status, fillPrice, fillQty) -> {
                    handleOrderUpdate(id, status, fillPrice, fillQty, order, signal);
                });

                order.setOrderId(orderId);
                System.out.println("  Order submitted: " + orderId);

                printAccountStatus();

            } catch (Exception e) {
                System.err.println("❌ Order submission failed: " + e.getMessage());
            }

        } else {
            System.out.println("\n❌ Signal DENIED by PropFirmRiskEngine: " + signal.getReason());
            System.out.println("  Reason: " + decision.getReason());
        }
    }

    /**
     * Handle order status updates from the connector.
     * CRITICAL: Updates order status to prevent ExecutionEngine from double-processing fills.
     */
    private void handleOrderUpdate(String orderId, OrderStatus status, Double fillPrice, Integer fillQty,
                                   Order order, StrategySignalEvent signal) {
        System.out.println("Order Update: " + orderId + " -> " + status);

        // CRITICAL: Update order status FIRST to prevent ExecutionEngine from double-filling
        order.updateStatus(status);

        if (status == OrderStatus.FILLED && fillPrice != null && fillQty != null) {
            System.out.println("  Filled at $" + String.format("%.2f", fillPrice) + " x " + fillQty);

            // CRITICAL: Record the fill on the order to mark it as not active
            order.recordFill(fillQty, fillPrice);

            // Remove order from ExecutionEngine's active orders to prevent double-processing
            executionEngine.removeOrderById(order.getSymbol(), orderId);

            // Update position in account state
            Position position = accountState.getPosition(order.getSymbol());
            if (position == null) {
                position = new Position(order.getSymbol(), order.getSide());
                accountState.addPosition(position);
            }

            // FIX: Apply signed quantity - Position expects positive for BUY, negative for SELL
            int signedFillQty = (order.getSide() == OrderSide.BUY) ? fillQty : -fillQty;
            position.updateWithFill(signedFillQty, fillPrice);
            System.out.println("  Position updated: " + position);

            // Record position opened in TradingRiskManager (NOW that order is actually filled)
            if (MULTI_INSTRUMENT_MODE && multiEngine != null && signal != null) {
                boolean isBullish = signal.getSide() == OrderSide.BUY;
                multiEngine.getRiskManager().recordPositionOpened(
                    signal.getSymbol(), isBullish, fillPrice
                );
            }

            // CRITICAL: Submit stop loss and take profit orders to the exchange
            // This provides protection even if the bot crashes
            if (signal != null && connector instanceof TopstepConnector) {
                submitProtectiveOrders(signal, fillQty, fillPrice, orderId);
            }
        }

        if (status == OrderStatus.REJECTED) {
            System.err.println("  ❌ ORDER REJECTED!");
            // Remove rejected order from ExecutionEngine
            executionEngine.removeOrderById(order.getSymbol(), orderId);
        }

        if (status == OrderStatus.CANCELED) {
            // Remove cancelled order from ExecutionEngine
            executionEngine.removeOrderById(order.getSymbol(), orderId);
        }
    }

    /**
     * Submit protective stop loss and take profit orders after entry is filled.
     * Uses BracketOrderManager for OCO (One Cancels Other) management.
     * CRITICAL: These orders are placed on the exchange for protection even if bot crashes.
     *
     * Directional rules:
     * - LONG position: SL = Sell Stop Market (below entry), TP = Sell Limit (above entry)
     * - SHORT position: SL = Buy Stop Market (above entry), TP = Buy Limit (below entry)
     */
    private void submitProtectiveOrders(StrategySignalEvent signal, int quantity, double fillPrice, String entryOrderId) {
        if (bracketManager == null) {
            System.err.println("  ❌ BracketOrderManager not available - position is UNPROTECTED!");
            return;
        }

        String symbol = signal.getSymbol();
        double stopPrice = signal.getStopPrice();
        double targetPrice = signal.getTargetPrice();

        // Validate bracket order prices based on direction
        boolean isLong = signal.getSide() == OrderSide.BUY;

        if (isLong) {
            // LONG: stopPrice < fillPrice < targetPrice
            if (stopPrice >= fillPrice) {
                System.err.println("  ❌ Invalid LONG bracket: stop (" + stopPrice + ") >= fill (" + fillPrice + ")");
                return;
            }
            if (targetPrice <= fillPrice) {
                System.err.println("  ❌ Invalid LONG bracket: target (" + targetPrice + ") <= fill (" + fillPrice + ")");
                return;
            }
        } else {
            // SHORT: targetPrice < fillPrice < stopPrice
            if (stopPrice <= fillPrice) {
                System.err.println("  ❌ Invalid SHORT bracket: stop (" + stopPrice + ") <= fill (" + fillPrice + ")");
                return;
            }
            if (targetPrice >= fillPrice) {
                System.err.println("  ❌ Invalid SHORT bracket: target (" + targetPrice + ") >= fill (" + fillPrice + ")");
                return;
            }
        }

        System.out.println("  Bracket validated: " + (isLong ? "LONG" : "SHORT") +
            " | Stop: " + stopPrice + " | Entry: " + fillPrice + " | Target: " + targetPrice);

        // Create the OCO bracket - BracketOrderManager handles all the details
        bracketManager.createBracket(
            symbol,
            entryOrderId,
            fillPrice,
            quantity,
            signal.getSide(),
            stopPrice,
            targetPrice
        );
    }

    /**
     * Calculate PnL for a closed position.
     */
    private double calculatePnl(String symbol, double entryPrice, double exitPrice, int quantity, OrderSide entrySide) {
        double tickValue = getTickValue(symbol);
        double priceDiff = (entrySide == OrderSide.BUY)
            ? (exitPrice - entryPrice)
            : (entryPrice - exitPrice);
        return priceDiff * quantity * tickValue;
    }

    /**
     * Get tick value for a symbol.
     */
    private double getTickValue(String symbol) {
        switch (symbol.toUpperCase()) {
            case "ES": return 12.50;
            case "NQ": return 5.00;
            case "6E": case "6J": case "6B": return 6.25;
            case "CL": case "GC": case "NG": return 10.00;
            case "SI": return 25.00;
            default: return 12.50;
        }
    }

    /**
     * Notify risk manager when a position is closed.
     * Should be called when a trade completes (stop hit or target hit).
     */
    public void notifyPositionClosed(String symbol, double pnl) {
        if (MULTI_INSTRUMENT_MODE && multiEngine != null) {
            multiEngine.notifyPositionClosed(symbol, pnl);
        }
    }

    /**
     * Check if it's time to flatten all positions.
     * Uses Central Time (CT) since Topstep is Chicago-based.
     */
    private void checkFlattenByTime() {
        if (!running.get() || flatteningPositions.get()) {
            return;
        }

        LocalTime now = LocalTime.now(CT_ZONE);
        DayOfWeek day = LocalDate.now(CT_ZONE).getDayOfWeek();

        // Only check on trading days
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return;
        }

        // Get flatten-by time from risk limits (in CT)
        LocalTime flattenByTime = riskLimits.getFlattenByTime();

        // Check if we need to flatten (between flattenByTime and 4 PM CT)
        if (now.isAfter(flattenByTime) && now.isBefore(LocalTime.of(16, 0))) {
            if (!accountState.getPositions().isEmpty()) {
                System.out.println("\n⏰ FLATTEN-BY-TIME TRIGGERED!");
                System.out.println("  Time: " + now + " CT");
                System.out.println("  Flatten Limit: " + flattenByTime + " CT");
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
     * IMPORTANT: Also cancels any active bracket orders (SL/TP) to avoid
     * duplicate exits or orphaned orders.
     */
    public void flattenAllPositions(String reason) {
        if (flatteningPositions.getAndSet(true)) {
            return; // Already flattening
        }

        try {
            System.out.println("\n📤 Flattening all positions: " + reason);

            // Collect symbols to flatten (avoid concurrent modification)
            java.util.List<String> symbolsToFlatten = new java.util.ArrayList<>();

            for (Position position : accountState.getPositions().values()) {
                if (position.getQuantity() != 0) {
                    String symbol = position.getSymbol();

                    // CRITICAL: Cancel any active brackets first to avoid orphaned orders
                    if (bracketManager != null && bracketManager.hasBracket(symbol)) {
                        bracketManager.cancelBracket(symbol, "Position flattened: " + reason);
                    }

                    try {
                        // Create market order to close position
                        OrderSide closeSide = position.getQuantity() > 0 ? OrderSide.SELL : OrderSide.BUY;
                        Order closeOrder = new Order(
                            symbol,
                            closeSide,
                            OrderType.MARKET,
                            Math.abs(position.getQuantity()),
                            0.0 // Market order, no price
                        );

                        String orderId = connector.submitOrder(closeOrder, (id, status, price, qty) -> {
                            if (status == OrderStatus.FILLED) {
                                System.out.println("  ✓ Closed " + symbol + " at $" + price);
                            }
                        });

                        System.out.println("  Closing " + symbol +
                            " x " + position.getQuantity() + " (Order: " + orderId + ")");

                        // Track symbol for removal after orders submitted
                        symbolsToFlatten.add(symbol);

                    } catch (Exception e) {
                        System.err.println("  ❌ Failed to close " + symbol + ": " + e.getMessage());
                    }
                }
            }

            // CRITICAL: Clear flattened positions from account state
            // Market orders are fire-and-forget on the exchange - they will fill
            // We can't wait for fill detection during shutdown, so clear now
            for (String symbol : symbolsToFlatten) {
                accountState.closePosition(symbol);
                System.out.println("  ✓ Removed " + symbol + " from account state");
            }

        } finally {
            flatteningPositions.set(false);
        }
    }

    /**
     * Cancel all pending orders.
     */
    private void cancelPendingOrders() {
        Map<String, Order> activeOrders = executionEngine.getActiveOrders();

        if (activeOrders.isEmpty()) {
            System.out.println("  No pending orders to cancel");
            return;
        }

        System.out.println("  Found " + activeOrders.size() + " pending order(s) to cancel");

        for (Order order : activeOrders.values()) {
            try {
                String orderId = order.getOrderId();
                if (orderId != null && !orderId.isEmpty()) {
                    connector.cancelOrder(orderId);
                    executionEngine.removeOrder(order.getSymbol());
                    System.out.println("  ✓ Cancelled order: " + orderId + " (" + order.getSymbol() + ")");
                }
            } catch (Exception e) {
                System.err.println("  ❌ Failed to cancel order " + order.getOrderId() + ": " + e.getMessage());
            }
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
        cancelPendingOrders();

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

        // Stop EventBus
        eventBus.stop();

        // Shutdown multi-instrument engine or single strategy
        if (MULTI_INSTRUMENT_MODE && multiEngine != null) {
            multiEngine.stop();
        } else {
            strategy.shutdown();
        }

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
    public MultiInstrumentEngine getMultiEngine() { return multiEngine; }
    public boolean isMultiInstrumentMode() { return MULTI_INSTRUMENT_MODE && multiEngine != null; }

    /**
     * Get current multi-instrument status.
     */
    public String getMultiInstrumentStatus() {
        if (!isMultiInstrumentMode()) {
            return "Single-instrument mode (NQ/ES only)";
        }
        return multiEngine.getDetailedStatus();
    }

    /**
     * Print current session and active instruments.
     */
    public void printSessionStatus() {
        if (isMultiInstrumentMode()) {
            System.out.println(multiEngine.getDetailedStatus());
        } else {
            System.out.println("Single-instrument mode: Trading " + DEFAULT_SYMBOL + " with " + SMT_SYMBOL + " for SMT");
        }
    }

    /**
     * Force switch to specific instruments (manual override).
     */
    public void forceInstruments(String... symbols) {
        if (!isMultiInstrumentMode()) {
            System.out.println("Cannot force instruments in single-instrument mode");
            return;
        }
        multiEngine.forceActivateInstruments(symbols);
    }

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
