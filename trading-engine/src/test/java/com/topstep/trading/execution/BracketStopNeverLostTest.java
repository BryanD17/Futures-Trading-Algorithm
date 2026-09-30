package com.topstep.trading.execution;

import com.topstep.trading.connector.OrderListener;
import com.topstep.trading.connector.TopstepConnector;
import com.topstep.trading.connector.TopstepConnector.BrokerOrder;
import com.topstep.trading.connector.TopstepConnector.BrokerPosition;
import com.topstep.trading.connector.TopstepConnector.BrokerSnapshot;
import com.topstep.trading.domain.Order;
import com.topstep.trading.domain.OrderSide;
import com.topstep.trading.domain.OrderStatus;
import com.topstep.trading.domain.OrderType;
import com.topstep.trading.domain.Position;
import com.topstep.trading.event.EventBus;
import com.topstep.trading.event.GateDecisionEvent;
import com.topstep.trading.strategy.TradeTier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AGENT-05.11 (V5, LIVE defect 2026-09-29 11:44:17, PRAC): SHORT 5 MNQ @
 * 30647.50, stop BUY STOP 5 @ 30664.25, TPs 2 @ 30630.75 / 2 @ 30614 /
 * 1 @ 30580.50. TP1 filled (remaining 3) -> the breakeven move cancelled the
 * stop and never placed a replacement, leaving a 3-lot LIVE short with NO
 * stop. These tests replay that sequence against a stateful fake TopstepX
 * (Mockito-mocked connector) and assert the stop can no longer be lost.
 */
@DisplayName("AGENT-05.11 bracket stop can never be lost")
class BracketStopNeverLostTest {

    static final String SYM = "MNQ";
    static final double ENTRY = 30647.50;
    static final double STOP = 30664.25;
    static final double TICK = 0.25;
    static final double BREAKEVEN = ENTRY - 2 * TICK; // existing rule: entry -/+ 2 ticks = 30647.00

    /** Stateful fake of the TopstepX order book for one account. */
    static final class FakeBroker {
        final Map<String, BrokerOrder> open = new LinkedHashMap<>();
        final Map<String, OrderListener> listeners = new LinkedHashMap<>();
        final List<String> ops = new CopyOnWriteArrayList<>();
        final List<Order> marketOrders = new ArrayList<>();
        final List<Integer> stopQuantities = new ArrayList<>();
        BrokerPosition position;
        long nextId = 3582761719L;
        int failStops = 0;            // next N stop submissions are rejected
        boolean rejectSecondStop = false;
        boolean snapshotAvailable = true;
        // AGENT-05.14: Order/searchOpen lags the acknowledgement (LIVE: ~100 ms).
        long now = 1_000_000L;               // fake clock (ms), shared with the manager
        long visibilityLagMs = 0;            // a new order is listed only this long after placement
        final Map<String, Long> placedAt = new LinkedHashMap<>();
        final java.util.Set<String> hidden = new java.util.HashSet<>(); // acknowledged, never listed

        /** Orders Order/searchOpen lists right now. */
        List<BrokerOrder> listed() {
            List<BrokerOrder> out = new ArrayList<>();
            for (BrokerOrder o : open.values()) {
                if (hidden.contains(o.orderId)) continue;
                Long t = placedAt.get(o.orderId);
                if (t != null && now < t + visibilityLagMs) continue;
                out.add(o);
            }
            return out;
        }

        String placeStop(String sym, OrderSide side, int qty, double px, OrderListener l) throws IOException {
            stopQuantities.add(qty);
            if (qty <= 0) throw new IllegalArgumentException("Order quantity must be positive, got: " + qty);
            if (failStops > 0) {
                failStops--;
                ops.add("REJECT STOP " + qty + "@" + px);
                throw new IOException("Stop order rejected: simulated");
            }
            if (rejectSecondStop && open.values().stream().anyMatch(o -> o.isStop() && o.symbol.equals(sym))) {
                ops.add("REJECT 2ND STOP " + qty + "@" + px);
                throw new IOException("Stop order rejected: only one stop allowed");
            }
            String id = String.valueOf(nextId++);
            open.put(id, new BrokerOrder(id, sym, "CON.F.US.MNQ.Z26", TopstepConnector.ORDER_TYPE_STOP, side, qty, px, Double.NaN));
            placedAt.put(id, now);
            listeners.put(id, l);
            ops.add("PLACE STOP " + id + " " + side + " " + qty + "@" + px);
            return id;
        }

