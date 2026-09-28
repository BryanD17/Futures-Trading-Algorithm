package com.topstep.trading.chartstate;

import com.topstep.trading.domain.Candle;
import com.topstep.trading.strategy.KillzoneClock;
import com.topstep.trading.strategy.SilverBulletClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V5 Agent 03 (RC-07) — the recalibrated raid score: weight table,
 * reachability table (session × SMT × level type), the SCORING-mode timing
 * rule, micro-contract tick config, and pipeline scoring of swing sweeps.
 */
@DisplayName("V5 Agent 03 — raid score weights + reachability")
class RaidScoreReachabilityTest {

    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final double TICK = 0.25;
    private static final int MNQ_FLOOR = 5;
    private static final int SCALP_FLOOR = 6;

    @AfterEach
    void cleanup() {
        System.clearProperty("session.gateMode");
        System.clearProperty("raid.weights");
    }

    /** A textbook HIGH sweep of {@code level}: 3-pt penetration, close 2 pt
     *  back below it, 60 % upper wick (strong rejection). */
    private static LiquidityRaid highSweep(LevelType type, double level, int cluster, boolean strong) {
        KnownLevel kl = new KnownLevel(type, level, Instant.parse("2026-09-28T08:00:00Z"), cluster);
        double high = level + 3.0;
        double close = strong ? level - 2.0 : level - 0.25;
        double open = strong ? level - 1.0 : level + 2.0;
        double low = Math.min(open, close) - (strong ? 0.25 : 1.0);
        return new LiquidityRaid("MNQ", kl, RaidDirection.HIGH_SWEEP, Instant.now(), 1,
                high, low, open, close, 3.0 / TICK, TICK);
    }

    private static RaidQualityScorer.RaidScoringContext ctx(Instant at, boolean smt, Boolean bearish,
                                                            double eq) {
        KillzoneClock kz = new KillzoneClock();
        SilverBulletClock sb = new SilverBulletClock();
        return new RaidQualityScorer.RaidScoringContext.Builder()
                .timestamp(at)
                .killzone(kz.isInKillzone(at), kz.getCurrentKillzoneName(at), kz.getCurrentPhase(at))
                .silverBullet(sb.isInSilverBulletWindow(at), sb.getCurrentWindowName(at))
                .sessionOverlap(kz.isSessionOverlap(at))
                .smtDivergence(smt)
                .htfBias(bearish == null ? null : !bearish)
                .rangeEquilibrium(eq)
                .strongCloseBackTicks(8)
                .build();
    }

    private static Instant et(LocalTime t) {
        return LocalDate.of(2026, 9, 28).atTime(t).atZone(ET).toInstant();
    }

    @Test
    @DisplayName("textbook SESSION-level sweep clears the MNQ floor 5 with NO SMT / PDH / killzone / bias")
    void textbookSessionSweepClearsFloor() {
        RaidQualityScorer s = new RaidQualityScorer();
        LiquidityRaid raid = highSweep(LevelType.LONDON_HIGH, 30640.0, 1, true);
        // Asia 21:00 ET: no killzone, no SB; bias NEUTRAL; no SMT; P/D unknown.
        int score = s.calculateScore(raid, ctx(et(LocalTime.of(21, 0)), false, null, Double.NaN));
        System.out.println("[A-03] textbook session sweep, nothing else: " + raid);
        assertThat(score).isGreaterThanOrEqualTo(MNQ_FLOOR);
    }

    @Test
    @DisplayName("D-11: the -1 low-probability-timing penalty applies ONLY in session.gateMode=BLOCKING")
    void timingPenaltyOnlyInBlockingMode() {
        RaidQualityScorer s = new RaidQualityScorer();
        Instant asia = et(LocalTime.of(21, 0));
        int scoring = s.calculateScore(highSweep(LevelType.LONDON_HIGH, 30640.0, 1, true),
                ctx(asia, false, null, Double.NaN));
        System.setProperty("session.gateMode", "BLOCKING");
        int blocking = s.calculateScore(highSweep(LevelType.LONDON_HIGH, 30640.0, 1, true),
                ctx(asia, false, null, Double.NaN));
        assertThat(blocking).isEqualTo(scoring - 1);
    }

    @Test
    @DisplayName("HTF-opposes penalty (-4) uses the bias it is handed (the one M2 judges)")
    void opposingBiasPenalty() {
        RaidQualityScorer s = new RaidQualityScorer();
        Instant asia = et(LocalTime.of(21, 0));
        int aligned = s.calculateScore(highSweep(LevelType.LONDON_HIGH, 30640.0, 1, true),
                ctx(asia, false, Boolean.TRUE, Double.NaN));
        int opposed = s.calculateScore(highSweep(LevelType.LONDON_HIGH, 30640.0, 1, true),
                ctx(asia, false, Boolean.FALSE, Double.NaN));
        assertThat(aligned - opposed).isEqualTo(6); // +2 aligned vs -4 opposed
    }

