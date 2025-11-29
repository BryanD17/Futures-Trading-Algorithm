package com.topstep.trading.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Abstract base class for events.
 */
public abstract class BaseEvent implements Event {
    private final String eventId;
    private final Instant timestamp;
    private final EventType type;

    protected BaseEvent(EventType type) {
        this.eventId = UUID.randomUUID().toString();
        this.timestamp = Instant.now();
        this.type = type;
    }

    @Override
    public String getEventId() {
        return eventId;
    }

    @Override
    public Instant getTimestamp() {
        return timestamp;
    }

    @Override
    public EventType getType() {
        return type;
    }

    @Override
    public String toString() {
        return String.format("%s{id='%s', timestamp=%s}",
                getClass().getSimpleName(), eventId, timestamp);
    }
}
