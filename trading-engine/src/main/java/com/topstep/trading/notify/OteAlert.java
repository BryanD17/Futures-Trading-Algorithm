package com.topstep.trading.notify;

import java.time.Instant;

/**
 * A single publishable OTE event, flattened away from the engine's internal
 * models.
 *
 * <p>WHY a separate DTO rather than passing {@code OteZoneSnapshot} straight to
 * the formatter: the notification layer should not be coupled to the shape of
 * the chart engine. When {@code OteZoneSnapshot} gains its next field the
 * formatter and its tests stay untouched, and the formatter can be tested
 * without constructing chart state. The adapter that performs the mapping is
 * the only class that knows both worlds.
 *
 * @param kind          which lifecycle transition triggered this
 * @param symbol        instrument, e.g. "NQ"
 * @param bullish       true when the setup expects upside
 * @param zoneNear      the 0.62 edge
 * @param zoneSweet     the 0.705 entry
 * @param zoneFar       the 0.79 edge
 * @param invalidation  protective stop level, just beyond the leg origin
 * @param target        first natural target, the leg extreme
 * @param raidScore     quality of the raid that anchored the leg, or null
 * @param raidLevel     which level was raided, e.g. "PDL", or null
 * @param session       "London", "NY AM", etc., or null
 * @param decimals      price decimal places for this instrument
 * @param occurredAt    event time
 */
public record OteAlert(
        Kind kind,
        String symbol,
        boolean bullish,
        double zoneNear,
        double zoneSweet,
        double zoneFar,
        double invalidation,
        double target,
        Integer raidScore,
        String raidLevel,
        String session,
        int decimals,
        Instant occurredAt
) {

    public enum Kind {
        /** Price has traded into the OTE band. The actionable one. */
        ARMED,
        /** Price rejected back out of the band toward the extreme. */
        REACTED,
        /** Price closed beyond the leg origin. The setup is dead. */
        INVALIDATED
    }

    public OteAlert {
        if (kind == null) throw new IllegalArgumentException("kind required");
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol required");
        }
        if (occurredAt == null) throw new IllegalArgumentException("occurredAt required");
        if (decimals < 0 || decimals > 8) {
            throw new IllegalArgumentException("decimals out of range: " + decimals);
        }
    }

    /**
     * Reward-to-risk from the sweet spot, or NaN when the geometry is degenerate.
     *
     * <p>Reported rather than assumed: if this comes back under 2 the setup is
     * usually not worth posting, and the publisher can gate on it.
     */
    public double riskReward() {
        double risk = Math.abs(zoneSweet - invalidation);
        double reward = Math.abs(target - zoneSweet);
        if (risk <= 0) return Double.NaN;
        return reward / risk;
    }

    /** Stable identity for a zone, so the same state is never posted twice. */
    public String dedupeKey() {
        return symbol + "|" + kind + "|" + bullish + "|"
                + Math.round(zoneNear * 1e6) + "|" + Math.round(zoneFar * 1e6);
    }
}
