package com.topstep.trading.news.events;

import com.topstep.trading.event.BaseEvent;
import com.topstep.trading.event.EventType;
import com.topstep.trading.news.model.EconomicEvent;

import java.util.Objects;

/**
 * Published when a high-impact economic event is approaching.
 * Subscribers can use this to adjust trading behavior or notify users.
 */
public class UpcomingEventWarning extends BaseEvent {
    private final EconomicEvent event;
    private final long minutesUntilEvent;

    public UpcomingEventWarning(EconomicEvent event, long minutesUntilEvent) {
        super(EventType.UPCOMING_NEWS_EVENT);
        this.event = Objects.requireNonNull(event, "event cannot be null");
        this.minutesUntilEvent = minutesUntilEvent;
    }

    /**
     * Get the upcoming economic event.
     */
    public EconomicEvent getEvent() {
        return event;
    }

    /**
     * Get the minutes until the event is scheduled.
     */
    public long getMinutesUntilEvent() {
        return minutesUntilEvent;
    }

    /**
     * Check if the event is imminent (less than 5 minutes).
     */
    public boolean isImminent() {
        return minutesUntilEvent <= 5;
    }

    /**
     * Get a human-readable warning message.
     */
    public String getWarningMessage() {
        String timeStr = minutesUntilEvent == 1 ? "1 minute" : minutesUntilEvent + " minutes";
        return String.format("%s %s event in %s: %s",
                event.getImpact(),
                event.getCurrency(),
                timeStr,
                event.getName());
    }

    @Override
    public String toString() {
        return String.format("UpcomingEventWarning{event='%s', minutesUntil=%d}",
                event.getName(), minutesUntilEvent);
    }
}
