package com.topstep.trading.strategy.stdvote;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Process-wide registry of active {@link StdvOteStrategy} instances by
 * symbol. Decouples the API layer (which needs to read setup state) from
 * the engine layer (which owns the strategy lifecycle).
 *
 * <p>The strategy registers itself on construction. The controller layer
 * reads via {@link #get(String)} — no shared bean wiring needed.
 *
 * <p>Thread-safety: backed by a {@link ConcurrentHashMap}; reads and writes
 * are independently consistent. Snapshot reads from {@link SetupContext} are
 * lock-free; the API layer must accept that fields may be in mid-update.
 * For SA6 the controller is read-only and the strategy mutates from one
 * thread, so the staleness window is bounded by one onCandle tick.
 */
public final class StdvOteRegistry {

    private static final ConcurrentMap<String, StdvOteStrategy> ACTIVE = new ConcurrentHashMap<>();

    private StdvOteRegistry() {}

    /** Register a strategy under its symbol. Replaces any prior registration. */
    static void register(StdvOteStrategy strategy) {
        if (strategy == null) return;
        SetupContext ctx = strategy.getSetupContext();
        if (ctx == null || ctx.symbol == null) return;
        ACTIVE.put(ctx.symbol, strategy);
    }

    /** Remove a strategy registration. Safe if absent. */
    static void unregister(String symbol) {
        if (symbol != null) ACTIVE.remove(symbol);
    }

    /** Look up the active strategy for an instrument (MNQ / MES / MGC). */
    public static Optional<StdvOteStrategy> get(String symbol) {
        if (symbol == null) return Optional.empty();
        return Optional.ofNullable(ACTIVE.get(symbol));
    }

    /** Symbols with a registered strategy. */
    public static Set<String> activeSymbols() {
        return Collections.unmodifiableSet(ACTIVE.keySet());
    }

    /** Clear the registry. Test-only helper; not exported. */
    static void clearForTest() {
        ACTIVE.clear();
    }
}
