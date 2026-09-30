package com.topstep.trading.execution;

import com.topstep.trading.connector.TopstepConnector;
import com.topstep.trading.connector.TopstepConnector.BrokerOrder;
import com.topstep.trading.connector.TopstepConnector.BrokerPosition;
import com.topstep.trading.domain.Order;
import com.topstep.trading.domain.OrderSide;
import com.topstep.trading.domain.RiskLimits;
import com.topstep.trading.execution.BracketStopNeverLostTest.FakeBroker;
import com.topstep.trading.strategy.TradeTier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AGENT-05.14 (V5, LIVE 2026-09-30 15:34-15:36 PT, 50K Combine): the owner
 * bought 45 MNQ @ 30761.0 by hand (target, no stop). Reconciliation adopted it
 * and placed SELL STOP 45 @ 30761.0 (#3588726604, acknowledged); ~100 ms later
 * the post-adoption BROKER VIEW did not list it yet and a SECOND stop
 * (#3588726609) was placed. Both filled @ 30760.5: the long was closed and the
 * account flipped SHORT 45; the short's breakeven stop was rejected
 * ("outside allowed range") and the engine flattened 45 by market.
 *
 * <p>These tests drive the real {@link BracketOrderManager} against the
 * stateful fake TopstepX book of {@link BracketStopNeverLostTest}, with a
 * fake clock and an Order/searchOpen visibility lag.
 */
@DisplayName("AGENT-05.14 adoption safety: one stop per position, observe-only foreign positions, real stop distance")
class BracketAdoptionSafetyTest {

    static final String SYM = "MNQ";
    static final String CON = "CON.F.US.MNQ.Z26";
    static final double TICK = 0.25;
    static final double OWNER_ENTRY = 30761.0;

    FakeBroker broker;
    BracketOrderManager manager;
    final List<BracketOrderManager.BracketOrder> adopted = new CopyOnWriteArrayList<>();
    final List<String> brokerFlat = new CopyOnWriteArrayList<>();
    final List<Double> stopFills = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        broker = new FakeBroker();
        TopstepConnector connector = mock(TopstepConnector.class);
        when(connector.submitStopOrder(anyString(), any(), anyInt(), anyDouble(), any())).thenAnswer(inv ->
                broker.placeStop(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3), inv.getArgument(4)));
        when(connector.submitTakeProfitOrder(anyString(), any(), anyInt(), anyDouble(), any())).thenAnswer(inv ->
                broker.placeLimit(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3), inv.getArgument(4)));
        doAnswer(inv -> { broker.cancel(inv.getArgument(0)); return null; }).when(connector).cancelOrder(anyString());
        when(connector.fetchBrokerSnapshot()).thenAnswer(inv -> broker.snapshot());
        when(connector.submitOrder(any(), any())).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            broker.marketOrders.add(o);
            broker.ops.add("MARKET " + o.getSide() + " " + o.getQuantity());
            return "MKT-" + broker.marketOrders.size();
        });
        doAnswer(inv -> { broker.listeners.put(inv.getArgument(0), inv.getArgument(5)); return null; })
                .when(connector).trackExistingOrder(anyString(), anyString(), anyInt(), any(), anyDouble(), any());

        manager = new BracketOrderManager(connector);
        manager.setRetryBackoffMs(0);
        manager.setClock(() -> broker.now);
        manager.setRiskLimitsProvider(RiskLimits::topstep50k); // maxContracts 5, riskPerTrade $250
        manager.setListener(new BracketOrderManager.BracketListener() {
            @Override public void onStopLossFilled(BracketOrderManager.BracketOrder b, double px) { stopFills.add(px); }
            @Override public void onTakeProfitFilled(BracketOrderManager.BracketOrder b, double px) {}
            @Override public void onPartialTakeProfitFilled(BracketOrderManager.BracketOrder b,
                                                           BracketOrderManager.TakeProfitLevel l, double px) {}
            @Override public void onBracketCanceled(BracketOrderManager.BracketOrder b, String reason) {}
            @Override public void onStopMovedToBreakeven(BracketOrderManager.BracketOrder b, double px) {}
            @Override public void onPositionAdopted(BracketOrderManager.BracketOrder b) { adopted.add(b); }
            @Override public void onBrokerFlat(BracketOrderManager.BracketOrder b, String reason) { brokerFlat.add(reason); }
        });
    }

    private static String captureErr(Runnable r) {
        PrintStream old = System.err;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buf, true));
        try {
            r.run();
        } finally {
            System.setErr(old);
        }
        String s = buf.toString();
        System.out.print(s);
        return s;
    }

    private void dumpOps(String title) {
        System.out.println("── " + title + " ── broker ops:");
        broker.ops.forEach(op -> System.out.println("   " + op));
        System.out.println("   working at broker: " + broker.open.values() + " | position: " + broker.position);
    }

    private long placedStops() {
        return broker.ops.stream().filter(op -> op.startsWith("PLACE STOP")).count();
    }

    /** An owner order put straight into the book (not through the engine). */
    private String ownerOrder(int type, OrderSide side, int qty, double px) {
        String id = String.valueOf(broker.nextId++);
        boolean stop = type == TopstepConnector.ORDER_TYPE_STOP;
        broker.open.put(id, new BrokerOrder(id, SYM, CON, type, side, qty, stop ? px : Double.NaN, stop ? Double.NaN : px));
        return id;
    }

    // ── A: never two stops ─────────────────────────────────────────────────

    @Test
    @DisplayName("REPRO (engine-owned 3-lot): acknowledged stop not listed yet -> NO second stop; listed after the grace -> nothing placed")
    void ackedStopNotVisibleNoSecondPlacement() {
        broker.visibilityLagMs = 100; // LIVE: searchOpen lagged ~100 ms behind the ack
        broker.position = new BrokerPosition(SYM, CON, true, 3, OWNER_ENTRY);
        manager.onLastPrice(SYM, OWNER_ENTRY);
        String err = captureErr(manager::reconcileOnStartup); // adoption + immediate BROKER VIEW
        dumpOps("ACK LAG");
        assertThat(placedStops()).isEqualTo(1);
        assertThat(broker.stops()).hasSize(1);
        assertThat(err).doesNotContain("re-placing");

        broker.now += 1_000;          // now listed
        manager.reconcileWithBroker();
        broker.now += BracketOrderManager.STOP_ACK_GRACE_MS * 2;
        manager.reconcileWithBroker();
        assertThat(placedStops()).isEqualTo(1);
        assertThat(broker.stops()).singleElement().satisfies(s -> assertThat(s.size).isEqualTo(3));
        assertThat(broker.ops).noneMatch(op -> op.startsWith("CANCEL"));
        assertThat(broker.marketOrders).isEmpty();
    }

    @Test
    @DisplayName("engine bracket creation with a lagging broker view -> exactly one stop, no re-placement")
    void bracketCreationWithLagHasOneStop() {
        broker.visibilityLagMs = 300;
        broker.position = new BrokerPosition(SYM, CON, false, 5, 30647.50);
        manager.createBracketWithPartials(SYM, "E1", 30647.50, 5, OrderSide.SELL, 30664.25, 30581.25, TradeTier.TIER_3, TICK);
        manager.reconcileWithBroker(); // still lagging, inside the grace
        assertThat(placedStops()).isEqualTo(1);
        assertThat(broker.stops()).singleElement().satisfies(s -> assertThat(s.size).isEqualTo(5));
    }

    @Test
    @DisplayName("stop still missing after the grace -> the acknowledged id is CANCELLED first, then ONE replacement")
    void missingAfterGraceCancelThenReplace() {
        broker.position = new BrokerPosition(SYM, CON, false, 5, 30647.50);
        manager.createBracketWithPartials(SYM, "E1", 30647.50, 5, OrderSide.SELL, 30664.25, 30581.25, TradeTier.TIER_3, TICK);
        String ghost = manager.getBracket(SYM).stopOrderId;
        broker.hidden.add(ghost);   // acknowledged but never listed by Order/searchOpen

        broker.now += 1_000;        // inside the grace: nothing happens
        manager.reconcileWithBroker();
        assertThat(placedStops()).isEqualTo(1);

        broker.now += BracketOrderManager.STOP_ACK_GRACE_MS;
        String err = captureErr(manager::reconcileWithBroker);
        dumpOps("GHOST STOP");
        int cancel = broker.indexOf("CANCEL " + ghost);
        String replacement = manager.getBracket(SYM).stopOrderId;
        int place = broker.indexOf("PLACE STOP " + replacement);
        assertThat(replacement).isNotEqualTo(ghost);
        assertThat(cancel).isGreaterThanOrEqualTo(0);
        assertThat(place).isGreaterThan(cancel);
        assertThat(broker.stops()).singleElement().satisfies(s -> {
            assertThat(s.orderId).isEqualTo(replacement);
            assertThat(s.size).isEqualTo(5);
            assertThat(s.stopPrice).isEqualTo(30664.25);
        });
        assertThat(err).contains("still NOT listed by the broker").contains("cancelling it before any replacement");
    }

    @Test
    @DisplayName("stop missing after the grace and the cancel says 'not found' -> ignored, ONE replacement")
    void missingAfterGraceNotFoundIgnored() {
        broker.position = new BrokerPosition(SYM, CON, false, 5, 30647.50);
        manager.createBracketWithPartials(SYM, "E1", 30647.50, 5, OrderSide.SELL, 30664.25, 30581.25, TradeTier.TIER_3, TICK);
        String gone = manager.getBracket(SYM).stopOrderId;
        broker.open.remove(gone);
        broker.now += BracketOrderManager.STOP_ACK_GRACE_MS + 1;
        manager.reconcileWithBroker();
        assertThat(broker.ops).contains("CANCEL-REJECT(5) " + gone);
        assertThat(broker.stops()).hasSize(1);
        assertThat(placedStops()).isEqualTo(2);
    }

    @Test
    @DisplayName("INVARIANT: broker view with two working stops -> extras cancelled down to the tracked one + ERROR")
    void twoWorkingStopsCancelledDownToOne() throws Exception {
        broker.position = new BrokerPosition(SYM, CON, false, 5, 30647.50);
        manager.createBracketWithPartials(SYM, "E1", 30647.50, 5, OrderSide.SELL, 30664.25, 30581.25, TradeTier.TIER_3, TICK);
        String tracked = manager.getBracket(SYM).stopOrderId;
        String extra = broker.placeStop(SYM, OrderSide.BUY, 5, 30670.0, null);
        broker.now += BracketOrderManager.STOP_ACK_GRACE_MS + 1;
        String err = captureErr(manager::reconcileWithBroker);
        assertThat(broker.stops()).singleElement().satisfies(s -> assertThat(s.orderId).isEqualTo(tracked));
        assertThat(broker.ops).contains("CANCEL " + extra);
        assertThat(err).contains("[BRACKET] ERROR INVARIANT MNQ: 2 working stops for one position")
                .contains("keeping " + tracked);
        assertThat(manager.getExtraStopsCancelledCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("INVARIANT inside the grace: acknowledged (unlisted) stop + a listed one -> the listed extra is cancelled")
    void extraStopCancelledWhileTrackedStillInGrace() throws Exception {
        broker.position = new BrokerPosition(SYM, CON, false, 5, 30647.50);
        broker.visibilityLagMs = 1_000;
        manager.createBracketWithPartials(SYM, "E1", 30647.50, 5, OrderSide.SELL, 30664.25, 30581.25, TradeTier.TIER_3, TICK);
        String tracked = manager.getBracket(SYM).stopOrderId;
        broker.visibilityLagMs = 0;
        broker.placedAt.put(tracked, broker.now + 10_000); // still invisible
        String extra = broker.placeStop(SYM, OrderSide.BUY, 5, 30670.0, null);
        manager.reconcileWithBroker();
        assertThat(broker.open).containsKey(tracked).doesNotContainKey(extra);
        assertThat(placedStops()).isEqualTo(2); // the engine's one + the extra; no replacement
    }

    // ── B / D: observe-only ────────────────────────────────────────────────

    @Test
    @DisplayName("REPRO 15:34 PT: owner's LONG 45 (> maxContracts 5) -> OBSERVE-ONLY: no stop, no TP, no flatten, owner's orders untouched")
    void ownersFortyFiveLotIsObserveOnly() {
        broker.visibilityLagMs = 100;
        broker.position = new BrokerPosition(SYM, CON, true, 45, OWNER_ENTRY);
        String ownerTarget = ownerOrder(TopstepConnector.ORDER_TYPE_LIMIT, OrderSide.SELL, 45, 30778.5);
        manager.onLastPrice(SYM, 30761.25);
        manager.reconcileWithBroker();
        String err = captureErr(manager::reconcileWithBroker);
        dumpOps("OBSERVE-ONLY 45");

        BracketOrderManager.BracketOrder b = manager.getBracket(SYM);
        assertThat(b).isNotNull();
        assertThat(b.adopted).isTrue();
        assertThat(b.observeOnly).isTrue();
        assertThat(adopted).singleElement().satisfies(a -> assertThat(a.totalQuantity).isEqualTo(45));
        assertThat(err).contains("[BRACKET] OBSERVE-ONLY adopted MNQ LONG 45 @ 30761.0 (size > maxContracts 5):"
                + " engine will NOT place stops or flatten it");

        // many passes later, well past every grace window, price moving: still nothing
        for (int i = 0; i < 5; i++) {
            broker.now += 30_000;
            manager.onLastPrice(SYM, 30700.0 - i);
            manager.reconcileWithBroker();
        }
        assertThat(broker.ops).as("the engine sent no order and no cancel").isEmpty();
        assertThat(broker.marketOrders).isEmpty();
        assertThat(broker.open).containsKey(ownerTarget);
        assertThat(manager.getUnprotectedFlattenCount()).isZero();
        assertThat(manager.getObserveOnlyAdoptionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("observe-only keeps the owner's stops untouched (even two) and flips/resizes are re-registered observe-only")
    void observeOnlyNeverCancelsAndStaysObserveOnly() {
        broker.position = new BrokerPosition(SYM, CON, true, 45, OWNER_ENTRY);
        String s1 = ownerOrder(TopstepConnector.ORDER_TYPE_STOP, OrderSide.SELL, 45, 30740.0);
        String s2 = ownerOrder(TopstepConnector.ORDER_TYPE_STOP, OrderSide.SELL, 45, 30735.0);
        manager.reconcileOnStartup();
        assertThat(manager.getBracket(SYM).observeOnly).isTrue();
        assertThat(manager.getBracket(SYM).stopPrice).as("owner's stop reported").isEqualTo(30740.0);

        // the owner reverses to SHORT 45, then scales down to 3 lots
        broker.open.clear();
        broker.position = new BrokerPosition(SYM, CON, false, 45, 30760.5);
        manager.reconcileWithBroker();
        assertThat(manager.getBracket(SYM).observeOnly).isTrue();
        assertThat(manager.getBracket(SYM).isLong()).isFalse();
        broker.position = new BrokerPosition(SYM, CON, false, 3, 30760.5);
        manager.onLastPrice(SYM, 30770.0);
        manager.reconcileWithBroker();
        assertThat(manager.getBracket(SYM).observeOnly).as("sticky until flat").isTrue();
        assertThat(manager.getBracket(SYM).totalQuantity).isEqualTo(3);
        assertThat(broker.ops).isEmpty();
        assertThat(broker.marketOrders).isEmpty();
        assertThat(s1).isNotEqualTo(s2);
    }

    @Test
    @DisplayName("observe-only: broker FLAT on 2 passes -> released via onBrokerFlat, the owner's leftover orders are NOT swept")
    void observeOnlyReleasedOnBrokerFlat() {
        broker.position = new BrokerPosition(SYM, CON, true, 45, OWNER_ENTRY);
        manager.reconcileOnStartup();
        broker.position = null;
        String leftover = ownerOrder(TopstepConnector.ORDER_TYPE_LIMIT, OrderSide.SELL, 45, 30778.5);
        manager.reconcileWithBroker();
        assertThat(manager.hasBracket(SYM)).isTrue();
        manager.reconcileWithBroker();
        assertThat(manager.hasBracket(SYM)).isFalse();
        assertThat(brokerFlat).singleElement().satisfies(r -> assertThat(r).startsWith("BROKER_FLAT"));
        assertThat(broker.open).containsKey(leftover);
        assertThat(broker.ops).isEmpty();
    }

    // ── C: engine-owned adopted positions get a REAL stop distance ────────

    @Test
    @DisplayName("adopted LONG 3 without a stop -> SELL STOP 3 at the risk distance below entry (never at entry)")
    void adoptedThreeLotRealDistance() {
        broker.position = new BrokerPosition(SYM, CON, true, 3, OWNER_ENTRY);
        manager.onLastPrice(SYM, 30762.0);
        manager.reconcileOnStartup();
        // $250 / (3 x $0.50) = 166 ticks = 41.50 pts
        assertThat(broker.stops()).singleElement().satisfies(s -> {
            assertThat(s.side).isEqualTo(OrderSide.SELL);
            assertThat(s.size).isEqualTo(3);
            assertThat(s.stopPrice).isEqualTo(OWNER_ENTRY - 41.50).isLessThan(OWNER_ENTRY);
        });
        assertThat(manager.getBracket(SYM).observeOnly).isFalse();
    }

    @Test
    @DisplayName("stop distance never below the 8-tick minimum (tiny riskPerTrade)")
    void minimumDistanceTicks() {
        manager.setRiskLimitsProvider(() -> RiskLimits.topstep50k().toBuilder().riskPerTrade(3.0).build());
        broker.position = new BrokerPosition(SYM, CON, false, 5, 30760.5);
        manager.onLastPrice(SYM, 30760.0);
        manager.reconcileOnStartup();
        // $3 / (5 x $0.50) = 1 tick < 8 -> 8 ticks = 2.00 pts above the short's entry
        assertThat(broker.stops()).singleElement().satisfies(s -> {
            assertThat(s.side).isEqualTo(OrderSide.BUY);
            assertThat(s.stopPrice).isEqualTo(30762.5);
        });
    }

    @Test
    @DisplayName("REPRO 15:35 PT: SHORT whose stop would be BELOW the market -> clamped above the last price, not rejected, no flatten")
    void wrongSideStopIsClamped() {
        broker.position = new BrokerPosition(SYM, CON, false, 3, 30760.5);
        manager.onLastPrice(SYM, 30820.0); // price ran 59.5 pts against the short (> 41.50 risk distance)
        String err = captureErr(manager::reconcileOnStartup);
        // raw stop 30802.0 <= last 30820 -> clamped to 30820 + 4 ticks
        assertThat(broker.stops()).singleElement().satisfies(s -> {
            assertThat(s.side).isEqualTo(OrderSide.BUY);
            assertThat(s.stopPrice).isEqualTo(30821.0).isGreaterThan(30820.0);
        });
        assertThat(err).contains("would be on the WRONG side of the last price 30820.0 — clamped to 30821.0");
        assertThat(broker.marketOrders).isEmpty();
        assertThat(manager.getUnprotectedFlattenCount()).isZero();
    }

    @Test
    @DisplayName("adopted 3-lot and NO price yet -> nothing placed (waits); first price -> stop placed at the risk distance")
    void waitsForPriceBeforePlacing() {
        broker.position = new BrokerPosition(SYM, CON, false, 3, 30760.5);
        manager.reconcileOnStartup();
        broker.now += BracketOrderManager.STOP_ACK_GRACE_MS * 3;
        manager.reconcileWithBroker();
        assertThat(broker.ops).isEmpty();
        assertThat(manager.hasBracket(SYM)).isTrue();
        assertThat(adopted).hasSize(1);

        manager.onLastPrice(SYM, 30761.0);
        assertThat(broker.stops()).singleElement().satisfies(s -> {
            assertThat(s.size).isEqualTo(3);
            assertThat(s.stopPrice).isEqualTo(30760.5 + 41.50);
        });
        manager.onLastPrice(SYM, 30762.0);
        manager.reconcileWithBroker();
        assertThat(placedStops()).isEqualTo(1);
    }

    @Test
    @DisplayName("engine-owned adopted position whose stop cannot be placed -> still flattened (rule D keeps the fallback)")
    void engineOwnedStillFlattensWhenNoStopPossible() {
        broker.position = new BrokerPosition(SYM, CON, false, 3, 30760.5);
        manager.onLastPrice(SYM, 30761.0);
        broker.failStops = 100;
        manager.reconcileOnStartup();
        assertThat(broker.marketOrders).singleElement().satisfies(o -> {
            assertThat(o.getSide()).isEqualTo(OrderSide.BUY);
            assertThat(o.getQuantity()).isEqualTo(3);
        });
        assertThat(manager.getUnprotectedFlattenCount()).isEqualTo(1);
    }

    // ── regression ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("REGRESSION: normal engine bracket -> exactly one stop of the position size, TPs placed, no cancels")
    void normalEngineBracketOneStop() {
        broker.position = new BrokerPosition(SYM, CON, false, 5, 30647.50);
        manager.onLastPrice(SYM, 30647.0);
        manager.createBracketWithPartials(SYM, "E1", 30647.50, 5, OrderSide.SELL, 30664.25, 30581.25, TradeTier.TIER_3, TICK);
        assertThat(broker.stops()).singleElement().satisfies(s -> {
            assertThat(s.size).isEqualTo(5);
            assertThat(s.stopPrice).isEqualTo(30664.25);
        });
        assertThat(broker.stopQuantities).containsExactly(5);
        assertThat(broker.ops).noneMatch(op -> op.startsWith("CANCEL"));
        assertThat(manager.getBracket(SYM).observeOnly).isFalse();
        assertThat(manager.getBracket(SYM).adopted).isFalse();
    }
}
