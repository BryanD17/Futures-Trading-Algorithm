package com.topstep.trading.connector;

import com.topstep.trading.domain.Candle;

/**
 * Listener interface for market data updates.
 */
public interface MarketDataListener {
    /**
     * Called when a new candle is received.
     */
    void onCandle(Candle candle);

    /**
     * Called when a tick/quote is received.
     */
    void onTick(String symbol, double bid, double ask, double last);

    /**
     * Called when market data connection error occurs.
     */
    void onError(String symbol, Exception error);
}
