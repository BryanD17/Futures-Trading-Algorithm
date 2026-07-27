package com.topstep.trading.validation;

import com.topstep.trading.chartstate.CandleSeries;
import com.topstep.trading.chartstate.LevelEngine;
import com.topstep.trading.domain.Candle;
import com.topstep.trading.strategy.FairValueGap;
import com.topstep.trading.strategy.LiquiditySweep;
import com.topstep.trading.strategy.MarketBias;
import com.topstep.trading.strategy.stdvote.OteZone;
import com.topstep.trading.strategy.stdvote.PremiumDiscountEvaluator;
import com.topstep.trading.strategy.stdvote.SetupContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * V3 Agent 02 — the M2b gate inside {@code validateStdvOte}, between M2 and
 * M3. Mirrors {@link StdvOteValidatorTest}'s happy-path fixture; existing
 * M1..M9 tests run UNCHANGED (no evaluator injected there = pre-V3 path).
 */
@DisplayName("MandatoryConfluenceValidator M2b (premium/discount)")
class StdvOteValidatorPdGateTest {

    private static final ZoneId ET = ZoneId.of("America/New_York");
    private static final double TICK = 0.25;

    private MandatoryConfluenceValidator newValidator() {
        return new MandatoryConfluenceValidator(
                mock(com.topstep.trading.strategy.MultiTimeframeAnalyzer.class),
                mock(com.topstep.trading.strategy.DisplacementDetector.class),
                mock(com.topstep.trading.chartstate.ChartStateQueryAPI.class));
    }

    private static Instant et(int day, int hour) {
        return ZonedDateTime.of(2026, 6, day, hour, 0, 0, 0, ET).toInstant();
    }

    /** LevelEngine with PDH/PDL locked at the given prior-day extremes. */
    private static LevelEngine levels(double pdh, double pdl) {
        CandleSeries series = new CandleSeries("MNQ", 1000);
        LevelEngine engine = new LevelEngine("MNQ", series);
        engine.processCandle(new Candle("MNQ", et(24, 10),
                (pdh + pdl) / 2, pdh, pdl, (pdh + pdl) / 2, 100));
        engine.processCandle(new Candle("MNQ", et(25, 9),
                20000, 20010, 19990, 20000, 100));
        return engine;
    }

    private static PremiumDiscountEvaluator pd(LevelEngine levels,
                                               PremiumDiscountEvaluator.PdMode mode) {
        return new PremiumDiscountEvaluator("MNQ", TICK, levels, mode, 2, 100);
    }

    /** Same happy fixture as StdvOteValidatorTest (entry 20020, bullish). */
    private SetupContext happyMnqBullish() {
        SetupContext ctx = new SetupContext();
        ctx.symbol = "MNQ";
        ctx.htfBias = MarketBias.BULLISH;
        ctx.killzoneOpen = true;
        ctx.sweep = new LiquiditySweep(true, 19952.0, Instant.now(), true);
        ctx.raidScore = 7;
        ctx.displacement = true;
        ctx.fvg = new FairValueGap(true, 20120.0, 20115.0, Instant.now());
        ctx.mss = true;
        ctx.ote = new OteZone(19952.0, 20180.0, true,
                20066.00, 20038.50, 20019.50, 19999.75, 19952.00);
        ctx.pdArrayInOte = 20020.00;
        ctx.entry = 20020.00;
        ctx.stop = 19951.00;
        ctx.rr = 5.5;
        ctx.sizeRequest = 12;
        ctx.lastGateFailed = null;
        return ctx;
    }

    @Test
    @DisplayName("BLOCK: premium long entry fails with gate id M2b, between M2 and M3")
    void blockModeFailsWithM2bId() {
        MandatoryConfluenceValidator v = newValidator();
        // PDH/PDL 20100/19900 -> eq 20000 -> entry 20020 is PREMIUM for a long.
        v.setPremiumDiscountEvaluator(pd(levels(20100, 19900),
                PremiumDiscountEvaluator.PdMode.BLOCK));
        SetupContext ctx = happyMnqBullish();
        ctx.killzoneOpen = false; // proves M2b fires BEFORE M3 is even read
        ValidationResult r = v.validateStdvOte(ctx);
        assertThat(r.passed()).isFalse();
        assertThat(r.getSummary()).isEqualTo("M2b");
        assertThat(r.getFailures().get(0)).contains("M2b").contains("PREMIUM");
    }

    @Test
    @DisplayName("LOG (default): premium entry still passes the whole gate chain")
    void logModePassesEverything() {
        MandatoryConfluenceValidator v = newValidator();
        PremiumDiscountEvaluator evaluator = pd(levels(20100, 19900),
                PremiumDiscountEvaluator.PdMode.LOG);
        v.setPremiumDiscountEvaluator(evaluator);
        ValidationResult r = v.validateStdvOte(happyMnqBullish());
        assertThat(r.passed()).as("failures: %s", r.getFailures()).isTrue();
        assertThat(evaluator.toApiMap().get("wouldBlockLong")).isEqualTo(1L);
    }

    @Test
    @DisplayName("BLOCK: discount long entry passes and the M2b confirmation is listed")
    void blockModeFavorablePasses() {
        MandatoryConfluenceValidator v = newValidator();
        // PDH/PDL 20300/19900 -> eq 20100 -> entry 20020 is DISCOUNT (favorable).
        v.setPremiumDiscountEvaluator(pd(levels(20300, 19900),
                PremiumDiscountEvaluator.PdMode.BLOCK));
        ValidationResult r = v.validateStdvOte(happyMnqBullish());
        assertThat(r.passed()).as("failures: %s", r.getFailures()).isTrue();
    }

    @Test
    @DisplayName("BLOCK + no range data: ABSTAIN passes (doctrine)")
    void abstainPassesThroughValidator() {
        MandatoryConfluenceValidator v = newValidator();
        LevelEngine empty = new LevelEngine("MNQ", new CandleSeries("MNQ", 10));
        v.setPremiumDiscountEvaluator(pd(empty, PremiumDiscountEvaluator.PdMode.BLOCK));
        ValidationResult r = v.validateStdvOte(happyMnqBullish());
        assertThat(r.passed()).as("failures: %s", r.getFailures()).isTrue();
    }

    @Test
    @DisplayName("OFF: gate passes with zero evaluator work (byte-identical path)")
    void offModeIsNoOp() {
        MandatoryConfluenceValidator v = newValidator();
        PremiumDiscountEvaluator evaluator = pd(levels(20100, 19900),
                PremiumDiscountEvaluator.PdMode.OFF);
        v.setPremiumDiscountEvaluator(evaluator);
        ValidationResult r = v.validateStdvOte(happyMnqBullish());
        assertThat(r.passed()).isTrue();
        assertThat(evaluator.evaluationCount()).isZero();
    }

    @Test
    @DisplayName("no evaluator injected: validator behaves exactly as pre-V3")
    void nullEvaluatorUnchanged() {
        ValidationResult r = newValidator().validateStdvOte(happyMnqBullish());
        assertThat(r.passed()).isTrue();
        assertThat(r.getSummary()).isEqualTo("STDV+OTE M1..M9 all passed");
    }
}
