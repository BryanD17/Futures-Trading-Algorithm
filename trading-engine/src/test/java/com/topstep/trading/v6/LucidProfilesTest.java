package com.topstep.trading.v6;

import com.topstep.trading.config.LucidProfiles;
import com.topstep.trading.domain.RiskLimits;
import com.topstep.trading.lifecycle.AccountLifecycle;
import com.topstep.trading.lifecycle.AccountPhase;
import com.topstep.trading.lifecycle.LucidAccountProfile;
import com.topstep.trading.lifecycle.TrailingDrawdown;
import com.topstep.trading.montecarlo.MonteCarloResult;
import com.topstep.trading.montecarlo.MonteCarloSimulator;
import com.topstep.trading.montecarlo.StrategyMetrics;
import com.topstep.trading.risk.RiskProfile;
import com.topstep.trading.strategy.stdvote.ScalpConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * V6 Agent 05.2 / 05.3: Lucid profiles come from config/lucid_profiles.yaml and
 * the MLL is EOD trailing with the lock rule (docs/LUCID_RULES.md §6–§10). The
 * expected numbers below are the LUCID_RULES.md tables (verification, not a
 * second source).
 */
class LucidProfilesTest {

    @Test
    @DisplayName("LUCID_FLEX_50K_EVAL = LUCID_RULES §6/§8 (target 3,000 / MLL 2,000 / trail 52,100 / lock 50,100 / 40 micros)")
    void flex50kEvalFromYaml() {
        LucidAccountProfile p = LucidProfiles.flex50kEval();
        assertThat(p.id()).isEqualTo("LUCID_FLEX_50K_EVAL");
        assertThat(p.isEval()).isTrue();
        assertThat(p.startingBalance()).isEqualTo(50_000.0);
        assertThat(p.profitTarget()).isEqualTo(3_000.0);
        assertThat(p.maxLossLimit()).isEqualTo(2_000.0);
        assertThat(p.initialTrailBalance()).isEqualTo(52_100.0);
        assertThat(p.lockedMllBalance()).isEqualTo(50_100.0);
        assertThat(p.maxContractsMicros()).isEqualTo(40);
        assertThat(p.scalingCapMicrosAt(5_000)).isEqualTo(40);
        assertThat(p.consistencyMaxPct()).isEqualTo(40.0);
        assertThat(p.minTradingDays()).isEqualTo(3);
        assertThat(p.softDailyLoss()).isEqualTo(600.0);
        assertThat(p.isEodTrailing()).isTrue();
        assertThat(p.profitSplitTrader()).isEqualTo(0.90);
    }

    @Test
    @DisplayName("LUCID_FLEX_50K_FUNDED scaling plan 20 -> 30 -> 40 micros at $0 / $1k / $2k (LUCID_RULES §10)")
    void flex50kFundedScaling() {
        LucidAccountProfile p = LucidProfiles.flex50kFunded();
        assertThat(p.isEval()).isFalse();
        assertThat(p.profitTarget()).isZero();
        assertThat(p.scalingCapMicrosAt(0)).isEqualTo(20);
        assertThat(p.scalingCapMicrosAt(999)).isEqualTo(20);
        assertThat(p.scalingCapMicrosAt(1_000)).isEqualTo(30);
        assertThat(p.scalingCapMicrosAt(2_500)).isEqualTo(40);
        assertThat(p.consistencyMaxPct()).isZero();
    }

    @Test
    @DisplayName("every YAML profile loads (25K/50K/100K/150K, eval + funded)")
    void allSizesLoad() {
        assertThat(LucidProfiles.all()).containsKeys("LUCID_FLEX_25K_EVAL", "LUCID_FLEX_25K_FUNDED",
                "LUCID_FLEX_50K_EVAL", "LUCID_FLEX_50K_FUNDED", "LUCID_FLEX_100K_EVAL", "LUCID_FLEX_100K_FUNDED",
                "LUCID_FLEX_150K_EVAL", "LUCID_FLEX_150K_FUNDED");
        assertThat(LucidProfiles.get("LUCID_FLEX_150K_EVAL").lockedMllBalance()).isEqualTo(150_100.0);
        assertThat(RiskProfile.lucidFlex100kEval().getAccount().maxLossLimit()).isEqualTo(3_000.0);
        assertThat(RiskProfile.lucidFlex25kFunded().getAccount().scalingCapMicrosAt(0)).isEqualTo(10);
    }

