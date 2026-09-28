package com.topstep.trading.risk;

import com.topstep.trading.domain.AccountState;
import com.topstep.trading.domain.OrderSide;
import com.topstep.trading.domain.RiskLimits;
import com.topstep.trading.event.EventBus;
import com.topstep.trading.event.GateDecisionEvent;
import com.topstep.trading.event.StrategySignalEvent;
import com.topstep.trading.event.StrategySignalEvent.SignalType;
import com.topstep.trading.strategy.TradeTier;
import com.topstep.trading.strategy.stdvote.StdvOteSizer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AGENT-05 (V5 RC-13 / RC-14): ONE sizer, ONE RR truth.
 *
 * <p>Topstep micro rule implemented: size = floor(riskDollars / (stopTicks x
 * tickValue)) clamped to [size.minMicros (1), min(size.maxMicros 20,
 * RiskLimits.maxContracts)]; below the floor = DENY with both dollar numbers.
 * MNQ: tick 0.25 = $0.50, 4 ticks/pt = $2.00/pt.
 */
class RiskDerivedSizingTest {

    private static final double MNQ_TICK = 0.25;
    private static final double MNQ_TICK_VALUE = 0.50;

    @AfterEach
    void clear() {
        System.clearProperty("scalpMode.enabled");
    }

    private static StrategySignalEvent shortSignal(double entry, double stop, double target, int qty) {
        return new StrategySignalEvent(SignalType.SHORT_ENTRY, "MNQ", OrderSide.SELL,
                entry, stop, target, "test", TradeTier.TIER_1, qty, 2.0, null, false,
                Instant.parse("2026-09-28T19:05:00Z"));
    }

    private static StrategySignalEvent longSignal(double entry, double stop, double target, int qty) {
        return new StrategySignalEvent(SignalType.LONG_ENTRY, "MNQ", OrderSide.BUY,
                entry, stop, target, "test", TradeTier.TIER_1, qty, 2.0, null, false,
                Instant.parse("2026-09-28T14:00:00Z"));
    }

    @Test
    void sizerTableMnqAt250() {
        int[] pts = {10, 20, 40, 80, 160};
        int[] expected = {12, 6, 3, 1, 0};
        System.out.println("SIZER TABLE — MNQ, riskPerTrade $250, tick 0.25 = $0.50 ($2/pt), band [1, 20]");
        System.out.println("stop pts | stop ticks | $/micro | size | decision");
        for (int i = 0; i < pts.length; i++) {
            double entry = 20000.0;
            StdvOteSizer.RiskSize rs = StdvOteSizer.riskDerived(250.0, entry, entry - pts[i],
                    MNQ_TICK, MNQ_TICK_VALUE, 1, 20);
            System.out.printf("%8d | %10.0f | %7.2f | %4d | %s%n", pts[i], rs.stopTicks(),
                    rs.perContract(), rs.contracts(), rs.denied() ? "DENY " + rs.reason() : "OK");
            assertThat(rs.contracts()).as("stop %d pts", pts[i]).isEqualTo(expected[i]);
        }
        StdvOteSizer.RiskSize deny = StdvOteSizer.riskDerived(250.0, 20000, 19840, MNQ_TICK, MNQ_TICK_VALUE, 1, 20);
        assertThat(deny.denied()).isTrue();
        assertThat(deny.reason()).isEqualTo("SIZE: stop too wide for risk budget (need $320.00, have $250.00)");
    }

    @Test
    void preferredMicrosIsNotAFloor() {
        // 80 pt -> 1 micro even though size.preferredMicros = 5.
        assertThat(RiskConfig.preferredMicros()).isEqualTo(5);
        assertThat(StdvOteSizer.riskDerived(250, 20000, 19920, MNQ_TICK, MNQ_TICK_VALUE, 1, 20).contracts())
                .isEqualTo(1);
    }

