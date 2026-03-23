package com.topstep.trading.lifecycle;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages multiple simultaneous prop firm challenge accounts.
 * Tracks aggregate economics across all accounts and recommends
 * whether to purchase additional challenges based on measured data.
 *
 * The Math:
 *   If pass rate = 50%, challenge fee = $150, funded profit = $2,000:
 *     E[payout/challenge] = 0.50 * $2,000 * 0.80 - $150 = $650
 *   Even at 20% pass rate with 3 concurrent accounts:
 *     E[payout/challenge] = 0.20 * $2,000 * 0.80 - $150 = $170
 *     E[monthly, 3 accounts] = $170 * 3 = $510/month
 */
public class MultiAccountManager {

    private final Map<String, AccountLifecycle> activeAccounts;
    private final List<CompletedChallenge> completedChallenges;

    // Aggregate economics
    private double totalChallengeFeesPaid;
    private double totalFundedPayoutsReceived;
    private int totalChallengesAttempted;
    private int totalChallengesPassed;

    public MultiAccountManager() {
        this.activeAccounts = new ConcurrentHashMap<>();
        this.completedChallenges = Collections.synchronizedList(new ArrayList<>());
        this.totalChallengeFeesPaid = 0;
        this.totalFundedPayoutsReceived = 0;
        this.totalChallengesAttempted = 0;
        this.totalChallengesPassed = 0;
    }

    /**
     * Add a new active challenge account.
     */
    public void addAccount(String accountId, AccountLifecycle lifecycle) {
        activeAccounts.put(accountId, lifecycle);
        totalChallengesAttempted++;
        totalChallengeFeesPaid += lifecycle.getChallengeFee();
    }

    /**
     * Record a challenge completion (passed or blown).
     */
    public void completeChallenge(String accountId, boolean passed, double payout) {
        AccountLifecycle lifecycle = activeAccounts.remove(accountId);
        if (lifecycle == null) return;

        if (passed) {
            totalChallengesPassed++;
            totalFundedPayoutsReceived += payout;
        }

        completedChallenges.add(new CompletedChallenge(
            accountId, passed, lifecycle.getChallengeFee(), payout,
            lifecycle.getTradingDaysElapsed(), lifecycle.getTotalTradesTaken()));
    }

    /**
     * Get actual pass rate from completed challenges.
     */
    public double getActualPassRate() {
        if (totalChallengesAttempted == 0) return 0;
        return (double) totalChallengesPassed / totalChallengesAttempted;
    }

    /**
     * Get return on investment (total payouts / total fees - 1).
     */
    public double getRoi() {
        if (totalChallengeFeesPaid == 0) return 0;
        return (totalFundedPayoutsReceived - totalChallengeFeesPaid) / totalChallengeFeesPaid;
    }

    /**
     * Get expected payout per challenge based on measured data.
     * E[payout] = actualPassRate * avgFundedPayout * profitSplit - avgChallengeFee
     */
    public double getExpectedPayoutPerChallenge() {
        if (totalChallengesAttempted == 0) return 0;

        double avgFee = totalChallengeFeesPaid / totalChallengesAttempted;
        double avgPayout = totalChallengesPassed > 0
            ? totalFundedPayoutsReceived / totalChallengesPassed
            : 0;
        double passRate = getActualPassRate();

        return passRate * avgPayout - avgFee;
    }

