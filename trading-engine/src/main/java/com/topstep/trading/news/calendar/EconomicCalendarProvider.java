package com.topstep.trading.news.calendar;

import com.topstep.trading.news.model.EconomicEvent;
import com.topstep.trading.news.model.EconomicRelease;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Interface for economic calendar data providers.
 * Implementations can pull from various APIs (Trading Economics, Forex Factory, etc.)
 * or from mock data for backtesting.
 */
public interface EconomicCalendarProvider {

    /**
     * Get upcoming events within a time range.
     *
     * @param from Start of the time range (inclusive)
     * @param to End of the time range (exclusive)
     * @return List of economic events scheduled within the range
     */
    List<EconomicEvent> getUpcomingEvents(Instant from, Instant to);

    /**
     * Get upcoming events for the next N hours.
     *
     * @param hoursAhead Number of hours to look ahead
     * @return List of economic events scheduled within the hours
     */
    default List<EconomicEvent> getUpcomingEvents(int hoursAhead) {
        Instant now = Instant.now();
        return getUpcomingEvents(now, now.plus(Duration.ofHours(hoursAhead)));
    }

    /**
     * Get the release data for an event (if available).
     *
     * @param eventId Unique identifier of the event
     * @return The release data if available
     */
    Optional<EconomicRelease> getRelease(String eventId);

    /**
     * Subscribe to real-time release notifications (for live trading).
     * The callback will be invoked when a new release is detected.
     *
     * @param callback Consumer to receive release notifications
     */
    void subscribeToReleases(Consumer<EconomicRelease> callback);

    /**
     * Unsubscribe from release notifications.
     *
     * @param callback The callback to remove
     */
    void unsubscribeFromReleases(Consumer<EconomicRelease> callback);

    /**
     * Refresh the calendar data from the source.
     * Called periodically to update the cached events.
     */
    void refresh();

    /**
     * Check if the provider is connected and healthy.
     *
     * @return true if the provider is operational
     */
    boolean isHealthy();

    /**
     * Get the name of this provider for logging/display.
     *
     * @return Provider name
     */
    String getProviderName();

    /**
     * Get the last time the calendar was refreshed.
     *
     * @return Timestamp of last refresh, or empty if never refreshed
     */
    Optional<Instant> getLastRefreshTime();
}
