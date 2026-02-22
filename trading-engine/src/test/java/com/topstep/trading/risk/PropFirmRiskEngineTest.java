package com.topstep.trading.risk;

import com.topstep.trading.domain.AccountState;
import com.topstep.trading.domain.OrderSide;
import com.topstep.trading.domain.RiskLimits;
import com.topstep.trading.event.StrategySignalEvent;
import com.topstep.trading.strategy.TradeTier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for PropFirmRiskEngine - 6J minimum contract guard and tier enforcement.
 */
class PropFirmRiskEngineTest {

    private PropFirmRiskEngine riskEngine;
    private AccountState account;
    private RiskLimits limits;

    @BeforeEach
    void setUp() {
        riskEngine = new PropFirmRiskEngine();
        account = new AccountState(50000.0);
        // Use generous limits so we can test specific 6J guards
        limits = RiskLimits.builder()
                .maxDailyLoss(2000.0)
                .maxLossLimit(4000.0)
                .maxContracts(10)
                .maxTotalContracts(20)
                .riskPerTrade(500.0)
                .minRiskRewardRatio(2.0)
                .maxRiskRewardRatio(6.0)
                .build();
    }

    @Nested
    @DisplayName("6J Minimum Contract Guard")
    class MinContractGuard {

        @Test
        @DisplayName("6J with quantity < 3 is rejected")
        void sixJ_quantityBelowMinimum_rejected() {
            // Create a 6J signal with a very tight stop (so risk engine would approve only 1-2 contracts)
            // 6J tick size = 0.0000005, tick value = $6.25
            // With a 60-tick stop: 60 * $6.25 = $375/contract
            // With $500 risk budget, risk engine calculates floor(500/375) = 1 contract
            StrategySignalEvent signal = new StrategySignalEvent(
                    StrategySignalEvent.SignalType.LONG_ENTRY,
                    "6J",
                    OrderSide.BUY,
                    0.006700,                     // entry
                    0.006700 - 0.000030,          // stop (60 ticks below)
                    0.006700 + 0.000045,          // target (90 ticks above)
                    "Test 6J signal",
                    TradeTier.TIER_3,
                    1
            );

            RiskDecision decision = riskEngine.evaluate(signal, account, limits);

            assertThat(decision.isAllowed()).isFalse();
            assertThat(decision.getReason()).contains("6J minimum contract size is 3");
        }

        @Test
        @DisplayName("6J with quantity >= 3 is allowed")
        void sixJ_quantityMeetsMinimum_allowed() {
            // With $1500+ risk budget, risk engine approves 3+ contracts
            // Stop: 60 ticks * $6.25 = $375/contract, 3 contracts = $1125
            RiskLimits generousLimits = RiskLimits.builder()
                    .maxDailyLoss(5000.0)
                    .maxLossLimit(10000.0)
                    .maxContracts(10)
                    .maxTotalContracts(20)
                    .riskPerTrade(1500.0)  // Enough for 3 contracts at $375 each
                    .minRiskRewardRatio(2.0)
                    .maxRiskRewardRatio(6.0)
                    .build();

            StrategySignalEvent signal = new StrategySignalEvent(
                    StrategySignalEvent.SignalType.LONG_ENTRY,
                    "6J",
                    OrderSide.BUY,
                    0.006700,
                    0.006700 - 0.000030,          // 60 ticks stop ($375/contract)
                    0.006700 + 0.000090,          // 180 ticks target (R:R = 3:1)
                    "Test 6J signal",
                    TradeTier.TIER_3,
                    3
            );

            RiskDecision decision = riskEngine.evaluate(signal, account, generousLimits);

            assertThat(decision.isAllowed()).isTrue();
        }

        @Test
        @DisplayName("Non-6J symbols are not affected by the 6J minimum")
        void nonSixJ_notAffectedByMinimum() {
            // NQ with 1 contract should be fine
            StrategySignalEvent signal = new StrategySignalEvent(
                    StrategySignalEvent.SignalType.LONG_ENTRY,
                    "NQ",
                    OrderSide.BUY,
                    18500.00,
                    18485.00,          // 60 ticks stop (15 points / 0.25 = 60 ticks)
                    18545.00,          // 180 ticks target (R:R 3:1)
                    "Test NQ signal",
                    TradeTier.TIER_2,
                    1
            );

            RiskDecision decision = riskEngine.evaluate(signal, account, limits);

            // Should not be rejected for minimum contracts reason
            if (!decision.isAllowed()) {
                assertThat(decision.getReason()).doesNotContain("6J minimum contract size");
            }
        }
    }

    @Nested
    @DisplayName("6J Tier Gate in Strategy")
    class TierGate {

        @Test
        @DisplayName("TradeTier.TIER_2 has level < 3")
        void tier2_levelBelow3() {
            assertThat(TradeTier.TIER_2.getLevel()).isLessThan(3);
        }

        @Test
        @DisplayName("TradeTier.TIER_3 has level >= 3")
        void tier3_levelAtLeast3() {
            assertThat(TradeTier.TIER_3.getLevel()).isGreaterThanOrEqualTo(3);
        }

        @Test
        @DisplayName("TradeTier.TIER_4 has level >= 3")
        void tier4_levelAtLeast3() {
            assertThat(TradeTier.TIER_4.getLevel()).isGreaterThanOrEqualTo(3);
        }

        @Test
        @DisplayName("TradeTier.TIER_1 has level < 3")
        void tier1_levelBelow3() {
            assertThat(TradeTier.TIER_1.getLevel()).isLessThan(3);
        }
    }
}
