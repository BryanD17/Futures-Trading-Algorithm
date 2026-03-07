package com.topstep.trading.chartstate;

/**
 * Listener interface for zone flip events.
 *
 * A zone flip occurs when a bullish demand level (e.g., Asia Low, Session Low)
 * gets broken by a displacement candle, transforming it into a bearish breaker block,
 * or vice versa.
 */
@FunctionalInterface
public interface ZoneFlipListener {

    /**
     * Called when a known level flips its polarity.
     *
     * @param level The level that was flipped
     * @param isNowBullish true if the level flipped to bullish (broken upward),
     *                     false if it flipped to bearish (broken downward)
     */
    void onZoneFlip(KnownLevel level, boolean isNowBullish);
}
