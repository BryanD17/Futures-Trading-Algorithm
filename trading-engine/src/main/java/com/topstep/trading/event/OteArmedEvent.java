package com.topstep.trading.event;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * V5 Agent 04 — published when a setup ARMS its OTE: after the MSS, price
 * traded into the [0.618, 0.786] band of the anchored leg for the first time.
 *
 * <p>{@code candleTime} is the candle that armed (candle time, never wall
 * clock). Subscribe with {@code bus.subscribe(EventType.OTE_ARMED, ...)}.
 */
public class OteArmedEvent extends BaseEvent {

    private final String symbol;
    private final Instant candleTime;
    private final boolean bullish;
    private final String anchorMode;
    private final double legLow;
    private final double legHigh;
    private final double f618;
    private final double f705;
    private final double f786;
    private final double eq50;
    private final double touchPrice;

    public OteArmedEvent(String symbol, Instant candleTime, boolean bullish, String anchorMode,
                         double legLow, double legHigh, double f618, double f705, double f786,
                         double eq50, double touchPrice) {
        super(EventType.OTE_ARMED);
        this.symbol = symbol;
        this.candleTime = candleTime;
        this.bullish = bullish;
        this.anchorMode = anchorMode;
        this.legLow = legLow;
        this.legHigh = legHigh;
        this.f618 = f618;
        this.f705 = f705;
        this.f786 = f786;
        this.eq50 = eq50;
        this.touchPrice = touchPrice;
    }

    public String getSymbol() { return symbol; }
    public Instant getCandleTime() { return candleTime; }
    public boolean isBullish() { return bullish; }
    public String getAnchorMode() { return anchorMode; }
    public double getLegLow() { return legLow; }
    public double getLegHigh() { return legHigh; }
    public double getF618() { return f618; }
    public double getF705() { return f705; }
    public double getF786() { return f786; }
    public double getEq50() { return eq50; }
    public double getTouchPrice() { return touchPrice; }

    /** JSON-ready view (for /api/setup and the dashboard). */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("event", "OTE_ARMED");
        m.put("symbol", symbol);
        m.put("candleTime", String.valueOf(candleTime));
        m.put("direction", bullish ? "LONG" : "SHORT");
        m.put("anchorMode", anchorMode);
        m.put("legLow", legLow);
        m.put("legHigh", legHigh);
        m.put("f618", f618);
        m.put("f705", f705);
        m.put("f786", f786);
        m.put("eq50", eq50);
        m.put("touchPrice", touchPrice);
        return m;
    }

    @Override
    public String toString() {
        return "OteArmedEvent" + toMap();
    }
}
