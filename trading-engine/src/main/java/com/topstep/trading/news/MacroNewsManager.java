package com.topstep.trading.news;

import com.topstep.trading.event.EventBus;
import com.topstep.trading.news.bias.MacroAlignmentScorer;
import com.topstep.trading.news.bias.NewsBiasBreakdown;
import com.topstep.trading.news.bias.NewsBiasModifier;
import com.topstep.trading.news.calendar.EconomicCalendarProvider;
import com.topstep.trading.news.events.EventReleaseEvent;
import com.topstep.trading.news.events.MacroBiasUpdateEvent;
import com.topstep.trading.news.events.UpcomingEventWarning;
import com.topstep.trading.news.gating.EventProximityChecker;
import com.topstep.trading.news.impact.CurrencyStrengthIndex;
import com.topstep.trading.news.impact.InstrumentNewsMapper;
import com.topstep.trading.news.impact.NewsImpactModel;
import com.topstep.trading.news.impact.SurpriseCalculator;
import com.topstep.trading.news.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.*;

/**
 * Main facade for the macro news system.
 * Strategy and other components interact through this class.
 *
 * This manager coordinates:
 * - Economic calendar data loading and refreshing
 * - Event proximity checking for trade gating
 * - Surprise calculation and impulse generation
 * - Bias modification for strategy confluence
 *
 * Thread-safe: All public methods can be called from any thread.
 */
public class MacroNewsManager {

    private static final Logger log = LoggerFactory.getLogger(MacroNewsManager.class);

    // Core components
    private final EconomicCalendarProvider calendarProvider;
    private final InstrumentNewsMapper mapper;
    private final EventProximityChecker proximityChecker;
    private final NewsImpactModel impactModel;
    private final NewsBiasModifier biasModifier;
    private final MacroAlignmentScorer alignmentScorer;
    private final CurrencyStrengthIndex strengthIndex;
    private final MacroNewsConfig config;
    private final EventBus eventBus;

    // Background scheduler
    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> refreshTask;
    private volatile boolean running = false;

