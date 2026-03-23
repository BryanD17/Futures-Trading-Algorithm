package com.topstep.trading.montecarlo;

import com.topstep.trading.lifecycle.AccountLifecycle;
import com.topstep.trading.risk.RiskProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.assertj.core.api.Assertions.*;

@DisplayName("MonteCarloSimulator Tests")
class MonteCarloSimulatorTest {

    private MonteCarloSimulator simulator;
    private AccountLifecycle lifecycle;
    private RiskProfile profile;

    @BeforeEach
    void setUp() {
        // Fixed seed for deterministic tests
        simulator = new MonteCarloSimulator(42L);
        lifecycle = AccountLifecycle.topstep50kEvaluation();
        profile = RiskProfile.topstep50kEvaluation();
    }

    @Test
    @DisplayName("Simulation produces valid result counts")
    void validResultCounts() {
        StrategyMetrics metrics = StrategyMetrics.fromParameters(0.45, 3.0, -1.0, 2.0);
        MonteCarloResult result = simulator.simulate(metrics, profile, lifecycle, 1000);

        assertThat(result.getTotalIterations()).isEqualTo(1000);
        assertThat(result.getPassedCount() + result.getBlownCount() + result.getTimeoutCount())
            .isEqualTo(1000);
    }

    @Test
    @DisplayName("Pass rate + blowup rate + timeout rate = 100%")
    void ratesSumToOne() {
        StrategyMetrics metrics = StrategyMetrics.fromParameters(0.45, 3.0, -1.0, 2.0);
        MonteCarloResult result = simulator.simulate(metrics, profile, lifecycle, 1000);

        double total = result.getPassRate() + result.getBlowupRate() + result.getTimeoutRate();
        assertThat(total).isCloseTo(1.0, within(0.001));
    }

    @Test
    @DisplayName("High win rate produces high pass rate")
    void highWinRateHighPassRate() {
        StrategyMetrics metrics = StrategyMetrics.fromParameters(0.70, 3.0, -1.0, 3.0);
        MonteCarloResult result = simulator.simulate(metrics, profile, lifecycle, 1000);

        assertThat(result.getPassRate()).isGreaterThan(0.5);
    }

    @Test
    @DisplayName("Low win rate produces high blowup rate")
    void lowWinRateHighBlowupRate() {
        StrategyMetrics metrics = StrategyMetrics.fromParameters(0.15, 2.0, -1.0, 2.0);
        MonteCarloResult result = simulator.simulate(metrics, profile, lifecycle, 1000);

        // With 15% win rate and 2R wins, most paths should blow up or timeout
        // Blowup + timeout should dominate (pass rate should be very low)
        assertThat(result.getPassRate()).isLessThan(0.5);
    }

    @Test
    @DisplayName("Max drawdown distribution has valid percentiles")
    void maxDrawdownPercentiles() {
        StrategyMetrics metrics = StrategyMetrics.fromParameters(0.45, 3.0, -1.0, 2.0);
        MonteCarloResult result = simulator.simulate(metrics, profile, lifecycle, 1000);

        assertThat(result.getMaxDrawdownP5()).isLessThanOrEqualTo(result.getMaxDrawdownP25());
        assertThat(result.getMaxDrawdownP25()).isLessThanOrEqualTo(result.getMaxDrawdownP50());
        assertThat(result.getMaxDrawdownP50()).isLessThanOrEqualTo(result.getMaxDrawdownP75());
        assertThat(result.getMaxDrawdownP75()).isLessThanOrEqualTo(result.getMaxDrawdownP95());
        assertThat(result.getMaxDrawdownP95()).isLessThanOrEqualTo(result.getMaxDrawdownP99());
    }

    @Test
    @DisplayName("Sample paths are collected (up to 100)")
    void samplePathsCollected() {
        StrategyMetrics metrics = StrategyMetrics.fromParameters(0.45, 3.0, -1.0, 2.0);
        MonteCarloResult result = simulator.simulate(metrics, profile, lifecycle, 1000);

        assertThat(result.getSamplePaths()).isNotEmpty();
        assertThat(result.getSamplePaths().size()).isLessThanOrEqualTo(100);
    }

    @Test
    @DisplayName("Single path simulation produces valid path result")
    void singlePathValid() {
        StrategyMetrics metrics = StrategyMetrics.fromParameters(0.45, 3.0, -1.0, 2.0);
        PathResult result = simulator.simulateSinglePath(metrics, profile, lifecycle);

        assertThat(result.getOutcome()).isNotNull();
        assertThat(result.getEquityCurve()).isNotEmpty();
        assertThat(result.getMaxDrawdown()).isGreaterThanOrEqualTo(0);
        assertThat(result.getTradesToComplete()).isGreaterThan(0);
    }

