package com.topstep.trading.trade;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A profile was SATISFIED while the ACTIVE profile did not emit (V4 Agent 08).
 *
 * <p>This is the actionable record the whole document is aimed at. It answers
 * "what would STANDARD have taken today, and what stopped the engine from
 * taking it" with a timestamp, a direction, a price and a NAMED list of
 * blocking gates — not an impression.
 *
 * @param blockingGatesOfActiveProfile every requirement the ACTIVE profile
 *        failed, not just the first. Ranking these by frequency across a week
 *        is what turns "it does not trade" into "M4 blocks 61% of setups".
 */
public record WouldTradeEvent(
        Instant timestamp,
        String symbol,
        boolean bullish,
        TradeProfile profile,
        TradeProfile activeProfile,
        List<String> blockingGatesOfActiveProfile,
        double entry,
        double stop,
        double target,
        double rr,
        int sizeRequest
) {

    public WouldTradeEvent {
        blockingGatesOfActiveProfile = List.copyOf(blockingGatesOfActiveProfile);
    }

    public String direction() {
        return bullish ? "LONG" : "SHORT";
    }

    /** JSON-friendly map — also the JSONL persistence shape. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("timestamp", timestamp == null ? null : timestamp.toString());
        m.put("symbol", symbol);
        m.put("direction", direction());
        m.put("profile", profile.name());
        m.put("activeProfile", activeProfile.name());
        m.put("blockingGates", blockingGatesOfActiveProfile);
        m.put("entry", entry);
        m.put("stop", stop);
        m.put("target", target);
        m.put("rr", rr);
        m.put("sizeRequest", sizeRequest);
        return m;
    }
}
