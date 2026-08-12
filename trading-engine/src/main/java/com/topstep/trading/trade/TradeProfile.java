package com.topstep.trading.trade;

/**
 * A named REQUIRED-CONFLUENCE SET for entries (V4 Agent 08).
 *
 * <p>A profile selects which STRATEGY confluences an entry must show. It can
 * never loosen a RISK control: sizing bounds, DLL/MLL headroom, flatten-by,
 * max contracts and the kill switch are enforced by {@code PropFirmRiskEngine}
 * and the M8/M9 gates, none of which the profile is even passed to. That is
 * not a policy, it is the shape of the code — and it is tested across all
 * three profiles (risk G-R2).
 *
 * <p>Rollout Doctrine: {@link #STRICT} is the DEFAULT and routes through the
 * existing M1..M9 chain unchanged. The owner flips
 * {@code -Dtrade.profile=STANDARD} from evidence, one switch at a time.
 */
public enum TradeProfile {

    /** Today's full mandatory-gate chain, byte-identical. THE DEFAULT. */
    STRICT,

    /**
     * Killzone + bias + a scored sweep + a PD array (FVG or order block) +
     * structure agreement + an OTE band touch. M2b/M8/M9 semantics unchanged.
     */
    STANDARD,

    /**
     * Killzone + sweep + structure shift + OTE band touch, M8/M9 unchanged.
     *
     * <p>NOT a trading mode — a DIAGNOSTIC FLOOR. If MINIMAL would not have
     * traded either, the problem is upstream (warmth, data, sweep detection)
     * and no amount of gate loosening fixes it (Appendix E5).
     */
    MINIMAL;

    /** The property the owner flips. */
    public static final String PROPERTY = "trade.profile";

    /** Resolved active profile; anything unrecognised means STRICT. */
    public static TradeProfile active() {
        return parse(System.getProperty(PROPERTY));
    }

    public static TradeProfile parse(String value) {
        if (value == null) return STRICT;
        return switch (value.trim().toUpperCase()) {
            case "STANDARD" -> STANDARD;
            case "MINIMAL" -> MINIMAL;
            default -> STRICT;
        };
    }
}
