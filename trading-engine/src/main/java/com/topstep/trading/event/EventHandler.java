package com.topstep.trading.event;

/**
 * Functional interface for event handlers.
 */
@FunctionalInterface
public interface EventHandler<T extends Event> {
    /**
     * Handle an event.
     * @param event The event to handle
     */
    void handle(T event);
}
