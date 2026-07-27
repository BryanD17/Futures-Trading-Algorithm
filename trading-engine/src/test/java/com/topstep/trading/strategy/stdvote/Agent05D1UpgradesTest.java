package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.chartstate.CandleSeries;
import com.topstep.trading.chartstate.KnownLevel;
import com.topstep.trading.chartstate.LevelEngine;
import com.topstep.trading.chartstate.LevelType;
import com.topstep.trading.domain.Candle;
import com.topstep.trading.strategy.DailyAmdCycleTracker.DailyPhase;
import com.topstep.trading.strategy.HtfTrendAnalyzer.HtfTrendState;
import com.topstep.trading.strategy.stdvote.BiasVoteEngine.BiasVoteResult;
import com.topstep.trading.strategy.stdvote.BiasVoteEngine.VoteDirection;
import com.topstep.trading.strategy.stdvote.BiasVoteEngine.VoteInputs;
import com.topstep.trading.strategy.stdvote.BiasVoteEngine.VoteMode;
import com.topstep.trading.strategy.stdvote.PremiumDiscountEvaluator.PdContext;
import com.topstep.trading.strategy.stdvote.PremiumDiscountEvaluator.PdMode;
import com.topstep.trading.strategy.stdvote.PremiumDiscountEvaluator.PdVerdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V3 Agent 05 — R0 (D1 dealing range) for the premium/discount gate and
 * the D1-aware vote context. The load-bearing property: with an EMPTY D1
 * source everything behaves exactly as Agents 02/03 shipped it (their
 * suites run unchanged in the same build as the fallback proof).
 */
@DisplayName("Agent 05: R0 D1 dealing range + D1-aware votes")
class Agent05D1UpgradesTest {

    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final double TICK = 0.25;
    private static final Instant TS = Instant.parse("2026-06-25T14:00:00Z");

    private static Instant day(int idx) {
        return ZonedDateTime.of(2026, 6, 1, 18, 0, 0, 0, ET)
                .plusDays(idx).toInstant();
    }

    /** 12 D1 bars with ONE established swing high (21000) and low (19000). */
    private static List<Candle> d1WithSwings() {
        double[] highs = {20005, 20010, 21000, 20010, 20005, 20003,
                          20002, 20001, 20000, 20002, 20003, 20004};
        double[] lows =  {19995, 19990, 19985, 19980, 19970, 19960,
                          19000, 19960, 19970, 19980, 19985, 19990};
        List<Candle> out = new ArrayList<>();
        for (int i = 0; i < highs.length; i++) {
            out.add(new Candle("MNQ", day(i), (highs[i] + lows[i]) / 2,
                    highs[i], lows[i], (highs[i] + lows[i]) / 2, 1000));
        }
        return out;
    }

    private static LevelEngine levelsWithPdRange() {
        CandleSeries series = new CandleSeries("MNQ", 1000);
        LevelEngine levels = new LevelEngine("MNQ", series);
        levels.processCandle(new Candle("MNQ",
                ZonedDateTime.of(2026, 6, 24, 10, 0, 0, 0, ET).toInstant(),
                20000, 20100, 19900, 20000, 100));
        levels.processCandle(new Candle("MNQ",
                ZonedDateTime.of(2026, 6, 25, 9, 0, 0, 0, ET).toInstant(),
                20000, 20010, 19990, 20000, 100));
        return levels;
    }

    // ── R0 ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("R0 governs once D1 is deep enough; eq from the last established swings")
    void r0Governs() {
        PremiumDiscountEvaluator pd = new PremiumDiscountEvaluator(
                "MNQ", TICK, levelsWithPdRange(), PdMode.BLOCK, 2, 100);
        pd.configureD1Source(Agent05D1UpgradesTest::d1WithSwings, 10);
        PdContext ctx = pd.classify(20500.0);
        assertThat(ctx.rangeSource()).isEqualTo("R0-D1");
        assertThat(ctx.rangeHigh()).isEqualTo(21000.0);
        assertThat(ctx.rangeLow()).isEqualTo(19000.0);
        assertThat(ctx.equilibrium()).isEqualTo(20000.0);
        assertThat(ctx.verdict()).isEqualTo(PdVerdict.PREMIUM);
        assertThat(pd.classify(19500.0).verdict()).isEqualTo(PdVerdict.DISCOUNT);
        // rangeSource telemetry counts R0-D1 on gate evaluations.
        pd.gateCheck(20500.0, true);
        @SuppressWarnings("unchecked")
        var byRange = (java.util.Map<String, Long>) pd.toApiMap().get("verdictsByRangeSource");
        assertThat(byRange).containsKey("R0-D1");
    }

