package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.strategy.TradingSessionCalendar;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-session census of how far the setup funnel actually gets, and what kills
 * it (V4 follow-up).
 *
 * <p>WHY THIS EXISTS: {@code ctx.lastGateFailed} is STICKY — it holds the last
 * failure until something overwrites it — so counting it across 15m samples
 * measures how long a setup sat dead, not how often anything happened. That
 * distinction matters enormously: the first read of this engine's SIM logs
 * showed "HTF bias became NEUTRAL" 274 times and suggested a bias crisis, when
 * the actual number of NEUTRAL invalidations in that window was ONE.
 *
 * <p>This class counts EVENTS: each transition into a state, and each
 * invalidation by reason. The resulting line answers "where does the funnel
 * die" in one glance:
 *
 * <pre>
 *   [FUNNEL MNQ] BIAS_SET=14 MANIP_DONE=14 SWEEP_DONE=12 DISPLACED=0
 *                MSS_CONFIRMED=0 OTE_ARMED=0 IN_TRADE=0 |
 *                invalidated: expired=11, HTF bias became NEUTRAL=1
 * </pre>
 *
 * <p>Measurement only — nothing here gates, sizes or vetoes anything.
 */
public final class FunnelTelemetry {

    private static final Map<String, FunnelTelemetry> INSTANCES = new ConcurrentHashMap<>();

    /** The states worth counting arrivals at, in funnel order. */
    private static final SetupState[] TRACKED = {
            SetupState.BIAS_SET, SetupState.MANIP_DONE, SetupState.SWEEP_DONE,
            SetupState.DISPLACED, SetupState.MSS_CONFIRMED, SetupState.OTE_ARMED,
            SetupState.IN_TRADE
    };

    private final String symbol;
    private final Map<SetupState, Long> arrivals = new EnumMap<>(SetupState.class);
    private final Map<String, Long> invalidations = new LinkedHashMap<>();
    /**
     * Why the funnel did NOT advance, counted per candle it sat waiting.
     * Arrival counts say where it stops; these say what it is stopping ON.
     */
    private final Map<String, Long> stalls = new LinkedHashMap<>();
    private SetupState deepestThisSetup = SetupState.IDLE;
    private SetupState deepestEver = SetupState.IDLE;
    private LocalDate sessionDate;

    private FunnelTelemetry(String symbol) {
        this.symbol = symbol;
        for (SetupState s : TRACKED) arrivals.put(s, 0L);
    }

    public static FunnelTelemetry forSymbol(String symbol) {
        return INSTANCES.computeIfAbsent(symbol == null ? "?" : symbol, FunnelTelemetry::new);
    }

    /** Test hook — drops every symbol's counters. */
    public static void resetAll() {
        INSTANCES.clear();
    }

    /**
     * Record one observed state transition.
     *
     * @param from state before this candle's funnel step
     * @param to   state after it
     * @param reason {@code ctx.lastGateFailed} when {@code to} is INVALIDATED
     */
    public synchronized void recordTransition(SetupState from, SetupState to, String reason) {
        if (from == to) return;
        if (arrivals.containsKey(to)) {
            arrivals.merge(to, 1L, Long::sum);
            if (to.ordinal() > deepestThisSetup.ordinal()) deepestThisSetup = to;
            if (to.ordinal() > deepestEver.ordinal()) deepestEver = to;
        }
        if (to == SetupState.INVALIDATED) {
            String key = normalise(reason);
            invalidations.merge(key, 1L, Long::sum);
            deepestThisSetup = SetupState.IDLE;
        }
        if (to == SetupState.IDLE) deepestThisSetup = SetupState.IDLE;
    }

    /**
     * Collapse the variable part of a reason so counts group. "expired (200
     * bars without progress)" and "expired (40 bars…)" are the same failure.
     */
    private static String normalise(String reason) {
        if (reason == null || reason.isBlank()) return "unknown";
        if (reason.startsWith("expired (")) return "expired";
        if (reason.startsWith("HTF bias flip")) return "HTF bias flip";
        return reason;
    }

    /** One candle spent waiting at {@code stage} for {@code reason}. */
    public synchronized void recordStall(String stage, String reason) {
        stalls.merge(stage + ":" + reason, 1L, Long::sum);
    }

    public synchronized Map<String, Long> stalls() {
        return new LinkedHashMap<>(stalls);
    }

    public synchronized long arrivals(SetupState state) {
        return arrivals.getOrDefault(state, 0L);
    }

    public synchronized Map<String, Long> invalidations() {
        return new LinkedHashMap<>(invalidations);
    }

    /** Deepest state reached at any point this session. */
    public synchronized SetupState deepest() {
        return deepestEver;
    }

    /** The one-line census. */
    public synchronized String logLine() {
        StringBuilder sb = new StringBuilder("[FUNNEL ").append(symbol).append("]");
        for (SetupState s : TRACKED) {
            sb.append(' ').append(s).append('=').append(arrivals.getOrDefault(s, 0L));
        }
        sb.append(" | invalidated: ");
        if (invalidations.isEmpty()) {
            sb.append("none");
        } else {
            List<Map.Entry<String, Long>> ranked = new ArrayList<>(invalidations.entrySet());
            ranked.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
            for (int i = 0; i < ranked.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(ranked.get(i).getKey()).append('=').append(ranked.get(i).getValue());
            }
        }
        if (!stalls.isEmpty()) {
            List<Map.Entry<String, Long>> ranked = new ArrayList<>(stalls.entrySet());
            ranked.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
            sb.append(" | stalls: ");
            for (int i = 0; i < Math.min(4, ranked.size()); i++) {
                if (i > 0) sb.append(", ");
                sb.append(ranked.get(i).getKey()).append('=').append(ranked.get(i).getValue());
            }
        }
        return sb.toString();
    }

    /** JSON-friendly counters for the API. */
    public synchronized Map<String, Object> toApiMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> stages = new LinkedHashMap<>();
        for (SetupState s : TRACKED) stages.put(s.name(), arrivals.getOrDefault(s, 0L));
        m.put("stages", stages);
        m.put("invalidations", new LinkedHashMap<>(invalidations));
        m.put("stalls", new LinkedHashMap<>(stalls));
        m.put("deepest", deepestEver.name());
        m.put("sessionDate", sessionDate == null ? null : sessionDate.toString());
        return m;
    }

    /** Session boundaries key on CANDLE time, never the wall clock (B6). */
    public synchronized void rollSessionIfNeeded(Instant at) {
        if (at == null) return;
        LocalDate sd = TradingSessionCalendar.sessionDate(at);
        if (sessionDate == null) {
            sessionDate = sd;
            return;
        }
        if (!sd.equals(sessionDate)) {
            System.out.println(logLine());
            arrivals.replaceAll((k, v) -> 0L);
            invalidations.clear();
            stalls.clear();
            deepestEver = SetupState.IDLE;
            deepestThisSetup = SetupState.IDLE;
            sessionDate = sd;
        }
    }
}
