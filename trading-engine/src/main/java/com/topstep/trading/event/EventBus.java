package com.topstep.trading.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Central event bus for the trading system.
 * Supports publish/subscribe pattern with async event processing.
 */
public class EventBus {
    private static final Logger logger = LoggerFactory.getLogger(EventBus.class);

    private final Map<EventType, List<EventHandler<? extends Event>>> handlers;
    private final ExecutorService executorService;
    private final BlockingQueue<Event> eventQueue;
    private final Thread processingThread;
    private volatile boolean running;
    private final AtomicLong eventsProcessed;
    private final AtomicLong droppedNotRunning = new AtomicLong();
    private final AtomicLong droppedQueueFull = new AtomicLong();
    private final AtomicLong handlerErrors = new AtomicLong();
    /** V5 Agent 05.3: events queued + handler tasks not yet finished (see {@link #awaitIdle}). */
    private final AtomicLong inFlight = new AtomicLong();

    /**
     * Default constructor with 4 worker threads.
     */
    public EventBus() {
        this(4);
    }

    // Maximum queue size to prevent memory exhaustion
    private static final int MAX_QUEUE_SIZE = 10000;

    public EventBus(int workerThreads) {
        this.handlers = new ConcurrentHashMap<>();
        this.executorService = Executors.newFixedThreadPool(workerThreads,
                new ThreadFactory() {
                    private final AtomicLong counter = new AtomicLong(0);
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "EventBus-Worker-" + counter.incrementAndGet());
                        t.setDaemon(true);
                        return t;
                    }
                });
        // CRITICAL: Use bounded queue to prevent memory exhaustion on high event volume
        this.eventQueue = new LinkedBlockingQueue<>(MAX_QUEUE_SIZE);
        this.eventsProcessed = new AtomicLong(0);
        this.running = false;

        // Main event processing thread
        this.processingThread = new Thread(this::processEvents, "EventBus-Processor");
        this.processingThread.setDaemon(true);
    }

    /**
     * Subscribe to a specific event type.
     */
    public <T extends Event> void subscribe(EventType type, EventHandler<T> handler) {
        handlers.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(handler);
        logger.debug("Subscribed handler for event type: {}", type);
    }

    /**
     * Subscribe to a specific event class.
     * Convenience method that maps event classes to their EventTypes.
     */
    public <T extends Event> void subscribe(Class<T> eventClass, EventHandler<T> handler) {
        EventType type = mapClassToEventType(eventClass);
        subscribe(type, handler);
    }

    /**
     * Unsubscribe a handler from a specific event type.
     * Prevents memory leaks when components are destroyed.
     *
     * @param type The event type to unsubscribe from
     * @param handler The handler to remove
     * @return true if handler was found and removed, false otherwise
     */
    public <T extends Event> boolean unsubscribe(EventType type, EventHandler<T> handler) {
        List<EventHandler<? extends Event>> eventHandlers = handlers.get(type);
        if (eventHandlers != null) {
            boolean removed = eventHandlers.remove(handler);
            if (removed) {
                logger.debug("Unsubscribed handler from event type: {}", type);
            }
            return removed;
        }
        return false;
    }

    /**
     * Unsubscribe a handler from a specific event class.
     * Convenience method that maps event classes to their EventTypes.
     */
    public <T extends Event> boolean unsubscribe(Class<T> eventClass, EventHandler<T> handler) {
        EventType type = mapClassToEventType(eventClass);
        return unsubscribe(type, handler);
    }

    /**
     * Unsubscribe all handlers for a specific event type.
     * Use with caution - this removes ALL handlers.
     */
    public void unsubscribeAll(EventType type) {
        List<EventHandler<? extends Event>> removed = handlers.remove(type);
        if (removed != null) {
            logger.debug("Removed {} handlers for event type: {}", removed.size(), type);
        }
    }

    /**
     * Clear all handlers (use during shutdown to prevent memory leaks).
     */
    public void clearAllHandlers() {
        int totalHandlers = handlers.values().stream().mapToInt(List::size).sum();
        handlers.clear();
        logger.debug("Cleared {} total handlers", totalHandlers);
    }

    private EventType mapClassToEventType(Class<? extends Event> eventClass) {
        String className = eventClass.getSimpleName();
        return switch (className) {
            case "StrategySignalEvent" -> EventType.STRATEGY_SIGNAL;
            case "CandleEvent" -> EventType.CANDLE;
            case "TickEvent" -> EventType.TICK;
            case "OrderFilledEvent" -> EventType.ORDER_FILLED;
            case "OrderCanceledEvent" -> EventType.ORDER_CANCELED;
            case "OrderRejectedEvent" -> EventType.ORDER_REJECTED;
            case "PositionOpenedEvent" -> EventType.POSITION_OPENED;
            case "PositionClosedEvent" -> EventType.POSITION_CLOSED;
            case "RiskBreachEvent" -> EventType.RISK_BREACH;
            case "GateDecisionEvent" -> EventType.GATE_DECISION;
            case "SetupCancelledEvent" -> EventType.SETUP_CANCELLED;
            default -> {
                logger.warn("Unknown event class: {}, defaulting to STRATEGY_SIGNAL", className);
                yield EventType.STRATEGY_SIGNAL;
            }
        };
    }

    /**
     * Publish an event to the bus.
     */
    public void publish(Event event) {
        if (!running) {
            // V5 Agent 01 (D-14): a publish while the bus is stopped is a WIRING
            // defect (handler/bus lifecycle out of order), not a warning.
            droppedNotRunning.incrementAndGet();
            EngineTelemetry.error("EventBus.publish.notRunning",
                    "EventBus not running, DROPPED event: " + event);
            return;
        }

        try {
            inFlight.incrementAndGet();
            boolean added = eventQueue.offer(event, 100, TimeUnit.MILLISECONDS);
            if (!added) {
                inFlight.decrementAndGet();
                // CRITICAL: Event was dropped due to queue full - this should never happen in normal operation
                droppedQueueFull.incrementAndGet();
                EngineTelemetry.error("EventBus.publish.queueFull",
                        "EVENT DROPPED - Queue full! Event: " + event + " (type: " + event.getClass().getSimpleName() + ")");
            }
        } catch (InterruptedException e) {
            inFlight.decrementAndGet();
            Thread.currentThread().interrupt();
            EngineTelemetry.error("EventBus.publish.interrupted", e);
        }
    }

    /**
     * V5 Agent 05.3 — block until every published event has been dispatched
     * and every handler task has returned (events published BY handlers
     * included), or the timeout elapses. Used by deterministic replay
     * harnesses to model "the handlers ran before the next candle" (a live
     * minute bar is ~10^4 x the handler latency). Production never calls it.
     *
     * @return true when the bus went idle, false on timeout
     */
    public boolean awaitIdle(long timeoutMillis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (inFlight.get() > 0) {
            if (System.nanoTime() > deadline) return false;
            Thread.onSpinWait();
            Thread.yield();
        }
        return true;
    }

    /**
     * Start the event bus.
     */
    public void start() {
        if (running) {
            logger.warn("EventBus already running");
            return;
        }

        running = true;
        processingThread.start();
        logger.info("EventBus started");
    }

    /**
     * Stop the event bus.
     * CRITICAL: Uses synchronized drain to prevent race conditions.
     * No new events can be published once stop() begins.
     */
    public void stop() {
        if (!running) {
            return;
        }

        // CRITICAL FIX: Set running to false FIRST to reject new publishes
        // This prevents race condition where events are added after drain starts
        running = false;

        // Now drain any remaining events in the queue
        // Since running=false, no new events can be added
        Event remainingEvent;
        while ((remainingEvent = eventQueue.poll()) != null) {
            dispatchEvent(remainingEvent);
            eventsProcessed.incrementAndGet();
        }

        // Signal the processing thread to stop
        processingThread.interrupt();

        // Wait for processing thread to finish
        try {
            processingThread.join(2000);  // Wait up to 2 seconds for processing thread
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Shutdown executor and wait for all handler tasks to complete
        executorService.shutdown();
        try {
            // CRITICAL: Wait for all handlers to finish (important for backtest)
            if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                logger.warn("EventBus executor didn't terminate in time, forcing shutdown");
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }

        logger.info("EventBus stopped. Total events processed: {}", eventsProcessed.get());
    }

    /**
     * Main event processing loop.
     */
    private void processEvents() {
        logger.info("EventBus processor thread started");

        while (running) {
            try {
                Event event = eventQueue.poll(100, TimeUnit.MILLISECONDS);
                if (event != null) {
                    dispatchEvent(event);
                    eventsProcessed.incrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                EngineTelemetry.error("EventBus.processEvents", e);
            }
        }

        logger.info("EventBus processor thread stopped");
    }

    /**
     * Dispatch event to all registered handlers.
     */
    @SuppressWarnings("unchecked")
    private void dispatchEvent(Event event) {
        List<EventHandler<? extends Event>> eventHandlers = handlers.get(event.getType());

        try {
            if (eventHandlers == null || eventHandlers.isEmpty()) {
                logger.trace("No handlers registered for event type: {}", event.getType());
                return;
            }

            for (EventHandler handler : eventHandlers) {
                inFlight.incrementAndGet();
                try {
                    executorService.submit(() -> {
                        try {
                            handler.handle(event);
                        } catch (Exception e) {
                            handlerErrors.incrementAndGet();
                            EngineTelemetry.error("EventBus.handler." + event.getType(), e);
                        } finally {
                            inFlight.decrementAndGet();
                        }
                    });
                } catch (RuntimeException rejected) {
                    inFlight.decrementAndGet();
                    throw rejected;
                }
            }
        } finally {
            inFlight.decrementAndGet(); // the dequeued event itself
        }
    }

    /**
     * Get the number of events processed.
     */
    public long getEventsProcessed() {
        return eventsProcessed.get();
    }

    /**
     * Get the current queue size.
     */
    public int getQueueSize() {
        return eventQueue.size();
    }

    /** Events dropped because publish() ran while the bus was stopped (ERROR each). */
    public long getDroppedNotRunning() {
        return droppedNotRunning.get();
    }

    /** Events dropped because the queue was full (ERROR each). */
    public long getDroppedQueueFull() {
        return droppedQueueFull.get();
    }

    /** Handler invocations that threw (ERROR each). */
    public long getHandlerErrors() {
        return handlerErrors.get();
    }

    /** Number of handlers subscribed for an event class (wiring assertions). */
    public int handlerCount(Class<? extends Event> eventClass) {
        List<EventHandler<? extends Event>> h = handlers.get(mapClassToEventType(eventClass));
        return h == null ? 0 : h.size();
    }

    /**
     * Check if the bus is running.
     */
    public boolean isRunning() {
        return running;
    }
}
