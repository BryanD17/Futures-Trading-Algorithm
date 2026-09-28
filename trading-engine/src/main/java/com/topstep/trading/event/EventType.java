package com.topstep.trading.event;

/**
 * Types of events flowing through the system.
 */
public enum EventType {
    // Market data events
    CANDLE,
    TICK,
    QUOTE,

    // Strategy events
    STRATEGY_SIGNAL,
    GATE_DECISION,            // V5 Agent 01: runtime gate telemetry (GateDecisionEvent)

    // Order events
    ORDER_SUBMITTED,
    ORDER_FILLED,
    ORDER_PARTIALLY_FILLED,
    ORDER_CANCELED,
    ORDER_REJECTED,

    // Position events
    POSITION_OPENED,
    POSITION_UPDATED,
    POSITION_CLOSED,

    // Risk events
    RISK_BREACH,
    DAILY_LOSS_LIMIT_REACHED,
    FLATTEN_TIME_APPROACHING,

    // System events
    ENGINE_STARTED,
    ENGINE_STOPPED,
    ENGINE_PAUSED,
    ENGINE_RESUMED,

    // Macro news events
    UPCOMING_NEWS_EVENT,      // Warning about upcoming high-impact event
    NEWS_RELEASE,             // Economic data release processed
    MACRO_BIAS_UPDATE,        // Change in macro bias for an instrument

    // OTE lifecycle telemetry (V5 Agent 04) — subscribe by EventType, not by
    // class (EventBus.mapClassToEventType does not know these classes).
    OTE_ARMED,
    OTE_ALARM,
    OTE_INVALIDATED,
    // V5 Agent 05.3: the emitting setup ended — cancel its unfilled entry.
    SETUP_CANCELLED
}