        String placeLimit(String sym, OrderSide side, int qty, double px, OrderListener l) {
            String id = String.valueOf(nextId++);
            open.put(id, new BrokerOrder(id, sym, "CON.F.US.MNQ.Z26", TopstepConnector.ORDER_TYPE_LIMIT, side, qty, Double.NaN, px));
            placedAt.put(id, now);
            listeners.put(id, l);
            ops.add("PLACE LIMIT " + id + " " + side + " " + qty + "@" + px);
            return id;
        }

        void cancel(String id) throws IOException {
            if (open.remove(id) == null) {
                ops.add("CANCEL-REJECT(5) " + id);
                throw new TopstepConnector.OrderNotWorkingException(id, 5, null);
            }
            ops.add("CANCEL " + id);
        }

        BrokerSnapshot snapshot() {
            if (!snapshotAvailable) return null;
            List<BrokerPosition> ps = position == null ? List.of() : List.of(position);
            return new BrokerSnapshot(ps, listed());
        }

        List<BrokerOrder> stops() {
            List<BrokerOrder> out = new ArrayList<>();
            for (BrokerOrder o : open.values()) if (o.isStop()) out.add(o);
            return out;
        }

        /** A resting order fills: it leaves the book, the position shrinks. */
        void fill(String id, double px) {
            BrokerOrder o = open.remove(id);
            ops.add("FILL " + id + " " + o.size + "@" + px);
            int left = position.size - o.size;
            position = left > 0 ? new BrokerPosition(SYM, position.contractId, position.isLong, left, position.averagePrice) : null;
            listeners.get(id).onOrderUpdate(id, OrderStatus.FILLED, px, o.size);
        }

        int indexOf(String prefix) {
            for (int i = 0; i < ops.size(); i++) if (ops.get(i).startsWith(prefix)) return i;
            return -1;
        }
    }

