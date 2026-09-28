package com.topstep.trading.validation;

import com.topstep.trading.domain.RiskLimits;
import com.topstep.trading.strategy.FairValueGap;
import com.topstep.trading.strategy.LiquiditySweep;
import com.topstep.trading.strategy.MarketBias;
import com.topstep.trading.strategy.stdvote.OteConfig;
import com.topstep.trading.strategy.stdvote.OteZone;
import com.topstep.trading.strategy.stdvote.SetupContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * V5 Agent 04 (RC-13 / PF-07) — the ONE RR band.
 *
 * <p>Floor 1.0R legacy / 0.8R scalp, checked against T1 ({@code ctx.rrT1});
 * ceiling 5.0R for both profiles, checked against the FINAL target
 * ({@code ctx.rr}). The band comes from {@link OteConfig} — the same accessor
 * PropFirmRiskEngine reads (Agent 05) — so an injected RiskLimits profile can
 * no longer produce a second, different band.
 */
@DisplayName("MandatoryConfluenceValidator M7 — ONE RR band (V5)")
class StdvOteValidatorRrBandTest {

    @AfterEach
    void clear() {
        System.clearProperty("risk.rrFloor");
        System.clearProperty("risk.rrFloor.scalp");
        System.clearProperty("risk.rrCeiling");
    }

    private MandatoryConfluenceValidator newValidator() {
        return new MandatoryConfluenceValidator(
                mock(com.topstep.trading.strategy.MultiTimeframeAnalyzer.class),
                mock(com.topstep.trading.strategy.DisplacementDetector.class),
                mock(com.topstep.trading.chartstate.ChartStateQueryAPI.class));
    }

    /** Happy-path context; rr (final) and rrT1 set per test. */
    private SetupContext ctx(double rrT1, double rrFinal, boolean scalp) {
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
        ctx.rrT1 = rrT1;
        ctx.rr = rrFinal;
        ctx.scalpProfile = scalp;
        ctx.sizeRequest = 12;
        ctx.lastGateFailed = null;
        return ctx;
    }

    private boolean passes(double rrT1, double rrFinal, boolean scalp) {
        return newValidator().validateStdvOte(ctx(rrT1, rrFinal, scalp)).passed();
    }

    @Test
    @DisplayName("0.9R: rejected in legacy (floor 1.0), accepted in scalp (floor 0.8)")
    void pointNineR() {
        ValidationResult legacy = newValidator().validateStdvOte(ctx(0.9, 0.9, false));
        assertThat(legacy.passed()).isFalse();
        assertThat(legacy.getSummary()).isEqualTo("M7");
        assertThat(legacy.getFailures().get(0)).contains("floor 1.0").contains("legacy");
        assertThat(passes(0.9, 0.9, true)).isTrue();
    }

    @Test
    @DisplayName("3.2R: accepted in both legacy and scalp")
    void threePointTwoR() {
        assertThat(passes(3.2, 3.2, false)).isTrue();
        assertThat(passes(3.2, 3.2, true)).isTrue();
    }

    @Test
    @DisplayName("floor is checked against T1, ceiling against the FINAL target")
    void floorVsT1CeilingVsFinal() {
        // G1 geometry: RR(T1)=2.01, RR(final T2)=3.25 → pass.
        assertThat(passes(2.01, 3.25, false)).isTrue();
        // T1 below the floor kills it even with a juicy final target.
        assertThat(passes(0.9, 3.25, false)).isFalse();
        // Final above the ceiling kills it even with a fine T1.
        ValidationResult r = newValidator().validateStdvOte(ctx(2.0, 5.5, false));
        assertThat(r.passed()).isFalse();
        assertThat(r.getFailures().get(0)).contains("ceiling 5.0");
        // No ladder planned (rrT1 == 0): both checks use ctx.rr.
        assertThat(passes(0.0, 1.0, false)).isTrue();
        assertThat(passes(0.0, 5.0, false)).isTrue();
        assertThat(passes(0.0, 5.01, false)).isFalse();
        assertThat(passes(0.0, 0.79, true)).isFalse();
    }

    @Test
    @DisplayName("an injected RiskLimits profile cannot create a second band")
    void riskLimitsInjectionDoesNotChangeTheBand() {
        MandatoryConfluenceValidator v = newValidator();
        v.setActiveRiskLimits(RiskLimits.topstep50k());      // legacy signal band [2.0, inf)
        assertThat(v.validateStdvOte(ctx(1.5, 1.5, false)).passed()).isTrue();
        assertThat(v.validateStdvOte(ctx(5.9, 5.9, false)).passed()).isFalse();
        v.setActiveRiskLimits(RiskLimits.topstep50kScalp()); // scalp signal band [0.8, 1.5]
        assertThat(v.validateStdvOte(ctx(3.2, 3.2, true)).passed()).isTrue();
    }

    @Test
    @DisplayName("band is configurable (risk.rrFloor / risk.rrFloor.scalp / risk.rrCeiling)")
    void configurable() {
        assertThat(OteConfig.rrFloor(false)).isEqualTo(1.0);
        assertThat(OteConfig.rrFloor(true)).isEqualTo(0.8);
        assertThat(OteConfig.rrCeiling()).isEqualTo(5.0);
        System.setProperty("risk.rrFloor", "1.5");
        System.setProperty("risk.rrCeiling", "3.0");
        assertThat(passes(1.2, 1.2, false)).isFalse();
        assertThat(passes(3.2, 3.2, false)).isFalse();
        assertThat(passes(2.0, 2.9, false)).isTrue();
    }
}
