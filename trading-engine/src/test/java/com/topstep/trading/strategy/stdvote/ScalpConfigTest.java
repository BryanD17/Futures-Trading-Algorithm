package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.domain.RiskLimits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SA3 tests for {@link ScalpConfig} — the scalpMode.enabled selection point
 * and the scalp.* tunables (mirrors the stdvOte.enabled property pattern).
 */
@DisplayName("ScalpConfig (scalpMode.enabled + scalp.* properties)")
class ScalpConfigTest {

    @AfterEach
    void clearProperties() {
        System.clearProperty(ScalpConfig.ENABLED_PROPERTY);
        System.clearProperty(ScalpConfig.BREAKEVEN_AT_HALF_R_PROPERTY);
        System.clearProperty(ScalpConfig.MIN_TARGET_CLEARANCE_TICKS_PROPERTY);
        System.clearProperty(ScalpConfig.CANDIDATE_WINDOW_R_PROPERTY);
    }

    @Test
    @DisplayName("default is OFF — absent property means legacy behaviour")
    void defaultOff() {
        System.clearProperty(ScalpConfig.ENABLED_PROPERTY);
        assertThat(ScalpConfig.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("-DscalpMode.enabled=true switches on; case-insensitive; junk stays off")
    void enabledFlagParsing() {
        System.setProperty(ScalpConfig.ENABLED_PROPERTY, "true");
        assertThat(ScalpConfig.isEnabled()).isTrue();
        System.setProperty(ScalpConfig.ENABLED_PROPERTY, "TRUE ");
        assertThat(ScalpConfig.isEnabled()).isTrue();
        System.setProperty(ScalpConfig.ENABLED_PROPERTY, "false");
        assertThat(ScalpConfig.isEnabled()).isFalse();
        System.setProperty(ScalpConfig.ENABLED_PROPERTY, "banana");
        assertThat(ScalpConfig.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("RiskLimits selection: legacy topstep50k() when off, topstep50kScalp() when on")
    void riskLimitsSelection() {
        System.clearProperty(ScalpConfig.ENABLED_PROPERTY);
        RiskLimits legacy = ScalpConfig.activeRiskLimits();
        assertThat(legacy.getRiskPerTrade()).isEqualTo(250.0);
        assertThat(legacy.getMinRiskRewardRatio()).isEqualTo(3.0);
        assertThat(legacy.getMaxTradesPerDay()).isZero();

        System.setProperty(ScalpConfig.ENABLED_PROPERTY, "true");
        RiskLimits scalp = ScalpConfig.activeRiskLimits();
        assertThat(scalp.getRiskPerTrade()).isEqualTo(150.0);
        assertThat(scalp.getMinRiskRewardRatio()).isEqualTo(0.8);
        assertThat(scalp.getMaxRiskRewardRatio()).isEqualTo(1.5);
        assertThat(scalp.getMaxTradesPerDay()).isEqualTo(6);
        assertThat(scalp.getMaxConsecutiveLosses()).isEqualTo(3);
    }

    @Test
    @DisplayName("breakevenAtHalfR defaults to true and is toggleable")
    void breakevenFlag() {
        assertThat(ScalpConfig.breakevenAtHalfR()).isTrue();
        System.setProperty(ScalpConfig.BREAKEVEN_AT_HALF_R_PROPERTY, "false");
        assertThat(ScalpConfig.breakevenAtHalfR()).isFalse();
    }

    @Test
    @DisplayName("tunables: defaults 2 ticks / 1.5R; overridable; junk falls back")
    void tunables() {
        assertThat(ScalpConfig.minTargetClearanceTicks()).isEqualTo(2);
        assertThat(ScalpConfig.candidateWindowR()).isEqualTo(1.5);

        System.setProperty(ScalpConfig.MIN_TARGET_CLEARANCE_TICKS_PROPERTY, "3");
        System.setProperty(ScalpConfig.CANDIDATE_WINDOW_R_PROPERTY, "2.0");
        assertThat(ScalpConfig.minTargetClearanceTicks()).isEqualTo(3);
        assertThat(ScalpConfig.candidateWindowR()).isEqualTo(2.0);

        System.setProperty(ScalpConfig.MIN_TARGET_CLEARANCE_TICKS_PROPERTY, "junk");
        System.setProperty(ScalpConfig.CANDIDATE_WINDOW_R_PROPERTY, "junk");
        assertThat(ScalpConfig.minTargetClearanceTicks()).isEqualTo(2);
        assertThat(ScalpConfig.candidateWindowR()).isEqualTo(1.5);
    }

    @Test
    @DisplayName("targetCalculator() builds from the live property values")
    void targetCalculatorFactory() {
        assertThat(ScalpConfig.targetCalculator()).isNotNull();
    }
}
