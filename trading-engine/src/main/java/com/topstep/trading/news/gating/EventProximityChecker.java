package com.topstep.trading.news.gating;

import com.topstep.trading.news.MacroNewsConfig;
import com.topstep.trading.news.calendar.EconomicCalendarProvider;
import com.topstep.trading.news.impact.InstrumentNewsMapper;
import com.topstep.trading.news.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Checks if trades should be gated based on proximity to high-impact events.
 * This is the PRIMARY risk control for news events.
 */
public class EventProximityChecker {

    private static final Logger log = LoggerFactory.getLogger(EventProximityChecker.class);

    private final EconomicCalendarProvider calendarProvider;
    private final InstrumentNewsMapper mapper;
    private final MacroNewsConfig config;

    // Track which events we've already processed releases for
    private final Set<String> processedReleases = ConcurrentHashMap.newKeySet();

    public EventProximityChecker(EconomicCalendarProvider calendarProvider,
                                  InstrumentNewsMapper mapper,
                                  MacroNewsConfig config) {
        this.calendarProvider = Objects.requireNonNull(calendarProvider, "calendarProvider cannot be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper cannot be null");
        this.config = Objects.requireNonNull(config, "config cannot be null");
    }

    /**
     * Check if we should gate trades for an instrument right now.
     *
     * @param instrument The instrument to check (ES, NQ, GC, CL, 6E, 6J)
     * @param now Current time
     * @return Gating decision with action, size multiplier, and reason
     */
    public TradeGatingDecision checkGating(String instrument, Instant now) {
        // Get events in the relevant window (look ahead + look back)
        Duration maxPreBuffer = maxDuration(config.getHighImpactPreBuffer(), config.getMediumImpactPreBuffer());
        Duration maxPostBuffer = maxDuration(config.getHighImpactPostBuffer(), config.getMediumImpactPostBuffer());

        Instant lookBack = now.minus(maxPostBuffer);
        Instant lookAhead = now.plus(maxPreBuffer.multipliedBy(2));

        List<EconomicEvent> events = calendarProvider.getUpcomingEvents(lookBack, lookAhead);

        if (events.isEmpty()) {
            log.debug("No events in window for {}, allowing trade", instrument);
            return TradeGatingDecision.allow();
        }

        for (EconomicEvent event : events) {
            double relevance = mapper.getRelevance(event, instrument);
            if (relevance < config.getMinRelevanceForGating()) {
                log.debug("Event {} relevance {} below threshold for {}, skipping",
                        event.getName(), relevance, instrument);
                continue;  // Not relevant enough to gate
            }

            Duration timeToEvent = Duration.between(now, event.getScheduledTime());
            TradeGatingDecision decision = evaluateEventProximity(event, timeToEvent, relevance);

            if (decision.getAction() != GatingAction.ALLOW) {
                log.debug("Gating decision for {} due to {}: {}",
                        instrument, event.getName(), decision);
                return decision;  // Return first blocking/reducing decision
            }
        }

        return TradeGatingDecision.allow();
    }

    /**
     * Convenience method using current time.
     */
    public TradeGatingDecision checkGating(String instrument) {
        return checkGating(instrument, Instant.now());
    }

    /**
     * Evaluate a single event's impact on trading.
     */
    private TradeGatingDecision evaluateEventProximity(EconomicEvent event,
                                                        Duration timeToEvent,
                                                        double relevance) {
        boolean isUpcoming = !timeToEvent.isNegative();
        long minutesToEvent = isUpcoming ? timeToEvent.toMinutes() : -timeToEvent.toMinutes();

        if (event.getImpact() == EventImpact.HIGH) {
            return evaluateHighImpactEvent(event, isUpcoming, minutesToEvent, relevance);
        } else if (event.getImpact() == EventImpact.MEDIUM) {
            return evaluateMediumImpactEvent(event, isUpcoming, minutesToEvent, relevance);
        }

        // LOW impact events don't gate
        return TradeGatingDecision.allow();
    }

    /**
     * HIGH impact events (Fed, CPI, NFP, ECB, BOJ):
     * - Block 5 minutes before
     * - Reduce size 15 minutes before
     * - Block 10 minutes after (until release processed)
     */
    private TradeGatingDecision evaluateHighImpactEvent(EconomicEvent event,
                                                         boolean isUpcoming,
                                                         long minutes,
                                                         double relevance) {
        long preBufferMinutes = config.getHighImpactPreBuffer().toMinutes();
        long postBufferMinutes = config.getHighImpactPostBuffer().toMinutes();

        if (isUpcoming) {
            // PRE-EVENT
            if (minutes <= preBufferMinutes) {
                return TradeGatingDecision.block(
                    String.format("HIGH impact event in %d min: %s", minutes, event.getName()),
                    event
                );
            }
            // Extended pre-buffer for reduced size (3x the block buffer)
            if (minutes <= preBufferMinutes * 3) {
                double multiplier = config.getHighImpactPostEventSizeMultiplier() * relevance;
                return TradeGatingDecision.reduceSize(
                    multiplier,
                    String.format("HIGH impact event approaching (%d min): %s", minutes, event.getName()),
                    event
                );
            }
        } else {
            // POST-EVENT
            if (minutes <= postBufferMinutes) {
                // Check if we've processed the release
                if (!processedReleases.contains(event.getId())) {
                    return TradeGatingDecision.block(
                        String.format("Waiting for %s release data (released %d min ago)",
                                      event.getName(), minutes),
                        event
                    );
                }
                // Even after processing, reduce size in immediate aftermath
                if (minutes <= preBufferMinutes) {
                    return TradeGatingDecision.reduceSize(
                        config.getHighImpactPostEventSizeMultiplier(),
                        String.format("Post-event volatility: %s (%d min ago)", event.getName(), minutes),
                        event
                    );
                }
            }
        }

        return TradeGatingDecision.allow();
    }

