package com.topstep.trading.optimization;

import com.topstep.trading.lifecycle.AccountLifecycle;
import com.topstep.trading.montecarlo.MonteCarloSimulator;
import com.topstep.trading.montecarlo.StrategyMetrics;
import com.topstep.trading.risk.RiskProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.assertj.core.api.Assertions.*;

@DisplayName("RiskOptimizer Tests")
class RiskOptimizerTest {

    private RiskOptimizer optimizer;

    @BeforeEach
    void setUp() {
        // Use deterministic simulator for tests
        MonteCarloSimulator simulator = new MonteCarloSimulator(42L);
        optimizer = new RiskOptimizer(simulator);
    }

    @Test
    @DisplayName("Optimization sweep produces data points")
    void sweepProducesDataPoints() {
        StrategyMetrics metrics = StrategyMetrics.fromParameters(0.45, 3.0, -1.0, 2.0);
        RiskProfile profile = RiskProfile.topstep50kEvaluation();
        AccountLifecycle lifecycle = AccountLifecycle.topstep50kEvaluation();

        OptimizationResult result = optimizer.optimizeRisk(metrics, profile, lifecycle,
            0.005, 0.015, 0.005);

        assertThat(result.getSweepCurve()).isNotEmpty();
        assertThat(result.getSweepCurve().size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("Optimal point is identified")
    void optimalPointIdentified() {
        StrategyMetrics metrics = StrategyMetrics.fromParameters(0.45, 3.0, -1.0, 2.0);
        RiskProfile profile = RiskProfile.topstep50kEvaluation();
        AccountLifecycle lifecycle = AccountLifecycle.topstep50kEvaluation();

        OptimizationResult result = optimizer.optimizeRisk(metrics, profile, lifecycle,
            0.005, 0.015, 0.005);

        assertThat(result.getOptimalPoint()).isNotNull();
        assertThat(result.getOptimalRiskPct()).isBetween(0.001, 0.03);
    }

    @Test
    @DisplayName("Data points contain all required fields")
    void dataPointsComplete() {
        StrategyMetrics metrics = StrategyMetrics.fromParameters(0.45, 3.0, -1.0, 2.0);
        RiskProfile profile = RiskProfile.topstep50kEvaluation();
        AccountLifecycle lifecycle = AccountLifecycle.topstep50kEvaluation();

        OptimizationResult result = optimizer.optimizeRisk(metrics, profile, lifecycle,
            0.005, 0.01, 0.005);

        for (OptimizationDataPoint point : result.getSweepCurve()) {
            assertThat(point.getRiskPct()).isGreaterThan(0);
            assertThat(point.getPassRate()).isBetween(0.0, 1.0);
            assertThat(point.getBlowupRate()).isBetween(0.0, 1.0);
            assertThat(point.getExpectedPayout()).isFinite();
        }
    }

    @Test
    @DisplayName("Walk-forward validation runs both phases")
    void walkForwardValidation() {
        StrategyMetrics inSample = StrategyMetrics.fromParameters(0.45, 3.0, -1.0, 2.0);
        StrategyMetrics outOfSample = StrategyMetrics.fromParameters(0.43, 2.8, -1.0, 2.0);
        RiskProfile profile = RiskProfile.topstep50kEvaluation();
        AccountLifecycle lifecycle = AccountLifecycle.topstep50kEvaluation();

        RiskOptimizer.WalkForwardResult result = optimizer.walkForwardValidation(
            inSample, outOfSample, profile, lifecycle);

        assertThat(result.getOptimalRiskPct()).isGreaterThan(0);
        assertThat(result.getOosPassRate()).isBetween(0.0, 1.0);
        assertThat(result.getNaivePassRate()).isBetween(0.0, 1.0);
    }
}
