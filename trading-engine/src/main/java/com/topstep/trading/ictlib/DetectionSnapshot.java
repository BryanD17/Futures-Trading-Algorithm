package com.topstep.trading.ictlib;

import java.time.Instant;
import java.util.Map;

/**
 * Immutable copy of a {@link Detection}, produced by
 * {@link DetectionRegistry#snapshot()} so API and chart threads never observe
 * a detection mid-transition.
 */
public record DetectionSnapshot(
        String id,
        String symbol,
        DetectionType type,
        String timeframe,
        DetectionDirection direction,
        double priceTop,
        double priceBottom,
        long createdAtBar,
        Instant createdAt,
        DetectionState state,
        Instant stateChangedAt,
        long stateChangedAtBar,
        Map<String, Object> meta
) implements Detection {

    public DetectionSnapshot {
        meta = (meta == null) ? Map.of() : Map.copyOf(meta);
    }

    static DetectionSnapshot of(Detection d) {
        return new DetectionSnapshot(
                d.id(), d.symbol(), d.type(), d.timeframe(), d.direction(),
                d.priceTop(), d.priceBottom(), d.createdAtBar(), d.createdAt(),
                d.state(), d.stateChangedAt(), d.stateChangedAtBar(), d.meta());
    }
}
