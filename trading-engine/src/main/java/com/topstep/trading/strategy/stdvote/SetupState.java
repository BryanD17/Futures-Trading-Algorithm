package com.topstep.trading.strategy.stdvote;

/**
 * State machine for a single in-flight STDV+OTE setup on one instrument.
 *
 * <p>States advance strictly in order — gates cannot be skipped. A single
 * candle may legally advance through multiple states in one tick if every
 * gate is satisfied at once (e.g., a candle that simultaneously sweeps and
 * displaces); each advance is independently validated.
 *
 * <p>{@code INVALIDATED} is reachable from any state on HTF bias flip,
 * counter-bias MSS, or setup-expiry. From {@code INVALIDATED} (or
 * {@code DONE}) the engine resets to {@code IDLE} for the next window.
 */
public enum SetupState {
    /** No bias, no setup. */
    IDLE,
    /** HTF bias is set, no manipulation leg yet. */
    BIAS_SET,
    /** Manipulation leg identified; STDV projection computed. */
    MANIP_DONE,
    /** Liquidity sweep confirmed with adequate raid quality. */
    SWEEP_DONE,
    /** Displacement candle + FVG present after the sweep. */
    DISPLACED,
    /** Market Structure Shift (or CHoCH) confirmed in the bias direction. */
    MSS_CONFIRMED,
    /** OTE zone built, PD array confirmed inside the zone, awaiting entry. */
    OTE_ARMED,
    /** Order emitted; position open. */
    IN_TRADE,
    /** Partials taken / runner active; managing to STDV targets. */
    MANAGING,
    /** Trade closed at target, stop, or by time. */
    DONE,
    /** Setup cancelled before emission (bias flip, expiry, counter-bias MSS). */
    INVALIDATED
}
