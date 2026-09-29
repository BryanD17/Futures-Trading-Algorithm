package com.topstep.trading.risk;

import com.topstep.trading.config.EngineConfig;
import com.topstep.trading.domain.AccountState;
import com.topstep.trading.domain.OrderSide;
import com.topstep.trading.domain.RiskLimits;
import com.topstep.trading.event.StrategySignalEvent;
import com.topstep.trading.event.StrategySignalEvent.SignalType;
import com.topstep.trading.lifecycle.AccountLifecycle;
import com.topstep.trading.lifecycle.RiskZone;
import com.topstep.trading.strategy.DefaultStrategyContext;
import com.topstep.trading.strategy.TradeTier;
import com.topstep.trading.strategy.stdvote.StdvOteSizer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * V5 Agent 05.10 — LIVE risk path parity.
 *
 * <p>The 2026-09-29 PRAC defect: broker balance $150,620.10 under the 50K
 * profile made the lifecycle read CRUISE (target 100%), the phase-aware layer
 * cut the budget to $250 x 0.3 x 0.75 = $56.25, and an LTF signal the strategy
 * had sized 5 micros x $37 against $250 was DENIED
 * ("requested 5 micros x $37.00 = $185.00 &gt; risk budget $56.25 (max 1 micros)").
 */
@DisplayName("V5 Agent 05.10 - LIVE risk path: STATIC = the proven path, PHASE_AWARE opt-in, zone from tracked P&L")
class LiveRiskPathTest {

    private static final double BROKER_BALANCE = 150_620.10;
    private static final Instant T0 = Instant.parse("2026-09-29T14:00:00Z");   // 09:00 CT
    private final List<String> log = new ArrayList<>();

    @AfterEach
    void clearProps() {
        System.clearProperty(LiveRiskPath.PHASE_AWARE_KEY);
        System.clearProperty(RiskConfig.HALT_ON_PROFIT_TARGET);
    }

    /** LTF MNQ short: 18.5 pt stop = 74 ticks x $0.50 = $37.00 per micro (the PRAC log's numbers). */
    private static StrategySignalEvent ltfShort(TradeTier tier, int qty) {
        double entry = 30650.0;
        return new StrategySignalEvent(SignalType.SHORT_ENTRY, "MNQ", OrderSide.SELL,
                entry, entry + 18.5, entry - 34.0,
                "STDV_OTE_LTF: " + tier + " size=" + qty + " (test)", tier, qty);
    }

    /** The PRAC account as the LIVE runner holds it: AccountState(50K) then the broker balance sync. */
    private static AccountState pracAccount() {
        AccountState a = new AccountState(50_000.0);
        a.setCurrentBalance(BROKER_BALANCE);
        return a;
    }

    private LiveRiskPath path(boolean phaseAware) {
        return new LiveRiskPath(AccountLifecycle.topstep50kEvaluation(), RiskProfile.topstep50kEvaluation(),
                new PhaseAwareRiskCalculator(), phaseAware, true, log::add);
    }

    /** The strategy's sizing hand-off (StdvOteRunnerStrategy.riskDerivedSize) for this account + context. */
    private static int strategySize(DefaultStrategyContext ctx, AccountState a, RiskLimits limits, TradeTier tier,
                                    StrategySignalEvent geometry) {
        double perTrade = ctx.perTradeRiskBudget("MNQ", tier, limits.getRiskPerTrade());
        double dll = limits.getMaxDailyLoss() + a.getNetDailyPnl();
        double mll = limits.getMaxLossLimit() - (a.getHighestEndOfDayBalance() - a.getEquity());
        double budget = StdvOteSizer.riskBudget(perTrade, dll, mll);
        StdvOteSizer.RiskSize rs = StdvOteSizer.riskDerived(budget, geometry.getEntryPrice(), geometry.getStopPrice(),
                0.25, 0.50, RiskConfig.minMicros(), Math.min(RiskConfig.maxMicros(), limits.getMaxContracts()));
        return rs.denied() ? 0 : rs.contracts();
    }

