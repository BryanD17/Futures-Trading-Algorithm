package com.topstep.trading;

// LIVE Trading Engine - Clean Version (no duplicates)
import com.topstep.trading.connector.TradingConnector;
import com.topstep.trading.connector.TopstepConnector;
import com.topstep.trading.domain.*;
import com.topstep.trading.engine.MultiInstrumentEngine;
import com.topstep.trading.event.EventBus;
import com.topstep.trading.event.StrategySignalEvent;
import com.topstep.trading.execution.ExecutionEngine;
import com.topstep.trading.execution.BracketOrderManager;
import com.topstep.trading.journal.TradeJournalService;
import com.topstep.trading.lifecycle.AccountLifecycle;
import com.topstep.trading.lifecycle.RiskZone;
import com.topstep.trading.risk.PhaseAwareRiskCalculator;
import com.topstep.trading.risk.PropFirmRiskEngine;
import com.topstep.trading.risk.RiskDecision;
import com.topstep.trading.risk.RiskProfile;
import com.topstep.trading.risk.TradingRiskManager;
import com.topstep.trading.strategy.DefaultStrategyContext;
import com.topstep.trading.strategy.IctHighConfluenceStrategy;
import com.topstep.trading.strategy.KillzoneClock;
import com.topstep.trading.strategy.KillzonePhase;
import com.topstep.trading.strategy.SessionManager;
import com.topstep.trading.strategy.TradeTier;
import com.topstep.trading.strategy.InstrumentCharacteristics;
import com.topstep.trading.strategy.TradingStrategy;

