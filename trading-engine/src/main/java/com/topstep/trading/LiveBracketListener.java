package com.topstep.trading;

import com.topstep.trading.domain.AccountState;
import com.topstep.trading.domain.OrderSide;
import com.topstep.trading.domain.Position;
import com.topstep.trading.event.EventBus;
import com.topstep.trading.execution.BracketOrderManager;
import com.topstep.trading.execution.ExecutionEngine;

import java.util.function.ObjDoubleConsumer;

/**
 * LIVE bracket-event funnel: books realized P&amp;L, journals the trade and
 * keeps {@link AccountState} positions in step with the broker for every
 * {@link BracketOrderManager} callback.
 *
 * <p>AGENT-05.13: extracted verbatim from the anonymous listener inside
 * {@link LiveEngineRunner} (so it can be unit tested) and extended with the
 * broker-flat release: LIVE 2026-09-30 01:07 PT an ADOPTED manual SHORT 45 MNQ
 * was closed by the owner, reconciliation dropped its bracket as BROKER_FLAT,
 * but nothing removed the position from AccountState. The phantom 45 lots
 * stayed for two hours (total-contracts gate would have denied every entry,
 * mark-to-market showed +$7,650 of P&amp;L the engine never made).
 */
class LiveBracketListener implements BracketOrderManager.BracketListener {

    private final AccountState accountState;
    private final ExecutionEngine executionEngine;
    private final EventBus eventBus;
    /** LiveEngineRunner.notifyPositionClosed (multi-instrument engine + lifecycle). */
    private final ObjDoubleConsumer<String> positionClosedNotifier;

    LiveBracketListener(AccountState accountState, ExecutionEngine executionEngine, EventBus eventBus,
                        ObjDoubleConsumer<String> positionClosedNotifier) {
        this.accountState = accountState;
        this.executionEngine = executionEngine;
        this.eventBus = eventBus;
        this.positionClosedNotifier = positionClosedNotifier;
    }

    @Override
    public void onStopLossFilled(BracketOrderManager.BracketOrder bracket, double fillPrice) {
        // Calculate PnL based on remaining quantity (after partials)
        int qty = bracket.remainingQuantity > 0 ? bracket.remainingQuantity : bracket.totalQuantity;
        double pnl = LiveEngineRunner.calculatePnl(bracket.symbol, bracket.entryPrice, fillPrice,
                                 qty, bracket.entrySide);
        System.out.println("  Stop PnL: $" + String.format("%.2f", pnl) + " (" + qty + " contracts)");
        positionClosedNotifier.accept(bracket.symbol, pnl);
        recordLiveTrade(bracket, fillPrice, qty, pnl, "Stop loss filled");
        // Book realized P&L so the DLL guard (getNetDailyPnl) sees
        // live losses — live closes bypass ExecutionEngine.closePosition,
        // which is where SIM/backtest book it.
        accountState.recordRealizedPnL(pnl);
        // Clear position from account state
        accountState.closePosition(bracket.symbol);
        // AGENT-05.13: the trade's levels must not outlive it (stale stop on the next position).
        executionEngine.clearOrderLevelsIfIdle(bracket.symbol);
        // Count the completed trade for the frequency gates
        // (live closes bypass ExecutionEngine.closePosition).
        accountState.recordTradeCompleted(pnl);
        // Same funnel: notify subscribers (scalp re-arm) of the close.
        eventBus.publish(new com.topstep.trading.event.PositionClosedEvent(
                bracket.symbol, pnl, pnl > 0, java.time.Instant.now()));
    }

