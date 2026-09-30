package com.topstep.trading;

import com.topstep.trading.connector.OrderListener;
import com.topstep.trading.connector.TopstepConnector;
import com.topstep.trading.connector.TopstepConnector.BrokerOrder;
import com.topstep.trading.connector.TopstepConnector.BrokerPosition;
import com.topstep.trading.connector.TopstepConnector.BrokerSnapshot;
import com.topstep.trading.domain.AccountState;
import com.topstep.trading.domain.Candle;
import com.topstep.trading.domain.Order;
import com.topstep.trading.domain.OrderSide;
import com.topstep.trading.domain.OrderType;
import com.topstep.trading.domain.Position;
import com.topstep.trading.domain.RiskLimits;
import com.topstep.trading.event.StrategySignalEvent.SignalType;
import com.topstep.trading.event.EventBus;
import com.topstep.trading.event.PositionClosedEvent;
import com.topstep.trading.event.StrategySignalEvent;
import com.topstep.trading.execution.BracketOrderManager;
import com.topstep.trading.execution.ExecutionEngine;
import com.topstep.trading.risk.PropFirmRiskEngine;
import com.topstep.trading.risk.RiskDecision;
import com.topstep.trading.strategy.TradeTier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * AGENT-05.13 (V5, LIVE 2026-09-30 01:05-01:08 PT, Main b52e6c8): the 30 s
 * reconciliation ADOPTED an untracked manual SHORT 45 MNQ @ 30688.75 (stop
 * #3584921305 BUY STOP 45 @ 30711.75); the owner closed it; reconciliation
 * saw the broker FLAT on 2 passes and dropped the bracket, but the position
 * stayed in AccountState for two hours (getTotalContracts()=45 -> every entry
 * would be denied by the total-contracts gate; /api/positions showed +$7,650
 * and the stale stop 30648.75 of an earlier 5-lot trade).
 *
 * <p>These tests drive the real {@link BracketOrderManager} reconciliation
 * against a fake TopstepX book, wired to the real {@link LiveBracketListener}
 * (the LIVE runner's bracket funnel) and a real {@link AccountState}.
 */
@DisplayName("AGENT-05.13 adopted / broker-flat position release")
class AdoptedPositionReleaseTest {

    static final String SYM = "MNQ";
    static final String CONTRACT = "CON.F.US.MNQ.Z26";
    static final double ADOPT_ENTRY = 30688.75;
    static final double ADOPT_STOP = 30711.75;
    static final double STALE_STOP = 30648.75;

    /** Minimal stateful TopstepX book. */
    static final class FakeBroker {
        final Map<String, BrokerOrder> open = new LinkedHashMap<>();
        final Map<String, OrderListener> listeners = new LinkedHashMap<>();
        BrokerPosition position;
        long nextId = 3584921400L;

        BrokerSnapshot snapshot() {
            List<BrokerPosition> ps = position == null ? List.of() : List.of(position);
            return new BrokerSnapshot(ps, new ArrayList<>(open.values()));
        }

        String place(int type, String sym, OrderSide side, int qty, double px, OrderListener l) {
            String id = String.valueOf(nextId++);
            boolean stop = type == TopstepConnector.ORDER_TYPE_STOP;
            open.put(id, new BrokerOrder(id, sym, CONTRACT, type, side, qty,
                    stop ? px : Double.NaN, stop ? Double.NaN : px));
            listeners.put(id, l);
            return id;
        }

        void cancel(String id) throws java.io.IOException {
            if (open.remove(id) == null) throw new TopstepConnector.OrderNotWorkingException(id, 5, null);
        }

        /** The account owner closes the position by hand: flat, book emptied. */
        void ownerClosesEverything() {
            position = null;
            open.clear();
        }
    }

    FakeBroker broker;
    BracketOrderManager manager;
    AccountState account;
    ExecutionEngine exec;
    LiveBracketListener listener;
    final List<PositionClosedEvent> closedEvents = new CopyOnWriteArrayList<>();
    final List<double[]> lifecycleCloses = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        broker = new FakeBroker();
        TopstepConnector connector = mock(TopstepConnector.class);
        when(connector.submitStopOrder(anyString(), any(), anyInt(), anyDouble(), any())).thenAnswer(inv ->
                broker.place(TopstepConnector.ORDER_TYPE_STOP, inv.getArgument(0), inv.getArgument(1),
                        inv.getArgument(2), inv.getArgument(3), inv.getArgument(4)));
        when(connector.submitTakeProfitOrder(anyString(), any(), anyInt(), anyDouble(), any())).thenAnswer(inv ->
                broker.place(TopstepConnector.ORDER_TYPE_LIMIT, inv.getArgument(0), inv.getArgument(1),
                        inv.getArgument(2), inv.getArgument(3), inv.getArgument(4)));
        doAnswer(inv -> { broker.cancel(inv.getArgument(0)); return null; }).when(connector).cancelOrder(anyString());
        when(connector.fetchBrokerSnapshot()).thenAnswer(inv -> broker.snapshot());
        when(connector.submitOrder(any(), any())).thenReturn("MKT-1");
        doAnswer(inv -> { broker.listeners.put(inv.getArgument(0), inv.getArgument(5)); return null; })
                .when(connector).trackExistingOrder(anyString(), anyString(), anyInt(), any(), anyDouble(), any());

        account = new AccountState(50_000.0);
        exec = new ExecutionEngine(account);
        EventBus bus = mock(EventBus.class);
        doAnswer(inv -> {
            Object e = inv.getArgument(0);
            if (e instanceof PositionClosedEvent p) closedEvents.add(p);
            return null;
        }).when(bus).publish(any());
        listener = new LiveBracketListener(account, exec, bus,
                (sym, pnl) -> lifecycleCloses.add(new double[] {pnl}));

        manager = new BracketOrderManager(connector);
        manager.setListener(listener);
        manager.setPositionQuantityProvider(s -> {
            Position p = account.getPosition(s);
            return p == null ? 0 : Math.abs(p.getQuantity());
        });
    }

    /** Broker state at 01:05:06 PT: SHORT 45 @ 30688.75 + the owner's LIMIT 45 and STOP 45. */
    private void ownersManualShort45() {
        broker.position = new BrokerPosition(SYM, CONTRACT, false, 45, ADOPT_ENTRY);
        broker.open.put("3584921116", new BrokerOrder("3584921116", SYM, CONTRACT,
                TopstepConnector.ORDER_TYPE_LIMIT, OrderSide.BUY, 45, Double.NaN, 30671.25));
        broker.open.put("3584921305", new BrokerOrder("3584921305", SYM, CONTRACT,
                TopstepConnector.ORDER_TYPE_STOP, OrderSide.BUY, 45, ADOPT_STOP, Double.NaN));
    }

    /** Two periodic passes = adoption (1st sighting is only a warning). */
    private void reconcileTwice() {
        manager.reconcileWithBroker();
        manager.reconcileWithBroker();
    }

    private static StrategySignalEvent newMnqSignal() {
        return new StrategySignalEvent(SignalType.LONG_ENTRY, SYM, OrderSide.BUY,
                21000.0, 20990.0, 21030.0, "05.13 test", TradeTier.TIER_1, 5);
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

    @Test
    @DisplayName("REPRO: adopted SHORT 45 -> owner closes -> broker FLAT 2 passes -> AccountState has no position, 0 contracts, P&L untouched, new signal passes the total-contracts gate")
    void adoptedPositionReleasedOnBrokerFlat() {
        ownersManualShort45();
        reconcileTwice();

        // adoption (PR #172 behaviour) intact
        assertThat(manager.getBracket(SYM)).isNotNull();
        assertThat(manager.getBracket(SYM).adopted).isTrue();
        Position adopted = account.getPosition(SYM);
        assertThat(adopted).isNotNull();
        assertThat(adopted.getQuantity()).isEqualTo(-45);
        assertThat(adopted.isAdopted()).isTrue();
        assertThat(account.getTotalContracts()).isEqualTo(45);

        // the defect's consequence while it is tracked: every entry denied
        RiskLimits limits = RiskLimits.topstep50k();
        PropFirmRiskEngine risk = new PropFirmRiskEngine();
        RiskDecision blocked = risk.evaluate(newMnqSignal(), account, limits);
        assertThat(blocked.isAllowed()).isFalse();
        assertThat(blocked.getReason()).contains("max total contracts");

        double realizedBefore = account.getRealizedPnL();
        double todayBefore = account.getRealizedPnlToday();
        int tradesBefore = account.getTradesToday();

        // 01:07:30 the owner closes it; 01:07:36 + 01:08:06 reconciliation sees FLAT twice
        broker.ownerClosesEverything();
        manager.reconcileWithBroker();
        assertThat(account.hasPosition(SYM)).as("1st flat sighting only warns").isTrue();
        String err = captureErr(manager::reconcileWithBroker);

        assertThat(manager.getBracket(SYM)).isNull();
        assertThat(account.getPosition(SYM)).isNull();
        assertThat(account.getPositions()).isEmpty();
        assertThat(account.getTotalContracts()).isZero();
        assertThat(account.getRealizedPnL()).isEqualTo(realizedBefore);
        assertThat(account.getRealizedPnlToday()).isEqualTo(todayBefore);
        assertThat(account.getTradesToday()).isEqualTo(tradesBefore);
        assertThat(lifecycleCloses).as("no lifecycle trade for a non-engine position").isEmpty();
        assertThat(closedEvents).as("no strategy owns an adopted position").isEmpty();
        assertThat(err).contains("[LIVE] ADOPTED position released: MNQ (broker flat)");

        RiskDecision after = risk.evaluate(newMnqSignal(), account, limits);
        assertThat(after.getReason()).doesNotContain("max total contracts");
        assertThat(after.isAllowed()).as("reason: %s", after.getReason()).isTrue();
    }

    @Test
    @DisplayName("adopted position carries the ADOPTED broker stop (30711.75), not the previous trade's stale stop")
    void adoptedPositionReportsAdoptedStop() {
        // an earlier engine 5-lot SHORT (stop 30648.75) filled in SIM terms and
        // was then closed by a LIVE bracket callback path that (on Main) never
        // cleared ExecutionEngine's levels: reproduce the leftover levels.
        Order entry = Order.builder().symbol(SYM).side(OrderSide.SELL).type(OrderType.LIMIT)
                .quantity(5).limitPrice(30630.0).build();
        exec.submitOrderEnhanced(entry, STALE_STOP, 30600.0, TradeTier.TIER_2, new double[0][]);
        exec.onNewCandle(new Candle(SYM, Instant.parse("2026-09-29T15:00:00Z"),
                30625.0, 30632.0, 30624.0, 30628.0, 100)); // entry fills
        assertThat(exec.getActiveOrdersList(SYM)).isEmpty();
        account.closePosition(SYM); // position gone, levels linger (Main behaviour)
        assertThat(exec.getOrderLevels(SYM).getCurrentStopPrice()).isEqualTo(STALE_STOP);

        ownersManualShort45();
        reconcileTwice();

        assertThat(manager.getBracket(SYM).stopPrice).isEqualTo(ADOPT_STOP);
        ExecutionEngine.EnhancedOrderLevels levels = exec.getOrderLevels(SYM);
        assertThat(levels).isNotNull();
        assertThat(levels.getCurrentStopPrice()).isEqualTo(ADOPT_STOP);
        assertThat(levels.getFinalTargetPrice()).as("no engine target -> API shows null").isZero();

        // released -> levels gone too (API shows nothing stale next time)
        broker.ownerClosesEverything();
        reconcileTwice();
        assertThat(exec.getOrderLevels(SYM)).isNull();
    }

    @Test
    @DisplayName("adopted position without a known stop -> no stale stop is reported (levels cleared)")
    void adoptedUnknownStopClearsLevels() {
        exec.setAdoptedOrderLevels(SYM, 30630.0, STALE_STOP, OrderSide.SELL, 5);
        exec.setAdoptedOrderLevels(SYM, ADOPT_ENTRY, Double.NaN, OrderSide.SELL, 45);
        assertThat(exec.getOrderLevels(SYM)).isNull();
    }

    @Test
    @DisplayName("adopted position: open GAIN never feeds tracked/daily P&L; open LOSS still does (DLL never loosened)")
    void adoptedMarkToMarket() {
        ownersManualShort45();
        reconcileTwice();
        Map<String, Double> tick = Map.of(SYM, 0.50);

        // price falls 170 pts -> the manual short is +$15,300 open
        account.updateUnrealizedPnL(Map.of(SYM, ADOPT_ENTRY - 170.0), tick);
        assertThat(account.getUnrealizedPnL()).isZero();
        assertThat(account.getNetDailyPnl()).isZero();
        assertThat(account.getEquity()).isEqualTo(50_000.0);

        // price rises 10 pts -> -$900 open: still counted
        account.updateUnrealizedPnL(Map.of(SYM, ADOPT_ENTRY + 10.0), tick);
        assertThat(account.getUnrealizedPnL()).isEqualTo(-900.0);
        assertThat(account.getNetDailyPnl()).isEqualTo(-900.0);
    }

    @Test
    @DisplayName("engine positions are marked-to-market exactly as before (gains and losses)")
    void enginePositionMarkToMarketUnchanged() {
        account.addPosition(new Position(SYM, -5, 30647.50));
        Map<String, Double> tick = Map.of(SYM, 0.50);
        account.updateUnrealizedPnL(Map.of(SYM, 30637.50), tick);
        assertThat(account.getUnrealizedPnL()).isEqualTo(100.0);
        assertThat(account.getNetDailyPnl()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("ENGINE bracket, broker FLAT without a fill callback -> position removed, P&L NOT invented (ERROR), strategy latch released")
    void engineBracketBrokerFlatReleasesPosition() {
        broker.position = new BrokerPosition(SYM, CONTRACT, false, 5, 30647.50);
        account.addPosition(new Position(SYM, -5, 30647.50));
        manager.createBracketWithPartials(SYM, "3582761618", 30647.50, 5, OrderSide.SELL,
                30664.25, 30581.25, TradeTier.TIER_3, 0.25);
        assertThat(manager.getBracket(SYM)).isNotNull();
        assertThat(manager.getBracket(SYM).adopted).isFalse();

        double realizedBefore = account.getRealizedPnL();
        broker.ownerClosesEverything(); // the stop fill callback never arrives
        manager.reconcileWithBroker();
        String err = captureErr(manager::reconcileWithBroker);

        assertThat(manager.getBracket(SYM)).isNull();
        assertThat(account.getPosition(SYM)).isNull();
        assertThat(account.getTotalContracts()).isZero();
        assertThat(account.getRealizedPnL()).as("exit price unknown: nothing booked").isEqualTo(realizedBefore);
        assertThat(account.getTradesToday()).isZero();
        assertThat(lifecycleCloses).isEmpty();
        assertThat(closedEvents).singleElement().satisfies(e -> {
            assertThat(e.getSymbol()).isEqualTo(SYM);
            assertThat(e.getPnl()).isZero();
        });
        assertThat(err).contains("[LIVE] ERROR engine position released: MNQ SHORT 5 @ 30647.5 (broker flat, no fill callback)")
                .contains("could NOT be booked");
    }

    @Test
    @DisplayName("plain cancel (flatten / shutdown) does NOT touch the position")
    void plainCancelKeepsPosition() {
        broker.position = new BrokerPosition(SYM, CONTRACT, false, 5, 30647.50);
        account.addPosition(new Position(SYM, -5, 30647.50));
        manager.createBracketWithPartials(SYM, "3582761618", 30647.50, 5, OrderSide.SELL,
                30664.25, 30581.25, TradeTier.TIER_3, 0.25);
        manager.cancelBracket(SYM, "Engine shutdown");
        assertThat(account.getPosition(SYM)).isNotNull();
        assertThat(account.getTotalContracts()).isEqualTo(5);
    }

    @Test
    @DisplayName("REGRESSION: stop-loss fill closes the position and books its P&L")
    void stopLossFillStillCloses() {
        broker.position = new BrokerPosition(SYM, CONTRACT, false, 5, 30647.50);
        account.addPosition(new Position(SYM, -5, 30647.50));
        manager.createBracketWithPartials(SYM, "3582761618", 30647.50, 5, OrderSide.SELL,
                30664.25, 30581.25, TradeTier.TIER_3, 0.25);
        BracketOrderManager.BracketOrder b = manager.getBracket(SYM);
        String stopId = b.stopOrderId;
        broker.open.remove(stopId);
        broker.position = null;
        broker.listeners.get(stopId).onOrderUpdate(stopId,
                com.topstep.trading.domain.OrderStatus.FILLED, 30664.25, 5);

        assertThat(account.getPosition(SYM)).isNull();
        assertThat(account.getTotalContracts()).isZero();
        // SHORT 5 stopped 16.75 pts higher: 67 ticks x 5 x $0.50 = -$167.50
        assertThat(account.getRealizedPnL()).isEqualTo(-167.50);
        assertThat(account.getTradesToday()).isEqualTo(1);
        assertThat(exec.getOrderLevels(SYM)).isNull();
    }

    @Test
    @DisplayName("REGRESSION: full take-profit closes the position (legacy single-TP remainder booked)")
    void fullTakeProfitStillCloses() {
        account.addPosition(new Position(SYM, 5, 21000.0));
        BracketOrderManager.BracketOrder b = new BracketOrderManager.BracketOrder(
                SYM, "E1", 21000.0, 5, OrderSide.BUY, TradeTier.TIER_1);
        b.stopPrice = 20990.0;
        b.originalStopPrice = 20990.0;
        listener.onTakeProfitFilled(b, 21010.0);

        assertThat(account.getPosition(SYM)).isNull();
        assertThat(account.getTotalContracts()).isZero();
        // 10 pts = 40 ticks x 5 x $0.50 = $100
        assertThat(account.getRealizedPnL()).isEqualTo(100.0);
        assertThat(account.getTradesToday()).isEqualTo(1);
        assertThat(closedEvents).singleElement().satisfies(e -> assertThat(e.isWin()).isTrue());
    }

    @Test
    @DisplayName("REGRESSION: multi-level TPs — partials booked, final TP closes without double counting")
    void multiLevelTakeProfitStillCloses() {
        account.addPosition(new Position(SYM, 4, 21000.0));
        BracketOrderManager.BracketOrder b = new BracketOrderManager.BracketOrder(
                SYM, "E1", 21000.0, 4, OrderSide.BUY, TradeTier.TIER_1);
        BracketOrderManager.TakeProfitLevel l1 = new BracketOrderManager.TakeProfitLevel(1.0, 0.5, 21010.0, 2);
        BracketOrderManager.TakeProfitLevel l2 = new BracketOrderManager.TakeProfitLevel(2.0, 0.5, 21020.0, 2);
        b.takeProfitLevels.add(l1);
        b.takeProfitLevels.add(l2);
        l1.filled = true;
        b.remainingQuantity = 2;
        listener.onPartialTakeProfitFilled(b, l1, 21010.0);
        assertThat(account.getPosition(SYM).getQuantity()).isEqualTo(2);
        l2.filled = true;
        b.remainingQuantity = 0;
        listener.onPartialTakeProfitFilled(b, l2, 21020.0);
        listener.onTakeProfitFilled(b, 21020.0);

        assertThat(account.getPosition(SYM)).isNull();
        // 2 x 40 ticks x $0.50 + 2 x 80 ticks x $0.50 = 40 + 80
        assertThat(account.getRealizedPnL()).isEqualTo(120.0);
        assertThat(account.getTradesToday()).isEqualTo(1);
    }

    @Test
    @DisplayName("live close clears the trade's levels only when no entry is resting")
    void levelsKeptWhileEntryResting() {
        Order entry = Order.builder().symbol(SYM).side(OrderSide.BUY).type(OrderType.LIMIT)
                .quantity(5).limitPrice(21000.0).build();
        exec.submitOrderEnhanced(entry, 20990.0, 21030.0, TradeTier.TIER_2, new double[0][]);
        exec.clearOrderLevelsIfIdle(SYM);
        assertThat(exec.getOrderLevels(SYM)).isNotNull();
        exec.setAdoptedOrderLevels(SYM, ADOPT_ENTRY, ADOPT_STOP, OrderSide.SELL, 45);
        assertThat(exec.getOrderLevels(SYM).getCurrentStopPrice()).isEqualTo(20990.0);
    }

    @SuppressWarnings("unused")
    private static Candle candle(Instant ts, double o, double h, double l, double c) {
        return new Candle(SYM, ts, o, h, l, c, 100);
    }
}