    /**
     * MEDIUM impact events (PMI, Retail Sales, Jobless Claims):
     * - Block 2 minutes before
     * - Reduce size 5 minutes before
     * - Reduce size 5 minutes after
     */
    private TradeGatingDecision evaluateMediumImpactEvent(EconomicEvent event,
                                                           boolean isUpcoming,
                                                           long minutes,
                                                           double relevance) {
        long preBufferMinutes = config.getMediumImpactPreBuffer().toMinutes();
        long postBufferMinutes = config.getMediumImpactPostBuffer().toMinutes();

        if (isUpcoming) {
            if (minutes <= preBufferMinutes) {
                return TradeGatingDecision.block(
                    String.format("MEDIUM impact event in %d min: %s", minutes, event.getName()),
                    event
                );
            }
            // Extended pre-buffer for reduced size (2.5x the block buffer)
            if (minutes <= Math.round(preBufferMinutes * 2.5)) {
                return TradeGatingDecision.reduceSize(
                    config.getMediumImpactSizeMultiplier(),
                    String.format("MEDIUM impact event approaching: %s", event.getName()),
                    event
                );
            }
        } else {
            if (minutes <= postBufferMinutes) {
                return TradeGatingDecision.reduceSize(
                    config.getMediumImpactSizeMultiplier(),
                    String.format("Post-event: %s (%d min ago)", event.getName(), minutes),
                    event
                );
            }
        }

        return TradeGatingDecision.allow();
    }

    /**
     * Mark a release as processed (called by NewsImpactModel after processing).
     */
    public void markReleaseProcessed(String eventId) {
        processedReleases.add(eventId);
        log.debug("Marked release as processed: {}", eventId);
    }

    /**
     * Check if a release has been processed.
     */
    public boolean isReleaseProcessed(String eventId) {
        return processedReleases.contains(eventId);
    }

    /**
     * Get upcoming events for an instrument (for dashboard display).
     *
     * @param instrument The instrument to check
     * @param hoursAhead Hours to look ahead
     * @return List of relevant events sorted by time
     */
    public List<EconomicEvent> getUpcomingEventsForInstrument(String instrument, int hoursAhead) {
        Instant now = Instant.now();
        List<EconomicEvent> events = calendarProvider.getUpcomingEvents(
            now, now.plus(Duration.ofHours(hoursAhead)));

        return events.stream()
            .filter(e -> mapper.getRelevance(e, instrument) >= 0.3)
            .sorted(Comparator.comparing(EconomicEvent::getScheduledTime))
            .collect(Collectors.toList());
    }

    /**
     * Get the next blocking event for an instrument.
     *
     * @param instrument The instrument to check
     * @return The next event that would block trading, if any
     */
    public Optional<EconomicEvent> getNextBlockingEvent(String instrument) {
        Instant now = Instant.now();
        // Look ahead for the maximum gating window
        Duration lookAhead = config.getHighImpactPreBuffer().multipliedBy(3);
        List<EconomicEvent> events = calendarProvider.getUpcomingEvents(now, now.plus(lookAhead));

        return events.stream()
            .filter(e -> mapper.getRelevance(e, instrument) >= config.getMinRelevanceForGating())
            .filter(e -> e.getImpact() == EventImpact.HIGH || e.getImpact() == EventImpact.MEDIUM)
            .min(Comparator.comparing(EconomicEvent::getScheduledTime));
    }

    /**
     * Get time until trading is unblocked for an instrument.
     *
     * @param instrument The instrument to check
     * @return Duration until trading is allowed, or empty if already allowed
     */
    public Optional<Duration> getTimeUntilUnblocked(String instrument) {
        TradeGatingDecision decision = checkGating(instrument);
        if (decision.getAction() == GatingAction.ALLOW) {
            return Optional.empty();
        }

        EconomicEvent event = decision.getTriggeringEvent();
        if (event == null) {
            return Optional.empty();
        }

        Instant now = Instant.now();
        Instant eventTime = event.getScheduledTime();

        if (now.isBefore(eventTime)) {
            // Pre-event: will unblock after post-event buffer
            Duration postBuffer = event.getImpact() == EventImpact.HIGH
                ? config.getHighImpactPostBuffer()
                : config.getMediumImpactPostBuffer();
            return Optional.of(Duration.between(now, eventTime.plus(postBuffer)));
        } else {
            // Post-event
            Duration postBuffer = event.getImpact() == EventImpact.HIGH
                ? config.getHighImpactPostBuffer()
                : config.getMediumImpactPostBuffer();
            Instant unblockTime = eventTime.plus(postBuffer);
            if (now.isBefore(unblockTime)) {
                return Optional.of(Duration.between(now, unblockTime));
            }
        }

        return Optional.empty();
    }

    /**
     * Clear all processed releases (useful for testing or reset).
     */
    public void clearProcessedReleases() {
        processedReleases.clear();
    }

    private Duration maxDuration(Duration a, Duration b) {
        return a.compareTo(b) > 0 ? a : b;
    }
}