    // ── (a) ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("(a) phaseAware=false: the LIVE handler approves the PRAC LTF signal sized 5 x $37 vs $250 exactly as the harness does")
    void staticPathApprovesExactlyAsTheHarness() {
        assertThat(LiveRiskPath.phaseAwareEnabled()).as("default").isFalse();
        LiveRiskPath live = LiveRiskPath.topstep50kLive(log::add);
        assertThat(live.phaseAware()).isFalse();
        assertThat(live.budgetProviderOrNull()).as("static path installs no budget provider").isNull();

        AccountState account = pracAccount();
        RiskLimits limits = RiskLimits.topstep50k();
        live.onCandle(T0, account);
        DefaultStrategyContext ctx = new DefaultStrategyContext(account);
        ctx.setRiskBudgetProvider(live.budgetProviderOrNull());
        StrategySignalEvent geometry = ltfShort(TradeTier.TIER_3, 0);
        int size = strategySize(ctx, account, limits, TradeTier.TIER_3, geometry);
        assertThat(size).as("floor($250 / $37) = 6, capped at maxContracts 5").isEqualTo(5);

        StrategySignalEvent sig = ltfShort(TradeTier.TIER_3, size);
        LiveRiskPath.Decision liveDecision = live.evaluate(sig, account, limits, new PropFirmRiskEngine());
        RiskDecision harness = new PropFirmRiskEngine().evaluate(sig, account, limits);   // the proven path

        System.out.println("[A-05.10 a] LIVE   " + liveDecision.path() + " -> " + liveDecision.reason());
        System.out.println("[A-05.10 a] HARNESS risk.evaluate(sig, account, limits) -> " + harness.getReason());
        assertThat(liveDecision.allowed()).isTrue();
        assertThat(liveDecision.path()).isEqualTo("STATIC");
        assertThat(liveDecision.riskDecision().getOrder().getQuantity()).isEqualTo(5);
        assertThat(liveDecision.reason()).isEqualTo(harness.getReason());
        assertThat(liveDecision.reason()).contains("Approved: 5 contracts", "$185.00 risk ($37.00/micro)",
                "budget $250.00");
        assertThat(liveDecision.effectiveBudget()).isEqualTo(250.0);
        assertThat(log).noneMatch(l -> l.startsWith("[DYNAMIC RISK]"));
        // Even a TIER_2 (quality 5) signal: no quality gate on the static path.
        LiveRiskPath.Decision t2 = live.evaluate(ltfShort(TradeTier.TIER_2, 5), account, limits, new PropFirmRiskEngine());
        assertThat(t2.allowed()).isTrue();
    }

    @Test
    @DisplayName("the 09-29 defect reproduced: broker-balance equity -> CRUISE -> 'requested 5 > max 1'; the fix never reaches it")
    void defectReproduction() {
        AccountLifecycle old = AccountLifecycle.topstep50kEvaluation();
        old.syncEquityFromLive(BROKER_BALANCE);   // the pre-05.10 per-candle sync
        assertThat(old.getCurrentRiskZone()).isEqualTo(RiskZone.CRUISE);
        PhaseAwareRiskCalculator.RiskCalculation c = new PhaseAwareRiskCalculator()
                .calculateRisk(old, RiskProfile.topstep50kEvaluation(), 7, 0);
        assertThat(c.getRiskDollars()).isCloseTo(56.25, within(1e-9));
        RiskDecision d = new PropFirmRiskEngine().evaluate(ltfShort(TradeTier.TIER_3, 5), pracAccount(),
                RiskLimits.topstep50k(), c.getRiskDollars());
        System.out.println("[A-05.10 defect] " + d.getReason());
        assertThat(d.getReason()).isEqualTo(
                "RISK: requested 5 micros x $37.00 = $185.00 > risk budget $56.25 (max 1 micros)");
    }

