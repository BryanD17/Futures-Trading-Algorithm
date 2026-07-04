package com.topstep.trading.execution;

import com.topstep.trading.connector.TopstepConnector;
import com.topstep.trading.domain.OrderSide;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SA3 tests for {@link BracketOrderManager#armPriceBreakevenTrigger} — the
 * scalp-mode +0.5R breakeven move riding on the existing price-trigger
 * mechanism ({@code checkPriceBreakevenTrigger} → {@code moveStopToBreakeven}).
 */
@DisplayName("BracketOrderManager.armPriceBreakevenTrigger (scalp +0.5R breakeven)")
class BracketScalpBreakevenTest {

    private static final String SYMBOL = "MNQ";
    private static final double TICK = 0.25;

    private TopstepConnector connector;
    private BracketOrderManager manager;

    @BeforeEach
    void setUp() throws Exception {
        connector = mock(TopstepConnector.class);
        when(connector.submitStopOrder(anyString(), any(), anyInt(), anyDouble(), any()))
                .thenReturn("SL-1", "SL-2");
        when(connector.submitTakeProfitOrder(anyString(), any(), anyInt(), anyDouble(), any()))
                .thenReturn("TP-1");
        manager = new BracketOrderManager(connector);
    }

    /** LONG 6 @ 21023, stop 21011, single TP 21035 — the scalp shape. */
    private void createScalpBracket() {
        manager.createBracket(SYMBOL, "ENTRY-1", 21023.0, 6, OrderSide.BUY,
                21011.0, 21035.0);
    }

    @Test
    @DisplayName("armed trigger fires at the +0.5R price and moves the stop to breakeven")
    void triggerFiresAtHalfR() throws Exception {
        createScalpBracket();
        // +0.5R = 21023 + 6 = 21029.
        manager.armPriceBreakevenTrigger(SYMBOL, 21029.0);

        // Below the trigger: nothing moves.
        manager.checkPriceBreakevenTrigger(SYMBOL, 21028.75, TICK);
        BracketOrderManager.BracketOrder bracket = manager.getBracket(SYMBOL);
        assertThat(bracket.movedToBreakeven).isFalse();
        assertThat(bracket.stopPrice).isEqualTo(21011.0);

        // At the trigger: stop moves to breakeven (entry + 2-tick buffer).
        manager.checkPriceBreakevenTrigger(SYMBOL, 21029.0, TICK);
        assertThat(bracket.movedToBreakeven).isTrue();
        assertThat(bracket.stopPrice).isEqualTo(21023.0 + 2 * TICK);
        verify(connector).cancelOrder(eq("SL-1"));
        verify(connector, times(2)).submitStopOrder(anyString(), any(), anyInt(),
                anyDouble(), any());
    }

    @Test
    @DisplayName("trigger fires only once — a second touch is a no-op")
    void triggerFiresOnlyOnce() throws Exception {
        createScalpBracket();
        manager.armPriceBreakevenTrigger(SYMBOL, 21029.0);
        manager.checkPriceBreakevenTrigger(SYMBOL, 21030.0, TICK);
        manager.checkPriceBreakevenTrigger(SYMBOL, 21031.0, TICK);
        verify(connector, times(2)).submitStopOrder(anyString(), any(), anyInt(),
                anyDouble(), any()); // original + one breakeven move only
    }

    @Test
    @DisplayName("arming without an active bracket is a safe no-op")
    void armWithoutBracketIsNoOp() {
        manager.armPriceBreakevenTrigger(SYMBOL, 21029.0);
        assertThat(manager.getBracket(SYMBOL)).isNull();
        // And check does nothing either.
        manager.checkPriceBreakevenTrigger(SYMBOL, 21030.0, TICK);
    }

    @Test
    @DisplayName("short bracket: trigger at entry - 0.5R moves stop down to breakeven")
    void shortTrigger() throws Exception {
        manager.createBracket(SYMBOL, "ENTRY-2", 21000.0, 6, OrderSide.SELL,
                21012.0, 20988.0);
        // +0.5R in the short's favor = 21000 - 6 = 20994.
        manager.armPriceBreakevenTrigger(SYMBOL, 20994.0);
        manager.checkPriceBreakevenTrigger(SYMBOL, 20994.0, TICK);

        BracketOrderManager.BracketOrder bracket = manager.getBracket(SYMBOL);
        assertThat(bracket.movedToBreakeven).isTrue();
        assertThat(bracket.stopPrice).isEqualTo(21000.0 - 2 * TICK);
    }
}