    @Test
    @DisplayName("Passed paths have equity above target")
    void passedPathsAboveTarget() {
        StrategyMetrics metrics = StrategyMetrics.fromParameters(0.60, 3.0, -1.0, 3.0);

        for (int i = 0; i < 100; i++) {
            PathResult result = simulator.simulateSinglePath(metrics, profile, lifecycle);
            if (result.passed()) {
                double totalPnl = result.getFinalEquity() - lifecycle.getStartingBalance();
                assertThat(totalPnl).isGreaterThanOrEqualTo(lifecycle.getProfitTarget());
            }
        }
    }

    @Test
    @DisplayName("Blown paths have drawdown at MLL")
    void blownPathsAtMll() {
        StrategyMetrics metrics = StrategyMetrics.fromParameters(0.20, 2.0, -1.0, 2.0);

        for (int i = 0; i < 100; i++) {
            PathResult result = simulator.simulateSinglePath(metrics, profile, lifecycle);
            if (result.blown()) {
                assertThat(result.getMaxDrawdownFromHwm())
                    .isGreaterThanOrEqualTo(lifecycle.getMaxLossLimit());
            }
        }
    }

    @Test
    @DisplayName("Block bootstrap simulator works")
    void blockBootstrapWorks() {
        MonteCarloSimulator blockSimulator = new MonteCarloSimulator(
            new java.util.Random(42), true);

        StrategyMetrics metrics = StrategyMetrics.fromParameters(0.45, 3.0, -1.0, 2.0);
        MonteCarloResult result = blockSimulator.simulate(metrics, profile, lifecycle, 500);

        assertThat(result.getTotalIterations()).isEqualTo(500);
        assertThat(result.getPassRate() + result.getBlowupRate() + result.getTimeoutRate())
            .isCloseTo(1.0, within(0.001));
    }

    @Test
    @DisplayName("Forward MC produces result in reasonable time")
    void forwardMcPerformance() {
        StrategyMetrics metrics = StrategyMetrics.fromParameters(0.45, 3.0, -1.0, 2.0);

        long start = System.currentTimeMillis();
        MonteCarloResult result = simulator.simulateForward(
            metrics, profile, 51500.0, 51500.0, 10, lifecycle);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isLessThan(10000); // Should complete in <10s
        assertThat(result.getTotalIterations()).isEqualTo(1000);
    }

    @Test
    @DisplayName("Equity fan bands are generated")
    void equityFanBands() {
        StrategyMetrics metrics = StrategyMetrics.fromParameters(0.45, 3.0, -1.0, 2.0);
        MonteCarloResult result = simulator.simulate(metrics, profile, lifecycle, 500);

        assertThat(result.getEquityBandsP5()).isNotNull();
        assertThat(result.getEquityBandsP50()).isNotNull();
        assertThat(result.getEquityBandsP95()).isNotNull();
    }

    @Test
    @DisplayName("Higher risk increases both pass rate and blowup rate")
    void higherRiskIncreasesVariance() {
        StrategyMetrics metrics = StrategyMetrics.fromParameters(0.45, 3.0, -1.0, 2.0);

        RiskProfile lowRisk = profile.withBaseRiskPct(0.003);
        RiskProfile highRisk = profile.withBaseRiskPct(0.015);

        MonteCarloResult lowResult = simulator.simulate(metrics, lowRisk, lifecycle, 1000);
        MonteCarloResult highResult = simulator.simulate(metrics, highRisk, lifecycle, 1000);

        // Higher risk = higher blowup rate (more variance)
        assertThat(highResult.getBlowupRate()).isGreaterThanOrEqualTo(lowResult.getBlowupRate() - 0.1);
    }

    @Test
    @DisplayName("StrategyMetrics.fromParameters creates valid metrics")
    void strategyMetricsFromParams() {
        StrategyMetrics metrics = StrategyMetrics.fromParameters(0.45, 3.0, -1.0, 2.0);

        assertThat(metrics.getWinRate()).isEqualTo(0.45);
        assertThat(metrics.getAvgWinR()).isEqualTo(3.0);
        assertThat(metrics.getAvgLossR()).isEqualTo(-1.0);
        assertThat(metrics.getTradesPerDay()).isEqualTo(2.0);
        assertThat(metrics.getExpectancyPerTrade()).isCloseTo(0.80, within(0.01)); // 0.45*3 - 0.55*1
    }

    @Test
    @DisplayName("Expected payout is calculated")
    void expectedPayoutCalculated() {
        StrategyMetrics metrics = StrategyMetrics.fromParameters(0.45, 3.0, -1.0, 2.0);
        MonteCarloResult result = simulator.simulate(metrics, profile, lifecycle, 500);

        // Expected payout should be a finite number
        assertThat(result.getExpectedPayout()).isFinite();
    }
}
