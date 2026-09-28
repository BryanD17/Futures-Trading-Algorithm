package com.topstep.trading.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-wide runtime telemetry (V5 Agent 01, RC-17): the last
 * {@value #RING_CAPACITY} {@link GateDecisionEvent}s, per-gate counters, and
 * per-site ERROR counters for every catch on the candle → signal → order
 * path in the runners / EventBus. Nothing here gates anything.
 */
public final class EngineTelemetry {

    private static final Logger log = LoggerFactory.getLogger(EngineTelemetry.class);

    /** How many gate decisions GET /api/setup can return. */
    public static final int RING_CAPACITY = 200;

    private static final Deque<GateDecisionEvent> RING = new ArrayDeque<>(RING_CAPACITY);
    private static final Map<String, AtomicLong> GATE_COUNTS = new ConcurrentHashMap<>();
    private static final Map<String, AtomicLong> ERROR_COUNTS = new ConcurrentHashMap<>();
    private static final ZoneId ET = ZoneId.of("America/New_York");

    private EngineTelemetry() {}

    /** Record the decision and publish it on the bus (when the bus is running). */
    public static void publish(EventBus bus, GateDecisionEvent event) {
        record(event);
        if (bus != null && bus.isRunning()) {
            bus.publish(event);
        }
    }

    /** Record without a bus (keeps the ring + counters). */
    public static void record(GateDecisionEvent event) {
        if (event == null) return;
        synchronized (RING) {
            if (RING.size() == RING_CAPACITY) RING.removeFirst();
            RING.addLast(event);
        }
        GATE_COUNTS.computeIfAbsent(event.getGate(), g -> new AtomicLong()).incrementAndGet();
    }

    /** Oldest-first copy of the last {@code n} decisions (n &lt;= 200). */
    public static List<GateDecisionEvent> recent(int n) {
        synchronized (RING) {
            List<GateDecisionEvent> all = new ArrayList<>(RING);
            int from = Math.max(0, all.size() - Math.max(0, n));
            return new ArrayList<>(all.subList(from, all.size()));
        }
    }

    /** Count of recorded decisions per gate since start. */
    public static Map<String, Long> gateCounts() {
        Map<String, Long> out = new TreeMap<>();
        GATE_COUNTS.forEach((k, v) -> out.put(k, v.get()));
        return out;
    }

    /**
     * An exception caught on the signal/order path: logged at ERROR (with the
     * stack trace) and counted per site. Never silent.
     */
    public static void error(String site, Throwable t) {
        ERROR_COUNTS.computeIfAbsent(site, s -> new AtomicLong()).incrementAndGet();
        log.error("[{}] {}: {}", site, t == null ? "error" : t.getClass().getSimpleName(),
                t == null ? "" : t.getMessage(), t);
    }

    /** An error condition without an exception (e.g. EventBus drop). */
    public static void error(String site, String message) {
        ERROR_COUNTS.computeIfAbsent(site, s -> new AtomicLong()).incrementAndGet();
        log.error("[{}] {}", site, message);
    }

    /** ERROR counts per site since start. */
    public static Map<String, Long> errorCounts() {
        Map<String, Long> out = new TreeMap<>();
        ERROR_COUNTS.forEach((k, v) -> out.put(k, v.get()));
        return out;
    }

    /** Clear everything (tests). */
    public static void resetForTests() {
        synchronized (RING) {
            RING.clear();
        }
        GATE_COUNTS.clear();
        ERROR_COUNTS.clear();
    }

    /**
     * Session label (ET) for telemetry rows — same windows as the
     * FunnelAutopsyHarness so runtime rows and tape histograms line up.
     * AGENT-02: replace with the single session classifier when it lands.
     */
    public static String sessionOf(Instant at) {
        if (at == null) return "UNKNOWN";
        ZonedDateTime z = at.atZone(ET);
        DayOfWeek d = z.getDayOfWeek();
        LocalTime t = z.toLocalTime();
        if (d == DayOfWeek.SATURDAY) return "WEEKEND";
        if (d == DayOfWeek.SUNDAY && t.isBefore(LocalTime.of(18, 0))) return "WEEKEND";
        if (d == DayOfWeek.FRIDAY && !t.isBefore(LocalTime.of(17, 0))) return "WEEKEND";
        if (!t.isBefore(LocalTime.of(19, 0)) || t.isBefore(LocalTime.of(2, 0))) return "ASIA";
        if (t.isBefore(LocalTime.of(8, 0))) return "LONDON";
        if (t.isBefore(LocalTime.of(9, 30))) return "PRE_NY";
        if (t.isBefore(LocalTime.of(12, 0))) return "NY_AM";
        if (t.isBefore(LocalTime.of(13, 30))) return "NY_LUNCH";
        if (t.isBefore(LocalTime.of(15, 45))) return "NY_PM";
        if (t.isBefore(LocalTime.of(18, 0))) return "NO_ENTRY";
        return "PRE_ASIA";
    }
}
