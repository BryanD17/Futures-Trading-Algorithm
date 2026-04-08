package com.topstep.trading.strategy;

/**
 * Listener interface for swing point events from IctStructureDetector.
 *
 * Decouples structure detection from statistical analysis components.
 * Implementations receive callbacks when pullbacks and impulses complete,
 * enabling retrospective analysis without look-ahead bias.
 */
public interface SwingPointListener {

    /**
     * Called when a pullback within a trend completes (trend resumes).
     *
     * @param impulseRange   Size of the impulse move before the pullback (points)
     * @param pullbackRange  Size of the pullback retracement (points)
     * @param durationBars   Number of 1m candles the pullback lasted
     * @param continued      Whether price resumed the trend after the pullback
     */
    void onPullbackCompleted(double impulseRange, double pullbackRange,
                              int durationBars, boolean continued);

    /**
     * Called when an impulse move completes (new swing high in uptrend,
     * new swing low in downtrend).
     *
     * @param impulseSize Size of the impulse in points (always positive)
     * @param bullish     true if bullish impulse (upward), false if bearish
     */
    void onImpulseCompleted(double impulseSize, boolean bullish);
}
