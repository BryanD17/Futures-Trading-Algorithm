package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.chartstate.CandleSeries;
import com.topstep.trading.chartstate.LevelEngine;
import com.topstep.trading.domain.Candle;
import com.topstep.trading.event.EventBus;
import com.topstep.trading.strategy.BarAggregationManager;
import com.topstep.trading.strategy.DailyAmdCycleTracker;
import com.topstep.trading.strategy.DailyAmdCycleTracker.DailyPhase;
import com.topstep.trading.strategy.HtfSeriesRegistry;
import com.topstep.trading.strategy.HtfTrendAnalyzer.HtfTrendState;
import com.topstep.trading.strategy.ImpulseExtensionAnalyzer;
import com.topstep.trading.strategy.LiquiditySweep;
import com.topstep.trading.strategy.MarketBias;
import com.topstep.trading.strategy.stdvote.BiasVoteEngine.BiasVote;
import com.topstep.trading.strategy.stdvote.BiasVoteEngine.VoteDirection;
import com.topstep.trading.validation.MandatoryConfluenceValidator;
import com.topstep.trading.validation.ValidationResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * V5 Agent 03 — bias reachability (ADAPTIVE vote, V2 fix, hysteresis,
 * biasEpoch), bias direction (dealing-range anchor), the M2 truth table and
 * M2b on the G1 dealing range.
 */
@DisplayName("V5 Agent 03 — bias, M2 truth table, M2b")
class Agent03BiasTest {

    private static final double TICK = 0.25;
    private static final ZoneId ET = ZoneId.of("America/New_York");

    @AfterEach
    void cleanup() {
        StdvOteRegistry.unregister("MNQ");
        System.clearProperty(BiasVoteEngine.MODE_PROPERTY);
    }

    private static BiasVote v(VoteDirection d) {
        return new BiasVote("V?", d, "");
    }

    // ── ADAPTIVE vote rule ───────────────────────────────────────────────

    @Test
    @DisplayName("ADAPTIVE: 3-of-4 / 2-of-3 / warm pair 2-0 / fewer -> NEUTRAL; STRICT unchanged")
    void adaptiveAggregation() {
        BiasConfig.VoteRule A = BiasConfig.VoteRule.ADAPTIVE;
        BiasConfig.VoteRule S = BiasConfig.VoteRule.STRICT_3OF4;
        // 4 voting: needs 3.
        assertThat(BiasVoteEngine.aggregate(List.of(v(VoteDirection.BEAR), v(VoteDirection.BEAR),
                v(VoteDirection.BEAR), v(VoteDirection.BULL)), A).finalBias()).isEqualTo(MarketBias.BEARISH);
        assertThat(BiasVoteEngine.aggregate(List.of(v(VoteDirection.BEAR), v(VoteDirection.BEAR),
                v(VoteDirection.BULL), v(VoteDirection.BULL)), A).finalBias()).isEqualTo(MarketBias.NEUTRAL);
        // 3 voting (one abstains): 2-of-3.
        assertThat(BiasVoteEngine.aggregate(List.of(v(VoteDirection.BULL), v(VoteDirection.BULL),
                v(VoteDirection.BEAR), v(VoteDirection.ABSTAIN)), A).finalBias()).isEqualTo(MarketBias.BULLISH);
        assertThat(BiasVoteEngine.aggregate(List.of(v(VoteDirection.BULL), v(VoteDirection.BULL),
                v(VoteDirection.BEAR), v(VoteDirection.ABSTAIN)), S).finalBias()).isEqualTo(MarketBias.NEUTRAL);
        // 2 voting (warm pair): must agree.
        assertThat(BiasVoteEngine.aggregate(List.of(v(VoteDirection.BULL), v(VoteDirection.BULL),
                v(VoteDirection.ABSTAIN), v(VoteDirection.ABSTAIN)), A).finalBias()).isEqualTo(MarketBias.BULLISH);
        assertThat(BiasVoteEngine.aggregate(List.of(v(VoteDirection.BULL), v(VoteDirection.BEAR),
                v(VoteDirection.ABSTAIN), v(VoteDirection.ABSTAIN)), A).finalBias()).isEqualTo(MarketBias.NEUTRAL);
        assertThat(BiasVoteEngine.aggregate(List.of(v(VoteDirection.BULL), v(VoteDirection.BULL),
                v(VoteDirection.ABSTAIN), v(VoteDirection.ABSTAIN)), S).finalBias()).isEqualTo(MarketBias.NEUTRAL);
        // 1 voting: never enough.
        assertThat(BiasVoteEngine.aggregate(List.of(v(VoteDirection.BULL), v(VoteDirection.ABSTAIN),
                v(VoteDirection.ABSTAIN), v(VoteDirection.ABSTAIN)), A).finalBias()).isEqualTo(MarketBias.NEUTRAL);
    }