    @Test
    @DisplayName("RiskProfile.lucidFlex50k*: account attached, $250 per-trade budget policy kept")
    void riskProfiles() {
        RiskProfile eval = RiskProfile.lucidFlex50kEval();
        assertThat(eval.getAccountId()).isEqualTo("LUCID_FLEX_50K_EVAL");
        assertThat(eval.getPhase()).isEqualTo(AccountPhase.EVALUATION);
        assertThat(eval.getBaseRiskPct() * eval.getAccount().startingBalance()).isCloseTo(250.0, within(1e-9));
        RiskProfile funded = RiskProfile.lucidFlex50kFunded();
        assertThat(funded.getAccountId()).isEqualTo("LUCID_FLEX_50K_FUNDED");
        assertThat(RiskProfile.lucidForPhase(AccountPhase.FUNDED_SCALING).getAccountId()).isEqualTo("LUCID_FLEX_50K_FUNDED");
        assertThat(eval.withBaseRiskPct(0.004).getAccount()).isSameAs(eval.getAccount());
    }

    @Test
    @DisplayName("Lucid RiskLimits: rails from the YAML, size cap = min(policy, scaling cap) (I4)")
    void lucidRiskLimits() {
        RiskLimits l = ScalpConfig.activeLucidRiskLimits();
        assertThat(l.getMaxLossLimit()).isEqualTo(2_000.0);
        assertThat(l.getProfitTarget()).isEqualTo(3_000.0);
        assertThat(l.getMaxDailyLoss()).isEqualTo(600.0);
        assertThat(l.getMaxContracts()).isLessThanOrEqualTo(40).isEqualTo(Math.min(ScalpConfig.activeRiskLimits().getMaxContracts(), 40));
        RiskLimits big = RiskLimits.builder().maxContracts(60).maxTotalContracts(60).build()
                .withLucidAccount(LucidProfiles.flex50kFunded());
        assertThat(big.getMaxContracts()).isEqualTo(20);
        assertThat(big.getMaxTotalContracts()).isEqualTo(20);
        // the oracle's pinned limits are untouched
        assertThat(ScalpConfig.activeRiskLimits().getMaxDailyLoss()).isEqualTo(1_000.0);
    }

    // ---------------------------------------------------------------- 05.3 MLL

    @Test
    @DisplayName("EOD trailing: intraday highs never trail; the floor re-bases on the closing balance")
    void eodTrailingNotIntraday() {
        TrailingDrawdown dd = TrailingDrawdown.lucid(LucidProfiles.flex50kEval());
        assertThat(dd.floor()).isEqualTo(48_000.0);
        // intraday run to 51,500 then close at 50,600: only the close counts
        assertThat(dd.breached(48_000.01)).isFalse();
        dd.onEndOfDay(50_600.0);
        assertThat(dd.floor()).isEqualTo(48_600.0);
        dd.onEndOfDay(50_200.0); // lower close never lowers the floor
        assertThat(dd.floor()).isEqualTo(48_600.0);
        assertThat(dd.room(49_000.0)).isEqualTo(400.0);
        assertThat(dd.breached(48_600.0)).as("touch = breach").isTrue();
        assertThat(dd.isLocked()).isFalse();
    }

    @Test
    @DisplayName("lock: a close ABOVE the 52,100 initial trail locks the floor at 50,100 forever")
    void lockRule() {
        TrailingDrawdown dd = TrailingDrawdown.lucid(LucidProfiles.flex50kEval());
        dd.onEndOfDay(52_100.0); // equal is not "exceeds"
        assertThat(dd.isLocked()).isFalse();
        assertThat(dd.floor()).isEqualTo(50_100.0);
        dd.onEndOfDay(52_100.25);
        assertThat(dd.isLocked()).isTrue();
        assertThat(dd.floor()).isEqualTo(50_100.0);
        dd.onEndOfDay(55_000.0);
        assertThat(dd.floor()).as("locked: no more trailing").isEqualTo(50_100.0);
        assertThat(dd.breached(50_100.0)).isTrue();
        assertThat(dd.breached(50_100.25)).isFalse();
        assertThat(dd.usagePct(53_000.0)).isZero();
    }

