package com.topstep.trading.ictlib;

/**
 * Lifecycle state of an ictlib detection (Appendix S defines the transitions
 * per family).
 *
 * <p>Two invariants hold for every family (V4 critical rule 8 + B13):
 * <ul>
 *   <li>Transitions are MONOTONIC — a state never reverts. A zone that has
 *       been traversed is a historical fact; letting it un-fill would make
 *       the chart unfalsifiable against the broker chart.</li>
 *   <li>There is ONE owner of the transition (the family's detector). The
 *       chart and the confluence stack READ this state; they never recompute
 *       it with their own rules.</li>
 * </ul>
 *
 * <p>{@link #isTerminal()} drives two things: the detector stops advancing the
 * detection, and {@link DetectionRegistry} evicts terminal detections before
 * live ones when a retention cap is hit.
 */
public enum DetectionState {

    /** Zone formed; price has not re-entered it. */
    ACTIVE(false),

    /** Price re-entered the zone without traversing it (partial fill). */
    TOUCHED(false),

    /** §S2 — price traversed the whole gap. Terminal; right edge frozen. */
    FILLED(true),

    /** §S3 — price closed through the region to its far side. Terminal. */
    BROKEN(true),

    /**
     * An instantaneous detection (§S1 displacement): it describes one candle
     * and never advances. Terminal by construction, not by consumption.
     */
    POINT(true);

    private final boolean terminal;

    DetectionState(boolean terminal) {
        this.terminal = terminal;
    }

    /** True when no further transition is possible from this state. */
    public boolean isTerminal() {
        return terminal;
    }
}
