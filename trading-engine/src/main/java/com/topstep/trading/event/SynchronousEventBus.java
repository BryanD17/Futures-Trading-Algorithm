package com.topstep.trading.event;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Deterministic, single-threaded {@link EventBus}: {@code publish} dispatches
 * to every subscribed handler INLINE on the calling thread, in subscription
 * order, before returning.
 *
 * <p>Used by the SA5 A/B backtest harness ({@code AbBacktestComparison}) and
 * verification tooling: the stock bus hands events to worker threads, so a
 * strategy signal can be risk-checked and submitted many candles after the
 * candle that produced it — acceptable live, but it destroys reproducibility
 * (and realistic fills) in a candle-by-candle backtest. Production runners
 * keep the asynchronous bus; this class is opt-in for deterministic replay.
 */
public class SynchronousEventBus extends EventBus {

    private final Map<EventType, List<EventHandler<Event>>> syncHandlers =
            new ConcurrentHashMap<>();

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Event> void subscribe(EventType type, EventHandler<T> handler) {
        syncHandlers.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>())
                .add((EventHandler<Event>) handler);
    }

    @Override
    public void publish(Event event) {
        if (event == null) return;
        for (EventHandler<Event> handler : syncHandlers.getOrDefault(event.getType(), List.of())) {
            handler.handle(event);
        }
    }

    /** No processor thread — starting is a no-op. */
    @Override
    public void start() {
        // synchronous: nothing to start
    }

    /** No processor thread — stopping is a no-op. */
    @Override
    public void stop() {
        // synchronous: nothing to stop
    }
}
