package com.topstep.api.entity;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * JPA entity for mc_simulation_results table.
 * Caches Monte Carlo simulation results for dashboard display.
 */
@Entity
@Table(name = "mc_simulation_results")
public class SimulationResultEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Instant runAt;

    @Column(columnDefinition = "TEXT")
    private String strategyConfig;

    @Column(columnDefinition = "TEXT")
    private String riskProfileConfig;

    @Column(columnDefinition = "TEXT")
    private String propFirmRules;

    private int iterations;

    private double passRate;

    private double blowupRate;

    private double expectedPayout;

    private double optimalRiskPct;

    @Column(columnDefinition = "TEXT")
    private String riskSweepData;

    public SimulationResultEntity() {}

    // Getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Instant getRunAt() { return runAt; }
    public void setRunAt(Instant runAt) { this.runAt = runAt; }
    public String getStrategyConfig() { return strategyConfig; }
    public void setStrategyConfig(String strategyConfig) { this.strategyConfig = strategyConfig; }
    public String getRiskProfileConfig() { return riskProfileConfig; }
    public void setRiskProfileConfig(String riskProfileConfig) { this.riskProfileConfig = riskProfileConfig; }
    public String getPropFirmRules() { return propFirmRules; }
    public void setPropFirmRules(String propFirmRules) { this.propFirmRules = propFirmRules; }
    public int getIterations() { return iterations; }
    public void setIterations(int iterations) { this.iterations = iterations; }
    public double getPassRate() { return passRate; }
    public void setPassRate(double passRate) { this.passRate = passRate; }
    public double getBlowupRate() { return blowupRate; }
    public void setBlowupRate(double blowupRate) { this.blowupRate = blowupRate; }
    public double getExpectedPayout() { return expectedPayout; }
    public void setExpectedPayout(double expectedPayout) { this.expectedPayout = expectedPayout; }
    public double getOptimalRiskPct() { return optimalRiskPct; }
    public void setOptimalRiskPct(double optimalRiskPct) { this.optimalRiskPct = optimalRiskPct; }
    public String getRiskSweepData() { return riskSweepData; }
    public void setRiskSweepData(String riskSweepData) { this.riskSweepData = riskSweepData; }
}
