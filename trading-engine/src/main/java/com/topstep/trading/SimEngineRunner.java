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

    // V5 Agent 01: configuration comes from the ONE EngineConfig loaded at
    // construction (no static -D reads). engine.symbol (legacy
    // stdvote.symbol) defaults to MNQ; a non-{MNQ,MES,MGC} symbol FAILS FAST
    // in StdvOteFactory unless strategy.legacyFallback=true.
    private final com.topstep.trading.config.EngineConfig config =
            com.topstep.trading.config.EngineConfig.current();
    private final String DEFAULT_SYMBOL = config.getString("engine.symbol", "MNQ");

    /**
     * When true (the default under stdvOte mode), the runner uses
     * {@link StdvOteMultiInstrumentEngine} to drive MNQ + MGC concurrently
     * with MES as an SMT feed. engine.multiInstrument=false (legacy
     * stdvote.multiInstrument) falls back to single-symbol mode driven by
     * {@code DEFAULT_SYMBOL}.
     */
    private final boolean MULTI_INSTRUMENT_ENABLED =
            StdvOteFactory.isEnabled() && config.getBoolean("engine.multiInstrument", true);

    /** V5 Agent 01 wiring check: set at the first candle this runner sees. */
    private final AtomicBoolean firstCandleSeen = new AtomicBoolean(false);
    private volatile boolean wiredBeforeFirstCandle = false;
    /** Last (state|gate) published per symbol — GateDecisionEvents are transitions, not per-bar spam. */
    private final java.util.Map<String, String> lastSetupDecision = new java.util.concurrent.ConcurrentHashMap<>();

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

    // V4 Agent 07 — the confluence stack. Pure aggregation over the chart,
    // the ICT library and the facts the strategy publishes each bar; it
    // gates nothing.
    private final com.topstep.trading.confluence.ConfluenceService confluenceService =
            new com.topstep.trading.confluence.ConfluenceService();

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
    /** AGENT-05 (V5 RC-15): per-symbol readiness + timeout, candle-time based. */
    private volatile WarmupGuard.Tracker warmup;
    /** AGENT-05: signals dropped by the warmup guard in this runner. */
    private final java.util.concurrent.atomic.AtomicLong warmupDrops = new java.util.concurrent.atomic.AtomicLong();

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
        this(startingBalance, riskLimits, new MockConnector(startingBalance));
    }

    /**
     * AGENT-05: connector-injecting constructor (tests drive a scripted
     * connector with deterministic candle times; production uses the
     * MockConnector via the constructors above).
     */
    SimEngineRunner(double startingBalance, RiskLimits riskLimits, TradingConnector connector) {
        // Initialize account
        this.accountState = new AccountState(startingBalance);
        this.riskLimits = riskLimits;

        // Initialize trading components
        this.connector = connector;
        this.executionEngine = new ExecutionEngine(accountState);
        this.riskEngine = new PropFirmRiskEngine();
        this.eventBus = new EventBus();
        // Publish PositionClosedEvent from the sim close funnel (the runner's
        // re-arm / latch release subscribes to it in BOTH modes, RC-16).
        this.executionEngine.setEventBus(eventBus);
        // AGENT-05 (V5 RC-17): every risk deny publishes a GateDecisionEvent;
        // the execution path carries the 14:45 CT flatten safety net and the
        // SIM order TTL (order.ttlBars).
        this.riskEngine.setEventBus(eventBus);
        this.executionEngine.setFlattenSafetyNet(true);
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
        confluenceService.setChartEngine(chartEngine);
        confluenceService.setIctLibEngine(ictLibEngine);
        for (String s : new String[] {"MNQ", "MES", "MGC"}) {
            confluenceService.registerInstrument(s,
                    com.topstep.trading.strategy.InstrumentCharacteristics
                            .getProfile(s).getTickSize());
        }
        if (multiEngine != null) {
            // DEFECT FIX (V4 follow-up): in multi-instrument mode the engine
            // subscribes symbols ITSELF, so onMarketData never runs for
            // MNQ/MGC and the EXECUTION ENGINE never saw a candle. Its fills,
            // stops and targets are all driven by onNewCandle, so a resting
            // limit order could never fill: signals were approved, orders were
            // submitted, and nothing ever happened. The tap is the only place
            // every candle passes through in this mode, so the execution
            // engine has to hang off it too.
            multiEngine.setCandleTap(candle -> {
                verifyWiringAtFirstCandle(candle);
                WarmupGuard.Tracker w = warmup;
                if (w != null) w.onCandle(candle.getSymbol(), candle.getTimestamp());
                chartEngine.onCandle(candle);
                strategyContext.setCurrentTime(candle.getTimestamp());
                executionEngine.onNewCandle(candle);
                publishSetupDecision(candle);
                // AGENT-05 (DIAGNOSIS §5): multi mode never ran the account
                // checks — DLL/MLL breach never stopped SIM. It does now.
                if (running.get()) {
                    checkRiskLimits();
                }
            });
            multiEngine.setChartEngine(chartEngine);
            multiEngine.setIctLibEngine(ictLibEngine);
            multiEngine.setConfluenceService(confluenceService);
        } else if (strategy instanceof com.topstep.trading.strategy.stdvote.StdvOteRunnerStrategy sors) {
            sors.setChartEngine(chartEngine);
            sors.setIctLibEngine(ictLibEngine);
            sors.setConfluenceService(confluenceService);
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

            // V5 Agent 01 (D-14): every handler must be subscribed and the bus
            // running BEFORE the connector can deliver its first candle (the
            // warm boot replays synchronously inside the subscribe below).
            assertWiredForCandles();

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
            EngineFacade.getInstance().setConfluenceService(confluenceService);

            // Subscribe to market data. Multi-instrument mode owns its own
            // subscriptions for all active + SMT-only symbols; single-symbol
            // mode subscribes the legacy way.
            String subscribedSymbols;
            java.util.List<String> required = (multiEngine != null)
                    ? multiEngine.symbolsForSubscription()
                    : java.util.List.of(DEFAULT_SYMBOL);
            warmup = new WarmupGuard.Tracker(required,
                    com.topstep.trading.risk.RiskConfig.warmupTimeoutSeconds());
            if (multiEngine != null) {
                multiEngine.start();
                subscribedSymbols = String.join(",", multiEngine.symbolsForSubscription());
            } else {
                connector.subscribeMarketData(DEFAULT_SYMBOL, this::onMarketData);
                subscribedSymbols = DEFAULT_SYMBOL;
            }

            // All subscriptions have returned — the synchronous SIM warm
            // boot (synthetic backfill) is finished. Signals may now trade.
            // AGENT-05 (V5 RC-15): synchronous warm boot done -> later candles
            // are LIVE; warmup completes when every required feed delivered
            // one live candle, or after warmup.timeoutSeconds with a WARN.
            warmup.beginLive();
            warmupCompletedAt = java.time.Instant.now();
            warmupComplete = true;
            System.out.println("✓ SIM warm boot returned — signals trade once every feed is live "
                    + "(required=" + required + ", timeout="
                    + com.topstep.trading.risk.RiskConfig.warmupTimeoutSeconds() + "s)");

            running.set(true);

            System.out.println("\n✓ SIM engine started successfully");
            System.out.println("  Trading symbols: " + subscribedSymbols);
            System.out.println("  Connector: " + connector.getName());
            System.out.println("\nWaiting for market data...\n");

            // Keep running until stopped
            shutdownLatch.await();

        } catch (Exception e) {
            com.topstep.trading.event.EngineTelemetry.error("SimEngineRunner.start", e);
            System.err.println("Failed to start SIM engine: " + e.getMessage());
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
            verifyWiringAtFirstCandle(candle);
            WarmupGuard.Tracker w = warmup;
            if (w != null) w.onCandle(candle.getSymbol(), candle.getTimestamp());
            // Chart-in-memory first: the internal 30m chart sees every
            // candle this runner processes (single-instrument path; the
            // multi-engine path feeds the chart via its candle tap).
            chartEngine.onCandle(candle);

            // Update context time
            strategyContext.setCurrentTime(candle.getTimestamp());

            // Process through execution engine first (fills, stops, targets)
            executionEngine.onNewCandle(candle);

            // Feed to strategy (only if not paused). The signal inherits
            // this candle's MARKET time (SignalCandleClock, RC-15).
            if (!paused.get()) {
                com.topstep.trading.event.SignalCandleClock.set(candle.getTimestamp());
                try {
                    strategy.onCandle(candle, strategyContext);
                } finally {
                    com.topstep.trading.event.SignalCandleClock.clear();
                }
            }

            // Check risk limits
            checkRiskLimits();
            publishSetupDecision(candle);

        } catch (Exception e) {
            com.topstep.trading.event.EngineTelemetry.error("SimEngineRunner.onMarketData", e);
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
        publishSignalDecision(signal, why);
        eventBus.publish(new com.topstep.trading.event.PositionClosedEvent(
                signal.getSymbol(), 0.0, false, java.time.Instant.now()));
    }

    private void handleStrategySignal(StrategySignalEvent signal) {
        publishSignalDecision(signal, "SIGNAL received");
        // ── WARMUP GUARD (V5 RC-15, deterministic): nothing trades until
        // every required feed delivered a live candle (or the timeout
        // fired with a WARN), and a signal whose CANDLE time precedes the
        // warmup-completion candle is replay-era — dropped, published,
        // counted.
        WarmupGuard.Tracker w = warmup;
        String warmupDrop = (w == null)
                ? "WARMUP: engine not started"
                : w.dropReason(signal.getCandleTime(), signal.getTimestamp());
        if (warmupDrop != null) {
            warmupDrops.incrementAndGet();
            WarmupGuard.recordDrop();
            System.out.println("[Warmup] SIM: dropping " + signal.getSignalType() + " "
                    + signal.getSymbol() + " — " + warmupDrop);
            publishGate(signal, "WARMUP", warmupDrop, epoch(signal.getCandleTime()),
                    w == null ? Double.NaN : epoch(w.completionCandleTime()));
            releaseLatch(signal, "warmup suppression");
            return;
        }

        if (paused.get()) {
            System.out.println("\n⏸ Signal ignored (paused): " + signal.getReason());
            publishGate(signal, "PAUSED", "ENGINE: paused — signal not executed", Double.NaN, Double.NaN);
            releaseLatch(signal, "paused");
            return;
        }

        // ── FLATTEN / NO-ENTRY safety net (defence in depth; SACRED):
        // no new entry inside 14:45–17:00 CT, whatever the session gate said.
        java.time.Instant entryTime = signal.getCandleTime() != null
                ? signal.getCandleTime() : strategyContext.getCurrentTime();
        if (com.topstep.trading.risk.RiskConfig.inNoEntryBlock(entryTime)) {
            String why = "FLATTEN: no new entries 14:45-17:00 CT (signal candle " + entryTime + ")";
            System.out.println("\n❌ Signal DENIED: " + why);
            publishGate(signal, "FLATTEN", why, epoch(entryTime), Double.NaN);
            releaseLatch(signal, "no-entry block");
            return;
        }

        // Evaluate against risk limits
        RiskDecision decision = riskEngine.evaluate(signal, accountState, riskLimits);

        if (decision.isAllowed()) {
            System.out.println("\n✓ Signal APPROVED: " + signal.getReason());
            System.out.println("  " + decision.getReason());

            // Submit order to execution engine. The signal's candle time
            // lets the engine replay a candle that raced ahead of this
            // (async) handler, so the fill is never lost (RC-17).
            Order order = decision.getOrder();
            executionEngine.submitOrder(order, signal.getStopPrice(), signal.getTargetPrice(),
                    signal.getCandleTime());
            publishSignalDecision(signal, "APPROVED: " + decision.getReason());

            // Record signal context for trade journal enrichment
            List<String> confluenceFactors = parseConfluenceFromReason(signal.getReason());
            executionEngine.recordSignalContext(signal.getSymbol(), signal.getTier(), confluenceFactors);

            // Print account status
            printAccountStatus();

        } else {
            System.out.println("\n❌ Signal DENIED: " + signal.getReason());
            System.out.println("  Reason: " + decision.getReason());
            // PropFirmRiskEngine already published the RISK/SIZE GateDecisionEvent
            // (with its two numbers) — release the latch without a duplicate row.
            releaseLatch(signal, "risk engine deny: " + decision.getReason());
        }
    }

    // ── V5 Agent 01: wiring assertions + runtime gate telemetry ─────────

    /**
     * Throws when a handler the candle path relies on is missing or the bus
     * is not running — a candle delivered before this is D-14 (signals
     * silently dropped by EventBus.publish while !running).
     */
    void assertWiredForCandles() {
        if (eventBus.handlerCount(StrategySignalEvent.class) == 0) {
            throw new IllegalStateException("SIM wiring: no StrategySignalEvent handler subscribed before the first candle");
        }
        if (!eventBus.isRunning()) {
            throw new IllegalStateException("SIM wiring: EventBus not running before the first candle");
        }
    }

    /** Records (once) whether the wiring was complete when the first candle arrived. */
    private void verifyWiringAtFirstCandle(Candle candle) {
        if (!firstCandleSeen.compareAndSet(false, true)) return;
        boolean ok = eventBus.handlerCount(StrategySignalEvent.class) > 0 && eventBus.isRunning();
        wiredBeforeFirstCandle = ok;
        if (!ok) {
            com.topstep.trading.event.EngineTelemetry.error("SimEngineRunner.wiring",
                    "first candle " + candle.getSymbol() + " @ " + candle.getTimestamp()
                            + " arrived before the signal handler / EventBus were ready");
        }
    }

    /** True when the first candle found every handler subscribed and the bus running (test hook). */
    boolean wasWiredBeforeFirstCandle() {
        return wiredBeforeFirstCandle;
    }

    /** True once the first candle has been seen (test hook). */
    boolean hasSeenFirstCandle() {
        return firstCandleSeen.get();
    }

    /** The runner's bus (tests). */
    EventBus getEventBus() {
        return eventBus;
    }

    /**
     * One GateDecisionEvent per setup TRANSITION (state or holding gate
     * changed) for a candle whose symbol has a live setup: which gate holds
     * it now. numberA = candle close, numberB = setup raid score.
     */
    private void publishSetupDecision(Candle candle) {
        try {
            var s = com.topstep.trading.strategy.stdvote.StdvOteRegistry.get(candle.getSymbol());
            if (s.isEmpty()) return;
            var ctx = s.get().getSetupContext();
            String state = ctx.state == null ? "IDLE" : ctx.state.name();
            String gate = ctx.lastGateFailed;
            if ("IDLE".equals(state) && gate == null) {
                lastSetupDecision.remove(candle.getSymbol());
                return;
            }
            String keyNow = state + "|" + gate;
            if (keyNow.equals(lastSetupDecision.put(candle.getSymbol(), keyNow))) return;
            com.topstep.trading.event.EngineTelemetry.publish(eventBus,
                    new com.topstep.trading.event.GateDecisionEvent(candle.getSymbol(),
                            candle.getTimestamp(),
                            com.topstep.trading.event.EngineTelemetry.sessionOf(candle.getTimestamp()),
                            state, gate == null ? "SETUP-" + state : "SETUP",
                            gate == null ? "setup progressing (no gate failed)" : gate,
                            candle.getClose(), ctx.raidScore));
        } catch (RuntimeException e) {
            com.topstep.trading.event.EngineTelemetry.error("SimEngineRunner.publishSetupDecision", e);
        }
    }

    /**
     * One GateDecisionEvent per signal outcome (received / warmup drop /
     * pause / risk deny / approved). numberA = signal R:R, numberB = signal
     * quantity; the reason carries the deciding component's text.
     */
    private void publishSignalDecision(StrategySignalEvent signal, String why) {
        String w = why == null ? "" : why;
        String lw = w.toLowerCase(java.util.Locale.ROOT);
        String gate = lw.startsWith("signal") ? "SIGNAL"
                : lw.startsWith("approved") ? "RISK-APPROVED"
                : lw.contains("warm") || lw.contains("stale") ? "WARMUP"
                : lw.contains("pause") ? "PAUSED"
                : "RISK";
        // AGENT-05 (RC-15): candle time when the signal carries one (D-01 fixed).
        java.time.Instant t = signal.getCandleTime() != null ? signal.getCandleTime() : signal.getTimestamp();
        com.topstep.trading.event.EngineTelemetry.publish(eventBus,
                new com.topstep.trading.event.GateDecisionEvent(signal.getSymbol(), t,
                        com.topstep.trading.event.EngineTelemetry.sessionOf(t),
                        "SIGNAL", gate, w, signal.getRiskRewardRatio(), signal.getQuantity()));
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

        // Check if profit target met — SIM default: keep trading
        // (risk.haltOnProfitTarget, LIVE true / SIM false). DLL/MLL above
        // ALWAYS stop the engine.
        if (com.topstep.trading.risk.RiskConfig.haltOnProfitTarget(false)
                && riskEngine.hasMetProfitTarget(accountState, riskLimits)) {
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

    private void publishGate(StrategySignalEvent signal, String gate, String reason, double a, double b) {
        java.time.Instant t = signal.getCandleTime() != null ? signal.getCandleTime() : signal.getTimestamp();
        com.topstep.trading.event.EngineTelemetry.publish(eventBus, new com.topstep.trading.event.GateDecisionEvent(
                signal.getSymbol(), t, com.topstep.trading.event.EngineTelemetry.sessionOf(t),
                "SIGNAL", gate, reason, a, b));
    }

    /**
     * AGENT-05: release the strategy latch when the denying gate already
     * published its own GateDecisionEvent (with numbers) — no duplicate row.
     */
    private void releaseLatch(StrategySignalEvent signal, String why) {
        System.out.println("[SignalRelease] SIM " + signal.getSymbol() + ": " + why
                + " — releasing strategy latch (no order/position created)");
        eventBus.publish(new com.topstep.trading.event.PositionClosedEvent(
                signal.getSymbol(), 0.0, false, java.time.Instant.now()));
    }

    private static double epoch(java.time.Instant t) {
        return t == null ? Double.NaN : t.getEpochSecond();
    }

    /** Signals dropped by the warmup guard (AGENT-01: expose on /api/status). */
    public long getWarmupDroppedSignals() {
        return warmupDrops.get();
    }

    /** True once every required feed is live (or the warmup timeout fired). */
    public boolean isWarmupComplete() {
        WarmupGuard.Tracker w = warmup;
        return w != null && w.poll();
    }

    /** Test hook: the runner's bus (synthetic-signal integration test). */
    EventBus getEventBusForTest() {
        return eventBus;
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
