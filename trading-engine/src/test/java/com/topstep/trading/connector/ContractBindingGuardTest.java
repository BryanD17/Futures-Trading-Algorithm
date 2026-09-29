package com.topstep.trading.connector;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.topstep.trading.domain.Order;
import com.topstep.trading.domain.OrderSide;
import com.topstep.trading.domain.OrderType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AGENT-05.12: contract resolution + the entry guard, against a local fake
 * TopstepX (no network).
 *
 * <p>LIVE 2026-09-29 (Main 78699f1): every symbol logged
 * {@code Generated fallback contract ID ...} because the empty-searchText
 * discovery page is truncated before MNQ / MES / MGC. MGC was guessed as
 * CON.F.US.MGC.V26 while the broker's active contract was CON.F.US.MGC.Z26.
 */
@DisplayName("AGENT-05.12 contract binding: targeted search, guess flag, entry guard")
class ContractBindingGuardTest {

    /** The truncated page the gateway returns for searchText="" (sorted by root, ends at M6E). */
    private static final String TRUNCATED_DISCOVERY = "{\"contracts\":["
        + "{\"id\":\"CON.F.US.BP6.Z26\",\"name\":\"6BZ6\",\"activeContract\":true},"
        + "{\"id\":\"CON.F.US.GCE.Z26\",\"name\":\"GCZ6\",\"activeContract\":true},"
        + "{\"id\":\"CON.F.US.M6E.Z26\",\"name\":\"M6EZ6\",\"activeContract\":true}"
        + "],\"success\":true,\"errorCode\":0}";

    private static final String MGC_LIVE_RESPONSE = "{\"contracts\":[{\"id\":\"CON.F.US.MGC.Z26\",\"name\":\"MGCZ6\","
        + "\"activeContract\":true,\"description\":\"Micro Gold: December 2026\"}],\"success\":true,\"errorCode\":0}";

    private FakeTopstepX fake;
    private TopstepConnector connector;
    private ListAppender<ILoggingEvent> logs;
    private Logger connectorLogger;

    @BeforeEach
    void start() throws Exception {
        fake = new FakeTopstepX();
        fake.contractSearch.put("", TRUNCATED_DISCOVERY);
        fake.responses.put("/api/Order/place", "{\"orderId\":3600000001,\"success\":true,\"errorCode\":0}");
        fake.responses.put("/api/Order/cancel", "{\"success\":true,\"errorCode\":0}");
        connector = fake.connector();
        connector.sleeper = ms -> { };
        connectorLogger = (Logger) LoggerFactory.getLogger(TopstepConnector.class);
        logs = new ListAppender<>();
        logs.start();
        connectorLogger.addAppender(logs);
    }

    @AfterEach
    void stop() {
        connectorLogger.detachAppender(logs);
        connector.disconnect();
        fake.close();
    }

    private boolean logged(Level level, String prefix) {
        return logs.list.stream().anyMatch(e -> e.getLevel() == level && e.getFormattedMessage().startsWith(prefix));
    }

    private static Order entry(String symbol) {
        return new Order(symbol, OrderSide.BUY, OrderType.MARKET, 5, 0.0);
    }

    private static final OrderListener NOOP = (id, status, price, qty) -> { };

    // ── resolution ──────────────────────────────────────────────────────

    @Test
    @DisplayName("MGC resolves to the broker's active Z26 by targeted search, not the calendar guess")
    void mgcResolvesToZ26() throws Exception {
        fake.contractSearch.put("MGC", MGC_LIVE_RESPONSE);

        String id = connector.searchContract("MGC");

        assertThat(id).isEqualTo("CON.F.US.MGC.Z26");
        TopstepConnector.ContractBinding b = connector.getContractBinding("MGC");
        assertThat(b.source).isEqualTo(TopstepConnector.BindingSource.BROKER_SEARCH);
        assertThat(b.activeContract).isTrue();
        assertThat(connector.isContractGuessed("MGC")).isFalse();
        assertThat(logged(Level.INFO,
            "CONTRACT BINDING MGC -> CON.F.US.MGC.Z26 (source=BROKER_SEARCH activeContract=true)")).isTrue();
        assertThat(fake.callsTo("/api/Contract/search"))
            .anyMatch(c -> c.body().contains("\"searchText\":\"MGC\""));
    }

