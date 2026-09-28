package com.topstep.trading.event;

import java.time.Instant;

/**
 * AGENT-05 (V5, RC-15): the MARKET time of the candle currently being
 * processed on this thread. Candle dispatchers (the runners' market-data
 * paths and {@code StdvOteMultiInstrumentEngine.dispatchCandle}) set it
 * around strategy dispatch; {@link StrategySignalEvent}'s legacy
 * constructors read it so every signal carries the candle time that
 * produced it without touching the strategy code that builds signals.
 * The warmup guard compares THIS time, never the wall-clock creation stamp.
 */
public final class SignalCandleClock {

    private static final ThreadLocal<Instant> CURRENT = new ThreadLocal<>();

    private SignalCandleClock() {}

    /** Set the candle time for the current thread (null clears). */
    public static void set(Instant candleTime) {
        if (candleTime == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(candleTime);
        }
    }

    /** Candle time of the candle being processed on this thread, or null. */
    public static Instant current() {
        return CURRENT.get();
    }

    /** Clear the current thread's candle time. */
    public static void clear() {
        CURRENT.remove();
    }
}
