package com.topstep.trading.strategy.stdvote;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Session-scoped agreement counters between the state machine's emissions
 * and the 30m ChartEngine's REACTED OTE zones (V2 Agent 06).
 *
 * <p>WHY: the owner's standing question — "should entries be gated on the
 * 30m chart's REACTED OTE?" — can only be answered with counts, not
 * anecdotes. This class counts; IT DOES NOT GATE. Nothing anywhere reads
 * these numbers to allow or block a trade.
 *
 * <p>NOT DURABLE, BY DESIGN: counters live in a JVM-static per-symbol
 * registry and reset when the process exits. They deliberately survive
 * in-app engine restarts (stop→start) so a day of observation is not
 * erased by a restart, but they are NOT a persisted metric — do not treat
 * them as one.
 *
 * <p>Thread-safety: counts are atomics; the per-bucket event rings are
 * bounded ({@link #RING_CAP}) and guarded by their own monitor. Market-data
 * thread writes, API thread reads snapshots.
 */
public final class OteAgreementStats {

    /** Bounded ring size per bucket — memory is a budget (anti-pattern C10). */
    public static final int RING_CAP = 10;

    private static final Map<String, OteAgreementStats> REGISTRY = new ConcurrentHashMap<>();

    /** The per-symbol stats instance (created on first use, JVM-scoped). */
    public static OteAgreementStats forSymbol(String symbol) {
        return REGISTRY.computeIfAbsent(symbol, OteAgreementStats::new);
    }

    /** Test hook: drop all per-symbol instances (fresh session counters). */
    static void resetRegistryForTest() {
        REGISTRY.clear();
    }

    private final AtomicLong machineEmittedChartAgreed = new AtomicLong();
    private final AtomicLong machineEmittedChartDisagreed = new AtomicLong();
    private final AtomicLong chartReactedMachineSilent = new AtomicLong();

    private final Deque<Instant> agreedEvents = new ArrayDeque<>(RING_CAP);
    private final Deque<Instant> disagreedEvents = new ArrayDeque<>(RING_CAP);
    private final Deque<Instant> chartOnlyEvents = new ArrayDeque<>(RING_CAP);

    /** Symbol key, so the persistence store can be queried (V3 Agent 06). */
    private final String symbol;

    private OteAgreementStats(String symbol) {
        this.symbol = symbol;
    }

    /** The machine emitted and the 30m chart showed REACTED same-direction. */
    public void recordMachineEmittedChartAgreed(Instant at) {
        machineEmittedChartAgreed.incrementAndGet();
        push(agreedEvents, at);
    }

    /** The machine emitted while the 30m chart did NOT show the pattern. */
    public void recordMachineEmittedChartDisagreed(Instant at) {
        machineEmittedChartDisagreed.incrementAndGet();
        push(disagreedEvents, at);
    }

    /** The 30m chart hit REACTED while the machine had no armed setup. */
    public void recordChartReactedMachineSilent(Instant at) {
        chartReactedMachineSilent.incrementAndGet();
        push(chartOnlyEvents, at);
    }

    private void push(Deque<Instant> ring, Instant at) {
        if (at == null) at = Instant.now();
        synchronized (ring) {
            while (ring.size() >= RING_CAP) {
                ring.removeFirst();
            }
            ring.addLast(at);
        }
    }

    private static List<String> copy(Deque<Instant> ring) {
        synchronized (ring) {
            List<String> out = new ArrayList<>(ring.size());
            for (Instant i : ring) out.add(i.toString());
            return out;
        }
    }

    public long agreed()    { return machineEmittedChartAgreed.get(); }
    public long disagreed() { return machineEmittedChartDisagreed.get(); }
    public long chartOnly() { return chartReactedMachineSilent.get(); }

    /** One-line rollup for the [GATES] 15m telemetry (append-only format). */
    public String rollup() {
        return "oteAgree=" + agreed() + " oteDisagree=" + disagreed()
                + " chartOnly=" + chartOnly();
    }

    /**
     * JSON-friendly map for the /api/chart "oteStats" object. The three
     * top-level counters keep their V2 names and SESSION scope (append-only
     * format); V3 Agent 06 ADDS explicit {@code session} and
     * {@code lifetime} blocks — lifetime = every persisted PAST session
     * (last checkpoint per session date) + this live session's counts.
     */
    public Map<String, Object> toApiMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("machineEmitted_chartAgreed", agreed());
        m.put("machineEmitted_chartDisagreed", disagreed());
        m.put("chartReacted_machineSilent", chartOnly());
        Map<String, Object> events = new LinkedHashMap<>();
        events.put("agreed", copy(agreedEvents));
        events.put("disagreed", copy(disagreedEvents));
        events.put("chartOnly", copy(chartOnlyEvents));
        m.put("lastEvents", events);

        Map<String, Object> session = new LinkedHashMap<>();
        session.put("machineEmitted_chartAgreed", agreed());
        session.put("machineEmitted_chartDisagreed", disagreed());
        session.put("chartReacted_machineSilent", chartOnly());
        m.put("session", session);

        java.time.LocalDate today = java.time.LocalDate.now(
                java.time.ZoneId.of("America/New_York"));
        OteAgreementStatsStore.Lifetime past =
                OteAgreementStatsStore.lifetimeExcluding(symbol, today);
        Map<String, Object> lifetime = new LinkedHashMap<>();
        lifetime.put("machineEmitted_chartAgreed", past.agreed() + agreed());
        lifetime.put("machineEmitted_chartDisagreed", past.disagreed() + disagreed());
        lifetime.put("chartReacted_machineSilent", past.chartOnly() + chartOnly());
        lifetime.put("sessionsCounted", past.sessions() + 1);
        m.put("lifetime", lifetime);
        return m;
    }
}
