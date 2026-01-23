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
    MACRO_BIAS_UPDATE         // Change in macro bias for an instrument
}
