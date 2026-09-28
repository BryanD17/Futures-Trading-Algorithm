package com.topstep.trading.strategy.stdvote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.topstep.trading.chartstate.CandleSeries;
import com.topstep.trading.chartstate.KnownLevel;
import com.topstep.trading.chartstate.LevelEngine;
import com.topstep.trading.chartstate.LevelType;
import com.topstep.trading.chartstate.LiquidityRaid;
import com.topstep.trading.chartstate.RaidDetector;
import com.topstep.trading.chartstate.RaidDirection;
import com.topstep.trading.domain.AccountState;
import com.topstep.trading.domain.Candle;
import com.topstep.trading.event.EventBus;
import com.topstep.trading.strategy.DefaultStrategyContext;
import com.topstep.trading.strategy.MarketBias;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * V5 Agent 03 — acceptance evidence on the owner's REAL tape
 * (src/test/resources/tape/real_MNQ_1m.json + real_MES_1m.json, 7,830 bars,
 * 2026-09-21 00:00 → 2026-09-28 16:22 ET), through the REAL runner with the
 * DEFAULT configuration (no -D flags = autopsy cfg A).
 *
 * <p>One replay feeds every assertion; the evidence lines are printed with
 * the {@code [A-03]} prefix for Appendix A-03.
 */
@DisplayName("V5 Agent 03 — real-tape evidence (G1 bias / M2b / sweep, levels, cold start)")
class Agent03TapeEvidenceTest {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    private static List<Candle> mnq;
    private static List<Candle> mes;
    /** ET "HH:mm" on 2026-09-28 → ctx.htfBias after that bar. */
    private static final Map<String, MarketBias> g1Bias = new LinkedHashMap<>();
    private static final Map<String, double[]> g1Range = new LinkedHashMap<>();
    private static final List<LiquidityRaid> raids = new ArrayList<>();
    private static int firstNonNeutralBar = -1;
    private static PremiumDiscountEvaluator.GateDecision g1M2b;
    private static long lastEpoch;

    private static List<Candle> load(String res, String symbol) throws Exception {
        try (InputStream in = Agent03TapeEvidenceTest.class.getResourceAsStream("/tape/" + res)) {
            JsonNode arr = new ObjectMapper().readTree(in);
            List<Candle> out = new ArrayList<>(arr.size());
            for (JsonNode b : arr) {
                out.add(new Candle(symbol,
                        java.time.OffsetDateTime.parse(b.get("t").asText()).toInstant(),
                        b.get("o").asDouble(), b.get("h").asDouble(),
                        b.get("l").asDouble(), b.get("c").asDouble(),
                        b.get("v").asLong()));
            }
            return out;
        }
    }

    private static Instant et(String iso) {
        return LocalDateTime.parse(iso).atZone(ET).toInstant();
    }

    @BeforeAll
    static void replay() throws Exception {
        mnq = load("real_MNQ_1m.json", "MNQ");
        mes = load("real_MES_1m.json", "MES");
        EventBus bus = new EventBus();
        StdvOteRunnerStrategy runner = new StdvOteRunnerStrategy("MNQ", "MES", bus);
        runner.initialize();
        RaidDetector rd = (RaidDetector) field(runner, "raidDetector");
        rd.addRaidListener(raids::add);
        DefaultStrategyContext context = new DefaultStrategyContext(new AccountState(50_000.0));
        SetupContext ctx = runner.getSetupContext();
        Instant g1Start = et("2026-09-28T09:30");
        Instant g1End = et("2026-09-28T15:46");
        int si = 0;
        for (int bar = 0; bar < mnq.size(); bar++) {
            Candle c = mnq.get(bar);
            while (si < mes.size() && !mes.get(si).getTimestamp().isAfter(c.getTimestamp())) {
                runner.onCandle(mes.get(si++), context);
            }
            context.setCurrentTime(c.getTimestamp());
            runner.onCandle(c, context);
            if (firstNonNeutralBar < 0 && ctx.htfBias != MarketBias.NEUTRAL) firstNonNeutralBar = bar;
            if (!c.getTimestamp().isBefore(g1Start) && c.getTimestamp().isBefore(g1End)) {
                String hhmm = c.getTimestamp().atZone(ET).toLocalTime().toString();
                g1Bias.put(hhmm, ctx.htfBias);
                g1Range.put(hhmm, new double[] {ctx.rangeHigh, ctx.rangeLow, ctx.rangeEq});
                if (hhmm.equals("14:53")) {
                    // M2b as the validator would call it for the owner's short.
                    g1M2b = PremiumDiscountEvaluator.get("MNQ").orElseThrow()
                            .gateCheck(30635.0, /* bullish */ false);
                }
            }
            lastEpoch = ctx.biasEpoch;
        }
        runner.shutdown();
    }

