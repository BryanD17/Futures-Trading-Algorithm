package com.topstep.trading.strategy.session;

/**
 * How the M3 session gate behaves ({@code session.gateMode}).
 *
 * <ul>
 *   <li>{@link #SCORING} (DEFAULT): M3 passes in every window except
 *       {@link SessionWindow#NO_ENTRY} / {@link SessionWindow#WEEKEND};
 *       the window and the prime-killzone flag are recorded on the setup and
 *       raise tier (O1) / size (killzone boost) instead of blocking. Re-arm
 *       needs no killzone. Setup expiry is measured from SWEEP_DONE.</li>
 *   <li>{@link #BLOCKING}: the complete pre-V5 time domain, kept for A/B:
 *       M3 requires the legacy killzone (NY KZ ∪ Silver Bullet, MGC London;
 *       scalp windows in scalp mode), re-arm requires an open killzone and
 *       expiry counts from BIAS_SET. NO_ENTRY / WEEKEND still block M3.</li>
 * </ul>
 */
public enum SessionGateMode {
    BLOCKING,
    SCORING;

    /** Lenient parse: null/blank/unknown → {@link #SCORING} (the default). */
    public static SessionGateMode parse(String raw) {
        if (raw == null || raw.isBlank()) return SCORING;
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            System.out.println("[SESSION] unknown session.gateMode '" + raw
                    + "' — using SCORING");
            return SCORING;
        }
    }
}
