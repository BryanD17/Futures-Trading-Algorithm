package com.topstep.trading.lifecycle;

import java.time.Instant;

/**
 * Event emitted when the account transitions between phases.
 * Consumed by downstream systems (risk engine, strategy, dashboard).
 */
public class PhaseTransitionEvent {

    private final AccountPhase fromPhase;
    private final AccountPhase toPhase;
    private final String reason;
    private final Instant timestamp;
    private final double equityAtTransition;
    private final int tradingDaysElapsed;

    public PhaseTransitionEvent(AccountPhase fromPhase, AccountPhase toPhase,
                                 String reason, double equityAtTransition,
                                 int tradingDaysElapsed) {
        this.fromPhase = fromPhase;
        this.toPhase = toPhase;
        this.reason = reason;
        this.timestamp = Instant.now();
        this.equityAtTransition = equityAtTransition;
        this.tradingDaysElapsed = tradingDaysElapsed;
    }

    public AccountPhase getFromPhase() { return fromPhase; }
    public AccountPhase getToPhase() { return toPhase; }
    public String getReason() { return reason; }
    public Instant getTimestamp() { return timestamp; }
    public double getEquityAtTransition() { return equityAtTransition; }
    public int getTradingDaysElapsed() { return tradingDaysElapsed; }

    @Override
    public String toString() {
        return String.format("PhaseTransition{%s -> %s, reason='%s', equity=%.2f, days=%d}",
                fromPhase, toPhase, reason, equityAtTransition, tradingDaysElapsed);
    }
}
