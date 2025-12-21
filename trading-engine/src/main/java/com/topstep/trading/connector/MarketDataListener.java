package com.topstep.trading.connector;

import com.topstep.trading.domain.Candle;

/**
 * Listener interface for market data updates.
 *
 * This interface is designed to be functional when using only the onCandle method,
 * allowing method references for simple candle-only listeners.
 */
@FunctionalInterface
public interface MarketDataListener {

    /**
     * Called when a new candle is received.
     * This is the primary method for functional interface usage.
     */
    void onCandle(Candle candle);

    /**
     * Called when a tick/quote is received.
     */
    default void onTick(String symbol, double bid, double ask, double last) {
        // Default no-op
    }

    /**
     * Called when market data connection error occurs.
     */
    default void onError(String symbol, Exception error) {
        // Default no-op - just log
        System.err.println("Market data error for " + symbol + ": " + error.getMessage());
    }
}