    @Test
    @DisplayName("RANGE anchor: a decisive dealing range decides; the 15m structure (V1) is one vote")
    void rangeAnchorDecides() {
        BiasVoteEngine e = new BiasVoteEngine("MNQ", TICK, BiasVoteEngine.VoteMode.VOTE, 2);
        e.configureRule(BiasConfig.VoteRule.ADAPTIVE, BiasConfig.BiasSource.RANGE);
        // G1 at 14:53: 15m structure BULLISH (the retrace), price below the
        // true day open (V3 BULL) — every vote reads the retrace.
        BiasVoteEngine.VoteInputs in = new BiasVoteEngine.VoteInputs(HtfTrendState.WEAK_BULLISH,
                DailyPhase.ACCUMULATION, Optional.of(30648.5), 30635.0,
                Optional.empty(), Optional.empty());
        DealingRangeTracker.Snapshot g1 = new DealingRangeTracker.Snapshot(
                MarketBias.BEARISH, 30759.25, 30356.75, 30558.0, true);
        BiasVoteEngine.BiasVoteResult r = e.evaluate(in, MarketBias.BULLISH, g1);
        assertThat(r.voteBias()).isEqualTo(MarketBias.BULLISH);
        assertThat(r.finalBias()).isEqualTo(MarketBias.BEARISH);
        assertThat(r.anchor()).startsWith("range:BEARISH");
        // Not decisive -> the vote decides.
        BiasVoteEngine.BiasVoteResult cold = e.evaluate(in, MarketBias.BULLISH,
                DealingRangeTracker.Snapshot.EMPTY);
        assertThat(cold.finalBias()).isEqualTo(MarketBias.BULLISH);
        // bias.source=VOTE ignores the range.
        e.configureRule(null, BiasConfig.BiasSource.VOTE);
        assertThat(e.evaluate(in, MarketBias.BULLISH, g1).finalBias()).isEqualTo(MarketBias.BULLISH);
    }

    // ── V2 (AMD) can vote ────────────────────────────────────────────────

    @Test
    @DisplayName("PF-08: DailyAmdCycleTracker leaves ACCUMULATION (pre-candle reference) so V2 votes")
    void amdV2CanVote() {
        DailyAmdCycleTracker amd = new DailyAmdCycleTracker("MNQ");
        LevelEngine levels = new LevelEngine("MNQ", new CandleSeries("MNQ", 100));
        Instant t = ZonedDateTime.of(2026, 9, 28, 9, 0, 0, 0, ET).toInstant();
        // Establish a session range 100..110.
        amd.update(new Candle("MNQ", t, 105, 110, 100, 105, 10), levels, null);
        // A candle closing BELOW the prior session low: manipulation down.
        amd.update(new Candle("MNQ", t.plusSeconds(60), 101, 102, 95, 97, 10), levels, null);
        assertThat(amd.getCurrentPhase()).isEqualTo(DailyPhase.MANIPULATION_DOWN);
        // Reclaim of the swept level: distribution up -> V2 votes BULL.
        amd.update(new Candle("MNQ", t.plusSeconds(120), 97, 103, 96, 102, 10), levels, null);
        assertThat(amd.getCurrentPhase()).isEqualTo(DailyPhase.DISTRIBUTION_UP);
        assertThat(BiasVoteEngine.voteV2(amd.getCurrentPhase()).direction()).isEqualTo(VoteDirection.BULL);
    }

    // ── hysteresis + biasEpoch ───────────────────────────────────────────

    private static StdvOteStrategy core() {
        return new StdvOteStrategy("MNQ",
                new StdvProjectionEngine(null, new ImpulseExtensionAnalyzer("MNQ", 30)),
                new OteEntryCalculator(), new MandatoryConfluenceValidator(null, null, null),
                null, 40L);
    }

    @Test
    @DisplayName("RC-04: hysteresis ON by default with grace 3; biasEpoch moves only on REAL flips")
    void hysteresisDefaultAndEpoch() {
        StdvOteStrategy s = core();
        s.recordHtfBias(MarketBias.BEARISH);
        assertThat(s.getSetupContext().state).isEqualTo(SetupState.BIAS_SET);
        assertThat(s.getSetupContext().biasEpoch).isEqualTo(1L);
        for (int i = 0; i < 3; i++) {
            s.recordHtfBias(MarketBias.NEUTRAL);
            assertThat(s.getSetupContext().state).as("grace " + (i + 1)).isEqualTo(SetupState.BIAS_SET);
        }
        s.recordHtfBias(MarketBias.BEARISH); // restored inside grace
        assertThat(s.getSetupContext().biasEpoch).as("NEUTRAL wobble is not a flip").isEqualTo(1L);
        s.recordHtfBias(MarketBias.BEARISH);
        assertThat(s.getSetupContext().biasEpoch).as("repeat is idempotent").isEqualTo(1L);
        s.recordHtfBias(MarketBias.BULLISH); // real flip
        assertThat(s.getSetupContext().state).isEqualTo(SetupState.INVALIDATED);
        assertThat(s.getSetupContext().biasEpoch).isEqualTo(2L);
        s.shutdown();
    }

