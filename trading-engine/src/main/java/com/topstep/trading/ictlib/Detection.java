package com.topstep.trading.ictlib;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One ICT detection — a FACT with a lifecycle, not a drawing (V4 anti-pattern
 * C2). The Bot Chart renders these; the confluence stack scores them; neither
 * one recomputes them.
 *
 * <p>Read-only view. The owning detector holds a {@link MutableDetection} and
 * the registry hands out immutable {@link DetectionSnapshot} copies to the API
 * and chart threads.
 */
public interface Detection {

    /**
     * Stable, DETERMINISTIC identity: {@code type:timeframe:sequence}. Never a
     * UUID or a hash of wall-clock time — replaying the same feed twice must
     * produce byte-identical ids (V4 critical rule 7).
     */
    String id();

    String symbol();

    DetectionType type();

    /** Timeframe label the detection was found on, e.g. {@code "1m"}, {@code "15m"}. */
    String timeframe();

    DetectionDirection direction();

    /** Upper price bound of the zone. Equals {@link #priceBottom()} for point detections. */
    double priceTop();

    /** Lower price bound of the zone. */
    double priceBottom();

    /** Index of the bar (on {@link #timeframe()}) at which the detection was created. */
    long createdAtBar();

    /** Timestamp of the creating bar. */
    Instant createdAt();

    DetectionState state();

    /** Timestamp of the bar that produced the current {@link #state()}. */
    Instant stateChangedAt();

    /** Bar index (on {@link #timeframe()}) of the current {@link #state()}. */
    long stateChangedAtBar();

    /** Small family-specific extras (midlines, cluster sizes, anchors, …). */
    Map<String, Object> meta();

    /** True when {@link #state()} can no longer change. */
    default boolean terminal() {
        return state().isTerminal();
    }

    /** True when {@code price} lies inside the zone (inclusive). */
    default boolean contains(double price) {
        return price >= priceBottom() && price <= priceTop();
    }

    /** Arithmetic midpoint of the zone. */
    default double midpoint() {
        return (priceTop() + priceBottom()) / 2.0;
    }

    /** JSON-friendly map for {@code /api/chart} and {@code /api/confluence}. */
    default Map<String, Object> toApiMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id());
        m.put("type", type().jsonKey());
        m.put("timeframe", timeframe());
        m.put("direction", direction().name());
        m.put("top", priceTop());
        m.put("bottom", priceBottom());
        m.put("state", state().name());
        m.put("createdAt", createdAt() == null ? null : createdAt().toString());
        m.put("stateChangedAt",
                stateChangedAt() == null ? null : stateChangedAt().toString());
        if (!meta().isEmpty()) {
            m.put("meta", meta());
        }
        return m;
    }
}