    /** FABLE-REJECT #1: the killzone boost never raises $ risk above the budget. */
    @Test
    void killzoneBoostNeverExceedsTheDollarRiskBudget() {
        // $150 budget, 28-pt MNQ stop = 112 ticks = $56/micro -> risk-derived 2 ($112).
        StdvOteSizer.RiskSize rs = StdvOteSizer.riskDerived(150, 30482.5, 30510.5, MNQ_TICK, MNQ_TICK_VALUE, 1, 20);
        int boosted = StdvOteSizer.applyBoost(rs.contracts(), 1.5, 20, 150, rs.perContract());
        System.out.printf("KILLZONE BOOST: budget $150, $%.2f/micro, risk-derived %d, boost x1.5 -> %d micros ($%.2f risk)%n",
                rs.perContract(), rs.contracts(), boosted, boosted * rs.perContract());
        assertThat(rs.contracts()).isEqualTo(2);
        assertThat(boosted).isEqualTo(2);
        assertThat(boosted * rs.perContract()).isEqualTo(112.0).isLessThanOrEqualTo(150.0);
        // Across a grid, the boosted $ risk is always <= budget.
        for (double budget : new double[] {100, 150, 250, 500}) {
            for (double pts : new double[] {2, 5, 10, 13.25, 28, 39.25}) {
                StdvOteSizer.RiskSize r = StdvOteSizer.riskDerived(budget, 20000, 20000 - pts, MNQ_TICK, MNQ_TICK_VALUE, 1, 20);
                if (r.denied()) continue;
                for (double boost : new double[] {1.25, 1.5, 2.0}) {
                    int b = StdvOteSizer.applyBoost(r.contracts(), boost, 20, budget, r.perContract());
                    assertThat(b * r.perContract()).as("budget %s stop %s boost %s", budget, pts, boost)
                            .isLessThanOrEqualTo(budget + 1e-9);
                    assertThat(b).isLessThanOrEqualTo(20);
                }
            }
        }
        // The boost only fills headroom a CAP left below the budget-implied max:
        // 10-pt stop @ $250 -> 12 by budget; a tier cap of 8 leaves headroom;
        // x1.5 -> min(12, 12, 20) = 12 ($240 <= $250).
        assertThat(StdvOteSizer.applyBoost(8, 1.5, 20, 250, 20.0)).isEqualTo(12);
    }

    @Test
    void g1SizingInsideTheEnvelope() {
        // G1: SELL 30635.75, stop ~30675 (39.25 pt = 157 ticks = $78.50/micro).
        double entry = 30635.75, stop = 30675.0, t1 = 30558.0;
        StdvOteSizer.RiskSize at250 = StdvOteSizer.riskDerived(250, entry, stop, MNQ_TICK, MNQ_TICK_VALUE, 1, 20);
        StdvOteSizer.RiskSize at150 = StdvOteSizer.riskDerived(150, entry, stop, MNQ_TICK, MNQ_TICK_VALUE, 1, 20);
        System.out.printf("G1 sizing: stop %.2f pt = %.0f ticks = $%.2f/micro | $250 -> %d micros ($%.2f risk) | $150 -> %d micro ($%.2f risk)%n",
                stop - entry, at250.stopTicks(), at250.perContract(),
                at250.contracts(), at250.contracts() * at250.perContract(),
                at150.contracts(), at150.contracts() * at150.perContract());
        assertThat(at250.contracts()).isEqualTo(3);
        assertThat(at250.contracts() * at250.perContract()).isLessThanOrEqualTo(250.0);
        assertThat(at150.contracts()).isEqualTo(1);
        assertThat(at150.contracts() * at150.perContract()).isLessThanOrEqualTo(150.0);

        // Through the risk engine (legacy topstep50k: $250, DLL $1000, MLL $2000, max 5).
        PropFirmRiskEngine engine = new PropFirmRiskEngine();
        AccountState acct = new AccountState(50_000.0);
        var d = engine.evaluate(shortSignal(entry, stop, t1, 3), acct, RiskLimits.topstep50k());
        System.out.println("G1 risk engine (legacy $250): " + d.getReason());
        assertThat(d.isAllowed()).as(d.getReason()).isTrue();
        assertThat(d.getOrder().getQuantity()).isEqualTo(3);
        // Scalp profile ($150).
        var ds = engine.evaluate(shortSignal(entry, stop, t1, 1), acct, RiskLimits.topstep50kScalp());
        System.out.println("G1 risk engine (scalp $150): " + ds.getReason());
        assertThat(ds.isAllowed()).as(ds.getReason()).isTrue();
        assertThat(ds.getOrder().getQuantity()).isEqualTo(1);
        // Envelope: $-risk <= riskPerTrade <= DLL room; contracts <= maxContracts.
        assertThat(3 * 78.5).isLessThan(RiskLimits.topstep50k().getMaxDailyLoss());
    }