    /**
     * Recommend whether to purchase a new challenge.
     * Only recommends if:
     *   1. Expected payout per challenge is positive
     *   2. Confidence in pass rate exceeds threshold (based on sample size)
     *
     * Uses Bayesian confidence interval (Wilson score for binomial proportion).
     */
    public PurchaseRecommendation shouldPurchaseNewChallenge() {
        if (totalChallengesAttempted == 0) {
            return new PurchaseRecommendation(true,
                "No data yet. First challenge recommended to establish baseline.",
                0, 0, 0);
        }

        double passRate = getActualPassRate();
        double expectedPayout = getExpectedPayoutPerChallenge();

        // Wilson score confidence interval (95%)
        double z = 1.96; // 95% confidence
        int n = totalChallengesAttempted;
        double phat = passRate;
        double denominator = 1 + z * z / n;
        double center = (phat + z * z / (2.0 * n)) / denominator;
        double margin = z * Math.sqrt((phat * (1 - phat) + z * z / (4.0 * n)) / n) / denominator;

        double lowerBound = Math.max(0, center - margin);
        double confidence = 1.0 - 2 * margin; // How narrow the interval is

        // Need minimum sample size for reliable estimate
        boolean hasMinSample = totalChallengesAttempted >= 3;
        boolean isPositiveEV = expectedPayout > 0;
        boolean isConfident = confidence > 0.3 || totalChallengesAttempted >= 10;

        boolean recommend = hasMinSample ? (isPositiveEV && isConfident) : true;
        String reason;

        if (!hasMinSample) {
            reason = String.format("Only %d challenges completed. Need 3+ for reliable estimate. " +
                "Current pass rate: %.0f%%", totalChallengesAttempted, passRate * 100);
        } else if (isPositiveEV && isConfident) {
            reason = String.format("Positive expected value: $%.0f/challenge. " +
                "Pass rate: %.0f%% (95%% CI: %.0f%%-%.0f%%)",
                expectedPayout, passRate * 100, lowerBound * 100, (center + margin) * 100);
        } else if (!isPositiveEV) {
            reason = String.format("Negative expected value: $%.0f/challenge. " +
                "Pass rate: %.0f%% is too low at current payout levels.",
                expectedPayout, passRate * 100);
        } else {
            reason = String.format("Insufficient confidence in pass rate estimate. " +
                "Current: %.0f%% based on %d challenges.",
                passRate * 100, totalChallengesAttempted);
        }

        return new PurchaseRecommendation(recommend, reason, expectedPayout, passRate, confidence);
    }

    // Getters
    public Map<String, AccountLifecycle> getActiveAccounts() { return Collections.unmodifiableMap(activeAccounts); }
    public List<CompletedChallenge> getCompletedChallenges() { return Collections.unmodifiableList(completedChallenges); }
    public double getTotalChallengeFeesPaid() { return totalChallengeFeesPaid; }
    public double getTotalFundedPayoutsReceived() { return totalFundedPayoutsReceived; }
    public int getTotalChallengesAttempted() { return totalChallengesAttempted; }
    public int getTotalChallengesPassed() { return totalChallengesPassed; }
    public int getActiveAccountCount() { return activeAccounts.size(); }

    /**
     * Record of a completed challenge.
     */
    public static class CompletedChallenge {
        private final String accountId;
        private final boolean passed;
        private final double feePaid;
        private final double payoutReceived;
        private final int tradingDays;
        private final int tradesTaken;

        public CompletedChallenge(String accountId, boolean passed, double feePaid,
                                   double payoutReceived, int tradingDays, int tradesTaken) {
            this.accountId = accountId;
            this.passed = passed;
            this.feePaid = feePaid;
            this.payoutReceived = payoutReceived;
            this.tradingDays = tradingDays;
            this.tradesTaken = tradesTaken;
        }

        public String getAccountId() { return accountId; }
        public boolean isPassed() { return passed; }
        public double getFeePaid() { return feePaid; }
        public double getPayoutReceived() { return payoutReceived; }
        public double getNetProfit() { return payoutReceived - feePaid; }
        public int getTradingDays() { return tradingDays; }
        public int getTradesTaken() { return tradesTaken; }
    }

    /**
     * Result of the purchase decision logic.
     */
    public static class PurchaseRecommendation {
        private final boolean recommend;
        private final String reason;
        private final double expectedPayout;
        private final double passRate;
        private final double confidence;

        public PurchaseRecommendation(boolean recommend, String reason,
                                       double expectedPayout, double passRate, double confidence) {
            this.recommend = recommend;
            this.reason = reason;
            this.expectedPayout = expectedPayout;
            this.passRate = passRate;
            this.confidence = confidence;
        }

        public boolean isRecommended() { return recommend; }
        public String getReason() { return reason; }
        public double getExpectedPayout() { return expectedPayout; }
        public double getPassRate() { return passRate; }
        public double getConfidence() { return confidence; }
    }
}