import java.time.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

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
 * - NQ/MNQ/ES/MES during NY session, GC/MGC during London session
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

    // STDV+OTE refactor: defaults to MNQ + MES (the registry-allowed micros).
    // Override via -Dstdvote.symbol=<MNQ|MES|MGC> and -Dstdvote.smt=<...>.
    // Setting an off-registry symbol routes the factory to the legacy
    // strategy as a fallback — see StdvOteFactory.
    private static final String DEFAULT_SYMBOL =
            System.getProperty("stdvote.symbol", "MNQ");
    private static final String SMT_SYMBOL =
            System.getProperty("stdvote.smt", "MES");

    // Multi-instrument mode flag (legacy MultiInstrumentEngine path).
    private static final boolean MULTI_INSTRUMENT_MODE = true;

    /**
     * When true (the default under stdvOte mode), use
     * {@link com.topstep.trading.strategy.stdvote.StdvOteMultiInstrumentEngine}
     * to drive MNQ + MGC concurrently with MES as SMT-only feed. When this is
     * on, the legacy {@code MultiInstrumentEngine} path above is disabled
     * (multiEngine == null), and {@link #onMarketData} routes candles to the
     * new engine instead. Override with {@code -Dstdvote.multiInstrument=false}.
     */
    private static final boolean STDV_OTE_MULTI_INSTRUMENT =
            com.topstep.trading.strategy.stdvote.StdvOteFactory.isEnabled()
                    && !"false".equalsIgnoreCase(
                        System.getProperty("stdvote.multiInstrument", "true"));

    // Timezone for Topstep (Chicago - Central Time)
    // Note: Topstep requires being flat by 3:10 PM CT
    private static final ZoneId CT_ZONE = ZoneId.of("America/Chicago");

    // Order timeout settings (hybrid approach)
    // Cancel unfilled limit orders after 90 minutes OR when killzone phase is CLOSING
    private static final long ORDER_TIMEOUT_MINUTES = 90;

    // Balance sync interval (sync with Topstep every 5 minutes)
    // This ensures local state stays aligned with actual account balance
    private static final long BALANCE_SYNC_INTERVAL_MINUTES = 5;

    private final TradingConnector connector;
    private final AccountState accountState;
    // Volatile (not final): the dashboard risk-settings endpoint can swap in
    // a tightened copy at runtime via setRiskLimits.
    private volatile RiskLimits riskLimits;
    private final PropFirmRiskEngine riskEngine;
    private final ExecutionEngine executionEngine;
    private final BracketOrderManager bracketManager;  // OCO bracket order management
    private final TradingStrategy strategy;           // Fallback single-instrument strategy
    private final MultiInstrumentEngine multiEngine;  // Multi-instrument auto-switching engine (legacy)
    private final com.topstep.trading.strategy.stdvote.StdvOteMultiInstrumentEngine stdvOteMultiEngine; // STDV+OTE multi-instrument engine
    private final EventBus eventBus;
    private final DefaultStrategyContext strategyContext;
    private final ScheduledExecutorService scheduler;

    // Track subscribed symbols for multi-instrument mode
    private final Set<String> subscribedSymbols = ConcurrentHashMap.newKeySet();

    // ── WARMUP GUARD ────────────────────────────────────────────────────
    // The startup backfill (HistoricalBackfill) replays days of history
    // through the same candle path as live data. A historical candle inside
    // a past killzone could satisfy every gate and emit a signal for a price
    // from yesterday — that signal must NEVER become an order. Two layers:
    //  1) warmupComplete: false until every initial subscription (and its
    //     synchronous backfill) has returned in start().
    //  2) lastCandleTs staleness: a signal whose symbol's most recent candle
    //     is > 5 minutes behind wall-clock is replayed/stale data. This is
    //     self-healing across reconnects and future replays.
    // Because the EventBus dispatches asynchronously (queue + worker thread),
    // a signal EMITTED during replay could be DEQUEUED after warmup ends —
    // so signals created before warmupCompletedAt are also suppressed.
    private final Map<String, Instant> lastCandleTs = new ConcurrentHashMap<>();
    private volatile boolean warmupComplete = false;
    private volatile Instant warmupCompletedAt = null;
    private static final long STALE_SIGNAL_THRESHOLD_SECONDS = 5 * 60;

    // Killzone clock for stale order checking
    private final KillzoneClock killzoneClock = new KillzoneClock();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicBoolean killSwitchActive = new AtomicBoolean(false);
    private final AtomicBoolean flatteningPositions = new AtomicBoolean(false);
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

    // Trade journal service for session summary and persistence
    private final TradeJournalService journalService = new TradeJournalService();

    // EXPRESS Funded Account flag - these accounts start at $0 balance (not $50K)
    // Balance represents P&L accumulated since account creation
    private final boolean isExpressAccount;
    private final com.topstep.trading.connector.TopstepCredentials credentials;

    // === Convex Payoff Optimization: Lifecycle-aware dynamic risk sizing ===
    private final AccountLifecycle lifecycle;
    private final PhaseAwareRiskCalculator riskCalculator;
    private final RiskProfile riskProfile;
    private volatile int tradesToday = 0;
    private volatile LocalDate lastTradingDate = null;

    /**
     * Create a new LIVE engine with Topstep 50K configuration.
     * The RiskLimits profile is selected by ScalpConfig: legacy topstep50k()
     * unless -DscalpMode.enabled=true (then topstep50kScalp()).
     */
    public LiveEngineRunner() {
        this(50_000.0, com.topstep.trading.strategy.stdvote.ScalpConfig.activeRiskLimits());
    }

    /**
     * Create a new LIVE engine with custom configuration.
     */
    public LiveEngineRunner(double startingBalance, RiskLimits riskLimits) {
        // Resolve credentials once (system properties / ~/.topstep file / env)
        // so the EXPRESS check and the connector agree on the account.
        com.topstep.trading.connector.TopstepCredentials creds =
            com.topstep.trading.connector.TopstepCredentials.load();
        this.credentials = creds;

        // Check if this is an Express Funded Account (balance starts at $0)
        String accountId = creds.accountId;
        this.isExpressAccount = accountId != null && accountId.toUpperCase().contains("EXPRESS");

        // For Express accounts, override starting balance to $0
        // Express accounts track P&L from zero, not from a funded amount
        double effectiveStartingBalance = isExpressAccount ? 0.0 : startingBalance;

        // Initialize account
        this.accountState = new AccountState(effectiveStartingBalance);
        this.riskLimits = riskLimits;

        if (isExpressAccount) {
            System.out.println("═══════════════════════════════════════════════════════════════");
            System.out.println("  EXPRESS FUNDED ACCOUNT DETECTED");
            System.out.println("  Balance starts at $0 (tracks P&L, not funded amount)");
            System.out.println("  Max Loss Limit: -$" + riskLimits.getMaxLossLimit());
            System.out.println("═══════════════════════════════════════════════════════════════");
        }

        // Initialize trading components
        this.connector = createConnector();
        this.executionEngine = new ExecutionEngine(accountState);
        this.executionEngine.setSimulationEnabled(false); // live mode relies on broker fills only
        this.riskEngine = new PropFirmRiskEngine();
        this.eventBus = new EventBus();
        // Publish PositionClosedEvent from any ExecutionEngine close (live
        // closes normally flow through the bracket handlers below, which
        // publish it themselves).
        this.executionEngine.setEventBus(eventBus);

        // Initialize bracket order manager for OCO SL/TP management
        if (this.connector instanceof TopstepConnector) {
            this.bracketManager = new BracketOrderManager((TopstepConnector) this.connector);
            this.bracketManager.setListener(new BracketOrderManager.BracketListener() {
                @Override
                public void onStopLossFilled(BracketOrderManager.BracketOrder bracket, double fillPrice) {
                    // Calculate PnL based on remaining quantity (after partials)
                    int qty = bracket.remainingQuantity > 0 ? bracket.remainingQuantity : bracket.totalQuantity;
                    double pnl = calculatePnl(bracket.symbol, bracket.entryPrice, fillPrice,
                                             qty, bracket.entrySide);
                    System.out.println("  Stop PnL: $" + String.format("%.2f", pnl) + " (" + qty + " contracts)");
                    notifyPositionClosed(bracket.symbol, pnl);
                    recordLiveTrade(bracket, fillPrice, qty, pnl, "Stop loss filled");
                    // Book realized P&L so the DLL guard (getNetDailyPnl) sees
                    // live losses — live closes bypass ExecutionEngine.closePosition,
                    // which is where SIM/backtest book it.
                    accountState.recordRealizedPnL(pnl);
                    // Clear position from account state
                    accountState.closePosition(bracket.symbol);
                    // Count the completed trade for the frequency gates
                    // (live closes bypass ExecutionEngine.closePosition).
                    accountState.recordTradeCompleted(pnl);
                    // Same funnel: notify subscribers (scalp re-arm) of the close.
                    eventBus.publish(new com.topstep.trading.event.PositionClosedEvent(
                            bracket.symbol, pnl, pnl > 0, java.time.Instant.now()));
                }

                @Override
                public void onTakeProfitFilled(BracketOrderManager.BracketOrder bracket, double fillPrice) {
                    // This is called when ALL take profits are filled (position fully closed)
                    double pnl = calculatePnl(bracket.symbol, bracket.entryPrice, fillPrice,
                                             bracket.totalQuantity, bracket.entrySide);
                    System.out.println("  Total PnL: $" + String.format("%.2f", pnl));
                    notifyPositionClosed(bracket.symbol, pnl);
                    // Multi-level TPs already booked/recorded every level via
                    // onPartialTakeProfitFilled (which also fires for the last
                    // level); only the legacy single-TP path arrives here with
                    // an unbooked remainder. Book just that portion or the DLL
                    // guard would double-count partials.
                    int unbookedQty = bracket.totalQuantity - bracket.getTotalFilledTpQuantity();
                    if (unbookedQty > 0) {
                        double unbookedPnl = calculatePnl(bracket.symbol, bracket.entryPrice, fillPrice,
                                                          unbookedQty, bracket.entrySide);
                        recordLiveTrade(bracket, fillPrice, unbookedQty, unbookedPnl, "Take profit filled");
                        accountState.recordRealizedPnL(unbookedPnl);
                    }
                    // Clear position from account state
                    accountState.closePosition(bracket.symbol);
                    // Count the completed trade for the frequency gates.
                    accountState.recordTradeCompleted(pnl);
                    // Same funnel: notify subscribers (scalp re-arm) of the close.
                    eventBus.publish(new com.topstep.trading.event.PositionClosedEvent(
                            bracket.symbol, pnl, pnl > 0, java.time.Instant.now()));
                }

                @Override
                public void onPartialTakeProfitFilled(BracketOrderManager.BracketOrder bracket,
                                                       BracketOrderManager.TakeProfitLevel level, double fillPrice) {
                    // Partial take profit filled - position still open but reduced
                    double partialPnl = calculatePnl(bracket.symbol, bracket.entryPrice, fillPrice,
                                                     level.quantity, bracket.entrySide);
                    System.out.println("  Partial PnL: $" + String.format("%.2f", partialPnl) +
                                      " (" + level.quantity + " contracts at " + level.rMultiple + "R)");
                    recordLiveTrade(bracket, fillPrice, level.quantity, partialPnl,
                        "Partial take profit (" + level.rMultiple + "R)");
                    // Update realized PnL but don't close position
                    accountState.recordRealizedPnL(partialPnl);
                    // Update position quantity
                    if (accountState.hasPosition(bracket.symbol)) {
                        Position pos = accountState.getPosition(bracket.symbol);
                        int newQty = bracket.remainingQuantity;
                        if (bracket.entrySide == OrderSide.BUY) {
                            pos.updateWithFill(-level.quantity, fillPrice);  // Reduce long
                        } else {
                            pos.updateWithFill(level.quantity, fillPrice);   // Reduce short
                        }
                    }
                }

                @Override
                public void onStopMovedToBreakeven(BracketOrderManager.BracketOrder bracket, double newStopPrice) {
                    System.out.println("  [RISK FREE] Stop moved to breakeven: " + newStopPrice);
                    // This is informational - position is now risk-free
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
                // If exchange callbacks are delayed or unavailable (e.g., order search 4xx), create
                // a protective bracket immediately using the strategy's recorded stop/target levels
                // so the position is never left naked.
                if (bracketManager != null && !bracketManager.hasBracket(symbol)) {
                    ExecutionEngine.EnhancedOrderLevels levels = executionEngine.getOrderLevels(symbol);
                    if (levels != null && levels.getCurrentStopPrice() > 0 && levels.getFinalTargetPrice() > 0) {
                        double stop = levels.getCurrentStopPrice();
                        double target = levels.getFinalTargetPrice();

                        boolean isLong = side == OrderSide.BUY;
                        boolean validLong = isLong && stop < entryPrice && target > entryPrice;
                        boolean validShort = !isLong && stop > entryPrice && target < entryPrice;

                        if (validLong || validShort) {
                            String fallbackOrderId = symbol + "-bracket-" + System.currentTimeMillis();
                            bracketManager.createBracket(
                                symbol,
                                fallbackOrderId,
                                entryPrice,
                                quantity,
                                side,
                                stop,
                                target
                            );
                            // SCALP mode: fallback brackets get the same
                            // +0.5R breakeven trigger as the primary path.
                            if (com.topstep.trading.strategy.stdvote.ScalpConfig.isEnabled()) {
                                double fbTickSize = InstrumentCharacteristics
                                        .getProfile(symbol).getTickSize();
                                armScalpBreakevenIfConfigured(symbol, entryPrice,
                                        stop, isLong, fbTickSize);
                            }
                        } else {
                            System.err.println("  ❌ Skipping fallback bracket for " + symbol +
                                " due to invalid prices (stop=" + stop + ", target=" + target +
                                ", entry=" + entryPrice + ")");
                        }
                    } else {
                        System.err.println("  ❌ No levels available to build fallback bracket for " + symbol);
                    }
                }
            }

            @Override
            public void onPositionClosed(String symbol, double pnl, boolean isWin) {
                System.out.println("[LIVE] Position closed: " + symbol + " | PnL: $" + String.format("%.2f", pnl) +
                                  " | " + (isWin ? "WIN" : "LOSS"));
                // Notify risk manager
                notifyPositionClosed(symbol, pnl);
            }
        });

        // STDV+OTE multi-instrument path takes precedence when enabled. The
        // legacy MultiInstrumentEngine is disabled in that case to avoid
        // double signal generation.
        if (STDV_OTE_MULTI_INSTRUMENT) {
            this.stdvOteMultiEngine =
                    new com.topstep.trading.strategy.stdvote.StdvOteMultiInstrumentEngine(
                            connector, eventBus, strategyContext);
            this.strategy = stdvOteMultiEngine.getPrimaryStrategy();
            this.multiEngine = null;
            System.out.println("[LIVE] STDV+OTE multi-instrument enabled: active="
                    + stdvOteMultiEngine.getActiveSymbols()
                    + " smtOnly=" + stdvOteMultiEngine.getSmtOnlySymbols());
        } else {
            this.stdvOteMultiEngine = null;
            // Initialize single-instrument fallback strategy via the STDV+OTE factory.
            // Default selection is the new StdvOteRunnerStrategy; set
            // -DstdvOte.enabled=false to roll back to IctHighConfluenceStrategy.
            this.strategy = com.topstep.trading.strategy.stdvote.StdvOteFactory.build(
                    DEFAULT_SYMBOL, SMT_SYMBOL, eventBus);

            // Initialize legacy multi-instrument engine for auto-switching
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
        }

        // === Convex Payoff Optimization: Initialize lifecycle-aware risk components ===
        this.lifecycle = AccountLifecycle.topstep50kEvaluation();
        this.riskProfile = RiskProfile.topstep50kEvaluation();
        this.riskCalculator = new PhaseAwareRiskCalculator();

        // Register lifecycle with the facade for dashboard access
        EngineFacade.getInstance().initializeLifecycle(lifecycle);

        System.out.println("\n  CONVEX PAYOFF OPTIMIZATION: ACTIVE");
        System.out.println("  - Dynamic risk sizing based on account zone & setup quality");
        System.out.println("  - Zone multipliers: NORMAL=1.0x, PROTECTION=0.6x, CAUTION=0.7x, DANGER=0.4x, CRUISE=0.3x");
        System.out.println("  - Quality gates: min quality " + riskProfile.getMinSetupQuality() +
            " (cruise: " + riskProfile.getCruiseMinQuality() + ")");
        System.out.println("  - Base risk: " + String.format("%.2f%%", riskProfile.getBaseRiskPct() * 100) +
            " of $" + String.format("%.0f", lifecycle.getStartingBalance()) +
            " = $" + String.format("%.0f", lifecycle.getStartingBalance() * riskProfile.getBaseRiskPct()));

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
            System.out.println("    • NY Session: NQ, MNQ, ES, MES");
            System.out.println("    • London Session: GC, MGC (Gold)");
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
        return new TopstepConnector(credentials.apiUrl, credentials.username,
            credentials.apiKey, credentials.accountId);
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

            // For EXPRESS accounts: The live balance represents P&L accumulated since account creation.
            // We need to set highestEndOfDayBalance to this value so MLL calculates correctly.
            // Without this, we'd compare against $0 which would give wrong drawdown calculations.
            if (isExpressAccount && liveBalance > 0) {
                accountState.updateHighestEndOfDayBalance(liveBalance);
                System.out.println("✓ EXPRESS account P&L synced: $" + String.format("%.2f", liveBalance));
                System.out.println("  Highest EOD balance set to: $" + String.format("%.2f", liveBalance));
                System.out.println("  MLL threshold: -$" + String.format("%.2f", riskLimits.getMaxLossLimit()));
                System.out.println("  Max drawdown to: -$" + String.format("%.2f",
                    riskLimits.getMaxLossLimit() - liveBalance));
            } else {
                System.out.println("✓ Account balance synced: $" + String.format("%.2f", liveBalance));
            }

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

            // Start the appropriate engine mode. STDV+OTE multi-instrument
            // wins if enabled; otherwise legacy multi-instrument; otherwise
            // single-symbol fallback.
            if (stdvOteMultiEngine != null) {
                System.out.println("\n[STDV+OTE MULTI-INSTRUMENT] Starting engine...");
                stdvOteMultiEngine.start();
                for (String s : stdvOteMultiEngine.symbolsForSubscription()) {
                    subscribedSymbols.add(s);
                }
                System.out.println("✓ Active: " + stdvOteMultiEngine.getActiveSymbols());
                System.out.println("✓ SMT feeds: " + stdvOteMultiEngine.getSmtOnlySymbols());
            } else if (MULTI_INSTRUMENT_MODE && multiEngine != null) {
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

            // All initial subscriptions have returned — and the historical
            // backfill runs synchronously inside startMarketDataPolling, so
            // replay is finished. Real-time signals may now create orders.
            warmupCompletedAt = Instant.now();
            warmupComplete = true;
            System.out.println("✓ Warmup complete — historical replay done, strategy signals live");

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

            // Schedule stale order cleanup (every 30 seconds)
            // Cancels orders older than 90 minutes OR when killzone is CLOSING
            scheduler.scheduleAtFixedRate(
                this::checkStaleOrders,
                30, 30, TimeUnit.SECONDS
            );

            // Schedule account balance sync with Topstep (every 5 minutes)
            // Ensures local state stays aligned with actual account balance
            scheduler.scheduleAtFixedRate(
                this::syncAccountBalance,
                BALANCE_SYNC_INTERVAL_MINUTES, BALANCE_SYNC_INTERVAL_MINUTES, TimeUnit.MINUTES
            );
            System.out.println("✓ Account balance sync scheduled (every " + BALANCE_SYNC_INTERVAL_MINUTES + " minutes)");

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
            // Warmup guard: record the most recent candle timestamp per
            // symbol BEFORE any strategy dispatch (staleness reference).
            lastCandleTs.put(candle.getSymbol(), candle.getTimestamp());

            // Update context time
            strategyContext.setCurrentTime(candle.getTimestamp());

            // === Convex Payoff: Detect new trading day and sync lifecycle equity ===
            LocalDate candleDate = candle.getTimestamp()
                .atZone(CT_ZONE).toLocalDate();
            if (lastTradingDate == null || !candleDate.equals(lastTradingDate)) {
                if (lastTradingDate != null) {
                    // End of previous day: record daily PnL
                    double dayPnl = accountState.getNetDailyPnl();
                    lifecycle.onDayEnd(dayPnl);
                    System.out.println("[LIFECYCLE] New trading day detected. Previous day PnL: $" +
                        String.format("%.2f", dayPnl));
                }
                tradesToday = 0;
                lastTradingDate = candleDate;
            }

            // Sync lifecycle equity from live account state on every candle
            lifecycle.syncEquityFromLive(accountState.getEquity());
            lifecycle.updateIntradayPnl(accountState.getNetDailyPnl());

            // Process through execution engine first (fills, stops, targets)
            executionEngine.onNewCandle(candle);

            // Check price-based breakeven trigger for single-contract runner positions
            // (e.g., 1 MGC contract running to full tier target, needs breakeven at 1R)
            if (bracketManager != null) {
                String symbol = candle.getSymbol();
                double tickSize = InstrumentCharacteristics.getProfile(symbol).getTickSize();
                bracketManager.checkPriceBreakevenTrigger(symbol, candle.getClose(), tickSize);
            }

            // Feed to appropriate engine (only if not paused and not flattening)
            if (!paused.get() && !flatteningPositions.get()) {
                if (stdvOteMultiEngine != null) {
                    // STDV+OTE multi-instrument mode: route via the new engine.
                    stdvOteMultiEngine.dispatchCandle(candle);
                } else if (MULTI_INSTRUMENT_MODE && multiEngine != null) {
                    // Legacy multi-instrument mode
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
        // ── WARMUP GUARD layer 1: nothing trades until every initial
        // subscription (and its synchronous historical backfill) returned.
        if (!warmupComplete) {
            System.out.println("[Warmup] Suppressing signal during startup warmup: "
                + signal.getSignalType() + " " + signal.getSymbol());
            return;
        }

        // The EventBus is async: a signal created during replay can be
        // dequeued after the flag flipped. Suppress those too.
        Instant completedAt = warmupCompletedAt;
        if (completedAt != null && signal.getTimestamp().isBefore(completedAt)) {
            System.out.println("[Warmup] Suppressing signal created during historical replay: "
                + signal.getSignalType() + " " + signal.getSymbol()
                + " (created=" + signal.getTimestamp() + ")");
            return;
        }

        // ── WARMUP GUARD layer 2: staleness — if the most recent candle for
        // this symbol is > 5 minutes behind wall-clock, the signal came from
        // historical/replayed data and must not create an order.
        Instant lastTs = lastCandleTs.get(signal.getSymbol());
        if (lastTs == null
                || lastTs.isBefore(Instant.now().minusSeconds(STALE_SIGNAL_THRESHOLD_SECONDS))) {
            System.out.println("[Warmup] Suppressing signal from historical/stale data: "
                + signal.getSignalType() + " " + signal.getSymbol()
                + " (lastCandle=" + lastTs + ")");
            return;
        }

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

        // STEP 2: Calculate dynamic risk via PhaseAwareRiskCalculator
        int setupQuality = extractSetupQuality(signal);
        RiskZone currentZone = lifecycle.getCurrentRiskZone();

        // Log lifecycle state before every risk decision
        System.out.println(String.format(
            "[LIFECYCLE] Phase=%s Zone=%s Target=%.1f%% DD=%.1f%% ConsecLoss=%d Budget=$%.0f DLLRoom=$%.0f",
            lifecycle.getCurrentPhase(), currentZone,
            lifecycle.targetCompletionPct() * 100,
            lifecycle.drawdownUsagePct() * 100,
            lifecycle.getConsecutiveLosses(),
            lifecycle.riskBudgetRemaining(),
            lifecycle.dailyLossRoomRemaining()
        ));

        PhaseAwareRiskCalculator.RiskCalculation riskCalc =
            riskCalculator.calculateRisk(lifecycle, riskProfile, setupQuality, tradesToday);

        System.out.println("[DYNAMIC RISK] " + riskCalc);

        if (!riskCalc.isTradingAllowed()) {
            System.out.println("\n❌ Signal DENIED by PhaseAwareRiskCalculator: " + signal.getReason());
            System.out.println("  Reason: " + riskCalc.getBlockReason());
            return;
        }

        // STEP 3: Evaluate against prop firm risk limits with dynamic risk amount
        double dynamicRisk = riskCalc.getRiskDollars();
        RiskDecision decision = riskEngine.evaluate(signal, accountState, riskLimits, dynamicRisk);

        if (decision.isAllowed()) {
            System.out.println("\n✓ LIVE Signal APPROVED: " + signal.getReason());
            System.out.println("  Tier: " + signal.getTier() + " | R:R: 1:" + signal.getRiskRewardRatio());
            System.out.println("  Dynamic Risk: $" + String.format("%.2f", dynamicRisk) +
                " (zone=" + currentZone + ", quality=" + setupQuality + ")");
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

                // Record signal context for trade journal enrichment
                List<String> confluenceFactors = parseConfluenceFromReason(signal.getReason());
                executionEngine.recordSignalContext(signal.getSymbol(), signal.getTier(), confluenceFactors);

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

        if (status == OrderStatus.FILLED && fillQty != null) {
            double effectiveFill = (fillPrice != null && fillPrice > 0)
                ? fillPrice
                : (order.getLimitPrice() != null && order.getLimitPrice() > 0 ? order.getLimitPrice() : signal.getEntryPrice());

            System.out.println("  Filled at $" + String.format("%.5f", effectiveFill) + " x " + fillQty);

            // CRITICAL: Record the fill on the order to mark it as not active
            order.recordFill(fillQty, effectiveFill);

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
            position.updateWithFill(signedFillQty, effectiveFill);
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
                submitProtectiveOrders(signal, fillQty, effectiveFill, orderId);
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

        // Fallback: if strategy prices are missing (should not happen), pull from execution engine
        ExecutionEngine.EnhancedOrderLevels levels = executionEngine.getOrderLevels(symbol);
        if ((stopPrice <= 0 || targetPrice <= 0) && levels != null) {
            stopPrice = levels.getCurrentStopPrice();
            targetPrice = levels.getFinalTargetPrice();
        }

        if (stopPrice <= 0 || targetPrice <= 0) {
            System.err.println("  ❌ Invalid bracket prices for " + symbol + " (stop=" + stopPrice + ", target=" + targetPrice + ")");
            return;
        }

        double tickSize = getTickSize(symbol);

        double roundedStop = roundToTick(stopPrice, tickSize);
        double roundedTarget = roundToTick(targetPrice, tickSize);

        if (Math.abs(stopPrice - roundedStop) > 1e-9 || Math.abs(targetPrice - roundedTarget) > 1e-9) {
            System.out.println("  [TICK ALIGN] " + symbol + " stop " + stopPrice + " -> " + roundedStop +
                ", target " + targetPrice + " -> " + roundedTarget + " (tick=" + tickSize + ")");
        }

        stopPrice = roundedStop;
        targetPrice = roundedTarget;

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

        // Get tick size for breakeven calculation is on line 707
        

        // Use enhanced tiered bracket if quantity > 1 (multiple contracts enable partial profits)
        // Otherwise use legacy single-level bracket
        TradeTier tier = signal.getTier();

        // SCALP MODE (SA3): exactly ONE take-profit at the signal target —
        // never the tier TP ladder (its 2R/3R/5R levels would rest beyond a
        // 1R-capped scalp target). Single OCO bracket via the legacy
        // createBracket path, plus an optional breakeven trigger at +0.5R.
        if (com.topstep.trading.strategy.stdvote.ScalpConfig.isEnabled()) {
            System.out.println("  Using SCALP bracket: single TP @ " + targetPrice
                + " (" + quantity + " contracts)");
            bracketManager.createBracket(
                symbol,
                entryOrderId,
                fillPrice,
                quantity,
                signal.getSide(),
                stopPrice,
                targetPrice
            );
            armScalpBreakevenIfConfigured(symbol, fillPrice, stopPrice, isLong, tickSize);
            return;
        }

        if (quantity > 1 && tier != null) {
            // ENHANCED: Multi-level take profits with breakeven after first partial
            System.out.println("  Using TIERED bracket: " + tier + " with " + quantity + " contracts");
            bracketManager.createBracketWithPartials(
                symbol,
                entryOrderId,
                fillPrice,
                quantity,
                signal.getSide(),
                stopPrice,
                targetPrice,
                tier,
                tickSize
            );
        } else {
            // LEGACY: Single take profit level (for 1 contract or no tier)
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
    }

    /**
     * SCALP mode breakeven: when {@code scalp.breakevenAtHalfR} is true
     * (the default), arm the existing BracketOrderManager price trigger so
     * the stop moves to entry once price reaches +0.5R in the trade's favor.
     */
    private void armScalpBreakevenIfConfigured(String symbol, double entryPrice,
                                               double stopPrice, boolean isLong,
                                               double tickSize) {
        if (bracketManager == null) return;
        if (!com.topstep.trading.strategy.stdvote.ScalpConfig.breakevenAtHalfR()) return;
        double risk = Math.abs(entryPrice - stopPrice);
        if (risk <= 0) return;
        double halfR = risk * com.topstep.trading.strategy.stdvote.ScalpConfig.BREAKEVEN_TRIGGER_R;
        double trigger = isLong ? (entryPrice + halfR) : (entryPrice - halfR);
        bracketManager.armPriceBreakevenTrigger(symbol, roundToTick(trigger, tickSize));
    }

    /**
     * Calculate PnL for a closed position.
     */
    /**
     * Record a Trade for a live broker-side exit (bracket SL/TP/partial).
     * Live fills bypass ExecutionEngine.closePosition, so without this the
     * Trades tab / journal / metrics never see live trades. Journaling only:
     * AccountState P&L and frequency gates are updated by the callers.
     */
    private void recordLiveTrade(BracketOrderManager.BracketOrder bracket, double exitPrice,
                                 int quantity, double pnl, String reason) {
        try {
            double stopForRisk = bracket.originalStopPrice > 0 ? bracket.originalStopPrice : bracket.stopPrice;
            double riskAmount = Math.abs(calculatePnl(bracket.symbol, bracket.entryPrice,
                stopForRisk, quantity, bracket.entrySide));
            executionEngine.recordExternalTrade(com.topstep.trading.domain.Trade.builder()
                .symbol(bracket.symbol)
                .side(bracket.entrySide)
                .quantity(quantity)
                .entryPrice(bracket.entryPrice)
                .exitPrice(exitPrice)
                .entryTime(bracket.createdAt)
                .exitTime(java.time.Instant.now())
                .realizedPnL(pnl)
                .riskAmount(riskAmount)
                .tier(bracket.tier)
                .notes(reason)
                .build());
        } catch (Exception e) {
            System.err.println("Failed to record live trade for " + bracket.symbol + ": " + e.getMessage());
        }
    }

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
            case "MES": return 1.25;
            case "NQ": return 5.00;
            case "MNQ": return 0.50;
            case "GC": case "NG": return 10.00;
            case "MGC": return 1.00;
            case "SI": return 25.00;
            default: return 12.50;
        }
    }

    /**
     * Get the tick size (minimum price increment) for a symbol. (Other in on line 786)
     */
    private double getTickSize(String symbol) {
        switch (symbol.toUpperCase()) {
            case "ES":
            case "MES":
                return 0.25;
            case "NQ":
            case "MNQ":
                return 0.25;
            case "GC":
            case "MGC":
                return 0.10;
            case "NG":
                return 0.001;
            case "SI":
                return 0.005;
            default:
                return 0.25;
        }
    }

    /**
     * Round a price to the nearest valid tick increment to satisfy exchange requirements.
     */
    private double roundToTick(double price, double tickSize) {
        double ticks = Math.round(price / tickSize);
        double rounded = ticks * tickSize;

        int decimals = getDecimalPlaces(tickSize);
        double multiplier = Math.pow(10, decimals);
        return Math.round(rounded * multiplier) / multiplier;
    }

    /**
     * Determine decimal places for rounding based on tick size (e.g., 0.00005 -> 5 decimals).
     */
    private int getDecimalPlaces(double tickSize) {
        String tickStr = String.valueOf(tickSize);
        int index = tickStr.indexOf('.') >= 0 ? tickStr.length() - tickStr.indexOf('.') - 1 : 0;
        while (index > 0 && tickStr.endsWith("0")) {
            tickStr = tickStr.substring(0, tickStr.length() - 1);
            index--;
        }
        return Math.max(index, 0);
    }

    /**
     * Notify risk manager and lifecycle when a position is closed.
     * Should be called when a trade completes (stop hit or target hit).
     */
    public void notifyPositionClosed(String symbol, double pnl) {
        if (MULTI_INSTRUMENT_MODE && multiEngine != null) {
            multiEngine.notifyPositionClosed(symbol, pnl);
        }

        // === Convex Payoff: Track trade in lifecycle for zone transitions ===
        lifecycle.recordTrade(pnl);
        tradesToday++;
        System.out.println(String.format(
            "[LIFECYCLE] Trade recorded: $%.2f | ConsecLoss=%d | TradesToday=%d | Zone=%s",
            pnl, lifecycle.getConsecutiveLosses(), tradesToday, lifecycle.getCurrentRiskZone()
        ));
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
     * Sync account balance with Topstep.
     *
     * This method periodically fetches the actual account balance from Topstep
     * to ensure our local state stays aligned. This catches:
     * - Manual trades made outside the bot
     * - Commission fees
     * - Any discrepancies from order execution
     *
     * If a significant discrepancy is found (> $50), it logs a warning.
     *
     * EXPRESS ACCOUNTS: Balance represents P&L starting from $0 (not $50K).
     * Valid range: -$2500 (below MLL buffer) to any positive amount.
     *
     * REGULAR ACCOUNTS: Balance should be >= $1000.
     */
    private void syncAccountBalance() {
        if (!running.get() || killSwitchActive.get()) {
            return;
        }

        try {
            // Fetch live balance from Topstep
            double liveBalance = connector.getAccountBalance();
            double localBalance = accountState.getCurrentBalance();

            // Validate balance based on account type
            if (isExpressAccount) {
                // EXPRESS accounts: balance is P&L from $0, can be negative down to MLL
                // Valid range: >= -$2500 (buffer below $2000 MLL for $50K account)
                if (liveBalance < -2500) {
                    System.err.println("[BALANCE SYNC] EXPRESS: Rejected invalid balance from API: $" +
                        String.format("%.2f", liveBalance) + " (below MLL threshold)");
                    return;
                }
                System.out.println("[BALANCE SYNC] EXPRESS account P&L: $" + String.format("%.2f", liveBalance));
            } else {
                // Regular accounts: balance should be >= $1000
                if (liveBalance < 1000) {
                    System.err.println("[BALANCE SYNC] Rejected invalid balance from API: $" +
                        String.format("%.2f", liveBalance) + " (keeping local: $" +
                        String.format("%.2f", localBalance) + ")");
                    return;
                }
            }

            double discrepancy = Math.abs(liveBalance - localBalance);

            // Log sync result
            if (discrepancy > 50.0) {
                // Significant discrepancy detected
                System.out.println("\n⚠️  BALANCE DISCREPANCY DETECTED");
                System.out.println("  Topstep Balance: $" + String.format("%.2f", liveBalance));
                System.out.println("  Local Balance:   $" + String.format("%.2f", localBalance));
                System.out.println("  Discrepancy:     $" + String.format("%.2f", discrepancy));

                // Update local state to match Topstep (source of truth)
                accountState.setCurrentBalance(liveBalance);
                System.out.println("  ✓ Local balance updated to match Topstep");
            } else if (discrepancy > 10.0) {
                // Minor discrepancy - just sync silently
                accountState.setCurrentBalance(liveBalance);
            }
            // If discrepancy <= $10, no action needed (likely just unrealized PnL timing)

        } catch (Exception e) {
            // Don't crash on sync failure - just log and continue
            System.err.println("[BALANCE SYNC] Failed to sync balance: " + e.getMessage());
        }
    }

    /**
     * Check for stale (expired) pending orders and cancel them.
     *
     * HYBRID APPROACH:
     * Cancel unfilled limit orders if EITHER condition is true:
     * 1. Order is older than ORDER_TIMEOUT_MINUTES (90 minutes by default)
     * 2. Current killzone phase is CLOSING (end of optimal trading window)
     *
     * This ensures signals remain fresh and aligned with ICT methodology.
     * Stale limit orders tie up capital and may fill at prices no longer valid.
     */
    private void checkStaleOrders() {
        if (!running.get() || flatteningPositions.get()) {
            return;
        }

        Instant now = Instant.now();
        Duration timeoutDuration = Duration.ofMinutes(ORDER_TIMEOUT_MINUTES);

        // Check current killzone phase
        KillzonePhase currentPhase = killzoneClock.getKillzonePhase(now);
        boolean isKillzoneClosing = (currentPhase == KillzonePhase.CLOSING);
        boolean isOutsideKillzone = !killzoneClock.isInKillzone(now);

        // Get all active orders across all symbols
        Map<String, Order> activeOrders = executionEngine.getActiveOrders();
        if (activeOrders.isEmpty()) {
            return;
        }

        // Collect orders to cancel (avoid concurrent modification)
        List<Order> ordersToCancel = new ArrayList<>();
        List<String> cancelReasons = new ArrayList<>();

        for (Order order : activeOrders.values()) {
            if (!order.isActive()) {
                continue;
            }

            Instant orderCreatedAt = order.getCreatedAt();
            Duration orderAge = Duration.between(orderCreatedAt, now);

            String cancelReason = null;

            // Check timeout (90 minutes)
            if (orderAge.compareTo(timeoutDuration) > 0) {
                cancelReason = "TIMEOUT: Order age " + orderAge.toMinutes() + " min > " + ORDER_TIMEOUT_MINUTES + " min limit";
            }
            // Check killzone closing
            else if (isKillzoneClosing) {
                cancelReason = "KILLZONE CLOSING: Cancelling to avoid late fills outside optimal window";
            }
            // Check if we're now outside killzone entirely
            else if (isOutsideKillzone && orderAge.toMinutes() >= 30) {
                // Only cancel if order is at least 30 min old and we're outside killzone
                cancelReason = "OUTSIDE KILLZONE: Order placed during killzone but killzone has ended";
            }

            if (cancelReason != null) {
                ordersToCancel.add(order);
                cancelReasons.add(cancelReason);
            }
        }

        // Cancel stale orders
        for (int i = 0; i < ordersToCancel.size(); i++) {
            Order order = ordersToCancel.get(i);
            String reason = cancelReasons.get(i);

            try {
                String orderId = order.getOrderId();
                String symbol = order.getSymbol();

                System.out.println("\n⏱ STALE ORDER CANCELLED: " + symbol);
                System.out.println("  Order: " + order.getSide() + " @ " + String.format("%.5f", order.getLimitPrice()));
                System.out.println("  Reason: " + reason);
                System.out.println("  Age: " + Duration.between(order.getCreatedAt(), now).toMinutes() + " minutes");

                if (orderId != null && !orderId.isEmpty()) {
                    connector.cancelOrder(orderId);
                    // Check if it was a server-assigned ID (numeric) or client-generated
                    try {
                        Long.parseLong(orderId);
                        System.out.println("  ✓ Cancelled on exchange: " + orderId);
                    } catch (NumberFormatException e) {
                        System.out.println("  ⚠ Removed from local tracking (no server ID): " + orderId);
                    }
                } else {
                    System.out.println("  ⚠ Order had no ID - removed from local tracking only");
                }

                // Remove from execution engine
                executionEngine.removeOrderById(symbol, orderId);

            } catch (Exception e) {
                System.err.println("  ❌ Failed to cancel stale order: " + e.getMessage());
            }
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

                        // Capture entry context now — the position is removed
                        // from account state below, before the fill callback.
                        final OrderSide entrySide = position.getSide();
                        final double entryPrice = position.getAvgEntryPrice();
                        final int closedQty = Math.abs(position.getQuantity());
                        final java.time.Instant openedAt = position.getOpenedAt();
                        final String flattenReason = reason;

                        String orderId = connector.submitOrder(closeOrder, (id, status, price, qty) -> {
                            if (status == OrderStatus.FILLED && price != null) {
                                System.out.println("  ✓ Closed " + symbol + " at $" + price);
                                double pnl = calculatePnl(symbol, entryPrice, price, closedQty, entrySide);
                                // Book P&L and journal the trade — flatten fills
                                // bypass both ExecutionEngine and the bracket
                                // manager, so nothing else records them.
                                accountState.recordRealizedPnL(pnl);
                                accountState.recordTradeCompleted(pnl);
                                executionEngine.recordExternalTrade(com.topstep.trading.domain.Trade.builder()
                                    .symbol(symbol)
                                    .side(entrySide)
                                    .quantity(closedQty)
                                    .entryPrice(entryPrice)
                                    .exitPrice(price)
                                    .entryTime(openedAt)
                                    .exitTime(java.time.Instant.now())
                                    .realizedPnL(pnl)
                                    .notes("Flattened: " + flattenReason)
                                    .build());
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

        // Cancel any working brackets and clear tracked positions instead of
        // submitting new market orders during shutdown.
        if (!accountState.getPositions().isEmpty()) {
            System.out.println("⚠️  Positions still open - cancelling protection and clearing state (no flatten orders)");
            if (bracketManager != null) {
                bracketManager.cancelAllBrackets("Engine shutdown");
            }
            accountState.clearAllPositions();
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

        // Finalize any in-progress HTF candles before shutdown
        if (stdvOteMultiEngine != null) {
            stdvOteMultiEngine.stop();
        } else if (MULTI_INSTRUMENT_MODE && multiEngine != null) {
            multiEngine.onSessionEnd();
            multiEngine.stop();
        } else {
            strategy.onSessionEnd();
            strategy.shutdown();
        }

        // Disconnect from market
        connector.disconnect();

        // Print and persist the session journal
        List<Trade> sessionTrades = executionEngine.getCompletedTrades();
        journalService.onSessionEnd(sessionTrades);

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

    /**
     * Extract a numeric setup quality score (0-10) from the strategy signal.
     * Derives quality from the trade tier since signals don't carry a separate quality score.
     *
     * Tier mapping:
     *   TIER_4 (Elite) = 9, TIER_3 (Premium) = 7, TIER_2 (Standard) = 5, TIER_1 = 3
     */
    private int extractSetupQuality(StrategySignalEvent signal) {
        TradeTier tier = signal.getTier();
        if (tier == null) return 5;  // default to standard

        switch (tier) {
            case TIER_4: return 9;   // Elite: highest quality
            case TIER_3: return 7;   // Premium: high quality
            case TIER_2: return 5;   // Standard: acceptable
            case TIER_1: return 3;   // Low: will be filtered by quality gate
            default:     return 5;
        }
    }

    // === Convex Payoff Optimization getters ===
    public AccountLifecycle getLifecycle() { return lifecycle; }
    public PhaseAwareRiskCalculator getRiskCalculator() { return riskCalculator; }
    public RiskProfile getRiskProfile() { return riskProfile; }

    // Getters for facade access
    public AccountState getAccountState() { return accountState; }
    public ExecutionEngine getExecutionEngine() { return executionEngine; }
    public RiskLimits getRiskLimits() { return riskLimits; }
    /** Swap in updated limits (tighten-only validation lives in EngineFacade.updateRiskSettings). */
    public void setRiskLimits(RiskLimits limits) { this.riskLimits = limits; }
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
     * Parse confluence factors from the signal reason string.
     * The reason string contains confluence details separated by common delimiters.
     */
    private List<String> parseConfluenceFromReason(String reason) {
        if (reason == null || reason.isBlank()) return List.of("Unknown");
        return Arrays.stream(reason.split("[|,;]+"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toList());
    }

    /**
     * Main entry point for LIVE mode.
     */
    public static void run() {
        LiveEngineRunner runner = new LiveEngineRunner();

        // Add shutdown hook for emergency shutdown and journal save
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nReceived shutdown signal...");
            System.out.println("[Journal] Shutdown hook triggered - saving journal...");
            runner.journalService.onSessionEnd(runner.executionEngine.getCompletedTrades());
            runner.emergencyShutdown("System shutdown signal");
        }));

        // Start the engine
        runner.start();
    }
}
