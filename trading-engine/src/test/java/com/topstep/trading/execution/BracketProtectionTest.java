package com.topstep.trading.execution;

import com.topstep.trading.connector.OrderListener;
import com.topstep.trading.connector.TopstepConnector;
import com.topstep.trading.domain.Order;
import com.topstep.trading.domain.OrderSide;
import com.topstep.trading.domain.OrderStatus;
import com.topstep.trading.domain.OrderType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * AGENT-05 (V5 RC-17): the BracketOrderManager paths that used to leave a
 * LIVE position without a stop (initial stop failure :316/:384, TP failure
 * cancelling the stop :401-404, breakeven / qty update after the old stop
 * was cancelled :516/:550) now RETRY, then FLATTEN by market. Code + unit
 * tests only — LIVE order routing is ⛔ (TopstepX apiKey rejected, errorCode 3).
 */
class BracketProtectionTest {

    private TopstepConnector connector;
    private BracketOrderManager manager;
    private final AtomicReference<Double> protectiveExit = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        connector = mock(TopstepConnector.class);
        manager = new BracketOrderManager(connector);
        manager.setListener(new BracketOrderManager.BracketListener() {
            @Override public void onStopLossFilled(BracketOrderManager.BracketOrder b, double px) { protectiveExit.set(px); }
            @Override public void onTakeProfitFilled(BracketOrderManager.BracketOrder b, double px) {}
            @Override public void onPartialTakeProfitFilled(BracketOrderManager.BracketOrder b, BracketOrderManager.TakeProfitLevel l, double px) {}
            @Override public void onBracketCanceled(BracketOrderManager.BracketOrder b, String reason) {}
            @Override public void onStopMovedToBreakeven(BracketOrderManager.BracketOrder b, double px) {}
        });
    }

    @Test
    void initialStopFailureRetriesThenFlattensByMarket() throws Exception {
        when(connector.submitStopOrder(anyString(), any(), anyInt(), anyDouble(), any()))
                .thenThrow(new IOException("stop rejected"));
        ArgumentCaptor<Order> close = ArgumentCaptor.forClass(Order.class);
        ArgumentCaptor<OrderListener> l = ArgumentCaptor.forClass(OrderListener.class);
        when(connector.submitOrder(close.capture(), l.capture())).thenReturn("MKT-1");

        manager.createBracket("MNQ", "E-1", 20000.0, 3, OrderSide.BUY, 19990.0, 20020.0);

        verify(connector, times(BracketOrderManager.PROTECTIVE_RETRIES))
                .submitStopOrder(eq("MNQ"), eq(OrderSide.SELL), eq(3), eq(19990.0), any());
        assertThat(close.getValue().getType()).isEqualTo(OrderType.MARKET);
        assertThat(close.getValue().getSide()).isEqualTo(OrderSide.SELL);
        assertThat(close.getValue().getQuantity()).isEqualTo(3);
        assertThat(manager.hasBracket("MNQ")).isFalse();
        assertThat(manager.getUnprotectedFlattenCount()).isEqualTo(1);
        // The market fill closes the position through the stop-fill funnel.
        l.getValue().onOrderUpdate("MKT-1", OrderStatus.FILLED, 19998.0, 3);
        assertThat(protectiveExit.get()).isEqualTo(19998.0);
    }

    @Test
    void initialStopSucceedsOnRetry() throws Exception {
        when(connector.submitStopOrder(anyString(), any(), anyInt(), anyDouble(), any()))
                .thenThrow(new IOException("transient")).thenReturn("SL-2");
        when(connector.submitTakeProfitOrder(anyString(), any(), anyInt(), anyDouble(), any())).thenReturn("TP-1");
        manager.createBracket("MNQ", "E-1", 20000.0, 3, OrderSide.BUY, 19990.0, 20020.0);
        assertThat(manager.getBracket("MNQ").stopOrderId).isEqualTo("SL-2");
        verify(connector, never()).submitOrder(any(), any());
    }

    @Test
    void takeProfitFailureNoLongerCancelsTheStop() throws Exception {
        when(connector.submitStopOrder(anyString(), any(), anyInt(), anyDouble(), any())).thenReturn("SL-1");
        when(connector.submitTakeProfitOrder(anyString(), any(), anyInt(), anyDouble(), any()))
                .thenThrow(new IOException("tp rejected"));
        manager.createBracket("MNQ", "E-1", 20000.0, 3, OrderSide.BUY, 19990.0, 20020.0);
        verify(connector, never()).cancelOrder("SL-1");
        assertThat(manager.hasBracket("MNQ")).isTrue();
        assertThat(manager.getBracket("MNQ").stopOrderId).isEqualTo("SL-1");
    }

    @Test
    void breakevenMoveFailureReplacesOldStopOrFlattens() throws Exception {
        when(connector.submitTakeProfitOrder(anyString(), any(), anyInt(), anyDouble(), any())).thenReturn("TP-1");
        // 1st call: initial stop OK. Then the breakeven stop fails 3x, the
        // old-level re-place fails 3x -> flatten.
        when(connector.submitStopOrder(anyString(), any(), anyInt(), anyDouble(), any()))
                .thenReturn("SL-1")
                .thenThrow(new IOException("be fail"));
        when(connector.submitOrder(any(), any())).thenReturn("MKT-1");
        manager.createBracket("MNQ", "E-1", 20000.0, 3, OrderSide.BUY, 19990.0, 20020.0);
        manager.armPriceBreakevenTrigger("MNQ", 20005.0);
        manager.checkPriceBreakevenTrigger("MNQ", 20006.0, 0.25);

        verify(connector).cancelOrder("SL-1");
        verify(connector, times(1 + 2 * BracketOrderManager.PROTECTIVE_RETRIES))
                .submitStopOrder(anyString(), any(), anyInt(), anyDouble(), any());
        verify(connector).submitOrder(any(), any());
        assertThat(manager.hasBracket("MNQ")).isFalse();
        assertThat(manager.getUnprotectedFlattenCount()).isEqualTo(1);
    }

    @Test
    void breakevenMoveFailureFallsBackToTheOldLevel() throws Exception {
        when(connector.submitTakeProfitOrder(anyString(), any(), anyInt(), anyDouble(), any())).thenReturn("TP-1");
        when(connector.submitStopOrder(anyString(), any(), anyInt(), eq(19990.0), any()))
                .thenReturn("SL-1", "SL-OLD");
        when(connector.submitStopOrder(anyString(), any(), anyInt(), eq(20000.5), any()))
                .thenThrow(new IOException("be fail"));
        manager.createBracket("MNQ", "E-1", 20000.0, 3, OrderSide.BUY, 19990.0, 20020.0);
        manager.armPriceBreakevenTrigger("MNQ", 20005.0);
        manager.checkPriceBreakevenTrigger("MNQ", 20006.0, 0.25);
        BracketOrderManager.BracketOrder b = manager.getBracket("MNQ");
        assertThat(b).isNotNull();
        assertThat(b.stopOrderId).isEqualTo("SL-OLD");
        assertThat(b.stopPrice).isEqualTo(19990.0);
        assertThat(b.movedToBreakeven).isFalse();
        verify(connector, never()).submitOrder(any(), any());
    }
}