    FakeBroker broker;
    TopstepConnector connector;
    BracketOrderManager manager;
    Position enginePosition;
    final List<GateDecisionEvent> events = new CopyOnWriteArrayList<>();
    final AtomicReference<Double> stopExit = new AtomicReference<>();
    final AtomicReference<Double> tpExit = new AtomicReference<>();
    final List<BracketOrderManager.BracketOrder> adopted = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        broker = new FakeBroker();
        connector = mock(TopstepConnector.class);
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
        EventBus bus = mock(EventBus.class);
        when(bus.isRunning()).thenReturn(true);
        doAnswer(inv -> {
            Object e = inv.getArgument(0);
            if (e instanceof GateDecisionEvent g) events.add(g);
            return null;
        }).when(bus).publish(any());
        manager.setEventBus(bus);
        enginePosition = new Position(SYM, -5, ENTRY);
        manager.setPositionQuantityProvider(s -> SYM.equals(s) && enginePosition != null ? Math.abs(enginePosition.getQuantity()) : 0);
        manager.setListener(new BracketOrderManager.BracketListener() {
            @Override public void onStopLossFilled(BracketOrderManager.BracketOrder b, double px) { stopExit.set(px); }
            @Override public void onTakeProfitFilled(BracketOrderManager.BracketOrder b, double px) { tpExit.set(px); }
            @Override public void onPartialTakeProfitFilled(BracketOrderManager.BracketOrder b,
                                                           BracketOrderManager.TakeProfitLevel l, double px) {
                // exactly what LiveEngineRunner does: reduce the SHORT
                enginePosition.updateWithFill(l.quantity, px);
            }
            @Override public void onBracketCanceled(BracketOrderManager.BracketOrder b, String reason) {}
            @Override public void onStopMovedToBreakeven(BracketOrderManager.BracketOrder b, double px) {}
            @Override public void onPositionAdopted(BracketOrderManager.BracketOrder b) { adopted.add(b); }
        });
    }

    /** Today's entry: SHORT 5 @ 30647.50 filled, tiered bracket placed. */
    private BracketOrderManager.BracketOrder openTodaysShort() {
        broker.position = new BrokerPosition(SYM, "CON.F.US.MNQ.Z26", false, 5, ENTRY);
        manager.createBracketWithPartials(SYM, "3582761618", ENTRY, 5, OrderSide.SELL,
                STOP, 30581.25, TradeTier.TIER_3, TICK);
        return manager.getBracket(SYM);
    }

    private String tpId(BracketOrderManager.BracketOrder b, int i) {
        return b.takeProfitLevels.get(i).orderId;
    }

    private void dumpOps(String title) {
        System.out.println("── " + title + " ── broker ops:");
        broker.ops.forEach(op -> System.out.println("   " + op));
        System.out.println("   working at broker: " + broker.open.values() + " | position: " + broker.position);
    }

    @Test
    @DisplayName("REPRO 2026-09-29: 5 filled, TP1 2 filled, remaining 3 -> a 3-lot stop is working at breakeven, placed before the old one is cancelled")
    void reproductionOfTodaysSequence() {
        BracketOrderManager.BracketOrder b = openTodaysShort();
        String oldStop = b.stopOrderId;
        assertThat(b.takeProfitLevels).extracting(l -> l.quantity).containsExactly(2, 2, 1);
        assertThat(b.takeProfitLevels).extracting(l -> l.price).containsExactly(30630.75, 30614.0, 30580.5);
        assertThat(broker.stops()).singleElement().satisfies(s -> {
            assertThat(s.size).isEqualTo(5);
            assertThat(s.stopPrice).isEqualTo(STOP);
        });

        // 11:44:17 — TP1 (2 @ 30630.75) fills at the broker.
        broker.fill(tpId(b, 0), 30630.75);
        dumpOps("REPRO");

        // Exactly ONE stop working at the broker: BUY STOP 3 @ breakeven.
        List<BrokerOrder> stops = broker.stops();
        assertThat(stops).hasSize(1);
        BrokerOrder s = stops.get(0);
        assertThat(s.side).isEqualTo(OrderSide.BUY);
        assertThat(s.size).isEqualTo(3).isEqualTo(broker.position.size);
        assertThat(s.stopPrice).isEqualTo(BREAKEVEN);
        assertThat(b.stopOrderId).isEqualTo(s.orderId);
        assertThat(b.movedToBreakeven).isTrue();
        assertThat(b.stopQuantity).isEqualTo(3);

        // Place-then-cancel: the new stop was acknowledged BEFORE the old one was cancelled.
        int placeNew = broker.indexOf("PLACE STOP " + s.orderId);
        int cancelOld = broker.indexOf("CANCEL " + oldStop);
        assertThat(placeNew).isGreaterThanOrEqualTo(0);
        assertThat(cancelOld).isGreaterThan(placeNew);
        // Quantity was never 0 and no second (redundant) stop operation ran.
        assertThat(broker.stopQuantities).allMatch(q -> q > 0).containsExactly(5, 3);
        assertThat(broker.ops).noneMatch(op -> op.startsWith("CANCEL-REJECT"));
        assertThat(manager.getUnprotectedFlattenCount()).isZero();
        assertThat(broker.marketOrders).isEmpty();
    }

    @Test
    @DisplayName("quantity comes from the POSITION even when the bookkeeping counter reads 0")
    void quantityFromPositionNotCounter() {
        BracketOrderManager.BracketOrder b = openTodaysShort();
        b.remainingQuantity = 0; // the LIVE symptom: the counter read 0
        broker.fill(tpId(b, 0), 30630.75);
        assertThat(broker.stops()).singleElement().satisfies(s -> assertThat(s.size).isEqualTo(3));
        assertThat(broker.stopQuantities).allMatch(q -> q > 0);
    }

    @Test
    @DisplayName("quantity <= 0 never cancels the working stop")
    void zeroQuantityNeverCancels() {
        BracketOrderManager.BracketOrder b = openTodaysShort();
        String stop = b.stopOrderId;
        enginePosition = null;                   // no position source
        b.takeProfitLevels.forEach(l -> l.filled = true); // ledger says 0 too (corrupt)
        manager.armPriceBreakevenTrigger(SYM, 30640.0);
        manager.checkPriceBreakevenTrigger(SYM, 30639.0, TICK);
        assertThat(broker.open).containsKey(stop);
        assertThat(broker.ops).noneMatch(op -> op.startsWith("CANCEL"));
        assertThat(broker.stopQuantities).allMatch(q -> q > 0);
    }

    @Test
    @DisplayName("cancel-reject code 5 means 'already gone': the stop is replaced, not reported as still working")
    void cancelRejectCode5IsAlreadyGone() {
        BracketOrderManager.BracketOrder b = openTodaysShort();
        String oldStop = b.stopOrderId;
        broker.open.remove(oldStop);   // broker already cancelled it (11:44:19 in the LIVE log)
        broker.fill(tpId(b, 0), 30630.75);
        dumpOps("CODE 5");
        assertThat(broker.ops).contains("CANCEL-REJECT(5) " + oldStop);
        assertThat(broker.stops()).singleElement().satisfies(s -> {
            assertThat(s.size).isEqualTo(3);
            assertThat(s.stopPrice).isEqualTo(BREAKEVEN);
        });
        assertThat(manager.getUnprotectedFlattenCount()).isZero();
    }

    @Test
    @DisplayName("TopstepX refuses a 2nd stop -> cancel-then-place fallback still ends with one 3-lot stop")
    void rejectSecondStopFallsBackToCancelThenPlace() {
        BracketOrderManager.BracketOrder b = openTodaysShort();
        String oldStop = b.stopOrderId;
        broker.rejectSecondStop = true;
        broker.fill(tpId(b, 0), 30630.75);
        dumpOps("2ND STOP REJECTED");
        assertThat(broker.open).doesNotContainKey(oldStop);
        assertThat(broker.stops()).singleElement().satisfies(s -> {
            assertThat(s.size).isEqualTo(3);
            assertThat(s.stopPrice).isEqualTo(BREAKEVEN);
        });
        assertThat(b.movedToBreakeven).isTrue();
        assertThat(broker.marketOrders).isEmpty();
    }

    @Test
    @DisplayName("stop placement fails x3 in both phases -> FLATTEN at market + 'BRACKET: stop lost — flattened' event")
    void placeFailureFlattensAndPublishes() {
        BracketOrderManager.BracketOrder b = openTodaysShort();
        broker.failStops = 100;
        broker.fill(tpId(b, 0), 30630.75);
        dumpOps("PLACE FAILURE");
        assertThat(broker.marketOrders).singleElement().satisfies(o -> {
            assertThat(o.getType()).isEqualTo(OrderType.MARKET);
            assertThat(o.getSide()).isEqualTo(OrderSide.BUY);
            assertThat(o.getQuantity()).isEqualTo(3);
        });
        assertThat(events).anyMatch(e -> e.getReason().startsWith("BRACKET: stop lost — flattened"));
        assertThat(manager.getUnprotectedFlattenCount()).isEqualTo(1);
        assertThat(manager.hasBracket(SYM)).isFalse();
        // each phase retried PROTECTIVE_RETRIES times (new level A, new level B, old level B)
        assertThat(broker.ops.stream().filter(op -> op.startsWith("REJECT")).count())
                .isEqualTo(3L * BracketOrderManager.PROTECTIVE_RETRIES);
    }

    @Test
    @DisplayName("reconciliation re-places a stop missing at the broker (at the intended breakeven) + ERROR event")
    void reconciliationRePlacesMissingStop() {
        BracketOrderManager.BracketOrder b = openTodaysShort();
        broker.fill(tpId(b, 0), 30630.75);
        String be = b.stopOrderId;
        // The LIVE end state: the broker has the position but NO stop.
        broker.open.remove(be);
        broker.now += BracketOrderManager.STOP_ACK_GRACE_MS + 1; // AGENT-05.14: past the ack grace
        manager.reconcileWithBroker();
        dumpOps("RECONCILE");
        assertThat(broker.stops()).singleElement().satisfies(s -> {
            assertThat(s.size).isEqualTo(3);
            assertThat(s.stopPrice).isEqualTo(BREAKEVEN);
        });
        assertThat(manager.getStopsRestoredCount()).isEqualTo(1);
        assertThat(events).anyMatch(e -> e.getReason().startsWith("BRACKET: broker position without a stop"));
    }

    @Test
    @DisplayName("reconciliation fixes a stop whose size != position size, and cancels an extra stop")
    void reconciliationFixesSizeAndExtras() throws Exception {
        BracketOrderManager.BracketOrder b = openTodaysShort();
        // broker position shrank to 3 behind the engine's back; a manual extra stop exists
        broker.position = new BrokerPosition(SYM, "CON.F.US.MNQ.Z26", false, 3, ENTRY);
        broker.placeStop(SYM, OrderSide.BUY, 3, 30647.5, null);
        manager.reconcileWithBroker();
        dumpOps("RESIZE");
        assertThat(broker.stops()).singleElement().satisfies(s -> {
            assertThat(s.size).isEqualTo(3);
            assertThat(s.stopPrice).isEqualTo(STOP);
        });
        assertThat(b.stopQuantity).isEqualTo(3);
    }

    @Test
    @DisplayName("engine believes a dead stop is working, broker has the manual one -> adopt the broker's stop")
    void reconciliationAdoptsBrokerStopWhenEngineBeliefIsStale() throws Exception {
        BracketOrderManager.BracketOrder b = openTodaysShort();
        broker.fill(tpId(b, 0), 30630.75);
        broker.open.remove(b.stopOrderId);                       // engine's stop is gone
        String manual = broker.placeStop(SYM, OrderSide.BUY, 3, 30647.5, null); // the hand-placed one
        broker.now += BracketOrderManager.STOP_ACK_GRACE_MS + 1; // AGENT-05.14: past the ack grace
        manager.reconcileWithBroker();
        assertThat(b.stopOrderId).isEqualTo(manual);
        assertThat(broker.stops()).singleElement().satisfies(s -> assertThat(s.orderId).isEqualTo(manual));
        assertThat(broker.marketOrders).isEmpty();
    }

    @Test
    @DisplayName("flat (final TP) -> every remaining order for the symbol is cancelled at the broker and verified")
    void flatCancelsAllOrders() {
        BracketOrderManager.BracketOrder b = openTodaysShort();
        broker.fill(tpId(b, 0), 30630.75);
        broker.fill(tpId(b, 1), 30614.0);
        // an unrelated leftover order for the symbol (e.g. a stale limit)
        broker.placeLimit(SYM, OrderSide.BUY, 1, 30500.0, null);
        broker.fill(tpId(b, 2), 30580.5);
        dumpOps("FLAT");
        assertThat(broker.position).isNull();
        assertThat(broker.open).isEmpty();
        assertThat(manager.hasBracket(SYM)).isFalse();
        assertThat(tpExit.get()).isEqualTo(30580.5);
    }

    @Test
    @DisplayName("flat (stop fill) -> remaining TPs and leftovers cancelled at the broker")
    void stopFillCancelsAllOrders() {
        BracketOrderManager.BracketOrder b = openTodaysShort();
        broker.fill(b.stopOrderId, STOP);
        assertThat(broker.position).isNull();
        assertThat(broker.open).isEmpty();
        assertThat(stopExit.get()).isEqualTo(STOP);
    }

    @Test
    @DisplayName("restart: untracked 3-lot without a stop is adopted with a stop at a REAL risk distance (AGENT-05.14: was breakeven)")
    void restartAdoptsUntrackedPositionWithRiskDistanceStop() {
        broker.position = new BrokerPosition(SYM, "CON.F.US.MNQ.Z26", false, 3, ENTRY);
        manager.onLastPrice(SYM, ENTRY);
        manager.reconcileOnStartup();
        dumpOps("ADOPT");
        assertThat(manager.hasBracket(SYM)).isTrue();
        assertThat(manager.getBracket(SYM).adopted).isTrue();
        assertThat(manager.getBracket(SYM).observeOnly).isFalse();
        // topstep50k riskPerTrade $250 / (3 x $0.50) = 166 ticks = 41.50 pts above the short's entry
        assertThat(broker.stops()).singleElement().satisfies(s -> {
            assertThat(s.side).isEqualTo(OrderSide.BUY);
            assertThat(s.size).isEqualTo(3);
            assertThat(s.stopPrice).isEqualTo(ENTRY + 41.50).isNotEqualTo(ENTRY);
        });
        assertThat(adopted).hasSize(1);
        assertThat(events).anyMatch(e -> e.getReason().startsWith("BRACKET: untracked broker position adopted"));
    }

    @Test
    @DisplayName("restart: untracked position that already has a stop keeps it (no duplicate)")
    void restartAdoptsExistingStop() throws Exception {
        broker.position = new BrokerPosition(SYM, "CON.F.US.MNQ.Z26", false, 3, ENTRY);
        String manual = broker.placeStop(SYM, OrderSide.BUY, 3, 30647.5, null);
        manager.reconcileOnStartup();
        assertThat(manager.getBracket(SYM).stopOrderId).isEqualTo(manual);
        assertThat(broker.stops()).hasSize(1);
    }

    @Test
    @DisplayName("periodic pass: an untracked position is adopted only on the 2nd consecutive sighting")
    void periodicAdoptionNeedsTwoSightings() {
        broker.position = new BrokerPosition(SYM, "CON.F.US.MNQ.Z26", false, 3, ENTRY);
        manager.reconcileWithBroker();
        assertThat(manager.hasBracket(SYM)).isFalse();
        manager.reconcileWithBroker();
        assertThat(manager.hasBracket(SYM)).isTrue();
    }

    @Test
    @DisplayName("broker view unavailable -> nothing is inferred, nothing is cancelled")
    void unavailableBrokerViewIsNotInferred() {
        BracketOrderManager.BracketOrder b = openTodaysShort();
        broker.snapshotAvailable = false;
        String stop = b.stopOrderId;
        manager.reconcileWithBroker();
        assertThat(broker.open).containsKey(stop);
        assertThat(broker.marketOrders).isEmpty();
    }
}