    @Test
    @DisplayName("reachability table: session x SMT x level type (bias aligned, strong rejection, premium)")
    void reachabilityTable() {
        RaidQualityScorer s = new RaidQualityScorer();
        Map<String, LocalTime> sessions = new LinkedHashMap<>();
        sessions.put("ASIA 21:00", LocalTime.of(21, 0));
        sessions.put("LONDON 04:30", LocalTime.of(4, 30));
        sessions.put("PRE_NY 08:45", LocalTime.of(8, 45));
        sessions.put("NY_AM 10:30", LocalTime.of(10, 30));
        sessions.put("NY_LUNCH 12:30", LocalTime.of(12, 30));
        sessions.put("NY_PM 14:30", LocalTime.of(14, 30));
        sessions.put("PRE_ASIA 18:30", LocalTime.of(18, 30));
        Object[][] levels = {
                {"PDH/PDL", LevelType.PDH, 1},
                {"SESSION (London/Asia/NY hi-lo)", LevelType.LONDON_HIGH, 1},
                {"EQUAL (cluster 3)", LevelType.EQUAL_HIGH, 3},
                {"SWING (prior 10-bar extreme)", LevelType.SESSION_HIGH, 1},
        };
        StringBuilder sb = new StringBuilder("[A-03] RAID REACHABILITY (score; * = >= MNQ floor 5; ** = >= scalp floor 6)\n");
        sb.append("  bias aligned, strong rejection, swept level in PREMIUM of the dealing range\n");
        sb.append(String.format("  %-16s | %-3s | %-9s | %-9s | %-9s | %-9s%n", "session", "SMT",
                "PDH/PDL", "SESSION", "EQUAL(3)", "SWING"));
        for (Map.Entry<String, LocalTime> e : sessions.entrySet()) {
            for (boolean smt : new boolean[] {false, true}) {
                StringBuilder row = new StringBuilder(String.format("  %-16s | %-3s |", e.getKey(), smt ? "yes" : "no"));
                for (Object[] lv : levels) {
                    LiquidityRaid raid = highSweep((LevelType) lv[1], 30640.0, (int) lv[2], true);
                    int score = s.calculateScore(raid, ctx(et(e.getValue()), smt, Boolean.TRUE, 30558.0));
                    row.append(String.format(" %-9s |", score + (score >= SCALP_FLOOR ? "**" : score >= MNQ_FLOOR ? "*" : "")));
                    // Every level type is reachable at the MNQ floor in EVERY session.
                    assertThat(score).as(e.getKey() + " " + lv[0] + " smt=" + smt).isGreaterThanOrEqualTo(MNQ_FLOOR);
                }
                sb.append(row).append('\n');
            }
        }
        // The minimum case: NEUTRAL bias, moderate rejection, P/D unknown, no SMT, Asia.
        sb.append("  minimum (bias NEUTRAL, moderate rejection, no P/D, no SMT, ASIA): ");
        for (Object[] lv : levels) {
            LiquidityRaid raid = highSweep((LevelType) lv[1], 30640.0, (int) lv[2], false);
            int score = s.calculateScore(raid, ctx(et(LocalTime.of(21, 0)), false, null, Double.NaN));
            sb.append(lv[0]).append('=').append(score).append("  ");
        }
        System.out.println(sb);
    }

    @Test
    @DisplayName("raid.weights=V4 rollback reproduces the pre-V5 table (textbook session sweep = 1 -> below floor)")
    void v4Rollback() {
        System.setProperty("raid.weights", "V4");
        System.setProperty("session.gateMode", "BLOCKING");
        int v4 = new RaidQualityScorer().calculateScore(highSweep(LevelType.LONDON_HIGH, 30640.0, 1, true),
                ctx(et(LocalTime.of(21, 0)), false, null, Double.NaN));
        assertThat(v4).isEqualTo(1); // +1 session extreme, -1 timing, clamp to 1
    }

    @Test
    @DisplayName("micro contracts use the full-size tick size (MNQ 0.25, MES 0.25, MGC 0.10)")
    void microTickConfig() {
        assertThat(InstrumentRaidConfig.forSymbol("MNQ").getTickSize()).isEqualTo(0.25);
        assertThat(InstrumentRaidConfig.forSymbol("MNQ").getMinPenetrationPrice()).isEqualTo(1.0);
        assertThat(InstrumentRaidConfig.forSymbol("MES").getTickSize()).isEqualTo(0.25);
        assertThat(InstrumentRaidConfig.forSymbol("MGC").getTickSize()).isEqualTo(0.10);
    }

    @Test
    @DisplayName("scoreSweep: a swing sweep with rejection is scored; a close-through is NOT a sweep")
    void pipelineScoresSwingSweeps() {
        CandleSeries series = new CandleSeries("MNQ", 100);
        LevelEngine levels = new LevelEngine("MNQ", series);
        RaidDetector rd = new RaidDetector("MNQ", levels, new EqualLevelDetector("MNQ", series), series);
        Instant t = et(LocalTime.of(21, 0));
        Candle sweep = new Candle("MNQ", t, 100.0, 104.0, 99.0, 100.5, 10);    // takes 102 high, closes back
        Optional<LiquidityRaid> r = rd.scoreSweep(sweep, 102.0, false,
                RaidDetector.RaidDetectionContext.full(false, Boolean.FALSE));
        assertThat(r).isPresent();
        assertThat(r.get().getTargetLevel().getType()).isEqualTo(LevelType.SESSION_HIGH);
        assertThat(r.get().getQualityScore()).isGreaterThanOrEqualTo(MNQ_FLOOR);
        Candle breakout = new Candle("MNQ", t, 100.0, 104.0, 99.9, 103.9, 10);  // closes through
        assertThat(rd.scoreSweep(breakout, 102.0, false,
                RaidDetector.RaidDetectionContext.full(false, Boolean.FALSE))).isEmpty();
        assertThat(List.of(r.get().getQualityFactors()).toString()).contains("BASE");
    }
}