    /**
     * Create a MacroNewsManager with all components.
     *
     * @param calendarProvider Provider for economic calendar data
     * @param config Configuration for the news system
     * @param eventBus EventBus for publishing events (can be null for standalone use)
     */
    public MacroNewsManager(EconomicCalendarProvider calendarProvider,
                            MacroNewsConfig config,
                            EventBus eventBus) {
        this.calendarProvider = Objects.requireNonNull(calendarProvider, "calendarProvider cannot be null");
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.eventBus = eventBus; // Can be null

        // Create internal components
        this.mapper = new InstrumentNewsMapper();
        this.proximityChecker = new EventProximityChecker(calendarProvider, mapper, config);

        SurpriseCalculator surpriseCalculator = new SurpriseCalculator(config);
        this.impactModel = new NewsImpactModel(surpriseCalculator, mapper, config, proximityChecker);
        this.biasModifier = new NewsBiasModifier(impactModel, mapper, config);
        this.alignmentScorer = new MacroAlignmentScorer(biasModifier, config);
        this.strengthIndex = new CurrencyStrengthIndex(impactModel);

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "MacroNewsManager");
            t.setDaemon(true);
            return t;
        });

        log.info("MacroNewsManager created with config: {}", config);
    }

    /**
     * Create a MacroNewsManager without EventBus.
     */
    public MacroNewsManager(EconomicCalendarProvider calendarProvider, MacroNewsConfig config) {
        this(calendarProvider, config, null);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // LIFECYCLE MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Start the news manager (calendar refresh, release subscription).
     */
    public void start() {
        if (running) {
            log.warn("MacroNewsManager is already running");
            return;
        }

        running = true;

        // Initial calendar refresh
        try {
            calendarProvider.refresh();
            log.info("Initial calendar refresh completed");
        } catch (Exception e) {
            log.error("Failed initial calendar refresh", e);
        }

        // Schedule periodic refresh
        long refreshMinutes = config.getCalendarRefreshInterval().toMinutes();
        refreshTask = scheduler.scheduleAtFixedRate(
            this::refreshAndCheckUpcoming,
            refreshMinutes,
            refreshMinutes,
            TimeUnit.MINUTES
        );

        // Subscribe to real-time releases
        calendarProvider.subscribeToReleases(this::onReleaseReceived);

        log.info("MacroNewsManager started (refresh every {} minutes)", refreshMinutes);
    }

    /**
     * Stop the news manager.
     */
    public void stop() {
        if (!running) {
            return;
        }

        running = false;

        if (refreshTask != null) {
            refreshTask.cancel(false);
        }

        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        log.info("MacroNewsManager stopped");
    }

    /**
     * Check if the manager is running.
     */
    public boolean isRunning() {
        return running;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PRIMARY API FOR STRATEGY
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Check if trades should be gated for an instrument.
     * CALL THIS BEFORE EVERY TRADE ENTRY.
     *
     * @param instrument The instrument to check
     * @return Gating decision with action, size multiplier, and reason
     */
    public TradeGatingDecision checkTradeGating(String instrument) {
        return checkTradeGating(instrument, Instant.now());
    }

    /**
     * Check trade gating at a specific time.
     */
    public TradeGatingDecision checkTradeGating(String instrument, Instant now) {
        if (!running) {
            log.debug("MacroNewsManager not running, allowing trade for {}", instrument);
            return TradeGatingDecision.allow();  // Fail open if not running
        }
        return proximityChecker.checkGating(instrument, now);
    }

    /**
     * Get macro alignment for confluence scoring.
     * CALL THIS TO ADJUST CONFLUENCE SCORE.
     *
     * @param instrument The trading instrument
     * @param technicalBullish True if technical analysis suggests bullish
     * @return Alignment status for confluence scoring
     */
    public MacroAlignment getMacroAlignment(String instrument, boolean technicalBullish) {
        return getMacroAlignment(instrument, technicalBullish, Instant.now());
    }

    /**
     * Get macro alignment at a specific time.
     */
    public MacroAlignment getMacroAlignment(String instrument, boolean technicalBullish, Instant now) {
        if (!running) {
            return MacroAlignment.NEUTRAL;  // Fail neutral if not running
        }
        return biasModifier.checkAlignment(instrument, technicalBullish, now);
    }

    /**
     * Get confluence score adjustment based on macro alignment.
     *
     * @param instrument The trading instrument
     * @param technicalBullish True if technical suggests bullish
     * @return Score adjustment (+1 for aligned, 0 for neutral, -1 for opposing)
     */
    public int getConfluenceAdjustment(String instrument, boolean technicalBullish) {
        if (!running) {
            return 0;
        }
        return alignmentScorer.getScoreAdjustment(instrument, technicalBullish);
    }

    /**
     * Check if trade should be blocked due to strong macro opposition.
     *
     * @param instrument The trading instrument
     * @param technicalBullish True if technical suggests bullish
     * @return true if trade should be blocked
     */
    public boolean shouldBlockOnOpposition(String instrument, boolean technicalBullish) {
        if (!running) {
            return false;
        }
        return biasModifier.shouldBlockOnOpposition(instrument, technicalBullish, Instant.now());
    }

    /**
     * Get current news bias modifier for an instrument.
     *
     * @param instrument The trading instrument
     * @return Bias value from -1.0 (bearish) to +1.0 (bullish)
     */
    public double getNewsBiasModifier(String instrument) {
        return getNewsBiasModifier(instrument, Instant.now());
    }

    /**
     * Get news bias at a specific time.
     */
    public double getNewsBiasModifier(String instrument, Instant now) {
        if (!running) {
            return 0.0;
        }
        return biasModifier.getBiasModifier(instrument, now);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // INFORMATIONAL API (for dashboard, logging)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Get upcoming events for an instrument.
     *
     * @param instrument The trading instrument
     * @param hoursAhead Hours to look ahead
     * @return List of relevant events
     */
    public List<EconomicEvent> getUpcomingEvents(String instrument, int hoursAhead) {
        return proximityChecker.getUpcomingEventsForInstrument(instrument, hoursAhead);
    }

    /**
     * Get all upcoming events.
     *
     * @param hoursAhead Hours to look ahead
     * @return List of all events
     */
    public List<EconomicEvent> getAllUpcomingEvents(int hoursAhead) {
        return calendarProvider.getUpcomingEvents(hoursAhead);
    }

    /**
     * Get detailed bias breakdown for an instrument.
     *
     * @param instrument The trading instrument
     * @return Breakdown with all contributing impulses
     */
    public NewsBiasBreakdown getBiasBreakdown(String instrument) {
        return biasModifier.getBiasBreakdown(instrument);
    }

    /**
     * Get currency strength index.
     *
     * @param currency The currency to check
     * @return Strength value from -1.0 to +1.0
     */
    public double getCurrencyStrength(Currency currency) {
        return strengthIndex.getStrength(currency);
    }

    /**
     * Get relative strength between two currencies.
     *
     * @param currency1 First currency
     * @param currency2 Second currency
     * @return Relative strength (positive = currency1 stronger)
     */
    public double getRelativeCurrencyStrength(Currency currency1, Currency currency2) {
        return strengthIndex.getRelativeStrength(currency1, currency2);
    }

    /**
     * Get time until trading is unblocked for an instrument.
     *
     * @param instrument The trading instrument
     * @return Duration until unblocked, or empty if already allowed
     */
    public Optional<Duration> getTimeUntilUnblocked(String instrument) {
        return proximityChecker.getTimeUntilUnblocked(instrument);
    }

    /**
     * Get the next blocking event for an instrument.
     *
     * @param instrument The trading instrument
     * @return The next event that would block trading, if any
     */
    public Optional<EconomicEvent> getNextBlockingEvent(String instrument) {
        return proximityChecker.getNextBlockingEvent(instrument);
    }

    /**
     * Check if the news system is healthy.
     */
    public boolean isHealthy() {
        return running && calendarProvider.isHealthy();
    }

    /**
     * Get the configuration.
     */
    public MacroNewsConfig getConfig() {
        return config;
    }

    /**
     * Get the instrument mapper.
     */
    public InstrumentNewsMapper getMapper() {
        return mapper;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // MANUAL OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Manually process a release (useful for backtesting).
     *
     * @param release The release to process
     * @return The generated impulse, if any
     */
    public Optional<NewsImpulse> processRelease(EconomicRelease release) {
        return impactModel.processRelease(release);
    }

    /**
     * Force a calendar refresh.
     */
    public void forceRefresh() {
        calendarProvider.refresh();
    }

    /**
     * Clear all active impulses (useful for testing or reset).
     */
    public void clearImpulses() {
        impactModel.clearAllImpulses();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // INTERNAL METHODS
    // ═══════════════════════════════════════════════════════════════════════

    private void refreshAndCheckUpcoming() {
        try {
            calendarProvider.refresh();

            if (eventBus == null) {
                return;
            }

            // Check for events in next 30 minutes and emit warnings
            List<EconomicEvent> upcoming = calendarProvider.getUpcomingEvents(
                Instant.now(), Instant.now().plus(Duration.ofMinutes(30)));

            for (EconomicEvent event : upcoming) {
                if (event.getImpact() == EventImpact.HIGH) {
                    Duration timeToEvent = Duration.between(Instant.now(), event.getScheduledTime());
                    long minutes = timeToEvent.toMinutes();
                    if (minutes <= 15 && minutes > 0) {
                        eventBus.publish(new UpcomingEventWarning(event, minutes));
                        log.info("Published warning for upcoming event: {} in {} min",
                                event.getName(), minutes);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error refreshing calendar", e);
        }
    }

    private void onReleaseReceived(EconomicRelease release) {
        try {
            Optional<NewsImpulse> impulse = impactModel.processRelease(release);

            if (impulse.isPresent() && eventBus != null) {
                eventBus.publish(new EventReleaseEvent(release, impulse.get()));

                // Emit bias update events for affected instruments
                for (String instrument : mapper.getAffectedInstruments(release.getEvent())) {
                    double newBias = biasModifier.getBiasModifier(instrument, Instant.now());
                    eventBus.publish(new MacroBiasUpdateEvent(instrument, newBias));
                }

                log.info("Processed release: {} | Surprise: {} | Impulse: {:.3f}",
                         release.getEvent().getName(),
                         release.getSurpriseDirection(),
                         impulse.get().getMagnitude());
            }
        } catch (Exception e) {
            log.error("Error processing release: {}", release.getEvent().getName(), e);
        }
    }
}
