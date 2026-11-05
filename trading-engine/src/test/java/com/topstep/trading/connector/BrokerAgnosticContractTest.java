package com.topstep.trading.connector;

import com.topstep.trading.domain.OrderSide;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V6 Agent 05.1: the connector-agnostic halves of the legacy TopstepX tests
 * (CancelOrderTruthTest.brokerSnapshotParsing / cancelNotWorking,
 * ConnectorFailLoudTest.cancelWithoutAnIdFailsLoudly), retargeted at the
 * {@link BracketBroker} value types and {@link MockConnector}. The HTTP /
 * ProjectX wire halves stay in trading-engine/legacy (not compiled).
 */
class BrokerAgnosticContractTest {

    private static BracketBroker.BrokerOrder stop(String id, OrderSide side, int size, double px) {
        return new BracketBroker.BrokerOrder(id, "MNQ", "C1", BracketBroker.ORDER_TYPE_STOP, side, size, px, Double.NaN);
    }

    private static BracketBroker.BrokerOrder limit(String id, OrderSide side, int size, double px) {
        return new BracketBroker.BrokerOrder(id, "MNQ", "C1", BracketBroker.ORDER_TYPE_LIMIT, side, size, Double.NaN, px);
    }

    @Test
    @DisplayName("snapshot: position lookup is per symbol, stopsFor filters type AND exit side")
    void snapshotViews() {
        BracketBroker.BrokerSnapshot snap = new BracketBroker.BrokerSnapshot(
                List.of(new BracketBroker.BrokerPosition("MNQ", "C1", false, 3, 30647.5)),
                List.of(stop("3582792632", OrderSide.BUY, 3, 30647.5),
                        limit("3582761731", OrderSide.BUY, 2, 30614.0),
                        stop("99", OrderSide.SELL, 1, 30000.0),
                        new BracketBroker.BrokerOrder("7", "MES", "C2", BracketBroker.ORDER_TYPE_STOP,
                                OrderSide.BUY, 1, 6000.0, Double.NaN)));
        BracketBroker.BrokerPosition pos = snap.position("mnq");
        assertThat(pos).isNotNull();
        assertThat(pos.isLong).isFalse();
        assertThat(pos.size).isEqualTo(3);
        assertThat(snap.position("MGC")).isNull();
        assertThat(snap.stopsFor("MNQ", OrderSide.BUY)).singleElement().satisfies(s -> {
            assertThat(s.orderId).isEqualTo("3582792632");
            assertThat(s.stopPrice).isEqualTo(30647.5);
            assertThat(s.isStop()).isTrue();
        });
        assertThat(snap.ordersFor("MNQ")).hasSize(3);
        assertThat(snap.ordersFor("MES")).hasSize(1);
    }

    @Test
    @DisplayName("snapshot lists are immutable copies (a later broker query cannot mutate an old view)")
    void snapshotIsACopy() {
        java.util.ArrayList<BracketBroker.BrokerOrder> orders = new java.util.ArrayList<>();
        orders.add(stop("1", OrderSide.SELL, 1, 1.0));
        BracketBroker.BrokerSnapshot snap = new BracketBroker.BrokerSnapshot(List.of(), orders);
        orders.clear();
        assertThat(snap.openOrders).hasSize(1);
        assertThatThrownBy(() -> snap.openOrders.add(stop("2", OrderSide.SELL, 1, 1.0)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("OrderNotWorkingException carries the id + code and says GONE, not working")
    void notWorkingException() {
        BracketBroker.OrderNotWorkingException e = new BracketBroker.OrderNotWorkingException("42", 5, "null");
        assertThat(e.getOrderId()).isEqualTo("42");
        assertThat(e.getCode()).isEqualTo(5);
        assertThat(e.getMessage()).contains("not open at the broker").contains("already cancelled or filled")
                .doesNotContain("null");
        assertThat(e).isInstanceOf(java.io.IOException.class);
    }

    @Test
    @DisplayName("MockConnector: a cancel without an id fails loudly")
    void cancelWithoutAnIdFailsLoudly() {
        MockConnector mc = new MockConnector(50_000.0);
        assertThatThrownBy(() -> mc.cancelOrder("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mc.cancelOrder(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
