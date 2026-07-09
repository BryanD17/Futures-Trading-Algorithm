package com.topstep.trading.chart;

/** Lifecycle of an OTE fib zone on the 30m chart. */
public enum OteState {
    /** Leg identified, fibs drawn, price has not entered the 0.62–0.79 band yet. */
    FORMING,
    /** Price traded into the OTE band — entry conditions are live. */
    ARMED,
    /** Price rejected back out of the band toward the extreme — the screenshot pattern. */
    REACTED,
    /** Price closed beyond the leg origin (1.0) — setup dead. */
    INVALIDATED,
    /** Zone aged out without a tag. */
    EXPIRED
}
