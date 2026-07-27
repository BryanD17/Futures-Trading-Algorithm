package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.chartstate.CandleSeries;
import com.topstep.trading.chartstate.LevelEngine;
import com.topstep.trading.domain.Candle;
import com.topstep.trading.strategy.stdvote.PremiumDiscountEvaluator.GateDecision;
import com.topstep.trading.strategy.stdvote.PremiumDiscountEvaluator.PdContext;
import com.topstep.trading.strategy.stdvote.PremiumDiscountEvaluator.PdMode;
import com.topstep.trading.strategy.stdvote.PremiumDiscountEvaluator.PdVerdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V3 Agent 02 tests — {@link PremiumDiscountEvaluator} (M2b).
 *
 * <p>Ranges are built through a REAL {@link LevelEngine} fed deterministic
 * candles across a session boundary, so the R1 (PDH/PDL) and R2 (developing
 * day) paths are exercised on the same plumbing production uses.
 */
@DisplayName("PremiumDiscountEvaluator (M2b)")
class PremiumDiscountEvaluatorTest {

    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final double TICK = 0.25;

    private static Instant et(int day, int hour, int minute) {
        return ZonedDateTime.of(2026, 6, day, hour, minute, 0, 0, ET).toInstant();
    }

    /**
     * LevelEngine with yesterday's range locked as PDH/PDL and a thin
     * developing range for today (20 pts = 80 ticks).
     */
    private static LevelEngine levelsWithPdRange(double pdh, double pdl) {
        CandleSeries series = new CandleSeries("MNQ", 1000);
        LevelEngine levels = new LevelEngine("MNQ", series);
        // Day 1 (Wed 2026-06-24): one candle spanning the full prior-day range.
        levels.processCandle(new Candle("MNQ", et(24, 10, 0),
                (pdh + pdl) / 2, pdh, pdl, (pdh + pdl) / 2, 100));
        // Day 2 first candle -> locks PDH/PDL and starts the developing day.
        levels.processCandle(new Candle("MNQ", et(25, 9, 0),
                20000, 20010, 19990, 20000, 100));
        return levels;
    }

    private static PremiumDiscountEvaluator evaluator(LevelEngine levels, PdMode mode) {
        return new PremiumDiscountEvaluator("MNQ", TICK, levels, mode,
                /* eqBandTicks */ 2, /* minRangeTicks */ 100);
    }

    // ── (a) long above equilibrium ───────────────────────────────────────

    @Test
    @DisplayName("a) LOG: long in premium passes and counts WOULD-BLOCK")
    void logModeLongPremiumPassesAndCounts() {
        PremiumDiscountEvaluator pd = evaluator(levelsWithPdRange(20100, 19900), PdMode.LOG);
        GateDecision d = pd.gateCheck(20050.0, /* bullish */ true); // eq = 20000
        assertThat(d.passed()).isTrue();
        assertThat(d.context().verdict()).isEqualTo(PdVerdict.PREMIUM);
        assertThat(d.context().rangeSource()).isEqualTo("R1");
        assertThat(pd.wouldBlockLongCount()).isEqualTo(1);
        assertThat(pd.blockedLongCount()).isZero();
    }

    @Test
    @DisplayName("a) BLOCK: long in premium rejected with the reason text")
    void blockModeLongPremiumRejected() {
        PremiumDiscountEvaluator pd = evaluator(levelsWithPdRange(20100, 19900), PdMode.BLOCK);
        GateDecision d = pd.gateCheck(20050.0, true);
        assertThat(d.passed()).isFalse();
        assertThat(d.reason()).contains("PREMIUM").contains("equilibrium");
        assertThat(pd.blockedLongCount()).isEqualTo(1);
    }

    // ── (b) short mirror ─────────────────────────────────────────────────

    @Test
    @DisplayName("b) LOG: short in discount passes and counts; BLOCK rejects")
    void shortMirror() {
        PremiumDiscountEvaluator log = evaluator(levelsWithPdRange(20100, 19900), PdMode.LOG);
        GateDecision dl = log.gateCheck(19950.0, /* bullish */ false); // discount
        assertThat(dl.passed()).isTrue();
        assertThat(log.wouldBlockShortCount()).isEqualTo(1);

        PremiumDiscountEvaluator block = evaluator(levelsWithPdRange(20100, 19900), PdMode.BLOCK);
        GateDecision db = block.gateCheck(19950.0, false);
        assertThat(db.passed()).isFalse();
        assertThat(db.context().verdict()).isEqualTo(PdVerdict.DISCOUNT);
        assertThat(block.blockedShortCount()).isEqualTo(1);

        // Favorable short: premium entry passes in BLOCK mode.
        assertThat(block.gateCheck(20050.0, false).passed()).isTrue();
    }

    // ── (c) equilibrium band ─────────────────────────────────────────────

    @Test
    @DisplayName("c) EQUILIBRIUM band (±2 ticks) blocks BOTH directions in BLOCK mode")
    void equilibriumBlocksBoth() {
        PremiumDiscountEvaluator pd = evaluator(levelsWithPdRange(20100, 19900), PdMode.BLOCK);
        // eq = 20000; band = ±0.5. 20000.25 is inside the band.
        GateDecision longSide = pd.gateCheck(20000.25, true);
        GateDecision shortSide = pd.gateCheck(19999.75, false);
        assertThat(longSide.passed()).isFalse();
        assertThat(longSide.context().verdict()).isEqualTo(PdVerdict.EQUILIBRIUM);
        assertThat(shortSide.passed()).isFalse();
        assertThat(shortSide.context().verdict()).isEqualTo(PdVerdict.EQUILIBRIUM);
        // Just outside the band is directional again.
        assertThat(pd.classify(20000.75).verdict()).isEqualTo(PdVerdict.PREMIUM);
    }

