package com.topstep.trading.news.events;

import com.topstep.trading.event.BaseEvent;
import com.topstep.trading.event.EventType;

/**
 * Published when macro bias changes for an instrument.
 * Subscribers can use this to update displays or adjust trading.
 */
public class MacroBiasUpdateEvent extends BaseEvent {
    private final String instrument;
    private final double newBias;

    public MacroBiasUpdateEvent(String instrument, double newBias) {
        super(EventType.MACRO_BIAS_UPDATE);
        this.instrument = instrument;
        this.newBias = newBias;
    }

    /**
     * Get the affected instrument.
     */
    public String getInstrument() {
        return instrument;
    }

    /**
     * Get the new bias value (-1.0 to +1.0).
     */
    public double getNewBias() {
        return newBias;
    }

    /**
     * Check if the bias is bullish.
     */
    public boolean isBullish() {
        return newBias > 0.1;
    }

    /**
     * Check if the bias is bearish.
     */
    public boolean isBearish() {
        return newBias < -0.1;
    }

    /**
     * Check if the bias is neutral.
     */
    public boolean isNeutral() {
        return Math.abs(newBias) <= 0.1;
    }

    /**
     * Get a description of the bias direction.
     */
    public String getBiasDirection() {
        if (newBias > 0.5) return "STRONGLY BULLISH";
        if (newBias > 0.2) return "BULLISH";
        if (newBias > 0.1) return "SLIGHTLY BULLISH";
        if (newBias < -0.5) return "STRONGLY BEARISH";
        if (newBias < -0.2) return "BEARISH";
        if (newBias < -0.1) return "SLIGHTLY BEARISH";
        return "NEUTRAL";
    }

    @Override
    public String toString() {
        return String.format("MacroBiasUpdateEvent{instrument='%s', bias=%.3f (%s)}",
                instrument, newBias, getBiasDirection());
    }
}
