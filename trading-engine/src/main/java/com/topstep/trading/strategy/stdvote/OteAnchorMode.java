package com.topstep.trading.strategy.stdvote;

/**
 * Which leg the engine's M7 OTE fibs are drawn on (V5 Agent 04, RC-11).
 *
 * <ul>
 *   <li>{@link #DEALING_RANGE} (default) — the dealing range the owner draws:
 *       Agent 03's {@code SetupContext.rangeHigh/rangeLow} when present, else
 *       {@link OteAnchorRangeTracker}'s session impulse leg (shorts: session HH →
 *       lowest low after it; longs: session LL → highest high after it).</li>
 *   <li>{@link #TRADING_DAY} — same rule over the whole CME trading day
 *       (18:00 ET roll) instead of the current session.</li>
 *   <li>{@link #IMPULSE} — the pre-V5 anchor: the post-sweep impulse from the
 *       sweep extreme to the MSS candle's extreme.</li>
 * </ul>
 */
public enum OteAnchorMode {
    DEALING_RANGE,
    TRADING_DAY,
    IMPULSE;

    public static OteAnchorMode parse(String raw) {
        if (raw == null || raw.isBlank()) return DEALING_RANGE;
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            System.out.println("[OteConfig] WARN: invalid ote.anchorMode='" + raw
                    + "', using DEALING_RANGE");
            return DEALING_RANGE;
        }
    }
}
