package com.topstep.trading.event;

import java.time.Instant;
import java.util.Objects;

/**
 * Event published when a position is FULLY closed (stop, target, or manual
 * flatten). Published at the exact same funnels that call
 * {@code AccountState.recordTradeCompleted}:
 *
 * <ul>
 *   <li>{@code ExecutionEngine.closePosition} — sim/backtest fills;</li>
 *   <li>{@code LiveEngineRunner}'s bracket SL/TP close handlers — live
 *       broker fills (which bypass {@code ExecutionEngine.closePosition}).</li>
 * </ul>
 *
 * <p>The {@link EventType#POSITION_CLOSED} slot and the
 * {@code EventBus.mapClassToEventType} mapping for this class already
 * existed; this class fills the slot. Consumers (e.g. the scalp-mode re-arm
 * logic in {@code StdvOteRunnerStrategy}) subscribe via
 * {@code eventBus.subscribe(PositionClosedEvent.class, handler)}.
 *
 * <p>{@code closedAt} is the market exit time (candle/fill timestamp) when
 * the publisher knows it — deterministic in backtests — falling back to the
 * publish wall-clock otherwise. The inherited {@link BaseEvent#getTimestamp()}
 * is always the publish wall-clock.
 */
public class PositionClosedEvent extends BaseEvent {

    private final String symbol;
    private final double pnl;
    private final boolean win;
    private final Instant closedAt;

    public PositionClosedEvent(String symbol, double pnl, boolean win, Instant closedAt) {
        super(EventType.POSITION_CLOSED);
        this.symbol = Objects.requireNonNull(symbol, "symbol must not be null");
        this.pnl = pnl;
        this.win = win;
        this.closedAt = (closedAt != null) ? closedAt : getTimestamp();
    }

    public String getSymbol() { return symbol; }

    /** Realized PnL of the fully closed position (dollars). */
    public double getPnl() { return pnl; }

    /** True when the closed trade was a win (pnl &gt; 0). */
    public boolean isWin() { return win; }

    /** Market exit time when known; publish time otherwise. Never null. */
    public Instant getClosedAt() { return closedAt; }

    @Override
    public String toString() {
        return String.format("PositionClosedEvent{symbol=%s, pnl=%.2f, win=%s, closedAt=%s}",
                symbol, pnl, win, closedAt);
    }
}
