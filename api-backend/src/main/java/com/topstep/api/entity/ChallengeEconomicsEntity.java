package com.topstep.api.entity;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * JPA entity for challenge_economics table.
 * Tracks financial outcomes of each challenge attempt.
 */
@Entity
@Table(name = "challenge_economics")
public class ChallengeEconomicsEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String accountId;

    @Column(nullable = false)
    private double feePaid;

    private double payoutReceived;

    private double netProfit;

    @Column(nullable = false)
    private String outcome; // PASSED, BLOWN, TIMEOUT

    private int daysToResolve;

    @Column(nullable = false)
    private Instant recordedAt;

    public ChallengeEconomicsEntity() {}

    // Getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public double getFeePaid() { return feePaid; }
    public void setFeePaid(double feePaid) { this.feePaid = feePaid; }
    public double getPayoutReceived() { return payoutReceived; }
    public void setPayoutReceived(double payoutReceived) { this.payoutReceived = payoutReceived; }
    public double getNetProfit() { return netProfit; }
    public void setNetProfit(double netProfit) { this.netProfit = netProfit; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }
    public int getDaysToResolve() { return daysToResolve; }
    public void setDaysToResolve(int daysToResolve) { this.daysToResolve = daysToResolve; }
    public Instant getRecordedAt() { return recordedAt; }
    public void setRecordedAt(Instant recordedAt) { this.recordedAt = recordedAt; }
}
