package com.topstep.trading.ictlib;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The detector-owned, mutable form of a {@link Detection}.
 *
 * <p>Only the family's own detector mutates one of these — that is the "one
 * source of truth per detection" rule (V4 B13) expressed in code: the setters
 * are package-private, so nothing outside {@code ictlib} can advance a
 * lifecycle behind the detector's back.
 */
public final class MutableDetection implements Detection {

    private final String id;
    private final String symbol;
    private final DetectionType type;
    private final String timeframe;
    private final DetectionDirection direction;
    private final long createdAtBar;
    private final Instant createdAt;
    private final Map<String, Object> meta = new LinkedHashMap<>();

    private double priceTop;
    private double priceBottom;
    private DetectionState state;
    private Instant stateChangedAt;
    private long stateChangedAtBar;

    MutableDetection(String id, String symbol, DetectionType type, String timeframe,
                     DetectionDirection direction, double priceBottom, double priceTop,
                     long createdAtBar, Instant createdAt, DetectionState initialState) {
        this.id = id;
        this.symbol = symbol;
        this.type = type;
        this.timeframe = timeframe;
        this.direction = direction;
        this.priceBottom = Math.min(priceBottom, priceTop);
        this.priceTop = Math.max(priceBottom, priceTop);
        this.createdAtBar = createdAtBar;
        this.createdAt = createdAt;
        this.state = initialState;
        this.stateChangedAt = createdAt;
        this.stateChangedAtBar = createdAtBar;
    }

    @Override public String id() { return id; }
    @Override public String symbol() { return symbol; }
    @Override public DetectionType type() { return type; }
    @Override public String timeframe() { return timeframe; }
    @Override public DetectionDirection direction() { return direction; }
    @Override public double priceTop() { return priceTop; }
    @Override public double priceBottom() { return priceBottom; }
    @Override public long createdAtBar() { return createdAtBar; }
    @Override public Instant createdAt() { return createdAt; }
    @Override public DetectionState state() { return state; }
    @Override public Instant stateChangedAt() { return stateChangedAt; }
    @Override public long stateChangedAtBar() { return stateChangedAtBar; }

    @Override
    public Map<String, Object> meta() {
        return Collections.unmodifiableMap(meta);
    }

    /**
     * Advance the lifecycle. No-ops when the detection is already terminal, so
     * a late candle can never resurrect a filled zone (monotonicity).
     */
    void advanceTo(DetectionState newState, Instant at, long bar) {
        if (state.isTerminal() || newState == state) return;
        this.state = newState;
        this.stateChangedAt = at;
        this.stateChangedAtBar = bar;
    }

    /**
     * §S6 pool update: replace the zone outright. Distinct from
     * {@link #widenTo} because a re-clustered pool's band is RECOMPUTED (a
     * newer ATR gives a different tolerance), not accumulated — widening
     * would let a pool grow monotonically until it covered everything.
     */
    void resetZone(double bottom, double top) {
        this.priceBottom = Math.min(bottom, top);
        this.priceTop = Math.max(bottom, top);
    }

    /** §S2 consecutive-gap merge: widen the zone to the union of both gaps. */
    void widenTo(double bottom, double top) {
        this.priceBottom = Math.min(this.priceBottom, Math.min(bottom, top));
        this.priceTop = Math.max(this.priceTop, Math.max(bottom, top));
    }

    void putMeta(String key, Object value) {
        meta.put(key, value);
    }

    @Override
    public String toString() {
        return String.format("%s[%s %s %s %.2f-%.2f @%s]",
                type, timeframe, direction, state, priceBottom, priceTop, createdAt);
    }
}
