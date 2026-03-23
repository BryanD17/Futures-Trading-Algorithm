package com.topstep.api.entity;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * JPA entity for account_lifecycle table.
 * Tracks each challenge attempt's lifecycle from start to finish.
 */
@Entity
@Table(name = "account_lifecycle", indexes = {
    @Index(name = "idx_lifecycle_account", columnList = "accountId"),
    @Index(name = "idx_lifecycle_phase", columnList = "phase")
})
public class AccountLifecycleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String accountId;

    @Column(nullable = false)
    private String phase;

    @Column(nullable = false)
    private double challengeFee;

    @Column(nullable = false)
    private double startingBalance;

    @Column(nullable = false)
    private double profitTarget;

    @Column(nullable = false)
    private double maxLossLimit;

    @Column(nullable = false)
    private double dailyLossLimit;

    @Column(nullable = false)
    private Instant startedAt;

    private Instant endedAt;

    private String outcome;

    private double finalEquity;

    private int tradesTaken;

    private int tradingDays;

    public AccountLifecycleEntity() {}

    // Getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public String getPhase() { return phase; }
    public void setPhase(String phase) { this.phase = phase; }
    public double getChallengeFee() { return challengeFee; }
    public void setChallengeFee(double challengeFee) { this.challengeFee = challengeFee; }
    public double getStartingBalance() { return startingBalance; }
    public void setStartingBalance(double startingBalance) { this.startingBalance = startingBalance; }
    public double getProfitTarget() { return profitTarget; }
    public void setProfitTarget(double profitTarget) { this.profitTarget = profitTarget; }
    public double getMaxLossLimit() { return maxLossLimit; }
    public void setMaxLossLimit(double maxLossLimit) { this.maxLossLimit = maxLossLimit; }
    public double getDailyLossLimit() { return dailyLossLimit; }
    public void setDailyLossLimit(double dailyLossLimit) { this.dailyLossLimit = dailyLossLimit; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getEndedAt() { return endedAt; }
    public void setEndedAt(Instant endedAt) { this.endedAt = endedAt; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }
    public double getFinalEquity() { return finalEquity; }
    public void setFinalEquity(double finalEquity) { this.finalEquity = finalEquity; }
    public int getTradesTaken() { return tradesTaken; }
    public void setTradesTaken(int tradesTaken) { this.tradesTaken = tradesTaken; }
    public int getTradingDays() { return tradingDays; }
    public void setTradingDays(int tradingDays) { this.tradingDays = tradingDays; }
}
