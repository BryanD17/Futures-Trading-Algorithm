package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.chartstate.KnownLevel;
import com.topstep.trading.chartstate.LevelType;
import com.topstep.trading.domain.Candle;
import com.topstep.trading.event.EventBus;
import com.topstep.trading.strategy.DailyAmdCycleTracker.DailyPhase;
import com.topstep.trading.strategy.HtfTrendAnalyzer.HtfTrendState;
import com.topstep.trading.strategy.MarketBias;
import com.topstep.trading.strategy.stdvote.BiasVoteEngine.BiasVote;
import com.topstep.trading.strategy.stdvote.BiasVoteEngine.BiasVoteResult;
import com.topstep.trading.strategy.stdvote.BiasVoteEngine.VoteDirection;
import com.topstep.trading.strategy.stdvote.BiasVoteEngine.VoteInputs;
import com.topstep.trading.strategy.stdvote.BiasVoteEngine.VoteMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V3 Agent 03 tests — the 3-of-4 bias vote per STDV_OTE_MODEL.md §H1.
 * Votes are tested in isolation (pure statics), the aggregation table is
 * exercised exhaustively, and the runner-level LEGACY/VOTE seams are proven
 * on a real {@link StdvOteRunnerStrategy} candle feed.
 */
@DisplayName("BiasVoteEngine (3-of-4 per §H1)")
class BiasVoteEngineTest {

    private static final double TICK = 0.25;
    private static final Instant TS = Instant.parse("2026-06-25T14:00:00Z");

    @AfterEach
    void clearModeProperty() {
        System.clearProperty(BiasVoteEngine.MODE_PROPERTY);
    }

    private static BiasVote v(VoteDirection d) {
        return new BiasVote("T", d, "");
    }

    private static KnownLevel level(LevelType type, double price, boolean raided) {
        KnownLevel l = new KnownLevel(type, price, TS);
        if (raided) l.markRaided(TS);
        return l;
    }

    // ── (a) isolated votes ───────────────────────────────────────────────

    @Test
    @DisplayName("a) V1 structure: bullish/bearish states vote, RANGING abstains")
    void v1Paths() {
        assertThat(BiasVoteEngine.voteV1(HtfTrendState.STRONG_BULLISH).direction())
                .isEqualTo(VoteDirection.BULL);
        assertThat(BiasVoteEngine.voteV1(HtfTrendState.WEAK_BULLISH).direction())
                .isEqualTo(VoteDirection.BULL);
        assertThat(BiasVoteEngine.voteV1(HtfTrendState.STRONG_BEARISH).direction())
                .isEqualTo(VoteDirection.BEAR);
        assertThat(BiasVoteEngine.voteV1(HtfTrendState.RANGING).direction())
                .isEqualTo(VoteDirection.ABSTAIN);
        assertThat(BiasVoteEngine.voteV1(null).direction())
                .isEqualTo(VoteDirection.ABSTAIN);
    }

    @Test
    @DisplayName("a) V2 AMD: only distribution phases vote; everything else abstains")
    void v2Paths() {
        assertThat(BiasVoteEngine.voteV2(DailyPhase.DISTRIBUTION_UP).direction())
                .isEqualTo(VoteDirection.BULL);
        assertThat(BiasVoteEngine.voteV2(DailyPhase.DISTRIBUTION_DOWN).direction())
                .isEqualTo(VoteDirection.BEAR);
        for (DailyPhase p : new DailyPhase[] {DailyPhase.ACCUMULATION,
                DailyPhase.MANIPULATION_DOWN, DailyPhase.MANIPULATION_UP,
                DailyPhase.REVERSAL}) {
            assertThat(BiasVoteEngine.voteV2(p).direction())
                    .as("phase %s", p).isEqualTo(VoteDirection.ABSTAIN);
            assertThat(BiasVoteEngine.voteV2(p).detail()).isEqualTo("no-dist-leg");
        }
    }