    // ── (b) ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("(b) phaseAware=true + CRUISE: the request is sized from the reduced budget and approved at 1 micro; a wider stop is a SIZE deny with both numbers; never 'requested > max'")
    void phaseAwareSizesFromTheReducedBudget() {
        LiveRiskPath live = path(true);
        AccountState account = pracAccount();
        account.recordRealizedPnL(2_600.0);        // tracked P&L since start: 2600 / 3000 = 86.7% -> CRUISE
        RiskLimits limits = RiskLimits.topstep50k();
        live.onCandle(T0, account);
        assertThat(live.lifecycle().getCurrentRiskZone()).isEqualTo(RiskZone.CRUISE);
        DefaultStrategyContext ctx = new DefaultStrategyContext(account);
        ctx.setRiskBudgetProvider(live.budgetProviderOrNull());

        assertThat(ctx.perTradeRiskBudget("MNQ", TradeTier.TIER_3, 250.0)).isCloseTo(56.25, within(1e-9));
        int size = strategySize(ctx, account, limits, TradeTier.TIER_3, ltfShort(TradeTier.TIER_3, 0));
        assertThat(size).as("floor($56.25 / $37) = 1").isEqualTo(1);
        LiveRiskPath.Decision d = live.evaluate(ltfShort(TradeTier.TIER_3, size), account, limits, new PropFirmRiskEngine());
        System.out.println("[A-05.10 b] CRUISE TIER_3 sized " + size + " -> " + d.reason());
        assertThat(d.allowed()).isTrue();
        assertThat(d.path()).isEqualTo("PHASE_AWARE");
        assertThat(d.riskDecision().getOrder().getQuantity()).isEqualTo(1);
        assertThat(d.reason()).contains("Approved: 1 contracts", "$37.00 risk", "budget $56.25");

        // A stop whose one micro costs more than the reduced budget: the SIZER refuses with both numbers.
        StdvOteSizer.RiskSize wide = StdvOteSizer.riskDerived(
                StdvOteSizer.riskBudget(ctx.perTradeRiskBudget("MNQ", TradeTier.TIER_3, 250.0), 1000, 2000),
                30650.0, 30680.0, 0.25, 0.50, 1, 5);
        System.out.println("[A-05.10 b] CRUISE wide stop -> " + wide.reason());
        assertThat(wide.denied()).isTrue();
        assertThat(wide.reason()).isEqualTo("SIZE: stop too wide for risk budget (need $60.00/micro, have $56.25)");

        // TIER_2 (quality 5) in CRUISE: the phase-aware quality gate denies with its own numbers.
        LiveRiskPath.Decision q5 = live.evaluate(ltfShort(TradeTier.TIER_2, 1), account, limits, new PropFirmRiskEngine());
        System.out.println("[A-05.10 b] CRUISE TIER_2 -> " + q5.reason());
        assertThat(q5.allowed()).isFalse();
        assertThat(q5.deniedBy()).isEqualTo(LiveRiskPath.DeniedBy.PHASE_AWARE_GATE);
        assertThat(q5.reason()).isEqualTo("RISK: PhaseAwareRiskCalculator — Setup quality 5 < required 7 (zone=CRUISE)");

        for (LiveRiskPath.Decision x : List.of(d, q5)) {
            assertThat(x.reason()).doesNotContain("RISK: requested").doesNotContain("> risk budget");
        }
    }

    // ── (c) ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("(c) $150,620 broker balance under the 50K profile is NOT CRUISE at start: tracked P&L since start = 0 -> NORMAL")
    void brokerBalanceIsNotTheZoneInput() {
        LiveRiskPath live = path(true);
        AccountState account = pracAccount();
        live.logZone("boot");
        live.onCandle(T0, account);
        AccountLifecycle lc = live.lifecycle();
        System.out.println("[A-05.10 c] " + lc.zoneInputs());
        assertThat(account.getEquity()).isCloseTo(BROKER_BALANCE, within(1e-9));
        assertThat(lc.trackedPnlSinceStart()).isZero();
        assertThat(lc.getCurrentEquity()).isEqualTo(50_000.0);
        assertThat(lc.targetCompletionPct()).isZero();
        assertThat(lc.getCurrentRiskZone()).isEqualTo(RiskZone.NORMAL);
        assertThat(log).anyMatch(l -> l.startsWith("[LIFECYCLE ZONE] boot: zone=NORMAL")
                && l.contains("trackedPnL=$0.00") && l.contains("broker balance NOT used"));
        // NORMAL, quality 7: $250 x 1.0 x 0.75 = $187.50 -> 5 micros x $37 = $185 fits.
        assertThat(live.perTradeBudget("MNQ", TradeTier.TIER_3, 250.0)).isCloseTo(187.5, within(1e-9));
        LiveRiskPath.Decision d = live.evaluate(ltfShort(TradeTier.TIER_3, 5), account, RiskLimits.topstep50k(),
                new PropFirmRiskEngine());
        assertThat(d.allowed()).isTrue();
        // The second PRAC signal (09:51 ET, TIER_2, entry 30567 stop 30586 = $38/micro):
        // STATIC approves 5 ($190 <= $250); PHASE_AWARE NORMAL q5 = $125 -> sized 3, approved 3.
        StrategySignalEvent second = new StrategySignalEvent(SignalType.SHORT_ENTRY, "MNQ", OrderSide.SELL,
                30567.0, 30586.0, 30504.0, "STDV_OTE_LTF: second PRAC signal", TradeTier.TIER_2, 5);
        assertThat(path(false).evaluate(second, account, RiskLimits.topstep50k(), new PropFirmRiskEngine())
                .riskDecision().getOrder().getQuantity()).isEqualTo(5);
        DefaultStrategyContext ctx = new DefaultStrategyContext(account);
        ctx.setRiskBudgetProvider(live.budgetProviderOrNull());
        int size2 = strategySize(ctx, account, RiskLimits.topstep50k(), TradeTier.TIER_2, second);
        assertThat(size2).isEqualTo(3);
        LiveRiskPath.Decision d2 = live.evaluate(new StrategySignalEvent(SignalType.SHORT_ENTRY, "MNQ", OrderSide.SELL,
                30567.0, 30586.0, 30504.0, "STDV_OTE_LTF: second PRAC signal", TradeTier.TIER_2, size2),
                account, RiskLimits.topstep50k(), new PropFirmRiskEngine());
        System.out.println("[A-05.10 c] second PRAC signal PHASE_AWARE NORMAL -> " + d2.reason());
        assertThat(d2.allowed()).isTrue();
        assertThat(d2.riskDecision().getOrder().getQuantity()).isEqualTo(3);

        // The zone moves with TRACKED P&L and each change is logged with its inputs.
        log.clear();
        account.recordRealizedPnL(1_600.0);   // 53% of the $3,000 target
        live.onCandle(T0.plusSeconds(60), account);
        assertThat(lc.getCurrentRiskZone()).isEqualTo(RiskZone.PROTECTION);
        assertThat(log).anyMatch(l -> l.startsWith("[LIFECYCLE ZONE] change NORMAL -> PROTECTION")
                && l.contains("trackedPnL=$1600.00"));
    }

