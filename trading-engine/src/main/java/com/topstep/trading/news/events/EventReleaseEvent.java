package com.topstep.trading.news.events;

import com.topstep.trading.event.BaseEvent;
import com.topstep.trading.event.EventType;
import com.topstep.trading.news.model.EconomicRelease;
import com.topstep.trading.news.model.NewsImpulse;

import java.util.Objects;

/**
 * Published when an economic release is processed.
 * Contains both the release data and the generated impulse.
 */
public class EventReleaseEvent extends BaseEvent {
    private final EconomicRelease release;
    private final NewsImpulse impulse;

    public EventReleaseEvent(EconomicRelease release, NewsImpulse impulse) {
        super(EventType.NEWS_RELEASE);
        this.release = Objects.requireNonNull(release, "release cannot be null");
        this.impulse = Objects.requireNonNull(impulse, "impulse cannot be null");
    }

    /**
     * Get the economic release data.
     */
    public EconomicRelease getRelease() {
        return release;
    }

    /**
     * Get the generated impulse.
     */
    public NewsImpulse getImpulse() {
        return impulse;
    }

    /**
     * Get the event name for convenience.
     */
    public String getEventName() {
        return release.getEvent().getName();
    }

    /**
     * Check if this was a significant surprise.
     */
    public boolean isSignificantSurprise() {
        return Math.abs(impulse.getMagnitude()) >= 0.3;
    }

    /**
     * Get a summary message.
     */
    public String getSummaryMessage() {
        return String.format("%s released: Actual=%s, Forecast=%s, Surprise=%s (Impulse: %.2f)",
                release.getEvent().getName(),
                release.getActual(),
                release.getEvent().getForecast(),
                release.getSurpriseDirection(),
                impulse.getMagnitude());
    }

    @Override
    public String toString() {
        return String.format("EventReleaseEvent{event='%s', surprise=%s, impulse=%.3f}",
                release.getEvent().getName(),
                release.getSurpriseDirection(),
                impulse.getMagnitude());
    }
}
