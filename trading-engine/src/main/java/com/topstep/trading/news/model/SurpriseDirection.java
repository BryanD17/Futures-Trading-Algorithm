package com.topstep.trading.news.model;

/**
 * Direction of economic surprise relative to forecast.
 */
public enum SurpriseDirection {
    BETTER_THAN_EXPECTED,   // Generally bullish for currency
    WORSE_THAN_EXPECTED,    // Generally bearish for currency
    INLINE                  // No significant surprise (within threshold)
}
