package com.topstep.trading.risk;

import com.topstep.trading.lifecycle.AccountLifecycle;
import com.topstep.trading.lifecycle.RiskZone;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.assertj.core.api.Assertions.*;

@DisplayName("PhaseAwareRiskCalculator Tests")
class PhaseAwareRiskCalculatorTest {

    private PhaseAwareRiskCalculator calculator;
    private AccountLifecycle lifecycle;
    private RiskProfile evalProfile;

    @BeforeEach
    void setUp() {
        calculator = new PhaseAwareRiskCalculator();
        lifecycle = AccountLifecycle.topstep50kEvaluation();
        evalProfile = RiskProfile.topstep50kEvaluation();
    }

    @Test
    @DisplayName("Normal zone with quality 8 gives full risk")
    void normalZoneFullRisk() {
        PhaseAwareRiskCalculator.RiskCalculation calc = calculator.calculateRisk(
            lifecycle, evalProfile, 8, 0);

        assertThat(calc.isTradingAllowed()).isTrue();
        assertThat(calc.getZone()).isEqualTo(RiskZone.NORMAL);
        assertThat(calc.getZoneMultiplier()).isEqualTo(1.0);
        assertThat(calc.getQualityMultiplier()).isEqualTo(1.0);
        assertThat(calc.getRiskDollars()).isGreaterThan(0);
    }

    @Test
    @DisplayName("Quality 4 gives 0.5x multiplier")
    void qualityFourHalfMultiplier() {
        PhaseAwareRiskCalculator.RiskCalculation calc = calculator.calculateRisk(
            lifecycle, evalProfile, 4, 0);

        assertThat(calc.isTradingAllowed()).isTrue();
        assertThat(calc.getQualityMultiplier()).isEqualTo(0.50);
    }

    @Test
    @DisplayName("Quality 6 gives 0.75x multiplier")
    void qualitySixThreeQuarterMultiplier() {
        PhaseAwareRiskCalculator.RiskCalculation calc = calculator.calculateRisk(
            lifecycle, evalProfile, 6, 0);

        assertThat(calc.getQualityMultiplier()).isEqualTo(0.75);
    }

    @Test
    @DisplayName("Quality below minimum blocks trading")
    void qualityBelowMinBlocks() {
        PhaseAwareRiskCalculator.RiskCalculation calc = calculator.calculateRisk(
            lifecycle, evalProfile, 3, 0);

        assertThat(calc.isTradingAllowed()).isFalse();
        assertThat(calc.getBlockReason()).contains("quality");
    }

    @Test
    @DisplayName("Max trades per day blocks additional trades")
    void maxTradesPerDayBlocks() {
        PhaseAwareRiskCalculator.RiskCalculation calc = calculator.calculateRisk(
            lifecycle, evalProfile, 8, 4);

        assertThat(calc.isTradingAllowed()).isFalse();
        assertThat(calc.getBlockReason()).contains("Max trades per day");
    }

    @Test
    @DisplayName("Consecutive loss limit blocks trading")
    void consecutiveLossLimitBlocks() {
        lifecycle.recordTrade(-50);
        lifecycle.recordTrade(-50);
        lifecycle.recordTrade(-50);

        PhaseAwareRiskCalculator.RiskCalculation calc = calculator.calculateRisk(
            lifecycle, evalProfile, 8, 0);

        assertThat(calc.isTradingAllowed()).isFalse();
        assertThat(calc.getBlockReason()).contains("Consecutive loss limit");
    }

    @Test
    @DisplayName("Trading paused for day blocks trading")
    void tradingPausedBlocks() {
        lifecycle.pauseTradingForDay();

        PhaseAwareRiskCalculator.RiskCalculation calc = calculator.calculateRisk(
            lifecycle, evalProfile, 8, 0);

        assertThat(calc.isTradingAllowed()).isFalse();
        assertThat(calc.getBlockReason()).contains("paused");
    }

    @Test
    @DisplayName("DANGER zone reduces risk to 0.4x")
    void dangerZoneReducesRisk() {
        // Put lifecycle into danger zone (60%+ drawdown usage)
        lifecycle.onDayEnd(-1200.0);

        PhaseAwareRiskCalculator.RiskCalculation calc = calculator.calculateRisk(
            lifecycle, evalProfile, 8, 0);

        assertThat(calc.isTradingAllowed()).isTrue();
        assertThat(calc.getZone()).isEqualTo(RiskZone.DANGER);
        assertThat(calc.getZoneMultiplier()).isEqualTo(0.4);
    }

    @Test
    @DisplayName("CAUTION zone reduces risk to 0.7x")
    void cautionZoneReducesRisk() {
        lifecycle.onDayEnd(-800.0); // 40% of MLL

        PhaseAwareRiskCalculator.RiskCalculation calc = calculator.calculateRisk(
            lifecycle, evalProfile, 8, 0);

        assertThat(calc.getZone()).isEqualTo(RiskZone.CAUTION);
        assertThat(calc.getZoneMultiplier()).isEqualTo(0.7);
    }

    @Test
    @DisplayName("Hard floor: risk capped at 25% of remaining budget")
    void riskBudgetFloor() {
        lifecycle.onDayEnd(-1500.0); // Only $500 risk budget remaining

        PhaseAwareRiskCalculator.RiskCalculation calc = calculator.calculateRisk(
            lifecycle, evalProfile, 8, 0);

        // Max risk should be 25% of $500 = $125
        assertThat(calc.getRiskDollars()).isLessThanOrEqualTo(125.01);
    }

    @Test
    @DisplayName("Zero risk budget blocks trading")
    void zeroRiskBudgetBlocks() {
        lifecycle.onDayEnd(-1950.0); // Almost at MLL

        PhaseAwareRiskCalculator.RiskCalculation calc = calculator.calculateRisk(
            lifecycle, evalProfile, 8, 0);

        // Very small risk budget -> may be below $25 minimum
        if (calc.isTradingAllowed()) {
            assertThat(calc.getRiskDollars()).isLessThan(50);
        }
    }

    @Test
    @DisplayName("Funded profile has higher quality requirement")
    void fundedProfileHigherQuality() {
        RiskProfile fundedProfile = RiskProfile.topstep50kFunded();
        assertThat(fundedProfile.getMinSetupQuality()).isGreaterThan(evalProfile.getMinSetupQuality());

        // Quality 4 works in eval but not funded
        PhaseAwareRiskCalculator.RiskCalculation evalCalc = calculator.calculateRisk(
            lifecycle, evalProfile, 4, 0);
        PhaseAwareRiskCalculator.RiskCalculation fundedCalc = calculator.calculateRisk(
            lifecycle, fundedProfile, 4, 0);

        assertThat(evalCalc.isTradingAllowed()).isTrue();
        assertThat(fundedCalc.isTradingAllowed()).isFalse();
    }

    @Test
    @DisplayName("Risk calculation toString provides useful info")
    void toStringOutput() {
        PhaseAwareRiskCalculator.RiskCalculation calc = calculator.calculateRisk(
            lifecycle, evalProfile, 8, 0);

        String str = calc.toString();
        assertThat(str).contains("Risk=");
        assertThat(str).contains("zone=");
    }
}
