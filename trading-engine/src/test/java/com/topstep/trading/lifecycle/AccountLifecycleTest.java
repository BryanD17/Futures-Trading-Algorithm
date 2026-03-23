package com.topstep.trading.lifecycle;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

@DisplayName("AccountLifecycle Tests")
class AccountLifecycleTest {

    private AccountLifecycle lifecycle;

    @BeforeEach
    void setUp() {
        lifecycle = AccountLifecycle.topstep50kEvaluation();
    }

    @Test
    @DisplayName("Initial state is EVALUATION with correct starting values")
    void initialState() {
        assertThat(lifecycle.getCurrentPhase()).isEqualTo(AccountPhase.EVALUATION);
        assertThat(lifecycle.getCurrentEquity()).isEqualTo(50000.0);
        assertThat(lifecycle.getStartingBalance()).isEqualTo(50000.0);
        assertThat(lifecycle.getProfitTarget()).isEqualTo(3000.0);
        assertThat(lifecycle.getMaxLossLimit()).isEqualTo(2000.0);
        assertThat(lifecycle.getDailyLossLimit()).isEqualTo(1000.0);
    }

    @Test
    @DisplayName("Distance to target starts at full profit target")
    void distanceToTarget() {
        assertThat(lifecycle.distanceToTarget()).isEqualTo(3000.0);
    }

    @Test
    @DisplayName("Distance to blowup starts at full MLL")
    void distanceToBlowup() {
        assertThat(lifecycle.distanceToBlowup()).isEqualTo(2000.0);
    }

