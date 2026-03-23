package com.topstep.api.controller;

import com.topstep.api.entity.SimulationResultEntity;
import com.topstep.api.repository.SimulationResultsRepository;
import com.topstep.trading.lifecycle.AccountLifecycle;
import com.topstep.trading.montecarlo.MonteCarloResult;
import com.topstep.trading.montecarlo.MonteCarloSimulator;
import com.topstep.trading.montecarlo.StrategyMetrics;
import com.topstep.trading.optimization.OptimizationResult;
import com.topstep.trading.optimization.RiskOptimizer;
import com.topstep.trading.risk.RiskProfile;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * REST controller for Monte Carlo simulation and optimization endpoints.
 *
 * POST /api/simulate           - Run MC simulation with custom params
 * POST /api/optimize           - Run risk optimization sweep
 * GET  /api/simulate/results/{id} - Retrieve cached simulation results
 */
@RestController
@RequestMapping("/api")
public class SimulationController {

    private final SimulationResultsRepository resultsRepo;
    private final ExecutorService executorService;
    private final Map<String, CompletableFuture<Map<String, Object>>> pendingJobs;

    @Autowired
    public SimulationController(SimulationResultsRepository resultsRepo) {
        this.resultsRepo = resultsRepo;
        this.executorService = Executors.newFixedThreadPool(2);
        this.pendingJobs = new ConcurrentHashMap<>();
    }

    /**
     * POST /api/simulate - Run MC simulation with custom parameters.
     */
    @PostMapping("/simulate")
    public ResponseEntity<Map<String, Object>> runSimulation(
            @RequestBody(required = false) Map<String, Object> params) {

        double winRate = getDouble(params, "winRate", 0.45);
        double avgWinR = getDouble(params, "avgWinR", 3.0);
        double avgLossR = getDouble(params, "avgLossR", -1.0);
        double tradesPerDay = getDouble(params, "tradesPerDay", 2.0);
        int iterations = getInt(params, "iterations", 10000);
        double baseRiskPct = getDouble(params, "baseRiskPct", 0.005);

        String jobId = UUID.randomUUID().toString().substring(0, 8);

        CompletableFuture<Map<String, Object>> future = CompletableFuture.supplyAsync(() -> {
            StrategyMetrics metrics = StrategyMetrics.fromParameters(winRate, avgWinR, avgLossR, tradesPerDay);
            RiskProfile profile = RiskProfile.topstep50kEvaluation().withBaseRiskPct(baseRiskPct);
            AccountLifecycle lifecycle = AccountLifecycle.topstep50kEvaluation();
            MonteCarloSimulator simulator = new MonteCarloSimulator();

            MonteCarloResult result = simulator.simulate(metrics, profile, lifecycle, iterations);

            // Cache result in database
            SimulationResultEntity entity = new SimulationResultEntity();
            entity.setRunAt(Instant.now());
            entity.setIterations(iterations);
            entity.setPassRate(result.getPassRate());
            entity.setBlowupRate(result.getBlowupRate());
            entity.setExpectedPayout(result.getExpectedPayout());
            resultsRepo.save(entity);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("jobId", jobId);
            response.put("status", "completed");
            response.put("iterations", result.getTotalIterations());
            response.put("passRate", result.getPassRate());
            response.put("blowupRate", result.getBlowupRate());
            response.put("timeoutRate", result.getTimeoutRate());
            response.put("avgTradesToPass", result.getAvgTradesToPass());
            response.put("avgDaysToPass", result.getAvgDaysToPass());
            response.put("expectedPayout", result.getExpectedPayout());
            response.put("expectedPayoutPerDollar", result.getExpectedPayoutPerChallengeDollar());
            response.put("maxDrawdownP50", result.getMaxDrawdownP50());
            response.put("maxDrawdownP95", result.getMaxDrawdownP95());
            response.put("maxDrawdownP99", result.getMaxDrawdownP99());
            response.put("resultId", entity.getId());
            return response;
        }, executorService);

        pendingJobs.put(jobId, future);

        // Try to get result quickly, otherwise return async
        try {
            Map<String, Object> result = future.get(10, TimeUnit.SECONDS);
            return ResponseEntity.ok(result);
        } catch (TimeoutException e) {
            Map<String, Object> pending = new LinkedHashMap<>();
            pending.put("jobId", jobId);
            pending.put("status", "running");
            pending.put("message", "Simulation in progress. Poll GET /api/simulate/job/" + jobId);
            return ResponseEntity.accepted().body(pending);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(
                Map.of("error", "Simulation failed: " + e.getMessage()));
        }
    }

