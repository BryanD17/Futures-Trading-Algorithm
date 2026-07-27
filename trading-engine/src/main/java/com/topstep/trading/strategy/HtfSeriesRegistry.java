package com.topstep.trading.strategy;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-symbol registry of the AUTHORITATIVE {@link BarAggregationManager}
 * instance on the live path (V3 Agent 04).
 *
 * <p>WHY: the two-tier HTF backfill needs a route from the connector (which
 * fetches historical H1 bars) to the aggregation layer's seeding API that
 * NEVER touches the 1m listener path (Critical Rule 8), and the API layer
 * needs read access to the H4/D1 series. There must be exactly ONE
 * authoritative manager per symbol for HTF reads (single-seam principle
 * applied to data — troubleshooting entry "two managers constructed");
 * registration is last-write-wins so an engine restart re-points the
 * registry at the fresh runner's manager.
 */
public final class HtfSeriesRegistry {

    private static final Map<String, BarAggregationManager> REGISTRY =
            new ConcurrentHashMap<>();

    private HtfSeriesRegistry() {}

    /** Register the live runner's manager for a symbol (last write wins). */
    public static void register(String symbol, BarAggregationManager manager) {
        if (symbol == null || manager == null) return;
        REGISTRY.put(symbol, manager);
    }

    /** The authoritative manager for a symbol, if a runner has registered. */
    public static Optional<BarAggregationManager> get(String symbol) {
        return Optional.ofNullable(REGISTRY.get(symbol));
    }
}
