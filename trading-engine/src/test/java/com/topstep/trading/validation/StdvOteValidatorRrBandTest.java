package com.topstep.trading.validation;

import com.topstep.trading.domain.RiskLimits;
import com.topstep.trading.strategy.FairValueGap;
import com.topstep.trading.strategy.LiquiditySweep;
import com.topstep.trading.strategy.MarketBias;
import com.topstep.trading.strategy.stdvote.OteZone;
import com.topstep.trading.strategy.stdvote.SetupContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * SA3 tests for the M7 RR band sourced from the ACTIVE RiskLimits.
 *
 * <p>The critical legacy constraint: the historical validator floor is 2.0
 * while {@code topstep50k().minRiskRewardRatio} is 3.0. The validator reads
 * the dedicated signal band ({@code signalMinRr}/{@code signalMaxRr}), NOT
 * {@code minRiskRewardRatio} — so a legacy 2.5-RR setup that emitted before
 * this change still emits with topstep50k() injected.
 */
@DisplayName("MandatoryConfluenceValidator M7 RR band from active RiskLimits")
class StdvOteValidatorRrBandTest {

    private MandatoryConfluenceValidator newValidator() {
        return new MandatoryConfluenceValidator(
                mock(com.topstep.trading.strategy.MultiTimeframeAnalyzer.class),
                mock(com.topstep.trading.strategy.DisplacementDetector.class),
                mock(com.topstep.trading.chartstate.ChartStateQueryAPI.class));
    }

    /** Same happy-path context as StdvOteValidatorTest; rr is set per test. */
    private SetupContext happyCtx(double rr) {
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
        ctx.rr = rr;
        ctx.sizeRequest = 12;
        ctx.lastGateFailed = null;
        return ctx;
    }

    @Test
    @DisplayName("no RiskLimits injected: historical band [2.0, +inf) applies unchanged")
    void noInjectionKeepsHistoricalBand() {
        MandatoryConfluenceValidator v = newValidator();
        assertThat(v.validateStdvOte(happyCtx(2.0)).passed()).isTrue();
        assertThat(v.validateStdvOte(happyCtx(1.99)).passed()).isFalse();
        assertThat(v.validateStdvOte(happyCtx(50.0)).passed()).isTrue(); // no ceiling
    }

    @Test
    @DisplayName("CRITICAL: topstep50k() injected does NOT tighten legacy emission to 3.0")
    void legacyProfileDoesNotTightenTo3() {
        MandatoryConfluenceValidator v = newValidator();
        v.setActiveRiskLimits(RiskLimits.topstep50k());
        // 2.5 RR is below topstep50k().minRiskRewardRatio (3.0) but above the
        // legacy validator floor (2.0) — it must STILL pass M7, proving the
        // validator reads signalMinRr, not minRiskRewardRatio.
        assertThat(v.validateStdvOte(happyCtx(2.5)).passed()).isTrue();
        assertThat(v.validateStdvOte(happyCtx(2.0)).passed()).isTrue();
        ValidationResult below = v.validateStdvOte(happyCtx(1.99));
        assertThat(below.passed()).isFalse();
        assertThat(below.getSummary()).isEqualTo("M7");
        // And still no ceiling for legacy.
        assertThat(v.validateStdvOte(happyCtx(5.9)).passed()).isTrue();
    }

    @Test
    @DisplayName("topstep50kScalp() injected: band [0.8, 1.5] enforced both ways")
    void scalpBandEnforced() {
        MandatoryConfluenceValidator v = newValidator();
        v.setActiveRiskLimits(RiskLimits.topstep50kScalp());
        assertThat(v.validateStdvOte(happyCtx(0.8)).passed()).isTrue();
        assertThat(v.validateStdvOte(happyCtx(1.0)).passed()).isTrue();
        assertThat(v.validateStdvOte(happyCtx(1.5)).passed()).isTrue();

        ValidationResult tooLow = v.validateStdvOte(happyCtx(0.79));
        assertThat(tooLow.passed()).isFalse();
        assertThat(tooLow.getSummary()).isEqualTo("M7");
        assertThat(tooLow.getFailures().get(0)).contains("floor");

        ValidationResult tooHigh = v.validateStdvOte(happyCtx(1.51));
        assertThat(tooHigh.passed()).isFalse();
        assertThat(tooHigh.getSummary()).isEqualTo("M7");
        assertThat(tooHigh.getFailures().get(0)).contains("ceiling");
    }
}
