package com.topstep.trading.strategy;

/**
 * Market bias based on higher timeframe structure analysis.
 */
public enum MarketBias {
    BULLISH,    // Upward trend - look for long entries
    BEARISH,    // Downward trend - look for short entries
    NEUTRAL     // No clear trend - avoid trading
}
