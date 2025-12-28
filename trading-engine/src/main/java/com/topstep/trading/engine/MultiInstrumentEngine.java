package com.topstep.trading.engine;

import com.topstep.trading.connector.TradingConnector;
import com.topstep.trading.domain.Candle;
import com.topstep.trading.domain.OrderSide;
import com.topstep.trading.event.EventBus;
import com.topstep.trading.event.StrategySignalEvent;
import com.topstep.trading.risk.MarketConditionFilter;
import com.topstep.trading.risk.RiskDecision;
import com.topstep.trading.risk.TradingRiskManager;
import com.topstep.trading.strategy.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Multi-Instrument Trading Engine with HYBRID SIMULTANEOUS TRADING.
 *
 * ENHANCED MODEL: Instead of session-based switching, this engine now supports
 * simultaneous trading across ALL instruments with intelligent safeguards.
 *
 * Key Features:
 * 1. ALL INSTRUMENTS ALWAYS ACTIVE - No longer switching between instruments
 * 2. Session-based TIER BONUS - Trading in preferred session upgrades tier
 * 3. POSITION LIMITS - Max 1 per instrument, max 3 total
 * 4. CORRELATION PROTECTION - No conflicting positions (e.g., long NQ + short ES)
 * 5. CONSECUTIVE LOSS TRACKING - Stop instrument after 2 consecutive losses
 * 6. MARKET CONDITION FILTERING - Skip trades in unfavorable conditions
 *
 * This hybrid model INCREASES weekly trades while MAINTAINING win percentage.
 */
public class MultiInstrumentEngine {

    private final TradingConnector connector;
    private final EventBus eventBus;
    private final StrategyContext strategyContext;
    private final SessionManager sessionManager;

    // Shared correlation tracker across all instruments for SMT divergence
    private final CorrelationTracker sharedCorrelationTracker;

    // Risk management
    private final TradingRiskManager riskManager;
    private final MarketConditionFilter conditionFilter;

    // Instrument profiles and their strategies
    private final Map<String, InstrumentProfile> profiles;
    private final Map<String, InstrumentSpecificStrategy> strategies;

    // Active instruments - NOW ALL INSTRUMENTS ARE ALWAYS ACTIVE
    private final Set<String> activeSymbols;
    private final Set<String> subscribedSymbols;

    // ATR calculators per instrument for condition filtering
    private final Map<String, ATRCalculator> atrCalculators;

    // Session tracking (for tier bonus, not for switching)
    private SessionManager.Session lastPrimarySession;
    private Instant lastSessionCheck;
    private static final long SESSION_CHECK_INTERVAL_MS = 60000; // Check every minute

    // Scheduler for periodic session checks
    private final ScheduledExecutorService sessionMonitor;
    private volatile boolean running = false;

    // Mode flag - true for hybrid simultaneous, false for session-switching
    private boolean hybridMode = true;

    // Callback for subscription changes
    private SubscriptionCallback subscriptionCallback;

    /**
     * Callback interface for subscription changes.
     */
    public interface SubscriptionCallback {
        void onSubscribe(String symbol);
        void onUnsubscribe(String symbol);
    }

