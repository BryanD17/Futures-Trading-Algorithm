package com.topstep.trading.connector;

import com.sun.net.httpserver.HttpServer;
import com.topstep.trading.domain.Order;
import com.topstep.trading.domain.OrderSide;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AGENT-05.11 — ROOT CAUSE of the LIVE 2026-09-29 11:44:17 stop loss, proven
 * against a local fake TopstepX (JDK HttpServer, no network):
 * {@code cancelOrder} got {"success":true}, logged "Order cancelled
 * successfully", then built a placeholder Order with quantity(0) for the
 * listener — the Order builder threw "Order quantity must be positive, got:
 * 0", the exception escaped, and BracketOrderManager concluded the cancel had
 * FAILED ("old stop kept working") while the broker had cancelled the stop.
 */
@DisplayName("AGENT-05.11 TopstepConnector cancel / searchOpen truth")
class CancelOrderTruthTest {

    private HttpServer server;
    private final Map<String, String> responses = new ConcurrentHashMap<>();
    private TopstepConnector connector;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        responses.put("/api/Account/search",
                "{\"accounts\":[{\"id\":28042793,\"name\":\"PRAC-TEST\",\"canTrade\":true,\"simulated\":true}],\"success\":true}");
        server.createContext("/", ex -> {
            String body = responses.getOrDefault(ex.getRequestURI().getPath(), "{\"success\":false,\"errorCode\":99}");
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        server.start();
        connector = new TopstepConnector("http://127.0.0.1:" + server.getAddress().getPort(),
                "u", "k", "PRAC-TEST", false);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    @DisplayName("REPRO: a broker-confirmed cancel with a registered listener no longer throws 'quantity 0'")
    void confirmedCancelWithListenerDoesNotThrow() {
        responses.put("/api/Order/cancel", "{\"success\":true,\"errorCode\":0,\"errorMessage\":null}");
        AtomicReference<Order> cancelled = new AtomicReference<>();
        connector.trackExistingOrder("3582761719", "MNQ", 5, OrderSide.BUY, 30664.25, new OrderListener() {
            @Override public void onOrderUpdate(String id, com.topstep.trading.domain.OrderStatus s, Double p, Integer q) {}
            @Override public void onOrderCanceled(Order order) { cancelled.set(order); }
        });
        assertThatCode(() -> connector.cancelOrder("3582761719")).doesNotThrowAnyException();
        assertThat(cancelled.get()).isNotNull();
        assertThat(cancelled.get().getQuantity()).isEqualTo(5);
        assertThat(cancelled.get().getSymbol()).isEqualTo("MNQ");
    }

    @Test
    @DisplayName("a throwing cancel listener cannot turn a confirmed cancel into a failure")
    void throwingListenerIsContained() {
        responses.put("/api/Order/cancel", "{\"success\":true,\"errorCode\":0}");
        connector.trackExistingOrder("42", "MNQ", 3, OrderSide.BUY, 1.0, new OrderListener() {
            @Override public void onOrderUpdate(String id, com.topstep.trading.domain.OrderStatus s, Double p, Integer q) {}
            @Override public void onOrderCanceled(Order order) { throw new IllegalStateException("boom"); }
        });
        assertThatCode(() -> connector.cancelOrder("42")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("cancel-reject code 5 -> OrderNotWorkingException (already gone), not a generic failure")
    void code5IsOrderNotWorking() {
        responses.put("/api/Order/cancel", "{\"success\":false,\"errorCode\":5,\"errorMessage\":null}");
        assertThatThrownBy(() -> connector.cancelOrder("3582761719"))
                .isInstanceOf(TopstepConnector.OrderNotWorkingException.class)
                .hasMessageContaining("not open at TopstepX")
                .hasMessageContaining("already cancelled or filled");
    }

    @Test
    @DisplayName("other cancel rejects stay generic failures")
    void otherCodesStayFailures() {
        responses.put("/api/Order/cancel", "{\"success\":false,\"errorCode\":3,\"errorMessage\":\"Rejected\"}");
        assertThatThrownBy(() -> connector.cancelOrder("7"))
                .isNotInstanceOf(TopstepConnector.OrderNotWorkingException.class)
                .hasMessageContaining("code: 3");
    }

    @Test
    @DisplayName("Order/searchOpen + Position/searchOpen are parsed into the broker's view")
    void brokerSnapshotParsing() throws Exception {
        responses.put("/api/Order/searchOpen", "{\"orders\":["
                + "{\"id\":3582792632,\"accountId\":28042793,\"contractId\":\"CON.F.US.MNQ.Z26\",\"status\":1,\"type\":4,\"side\":0,\"size\":3,\"stopPrice\":30647.5},"
                + "{\"id\":3582761731,\"accountId\":28042793,\"contractId\":\"CON.F.US.MNQ.Z26\",\"status\":1,\"type\":1,\"side\":0,\"size\":2,\"limitPrice\":30614.0},"
                + "{\"id\":1,\"contractId\":\"CON.F.US.MNQ.Z26\",\"status\":3,\"type\":4,\"side\":0,\"size\":5,\"stopPrice\":30664.25}"
                + "],\"success\":true,\"errorCode\":0}");
        responses.put("/api/Position/searchOpen", "{\"positions\":["
                + "{\"id\":9,\"accountId\":28042793,\"contractId\":\"CON.F.US.MNQ.Z26\",\"type\":2,\"size\":3,\"averagePrice\":30647.5}"
                + "],\"success\":true,\"errorCode\":0}");
        TopstepConnector.BrokerSnapshot snap = connector.fetchBrokerSnapshot();
        TopstepConnector.BrokerPosition pos = snap.position("MNQ");
        assertThat(pos).isNotNull();
        assertThat(pos.isLong).isFalse();
        assertThat(pos.size).isEqualTo(3);
        List<TopstepConnector.BrokerOrder> stops = snap.stopsFor("MNQ", OrderSide.BUY);
        assertThat(stops).singleElement().satisfies(s -> {
            assertThat(s.orderId).isEqualTo("3582792632");
            assertThat(s.size).isEqualTo(3);
            assertThat(s.stopPrice).isEqualTo(30647.5);
        });
        assertThat(snap.ordersFor("MNQ")).hasSize(2); // the cancelled (status 3) order is not working
    }

    @Test
    @DisplayName("contract id -> engine symbol")
    void contractSymbolMapping() {
        assertThat(TopstepConnector.symbolFromContractId("CON.F.US.MNQ.Z26")).isEqualTo("MNQ");
        assertThat(TopstepConnector.symbolFromContractId("CON.F.US.MES.Z26")).isEqualTo("MES");
        assertThat(TopstepConnector.symbolFromContractId("CON.F.US.MGC.Z26")).isEqualTo("MGC");
        assertThat(TopstepConnector.symbolFromContractId("CON.F.US.EP.Z26")).isEqualTo("ES");
    }
}
