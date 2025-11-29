package com.topstep.trading.event;

import com.topstep.trading.domain.Candle;
import java.util.Objects;

/**
 * Event emitted when a new candle is received.
 */
public class CandleEvent extends BaseEvent {
    private final Candle candle;

    public CandleEvent(Candle candle) {
        super(EventType.CANDLE);
        this.candle = Objects.requireNonNull(candle);
    }

    public Candle getCandle() {
        return candle;
    }

    @Override
    public String toString() {
        return String.format("CandleEvent{candle=%s}", candle);
    }
}