    @Test
    @DisplayName("below pd.d1MinBars the chain falls through to R1 unchanged")
    void thinD1FallsThrough() {
        PremiumDiscountEvaluator pd = new PremiumDiscountEvaluator(
                "MNQ", TICK, levelsWithPdRange(), PdMode.LOG, 2, 100);
        pd.configureD1Source(() -> d1WithSwings().subList(0, 8), 10);
        PdContext ctx = pd.classify(20050.0);
        assertThat(ctx.rangeSource()).isEqualTo("R1");
        assertThat(ctx.equilibrium()).isEqualTo(20000.0); // PDH/PDL midpoint
    }

    @Test
    @DisplayName("EMPTY D1 source (pre-Agent-05 construction) is byte-identical R1/R2/R3")
    void emptyD1IsPreAgent05() {
        PremiumDiscountEvaluator pd = new PremiumDiscountEvaluator(
                "MNQ", TICK, levelsWithPdRange(), PdMode.LOG, 2, 100);
        // No configureD1Source call at all — the Agent 02 construction.
        assertThat(pd.classify(20050.0).rangeSource()).isEqualTo("R1");
        assertThat(pd.classify(20050.0).verdict()).isEqualTo(PdVerdict.PREMIUM);
    }

    // ── V1 H4 consult (default OFF) ──────────────────────────────────────

    /** Bearish H4 zigzag: swing highs 110→107 (LH), lows 100→97 (LL). */
    private static List<Candle> bearishH4() {
        double[] path = {104, 107, 110, 107, 104, 100, 103, 105,
                         107, 105, 101, 97, 99, 101, 103};
        List<Candle> out = new ArrayList<>();
        for (int i = 0; i < path.length; i++) {
            out.add(new Candle("MNQ", TS.plusSeconds(i * 14400L),
                    path[i], path[i] + 0.5, path[i] - 0.5, path[i], 1000));
        }
        return out;
    }

    @Test
    @DisplayName("bias.v1.includeH4=true: H4 structure conflict demotes V1 to ABSTAIN")
    void includeH4Conflict() {
        VoteInputs in = new VoteInputs(HtfTrendState.STRONG_BULLISH,
                DailyPhase.ACCUMULATION, Optional.empty(), 105,
                Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), bearishH4());

        // DEFAULT (false): V1 unchanged — the flag is measure-first.
        BiasVoteEngine off = new BiasVoteEngine("MNQ", TICK, VoteMode.LOG, 2);
        BiasVoteResult offResult = off.evaluate(in,
                com.topstep.trading.strategy.MarketBias.NEUTRAL);
        assertThat(offResult.votes().get(0).direction()).isEqualTo(VoteDirection.BULL);

        // Enabled: bearish H4 fractal structure contradicts -> ABSTAIN.
        BiasVoteEngine on = new BiasVoteEngine("MNQ", TICK, VoteMode.LOG, 2);
        on.configureIncludeH4(true);
        BiasVoteResult onResult = on.evaluate(in,
                com.topstep.trading.strategy.MarketBias.NEUTRAL);
        assertThat(onResult.votes().get(0).direction()).isEqualTo(VoteDirection.ABSTAIN);
        assertThat(onResult.votes().get(0).detail()).startsWith("h4-conflict");
        // Aligned H4 (or neutral) leaves V1 alone.
        assertThat(FractalSwings.direction(bearishH4(), 2)).isEqualTo(-1);
    }

    // ── V4 weekly context (detail only) ──────────────────────────────────

    @Test
    @DisplayName("PWH/PWL tapped state rides V4's DETAIL string; vote unchanged")
    void weeklyDetailOnly() {
        KnownLevel pdh = new KnownLevel(LevelType.PDH, 20100, TS);
        KnownLevel pdlTapped = new KnownLevel(LevelType.PDL, 19900, TS);
        pdlTapped.markRaided(TS);
        KnownLevel pwh = new KnownLevel(LevelType.PWH, 20400, TS);
        KnownLevel pwlTapped = new KnownLevel(LevelType.PWL, 19600, TS);
        pwlTapped.markRaided(TS);

        VoteInputs in = new VoteInputs(HtfTrendState.RANGING,
                DailyPhase.ACCUMULATION, Optional.empty(), 20000,
                Optional.of(pdh), Optional.of(pdlTapped),
                Optional.of(pwh), Optional.of(pwlTapped), List.of());
        BiasVoteEngine engine = new BiasVoteEngine("MNQ", TICK, VoteMode.LOG, 2);
        BiasVoteResult r = engine.evaluate(in,
                com.topstep.trading.strategy.MarketBias.NEUTRAL);
        var v4 = r.votes().get(3);
        // Direction from the DAILY levels exactly as Agent 03 shipped it.
        assertThat(v4.direction()).isEqualTo(VoteDirection.BULL);
        assertThat(v4.detail()).contains("PDH-untapped")
                .contains(",wk:PWH-untapped/PWL-tapped");
    }
}
