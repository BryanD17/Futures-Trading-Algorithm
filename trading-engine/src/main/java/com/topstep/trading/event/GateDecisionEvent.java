package com.topstep.trading.event;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runtime gate telemetry (V5 Agent 01, RC-17): ONE record per decision that
 * stopped (or passed) a candle / setup / signal on its way to an order, so the
 * dashboard can answer "which gate killed it" from the running engine rather
 * than from an offline replay.
 *
 * <p>{@code numberA}/{@code numberB} are the two numbers the gate compared
 * (e.g. RR vs floor, size vs minimum, last-candle age vs threshold); their
 * meaning is spelled out in {@code reason}. {@code candleTime} is MARKET time
 * (the candle / signal time), never the publish wall clock.
 *
 * <p>Published through {@link EngineTelemetry#publish(EventBus, GateDecisionEvent)},
 * which also keeps the last {@value EngineTelemetry#RING_CAPACITY} in memory for
 * {@code GET /api/setup}.
 */
public class GateDecisionEvent extends BaseEvent {

    private final String symbol;
    private final Instant candleTime;
    private final String session;
    private final String state;
    private final String gate;
    private final String reason;
    private final double numberA;
    private final double numberB;

    public GateDecisionEvent(String symbol, Instant candleTime, String session, String state,
                             String gate, String reason, double numberA, double numberB) {
        super(EventType.GATE_DECISION);
        this.symbol = symbol;
        this.candleTime = candleTime;
        this.session = session;
        this.state = state;
        this.gate = gate;
        this.reason = reason;
        this.numberA = finite(numberA);
        this.numberB = finite(numberB);
    }

    private static double finite(double d) {
        return Double.isFinite(d) ? d : 0.0;
    }

    public String getSymbol() { return symbol; }
    public Instant getCandleTime() { return candleTime; }
    public String getSession() { return session; }
    public String getState() { return state; }
    public String getGate() { return gate; }
    public String getReason() { return reason; }
    public double getNumberA() { return numberA; }
    public double getNumberB() { return numberB; }

    /** JSON-friendly row for the API. */
    public Map<String, Object> toApiMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("symbol", symbol);
        m.put("candleTime", candleTime == null ? null : candleTime.toString());
        m.put("session", session);
        m.put("state", state);
        m.put("gate", gate);
        m.put("reason", reason);
        m.put("numberA", numberA);
        m.put("numberB", numberB);
        m.put("publishedAt", getTimestamp().toString());
        return m;
    }

    @Override
    public String toString() {
        return "GateDecisionEvent{" + symbol + " " + candleTime + " " + session + " " + state
                + " gate=" + gate + " reason='" + reason + "' a=" + numberA + " b=" + numberB + "}";
    }
}
