package com.topstep.trading.trade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.topstep.trading.confluence.ConfluenceService;
import com.topstep.trading.confluence.ConfluenceSnapshot;
import com.topstep.trading.strategy.TradingSessionCalendar;
import com.topstep.trading.strategy.stdvote.SetupContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ALWAYS-ON profile simulator (V4 Agent 08) — the answer to "why does it not
 * trade", as a number.
 *
 * <p>At every emission evaluation it evaluates ALL THREE profiles, regardless
 * of which one is active. When a profile is satisfied and the active profile
 * did NOT emit, it records a {@link WouldTradeEvent} carrying the active
 * profile's full blocking-gate list. Even running in STRICT — the default —
 * every session quietly answers "what would STANDARD have done?", so the owner
 * never has to guess what a flip would change (Appendix E4).
 *
 * <p>It is pure measurement. It never emits, blocks, sizes or vetoes anything;
 * {@link #evaluate} returns void and its result is not consulted by any gate.
 *
 * <p>Persistence follows the repo's existing engine-side convention (the same
 * one {@code OteAgreementStatsStore} uses): append-only JSONL at
 * {@code data/profile_sim.jsonl}, overridable with {@code -Dprofile.sim.file}.
 * Append-only is crash-safe by construction, and the events are individually
 * meaningful, so a truncated tail costs one event rather than the file.
 */
public final class ProfileSimulator {

    /** System property overriding the JSONL path (tests use temp files). */
    public static final String FILE_PROPERTY = "profile.sim.file";
    /** How many recent events per profile the API surfaces. */
    public static final int RECENT_EVENTS = 5;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Object FILE_LOCK = new Object();
    private static final Map<String, ProfileSimulator> INSTANCES = new ConcurrentHashMap<>();

    private final String symbol;
    private final Map<TradeProfile, Long> satisfiedCount = new EnumMap<>(TradeProfile.class);
    private final Map<TradeProfile, Long> wouldTradeCount = new EnumMap<>(TradeProfile.class);
    private final Map<String, Long> blockingFrequency = new LinkedHashMap<>();
    private final Deque<WouldTradeEvent> recent = new ArrayDeque<>();
    /**
     * Rising-edge latch per profile. A profile that stays satisfied across
     * twenty consecutive samples represents ONE opportunity, not twenty; only
     * the transition into satisfied records an event. Without this the counts
     * would measure how long a condition persisted rather than how often it
     * occurred, and the owner would flip a profile on an inflated number.
     */
    private final Map<TradeProfile, Boolean> wasSatisfied = new EnumMap<>(TradeProfile.class);
    private long evaluations;
    private long emissionEvaluations;
    private LocalDate sessionDate;

    private ProfileSimulator(String symbol) {
        this.symbol = symbol;
        for (TradeProfile p : TradeProfile.values()) {
            satisfiedCount.put(p, 0L);
            wouldTradeCount.put(p, 0L);
            wasSatisfied.put(p, Boolean.FALSE);
        }
    }

    public static ProfileSimulator forSymbol(String symbol) {
        return INSTANCES.computeIfAbsent(symbol == null ? "?" : symbol, ProfileSimulator::new);
    }