    @Test
    @DisplayName("MGC picks active Z26 when the response also holds GCE and a non-active MGC month")
    void mgcIgnoresGceAndNonActiveMonth() throws Exception {
        fake.contractSearch.put("MGC", "{\"contracts\":["
            + "{\"id\":\"CON.F.US.GCE.Z26\",\"name\":\"GCZ6\",\"activeContract\":true},"
            + "{\"id\":\"CON.F.US.MGC.V26\",\"name\":\"MGCV6\",\"activeContract\":false},"
            + "{\"id\":\"CON.F.US.MGC.Z26\",\"name\":\"MGCZ6\",\"activeContract\":true}]}");
        assertThat(connector.searchContract("MGC")).isEqualTo("CON.F.US.MGC.Z26");
    }

    @Test
    @DisplayName("MES never binds to M6E, even though the discovery page contains M6E")
    void mesNeverBindsToM6E() throws Exception {
        fake.contractSearch.put("MES", "{\"contracts\":[{\"id\":\"CON.F.US.M6E.Z26\",\"activeContract\":true}]}");

        String id = connector.searchContract("MES");

        assertThat(id).doesNotContain("M6E");
        assertThat(TopstepConnector.rootOf(id)).isEqualTo("MES");
        assertThat(connector.isContractGuessed("MES")).isTrue();
    }

    @Test
    @DisplayName("no broker match -> calendar guess, flagged as guessed and logged at WARN")
    void guessPathSetsFlagAndWarns() throws Exception {
        String id = connector.searchContract("MGC");

        assertThat(TopstepConnector.rootOf(id)).isEqualTo("MGC");
        assertThat(connector.isContractGuessed("MGC")).isTrue();
        assertThat(connector.getContractBinding("MGC").source)
            .isEqualTo(TopstepConnector.BindingSource.CALENDAR_GUESS);
        assertThat(logged(Level.WARN, "CONTRACT BINDING MGC -> " + id + " (source=CALENDAR_GUESS)")).isTrue();
        assertThat(logs.list.stream().anyMatch(e -> e.getLevel() == Level.WARN
            && e.getFormattedMessage().contains("GUESS"))).isTrue();
    }

    @Test
    @DisplayName("boot: subscribeMarketData logs one CONTRACT BINDING line with the broker binding")
    void subscribeLogsBindingLine() {
        fake.contractSearch.put("MGC", MGC_LIVE_RESPONSE);

        connector.subscribeMarketData("MGC", candle -> { });

        assertThat(logs.list.stream().filter(e -> e.getFormattedMessage().startsWith("CONTRACT BINDING MGC"))
            .map(ILoggingEvent::getFormattedMessage).toList())
            .containsExactly("CONTRACT BINDING MGC -> CON.F.US.MGC.Z26 (source=BROKER_SEARCH activeContract=true)");
        assertThat(fake.callsTo("/api/History/retrieveBars"))
            .allMatch(c -> c.body().contains("CON.F.US.MGC.Z26"));
    }

    // ── entry guard ─────────────────────────────────────────────────────

    @Test
    @DisplayName("entry on a guessed binding is REFUSED when broker re-resolution still fails; nothing is sent")
    void entryRefusedOnGuess() throws Exception {
        String guessed = connector.searchContract("MGC");
        long searchesBefore = fake.count("/api/Contract/search");

        assertThatThrownBy(() -> connector.submitEntryOrder(entry("MGC"), NOOP))
            .isInstanceOf(IOException.class)
            .hasMessage("ORDER REFUSED MGC: contract id " + guessed + " is a calendar guess, broker resolution failed");

        assertThat(fake.count("/api/Order/place")).isZero();
        assertThat(fake.count("/api/Contract/search")).isGreaterThan(searchesBefore); // re-attempted
        assertThat(logged(Level.ERROR, "ORDER REFUSED MGC: contract id " + guessed)).isTrue();
    }

    @Test
    @DisplayName("entry is allowed once re-resolution confirms the contract")
    void entryAllowedAfterReResolution() throws Exception {
        String guessed = connector.searchContract("MGC");
        fake.contractSearch.put("MGC", "{\"contracts\":[{\"id\":\"" + guessed + "\",\"activeContract\":true}]}");

        String orderId = connector.submitEntryOrder(entry("MGC"), NOOP);

        assertThat(orderId).isEqualTo("3600000001");
        assertThat(connector.isContractGuessed("MGC")).isFalse();
        assertThat(fake.callsTo("/api/Order/place")).singleElement()
            .satisfies(c -> assertThat(c.body()).contains(guessed));
    }