    @AfterAll
    static void cleanup() {
        StdvOteRegistry.unregister("MNQ");
    }

    private static Object field(Object o, String name) throws Exception {
        java.lang.reflect.Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    @Test
    @DisplayName("cold start: bias non-NEUTRAL within 60 bars (was bar 120 legacy / 1,730 vote)")
    void coldStartWithin60Bars() {
        System.out.println("[A-03] cold start: first non-NEUTRAL ctx.htfBias at bar " + firstNonNeutralBar
                + " (" + mnq.get(firstNonNeutralBar).getTimestamp().atZone(ET) + ")");
        assertThat(firstNonNeutralBar).isBetween(0, 60);
        assertThat(lastEpoch).isPositive();
    }

    @Test
    @DisplayName("G1: bias BEARISH from 11:30 ET through 15:00 ET on 2026-09-28")
    void g1BiasBearish() {
        StringBuilder sb = new StringBuilder("[A-03] G1 bias rows (15m marks):\n");
        for (Map.Entry<String, MarketBias> e : g1Bias.entrySet()) {
            String t = e.getKey();
            if (t.compareTo("11:30") >= 0 && t.compareTo("15:00") <= 0) {
                assertThat(e.getValue()).as("bias at " + t).isEqualTo(MarketBias.BEARISH);
            }
            if (t.endsWith(":00") || t.endsWith(":15") || t.endsWith(":30") || t.endsWith(":45")) {
                double[] r = g1Range.get(t);
                sb.append(String.format("  %s ET bias=%s range=[%.2f, %.2f] eq=%.3f%n",
                        t, e.getValue(), r[1], r[0], r[2]));
            }
        }
        System.out.println(sb);
    }

    @Test
    @DisplayName("G1: dealing range = 30759.25 / 30356.75 (EQ 30558.0) once the NY-AM impulse completes")
    void g1DealingRange() {
        for (String t : List.of("11:30", "12:00", "13:00", "14:00", "14:53", "15:00")) {
            double[] r = g1Range.get(t);
            assertThat(r[0]).as("rangeHigh " + t).isEqualTo(30759.25);
            assertThat(r[1]).as("rangeLow " + t).isEqualTo(30356.75);
            assertThat(r[2]).as("rangeEq " + t).isCloseTo(30558.0, within(1e-9));
        }
    }

    @Test
    @DisplayName("G1 M2b: short @30635 vs the dealing range EQ → PREMIUM → pass (BLOCK mode)")
    void g1M2bPremium() {
        System.out.println("[A-03] G1 M2b @14:53: " + g1M2b);
        assertThat(g1M2b.passed()).isTrue();
        assertThat(g1M2b.context().verdict()).isEqualTo(PremiumDiscountEvaluator.PdVerdict.PREMIUM);
        assertThat(g1M2b.context().rangeSource()).isEqualTo("RD");
        assertThat(g1M2b.context().equilibrium()).isCloseTo(30558.0, within(1e-9));
    }

    @Test
    @DisplayName("G1 sweep: the 30640.00 London high raided 14:52–14:58 ET with score >= 5")
    void g1SweepOfLondonHigh() {
        Instant from = et("2026-09-28T14:52");
        Instant to = et("2026-09-28T14:59");
        Optional<LiquidityRaid> london = raids.stream()
                .filter(r -> r.getTargetLevel().getType() == LevelType.LONDON_HIGH)
                .filter(r -> !r.getRaidTime().isBefore(from) && r.getRaidTime().isBefore(to))
                .findFirst();
        assertThat(london).isPresent();
        LiquidityRaid r = london.get();
        System.out.println("[A-03] G1 sweep: " + r);
        assertThat(r.getDirection()).isEqualTo(RaidDirection.HIGH_SWEEP);
        assertThat(r.getTargetLevel().getPrice()).isEqualTo(30640.0);
        assertThat(r.getQualityScore()).isGreaterThanOrEqualTo(5);
    }

    @Test
    @DisplayName("levels 2026-09-28 within 1 pt of the chart (PDH/PDL, NY AM, London)")
    void levelParity0928() {
        LevelEngine levels = new LevelEngine("MNQ", new CandleSeries("MNQ", 5000));
        Instant cutoff = et("2026-09-28T15:00");
        for (Candle c : mnq) {
            if (!c.getTimestamp().isBefore(cutoff)) break;
            levels.processCandle(c);
        }
        Map<LevelType, Double> expected = new LinkedHashMap<>();
        expected.put(LevelType.PDH, 30999.50);
        expected.put(LevelType.PDL, 30679.00);
        expected.put(LevelType.NY_AM_HIGH, 30759.25);
        expected.put(LevelType.NY_AM_LOW, 30356.75);
        expected.put(LevelType.LONDON_HIGH, 30640.00);
        StringBuilder sb = new StringBuilder("[A-03] level parity 2026-09-28 (engine vs expected):\n");
        for (Map.Entry<LevelType, Double> e : expected.entrySet()) {
            double got = levels.getLevel(e.getKey()).map(KnownLevel::getPrice).orElse(Double.NaN);
            sb.append(String.format("  %-12s engine=%.2f expected=%.2f diff=%.2f%n",
                    e.getKey(), got, e.getValue(), Math.abs(got - e.getValue())));
            assertThat(got).as(e.getKey().name()).isCloseTo(e.getValue(), within(1.0));
        }
        for (LevelType t : List.of(LevelType.LONDON_LOW, LevelType.ASIA_HIGH, LevelType.ASIA_LOW,
                LevelType.PWH, LevelType.PWL, LevelType.MIDNIGHT_OPEN)) {
            sb.append(String.format("  %-12s engine=%s%n", t,
                    levels.getLevel(t).map(KnownLevel::getPrice).orElse(null)));
        }
        System.out.println(sb);
    }

    @Test
    @DisplayName("phantom day: the 2026-09-25 17:00 ET 1-lot print never becomes PDH/PDL")
    void phantomSettlementPrintIgnored() {
        LevelEngine levels = new LevelEngine("MNQ", new CandleSeries("MNQ", 5000));
        Instant cutoff = et("2026-09-27T20:00"); // Sunday session under way
        for (Candle c : mnq) {
            if (!c.getTimestamp().isBefore(cutoff)) break;
            levels.processCandle(c);
        }
        assertThat(levels.getPDH()).contains(30999.50);
        assertThat(levels.getPDL()).contains(30679.00);
        assertThat(levels.getPDH().get()).isNotEqualTo(30921.75);
        ZonedDateTime fri17 = ZonedDateTime.of(2026, 9, 25, 17, 0, 0, 0, ET);
        assertThat(mnq.stream().anyMatch(c -> c.getTimestamp().equals(fri17.toInstant())))
                .as("the tape really contains the 17:00 settlement print").isTrue();
    }
}
