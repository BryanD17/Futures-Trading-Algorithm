package com.topstep.trading.event;

import java.time.Instant;
import java.util.Objects;

/**
 * V5 Agent 05.3 — published by the strategy when the setup that EMITTED an
 * entry signal leaves {@code IN_TRADE} without (or before) a position: it was
 * INVALIDATED / EXPIRED for any reason, or the machine re-armed. Consumers
 * cancel the still-unfilled entry order for the symbol:
 *
 * <ul>
 *   <li>SIM / backtest / autopsy: {@code ExecutionEngine} (simulation enabled)
 *       removes the resting entry ({@code removeOrder});</li>
 *   <li>LIVE: {@code LiveEngineRunner} cancels it at the broker
 *       ({@code connector.cancelOrder}).</li>
 * </ul>
 *
 * Each consumer publishes a {@link GateDecisionEvent}
 * {@code "ORDER: cancelled — setup <reason>"} for every order it cancels. A
 * consumer with no resting entry for the symbol does nothing (an already
 * filled entry is a position, never cancelled here). The SIM order TTL
 * ({@code order.ttlBars}) stays as the backstop.
 *
 * <p>No PositionClosedEvent is published for these cancels: the strategy has
 * already released its own latch when it published this event.
 */
public class SetupCancelledEvent extends BaseEvent {

    private final String symbol;
    private final String reason;
    private final Instant candleTime;

    public SetupCancelledEvent(String symbol, String reason, Instant candleTime) {
        super(EventType.SETUP_CANCELLED);
        this.symbol = Objects.requireNonNull(symbol, "symbol must not be null");
        this.reason = reason == null ? "invalidated" : reason;
        this.candleTime = candleTime != null ? candleTime : getTimestamp();
    }

    public String getSymbol() { return symbol; }

    /** Why the emitting setup ended (invalidation reason, or "re-armed"). */
    public String getReason() { return reason; }

    /** Candle time of the bar on which the setup ended. Never null. */
    public Instant getCandleTime() { return candleTime; }

    @Override
    public String toString() {
        return "SetupCancelledEvent{symbol=" + symbol + ", reason=" + reason + ", candleTime=" + candleTime + "}";
    }
}
