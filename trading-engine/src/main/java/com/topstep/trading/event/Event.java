package com.topstep.trading.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Base interface for all events in the trading system.
 */
public interface Event {
    /**
     * Unique identifier for this event.
     */
    String getEventId();

    /**
     * Timestamp when this event was created.
     */
    Instant getTimestamp();

    /**
     * Type of event for filtering and routing.
     */
    EventType getType();
}