    // ── M2 truth table ───────────────────────────────────────────────────

    private static SetupContext setupWith(MarketBias bias, boolean lowSweep) {
        SetupContext c = new SetupContext();
        c.symbol = "MNQ";
        c.htfBias = bias;
        c.sweep = new LiquiditySweep(lowSweep, 20000, Instant.parse("2026-09-28T14:00:00Z"), false);
        c.raidScore = 7;
        return c;
    }

    @Test
    @DisplayName("M2 truth table: LOW->long needs BULLISH, HIGH->short needs BEARISH (4 rows)")
    void m2TruthTable() {
        MandatoryConfluenceValidator v = new MandatoryConfluenceValidator(null, null, null);
        Object[][] rows = {
                // bias, lowSweep, M2 passes?
                {MarketBias.BULLISH, true, true},    // LOW sweep -> long, BULLISH: pass
                {MarketBias.BEARISH, false, true},   // HIGH sweep -> short, BEARISH: pass
                {MarketBias.BEARISH, true, false},   // LOW sweep (long) under BEARISH: fail M2
                {MarketBias.BULLISH, false, false},  // HIGH sweep (short) under BULLISH: fail M2
        };
        StringBuilder sb = new StringBuilder("[A-03] M2 truth table:\n");
        for (Object[] row : rows) {
            ValidationResult r = v.validateStdvOte(setupWith((MarketBias) row[0], (boolean) row[1]));
            boolean m2Failed = r.failed() && "M2".equals(r.getSummary());
            sb.append(String.format("  bias=%-8s sweep=%-4s -> M2 %s (%s)%n", row[0],
                    ((boolean) row[1]) ? "LOW" : "HIGH", m2Failed ? "FAIL" : "PASS", r.failed() ? r.getFailures() : "passed"));
            assertThat(!m2Failed).as(row[0] + "/" + row[1]).isEqualTo((boolean) row[2]);
        }
        // Core agrees: a counter-bias sweep is never taken.
        StdvOteStrategy s = core();
        s.recordHtfBias(MarketBias.BEARISH);
        s.recordManipulationLeg(19990, 20010, TICK, 3);
        s.recordSweep(new LiquiditySweep(true, 19990, Instant.now(), false), 9);
        assertThat(s.getSetupContext().state).isEqualTo(SetupState.MANIP_DONE);
        s.recordSweep(new LiquiditySweep(false, 20010, Instant.now(), false), 9);
        assertThat(s.getSetupContext().state).isEqualTo(SetupState.SWEEP_DONE);
        s.shutdown();
        System.out.println(sb);
    }

    // ── M2b on the G1 dealing range ──────────────────────────────────────

    @Test
    @DisplayName("G1 M2b: short @30635 vs EQ 30557.875 of [30356.50, 30759.25] -> PREMIUM -> pass (BLOCK)")
    void g1M2bUnit() {
        PremiumDiscountEvaluator pd = new PremiumDiscountEvaluator("MNQ", TICK,
                new LevelEngine("MNQ", new CandleSeries("MNQ", 10)),
                PremiumDiscountEvaluator.PdMode.BLOCK, 2, 80);
        pd.configureDealingRangeSource(() -> new double[] {30759.25, 30356.50});
        PremiumDiscountEvaluator.GateDecision d = pd.gateCheck(30635.0, false);
        System.out.println("[A-03] G1 M2b (owner anchors): " + d);
        assertThat(d.passed()).isTrue();
        assertThat(d.context().verdict()).isEqualTo(PremiumDiscountEvaluator.PdVerdict.PREMIUM);
        assertThat(d.context().equilibrium()).isCloseTo(30557.875, within(1e-9));
        assertThat(d.context().rangeSource()).isEqualTo("RD");
        // A LONG at the same price is blocked (premium).
        assertThat(pd.gateCheck(30635.0, true).passed()).isFalse();
    }

    // ── dealing range mechanics ──────────────────────────────────────────

