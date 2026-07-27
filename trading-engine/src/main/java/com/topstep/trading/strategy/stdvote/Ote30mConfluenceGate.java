package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.chart.ChartEngine;
import com.topstep.trading.chart.OteState;
import com.topstep.trading.chart.OteZoneSnapshot;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * M7b — the 30m ChartEngine OTE confluence gate (V3 Agent 06, GAP-4).
 *
 * <p>The 30m chart's screenshot-pattern verdict has been LOG-ONLY since V2
 * ("DO NOT gate on this"). This class gives it a decision pipeline: an
 * additional M-series gate at the SAME evaluation point as the existing
 * comparison, behind a three-position switch. The promote-or-delete
 * DECISION stays with the owner (QUICK_START carries the numeric
 * criteria); this gate just makes both outcomes one step away.
 *
 * <h2>Modes ({@code ote30m.confluence}, DEFAULT LOG)</h2>
 * <ul>
 *   <li>{@code OFF} — never evaluated (byte-identical, no counters).</li>
 *   <li>{@code LOG} — the confluence verdict is computed and counted (the
 *       V2 comparison formalized through the same counters); the gate
 *       always passes.</li>
 *   <li>{@code GATE} — emission requires the chart's active zone for the
 *       SIGNAL DIRECTION to be {@link OteState#REACTED} (or
 *       {@link OteState#ARMED} when {@code ote30m.acceptArmed=true},
 *       default false).</li>
 * </ul>
 *
 * <p>ABSTAIN semantics (Rollout Doctrine): NO zone currently tracked (or
 * no ChartEngine wired) → pass + count. A thin chart must never silently
 * disable trading — the cold-start deadlock class stays dead.
 */
public final class Ote30mConfluenceGate {

    /** System property: gate mode. */
    public static final String MODE_PROPERTY = "ote30m.confluence";
    /** System property: accept ARMED (not just REACTED) zones. Default false. */
    public static final String ACCEPT_ARMED_PROPERTY = "ote30m.acceptArmed";

    /** Three-position rollout switch. */
    public enum Mode { OFF, LOG, GATE }

    /** Outcome of one M7b check. */
    public record Decision(boolean passed, String reason) {}

    private static final Map<String, Ote30mConfluenceGate> REGISTRY =
            new ConcurrentHashMap<>();

    /** Build from system properties, register for API access, log config. */
    public static Ote30mConfluenceGate install(String symbol) {
        Mode mode = parseMode(System.getProperty(MODE_PROPERTY, "LOG"));
        boolean acceptArmed = Boolean.getBoolean(ACCEPT_ARMED_PROPERTY);
        Ote30mConfluenceGate g = new Ote30mConfluenceGate(symbol, mode, acceptArmed);
        REGISTRY.put(symbol, g);
        System.out.println("[OTE30M " + symbol + "] config: mode=" + mode
                + " acceptArmed=" + acceptArmed);
        return g;
    }

    /** Registered gate for a symbol (empty before the runner wires it). */
    public static Optional<Ote30mConfluenceGate> get(String symbol) {
        return Optional.ofNullable(REGISTRY.get(symbol));
    }

    static Mode parseMode(String raw) {
        if (raw == null) return Mode.LOG;
        try {
            return Mode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            System.out.println("[OTE30M] WARN: invalid " + MODE_PROPERTY + "='"
                    + raw + "', using default LOG");
            return Mode.LOG;
        }
    }

    private final String symbol;
    private final Mode mode;
    private final boolean acceptArmed;

    /** Zone source — the chart engine in production, fabricated in tests. */
    private volatile java.util.function.Supplier<Optional<OteZoneSnapshot>> zoneSource;

    // Session-scoped counters (labeled; NOT persisted — the persistent
    // evidence stream is OteAgreementStats + its store).
    private final AtomicLong evaluations = new AtomicLong();
    private final AtomicLong wouldBlock = new AtomicLong();
    private final AtomicLong blocked = new AtomicLong();
    private final AtomicLong abstains = new AtomicLong();
    private volatile String lastToken = "?";
    private volatile boolean lastGateBlocked;

    /** Direct construction with explicit config (tests). */
    public Ote30mConfluenceGate(String symbol, Mode mode, boolean acceptArmed) {
        this.symbol = symbol;
        this.mode = mode;
        this.acceptArmed = acceptArmed;
    }

    /** Wire the observability ChartEngine (null tolerated → ABSTAIN). */
    public void setChartEngine(ChartEngine engine) {
        this.zoneSource = (engine == null) ? null
                : () -> engine.getActiveOteZone(symbol);
    }

    /** Test seam: fabricate the zone the gate would read from the chart. */
    void setZoneSource(java.util.function.Supplier<Optional<OteZoneSnapshot>> source) {
        this.zoneSource = source;
    }

    public Mode mode() {
        return mode;
    }

    /** Evaluation count — the OFF-mode no-invocation assertion hook. */
    public long evaluationCount() {
        return evaluations.get();
    }

    /** The M7b check, run by the validator between M7 and M8. */
    public Decision gateCheck(boolean bullish) {
        if (mode == Mode.OFF) {
            return new Decision(true, "OFF");
        }
        evaluations.incrementAndGet();
        java.util.function.Supplier<Optional<OteZoneSnapshot>> src = zoneSource;
        Optional<OteZoneSnapshot> zone =
                (src == null) ? Optional.empty() : src.get();
        if (zone.isEmpty()) {
            abstains.incrementAndGet();
            lastToken = "ABSTAIN(no-zone)";
            lastGateBlocked = false;
            System.out.println("[OTE30M " + symbol + "] ABSTAIN no-zone — gate passes");
            return new Decision(true, "ABSTAIN no-zone");
        }
        OteZoneSnapshot z = zone.get();
        boolean confluent = z.bullish() == bullish
                && (z.state() == OteState.REACTED
                        || (acceptArmed && z.state() == OteState.ARMED));
        String zoneDesc = z.state() + (z.bullish() ? "/BULL" : "/BEAR");
        if (confluent) {
            lastToken = "CONFLUENT(" + zoneDesc + ")";
            lastGateBlocked = false;
            return new Decision(true, "30m zone " + zoneDesc);
        }
        if (mode == Mode.LOG) {
            wouldBlock.incrementAndGet();
            lastToken = "WOULD-BLOCK(" + zoneDesc + ")";
            lastGateBlocked = false;
            System.out.println("[OTE30M " + symbol + "] WOULD-BLOCK "
                    + (bullish ? "LONG" : "SHORT") + " — zone=" + zoneDesc);
            return new Decision(true, "LOG: would block (" + zoneDesc + ")");
        }
        blocked.incrementAndGet();
        lastToken = "BLOCKED(" + zoneDesc + ")";
        lastGateBlocked = true;
        System.out.println("[OTE30M " + symbol + "] BLOCK "
                + (bullish ? "LONG" : "SHORT") + " — zone=" + zoneDesc);
        return new Decision(false, "30m OTE zone " + zoneDesc
                + " is not REACTED confluence for this direction");
    }

    /** Compact token for the [GATES] line. */
    public String gatesToken() {
        if (mode == Mode.OFF) return "m7b=OFF";
        return "m7b=" + lastToken;
    }

    /** JSON-friendly snapshot for /api/setup (session-scoped counters). */
    public Map<String, Object> toApiMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", mode.name());
        m.put("acceptArmed", acceptArmed);
        m.put("gatePassing", !lastGateBlocked);
        m.put("wouldBlock", wouldBlock.get());
        m.put("blocked", blocked.get());
        m.put("abstains", abstains.get());
        return m;
    }

    // Test hooks.
    long wouldBlockCount() { return wouldBlock.get(); }
    long blockedCount()    { return blocked.get(); }
    long abstainCount()    { return abstains.get(); }
}