    @Test
    void riskEngineHonoursRequestedSizeAndDeniesAnythingAboveTheBudget() {
        PropFirmRiskEngine engine = new PropFirmRiskEngine();
        AccountState acct = new AccountState(50_000.0);
        RiskLimits scalp = RiskLimits.topstep50kScalp(); // $150, maxContracts 20
        // 20-pt stop: $40/micro -> risk-derived max 3 ($120). Strategy asks 2 -> honoured.
        var smaller = engine.evaluate(longSignal(20000, 19980, 20020, 2), acct, scalp);
        assertThat(smaller.getOrder().getQuantity()).isEqualTo(2);
        assertThat(smaller.getReason()).contains("honoured requested 2");
        // Strategy asks 3 == derived max -> 3 ($120 <= $150).
        assertThat(engine.evaluate(longSignal(20000, 19980, 20020, 3), acct, scalp)
                .getOrder().getQuantity()).isEqualTo(3);
        // Strategy asks 4 ($160 > $150 budget) -> DENIED with both numbers (no trimming,
        // scalp mode or not — a killzone boost can never buy extra dollar risk).
        System.setProperty("scalpMode.enabled", "true");
        var over = engine.evaluate(longSignal(20000, 19980, 20020, 4), acct, scalp);
        System.out.println("RISK over-budget request: " + over.getReason());
        assertThat(over.isAllowed()).isFalse();
        assertThat(over.getReason())
                .isEqualTo("RISK: requested 4 micros x $40.00 = $160.00 > risk budget $150.00 (max 3 micros)");
        // Over maxContracts (legacy max 5) with $ inside the budget -> denied too.
        var tooMany = engine.evaluate(longSignal(20000, 19995, 20010, 6), acct, RiskLimits.topstep50k());
        assertThat(tooMany.isAllowed()).isFalse();
        assertThat(tooMany.getReason()).contains("requested 6 micros");
    }