    @Override
    public void onTakeProfitFilled(BracketOrderManager.BracketOrder bracket, double fillPrice) {
        // This is called when ALL take profits are filled (position fully closed)
        double pnl = LiveEngineRunner.calculatePnl(bracket.symbol, bracket.entryPrice, fillPrice,
                                 bracket.totalQuantity, bracket.entrySide);
        System.out.println("  Total PnL: $" + String.format("%.2f", pnl));
        positionClosedNotifier.accept(bracket.symbol, pnl);
        // Multi-level TPs already booked/recorded every level via
        // onPartialTakeProfitFilled (which also fires for the last
        // level); only the legacy single-TP path arrives here with
        // an unbooked remainder. Book just that portion or the DLL
        // guard would double-count partials.
        int unbookedQty = bracket.totalQuantity - bracket.getTotalFilledTpQuantity();
        if (unbookedQty > 0) {
            double unbookedPnl = LiveEngineRunner.calculatePnl(bracket.symbol, bracket.entryPrice, fillPrice,
                                              unbookedQty, bracket.entrySide);
            recordLiveTrade(bracket, fillPrice, unbookedQty, unbookedPnl, "Take profit filled");
            accountState.recordRealizedPnL(unbookedPnl);
        } else {
            // AGENT-05.3: every level was a partial — the position
            // is flat now: journal the ONE merged Trade.
            executionEngine.finalizeExternalTrade(bracket.symbol);
        }
        // Clear position from account state
        accountState.closePosition(bracket.symbol);
        // AGENT-05.13: the trade's levels must not outlive it (stale stop on the next position).
        executionEngine.clearOrderLevelsIfIdle(bracket.symbol);
        // Count the completed trade for the frequency gates.
        accountState.recordTradeCompleted(pnl);
        // Same funnel: notify subscribers (scalp re-arm) of the close.
        eventBus.publish(new com.topstep.trading.event.PositionClosedEvent(
                bracket.symbol, pnl, pnl > 0, java.time.Instant.now()));
    }

    @Override
    public void onPartialTakeProfitFilled(BracketOrderManager.BracketOrder bracket,
                                          BracketOrderManager.TakeProfitLevel level, double fillPrice) {
        // Partial take profit filled - position still open but reduced
        double partialPnl = LiveEngineRunner.calculatePnl(bracket.symbol, bracket.entryPrice, fillPrice,
                                         level.quantity, bracket.entrySide);
        System.out.println("  Partial PnL: $" + String.format("%.2f", partialPnl) +
                          " (" + level.quantity + " contracts at " + level.rMultiple + "R)");
        recordLiveTrade(bracket, fillPrice, level.quantity, partialPnl,
            "Partial take profit (" + level.rMultiple + "R)", true);
        // Update realized PnL but don't close position
        accountState.recordRealizedPnL(partialPnl);
        // Update position quantity
        if (accountState.hasPosition(bracket.symbol)) {
            Position pos = accountState.getPosition(bracket.symbol);
            if (bracket.entrySide == OrderSide.BUY) {
                pos.updateWithFill(-level.quantity, fillPrice);  // Reduce long
            } else {
                pos.updateWithFill(level.quantity, fillPrice);   // Reduce short
            }
        }
    }

    @Override
    public void onStopMovedToBreakeven(BracketOrderManager.BracketOrder bracket, double newStopPrice) {
        System.out.println("  [RISK FREE] Stop moved to breakeven: " + newStopPrice);
        // This is informational - position is now risk-free
    }

    @Override
    public void onBracketCanceled(BracketOrderManager.BracketOrder bracket, String reason) {
        // A plain cancel (flatten / shutdown) does NOT touch the position:
        // the flatten path closes it itself; shutdown keeps it tracked.
        System.out.println("[BRACKET] Bracket canceled for " + bracket.symbol + ": " + reason);
    }

    /**
     * AGENT-05.13: the broker is FLAT for the bracket's symbol (reconciliation,
     * two passes, no fill callback). Whatever AccountState holds for the
     * symbol is a phantom: remove it. The exit price is unknown on this path,
     * so no realized P&amp;L is invented:
     * <ul>
     *   <li>ADOPTED position (not opened by the engine): released, nothing
     *       booked, no trade counted, no PositionClosedEvent (no strategy owns it).</li>
     *   <li>ENGINE position (its fill callback was missed): released; realized
     *       P&amp;L NOT booked and the trade NOT counted (logged at ERROR);
     *       PositionClosedEvent(pnl 0) releases the strategy's position latch,
     *       exactly like the SIM "no position" release.</li>
     * </ul>
     */
    @Override
    public void onBrokerFlat(BracketOrderManager.BracketOrder bracket, String reason) {
        String sym = bracket.symbol;
        System.out.println("[BRACKET] Bracket canceled for " + sym + ": " + reason);
        Position pos = accountState.getPosition(sym);
        boolean adopted = pos != null ? pos.isAdopted() : bracket.adopted;
        accountState.closePosition(sym);
        executionEngine.clearOrderLevelsIfIdle(sym);
        if (adopted) {
            System.err.println("[LIVE] ADOPTED position released: " + sym + " (broker flat)");
            return;
        }
        String what = pos != null ? describe(pos) : "(no AccountState position)";
        System.err.println("[LIVE] ERROR engine position released: " + sym + " " + what
                + " (broker flat, no fill callback) — realized P&L for this trade could NOT be booked"
                + " (exit price unknown); tracked P&L unchanged, trade not counted");
        eventBus.publish(new com.topstep.trading.event.PositionClosedEvent(
                sym, 0.0, false, java.time.Instant.now()));
    }

