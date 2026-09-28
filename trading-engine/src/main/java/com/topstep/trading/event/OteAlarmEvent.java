package com.topstep.trading.event;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * V5 Agent 04 — published when an ARMED OTE fires its ALARM: a PD array
 * (FVG / OB / IFVG / BREAKER) overlaps the band AND price reacted (closed back
 * toward the trade, or traded to the 0.705 limit). The planned entry / stop /
 * targets are the ones handed to the emission attempt on the same candle.
 *
 * <p>Subscribe with {@code bus.subscribe(EventType.OTE_ALARM, ...)}.
 */
public class OteAlarmEvent extends BaseEvent {

    private final String symbol;
    private final Instant candleTime;
    private final boolean bullish;
    private final String pdKind;
    private final double pdBottom;
    private final double pdTop;
    private final double entry;
    private final double f618;
    private final double f786;
    private final String reaction;

    public OteAlarmEvent(String symbol, Instant candleTime, boolean bullish, String pdKind,
                         double pdBottom, double pdTop, double entry, double f618, double f786,
                         String reaction) {
        super(EventType.OTE_ALARM);
        this.symbol = symbol;
        this.candleTime = candleTime;
        this.bullish = bullish;
        this.pdKind = pdKind;
        this.pdBottom = pdBottom;
        this.pdTop = pdTop;
        this.entry = entry;
        this.f618 = f618;
        this.f786 = f786;
        this.reaction = reaction;
    }

    public String getSymbol() { return symbol; }
    public Instant getCandleTime() { return candleTime; }
    public boolean isBullish() { return bullish; }
    public String getPdKind() { return pdKind; }
    public double getPdBottom() { return pdBottom; }
    public double getPdTop() { return pdTop; }
    public double getEntry() { return entry; }
    public double getF618() { return f618; }
    public double getF786() { return f786; }
    public String getReaction() { return reaction; }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("event", "OTE_ALARM");
        m.put("symbol", symbol);
        m.put("candleTime", String.valueOf(candleTime));
        m.put("direction", bullish ? "LONG" : "SHORT");
        m.put("pdKind", pdKind);
        m.put("pdBottom", pdBottom);
        m.put("pdTop", pdTop);
        m.put("entry", entry);
        m.put("f618", f618);
        m.put("f786", f786);
        m.put("reaction", reaction);
        return m;
    }

    @Override
    public String toString() {
        return "OteAlarmEvent" + toMap();
    }
}