    private static Candle bar(ZonedDateTime t, double o, double h, double l, double c) {
        return new Candle("MNQ", t.toInstant(), o, h, l, c, 10);
    }

    @Test
    @DisplayName("dealing range: BOS re-anchor after a premium pullback; sweep vs close-break; halt ignored")
    void dealingRangeMechanics() {
        DealingRangeTracker dr = new DealingRangeTracker(0.08, 0.5);
        ZonedDateTime t = ZonedDateTime.of(2026, 9, 28, 9, 30, 0, 0, ET);
        dr.onCandle(bar(t, 30750, 30759.25, 30740, 30745));              // high first
        dr.onCandle(bar(t.plusMinutes(1), 30745, 30746, 30600, 30610));   // low later -> BEARISH
        assertThat(dr.direction()).isEqualTo(MarketBias.BEARISH);
        dr.onCandle(bar(t.plusMinutes(2), 30610, 30640, 30605, 30630));   // small pullback (< 50%)
        dr.onCandle(bar(t.plusMinutes(3), 30630, 30631, 30356.75, 30400)); // new low: no re-anchor
        assertThat(dr.snapshot().high()).isEqualTo(30759.25);
        assertThat(dr.snapshot().low()).isEqualTo(30356.75);
        // Retrace into premium, wick ABOVE the old OB but close back inside: still bearish.
        dr.onCandle(bar(t.plusMinutes(4), 30400, 30650, 30390, 30633.75));
        assertThat(dr.direction()).isEqualTo(MarketBias.BEARISH);
        // New low after that >=50% pullback re-anchors the range high to 30650.
        dr.onCandle(bar(t.plusMinutes(5), 30630, 30632, 30300, 30310));
        assertThat(dr.snapshot().high()).isEqualTo(30650.0);
        assertThat(dr.snapshot().low()).isEqualTo(30300.0);
        // A 1m CLOSE above the range high flips BULLISH.
        dr.onCandle(bar(t.plusMinutes(6), 30310, 30700, 30305, 30690));
        assertThat(dr.direction()).isEqualTo(MarketBias.BULLISH);
        // Halt print (17:30 ET) is ignored entirely.
        MarketBias before = dr.direction();
        double hiBefore = dr.snapshot().high();
        dr.onCandle(bar(ZonedDateTime.of(2026, 9, 28, 17, 30, 0, 0, ET), 1, 99999, 1, 1));
        assertThat(dr.direction()).isEqualTo(before);
        assertThat(dr.snapshot().high()).isEqualTo(hiBefore);
        // 18:00 roll: direction CARRIES while the new day is undecided.
        dr.onCandle(bar(ZonedDateTime.of(2026, 9, 28, 18, 0, 0, 0, ET), 30690, 30691, 30689, 30690));
        assertThat(dr.direction()).isEqualTo(MarketBias.BULLISH);
        assertThat(dr.decisiveToday()).isFalse();
    }

    // ── warm boot from the seeded H1 ladder ──────────────────────────────

    @Test
    @DisplayName("warm boot: seeded H1 history -> bias at the FIRST 15m close (cold start: needs a range)")
    void warmBootFromSeededH1() {
        System.clearProperty(BiasVoteEngine.MODE_PROPERTY);
        StdvOteRunnerStrategy s = new StdvOteRunnerStrategy("MNQ", "MES", new EventBus());
        s.initialize();
        BarAggregationManager bm = HtfSeriesRegistry.get("MNQ").orElseThrow();
        List<Candle> h1 = new ArrayList<>();
        ZonedDateTime t0 = ZonedDateTime.of(2026, 9, 28, 1, 0, 0, 0, ET);
        double p = 30700;
        for (int i = 0; i < 8; i++) { // an H1 down-leg 30700 -> ~30500
            h1.add(new Candle("MNQ", t0.plusHours(i).toInstant(), p, p + 5, p - 30, p - 25, 1000));
            p -= 25;
        }
        bm.seedHigherTimeframe(h1);
        Instant t = t0.plusHours(8).toInstant();
        int bars = 0;
        while (s.getSetupContext().htfBias == MarketBias.NEUTRAL && bars < 60) {
            s.onCandle(new Candle("MNQ", t.plus(bars, ChronoUnit.MINUTES),
                    30500, 30500.5, 30499.5, 30500, 10), null);
            bars++;
        }
        System.out.println("[A-03] warm boot: bias " + s.getSetupContext().htfBias
                + " after " + bars + " live 1m bars (first 15m close)");
        assertThat(s.getSetupContext().htfBias).isEqualTo(MarketBias.BEARISH);
        assertThat(bars).as("first completed 15m bar is observed on the 16th 1m candle").isLessThanOrEqualTo(16);
        s.shutdown();
    }
}