    @Test
    @DisplayName("a) V3 true day open: below=BULL, above=BEAR, band/absent=ABSTAIN")
    void v3Paths() {
        Optional<Double> open = Optional.of(20000.0);
        assertThat(BiasVoteEngine.voteV3(19990.0, open, TICK, 2).direction())
                .isEqualTo(VoteDirection.BULL);
        assertThat(BiasVoteEngine.voteV3(20010.0, open, TICK, 2).direction())
                .isEqualTo(VoteDirection.BEAR);
        // Band: ±2 ticks = ±0.5 around the open.
        assertThat(BiasVoteEngine.voteV3(20000.5, open, TICK, 2).direction())
                .isEqualTo(VoteDirection.ABSTAIN);
        assertThat(BiasVoteEngine.voteV3(20000.0, Optional.empty(), TICK, 2).direction())
                .isEqualTo(VoteDirection.ABSTAIN);
    }

    @Test
    @DisplayName("a) V4 draw on liquidity: all four cases + equidistant tie")
    void v4Paths() {
        Optional<KnownLevel> pdhUntapped = Optional.of(level(LevelType.PDH, 20100, false));
        Optional<KnownLevel> pdhTapped = Optional.of(level(LevelType.PDH, 20100, true));
        Optional<KnownLevel> pdlUntapped = Optional.of(level(LevelType.PDL, 19900, false));
        Optional<KnownLevel> pdlTapped = Optional.of(level(LevelType.PDL, 19900, true));

        // Exactly one untapped -> draw toward it.
        assertThat(BiasVoteEngine.voteV4(20000, pdhUntapped, pdlTapped, TICK, 2))
                .extracting(BiasVote::direction, BiasVote::detail)
                .containsExactly(VoteDirection.BULL, "PDH-untapped");
        assertThat(BiasVoteEngine.voteV4(20000, pdhTapped, pdlUntapped, TICK, 2))
                .extracting(BiasVote::direction, BiasVote::detail)
                .containsExactly(VoteDirection.BEAR, "PDL-untapped");
        // Both untapped -> the NEARER magnet.
        assertThat(BiasVoteEngine.voteV4(20080, pdhUntapped, pdlUntapped, TICK, 2)
                .direction()).isEqualTo(VoteDirection.BULL);   // 20 vs 180 away
        assertThat(BiasVoteEngine.voteV4(19920, pdhUntapped, pdlUntapped, TICK, 2)
                .direction()).isEqualTo(VoteDirection.BEAR);   // 180 vs 20 away
        // Both untapped, equidistant within the band -> no meaningful magnet.
        assertThat(BiasVoteEngine.voteV4(20000, pdhUntapped, pdlUntapped, TICK, 2))
                .extracting(BiasVote::direction, BiasVote::detail)
                .containsExactly(VoteDirection.ABSTAIN, "equidistant");
        // Both tapped -> range day.
        assertThat(BiasVoteEngine.voteV4(20000, pdhTapped, pdlTapped, TICK, 2)
                .detail()).isEqualTo("both-tapped");
        // Levels absent (cold start).
        assertThat(BiasVoteEngine.voteV4(20000, Optional.empty(), Optional.empty(),
                TICK, 2).detail()).isEqualTo("no-pd-levels");
    }

    // ── (b) aggregation table ────────────────────────────────────────────

    @Test
    @DisplayName("b) aggregation: 4-0, 3-1, 2-2, 3+1abs, 2abs -> per §H1")
    void aggregationTable() {
        assertThat(BiasVoteEngine.aggregate(List.of(
                v(VoteDirection.BULL), v(VoteDirection.BULL),
                v(VoteDirection.BULL), v(VoteDirection.BULL))).finalBias())
                .isEqualTo(MarketBias.BULLISH);
        assertThat(BiasVoteEngine.aggregate(List.of(
                v(VoteDirection.BEAR), v(VoteDirection.BEAR),
                v(VoteDirection.BEAR), v(VoteDirection.BULL))).finalBias())
                .isEqualTo(MarketBias.BEARISH);
        assertThat(BiasVoteEngine.aggregate(List.of(
                v(VoteDirection.BULL), v(VoteDirection.BULL),
                v(VoteDirection.BEAR), v(VoteDirection.BEAR))).finalBias())
                .isEqualTo(MarketBias.NEUTRAL);
        // 3 aligned with 1 abstention is still directional.
        BiasVoteResult withAbstain = BiasVoteEngine.aggregate(List.of(
                v(VoteDirection.BULL), v(VoteDirection.BULL),
                v(VoteDirection.BULL), v(VoteDirection.ABSTAIN)));
        assertThat(withAbstain.finalBias()).isEqualTo(MarketBias.BULLISH);
        assertThat(withAbstain.abstains()).isEqualTo(1);
        // 2+ abstentions make 3-of-4 impossible -> NEUTRAL by construction.
        assertThat(BiasVoteEngine.aggregate(List.of(
                v(VoteDirection.BULL), v(VoteDirection.BULL),
                v(VoteDirection.ABSTAIN), v(VoteDirection.ABSTAIN))).finalBias())
                .isEqualTo(MarketBias.NEUTRAL);
    }

