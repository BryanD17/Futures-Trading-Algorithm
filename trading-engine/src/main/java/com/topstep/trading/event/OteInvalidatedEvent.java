package com.topstep.trading.event;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * V5 Agent 04 — published when a setup that had built an OTE zone dies before
 * emitting: price CLOSED beyond the anchored range extreme (the 1.0), the OTE
 * window expired, or any other invalidation after MSS_CONFIRMED.
 *
 * <p>Subscribe with {@code bus.subscribe(EventType.OTE_INVALIDATED, ...)}.
 */
public class OteInvalidatedEvent extends BaseEvent {

    private final String symbol;
    private final Instant candleTime;
    private final boolean bullish;
    private final String reason;
    private final double price;
    private final double rangeExtreme;
    private final double f618;
    private final double f786;

    public OteInvalidatedEvent(String symbol, Instant candleTime, boolean bullish, String reason,
                               double price, double rangeExtreme, double f618, double f786) {
        super(EventType.OTE_INVALIDATED);
        this.symbol = symbol;
        this.candleTime = candleTime;
        this.bullish = bullish;
        this.reason = reason;
        this.price = price;
        this.rangeExtreme = rangeExtreme;
        this.f618 = f618;
        this.f786 = f786;
    }

    public String getSymbol() { return symbol; }
    public Instant getCandleTime() { return candleTime; }
    public boolean isBullish() { return bullish; }
    public String getReason() { return reason; }
    public double getPrice() { return price; }
    public double getRangeExtreme() { return rangeExtreme; }
    public double getF618() { return f618; }
    public double getF786() { return f786; }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("event", "OTE_INVALIDATED");
        m.put("symbol", symbol);
        m.put("candleTime", String.valueOf(candleTime));
        m.put("direction", bullish ? "LONG" : "SHORT");
        m.put("reason", reason);
        m.put("price", price);
        m.put("rangeExtreme", rangeExtreme);
        m.put("f618", f618);
        m.put("f786", f786);
        return m;
    }

    @Override
    public String toString() {
        return "OteInvalidatedEvent" + toMap();
    }
}