    @Test
    @DisplayName("risk.haltOnProfitTarget (LIVE true) reads tracked realized P&L since start, never the broker balance")
    void haltOnProfitTargetFromTrackedPnl() {
        LiveRiskPath live = LiveRiskPath.topstep50kLive(log::add);
        assertThat(live.haltOnProfitTarget()).isTrue();
        AccountState account = pracAccount();   // +$100K vs the 50K baseline at the broker: NOT a halt
        RiskLimits limits = RiskLimits.topstep50k();
        assertThat(live.evaluate(ltfShort(TradeTier.TIER_3, 5), account, limits, new PropFirmRiskEngine()).allowed())
                .isTrue();
        account.recordRealizedPnL(3_000.0);
        LiveRiskPath.Decision d = live.evaluate(ltfShort(TradeTier.TIER_3, 5), account, limits, new PropFirmRiskEngine());
        System.out.println("[A-05.10 halt] " + d.reason());
        assertThat(d.allowed()).isFalse();
        assertThat(d.deniedBy()).isEqualTo(LiveRiskPath.DeniedBy.PROFIT_TARGET_HALT);
        assertThat(d.reason()).startsWith("RISK: profit target reached: tracked realized P&L since engine start $3000.00 >= $3000.00");
        System.setProperty(RiskConfig.HALT_ON_PROFIT_TARGET, "false");
        assertThat(LiveRiskPath.topstep50kLive(log::add)
                .evaluate(ltfShort(TradeTier.TIER_3, 5), account, limits, new PropFirmRiskEngine()).allowed()).isTrue();
    }

    // ── keys + boot lines ───────────────────────────────────────────────

    @Test
    @DisplayName("risk.phaseAware is registered (default false) and the boot table prints RISK PATH with the effective budget")
    void keyAndBootLines() {
        assertThat(EngineConfig.KEYS).containsKey("risk.phaseAware");
        assertThat(EngineConfig.KEYS.get("risk.phaseAware").defaultValue()).isEqualTo("false");
        assertThat(EngineConfig.RISK_PER_TRADE_LEGACY).isEqualTo(RiskLimits.topstep50k().getRiskPerTrade());
        assertThat(EngineConfig.RISK_PER_TRADE_SCALP).isEqualTo(RiskLimits.topstep50kScalp().getRiskPerTrade());
        List<String> modes = EngineConfig.current().modeConsequences();
        System.out.println("[A-05.10 boot] " + modes.stream().filter(l -> l.startsWith("RISK PATH")).findFirst().orElse("?"));
        assertThat(modes).anyMatch(l -> l.startsWith("RISK PATH: STATIC (proven) (risk.phaseAware=false)")
                && l.contains("$250.00"));
        String staticBoot = path(false).bootLine(RiskLimits.topstep50k());
        String paBoot = path(true).bootLine(RiskLimits.topstep50k());
        System.out.println("[A-05.10 boot] " + staticBoot);
        System.out.println("[A-05.10 boot] " + paBoot);
        assertThat(staticBoot).startsWith("RISK PATH: STATIC (proven) - effective per-trade budget $250.00");
        assertThat(paBoot).startsWith("RISK PATH: PHASE_AWARE").contains("TIER_3(q7)=$187.50");

        System.setProperty(LiveRiskPath.PHASE_AWARE_KEY, "true");
        assertThat(LiveRiskPath.phaseAwareEnabled()).isTrue();
        assertThat(EngineConfig.current().modeConsequences()).anyMatch(l -> l.startsWith("RISK PATH: PHASE_AWARE"));
    }
}