    /** Test hook — drops every symbol's counters. */
    public static void resetAll() {
        INSTANCES.clear();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // THE EVALUATION
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Evaluate every profile for one emission attempt.
     *
     * @param ctx              the setup being judged
     * @param strictFailedGate the gate the STRICT chain stopped at, or null
     * @param activeEmitted    whether the ACTIVE profile let the trade through
     * @param confluence       the stack for this setup's direction (may be null)
     * @param at               candle time — never the wall clock (B6)
     */
    public void evaluate(SetupContext ctx, String strictFailedGate, boolean activeEmitted,
                         ConfluenceSnapshot confluence, Instant at) {
        evaluate(ctx, strictFailedGate, activeEmitted, confluence, at, true);
    }

    /**
     * @param atEmissionPoint true when this is a real emission evaluation
     *        (the validator seam), false for the periodic 15m sample.
     *
     * <p>Sampling periodically is not padding. In practice the funnel reaches
     * an emission evaluation only rarely — which is itself the owner's
     * complaint — so a simulator that ONLY ran there would report zero and
     * explain nothing. The periodic sample answers "what would each profile
     * say about the setup as it stands right now", which is the question that
     * makes MINIMAL=0 meaningful (Appendix E5).
     */
    public synchronized void evaluate(SetupContext ctx, String strictFailedGate,
                                      boolean activeEmitted, ConfluenceSnapshot confluence,
                                      Instant at, boolean atEmissionPoint) {
        if (ctx == null) return;
        TradeProfile active = TradeProfile.active();
        rollSessionIfNeeded(at);
        evaluations++;
        if (atEmissionPoint) emissionEvaluations++;

        Map<TradeProfile, ProfileDecision> decisions = new EnumMap<>(TradeProfile.class);
        for (TradeProfile p : TradeProfile.values()) {
            decisions.put(p, ProfileEvaluator.evaluate(p, ctx, confluence, strictFailedGate));
        }

        ProfileDecision activeDecision = decisions.get(active);
        for (String gate : activeDecision.blocking()) {
            blockingFrequency.merge(gate, 1L, Long::sum);
        }

        for (Map.Entry<TradeProfile, ProfileDecision> e : decisions.entrySet()) {
            boolean satisfied = e.getValue().satisfied();
            boolean rising = satisfied && !Boolean.TRUE.equals(wasSatisfied.get(e.getKey()));
            wasSatisfied.put(e.getKey(), satisfied);
            if (!rising) continue;
            satisfiedCount.merge(e.getKey(), 1L, Long::sum);
            // A would-trade event is only interesting when the engine did NOT
            // take it — otherwise it is just a trade.
            if (activeEmitted || e.getKey() == active) continue;

            WouldTradeEvent event = new WouldTradeEvent(
                    at, symbol, ProfileEvaluator.directionOf(ctx), e.getKey(), active,
                    activeDecision.blocking(), ctx.entry, ctx.stop,
                    targetOf(ctx), ctx.rr, ctx.sizeRequest);
            wouldTradeCount.merge(e.getKey(), 1L, Long::sum);
            synchronized (this) {
                recent.addLast(event);
                while (recent.size() > RECENT_EVENTS * TradeProfile.values().length) {
                    recent.removeFirst();
                }
            }
            persist(event);
        }
    }

    private static double targetOf(SetupContext ctx) {
        // The profile would have used the same geometry the strategy computed:
        // risk projected by the achieved RR. Derived, never invented.
        double risk = Math.abs(ctx.entry - ctx.stop);
        boolean bullish = ProfileEvaluator.directionOf(ctx);
        return bullish ? ctx.entry + risk * ctx.rr : ctx.entry - risk * ctx.rr;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TELEMETRY + READOUT
    // ═══════════════════════════════════════════════════════════════════════

    /** {@code [PROFILE MNQ] active=STRICT wouldTrade: STANDARD=3 MINIMAL=7 (session)} */
    public synchronized String logLine() {
        return "[PROFILE " + symbol + "] active=" + TradeProfile.active()
                + " wouldTrade: STANDARD=" + wouldTradeCount.getOrDefault(TradeProfile.STANDARD, 0L)
                + " MINIMAL=" + wouldTradeCount.getOrDefault(TradeProfile.MINIMAL, 0L)
                + " (session)";
    }

    public synchronized long evaluations() {
        return evaluations;
    }

    /** Evaluations that happened at a REAL emission point, not a periodic sample. */
    public synchronized long emissionEvaluations() {
        return emissionEvaluations;
    }

    public synchronized long wouldTrade(TradeProfile profile) {
        return wouldTradeCount.getOrDefault(profile, 0L);
    }

    public synchronized long satisfied(TradeProfile profile) {
        return satisfiedCount.getOrDefault(profile, 0L);
    }

    /** Blocking gates of the ACTIVE profile, ranked by frequency this session. */
    public synchronized List<Map.Entry<String, Long>> rankedBlockingGates() {
        List<Map.Entry<String, Long>> out = new ArrayList<>(blockingFrequency.entrySet());
        out.sort((a, b) -> {
            int byCount = Long.compare(b.getValue(), a.getValue());
            return byCount != 0 ? byCount : a.getKey().compareTo(b.getKey());
        });
        return out;
    }

    /** The most recent would-trade events for a profile, newest last. */
    public synchronized List<WouldTradeEvent> recentEvents(TradeProfile profile) {
        List<WouldTradeEvent> out = new ArrayList<>();
        for (WouldTradeEvent e : recent) {
            if (e.profile() == profile) out.add(e);
        }
        int from = Math.max(0, out.size() - RECENT_EVENTS);
        return out.subList(from, out.size());
    }

    /** JSON-friendly counters for {@code /api/confluence}. */
    public synchronized Map<String, Object> toApiMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("activeProfile", TradeProfile.active().name());
        m.put("evaluations", evaluations);
        m.put("emissionEvaluations", emissionEvaluations);
        m.put("sessionDate", sessionDate == null ? null : sessionDate.toString());

        Map<String, Object> counters = new LinkedHashMap<>();
        for (TradeProfile p : TradeProfile.values()) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("satisfied", satisfiedCount.getOrDefault(p, 0L));
            c.put("wouldTrade", wouldTradeCount.getOrDefault(p, 0L));
            List<Map<String, Object>> events = new ArrayList<>();
            for (WouldTradeEvent e : recentEvents(p)) events.add(e.toMap());
            c.put("recentEvents", events);
            counters.put(p.name(), c);
        }
        m.put("profiles", counters);

        List<Map<String, Object>> ranked = new ArrayList<>();
        for (Map.Entry<String, Long> e : rankedBlockingGates()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("gate", e.getKey());
            r.put("count", e.getValue());
            ranked.add(r);
        }
        m.put("blockingGates", ranked);
        return m;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PERSISTENCE
    // ═══════════════════════════════════════════════════════════════════════

    private static Path file() {
        return Path.of(System.getProperty(FILE_PROPERTY, "data/profile_sim.jsonl"));
    }

    /** Append one event. Failures are logged and swallowed — never the tape. */
    private static void persist(WouldTradeEvent event) {
        synchronized (FILE_LOCK) {
            try {
                Path f = file();
                if (f.getParent() != null) Files.createDirectories(f.getParent());
                Files.writeString(f, MAPPER.writeValueAsString(event.toMap()) + System.lineSeparator(),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException | RuntimeException e) {
                System.out.println("[PROFILE] could not persist would-trade event: " + e);
            }
        }
    }

    /** Read every persisted event back — the durability round-trip. */
    public static List<Map<String, Object>> loadPersisted() {
        synchronized (FILE_LOCK) {
            List<Map<String, Object>> out = new ArrayList<>();
            Path f = file();
            if (!Files.exists(f)) return out;
            try {
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) continue;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = MAPPER.readValue(line, Map.class);
                    out.add(m);
                }
            } catch (IOException | RuntimeException e) {
                System.out.println("[PROFILE] could not read would-trade events: " + e);
            }
            return out;
        }
    }

    /**
     * Session counters key on the CME SESSION date derived from the CANDLE's
     * timestamp — never the wall clock, so a replay reports the session it is
     * replaying (B6).
     */
    private void rollSessionIfNeeded(Instant at) {
        if (at == null) return;
        LocalDate sd = TradingSessionCalendar.sessionDate(at);
        if (sessionDate == null) {
            sessionDate = sd;
            return;
        }
        if (!sd.equals(sessionDate)) {
            System.out.println(logLine());
            satisfiedCount.replaceAll((k, v) -> 0L);
            wouldTradeCount.replaceAll((k, v) -> 0L);
            blockingFrequency.clear();
            evaluations = 0;
            emissionEvaluations = 0;
            wasSatisfied.replaceAll((k, v) -> Boolean.FALSE);
            sessionDate = sd;
        }
    }

    /** Wire the confluence stack in so the simulator can score profiles. */
    public static ConfluenceSnapshot snapshotFor(ConfluenceService service, String symbol,
                                                 SetupContext ctx) {
        if (service == null) return null;
        return service.snapshot(symbol, ProfileEvaluator.directionOf(ctx));
    }
}
