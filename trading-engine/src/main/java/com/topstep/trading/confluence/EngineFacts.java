package com.topstep.trading.confluence;

import com.topstep.trading.strategy.MarketBias;

import java.time.Instant;

/**
 * The engine-side facts the strategy PUBLISHES for the confluence stack
 * (V4 Agent 07).
 *
 * <p>Every field is nullable, and null means exactly one thing: the owning
 * source could not answer, so the field is {@link Tri#UNKNOWN}. That is the
 * ABSTAIN doctrine expressed in a type — there is no way to accidentally
 * report a cold source as a negative.
 *
 * <p>This record exists so {@code ConfluenceService} can aggregate WITHOUT
 * reaching into the strategy's internals. The strategy already computes each
 * of these for its own gates; it hands over the answers it already has, and
 * the service recomputes none of them (B13).
 *
 * @param at                 when the facts were published (candle time)
 * @param price              the price they were published at
 * @param inTradingKillzone  KillzoneClock — the ONLY session window that gates
 * @param legacyBias         HtfTrendAnalyzer-derived bias
 * @param voteBias           the V3 3-of-4 bias vote result, if it is running
 * @param voteDetail         the vote's own rollup token, verbatim
 * @param pdAligned          M2b premium/discount verdict for the planned entry
 * @param pdDetail           the evaluator's own reason string, verbatim
 * @param recentSweep        raid pipeline — a liquidity sweep is in play
 * @param raidScore          RaidQualityScorer 0..10
 * @param machineOteState    the stdvote path's own OTE state name
 */
public record EngineFacts(
        Instant at,
        Double price,
        Boolean inTradingKillzone,
        MarketBias legacyBias,
        MarketBias voteBias,
        String voteDetail,
        Boolean pdAligned,
        String pdDetail,
        Boolean recentSweep,
        Integer raidScore,
        String machineOteState
) {

    /** Everything unknown — what a symbol reads as before the engine warms. */
    public static EngineFacts cold(Instant at) {
        return new EngineFacts(at, null, null, null, null, null,
                null, null, null, null, null);
    }
}