    // ── (c) LEGACY mode: engine never evaluated on the live feed ─────────

    @Test
    @DisplayName("c) LEGACY: runner never invokes the engine (byte-identical path)")
    void legacyModeNoInvocation() {
        System.setProperty(BiasVoteEngine.MODE_PROPERTY, "LEGACY");
        StdvOteRunnerStrategy s = new StdvOteRunnerStrategy("MNQ", "MES", new EventBus());
        BiasVoteEngine engine = BiasVoteEngine.get("MNQ").orElseThrow();
        assertThat(engine.mode()).isEqualTo(VoteMode.LEGACY);
        feedOneHour(s);
        assertThat(engine.evaluationCount()).isZero();
    }

    // ── (d) LOG mode: legacy decides, counters move ──────────────────────

    @Test
    @DisplayName("d) LOG: evaluated per HTF close; legacy bias still decides the seam")
    void logModeCountsButLegacyDecides() {
        System.setProperty(BiasVoteEngine.MODE_PROPERTY, "LOG");
        StdvOteRunnerStrategy s = new StdvOteRunnerStrategy("MNQ", "MES", new EventBus());
        BiasVoteEngine engine = BiasVoteEngine.get("MNQ").orElseThrow();
        feedOneHour(s);
        assertThat(engine.evaluationCount()).isGreaterThan(0);
        assertThat(engine.agreeCount() + engine.disagreeCount())
                .isEqualTo(engine.evaluationCount());
        // Counter semantics at the unit level.
        BiasVoteEngine unit = new BiasVoteEngine("MNQ", TICK, VoteMode.LOG, 2);
        VoteInputs neutralInputs = new VoteInputs(HtfTrendState.RANGING,
                DailyPhase.ACCUMULATION, Optional.empty(), 20000,
                Optional.empty(), Optional.empty());
        unit.evaluate(neutralInputs, MarketBias.BULLISH);
        assertThat(unit.voteNeutralLegacyDirectionalCount()).isEqualTo(1);
        VoteInputs bullInputs = new VoteInputs(HtfTrendState.STRONG_BULLISH,
                DailyPhase.DISTRIBUTION_UP, Optional.of(20050.0), 20000,
                Optional.of(level(LevelType.PDH, 20100, false)),
                Optional.of(level(LevelType.PDL, 19900, true)));
        unit.evaluate(bullInputs, MarketBias.NEUTRAL);
        assertThat(unit.voteDirectionalLegacyNeutralCount()).isEqualTo(1);
        // The seam: LOG always hands back the legacy value.
        BiasVoteResult bull4 = BiasVoteEngine.aggregate(List.of(
                v(VoteDirection.BULL), v(VoteDirection.BULL),
                v(VoteDirection.BULL), v(VoteDirection.BULL)));
        assertThat(BiasVoteEngine.effectiveBias(VoteMode.LOG, MarketBias.BEARISH, bull4))
                .isEqualTo(MarketBias.BEARISH);
        assertThat(BiasVoteEngine.effectiveBias(VoteMode.LEGACY, MarketBias.BEARISH, null))
                .isEqualTo(MarketBias.BEARISH);
    }

    // ── (e) VOTE mode: finalBias feeds the single seam ───────────────────

