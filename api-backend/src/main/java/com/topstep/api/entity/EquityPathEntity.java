package com.topstep.api.entity;

import jakarta.persistence.*;
import java.time.LocalDate;

/**
 * JPA entity for equity_path table.
 * Daily equity snapshots for tracking progress through challenge.
 */
@Entity
@Table(name = "equity_path", indexes = {
    @Index(name = "idx_equity_account_date", columnList = "accountId, date")
})
public class EquityPathEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String accountId;

    @Column(nullable = false)
    private LocalDate date;

    @Column(nullable = false)
    private double eodEquity;

    private double dailyPnl;

    private double highWaterMark;

    private double drawdownUsagePct;

    private double targetCompletionPct;

    private int tradesToday;

    private String riskProfileUsed;

    public EquityPathEntity() {}

    // Getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }
    public double getEodEquity() { return eodEquity; }
    public void setEodEquity(double eodEquity) { this.eodEquity = eodEquity; }
    public double getDailyPnl() { return dailyPnl; }
    public void setDailyPnl(double dailyPnl) { this.dailyPnl = dailyPnl; }
    public double getHighWaterMark() { return highWaterMark; }
    public void setHighWaterMark(double highWaterMark) { this.highWaterMark = highWaterMark; }
    public double getDrawdownUsagePct() { return drawdownUsagePct; }
    public void setDrawdownUsagePct(double drawdownUsagePct) { this.drawdownUsagePct = drawdownUsagePct; }
    public double getTargetCompletionPct() { return targetCompletionPct; }
    public void setTargetCompletionPct(double targetCompletionPct) { this.targetCompletionPct = targetCompletionPct; }
    public int getTradesToday() { return tradesToday; }
    public void setTradesToday(int tradesToday) { this.tradesToday = tradesToday; }
    public String getRiskProfileUsed() { return riskProfileUsed; }
    public void setRiskProfileUsed(String riskProfileUsed) { this.riskProfileUsed = riskProfileUsed; }
}
