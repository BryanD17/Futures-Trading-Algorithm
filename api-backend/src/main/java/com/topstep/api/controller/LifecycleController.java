package com.topstep.api.controller;

import com.topstep.api.entity.ChallengeEconomicsEntity;
import com.topstep.api.entity.EquityPathEntity;
import com.topstep.api.repository.ChallengeEconomicsRepository;
import com.topstep.api.repository.EquityPathRepository;
import com.topstep.trading.EngineFacade;
import com.topstep.trading.lifecycle.AccountLifecycle;
import com.topstep.trading.lifecycle.AccountPhase;
import com.topstep.trading.lifecycle.RiskZone;
import com.topstep.trading.montecarlo.MonteCarloResult;
import com.topstep.trading.montecarlo.MonteCarloSimulator;
import com.topstep.trading.montecarlo.StrategyMetrics;
import com.topstep.trading.risk.RiskProfile;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * REST controller for account lifecycle endpoints.
 *
 * GET /api/lifecycle             - Current phase, metrics, risk zone
 * GET /api/lifecycle/pass-probability - Live MC estimate from current state
 * GET /api/lifecycle/equity-fan  - MC fan chart band data
 * GET /api/lifecycle/economics   - Challenge ROI tracking
 */
@RestController
@RequestMapping("/api/lifecycle")
public class LifecycleController {

    private final ChallengeEconomicsRepository economicsRepo;
    private final EquityPathRepository equityPathRepo;

    // Lifecycle instance (shared with engine)
    private volatile AccountLifecycle lifecycle;

    @Autowired
    public LifecycleController(ChallengeEconomicsRepository economicsRepo,
                                EquityPathRepository equityPathRepo) {
        this.economicsRepo = economicsRepo;
        this.equityPathRepo = equityPathRepo;
    }

    /**
     * Set the active lifecycle (called by engine on startup).
     */
    public void setLifecycle(AccountLifecycle lifecycle) {
        this.lifecycle = lifecycle;
    }