    public MultiInstrumentEngine(TradingConnector connector, EventBus eventBus, StrategyContext context) {
        this.connector = connector;
        this.eventBus = eventBus;
        this.strategyContext = context;
        this.sessionManager = new SessionManager();
        this.sharedCorrelationTracker = new CorrelationTracker(50);

        // Initialize risk management components
        this.riskManager = new TradingRiskManager(context);
        this.conditionFilter = new MarketConditionFilter();

        this.profiles = new ConcurrentHashMap<>();
        this.strategies = new ConcurrentHashMap<>();
        this.activeSymbols = ConcurrentHashMap.newKeySet();
        this.subscribedSymbols = ConcurrentHashMap.newKeySet();
        this.atrCalculators = new ConcurrentHashMap<>();

        this.sessionMonitor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SessionMonitor");
            t.setDaemon(true);
            return t;
        });

        // Initialize all instrument profiles
        initializeProfiles();

        // Subscribe to strategy signals for risk management
        setupSignalInterception();
    }

    /**
     * Setup signal interception for risk management.
     * Intercepts signals before they reach execution engine.
     */
    private void setupSignalInterception() {
        // Register as a handler for strategy signals to apply risk checks
        eventBus.subscribe(StrategySignalEvent.class, this::handleStrategySignal);
    }

    /**
     * Handle strategy signal with risk checks.
     * NOTE: This is for logging/monitoring only. Position tracking happens in the Runner
     * after the order is actually filled to avoid race conditions.
     */
    private void handleStrategySignal(StrategySignalEvent signal) {
        String symbol = signal.getSymbol();

        // Log signal reception (for monitoring)
        System.out.println("\n[HYBRID ENGINE] Signal received: " + signal.getSignalType() +
                " " + symbol + " " + signal.getSide());

        // Log market conditions (informational only - actual validation in Runner)
        InstrumentProfile profile = profiles.get(symbol);
        ATRCalculator atr = atrCalculators.get(symbol);
        if (profile != null && atr != null) {
            MarketConditionFilter.MarketCondition condition =
                    conditionFilter.evaluate(strategyContext.getCurrentTime(), symbol, atr, profile);

            System.out.println("[HYBRID ENGINE] Market condition: " + condition.getRecommendation() +
                    " (score: " + condition.getTotalScore() + ")");
        }

        // Log session status
        boolean isPreferredSession = isInstrumentInPreferredSession(symbol);
        System.out.println("[HYBRID ENGINE] Preferred Session: " + (isPreferredSession ? "YES (tier bonus)" : "NO"));

        // NOTE: Do NOT record position here - let the Runner do it after order fills
        // to avoid race condition with PropFirmRiskEngine validation
    }

    /**
     * Validate a signal using TradingRiskManager.
     * Called by Runners BEFORE PropFirmRiskEngine evaluation.
     */
    public RiskDecision validateSignalWithRiskManager(StrategySignalEvent signal) {
        return riskManager.validateSignal(signal);
    }

    /**
     * Check market conditions for a signal.
     * Returns null if conditions are acceptable, or a rejection reason if not.
     */
    public String checkMarketConditions(StrategySignalEvent signal) {
        String symbol = signal.getSymbol();
        InstrumentProfile profile = profiles.get(symbol);
        ATRCalculator atr = atrCalculators.get(symbol);

        if (profile == null) {
            return null; // No profile means no filtering
        }

        MarketConditionFilter.MarketCondition condition =
                conditionFilter.evaluate(strategyContext.getCurrentTime(), symbol, atr, profile);

        if (!condition.shouldTrade()) {
            return "Market conditions unfavorable: " + condition.getRecommendation();
        }

        return null; // Conditions OK
    }

    /**
     * Get the adjusted tier with session bonus applied.
     */
    public TradeTier getAdjustedTier(String symbol, TradeTier baseTier) {
        boolean isPreferredSession = isInstrumentInPreferredSession(symbol);
        return riskManager.applySessionBonus(symbol, baseTier, isPreferredSession);
    }

    /**
     * Initialize all supported instrument profiles.
     */
    private void initializeProfiles() {
        for (InstrumentProfile profile : InstrumentCharacteristics.getAllProfiles()) {
            profiles.put(profile.getSymbol(), profile);
        }
        System.out.println("[MultiInstrument] Loaded " + profiles.size() + " instrument profiles:");
        profiles.forEach((symbol, profile) -> {
            System.out.println("  - " + symbol + ": " + profile.getName() +
                              " | Primary Sessions: " + profile.getPrimaryKillzones());
        });
    }

    /**
     * Set the callback for subscription changes.
     */
    public void setSubscriptionCallback(SubscriptionCallback callback) {
        this.subscriptionCallback = callback;
    }

    /**
     * Start the multi-instrument engine.
     */
    public void start() {
        if (running) {
            System.out.println("[HYBRID ENGINE] Engine already running");
            return;
        }

        running = true;
        System.out.println("\n" + "=".repeat(60));
        System.out.println("HYBRID SIMULTANEOUS TRADING ENGINE STARTING");
        System.out.println("=".repeat(60));

        if (hybridMode) {
            // HYBRID MODE: Activate ALL instruments at once
            System.out.println("[HYBRID ENGINE] Mode: SIMULTANEOUS TRADING");
            System.out.println("[HYBRID ENGINE] All instruments always active");
            System.out.println("[HYBRID ENGINE] Session-based tier bonuses enabled");
            activateAllInstruments();
        } else {
            // Legacy mode: Session-based switching
            System.out.println("[HYBRID ENGINE] Mode: SESSION-BASED SWITCHING");
            checkAndUpdateSession(Instant.now());
        }

        // Schedule periodic session checks (for logging/tier bonus, not switching)
        sessionMonitor.scheduleAtFixedRate(
            () -> {
                if (running) {
                    if (hybridMode) {
                        logSessionStatus(Instant.now());
                    } else {
                        checkAndUpdateSession(Instant.now());
                    }
                }
            },
            SESSION_CHECK_INTERVAL_MS,
            SESSION_CHECK_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        );

        System.out.println("\n[HYBRID ENGINE] Safeguards enabled:");
        System.out.println("  - Max 1 position per instrument");
        System.out.println("  - Max 3 total positions");
        System.out.println("  - Correlation conflict prevention");
        System.out.println("  - Consecutive loss protection (2 max)");
        System.out.println("  - Market condition filtering");
        System.out.println("  - Session-based tier bonus");

        System.out.println("\n[HYBRID ENGINE] Engine started successfully");
    }

    /**
     * Activate ALL instruments for hybrid simultaneous trading.
     */
    private void activateAllInstruments() {
        System.out.println("\n[HYBRID ENGINE] Activating all instruments:");

        for (InstrumentProfile profile : profiles.values()) {
            activateInstrument(profile.getSymbol());
        }

        System.out.println("\n[HYBRID ENGINE] Active instruments: " + activeSymbols);
        System.out.println("[HYBRID ENGINE] Total active: " + activeSymbols.size());
    }

    /**
     * Log current session status (for hybrid mode).
     */
    private void logSessionStatus(Instant now) {
        SessionManager.Session currentSession = sessionManager.getPrimarySession(now);

        // Only log on session change
        if (currentSession != lastPrimarySession) {
            String overlapType = sessionManager.getOverlapType(now);
            System.out.println("\n[HYBRID ENGINE] Session update: " +
                    (lastPrimarySession != null ? lastPrimarySession.getName() : "NONE") +
                    " -> " + currentSession.getName());

            if (!"SINGLE_SESSION".equals(overlapType)) {
                System.out.println("[HYBRID ENGINE] Overlap: " + overlapType + " (tier bonus active for overlapping instruments)");
            }

            // Log which instruments get tier bonus in this session
            StringBuilder preferredInstruments = new StringBuilder();
            for (String symbol : activeSymbols) {
                if (isInstrumentInPreferredSession(symbol)) {
                    if (preferredInstruments.length() > 0) preferredInstruments.append(", ");
                    preferredInstruments.append(symbol);
                }
            }
            if (preferredInstruments.length() > 0) {
                System.out.println("[HYBRID ENGINE] Tier bonus active for: " + preferredInstruments);
            }

            lastPrimarySession = currentSession;
        }
    }

    /**
     * Check if an instrument is in its preferred session.
     */
    private boolean isInstrumentInPreferredSession(String symbol) {
        InstrumentProfile profile = profiles.get(symbol);
        if (profile == null) return false;
        return isInstrumentPreferredNow(profile, Instant.now());
    }

    /**
     * Set hybrid mode on/off.
     */
    public void setHybridMode(boolean hybrid) {
        this.hybridMode = hybrid;
        System.out.println("[HYBRID ENGINE] Mode set to: " + (hybrid ? "SIMULTANEOUS" : "SESSION-SWITCHING"));
    }

    /**
     * Check if hybrid mode is enabled.
     */
    public boolean isHybridMode() {
        return hybridMode;
    }

    /**
     * Stop the multi-instrument engine.
     */
    public void stop() {
        if (!running) {
            return;
        }

        running = false;
        System.out.println("\n[MultiInstrument] Stopping engine...");

        // Shutdown session monitor
        sessionMonitor.shutdown();
        try {
            sessionMonitor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Shutdown all strategies
        strategies.values().forEach(TradingStrategy::shutdown);

        // Unsubscribe from all market data
        for (String symbol : new ArrayList<>(subscribedSymbols)) {
            unsubscribeFromSymbol(symbol);
        }

        strategies.clear();
        activeSymbols.clear();

        System.out.println("[MultiInstrument] Engine stopped");
    }

    /**
     * Check current session and update active instruments accordingly.
     */
    private void checkAndUpdateSession(Instant now) {
        // Get current session info
        SessionManager.Session currentSession = sessionManager.getPrimarySession(now);
        List<InstrumentConfig> recommendedInstruments = sessionManager.getActiveInstruments(now);
        String overlapType = sessionManager.getOverlapType(now);

        // Check if session changed
        boolean sessionChanged = currentSession != lastPrimarySession;
        if (sessionChanged && currentSession != null) {
            System.out.println("\n[MultiInstrument] Session changed: " +
                              (lastPrimarySession != null ? lastPrimarySession.getName() : "NONE") +
                              " -> " + currentSession.getName());
            if (!"SINGLE_SESSION".equals(overlapType)) {
                System.out.println("[MultiInstrument] Overlap: " + overlapType);
            }
            lastPrimarySession = currentSession;
        }

        // Determine which instruments should be active
        Set<String> shouldBeActive = new HashSet<>();

        // Add instruments from session manager recommendations
        for (InstrumentConfig config : recommendedInstruments) {
            shouldBeActive.add(config.getSymbol());
            // Also add SMT pair if it's a tradeable instrument
            if (profiles.containsKey(config.getSmtSymbol())) {
                shouldBeActive.add(config.getSmtSymbol());
            }
        }

        // During overlaps, keep more instruments active
        if (sessionManager.isSessionOverlap(now)) {
            // Add all instruments that prefer the current sessions
            for (InstrumentProfile profile : profiles.values()) {
                if (isInstrumentPreferredNow(profile, now)) {
                    shouldBeActive.add(profile.getSymbol());
                    if (profile.getSmtSymbol() != null) {
                        shouldBeActive.add(profile.getSmtSymbol());
                    }
                }
            }
        }

        // Always keep NQ and ES subscribed during NY session (our primary instruments)
        if (currentSession == SessionManager.Session.NEW_YORK) {
            shouldBeActive.add("NQ");
            shouldBeActive.add("ES");
        }

        // If no instruments active, default to NQ
        if (shouldBeActive.isEmpty()) {
            shouldBeActive.add("NQ");
            shouldBeActive.add("ES");
        }

        // Activate new instruments
        for (String symbol : shouldBeActive) {
            if (!activeSymbols.contains(symbol)) {
                activateInstrument(symbol);
            }
        }

        // Deactivate instruments no longer needed (but keep subscriptions for a bit to avoid rapid switching)
        for (String symbol : new ArrayList<>(activeSymbols)) {
            if (!shouldBeActive.contains(symbol)) {
                // Only deactivate if it's been active for at least 5 minutes
                // This prevents rapid switching during session transitions
                deactivateInstrument(symbol);
            }
        }

        lastSessionCheck = now;
    }

    /**
     * Check if an instrument prefers the current session.
     */
    private boolean isInstrumentPreferredNow(InstrumentProfile profile, Instant now) {
        String killzoneName = new KillzoneClock().getKillzoneName(now);

        if (profile.getPrimaryKillzones() != null) {
            for (String kz : profile.getPrimaryKillzones()) {
                if (killzoneName.toUpperCase().contains(kz.toUpperCase())) {
                    return true;
                }
            }
        }
        if (profile.getSecondaryKillzones() != null) {
            for (String kz : profile.getSecondaryKillzones()) {
                if (killzoneName.toUpperCase().contains(kz.toUpperCase())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Activate an instrument - create strategy and subscribe to market data.
     */
    private void activateInstrument(String symbol) {
        InstrumentProfile profile = profiles.get(symbol);
        if (profile == null) {
            // Not a tradeable instrument, just subscribe for SMT data
            if (!subscribedSymbols.contains(symbol)) {
                subscribeToSymbol(symbol);
            }
            return;
        }

        System.out.println("[HYBRID ENGINE] Activating: " + symbol + " (" + profile.getName() + ")");

        // Create strategy if not exists
        if (!strategies.containsKey(symbol)) {
            InstrumentSpecificStrategy strategy = new InstrumentSpecificStrategy(
                profile, eventBus, sharedCorrelationTracker
            );
            strategy.initialize();
            strategies.put(symbol, strategy);
        }

        // Create ATR calculator for this instrument (for market condition filtering)
        if (!atrCalculators.containsKey(symbol)) {
            atrCalculators.put(symbol, new ATRCalculator(14));
        }

        // Subscribe to market data
        if (!subscribedSymbols.contains(symbol)) {
            subscribeToSymbol(symbol);
        }

        // Subscribe to SMT symbol if needed
        String smtSymbol = profile.getSmtSymbol();
        if (smtSymbol != null && !subscribedSymbols.contains(smtSymbol)) {
            subscribeToSymbol(smtSymbol);
        }

        activeSymbols.add(symbol);

        System.out.println("[HYBRID ENGINE] Activated: " + symbol +
                          " | Primary Sessions: " + profile.getPrimaryKillzones());
    }

    /**
     * Deactivate an instrument.
     */
    private void deactivateInstrument(String symbol) {
        if (!activeSymbols.contains(symbol)) {
            return;
        }

        System.out.println("[MultiInstrument] Deactivating instrument: " + symbol);

        // Reset the strategy's signal pending state
        InstrumentSpecificStrategy strategy = strategies.get(symbol);
        if (strategy != null) {
            strategy.resetSignalPending();
        }

        activeSymbols.remove(symbol);

        // Note: We don't immediately unsubscribe to avoid rapid switching
        // The subscription will be cleaned up if not needed for a while
    }

    /**
     * Subscribe to market data for a symbol.
     */
    private void subscribeToSymbol(String symbol) {
        if (subscribedSymbols.contains(symbol)) {
            return;
        }

        System.out.println("[MultiInstrument] Subscribing to market data: " + symbol);

        if (subscriptionCallback != null) {
            subscriptionCallback.onSubscribe(symbol);
        }

        subscribedSymbols.add(symbol);
    }

    /**
     * Unsubscribe from market data for a symbol.
     */
    private void unsubscribeFromSymbol(String symbol) {
        if (!subscribedSymbols.contains(symbol)) {
            return;
        }

        System.out.println("[MultiInstrument] Unsubscribing from market data: " + symbol);

        if (subscriptionCallback != null) {
            subscriptionCallback.onUnsubscribe(symbol);
        }

        subscribedSymbols.remove(symbol);
    }

    /**
     * Process incoming market data candle.
     * Routes to appropriate strategy based on symbol.
     */
    public void onMarketData(Candle candle) {
        if (!running) {
            return;
        }

        String symbol = candle.getSymbol();

        // Update shared correlation tracker for all candles
        sharedCorrelationTracker.update(candle);

        // Update ATR calculator for this symbol
        ATRCalculator atr = atrCalculators.get(symbol);
        if (atr != null) {
            atr.update(candle);
        }

        // Update strategy context time
        strategyContext.setCurrentTime(candle.getTimestamp());

        // Route to primary strategy if this is an active trading instrument
        InstrumentSpecificStrategy strategy = strategies.get(symbol);
        if (strategy != null && activeSymbols.contains(symbol)) {
            // Check if instrument is blocked by risk manager
            if (riskManager.isInstrumentBlocked(symbol)) {
                // Skip - instrument is blocked due to consecutive losses
                return;
            }
            strategy.onCandle(candle, strategyContext);
        }

        // Also send to other strategies that use this as SMT pair
        for (Map.Entry<String, InstrumentSpecificStrategy> entry : strategies.entrySet()) {
            if (!entry.getKey().equals(symbol) && activeSymbols.contains(entry.getKey())) {
                InstrumentProfile profile = entry.getValue().getProfile();
                if (symbol.equals(profile.getSmtSymbol())) {
                    entry.getValue().onCandle(candle, strategyContext);
                }
            }
        }
    }

    /**
     * Get currently active symbols (subscribed and trading).
     */
    public Set<String> getActiveSymbols() {
        return new HashSet<>(activeSymbols);
    }

    /**
     * Get all subscribed symbols.
     */
    public Set<String> getSubscribedSymbols() {
        return new HashSet<>(subscribedSymbols);
    }

    /**
     * Get the strategy for a specific symbol.
     */
    public InstrumentSpecificStrategy getStrategy(String symbol) {
        return strategies.get(symbol);
    }

    /**
     * Get current session info.
     */
    public String getSessionInfo() {
        Instant now = Instant.now();
        return sessionManager.getSessionInfo(now);
    }

    /**
     * Get detailed status of the engine.
     */
    public String getDetailedStatus() {
        Instant now = Instant.now();
        StringBuilder sb = new StringBuilder();

        sb.append("\n").append("=".repeat(60)).append("\n");
        sb.append("HYBRID SIMULTANEOUS TRADING ENGINE STATUS\n");
        sb.append("=".repeat(60)).append("\n");

        sb.append("Mode: ").append(hybridMode ? "SIMULTANEOUS" : "SESSION-SWITCHING").append("\n");
        sb.append("Session: ").append(sessionManager.getSessionInfo(now)).append("\n");
        sb.append("Volatility Multiplier: ").append(sessionManager.getVolatilityMultiplier(now)).append("x\n");

        sb.append("\nActive Instruments (").append(activeSymbols.size()).append("):\n");
        for (String symbol : activeSymbols) {
            InstrumentSpecificStrategy strategy = strategies.get(symbol);
            if (strategy != null) {
                InstrumentProfile profile = strategy.getProfile();
                boolean preferred = isInstrumentInPreferredSession(symbol);
                boolean blocked = riskManager.isInstrumentBlocked(symbol);

                sb.append("  ").append(symbol).append(": ").append(profile.getName());
                sb.append(" | Session Bonus: ").append(preferred ? "YES" : "NO");
                sb.append(" | Status: ").append(blocked ? "BLOCKED" : "ACTIVE");

                TradeTier tier = strategy.getCurrentTier();
                if (tier != null) {
                    sb.append(" | Last Tier: ").append(tier);
                }
                sb.append("\n");
            } else {
                sb.append("  ").append(symbol).append(": SMT Data Only\n");
            }
        }

        sb.append("\nSubscribed Symbols: ").append(subscribedSymbols).append("\n");

        // Add risk manager status
        sb.append(riskManager.getStatusSummary());

        return sb.toString();
    }

    /**
     * Notify the engine that a position was closed.
     * This updates the risk manager for consecutive loss/win tracking.
     */
    public void notifyPositionClosed(String symbol, double pnl) {
        boolean isWin = pnl > 0;
        riskManager.recordPositionClosed(symbol, pnl, isWin);
    }

    /**
     * Get the risk manager.
     */
    public TradingRiskManager getRiskManager() {
        return riskManager;
    }

    /**
     * Get the market condition filter.
     */
    public MarketConditionFilter getConditionFilter() {
        return conditionFilter;
    }

    /**
     * Force switch to specific instruments (manual override).
     */
    public void forceActivateInstruments(String... symbols) {
        System.out.println("[MultiInstrument] Force activating instruments: " + Arrays.toString(symbols));

        // Deactivate all current
        for (String symbol : new ArrayList<>(activeSymbols)) {
            deactivateInstrument(symbol);
        }

        // Activate requested
        for (String symbol : symbols) {
            activateInstrument(symbol);
        }
    }

    /**
     * Check if engine is running.
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Get the session manager.
     */
    public SessionManager getSessionManager() {
        return sessionManager;
    }

    /**
     * Get shared correlation tracker.
     */
    public CorrelationTracker getCorrelationTracker() {
        return sharedCorrelationTracker;
    }
}
