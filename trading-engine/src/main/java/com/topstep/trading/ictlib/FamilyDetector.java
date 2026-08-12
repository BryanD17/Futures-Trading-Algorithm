package com.topstep.trading.ictlib;

/**
 * One Appendix S family, bound to one (symbol, timeframe).
 *
 * <p>Contract: {@link #onBar} is invoked once per CLOSED candle, after the
 * candle has been pushed into the series, and must
 * <ol>
 *   <li>advance the lifecycles of detections created on EARLIER bars, then</li>
 *   <li>create any detection the current bar completes.</li>
 * </ol>
 * That order is load-bearing: a zone must not be marked TOUCHED by the very
 * bar that created it (Appendix S says "for some later bar j").
 */
public interface FamilyDetector {

    /** The family this detector owns — one owner per family (V4 B13). */
    DetectionType family();

    /**
     * Process the newest closed bar of {@code series}.
     *
     * @param series the shared window for this (symbol, timeframe)
     * @param registry the symbol's bounded detection store
     */
    void onBar(TimeframeSeries series, DetectionRegistry registry);
}