    /**
     * GET /api/lifecycle - Current phase, metrics, risk zone.
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getLifecycleState() {
        Map<String, Object> response = new LinkedHashMap<>();

        if (lifecycle != null) {
            response.put("phase", lifecycle.getCurrentPhase().name());
            response.put("phaseDisplayName", lifecycle.getCurrentPhase().getDisplayName());
            response.put("currentEquity", lifecycle.getCurrentEquity());
            response.put("startingBalance", lifecycle.getStartingBalance());
            response.put("profitTarget", lifecycle.getProfitTarget());
            response.put("maxLossLimit", lifecycle.getMaxLossLimit());
            response.put("dailyLossLimit", lifecycle.getDailyLossLimit());
            response.put("distanceToTarget", lifecycle.distanceToTarget());
            response.put("distanceToBlowup", lifecycle.distanceToBlowup());
            response.put("targetCompletionPct", lifecycle.targetCompletionPct());
            response.put("drawdownUsagePct", lifecycle.drawdownUsagePct());
            response.put("riskBudgetRemaining", lifecycle.riskBudgetRemaining());

            RiskZone zone = lifecycle.getCurrentRiskZone();
            response.put("riskZone", zone.name());
            response.put("riskZoneDescription", zone.getDescription());
            response.put("riskZoneColor", zone.getColor());
            response.put("riskZoneMultiplier", zone.getRiskMultiplier());

            response.put("tradingDaysElapsed", lifecycle.getTradingDaysElapsed());
            response.put("totalTradesTaken", lifecycle.getTotalTradesTaken());
            response.put("consecutiveLosses", lifecycle.getConsecutiveLosses());
            response.put("tradingPausedForDay", lifecycle.isTradingPausedForDay());
            response.put("highWaterMark", lifecycle.getCurrentHighWaterMark());
        } else {
            response.put("phase", "NOT_INITIALIZED");
            response.put("message", "Lifecycle not active. Start trading engine first.");
        }

        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/lifecycle/pass-probability - Live MC estimate from current state.
     * Runs a fast forward-looking MC (1K iterations, <5s).
     */
    @GetMapping("/pass-probability")
    public ResponseEntity<Map<String, Object>> getPassProbability() {
        Map<String, Object> response = new LinkedHashMap<>();

        if (lifecycle == null) {
            response.put("passProbability", 0.0);
            response.put("message", "Lifecycle not active");
            return ResponseEntity.ok(response);
        }

        // Run forward-looking mini MC
        StrategyMetrics defaultMetrics = StrategyMetrics.fromParameters(
            0.45, 3.0, -1.0, 2.0); // Default assumptions
        RiskProfile profile = RiskProfile.topstep50kEvaluation();
        MonteCarloSimulator simulator = new MonteCarloSimulator();

        MonteCarloResult result = simulator.simulateForward(
            defaultMetrics, profile,
            lifecycle.getCurrentEquity(),
            lifecycle.getCurrentHighWaterMark(),
            lifecycle.getTradingDaysElapsed(),
            lifecycle);

        response.put("passProbability", result.getPassRate());
        response.put("blowupProbability", result.getBlowupRate());
        response.put("timeoutProbability", result.getTimeoutRate());
        response.put("expectedPayout", result.getExpectedPayout());
        response.put("iterations", result.getTotalIterations());
        response.put("currentPhase", lifecycle.getCurrentPhase().name());
        response.put("targetCompletionPct", lifecycle.targetCompletionPct());
        response.put("drawdownUsagePct", lifecycle.drawdownUsagePct());

        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/lifecycle/equity-fan - MC fan chart band data for overlay.
     */
    @GetMapping("/equity-fan")
    public ResponseEntity<Map<String, Object>> getEquityFanData() {
        Map<String, Object> response = new LinkedHashMap<>();

        if (lifecycle == null) {
            response.put("bands", Collections.emptyList());
            return ResponseEntity.ok(response);
        }

        // Run MC for fan chart
        StrategyMetrics defaultMetrics = StrategyMetrics.fromParameters(
            0.45, 3.0, -1.0, 2.0);
        RiskProfile profile = RiskProfile.topstep50kEvaluation();
        MonteCarloSimulator simulator = new MonteCarloSimulator();
        MonteCarloResult result = simulator.simulate(defaultMetrics, profile, lifecycle, 2000);

        // Build fan chart data
        List<Map<String, Object>> bands = new ArrayList<>();
        double[][] p5 = result.getEquityBandsP5();
        double[][] p25 = result.getEquityBandsP25();
        double[][] p50 = result.getEquityBandsP50();
        double[][] p75 = result.getEquityBandsP75();
        double[][] p95 = result.getEquityBandsP95();

        if (p5 != null) {
            for (int d = 0; d < p5.length; d++) {
                Map<String, Object> dayData = new LinkedHashMap<>();
                dayData.put("day", d);
                dayData.put("p5", p5[d][0]);
                dayData.put("p25", p25[d][0]);
                dayData.put("p50", p50[d][0]);
                dayData.put("p75", p75[d][0]);
                dayData.put("p95", p95[d][0]);
                bands.add(dayData);
            }
        }

        response.put("bands", bands);
        response.put("actualPath", lifecycle.getEquityPathHistory());
        response.put("profitTargetLine", lifecycle.getStartingBalance() + lifecycle.getProfitTarget());
        response.put("blowupLine", lifecycle.getCurrentHighWaterMark() - lifecycle.getMaxLossLimit());

        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/lifecycle/economics - Challenge ROI tracking.
     */
    @GetMapping("/economics")
    public ResponseEntity<Map<String, Object>> getEconomics() {
        Map<String, Object> response = new LinkedHashMap<>();

        List<ChallengeEconomicsEntity> economics = economicsRepo.findAllByOrderByRecordedAtDesc();

        Double totalFees = economicsRepo.totalFeesPaid();
        Double totalPayouts = economicsRepo.totalPayoutsReceived();
        Double totalNet = economicsRepo.totalNetProfit();
        long passed = economicsRepo.countPassed();
        long total = economicsRepo.countTotal();

        response.put("challenges", economics);
        response.put("totalFeesPaid", totalFees != null ? totalFees : 0.0);
        response.put("totalPayoutsReceived", totalPayouts != null ? totalPayouts : 0.0);
        response.put("totalNetProfit", totalNet != null ? totalNet : 0.0);
        response.put("challengesPassed", passed);
        response.put("challengesTotal", total);
        response.put("passRate", total > 0 ? (double) passed / total : 0.0);
        response.put("roi", totalFees != null && totalFees > 0
            ? ((totalPayouts != null ? totalPayouts : 0.0) - totalFees) / totalFees : 0.0);

        return ResponseEntity.ok(response);
    }
}