    @Test
    @DisplayName("Target completion starts at 0%")
    void targetCompletion() {
        assertThat(lifecycle.targetCompletionPct()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Drawdown usage starts at 0%")
    void drawdownUsage() {
        assertThat(lifecycle.drawdownUsagePct()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("Normal risk zone at start")
    void normalZoneAtStart() {
        assertThat(lifecycle.getCurrentRiskZone()).isEqualTo(RiskZone.NORMAL);
    }

    @Test
    @DisplayName("Positive daily PnL updates target completion")
    void positivePnlUpdatesTarget() {
        lifecycle.onDayEnd(1500.0);
        assertThat(lifecycle.targetCompletionPct()).isCloseTo(0.50, within(0.01));
        assertThat(lifecycle.distanceToTarget()).isCloseTo(1500.0, within(0.01));
    }

    @Test
    @DisplayName("Profit target triggers EVALUATION -> FUNDED_PROBATION transition")
    void profitTargetTriggersTransition() {
        AtomicReference<PhaseTransitionEvent> captured = new AtomicReference<>();
        lifecycle.addTransitionListener(captured::set);

        lifecycle.onDayEnd(1500.0);
        assertThat(lifecycle.getCurrentPhase()).isEqualTo(AccountPhase.EVALUATION);

        lifecycle.onDayEnd(1500.0);
        assertThat(lifecycle.getCurrentPhase()).isEqualTo(AccountPhase.FUNDED_PROBATION);

        PhaseTransitionEvent event = captured.get();
        assertThat(event).isNotNull();
        assertThat(event.getFromPhase()).isEqualTo(AccountPhase.EVALUATION);
        assertThat(event.getToPhase()).isEqualTo(AccountPhase.FUNDED_PROBATION);
    }

    @Test
    @DisplayName("MLL breach triggers BLOWN from any phase")
    void mllBreachBlowsAccount() {
        lifecycle.onDayEnd(-1000.0);
        assertThat(lifecycle.getCurrentPhase()).isEqualTo(AccountPhase.EVALUATION);

        lifecycle.onDayEnd(-1000.0);
        assertThat(lifecycle.getCurrentPhase()).isEqualTo(AccountPhase.BLOWN);
        assertThat(lifecycle.getCurrentPhase().isTerminal()).isTrue();
    }

    @Test
    @DisplayName("Exactly at MLL boundary triggers BLOWN")
    void exactlyAtMllTriggersBlown() {
        lifecycle.onDayEnd(-2000.0);
        assertThat(lifecycle.getCurrentPhase()).isEqualTo(AccountPhase.BLOWN);
    }

    @Test
    @DisplayName("Exactly at profit target triggers FUNDED_PROBATION")
    void exactlyAtTargetTriggersFunded() {
        lifecycle.onDayEnd(3000.0);
        assertThat(lifecycle.getCurrentPhase()).isEqualTo(AccountPhase.FUNDED_PROBATION);
    }

    @Test
    @DisplayName("Drawdown usage increases with losses")
    void drawdownUsageIncreasesWithLoss() {
        lifecycle.onDayEnd(-800.0);
        assertThat(lifecycle.drawdownUsagePct()).isCloseTo(0.40, within(0.01));
    }

    @Test
    @DisplayName("CAUTION zone at 40% drawdown usage")
    void cautionZoneAt40Pct() {
        lifecycle.onDayEnd(-800.0);
        assertThat(lifecycle.getCurrentRiskZone()).isEqualTo(RiskZone.CAUTION);
    }

    @Test
    @DisplayName("DANGER zone at 60% drawdown usage")
    void dangerZoneAt60Pct() {
        lifecycle.onDayEnd(-1200.0);
        assertThat(lifecycle.getCurrentRiskZone()).isEqualTo(RiskZone.DANGER);
    }

    @Test
    @DisplayName("CRUISE zone at 85% target completion")
    void cruiseZoneNearTarget() {
        lifecycle.onDayEnd(2550.0); // 85% of 3000
        assertThat(lifecycle.getCurrentRiskZone()).isEqualTo(RiskZone.CRUISE);
    }

    @Test
    @DisplayName("PROTECTION zone at 50% target completion")
    void protectionZoneAtHalfway() {
        lifecycle.onDayEnd(1500.0);
        assertThat(lifecycle.getCurrentRiskZone()).isEqualTo(RiskZone.PROTECTION);
    }

    @Test
    @DisplayName("High water mark trails correctly")
    void highWaterMarkTrails() {
        lifecycle.onDayEnd(500.0); // Equity = 50500
        assertThat(lifecycle.getCurrentHighWaterMark()).isEqualTo(50500.0);

        lifecycle.onDayEnd(-200.0); // Equity = 50300
        assertThat(lifecycle.getCurrentHighWaterMark()).isEqualTo(50500.0); // HWM doesn't drop

        lifecycle.onDayEnd(300.0); // Equity = 50600
        assertThat(lifecycle.getCurrentHighWaterMark()).isEqualTo(50600.0); // New HWM
    }

    @Test
    @DisplayName("Daily PnL history is recorded")
    void dailyPnlHistory() {
        lifecycle.onDayEnd(200.0);
        lifecycle.onDayEnd(-100.0);
        lifecycle.onDayEnd(300.0);

        assertThat(lifecycle.getDailyPnlHistory()).hasSize(3);
        assertThat(lifecycle.getDailyPnlHistory()).containsExactly(200.0, -100.0, 300.0);
    }

    @Test
    @DisplayName("Trading days count correctly")
    void tradingDaysCount() {
        lifecycle.onDayEnd(100.0);
        lifecycle.onDayEnd(200.0);
        lifecycle.onDayEnd(-50.0);

        assertThat(lifecycle.getTradingDaysElapsed()).isEqualTo(3);
    }

    @Test
    @DisplayName("Consecutive losses track correctly")
    void consecutiveLosses() {
        lifecycle.recordTrade(-50.0);
        lifecycle.recordTrade(-30.0);
        assertThat(lifecycle.getConsecutiveLosses()).isEqualTo(2);

        lifecycle.recordTrade(100.0);
        assertThat(lifecycle.getConsecutiveLosses()).isEqualTo(0);
    }

    @Test
    @DisplayName("Topstep 50K factory creates correct lifecycle")
    void topstep50kFactory() {
        AccountLifecycle lc = AccountLifecycle.topstep50kEvaluation();
        assertThat(lc.getChallengeFee()).isEqualTo(49.0);
        assertThat(lc.getProfitTarget()).isEqualTo(3000.0);
        assertThat(lc.getMaxLossLimit()).isEqualTo(2000.0);
        assertThat(lc.getDailyLossLimit()).isEqualTo(1000.0);
        assertThat(lc.getMaxContracts()).isEqualTo(5);
        assertThat(lc.getStartingBalance()).isEqualTo(50000.0);
        assertThat(lc.getProfitSplit()).isEqualTo(0.90);
    }

    @Test
    @DisplayName("Risk budget remaining decreases with drawdown")
    void riskBudgetRemaining() {
        assertThat(lifecycle.riskBudgetRemaining()).isEqualTo(2000.0);
        lifecycle.onDayEnd(-500.0);
        assertThat(lifecycle.riskBudgetRemaining()).isCloseTo(1500.0, within(0.01));
    }

    @Test
    @DisplayName("Force transition works for external triggers")
    void forceTransition() {
        // Force to funded (simulating external payout trigger)
        PhaseTransitionEvent event = lifecycle.forceTransition(
            AccountPhase.FUNDED_PROBATION, "Manual transition for testing");
        assertThat(lifecycle.getCurrentPhase()).isEqualTo(AccountPhase.FUNDED_PROBATION);
        assertThat(event).isNotNull();
        assertThat(event.getReason()).contains("Manual transition");
    }
}
