package com.topstep.trading;

import com.topstep.trading.connector.MockConnector;
import com.topstep.trading.connector.TradingConnector;
import com.topstep.trading.domain.AccountState;
import com.topstep.trading.domain.Candle;
import com.topstep.trading.domain.Order;
import com.topstep.trading.domain.RiskLimits;
import com.topstep.trading.domain.Trade;
import com.topstep.trading.event.EventBus;
import com.topstep.trading.event.StrategySignalEvent;
import com.topstep.trading.execution.ExecutionEngine;
import com.topstep.trading.journal.TradeJournalService;
import com.topstep.trading.risk.PropFirmRiskEngine;
import com.topstep.trading.risk.RiskDecision;
import com.topstep.trading.strategy.DefaultStrategyContext;
import com.topstep.trading.strategy.IctHighConfluenceStrategy;
import com.topstep.trading.strategy.TradingStrategy;
import com.topstep.trading.strategy.stdvote.StdvOteFactory;
import com.topstep.trading.strategy.stdvote.StdvOteMultiInstrumentEngine;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

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

    // STDV+OTE refactor: default to MNQ (the registry-allowed micro symbol).
    // Override via -Dstdvote.symbol=<MNQ|MES|MGC> if desired. Setting any
    // other symbol routes the factory to the legacy strategy as a fallback.
    private static final String DEFAULT_SYMBOL =
            System.getProperty("stdvote.symbol", "MNQ");

    /**
     * When true (the default under stdvOte mode), the runner uses
     * {@link StdvOteMultiInstrumentEngine} to drive MNQ + MGC concurrently
     * with MES as an SMT feed. Override with
     * {@code -Dstdvote.multiInstrument=false} to fall back to single-symbol
     * mode driven by {@code DEFAULT_SYMBOL}.
     */
    private static final boolean MULTI_INSTRUMENT_ENABLED =
            StdvOteFactory.isEnabled()
                    && !"false".equalsIgnoreCase(
                        System.getProperty("stdvote.multiInstrument", "true"));

    private final TradingConnector connector;
    private final AccountState accountState;
    // Volatile (not final): the dashboard risk-settings endpoint can swap in
    // a tightened copy at runtime via setRiskLimits.
    private volatile RiskLimits riskLimits;
    private final PropFirmRiskEngine riskEngine;
    private final ExecutionEngine executionEngine;
    private final TradingStrategy strategy;
    private final StdvOteMultiInstrumentEngine multiEngine;
    private final EventBus eventBus;
    private final DefaultStrategyContext strategyContext;

    private final TradeJournalService journalService = new TradeJournalService();

    // Chart-in-memory: SIM gets the same chart brain as LIVE so the
    // /api/chart endpoint and the OTE observability logs work identically
    // in both modes (Agent 11 SIM verification depends on this).
    private final com.topstep.trading.chart.ChartEngine chartEngine =
            new com.topstep.trading.chart.ChartEngine();

    // V4 Agent 02 — the ICT detection library, hung off the chart's candle tap
    // so it provably reads the same bars the Bot Chart draws (ONE ingest seam).
    // Observation-grade: it feeds the chart overlay, the confluence snapshot
    // and the profile simulator, and gates nothing.
    private final com.topstep.trading.ictlib.IctLibEngine ictLibEngine =
            com.topstep.trading.ictlib.IctLibEngine.attachTo(chartEngine);

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

    // ── WARMUP GUARD (SIM mirror of LiveEngineRunner's layers 1+2) ──────
    // The SIM warm boot replays days of synthetic history through the same
    // candle path as live-sim ticks; a replay-era candle could satisfy every
    // gate and emit a signal for a price from "yesterday". Layer 1: nothing
    // trades until every subscription (and its synchronous warm boot) has
    // returned. Layer 2: the EventBus is async, so signals CREATED during
    // the warm boot are suppressed even when dequeued after it. Layer 3
    // (wall-clock staleness) is deliberately NOT mirrored here: the mock's
    // virtual-clock acceleration stamps non-wall-clock times, which would
    // suppress every signal in accelerated SIM runs.
    private volatile boolean warmupComplete = false;
    private volatile java.time.Instant warmupCompletedAt = null;

    /**
     * Create a new SIM engine with default Topstep 50K configuration.
     * The RiskLimits profile is selected by ScalpConfig: legacy topstep50k()
     * unless -DscalpMode.enabled=true (then topstep50kScalp()).
     */
    public SimEngineRunner() {
        this(50_000.0, com.topstep.trading.strategy.stdvote.ScalpConfig.activeRiskLimits());
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
        // Publish PositionClosedEvent from the sim close funnel (scalp-mode
        // re-arm subscribes to it; no-op for legacy consumers).
        this.executionEngine.setEventBus(eventBus);
        this.strategyContext = new DefaultStrategyContext(accountState);

        if (MULTI_INSTRUMENT_ENABLED) {
            // Multi-instrument STDV+OTE: MNQ + MGC active, MES as SMT for MNQ.
            // The engine owns one StdvOteRunnerStrategy per active symbol;
            // we keep a reference to the primary for EngineFacade compat.
            this.multiEngine = new StdvOteMultiInstrumentEngine(
                    connector, eventBus, strategyContext);
            this.strategy = multiEngine.getPrimaryStrategy();
            System.out.println("  Multi-instrument active="
                    + multiEngine.getActiveSymbols()
                    + " smtOnly=" + multiEngine.getSmtOnlySymbols());
        } else {
            this.multiEngine = null;
            this.strategy = StdvOteFactory.build(DEFAULT_SYMBOL, "MES", eventBus);
        }

        // Chart-in-memory wiring (mirrors LiveEngineRunner): tick sizes from
        // the instrument spec, candle tap on the multi-engine's dispatch
        // (which subscribes symbols itself, bypassing this.onMarketData).
        for (String s : new String[] {"MNQ", "MES", "MGC"}) {
            chartEngine.registerInstrument(s,
                    com.topstep.trading.strategy.InstrumentCharacteristics
                            .getProfile(s).getTickSize());
            // V2 Agent 05: per-instrument leg thresholds via
            // -Dchart.minLegTicks.<SYM> etc.; defaults preserved, logged.
            chartEngine.applySystemPropertyTuning(s);
        }
        if (multiEngine != null) {
            multiEngine.setCandleTap(chartEngine::onCandle);
            multiEngine.setChartEngine(chartEngine);
            multiEngine.setIctLibEngine(ictLibEngine);
        } else if (strategy instanceof com.topstep.trading.strategy.stdvote.StdvOteRunnerStrategy sors) {
            sors.setChartEngine(chartEngine);
            sors.setIctLibEngine(ictLibEngine);
        }

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

            // CRITICAL: Start EventBus BEFORE strategy initialization
            // Without this, all signals published by strategy are silently dropped!
            eventBus.start();
            System.out.println("✓ EventBus started");

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
            EngineFacade.getInstance().setChartEngine(chartEngine);
            EngineFacade.getInstance().setIctLibEngine(ictLibEngine);

            // Subscribe to market data. Multi-instrument mode owns its own
            // subscriptions for all active + SMT-only symbols; single-symbol
            // mode subscribes the legacy way.
            String subscribedSymbols;
            if (multiEngine != null) {
                multiEngine.start();
                subscribedSymbols = String.join(",", multiEngine.symbolsForSubscription());
            } else {
                connector.subscribeMarketData(DEFAULT_SYMBOL, this::onMarketData);
                subscribedSymbols = DEFAULT_SYMBOL;
            }

            // All subscriptions have returned — the synchronous SIM warm
            // boot (synthetic backfill) is finished. Signals may now trade.
            warmupCompletedAt = java.time.Instant.now();
            warmupComplete = true;
            System.out.println("✓ SIM warmup complete — synthetic replay done, strategy signals live");

            running.set(true);

            System.out.println("\n✓ SIM engine started successfully");
            System.out.println("  Trading symbols: " + subscribedSymbols);
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

        // Finalize any in-progress HTF candles before shutdown
        if (multiEngine != null) {
            multiEngine.stop();
        } else {
            strategy.onSessionEnd();
            strategy.shutdown();
        }

        // CRITICAL: Stop EventBus to prevent thread leaks
        eventBus.stop();
        System.out.println("✓ EventBus stopped");

        // Disconnect from market data
        connector.disconnect();

        // Print and persist the session journal
        List<Trade> sessionTrades = executionEngine.getCompletedTrades();
        journalService.onSessionEnd(sessionTrades);

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
            // Chart-in-memory first: the internal 30m chart sees every
            // candle this runner processes (single-instrument path; the
            // multi-engine path feeds the chart via its candle tap).
            chartEngine.onCandle(candle);

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
    /**
     * SIM twin of LiveEngineRunner's release (2026-07-27 no-trade fix): a
     * suppressed or vetoed signal must free the strategy's IN_TRADE /
     * positionOpen latch, or the symbol never re-arms until restart. The
     * synthetic PositionClosedEvent's only subscriber is the strategy runner.
     */
    private void releaseUnexecutedSignal(StrategySignalEvent signal, String why) {
        System.out.println("[SignalRelease] SIM " + signal.getSymbol() + ": " + why
                + " — releasing strategy latch (no order/position created)");
        eventBus.publish(new com.topstep.trading.event.PositionClosedEvent(
                signal.getSymbol(), 0.0, false, java.time.Instant.now()));
    }

    private void handleStrategySignal(StrategySignalEvent signal) {
        // ── WARMUP GUARD layer 1 (SIM): nothing trades until every
        // subscription (and its synchronous synthetic warm boot) returned.
        if (!warmupComplete) {
            System.out.println("[Warmup] SIM: suppressing signal during warm-boot replay: "
                + signal.getSignalType() + " " + signal.getSymbol());
            releaseUnexecutedSignal(signal, "warmup suppression");
            return;
        }

        // ── layer 2 (SIM): the EventBus is async — a signal CREATED during
        // the warm boot can be dequeued after the flag flipped.
        if (WarmupGuard.createdDuringWarmup(signal.getTimestamp(), warmupCompletedAt)) {
            System.out.println("[Warmup] SIM: suppressing signal created during warm-boot replay: "
                + signal.getSignalType() + " " + signal.getSymbol()
                + " (created=" + signal.getTimestamp() + ")");
            releaseUnexecutedSignal(signal, "created during warm-boot replay");
            return;
        }

        if (paused.get()) {
            System.out.println("\n⏸ Signal ignored (paused): " + signal.getReason());
            releaseUnexecutedSignal(signal, "paused");
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

            // Record signal context for trade journal enrichment
            List<String> confluenceFactors = parseConfluenceFromReason(signal.getReason());
            executionEngine.recordSignalContext(signal.getSymbol(), signal.getTier(), confluenceFactors);

            // Print account status
            printAccountStatus();

        } else {
            System.out.println("\n❌ Signal DENIED: " + signal.getReason());
            System.out.println("  Reason: " + decision.getReason());
            releaseUnexecutedSignal(signal, "risk engine deny");
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

    /** Swap in updated limits (tighten-only validation lives in EngineFacade.updateRiskSettings). */
    public void setRiskLimits(RiskLimits limits) {
        this.riskLimits = limits;
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
     * Parse confluence factors from the signal reason string.
     */
    private List<String> parseConfluenceFromReason(String reason) {
        if (reason == null || reason.isBlank()) return List.of("Unknown");
        return Arrays.stream(reason.split("[|,;]+"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toList());
    }

    /**
     * Main entry point for SIM mode.
     */
    public static void run() {
        SimEngineRunner runner = new SimEngineRunner();

        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nReceived shutdown signal...");
            System.out.println("[Journal] Shutdown hook triggered - saving journal...");
            runner.journalService.onSessionEnd(runner.executionEngine.getCompletedTrades());
            runner.stop();
        }));

        // Start the engine
        runner.start();
    }
}
