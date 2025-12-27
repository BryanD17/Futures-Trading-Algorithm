package com.topstep.trading.engine;

import com.topstep.trading.connector.TradingConnector;
import com.topstep.trading.domain.Candle;
import com.topstep.trading.event.EventBus;
import com.topstep.trading.strategy.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Multi-Instrument Trading Engine with automatic session-based switching.
 *
 * This engine manages multiple instrument-specific strategies and automatically:
 * 1. Detects the current trading session (Sydney, Tokyo, London, NY)
 * 2. Activates/deactivates instruments based on their optimal sessions
 * 3. Manages market data subscriptions for active instruments
 * 4. Routes market data to the appropriate strategies
 *
 * Session-Instrument Mapping:
 * - SYDNEY: Minimal trading (low volatility)
 * - TOKYO: 6J (Yen)
 * - LONDON: GC (Gold), 6E (Euro), CL (Oil)
 * - NEW_YORK: NQ, ES
 * - LONDON_NY_OVERLAP: All of the above (highest volatility)
 */
public class MultiInstrumentEngine {

    private final TradingConnector connector;
    private final EventBus eventBus;
    private final StrategyContext strategyContext;
    private final SessionManager sessionManager;

    // Shared correlation tracker across all instruments for SMT divergence
    private final CorrelationTracker sharedCorrelationTracker;

    // Instrument profiles and their strategies
    private final Map<String, InstrumentProfile> profiles;
    private final Map<String, InstrumentSpecificStrategy> strategies;

    // Currently active instruments (subscribed to market data)
    private final Set<String> activeSymbols;
    private final Set<String> subscribedSymbols;

    // Session tracking
    private SessionManager.Session lastPrimarySession;
    private Instant lastSessionCheck;
    private static final long SESSION_CHECK_INTERVAL_MS = 60000; // Check every minute

    // Scheduler for periodic session checks
    private final ScheduledExecutorService sessionMonitor;
    private volatile boolean running = false;

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

        this.profiles = new ConcurrentHashMap<>();
        this.strategies = new ConcurrentHashMap<>();
        this.activeSymbols = ConcurrentHashMap.newKeySet();
        this.subscribedSymbols = ConcurrentHashMap.newKeySet();

        this.sessionMonitor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SessionMonitor");
            t.setDaemon(true);
            return t;
        });

        // Initialize all instrument profiles
        initializeProfiles();
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
            System.out.println("[MultiInstrument] Engine already running");
            return;
        }

        running = true;
        System.out.println("\n" + "=".repeat(60));
        System.out.println("MULTI-INSTRUMENT ENGINE STARTING");
        System.out.println("=".repeat(60));

        // Perform initial session check and activate instruments
        checkAndUpdateSession(Instant.now());

        // Schedule periodic session checks
        sessionMonitor.scheduleAtFixedRate(
            () -> {
                if (running) {
                    checkAndUpdateSession(Instant.now());
                }
            },
            SESSION_CHECK_INTERVAL_MS,
            SESSION_CHECK_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        );

        System.out.println("[MultiInstrument] Engine started - monitoring sessions for auto-switching");
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

        System.out.println("[MultiInstrument] Activating instrument: " + symbol + " (" + profile.getName() + ")");

        // Create strategy if not exists
        if (!strategies.containsKey(symbol)) {
            InstrumentSpecificStrategy strategy = new InstrumentSpecificStrategy(
                profile, eventBus, sharedCorrelationTracker
            );
            strategy.initialize();
            strategies.put(symbol, strategy);
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

        System.out.println("[MultiInstrument] Activated: " + symbol +
                          " | Strategy: " + strategies.get(symbol).getName());
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

        // Update strategy context time
        strategyContext.setCurrentTime(candle.getTimestamp());

        // Route to primary strategy if this is an active trading instrument
        InstrumentSpecificStrategy strategy = strategies.get(symbol);
        if (strategy != null && activeSymbols.contains(symbol)) {
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

        sb.append("\n").append("=".repeat(50)).append("\n");
        sb.append("MULTI-INSTRUMENT ENGINE STATUS\n");
        sb.append("=".repeat(50)).append("\n");

        sb.append("Session: ").append(sessionManager.getSessionInfo(now)).append("\n");
        sb.append("Volatility Multiplier: ").append(sessionManager.getVolatilityMultiplier(now)).append("x\n");

        sb.append("\nActive Instruments:\n");
        for (String symbol : activeSymbols) {
            InstrumentSpecificStrategy strategy = strategies.get(symbol);
            if (strategy != null) {
                InstrumentProfile profile = strategy.getProfile();
                sb.append("  - ").append(symbol).append(": ").append(profile.getName());
                TradeTier tier = strategy.getCurrentTier();
                if (tier != null) {
                    sb.append(" | Last Tier: ").append(tier);
                }
                sb.append("\n");
            } else {
                sb.append("  - ").append(symbol).append(": SMT Data Only\n");
            }
        }

        sb.append("\nSubscribed Symbols: ").append(subscribedSymbols).append("\n");

        return sb.toString();
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