    /**
     * POST /api/optimize - Run risk optimization sweep.
     */
    @PostMapping("/optimize")
    public ResponseEntity<Map<String, Object>> runOptimization(
            @RequestBody(required = false) Map<String, Object> params) {

        double winRate = getDouble(params, "winRate", 0.45);
        double avgWinR = getDouble(params, "avgWinR", 3.0);
        double avgLossR = getDouble(params, "avgLossR", -1.0);
        double tradesPerDay = getDouble(params, "tradesPerDay", 2.0);

        StrategyMetrics metrics = StrategyMetrics.fromParameters(winRate, avgWinR, avgLossR, tradesPerDay);
        RiskProfile baseProfile = RiskProfile.topstep50kEvaluation();
        AccountLifecycle lifecycle = AccountLifecycle.topstep50kEvaluation();

        RiskOptimizer optimizer = new RiskOptimizer();
        OptimizationResult optResult = optimizer.optimizeRisk(metrics, baseProfile, lifecycle);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("optimalRiskPct", optResult.getOptimalRiskPct());

        if (optResult.getOptimalPoint() != null) {
            response.put("optimalPassRate", optResult.getOptimalPoint().getPassRate());
            response.put("optimalExpectedPayout", optResult.getOptimalPoint().getExpectedPayout());
            response.put("optimalMaxDrawdownP95", optResult.getOptimalPoint().getMaxDrawdownP95());
        }

        List<Map<String, Object>> sweepData = new ArrayList<>();
        optResult.getSweepCurve().forEach(point -> {
            Map<String, Object> dp = new LinkedHashMap<>();
            dp.put("riskPct", point.getRiskPct());
            dp.put("passRate", point.getPassRate());
            dp.put("blowupRate", point.getBlowupRate());
            dp.put("expectedPayout", point.getExpectedPayout());
            dp.put("avgTradesToPass", point.getAvgTradesToPass());
            dp.put("maxDrawdownP95", point.getMaxDrawdownP95());
            sweepData.add(dp);
        });
        response.put("sweepCurve", sweepData);

        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/simulate/results/{id} - Retrieve cached simulation result.
     */
    @GetMapping("/simulate/results/{id}")
    public ResponseEntity<Map<String, Object>> getSimulationResult(@PathVariable Long id) {
        return resultsRepo.findById(id)
            .map(entity -> {
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("id", entity.getId());
                response.put("runAt", entity.getRunAt());
                response.put("iterations", entity.getIterations());
                response.put("passRate", entity.getPassRate());
                response.put("blowupRate", entity.getBlowupRate());
                response.put("expectedPayout", entity.getExpectedPayout());
                response.put("optimalRiskPct", entity.getOptimalRiskPct());
                return ResponseEntity.ok(response);
            })
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * GET /api/simulate/results - List recent simulation results.
     */
    @GetMapping("/simulate/results")
    public ResponseEntity<List<SimulationResultEntity>> getRecentResults() {
        return ResponseEntity.ok(resultsRepo.findTop10ByOrderByRunAtDesc());
    }

    private double getDouble(Map<String, Object> params, String key, double defaultVal) {
        if (params == null || !params.containsKey(key)) return defaultVal;
        Object val = params.get(key);
        if (val instanceof Number) return ((Number) val).doubleValue();
        return defaultVal;
    }

    private int getInt(Map<String, Object> params, String key, int defaultVal) {
        if (params == null || !params.containsKey(key)) return defaultVal;
        Object val = params.get(key);
        if (val instanceof Number) return ((Number) val).intValue();
        return defaultVal;
    }
}