    @Test
    @DisplayName("a payout request locks the MLL immediately (funded)")
    void payoutLocks() {
        TrailingDrawdown dd = TrailingDrawdown.lucid(LucidProfiles.flex50kFunded());
        dd.onEndOfDay(51_000.0);
        assertThat(dd.floor()).isEqualTo(49_000.0);
        dd.onPayoutRequest();
        assertThat(dd.isLocked()).isTrue();
        assertThat(dd.floor()).isEqualTo(50_100.0);
    }

    @Test
    @DisplayName("legacy drawdown math is unchanged (HWM − MLL, never locks)")
    void legacyUnchanged() {
        TrailingDrawdown dd = TrailingDrawdown.legacy(50_000.0, 2_000.0);
        dd.onEndOfDay(53_000.0);
        assertThat(dd.isLocked()).isFalse();
        assertThat(dd.floor()).isEqualTo(51_000.0);
        dd.onPayoutRequest();
        assertThat(dd.isLocked()).isFalse();
    }

    @Test
    @DisplayName("AccountLifecycle(Lucid): breach on the trailing floor, lock, and eval pass needs min days + consistency")
    void lucidLifecycle() {
        AccountLifecycle lc = AccountLifecycle.lucidFlex50kEvaluation();
        assertThat(lc.getLucidProfile().id()).isEqualTo("LUCID_FLEX_50K_EVAL");
        assertThat(lc.getDailyLossLimit()).isEqualTo(600.0);
        // day 1: +3,100 in one day -> target met, but 1 day < 3 and 100 % > 40 % consistency
        assertThat(lc.onDayEnd(3_100.0)).isNull();
        assertThat(lc.getCurrentPhase()).isEqualTo(AccountPhase.EVALUATION);
        assertThat(lc.isMllLocked()).isTrue(); // 53,100 > 52,100
        assertThat(lc.getMllFloor()).isEqualTo(50_100.0);
        lc.onDayEnd(1_000.0);
        lc.onDayEnd(1_000.0);
        lc.onDayEnd(1_000.0);
        lc.onDayEnd(1_000.0);
        // total 7,100, largest 3,100 = 43.7 % > 40 %: still not passed
        assertThat(lc.getCurrentPhase()).isEqualTo(AccountPhase.EVALUATION);
        lc.onDayEnd(800.0); // 7,900 -> 39.2 %
        assertThat(lc.getCurrentPhase()).isEqualTo(AccountPhase.FUNDED_PROBATION);
    }

    @Test
    @DisplayName("AccountLifecycle(Lucid): the balance touching the EOD floor blows the account")
    void lucidLifecycleBreach() {
        AccountLifecycle lc = AccountLifecycle.lucidFlex50kEvaluation();
        lc.onDayEnd(1_500.0);            // close 51,500 -> floor 49,500
        assertThat(lc.distanceToBlowup()).isEqualTo(2_000.0);
        assertThat(lc.onDayEnd(-1_999.0)).isNull(); // 49,501
        assertThat(lc.onDayEnd(-1.0)).isNotNull();   // 49,500 = touch
        assertThat(lc.getCurrentPhase()).isEqualTo(AccountPhase.BLOWN);
    }

    @Test
    @DisplayName("Monte Carlo on the Lucid default runs and is deterministic per seed")
    void monteCarloLucidDefault() {
        StrategyMetrics m = StrategyMetrics.fromParameters(0.45, 3.0, -1.0, 2.0);
        MonteCarloResult a = new MonteCarloSimulator(7L).simulate(m, RiskProfile.lucidFlex50kEval(),
                AccountLifecycle.lucidFlex50kEvaluation(), 500);
        MonteCarloResult b = new MonteCarloSimulator(7L).simulate(m, RiskProfile.lucidFlex50kEval(),
                AccountLifecycle.lucidFlex50kEvaluation(), 500);
        assertThat(a.getTotalIterations()).isEqualTo(500);
        assertThat(a.getPassRate() + a.getBlowupRate() + a.getTimeoutRate()).isCloseTo(1.0, within(1e-9));
        assertThat(a.getPassRate()).isEqualTo(b.getPassRate());
        assertThat(a.getPassRate()).isGreaterThan(0.0);
    }
}