    @Test
    @DisplayName("broker names a DIFFERENT contract: this entry is refused, binding moves, the next entry goes to it")
    void differentContractRebindsAndRefusesThisEntry() throws Exception {
        String guessed = connector.searchContract("MGC");
        String broker = guessed.endsWith("Z26") ? "CON.F.US.MGC.G27" : "CON.F.US.MGC.Z26";
        fake.contractSearch.put("MGC", "{\"contracts\":[{\"id\":\"" + broker + "\",\"activeContract\":true}]}");

        assertThatThrownBy(() -> connector.submitEntryOrder(entry("MGC"), NOOP))
            .isInstanceOf(IOException.class)
            .hasMessageStartingWith("ORDER REFUSED MGC: contract id " + guessed + " is a calendar guess");
        assertThat(fake.count("/api/Order/place")).isZero();
        assertThat(connector.getContractBinding("MGC").contractId).isEqualTo(broker);

        connector.submitEntryOrder(entry("MGC"), NOOP);
        assertThat(fake.callsTo("/api/Order/place")).singleElement()
            .satisfies(c -> assertThat(c.body()).contains(broker));
    }

    @Test
    @DisplayName("a broker-resolved binding never triggers the guard")
    void brokerBindingEntryGoesStraightThrough() throws Exception {
        fake.contractSearch.put("MGC", MGC_LIVE_RESPONSE);
        connector.searchContract("MGC");
        long searches = fake.count("/api/Contract/search");

        connector.submitEntryOrder(entry("MGC"), NOOP);

        assertThat(fake.count("/api/Contract/search")).isEqualTo(searches);
        assertThat(fake.count("/api/Order/place")).isEqualTo(1);
    }

    @Test
    @DisplayName("protective / closing orders on a guessed binding are NEVER refused")
    void protectiveOrdersNeverRefused() throws Exception {
        String guessed = connector.searchContract("MGC");
        // The entry is refused (and binds MGC in symbolToContractId, as subscribe would).
        assertThatThrownBy(() -> connector.submitEntryOrder(entry("MGC"), NOOP)).isInstanceOf(IOException.class);
        assertThat(connector.isContractGuessed("MGC")).isTrue();

        // stop placement
        assertThatCode(() -> connector.submitStopOrder("MGC", OrderSide.SELL, 5, 2650.0, NOOP))
            .doesNotThrowAnyException();
        // stop replacement = place new + cancel old
        assertThatCode(() -> connector.submitStopOrder("MGC", OrderSide.SELL, 5, 2655.0, NOOP))
            .doesNotThrowAnyException();
        assertThatCode(() -> connector.cancelOrder("3600000001")).doesNotThrowAnyException();
        // take profit
        assertThatCode(() -> connector.submitTakeProfitOrder("MGC", OrderSide.SELL, 5, 2700.0, NOOP))
            .doesNotThrowAnyException();
        // flatten / close by market (BracketOrderManager + LiveEngineRunner use submitOrder)
        assertThatCode(() -> connector.submitOrder(
                new Order("MGC", OrderSide.SELL, OrderType.MARKET, 5, 0.0), NOOP))
            .doesNotThrowAnyException();

        assertThat(fake.callsTo("/api/Order/place")).hasSize(4)
            .allSatisfy(c -> assertThat(c.body()).contains(guessed));
        assertThat(logs.list.stream().filter(e -> e.getFormattedMessage().startsWith("ORDER REFUSED")).count())
            .isEqualTo(1); // only the entry above
    }

    @Test
    @DisplayName("the default TradingConnector.submitEntryOrder is a plain submitOrder")
    void defaultEntryIsPlainSubmit() throws Exception {
        java.util.List<Order> submitted = new java.util.ArrayList<>();
        TradingConnector plain = new TradingConnector() {
            @Override public void connect() { }
            @Override public void disconnect() { }
            @Override public boolean isConnected() { return true; }
            @Override public void subscribeMarketData(String symbol, MarketDataListener listener) { }
            @Override public void unsubscribeMarketData(String symbol) { }
            @Override public String submitOrder(Order order, OrderListener listener) { submitted.add(order); return "X-1"; }
            @Override public void cancelOrder(String orderId) { }
            @Override public double getAccountBalance() { return 0; }
            @Override public String getName() { return "plain"; }
        };
        assertThat(plain.submitEntryOrder(entry("MNQ"), NOOP)).isEqualTo("X-1");
        assertThat(submitted).hasSize(1);
    }
}