    @Override
    public void onPositionAdopted(BracketOrderManager.BracketOrder bracket) {
        // AGENT-05.11: an untracked broker position was adopted
        // (restart). Register it so the entry gates see the
        // symbol as IN POSITION (no second entry) and its exit
        // books P&L through the normal bracket funnel.
        // AGENT-05.13: a NEW AccountState position is marked adopted (not the
        // engine's: no MTM gain in tracked P&L, no P&L on broker-flat release).
        if (!accountState.hasPosition(bracket.symbol)) {
            int signed = bracket.isLong() ? bracket.totalQuantity : -bracket.totalQuantity;
            accountState.addPosition(new Position(bracket.symbol, signed, bracket.entryPrice).markAdopted());
        }
        // AGENT-05.13: report the ADOPTED broker stop, never the previous trade's levels.
        executionEngine.setAdoptedOrderLevels(bracket.symbol, bracket.entryPrice, bracket.stopPrice,
                bracket.entrySide, bracket.totalQuantity);
        System.err.println("[LIVE] ADOPTED broker position registered: " + bracket.symbol + " "
                + (bracket.isLong() ? "LONG " : "SHORT ") + bracket.totalQuantity + " @ " + bracket.entryPrice
                + " (stop " + bracket.stopOrderId + " @ " + bracket.stopPrice + ")");
    }

    private static String describe(Position p) {
        return (p.isLong() ? "LONG " : p.isShort() ? "SHORT " : "FLAT ") + Math.abs(p.getQuantity())
                + " @ " + p.getAvgEntryPrice();
    }

    /**
     * Record a Trade for a live broker-side exit (bracket SL/TP/partial).
     * Live fills bypass ExecutionEngine.closePosition, so without this the
     * Trades tab / journal / metrics never see live trades. Journaling only:
     * AccountState P&L and frequency gates are updated by the callers.
     */
    private void recordLiveTrade(BracketOrderManager.BracketOrder bracket, double exitPrice,
                                 int quantity, double pnl, String reason) {
        recordLiveTrade(bracket, exitPrice, quantity, pnl, reason, false);
    }

    /**
     * AGENT-05.3: {@code partial=true} holds the leg until the position is
     * flat; the closing leg (stop / final TP) merges every held leg into ONE
     * journaled Trade (quantity sum, VWAP exit, P&amp;L sum, R on the initial risk).
     */
    private void recordLiveTrade(BracketOrderManager.BracketOrder bracket, double exitPrice,
                                 int quantity, double pnl, String reason, boolean partial) {
        try {
            double stopForRisk = bracket.originalStopPrice > 0 ? bracket.originalStopPrice : bracket.stopPrice;
            double riskAmount = Math.abs(LiveEngineRunner.calculatePnl(bracket.symbol, bracket.entryPrice,
                stopForRisk, quantity, bracket.entrySide));
            com.topstep.trading.domain.Trade leg = com.topstep.trading.domain.Trade.builder()
                .symbol(bracket.symbol)
                .side(bracket.entrySide)
                .quantity(quantity)
                .entryPrice(bracket.entryPrice)
                .exitPrice(exitPrice)
                .entryTime(bracket.createdAt)
                .exitTime(java.time.Instant.now())
                .realizedPnL(pnl)
                .riskAmount(riskAmount)
                .tier(bracket.tier)
                .notes(reason)
                .build();
            if (partial) {
                executionEngine.recordExternalPartial(leg);
            } else {
                executionEngine.recordExternalTrade(leg);
            }
        } catch (Exception e) {
            com.topstep.trading.event.EngineTelemetry.error("LiveEngineRunner.journal", e);
            System.err.println("Failed to record live trade for " + bracket.symbol + ": " + e.getMessage());
        }
    }
}
