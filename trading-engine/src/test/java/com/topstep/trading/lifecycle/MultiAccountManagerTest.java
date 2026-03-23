package com.topstep.trading.lifecycle;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.assertj.core.api.Assertions.*;

@DisplayName("MultiAccountManager Tests")
class MultiAccountManagerTest {

    private MultiAccountManager manager;

    @BeforeEach
    void setUp() {
        manager = new MultiAccountManager();
    }

    @Test
    @DisplayName("Empty manager has zero pass rate")
    void emptyManagerZeroPassRate() {
        assertThat(manager.getActualPassRate()).isEqualTo(0);
        assertThat(manager.getTotalChallengesAttempted()).isEqualTo(0);
    }

    @Test
    @DisplayName("Adding accounts tracks fees")
    void addAccountTracksFees() {
        AccountLifecycle lc = AccountLifecycle.topstep50kEvaluation();
        manager.addAccount("acct-1", lc);

        assertThat(manager.getTotalChallengesAttempted()).isEqualTo(1);
        assertThat(manager.getTotalChallengeFeesPaid()).isEqualTo(49.0);
        assertThat(manager.getActiveAccountCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Completing a passed challenge updates pass rate")
    void completedPassUpdatesRate() {
        AccountLifecycle lc = AccountLifecycle.topstep50kEvaluation();
        manager.addAccount("acct-1", lc);
        manager.completeChallenge("acct-1", true, 2000.0);

        assertThat(manager.getTotalChallengesPassed()).isEqualTo(1);
        assertThat(manager.getActualPassRate()).isEqualTo(1.0);
        assertThat(manager.getTotalFundedPayoutsReceived()).isEqualTo(2000.0);
        assertThat(manager.getActiveAccountCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Mix of passed and blown gives correct pass rate")
    void mixedOutcomesCorrectRate() {
        for (int i = 0; i < 5; i++) {
            manager.addAccount("pass-" + i, AccountLifecycle.topstep50kEvaluation());
            manager.completeChallenge("pass-" + i, true, 2000.0);
        }
        for (int i = 0; i < 5; i++) {
            manager.addAccount("blown-" + i, AccountLifecycle.topstep50kEvaluation());
            manager.completeChallenge("blown-" + i, false, 0);
        }

        assertThat(manager.getActualPassRate()).isCloseTo(0.5, within(0.01));
        assertThat(manager.getTotalChallengesAttempted()).isEqualTo(10);
    }

    @Test
    @DisplayName("ROI calculation is correct")
    void roiCalculation() {
        // 2 challenges at $49 each = $98 total fees
        // 1 passes with $2000 payout, 1 blows
        manager.addAccount("a1", AccountLifecycle.topstep50kEvaluation());
        manager.completeChallenge("a1", true, 2000.0);
        manager.addAccount("a2", AccountLifecycle.topstep50kEvaluation());
        manager.completeChallenge("a2", false, 0);

        // ROI = (2000 - 98) / 98 = 19.4x
        assertThat(manager.getRoi()).isGreaterThan(10);
    }

    @Test
    @DisplayName("Expected payout per challenge calculation")
    void expectedPayoutPerChallenge() {
        // 10 challenges, 5 pass with $2000 each
        for (int i = 0; i < 5; i++) {
            manager.addAccount("p-" + i, AccountLifecycle.topstep50kEvaluation());
            manager.completeChallenge("p-" + i, true, 2000.0);
        }
        for (int i = 0; i < 5; i++) {
            manager.addAccount("b-" + i, AccountLifecycle.topstep50kEvaluation());
            manager.completeChallenge("b-" + i, false, 0);
        }

        // E[payout] = 0.5 * $2000 - $49 = $951
        double expected = manager.getExpectedPayoutPerChallenge();
        assertThat(expected).isGreaterThan(900);
    }

    @Test
    @DisplayName("Purchase recommendation with no data suggests first challenge")
    void purchaseRecommendationNoData() {
        MultiAccountManager.PurchaseRecommendation rec = manager.shouldPurchaseNewChallenge();
        assertThat(rec.isRecommended()).isTrue();
        assertThat(rec.getReason()).contains("No data");
    }

    @Test
    @DisplayName("Purchase recommendation with positive EV recommends")
    void purchaseRecommendationPositiveEV() {
        for (int i = 0; i < 5; i++) {
            manager.addAccount("p-" + i, AccountLifecycle.topstep50kEvaluation());
            manager.completeChallenge("p-" + i, true, 2000.0);
        }
        for (int i = 0; i < 5; i++) {
            manager.addAccount("b-" + i, AccountLifecycle.topstep50kEvaluation());
            manager.completeChallenge("b-" + i, false, 0);
        }

        MultiAccountManager.PurchaseRecommendation rec = manager.shouldPurchaseNewChallenge();
        assertThat(rec.isRecommended()).isTrue();
        assertThat(rec.getExpectedPayout()).isGreaterThan(0);
    }

    @Test
    @DisplayName("Simultaneous accounts with mixed outcomes handled correctly")
    void simultaneousAccountsMixedOutcomes() {
        // Start 3 accounts simultaneously
        manager.addAccount("sim-1", AccountLifecycle.topstep50kEvaluation());
        manager.addAccount("sim-2", AccountLifecycle.topstep50kEvaluation());
        manager.addAccount("sim-3", AccountLifecycle.topstep50kEvaluation());

        assertThat(manager.getActiveAccountCount()).isEqualTo(3);

        // One passes while another blows
        manager.completeChallenge("sim-1", true, 2000.0);
        manager.completeChallenge("sim-2", false, 0);

        assertThat(manager.getActiveAccountCount()).isEqualTo(1);
        assertThat(manager.getTotalChallengesPassed()).isEqualTo(1);
    }

    @Test
    @DisplayName("No division by zero with zero completed challenges")
    void noDivisionByZero() {
        manager.addAccount("active", AccountLifecycle.topstep50kEvaluation());

        assertThat(manager.getActualPassRate()).isEqualTo(0);
        assertThat(manager.getRoi()).isFinite();
        assertThat(manager.getExpectedPayoutPerChallenge()).isFinite();
    }
}