    @Test
    @DisplayName("e) VOTE: the vote's finalBias replaces legacy at the seam")
    void voteModeFeedsSeam() {
        BiasVoteResult bear3 = BiasVoteEngine.aggregate(List.of(
                v(VoteDirection.BEAR), v(VoteDirection.BEAR),
                v(VoteDirection.BEAR), v(VoteDirection.ABSTAIN)));
        assertThat(BiasVoteEngine.effectiveBias(VoteMode.VOTE, MarketBias.BULLISH, bear3))
                .isEqualTo(MarketBias.BEARISH);

        // Runner-level: cold start guarantees >= 2 abstentions (no PDH/PDL,
        // AMD in accumulation) -> vote is NEUTRAL by arithmetic -> in VOTE
        // mode the machine must NOT leave IDLE regardless of structure.
        System.setProperty(BiasVoteEngine.MODE_PROPERTY, "VOTE");
        StdvOteRunnerStrategy s = new StdvOteRunnerStrategy("MNQ", "MES", new EventBus());
        BiasVoteEngine engine = BiasVoteEngine.get("MNQ").orElseThrow();
        feedOneHour(s);
        assertThat(engine.evaluationCount()).isGreaterThan(0);
        assertThat(s.getSetupContext().htfBias).isEqualTo(MarketBias.NEUTRAL);
        assertThat(s.getSetupContext().state).isEqualTo(SetupState.IDLE);
    }

    // ── (f) determinism ──────────────────────────────────────────────────

    @Test
    @DisplayName("f) same inputs -> identical vote result every evaluation")
    void determinism() {
        BiasVoteEngine engine = new BiasVoteEngine("MNQ", TICK, VoteMode.LOG, 2);
        VoteInputs in = new VoteInputs(HtfTrendState.WEAK_BEARISH,
                DailyPhase.DISTRIBUTION_DOWN, Optional.of(20010.0), 20030,
                Optional.of(level(LevelType.PDH, 20100, true)),
                Optional.of(level(LevelType.PDL, 19900, false)));
        BiasVoteResult a = engine.evaluate(in, MarketBias.BEARISH);
        BiasVoteResult b = engine.evaluate(in, MarketBias.BEARISH);
        assertThat(b).isEqualTo(a);
        assertThat(a.finalBias()).isEqualTo(MarketBias.BEARISH); // V1+V2+V4 BEAR, V3 BEAR(above)
        assertThat(a.alignedBear()).isEqualTo(4);
    }

    // ── (g) hysteresis interplay ─────────────────────────────────────────

    @Test
    @DisplayName("g) VOTE + hysteresis ON: a vote-NEUTRAL flip gets grace like a legacy flip")
    void hysteresisInterplay() {
        // The seam feeds core.recordHtfBias identically in every mode, so a
        // vote-produced NEUTRAL must be held by grace exactly like a
        // legacy-produced one. Proven at the core hook, which is the only
        // consumer of the seam's output.
        StdvOteStrategy core = new StdvOteStrategy("MNQ",
                new StdvProjectionEngine(/* chartState */ null,
                        new com.topstep.trading.strategy.ImpulseExtensionAnalyzer("MNQ", 30)),
                new OteEntryCalculator(),
                new com.topstep.trading.validation.MandatoryConfluenceValidator(
                        null, null, null),
                null, 40L);
        core.configureBiasHysteresis(true, 2);
        core.recordHtfBias(MarketBias.BULLISH);
        assertThat(core.getSetupContext().state).isEqualTo(SetupState.BIAS_SET);
        // First and second NEUTRAL evaluations: held by grace.
        core.recordHtfBias(MarketBias.NEUTRAL);
        core.recordHtfBias(MarketBias.NEUTRAL);
        assertThat(core.getSetupContext().state).isEqualTo(SetupState.BIAS_SET);
        // Third consecutive NEUTRAL exceeds graceBars=2 -> invalidated.
        core.recordHtfBias(MarketBias.NEUTRAL);
        assertThat(core.getSetupContext().state).isEqualTo(SetupState.INVALIDATED);
        core.shutdown();
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /** ~65 minutes of quiet 1m candles — crosses several 15m boundaries. */
    private static void feedOneHour(StdvOteRunnerStrategy s) {
        Instant t = Instant.parse("2026-06-25T14:00:00Z");
        for (int i = 0; i < 65; i++) {
            s.onCandle(new Candle("MNQ", t.plus(i, ChronoUnit.MINUTES),
                    20000.0, 20000.5 + (i % 3), 19999.5 - (i % 2),
                    20000.0 + (i % 2), 100), null);
        }
    }
}