    // ── (d) R2 promotion on breakout days, R3 before the range is real ──

    @Test
    @DisplayName("d) outside PDH/PDL: ABSTAIN while developing range is thin, R2 once it spans minRangeTicks")
    void breakoutDayR2Promotion() {
        LevelEngine levels = levelsWithPdRange(20100, 19900);
        PremiumDiscountEvaluator pd = evaluator(levels, PdMode.BLOCK);

        // 20150 is above PDH -> R1 unavailable. Developing range spans only
        // 80 ticks (20 pts) < minRangeTicks 100 -> ABSTAIN, passes even in
        // BLOCK mode (doctrine).
        GateDecision thin = pd.gateCheck(20150.0, true);
        assertThat(thin.passed()).isTrue();
        assertThat(thin.context().verdict()).isEqualTo(PdVerdict.ABSTAIN);
        assertThat(thin.context().detail()).isEqualTo("breakout-day-range-thin");

        // Extend today's developing range to 19990-20120 (130 pts = 520 ticks).
        levels.processCandle(new Candle("MNQ", et(25, 10, 0),
                20010, 20120, 20005, 20110, 100));
        PdContext ctx = pd.classify(20150.0);
        assertThat(ctx.rangeSource()).isEqualTo("R2");
        // eq = (20120 + 19990) / 2 = 20055 -> 20150 is PREMIUM.
        assertThat(ctx.equilibrium()).isEqualTo(20055.0);
        assertThat(ctx.verdict()).isEqualTo(PdVerdict.PREMIUM);
        assertThat(pd.gateCheck(20150.0, true).passed()).isFalse();
    }

    // ── (e) ABSTAIN passes in BLOCK mode ─────────────────────────────────

    @Test
    @DisplayName("e) ABSTAIN always passes in BLOCK mode and is counted by reason")
    void abstainPassesInBlockMode() {
        // Fresh engine with NO candles at all: no PDH/PDL, no developing day.
        CandleSeries series = new CandleSeries("MNQ", 100);
        LevelEngine levels = new LevelEngine("MNQ", series);
        PremiumDiscountEvaluator pd = evaluator(levels, PdMode.BLOCK);
        GateDecision d = pd.gateCheck(20000.0, true);
        assertThat(d.passed()).isTrue();
        assertThat(d.context().verdict()).isEqualTo(PdVerdict.ABSTAIN);
        assertThat(d.context().detail()).isEqualTo("no-day-range-yet");
        assertThat(pd.abstainCount()).isEqualTo(1);
        assertThat(pd.blockedLongCount()).isZero();
    }

    // ── (f) OFF mode is a no-op ──────────────────────────────────────────

    @Test
    @DisplayName("f) OFF: gate passes with zero evaluations (no-invocation proof)")
    void offModeNoInvocation() {
        PremiumDiscountEvaluator pd = evaluator(levelsWithPdRange(20100, 19900), PdMode.OFF);
        GateDecision d = pd.gateCheck(20050.0, true);
        assertThat(d.passed()).isTrue();
        assertThat(d.context()).isNull();
        assertThat(pd.evaluationCount()).isZero();
        assertThat(pd.wouldBlockLongCount()).isZero();
        assertThat(pd.gatesToken(20050.0)).isEqualTo("pd=OFF");
    }

    // ── (g) determinism ──────────────────────────────────────────────────

    @Test
    @DisplayName("g) same LevelEngine state + price -> identical PdContext every time")
    void determinism() {
        PremiumDiscountEvaluator pd = evaluator(levelsWithPdRange(20100, 19900), PdMode.LOG);
        PdContext first = pd.classify(20050.0);
        PdContext second = pd.classify(20050.0);
        assertThat(second).isEqualTo(first);
        assertThat(first.equilibrium()).isEqualTo(20000.0);
    }

    // ── telemetry token ──────────────────────────────────────────────────

    @Test
    @DisplayName("[GATES] token: gate event shown once, then live preview")
    void gatesTokenConsumesEvent() {
        PremiumDiscountEvaluator pd = evaluator(levelsWithPdRange(20100, 19900), PdMode.LOG);
        pd.gateCheck(20050.0, true);
        assertThat(pd.gatesToken(19950.0)).isEqualTo("pd=WOULD-BLOCK-LONG");
        // Event consumed -> falls back to the preview of the given price.
        assertThat(pd.gatesToken(19950.0)).isEqualTo("pd=DISCOUNT(R1)");
    }

    @Test
    @DisplayName("mode parsing: invalid value falls back to LOG (safe default)")
    void modeParsing() {
        assertThat(PremiumDiscountEvaluator.parseMode("BLOCK")).isEqualTo(PdMode.BLOCK);
        assertThat(PremiumDiscountEvaluator.parseMode("off")).isEqualTo(PdMode.OFF);
        assertThat(PremiumDiscountEvaluator.parseMode(null)).isEqualTo(PdMode.LOG);
        assertThat(PremiumDiscountEvaluator.parseMode("banana")).isEqualTo(PdMode.LOG);
    }
}
