package com.topstep.trading.connector;

import com.topstep.trading.domain.Candle;
import com.topstep.trading.domain.Order;
import com.topstep.trading.domain.OrderSide;
import com.topstep.trading.domain.OrderStatus;
import com.topstep.trading.domain.OrderType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AGENT-05 (V5 RC-17): connector-level "never silent" guarantees, proven
 * without network access (LIVE is ⛔: TopstepX apiKey rejected, errorCode 3).
 */
class ConnectorFailLoudTest {

    private static final Instant T0 = Instant.parse("2026-09-28T14:00:00Z");

    private static Candle bar(int minute) {
        return new Candle("MNQ", T0.plusSeconds(60L * minute), 1, 2, 0.5, 1.5, 10);
    }

    @Test
    void poisonBarIsSkippedAndTheWatermarkStillAdvances() {
        List<Candle> bars = List.of(bar(1), bar(2), bar(3));
        List<Instant> delivered = new ArrayList<>();
        TopstepConnector.DeliveryResult r = TopstepConnector.deliverNewBars("MNQ", bars, T0, c -> {
            if (c.getTimestamp().equals(T0.plusSeconds(120))) {
                throw new IllegalStateException("poison");
            }
            delivered.add(c.getTimestamp());
        });
        assertThat(delivered).containsExactly(T0.plusSeconds(60), T0.plusSeconds(180));
        assertThat(r.failed).isEqualTo(1);
        assertThat(r.delivered).isEqualTo(2);
        // Watermark past the bad bar -> the next poll does NOT re-deliver it.
        assertThat(r.watermark).isEqualTo(T0.plusSeconds(180));
        TopstepConnector.DeliveryResult again = TopstepConnector.deliverNewBars("MNQ", bars, r.watermark, c -> {
            throw new AssertionError("re-delivered " + c.getTimestamp());
        });
        assertThat(again.delivered).isZero();
    }

    @Test
    void throwingFillListenerIsRetriedNotLost() {
        TopstepConnector tc = new TopstepConnector("http://localhost:1", "u", "k", "acct");
        AtomicInteger calls = new AtomicInteger();
        OrderListener flaky = (id, status, price, qty) -> {
            if (calls.incrementAndGet() < 2) throw new IllegalStateException("handler hiccup");
        };
        TopstepConnector.PendingOrder p = new TopstepConnector.PendingOrder(
                "42", "MNQ", 3, OrderSide.BUY, 20000.0, flaky);
        // 1st poll: listener throws -> order stays pending (NOT removed).
        assertThat(tc.deliverTerminalUpdate("42", p, OrderStatus.FILLED, 20000.0, 3)).isFalse();
        // 2nd poll: delivered -> removable.
        assertThat(tc.deliverTerminalUpdate("42", p, OrderStatus.FILLED, 20000.0, 3)).isTrue();
        assertThat(calls.get()).isEqualTo(2);
        assertThat(tc.getFillListenerFailures()).isEqualTo(1);

        // A permanently broken listener is abandoned after N attempts, loudly.
        OrderListener broken = (id, status, price, qty) -> { throw new IllegalStateException("broken"); };
        TopstepConnector.PendingOrder q = new TopstepConnector.PendingOrder(
                "43", "MNQ", 1, OrderSide.SELL, 20000.0, broken);
        for (int i = 1; i < TopstepConnector.FILL_CALLBACK_ATTEMPTS; i++) {
            assertThat(tc.deliverTerminalUpdate("43", q, OrderStatus.FILLED, 20000.0, 1)).isFalse();
        }
        assertThat(tc.deliverTerminalUpdate("43", q, OrderStatus.FILLED, 20000.0, 1)).isTrue();
    }

    @Test
    void cancelWithoutAnIdFailsLoudly() {
        TopstepConnector tc = new TopstepConnector("http://localhost:1", "u", "k", "acct");
        assertThatThrownBy(() -> tc.cancelOrder("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mockConnectorFillReachesALambdaListener() {
        // OrderListener.onOrderFilled used to be a no-op default: every
        // MockConnector-driven fill vanished for lambda listeners.
        List<String> seen = new ArrayList<>();
        OrderListener lambda = (id, status, price, qty) -> seen.add(status + "@" + price + "x" + qty);
        Order o = Order.builder().symbol("MNQ").side(OrderSide.BUY).type(OrderType.LIMIT)
                .quantity(2).limitPrice(20000.0).build();
        lambda.onOrderFilled(o, 2, 20000.0);
        lambda.onOrderRejected(o, "nope");
        assertThat(seen).containsExactly("FILLED@20000.0x2", "REJECTED@nullx0");
    }
}