    @Test
    void unrealisticCeilingGoneFloorFromRiskLimitsAndDeniesPublishGateDecisions() throws Exception {
        EventBus bus = new EventBus();
        CopyOnWriteArrayList<GateDecisionEvent> gates = new CopyOnWriteArrayList<>();
        bus.subscribe(GateDecisionEvent.class, gates::add);
        bus.start();
        try {
            PropFirmRiskEngine engine = new PropFirmRiskEngine();
            engine.setEventBus(bus);
            AccountState acct = new AccountState(50_000.0);
            RiskLimits legacy = RiskLimits.topstep50k();
            assertThat(legacy.getRrFloor()).isEqualTo(1.0);
            assertThat(legacy.getRrCeiling()).isEqualTo(5.0);

            // RR 5.5 — used to be "R:R too high (unrealistic)" at > 6 and "too low" < 3.
            var rr55 = engine.evaluate(longSignal(20000, 19990, 20055, 2), acct, legacy);
            assertThat(rr55.isAllowed()).as(rr55.getReason()).isTrue();
            // RR 2.0 (G1) — used to be DENIED "R:R too low: 2.0 < 3.0".
            assertThat(engine.evaluate(longSignal(20000, 19990, 20020, 2), acct, legacy).isAllowed()).isTrue();
            // RR 0.9 < floor 1.0 -> denied with both numbers.
            var low = engine.evaluate(longSignal(20000, 19990, 20009, 2), acct, legacy);
            assertThat(low.isAllowed()).isFalse();
            assertThat(low.getReason()).isEqualTo("RISK: R:R too low: 0.90 < 1.0");
            // 160-pt stop -> SIZE deny.
            var wide = engine.evaluate(longSignal(20000, 19840, 20400, 1), acct, legacy);
            assertThat(wide.isAllowed()).isFalse();
            assertThat(wide.getReason()).isEqualTo("SIZE: stop too wide for risk budget (need $320.00, have $250.00)");

            long deadline = System.currentTimeMillis() + 3000;
            while (gates.size() < 2 && System.currentTimeMillis() < deadline) {
                TimeUnit.MILLISECONDS.sleep(10);
            }
            assertThat(gates).extracting(GateDecisionEvent::getGate).containsExactlyInAnyOrder("RISK", "SIZE");
            GateDecisionEvent size = gates.stream().filter(g -> g.getGate().equals("SIZE")).findFirst().orElseThrow();
            assertThat(size.getNumberA()).isEqualTo(320.0);
            assertThat(size.getNumberB()).isEqualTo(250.0);
            assertThat(size.getCandleTime()).isEqualTo(Instant.parse("2026-09-28T14:00:00Z"));
            assertThat(engine.getDenialCount()).isEqualTo(2);
        } finally {
            bus.stop();
        }
    }

    @Test
    void freshSimAccountIsInGoodStanding() {
        PropFirmRiskEngine engine = new PropFirmRiskEngine();
        AccountState fresh = new AccountState(50_000.0);
        boolean legacy = engine.isAccountInGoodStanding(fresh, RiskLimits.topstep50k());
        boolean scalp = engine.isAccountInGoodStanding(fresh, RiskLimits.topstep50kScalp());
        System.out.println("fresh SIM AccountState(50000): goodStanding legacy=" + legacy + " scalp=" + scalp
                + " profitTargetMet=" + engine.hasMetProfitTarget(fresh, RiskLimits.topstep50k())
                + " haltOnProfitTarget(SIM)=" + RiskConfig.haltOnProfitTarget(false)
                + " haltOnProfitTarget(LIVE)=" + RiskConfig.haltOnProfitTarget(true));
        assertThat(legacy).isTrue();
        assertThat(scalp).isTrue();
        assertThat(engine.hasMetProfitTarget(fresh, RiskLimits.topstep50k())).isFalse();
        assertThat(RiskConfig.haltOnProfitTarget(false)).isFalse();
        assertThat(RiskConfig.haltOnProfitTarget(true)).isTrue();
        // And a fresh account passes every account gate of the risk engine.
        assertThat(engine.evaluate(longSignal(20000, 19990, 20020, 2), fresh, RiskLimits.topstep50k())
                .isAllowed()).isTrue();
    }

    @Test
    void noEntryBlockIs1445To1700Ct() {
        // 2026-09-28 is CDT (UTC-5): 14:44 CT = 19:44Z, 14:45 CT = 19:45Z, 17:00 CT = 22:00Z.
        assertThat(RiskConfig.inNoEntryBlock(Instant.parse("2026-09-28T19:44:59Z"))).isFalse();
        assertThat(RiskConfig.inNoEntryBlock(Instant.parse("2026-09-28T19:45:00Z"))).isTrue();
        assertThat(RiskConfig.inNoEntryBlock(Instant.parse("2026-09-28T21:59:59Z"))).isTrue();
        assertThat(RiskConfig.inNoEntryBlock(Instant.parse("2026-09-28T22:00:00Z"))).isFalse();
        // Winter (CST, UTC-6): 14:45 CT = 20:45Z.
        assertThat(RiskConfig.inNoEntryBlock(Instant.parse("2026-12-01T20:44:00Z"))).isFalse();
        assertThat(RiskConfig.inNoEntryBlock(Instant.parse("2026-12-01T20:45:00Z"))).isTrue();
    }
}
