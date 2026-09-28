package com.topstep.trading.strategy.stdvote;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * V5 Agent 04 — last {@value #CAPACITY} OTE lifecycle events per symbol, in
 * JSON-ready form, for {@code /api/setup} and the dashboard.
 *
 * <p>The same events are published on the EventBus (types OTE_ARMED /
 * OTE_ALARM / OTE_INVALIDATED); this log exists so the API layer can read the
 * recent history without subscribing. Hand-off to Agent 01's SetupController:
 * {@code OteEventLog.recent(symbol)} → {@code "oteEvents"}.
 */
public final class OteEventLog {

    static final int CAPACITY = 50;

    private static final Map<String, Deque<Map<String, Object>>> LOG = new ConcurrentHashMap<>();

    private OteEventLog() {}

    static void record(String symbol, Map<String, Object> event) {
        if (symbol == null || event == null) return;
        Deque<Map<String, Object>> q = LOG.computeIfAbsent(symbol, k -> new ArrayDeque<>());
        synchronized (q) {
            q.addLast(event);
            while (q.size() > CAPACITY) q.removeFirst();
        }
    }

    /** Oldest-first copy of the recent events for {@code symbol}. */
    public static List<Map<String, Object>> recent(String symbol) {
        Deque<Map<String, Object>> q = LOG.get(symbol);
        if (q == null) return List.of();
        synchronized (q) {
            return new ArrayList<>(q);
        }
    }

    /** Test hook. */
    static void clear(String symbol) {
        LOG.remove(symbol);
    }
}
