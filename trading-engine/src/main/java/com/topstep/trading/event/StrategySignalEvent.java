package com.topstep.trading.event;

import com.topstep.trading.domain.OrderSide;
import java.util.Objects;

/**
 * Event emitted when the strategy generates a trading signal.
 */
public class StrategySignalEvent extends BaseEvent {
    private final SignalType signalType;
    private final String symbol;
    private final OrderSide side;
    private final double entryPrice;
    private final double stopPrice;
    private final double targetPrice;
    private final String reason;

    public StrategySignalEvent(SignalType signalType, String symbol, OrderSide side,
                               double entryPrice, double stopPrice, double targetPrice, String reason) {
        super(EventType.STRATEGY_SIGNAL);
        this.signalType = Objects.requireNonNull(signalType);
        this.symbol = Objects.requireNonNull(symbol);
        this.side = side;
        this.entryPrice = entryPrice;
        this.stopPrice = stopPrice;
        this.targetPrice = targetPrice;
        this.reason = reason;
    }

    public SignalType getSignalType() { return signalType; }
    public String getSymbol() { return symbol; }
    public OrderSide getSide() { return side; }
    public double getEntryPrice() { return entryPrice; }
    public double getStopPrice() { return stopPrice; }
    public double getTargetPrice() { return targetPrice; }
    public String getReason() { return reason; }

    @Override
    public String toString() {
        return String.format("StrategySignalEvent{type=%s, symbol='%s', side=%s, entry=%.2f, stop=%.2f, target=%.2f, reason='%s'}",
                signalType, symbol, side, entryPrice, stopPrice, targetPrice, reason);
    }

    public enum SignalType {
        LONG_ENTRY,
        SHORT_ENTRY,
        SCALE_IN,
        SCALE_OUT,
        EXIT
    }
}
