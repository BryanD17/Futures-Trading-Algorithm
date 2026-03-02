package com.topstep.trading.strategy;

import com.topstep.trading.domain.Candle;

/**
 * Interface for trading strategies.
 * Strategies receive candles and generate trading signals via the event bus.
 */
public interface TradingStrategy {

    /**
     * Called when a new candle is received.
     * Strategy should analyze the candle and emit StrategySignalEvent if conditions are met.
     *
     * @param candle The new candle data
     * @param context Context providing account state and positions
     */
    void onCandle(Candle candle, StrategyContext context);

    /**
     * Get the name of this strategy.
     */
    String getName();

    /**
     * Initialize the strategy (called once before trading starts).
     */
    default void initialize() {
        // Optional initialization
    }

    /**
     * Called when the trading session ends.
     *
     * This signals that no more candles will arrive for the current session.
     * Strategies should finalize any in-progress higher-timeframe candles
     * and mark them as partial so downstream analysis can handle them appropriately.
     */
    default void onSessionEnd() {
        // Optional session-end handling
    }

    /**
     * Cleanup and shutdown the strategy (called when trading stops).
     */
    default void shutdown() {
        // Optional cleanup
    }
}
