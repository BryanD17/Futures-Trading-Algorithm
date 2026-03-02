package com.topstep.trading.risk;

import com.topstep.trading.domain.AccountState;
import com.topstep.trading.domain.RiskLimits;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;

/**
 * Tests for PropFirmRiskEngine - risk evaluation and tier enforcement.
 */
class PropFirmRiskEngineTest {

    private PropFirmRiskEngine riskEngine;
    private AccountState account;
    private RiskLimits limits;

    @BeforeEach
    void setUp() {
        riskEngine = new PropFirmRiskEngine();
        account = new AccountState(50000.0);
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

}
