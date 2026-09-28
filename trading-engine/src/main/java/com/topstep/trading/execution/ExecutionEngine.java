package com.topstep.trading.execution;

import com.topstep.trading.domain.*;
import com.topstep.trading.event.EventBus;
import com.topstep.trading.event.PositionClosedEvent;
import com.topstep.trading.event.SetupCancelledEvent;
import com.topstep.trading.strategy.TradeTier;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * ExecutionEngine handles order execution and position management.
 *
 * Enhanced features:
 * - Partial profit taking at R-multiples
 * - Trailing stops after hitting profit targets
 * - Dynamic R:R based on trade tier
 * - Position scaling
 *
 * In backtest mode:
 * - Simulates fills based on candle prices
 * - Updates positions and PnL
 * - Tracks completed trades
 *
 * In live/sim mode:
 * - Delegates to TradingConnector
 * - Reacts to real fill notifications
 */
public class ExecutionEngine {

    /**
     * Listener interface for execution events (fills, closes).
     * CRITICAL: Allows BacktestRunner/LiveRunner to track position state correctly.
     */
    public interface ExecutionListener {
        /** Called when an order is filled and position is opened. */
        void onPositionOpened(String symbol, OrderSide side, double entryPrice, int quantity);
        /** Called when a position is fully or partially closed. */
        void onPositionClosed(String symbol, double pnl, boolean isWin);
    }

    private final AccountState accountState;
    // CRITICAL: Changed to List<Order> to support multiple orders per symbol
    private final Map<String, List<Order>> activeOrders;
    private final Map<String, Double> tickValues;
    private final List<Trade> completedTrades;

    // Enhanced order management with partial profits and trailing stops
    private final Map<String, EnhancedOrderLevels> orderLevels;

    // Track last prices for live PnL calculations
    private final Map<String, Double> lastPrices;

    // Execution listener for external notifications
    private ExecutionListener executionListener;

    // Optional event bus: when set, a PositionClosedEvent is published from
    // closePosition (the same funnel that counts the trade via
    // AccountState.recordTradeCompleted). Null = no events (legacy behavior).
    private EventBus eventBus;

    // Per-symbol signal context for enriching Trade records with confluence details
    private final Map<String, List<String>> pendingConfluenceFactors = new ConcurrentHashMap<>();
    private final Map<String, TradeTier> pendingTiers = new ConcurrentHashMap<>();

    // Controls whether this engine simulates fills (true for backtest/sim, false for live)
    private boolean simulationEnabled = true;

    // ── AGENT-05 (V5 RC-17) ───────────────────────────────────────────
    /** SIM resting-order time-to-live in feed bars (order.ttlBars); 0 = off. */
    private int orderTtlBars = com.topstep.trading.risk.RiskConfig.orderTtlBars();
    /** Bars each resting order (by orderId) has waited. */
    private final Map<String, Integer> orderAgeBars = new ConcurrentHashMap<>();
    /** Recent candles per symbol, replayed for an order submitted AFTER the
     *  next candle already arrived (async EventBus race). */
    private final Map<String, java.util.Deque<Candle>> recentCandles = new ConcurrentHashMap<>();
    private static final int RECENT_CANDLES = 5;
    /** Execution-path 14:45 CT flatten / no-fill safety net (defence in depth). */
    private volatile boolean flattenSafetyNet = false;
    private final java.util.concurrent.atomic.AtomicLong ttlCancels = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong safetyNetFlattens = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong setupCancels = new java.util.concurrent.atomic.AtomicLong();

    // -- AGENT-05.3 (V5): ONE completed Trade per position --
    /**
     * Exits of one open position, aggregated into a single {@link Trade}
     * when the position goes flat — whether the last exit is the stop /
     * target ({@link #closePosition}) or a partial take-profit that
     * flattens it. Quantity = contracts exited, exit = VWAP of the exits,
     * P&amp;L = sum of the legs, R = P&amp;L / initial $ risk (entry→original
     * stop x tick value x filled quantity).
     */
    static final class PositionLedger {
        final OrderSide side;
        double entryPrice;
        final Instant entryTime;
        int filledQty;
        double initialRiskDollars;
        int exitedQty;
        double exitNotional;
        double pnl;
        final List<String> exitReasons = new ArrayList<>();

        PositionLedger(OrderSide side, double entryPrice, Instant entryTime) {
            this.side = side;
            this.entryPrice = entryPrice;
            this.entryTime = entryTime;
        }

        void addExit(int qty, double price, double legPnl, String reason) {
            exitedQty += qty;
            exitNotional += qty * price;
            pnl += legPnl;
            exitReasons.add(qty + "@" + String.format("%.2f", price) + " " + reason);
        }

        double vwapExit() { return exitedQty > 0 ? exitNotional / exitedQty : 0.0; }
    }

    /** Open-position ledgers by symbol (SIM fills; LIVE external legs). */
    private final Map<String, PositionLedger> ledgers = new ConcurrentHashMap<>();
    /** LIVE journaling: partial legs recorded before the position went flat. */
    private final Map<String, List<Trade>> externalLegs = new ConcurrentHashMap<>();

    public ExecutionEngine(AccountState accountState) {
        this.accountState = accountState;
        // CRITICAL: Use ConcurrentHashMap for thread-safe access from multiple threads
        this.activeOrders = new ConcurrentHashMap<>();
        this.completedTrades = new ArrayList<>();
        this.tickValues = new ConcurrentHashMap<>();
        this.orderLevels = new ConcurrentHashMap<>();
        this.lastPrices = new ConcurrentHashMap<>();

        // Set default tick values
        initializeTickValues();
    }

    /**
     * Initialize tick values for common futures symbols.
     */
    private void initializeTickValues() {
        // Index futures
        tickValues.put("ES", 12.50);   // E-mini S&P 500: $12.50 per tick (0.25 point)
        tickValues.put("NQ", 5.00);    // E-mini NASDAQ 100: $5.00 per tick (0.25 point)
        tickValues.put("MES", 1.25);   // Micro E-mini S&P 500: $1.25 per tick
        tickValues.put("MNQ", 0.50);   // Micro E-mini NASDAQ 100: $0.50 per tick
        tickValues.put("YM", 5.00);    // E-mini Dow: $5.00 per tick
        tickValues.put("RTY", 5.00);   // E-mini Russell 2000: $5.00 per tick

        // Metals
        tickValues.put("GC", 10.00);   // Gold: $10.00 per tick (100 oz × 0.10)
        tickValues.put("MGC", 1.00);   // Micro Gold: $1.00 per tick (10 oz × 0.10)
    }

    /**
     * Set the execution listener for fill/close notifications.
     * CRITICAL: Must be set before processing candles to receive all notifications.
     */
    public void setExecutionListener(ExecutionListener listener) {
        this.executionListener = listener;
    }

    /**
     * Set the event bus used to publish {@link PositionClosedEvent} from the
     * close funnel. Optional; when unset no events are published.
     */
    public void setEventBus(EventBus eventBus) {
        this.eventBus = eventBus;
        // V5 Agent 05.3: the setup that emitted an entry ended (invalidated,
        // expired, re-armed) — cancel its still-unfilled SIM entry. LIVE
        // (simulation disabled) cancels at the broker in LiveEngineRunner.
        if (eventBus != null) {
            eventBus.subscribe(SetupCancelledEvent.class, evt -> {
                if (simulationEnabled) {
                    cancelEntryForSetup(evt.getSymbol(), evt.getReason(), evt.getCandleTime());
                }
            });
        }
    }

    /**
     * V5 Agent 05.3 — cancel every still-unfilled resting ENTRY order for
     * {@code symbol} because the setup that emitted it ended. Publishes one
     * GateDecisionEvent {@code "ORDER: cancelled — setup <reason>"} per
     * order. No PositionClosedEvent: the strategy released its own latch
     * when it asked for the cancel. An already filled entry is a position
     * and is never touched here.
     *
     * @return the number of orders cancelled
     */
    public synchronized int cancelEntryForSetup(String symbol, String reason, Instant candleTime) {
        List<Order> orders = activeOrders.get(symbol);
        if (orders == null || orders.isEmpty()) return 0;
        int cancelled = 0;
        for (Order order : orders) {
            if (order.getFilledQuantity() > 0) continue; // partially filled: a position exists
            orders.remove(order);
            orderAgeBars.remove(order.getOrderId());
            order.updateStatus(OrderStatus.CANCELED);
            cancelled++;
            String why = "ORDER: cancelled — setup " + reason;
            System.out.println("[ExecutionEngine] " + why + " (" + symbol + " " + order.getSide()
                    + " " + order.getQuantity() + " @ " + order.getLimitPrice() + ")");
            if (eventBus != null) {
                com.topstep.trading.event.EngineTelemetry.publish(eventBus, new com.topstep.trading.event.GateDecisionEvent(
                        symbol, candleTime, null, "ORDER_RESTING", "ORDER", why,
                        order.getQuantity(), order.getLimitPrice() == null ? Double.NaN : order.getLimitPrice()));
            }
        }
        if (orders.isEmpty()) activeOrders.remove(symbol);
        if (cancelled > 0) {
            setupCancels.addAndGet(cancelled);
            if (!accountState.hasPosition(symbol)) {
                orderLevels.remove(symbol);
                pendingTiers.remove(symbol);
                pendingConfluenceFactors.remove(symbol);
            }
        }
        return cancelled;
    }

    /** Entry orders cancelled because their setup ended (Agent 05.3). */
    public long getSetupCancelCount() { return setupCancels.get(); }

    /** SIM order TTL in bars (order.ttlBars); {@code <= 0} disables it. */
    public void setOrderTtlBars(int bars) {
        this.orderTtlBars = bars;
    }

    public int getOrderTtlBars() { return orderTtlBars; }

    /**
     * Enable the execution-path 14:45 CT safety net: inside the Topstep
     * 14:45–17:00 CT block no resting entry may fill (it is cancelled) and
     * any open simulated position is flattened at the bar close. The
     * strategy/session gate is the primary guard; this is defence in depth.
     */
    public void setFlattenSafetyNet(boolean enabled) {
        this.flattenSafetyNet = enabled;
    }

    public long getTtlCancelCount() { return ttlCancels.get(); }
    public long getSafetyNetFlattenCount() { return safetyNetFlattens.get(); }

    /**
     * Enable/disable simulation behaviors (limit fills, stop/target checks, trailing) for live mode.
     */
    public void setSimulationEnabled(boolean simulationEnabled) {
        this.simulationEnabled = simulationEnabled;
    }

    /**
     * Record signal context (tier and confluence factors) for a symbol.
     * Called when a strategy signal is processed, so the data is available
     * when the trade is eventually closed and the Trade record is built.
     */
    public void recordSignalContext(String symbol, TradeTier tier, List<String> confluenceFactors) {
        if (tier != null) {
            pendingTiers.put(symbol, tier);
        }
        if (confluenceFactors != null && !confluenceFactors.isEmpty()) {
            pendingConfluenceFactors.put(symbol, confluenceFactors);
        }
    }

    /**
     * Accept a new approved order for execution.
     */
    public synchronized void submitOrder(Order order) {
        if (order == null) {
            throw new IllegalArgumentException("Order cannot be null");
        }

        order.updateStatus(OrderStatus.SUBMITTED);
        // CRITICAL: Support multiple orders per symbol by using a list.
        // AGENT-05 (D-15): CopyOnWriteArrayList — orders are added on the
        // EventBus worker thread and iterated on the market-data thread.
        activeOrders.computeIfAbsent(order.getSymbol(), k -> new CopyOnWriteArrayList<>()).add(order);
        orderAgeBars.put(order.getOrderId(), 0);
    }

    /**
     * AGENT-05 (V5 RC-17): submit with the CANDLE time of the signal that
     * produced the order. Candles for the symbol that the market-data thread
     * already processed AFTER that candle (the async EventBus let the next
     * bar arrive first) are replayed for fills / stops / targets, so a fill
     * is never lost to the race.
     */
    public synchronized void submitOrder(Order order, double stopPrice, double targetPrice,
                                         Instant signalCandleTime) {
        submitOrder(order, stopPrice, targetPrice);
        replayMissedCandles(order.getSymbol(), signalCandleTime);
    }

    /** Enhanced twin of {@link #submitOrder(Order, double, double, Instant)}. */
    public synchronized void submitOrderEnhanced(Order order, double stopPrice, double targetPrice,
                                                 TradeTier tier, double[][] partialProfitTargets,
                                                 Instant signalCandleTime) {
        submitOrderEnhanced(order, stopPrice, targetPrice, tier, partialProfitTargets);
        replayMissedCandles(order.getSymbol(), signalCandleTime);
    }

    private void replayMissedCandles(String symbol, Instant after) {
        if (!simulationEnabled || after == null) return;
        java.util.Deque<Candle> recent = recentCandles.get(symbol);
        if (recent == null) return;
        List<Candle> missed = new ArrayList<>();
        synchronized (recent) {
            for (Candle c : recent) {
                if (c.getTimestamp() != null && c.getTimestamp().isAfter(after)) missed.add(c);
            }
        }
        for (Candle c : missed) {
            System.out.println("[ExecutionEngine] replaying " + symbol + " candle " + c.getTimestamp()
                    + " for an order submitted after it arrived (fill race guard)");
            checkOrderFills(c);
            checkStopTargetHits(c);
        }
    }

    /**
     * Submit order with stop and target levels (original method for compatibility).
     */
    public synchronized void submitOrder(Order order, double stopPrice, double targetPrice) {
        submitOrder(order);

        EnhancedOrderLevels levels = new EnhancedOrderLevels(
            order.getLimitPrice(),
            stopPrice,
            targetPrice,
            order.getSide(),
            order.getQuantity(),
            TradeTier.TIER_2  // Default tier
        );
        orderLevels.put(order.getSymbol(), levels);
    }

    /**
     * Submit order with enhanced levels including tier and partial profit targets.
     */
    public synchronized void submitOrderEnhanced(Order order, double stopPrice, double targetPrice,
                                    TradeTier tier, double[][] partialProfitTargets) {
        submitOrder(order);

        EnhancedOrderLevels levels = new EnhancedOrderLevels(
            order.getLimitPrice(),
            stopPrice,
            targetPrice,
            order.getSide(),
            order.getQuantity(),
            tier,
            partialProfitTargets
        );
        orderLevels.put(order.getSymbol(), levels);
    }

    /**
     * Process a new candle - check for fills and update PnL.
     */
    public synchronized void onNewCandle(Candle candle) {
        // CRITICAL: Track last price for live PnL display
        lastPrices.put(candle.getSymbol(), candle.getClose());
        java.util.Deque<Candle> recent = recentCandles.computeIfAbsent(
                candle.getSymbol(), k -> new java.util.ArrayDeque<>());
        synchronized (recent) {
            recent.addLast(candle);
            while (recent.size() > RECENT_CANDLES) recent.removeFirst();
        }

        if (simulationEnabled) {
            // AGENT-05: 14:45–17:00 CT safety net (defence in depth).
            if (flattenSafetyNet && com.topstep.trading.risk.RiskConfig.inNoEntryBlock(candle.getTimestamp())) {
                applyFlattenSafetyNet(candle);
                updateUnrealizedPnl(candle);
                return;
            }

            // Check for entry fills
            checkOrderFills(candle);

            // AGENT-05: SIM order TTL — a resting entry never lives forever.
            expireStaleOrders(candle);

            // Check for partial profit targets
            checkPartialProfitTargets(candle);

            // Check for trailing stop updates
            updateTrailingStops(candle);

            // Check for stop/target hits on existing positions
            checkStopTargetHits(candle);
        }

        // Update unrealized PnL
        updateUnrealizedPnl(candle);
    }

    /**
     * Get the last known price for a symbol.
     * Used for live PnL calculations in the dashboard.
     */
    public double getLastPrice(String symbol) {
        return lastPrices.getOrDefault(symbol, 0.0);
    }

    /**
     * Check if any active orders should fill based on current candle.
     */
    private void checkOrderFills(Candle candle) {
        List<Order> orders = activeOrders.get(candle.getSymbol());
        if (orders == null || orders.isEmpty()) {
            return;
        }

        // AGENT-05: iterate a snapshot (CopyOnWriteArrayList) and remove by
        // identity — safe against concurrent submitOrder on another thread.
        for (Order order : orders) {
            if (!order.isActive()) {
                orders.remove(order);
                orderAgeBars.remove(order.getOrderId());
                continue;
            }

            boolean filled = false;
            double fillPrice = 0;

            if (order.getSide() == OrderSide.BUY) {
                // Buy limit fills when price drops to or below limit
                if (candle.getLow() <= order.getLimitPrice()) {
                    filled = true;
                    fillPrice = order.getLimitPrice();
                }
            } else { // SELL
                // Sell limit fills when price rises to or above limit
                if (candle.getHigh() >= order.getLimitPrice()) {
                    filled = true;
                    fillPrice = order.getLimitPrice();
                }
            }

            if (filled) {
                executeFill(order, fillPrice, candle.getTimestamp());
                orders.remove(order);
                orderAgeBars.remove(order.getOrderId());
            }
        }

        // Clean up empty lists
        if (orders.isEmpty()) {
            activeOrders.remove(candle.getSymbol());
        }
    }

    /**
     * Print status of all active orders (for debugging).
     */
    public void printActiveOrderStatus() {
        if (activeOrders.isEmpty()) {
            System.out.println("  [ExecutionEngine] No active orders pending");
        } else {
            for (Map.Entry<String, List<Order>> entry : activeOrders.entrySet()) {
                for (Order order : entry.getValue()) {
                    System.out.println("  [ExecutionEngine] PENDING ORDER: " + order.getSymbol() + " " +
                                       order.getSide() + " @ limit " + String.format("%.2f", order.getLimitPrice()) +
                                       " (waiting for fill)");
                }
            }
        }
    }

    /**
     * Check and execute partial profit targets.
     */
    private void checkPartialProfitTargets(Candle candle) {
        if (!accountState.hasPosition(candle.getSymbol())) {
            return;
        }

        Position position = accountState.getPosition(candle.getSymbol());
        EnhancedOrderLevels levels = orderLevels.get(candle.getSymbol());

        if (position == null || levels == null) {
            return;
        }

        // Check each partial target
        for (PartialTarget target : levels.partialTargets) {
            if (target.executed) {
                continue;
            }

            boolean targetHit = false;
            double exitPrice = target.price;

            if (levels.isLong) {
                if (candle.getHigh() >= target.price) {
                    targetHit = true;
                }
            } else {
                if (candle.getLow() <= target.price) {
                    targetHit = true;
                }
            }

            if (targetHit) {
                // Calculate quantity to close
                int closeQty = (int) Math.ceil(levels.originalQuantity * target.percentage);
                closeQty = Math.min(closeQty, Math.abs(position.getQuantity()));

                if (closeQty > 0) {
                    executePartialClose(position, closeQty, exitPrice, candle.getTimestamp(),
                            "Partial profit at " + target.rMultiple + "R");
                    target.executed = true;

                    // Move stop to breakeven after first partial
                    if (target.rMultiple >= 1.0 && !levels.stopMovedToBreakeven) {
                        moveStopToBreakeven(levels);
                    }
                }
            }
        }
    }

    /**
     * Update trailing stops based on price movement.
     */
    private void updateTrailingStops(Candle candle) {
        if (!accountState.hasPosition(candle.getSymbol())) {
            return;
        }

        EnhancedOrderLevels levels = orderLevels.get(candle.getSymbol());
        if (levels == null || !levels.trailingStopActive) {
            return;
        }

        // Trail stop at configured distance
        if (levels.isLong) {
            // For long positions, trail below price
            double newStop = candle.getHigh() - levels.trailingDistance;
            if (newStop > levels.currentStopPrice) {
                levels.currentStopPrice = newStop;
                System.out.println("TRAILING STOP: " + candle.getSymbol() +
                        " stop moved to " + String.format("%.2f", newStop));
            }
        } else {
            // For short positions, trail above price
            double newStop = candle.getLow() + levels.trailingDistance;
            if (newStop < levels.currentStopPrice) {
                levels.currentStopPrice = newStop;
                System.out.println("TRAILING STOP: " + candle.getSymbol() +
                        " stop moved to " + String.format("%.2f", newStop));
            }
        }
    }

    /**
     * Move stop to breakeven (+ small buffer for commissions).
     */
    private void moveStopToBreakeven(EnhancedOrderLevels levels) {
        double buffer = levels.riskDistance * 0.1;  // 10% of risk as buffer

        if (levels.isLong) {
            levels.currentStopPrice = levels.entryPrice + buffer;
        } else {
            levels.currentStopPrice = levels.entryPrice - buffer;
        }

        levels.stopMovedToBreakeven = true;
        levels.trailingStopActive = true;
        levels.trailingDistance = levels.riskDistance;  // Trail at 1R distance initially

        System.out.println("BREAKEVEN: Stop moved to " + String.format("%.2f", levels.currentStopPrice));
    }

    /**
     * Check if stop or target is hit for existing positions.
     */
    private void checkStopTargetHits(Candle candle) {
        if (!accountState.hasPosition(candle.getSymbol())) {
            return;
        }

        Position position = accountState.getPosition(candle.getSymbol());
        EnhancedOrderLevels levels = orderLevels.get(candle.getSymbol());

        if (position == null || levels == null) {
            return;
        }

        boolean exitTriggered = false;
        double exitPrice = 0;
        String exitReason = "";

        if (levels.isLong) {
            // Check stop hit (below stop price)
            if (candle.getLow() <= levels.currentStopPrice) {
                exitTriggered = true;
                exitPrice = levels.currentStopPrice;
                exitReason = levels.stopMovedToBreakeven ? "Breakeven stop hit" : "Stop hit";
            }
            // Check final target hit (above target price)
            else if (candle.getHigh() >= levels.finalTargetPrice) {
                exitTriggered = true;
                exitPrice = levels.finalTargetPrice;
                exitReason = "Final target hit";
            }
        } else {
            // Check stop hit (above stop price)
            if (candle.getHigh() >= levels.currentStopPrice) {
                exitTriggered = true;
                exitPrice = levels.currentStopPrice;
                exitReason = levels.stopMovedToBreakeven ? "Breakeven stop hit" : "Stop hit";
            }
            // Check final target hit (below target price)
            else if (candle.getLow() <= levels.finalTargetPrice) {
                exitTriggered = true;
                exitPrice = levels.finalTargetPrice;
                exitReason = "Final target hit";
            }
        }

        if (exitTriggered) {
            closePosition(position, exitPrice, candle.getTimestamp(), exitReason);
            orderLevels.remove(candle.getSymbol());
        }
    }

    /**
     * Execute a fill for an order.
     */
    private void executeFill(Order order, double fillPrice, Instant fillTime) {
        // AGENT-05.3: a fill onto a flat book starts a NEW position ledger.
        if (!accountState.hasPosition(order.getSymbol())) {
            ledgers.remove(order.getSymbol());
        }
        order.recordFill(order.getQuantity(), fillPrice);

        // Update position in account
        int positionDelta = order.getSide() == OrderSide.BUY ? order.getQuantity() : -order.getQuantity();
        accountState.updatePosition(order.getSymbol(), positionDelta, fillPrice);

        // Update entry price in order levels
        EnhancedOrderLevels levels = orderLevels.get(order.getSymbol());
        if (levels != null) {
            levels.entryPrice = fillPrice;
            levels.riskDistance = Math.abs(fillPrice - levels.originalStopPrice);

            // Calculate partial target prices based on R-multiples
            if (levels.partialTargets != null) {
                for (PartialTarget target : levels.partialTargets) {
                    if (levels.isLong) {
                        target.price = fillPrice + (levels.riskDistance * target.rMultiple);
                    } else {
                        target.price = fillPrice - (levels.riskDistance * target.rMultiple);
                    }
                }
            }
        }

        // AGENT-05.3: open (or extend) the position ledger with the initial
        // $ risk of this fill — entry to ORIGINAL stop x tick value x qty.
        PositionLedger ledger = ledgers.computeIfAbsent(order.getSymbol(),
                s -> new PositionLedger(order.getSide(), fillPrice, fillTime));
        if (ledger.side == order.getSide()) {
            int prevQty = ledger.filledQty;
            ledger.filledQty += order.getQuantity();
            ledger.entryPrice = prevQty == 0 ? fillPrice
                    : (ledger.entryPrice * prevQty + fillPrice * order.getQuantity()) / ledger.filledQty;
            if (levels != null) {
                ledger.initialRiskDollars += Math.abs(fillPrice - levels.originalStopPrice)
                        / ContractSpecs.tickSize(order.getSymbol())
                        * tickValues.getOrDefault(order.getSymbol(), 12.50) * order.getQuantity();
            }
        }

        System.out.println("ENTRY FILLED: " + order.getSymbol() + " " + order.getSide() +
                          " " + order.getQuantity() + " @ " + String.format("%.2f", fillPrice));

        // CRITICAL: Notify listener that position is opened
        if (executionListener != null) {
            executionListener.onPositionOpened(order.getSymbol(), order.getSide(), fillPrice, order.getQuantity());
        }
    }

    /**
     * Execute a partial close of position.
     */
    private void executePartialClose(Position position, int quantity, double exitPrice,
                                     Instant exitTime, String reason) {
        String symbol = position.getSymbol();
        double entryPrice = position.getAvgEntryPrice();

        // Calculate realized PnL for this partial
        double tickValue = tickValues.getOrDefault(symbol, 12.50);
        double priceDiff = position.isLong() ? (exitPrice - entryPrice) : (entryPrice - exitPrice);
        // AGENT-05 (V5 RC-17): points / tickSize * tickValue (was points * tickValue).
        double realizedPnl = priceDiff / ContractSpecs.tickSize(symbol) * quantity * tickValue;

        // Update account with realized PnL
        accountState.recordRealizedPnL(realizedPnl);

        // Update position quantity
        boolean wasLong = position.isLong();
        int closeQty = wasLong ? -quantity : quantity;
        accountState.updatePosition(symbol, closeQty, exitPrice);

        System.out.println("PARTIAL EXIT: " + symbol + " " + quantity + " @ " +
                          String.format("%.2f", exitPrice) + " | PnL: $" +
                          String.format("%.2f", realizedPnl) + " | " + reason);

        // AGENT-05.3: the leg joins the position's ledger; a partial that
        // FLATTENS the position completes the trade (one Trade, one
        // PositionClosedEvent) — it used to leave no Trade and no event.
        PositionLedger ledger = ledgerFor(position, wasLong);
        ledger.addExit(quantity, exitPrice, realizedPnl, reason);
        if (!accountState.hasPosition(symbol)) {
            completeTrade(symbol, ledger, exitTime);
            orderLevels.remove(symbol);
        }
    }

    /** The symbol's ledger, created from the position when a fill bypassed executeFill. */
    private PositionLedger ledgerFor(Position position, boolean wasLong) {
        String symbol = position.getSymbol();
        return ledgers.computeIfAbsent(symbol, s -> {
            PositionLedger l = new PositionLedger(wasLong ? OrderSide.BUY : OrderSide.SELL,
                    position.getAvgEntryPrice(), position.getOpenedAt());
            l.filledQty = Math.abs(position.getQuantity());
            EnhancedOrderLevels levels = orderLevels.get(symbol);
            if (levels != null) {
                l.initialRiskDollars = Math.abs(position.getAvgEntryPrice() - levels.originalStopPrice)
                        / ContractSpecs.tickSize(symbol) * tickValues.getOrDefault(symbol, 12.50)
                        * Math.abs(position.getQuantity());
            }
            return l;
        });
    }

    /**
     * AGENT-05.3: the position is flat — record exactly ONE Trade that
     * aggregates every exit leg, count it once for the frequency gates, and
     * publish ONE PositionClosedEvent. Realized P&amp;L was already booked
     * per leg (recordRealizedPnL), so it is not booked again here.
     */
    private void completeTrade(String symbol, PositionLedger ledger, Instant exitTime) {
        ledgers.remove(symbol);
        double pnl = ledger.pnl;
        String notes;
        if (ledger.exitReasons.size() == 1) {
            String only = ledger.exitReasons.get(0);
            notes = only.substring(only.indexOf(' ') + 1);
        } else {
            notes = ledger.exitReasons.size() + " exits: " + String.join("; ", ledger.exitReasons);
        }
        Trade trade = Trade.builder()
                .symbol(symbol)
                .side(ledger.side)
                .quantity(ledger.exitedQty)
                .entryPrice(ledger.entryPrice)
                .exitPrice(ledger.vwapExit())
                .entryTime(ledger.entryTime)
                .exitTime(exitTime)
                .realizedPnL(pnl)
                .riskAmount(ledger.initialRiskDollars)
                .notes(notes)
                .tier(pendingTiers.getOrDefault(symbol, TradeTier.TIER_1))
                .confluenceFactors(pendingConfluenceFactors.getOrDefault(symbol, List.of()))
                .build();
        pendingTiers.remove(symbol);
        pendingConfluenceFactors.remove(symbol);
        completedTrades.add(trade);
        // Count the completed trade for the trade-frequency gates
        // (maxTradesPerDay / maxConsecutiveLosses in PropFirmRiskEngine) —
        // ONCE per position, on its total P&L.
        accountState.recordTradeCompleted(pnl);
        System.out.println("TRADE COMPLETE: " + symbol + " " + ledger.side + " q=" + ledger.exitedQty
                + " in=" + String.format("%.2f", ledger.entryPrice)
                + " out(vwap)=" + String.format("%.2f", ledger.vwapExit())
                + " | PnL: $" + String.format("%.2f", pnl)
                + " | R=" + String.format("%.2f", trade.getRMultiple()) + " | " + notes);
        boolean isWin = pnl > 0;
        if (executionListener != null) {
            executionListener.onPositionClosed(symbol, pnl, isWin);
        }
        // Publish the position-closed event at the SAME funnel that counted
        // the trade. Consumers: the strategy's re-arm/latch, dashboards.
        if (eventBus != null) {
            eventBus.publish(new PositionClosedEvent(symbol, pnl, isWin, exitTime));
        }
    }

    /**
     * Close a position at the given price.
     */
    private void closePosition(Position position, double exitPrice, Instant exitTime, String reason) {
        String symbol = position.getSymbol();
        double entryPrice = position.getAvgEntryPrice();
        int quantity = Math.abs(position.getQuantity());

        // Calculate realized PnL
        double tickValue = tickValues.getOrDefault(symbol, 12.50);
        boolean wasLong = position.isLong();
        double priceDiff = wasLong ? (exitPrice - entryPrice) : (entryPrice - exitPrice);
        // AGENT-05 (V5 RC-17): points / tickSize * tickValue (was points * tickValue).
        double realizedPnl = priceDiff / ContractSpecs.tickSize(symbol) * quantity * tickValue;

        // AGENT-05.3: this leg joins the ledger (earlier partial exits
        // included) BEFORE the position is flattened.
        PositionLedger ledger = ledgerFor(position, wasLong);
        ledger.addExit(quantity, exitPrice, realizedPnl, reason);

        // Update account with realized PnL (this leg; partials booked theirs)
        accountState.recordRealizedPnL(realizedPnl);

        // Close position
        int closeQuantity = wasLong ? -quantity : quantity;
        accountState.updatePosition(symbol, closeQuantity, exitPrice);

        System.out.println("EXIT FILLED: " + symbol + " @ " + String.format("%.2f", exitPrice) +
                          " | PnL: $" + String.format("%.2f", realizedPnl) + " | " + reason);

        // ONE Trade for the whole position (+ frequency count, listener,
        // PositionClosedEvent) — see completeTrade.
        completeTrade(symbol, ledger, exitTime);
    }

    /**
     * Update unrealized PnL based on current candle prices.
     */
    private void updateUnrealizedPnl(Candle candle) {
        // AGENT-05: seed every open position with its last known price so a
        // candle for one symbol does not zero another symbol's unrealized P&L.
        Map<String, Double> currentPrices = new HashMap<>(lastPrices);
        currentPrices.put(candle.getSymbol(), candle.getClose());

        accountState.updateUnrealizedPnL(currentPrices, tickValues);
    }

    /**
     * AGENT-05 (V5 RC-17): cancel resting SIM entry orders older than
     * {@code order.ttlBars} feed bars. The strategy latch is released with a
     * synthetic PositionClosedEvent (no position ever existed) and the
     * cancellation is published as a GateDecisionEvent "ORDER_TTL".
     */
    private void expireStaleOrders(Candle candle) {
        if (orderTtlBars <= 0) return;
        List<Order> orders = activeOrders.get(candle.getSymbol());
        if (orders == null || orders.isEmpty()) return;
        for (Order order : orders) {
            int age = orderAgeBars.merge(order.getOrderId(), 1, Integer::sum);
            if (age > orderTtlBars) {
                cancelResting(order, candle, "ORDER_TTL",
                        "SIM order TTL: unfilled after " + (age - 1) + " bars (order.ttlBars=" + orderTtlBars + ")",
                        age - 1, orderTtlBars);
                ttlCancels.incrementAndGet();
            }
        }
    }

    private void cancelResting(Order order, Candle candle, String gate, String reason, double a, double b) {
        String symbol = order.getSymbol();
        List<Order> orders = activeOrders.get(symbol);
        if (orders != null) {
            orders.remove(order);
            if (orders.isEmpty()) activeOrders.remove(symbol);
        }
        orderAgeBars.remove(order.getOrderId());
        order.updateStatus(OrderStatus.CANCELED);
        if (!accountState.hasPosition(symbol)) {
            orderLevels.remove(symbol);
        }
        System.out.println("[ExecutionEngine] " + gate + " cancel " + symbol + " " + order.getSide()
                + " @ " + order.getLimitPrice() + " — " + reason);
        if (eventBus != null) {
            com.topstep.trading.event.EngineTelemetry.publish(eventBus, new com.topstep.trading.event.GateDecisionEvent(
                    symbol, candle.getTimestamp(), null, "ORDER_RESTING", gate, reason, a, b));
            // No position was created: release the strategy's latch.
            eventBus.publish(new PositionClosedEvent(symbol, 0.0, false, candle.getTimestamp()));
        }
    }

    /** 14:45–17:00 CT: cancel resting entries, flatten open SIM positions at the close. */
    private void applyFlattenSafetyNet(Candle candle) {
        String symbol = candle.getSymbol();
        List<Order> orders = activeOrders.get(symbol);
        if (orders != null) {
            for (Order order : orders) {
                cancelResting(order, candle, "FLATTEN",
                        "FLATTEN: resting entry cancelled inside the 14:45-17:00 CT no-entry block", 0, 0);
            }
        }
        if (accountState.hasPosition(symbol)) {
            Position position = accountState.getPosition(symbol);
            if (position != null && !position.isFlat()) {
                safetyNetFlattens.incrementAndGet();
                System.out.println("[ExecutionEngine] FLATTEN safety net: closing " + symbol
                        + " at " + candle.getClose() + " (" + candle.getTimestamp() + ", 14:45 CT rule)");
                closePosition(position, candle.getClose(), candle.getTimestamp(),
                        "FLATTEN 14:45 CT safety net");
                orderLevels.remove(symbol);
                if (eventBus != null) {
                    com.topstep.trading.event.EngineTelemetry.publish(eventBus, new com.topstep.trading.event.GateDecisionEvent(
                            symbol, candle.getTimestamp(), null, "IN_POSITION", "FLATTEN",
                            "FLATTEN: position closed by the 14:45 CT execution safety net",
                            position.getQuantity(), candle.getClose()));
                }
            }
        }
    }

    /**
     * Get all completed trades.
     */
    public List<Trade> getCompletedTrades() {
        return new ArrayList<>(completedTrades);
    }

    /**
     * Record a trade whose fills happened outside this engine (live broker
     * fills routed through BracketOrderManager or flatten market orders).
     * Only adds the record for journaling/dashboard — account P&L and
     * trade-frequency accounting remain the caller's responsibility, since
     * the live close paths already update AccountState themselves.
     */
    public synchronized void recordExternalTrade(Trade trade) {
        if (trade == null) return;
        // AGENT-05.3: merge any partial legs of the same position into ONE Trade.
        List<Trade> legs = externalLegs.remove(trade.getSymbol());
        if (legs == null || legs.isEmpty()) {
            completedTrades.add(trade);
            return;
        }
        legs.add(trade);
        completedTrades.add(mergeLegs(legs));
    }

    /**
     * AGENT-05.3 (LIVE journaling): a partial exit of a position that is
     * still open. Held until the position goes flat, then merged with the
     * final leg ({@link #recordExternalTrade}) or on its own
     * ({@link #finalizeExternalTrade}) into ONE Trade.
     */
    public synchronized void recordExternalPartial(Trade leg) {
        if (leg == null) return;
        externalLegs.computeIfAbsent(leg.getSymbol(), s -> new ArrayList<>()).add(leg);
    }

    /** AGENT-05.3: the position went flat on a partial — record its merged Trade. */
    public synchronized void finalizeExternalTrade(String symbol) {
        List<Trade> legs = externalLegs.remove(symbol);
        if (legs != null && !legs.isEmpty()) {
            completedTrades.add(mergeLegs(legs));
        }
    }

    /** Quantity = sum, exit = VWAP, P&amp;L = sum, risk = sum of the legs' risk. */
    static Trade mergeLegs(List<Trade> legs) {
        if (legs.size() == 1) return legs.get(0);
        Trade first = legs.get(0);
        Trade last = legs.get(legs.size() - 1);
        int qty = 0;
        double notional = 0, pnl = 0, risk = 0;
        List<String> notes = new ArrayList<>();
        for (Trade t : legs) {
            qty += t.getQuantity();
            notional += t.getQuantity() * t.getExitPrice();
            pnl += t.getRealizedPnL();
            risk += t.getRiskAmount();
            notes.add(t.getQuantity() + "@" + String.format("%.2f", t.getExitPrice()) + " " + t.getNotes());
        }
        return Trade.builder()
                .symbol(first.getSymbol())
                .side(first.getSide())
                .quantity(qty)
                .entryPrice(first.getEntryPrice())
                .exitPrice(qty > 0 ? notional / qty : last.getExitPrice())
                .entryTime(first.getEntryTime())
                .exitTime(last.getExitTime())
                .realizedPnL(pnl)
                .riskAmount(risk)
                .tier(first.getTier())
                .confluenceFactors(first.getConfluenceFactors())
                .notes(legs.size() + " exits: " + String.join("; ", notes))
                .build();
    }

    /**
     * Get current account state.
     */
    public AccountState getAccountState() {
        return accountState;
    }

    /**
     * Get tick value for a symbol.
     */
    public double getTickValue(String symbol) {
        return tickValues.getOrDefault(symbol, 12.50);
    }

    /**
     * Set custom tick value for a symbol.
     */
    public void setTickValue(String symbol, double tickValue) {
        tickValues.put(symbol, tickValue);
    }

    /**
     * Get all active (pending) orders as a flat map (first order per symbol for compatibility).
     * Use getActiveOrdersList() for full list.
     */
    public Map<String, Order> getActiveOrders() {
        Map<String, Order> result = new HashMap<>();
        for (Map.Entry<String, List<Order>> entry : activeOrders.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                result.put(entry.getKey(), entry.getValue().get(0));
            }
        }
        return result;
    }

    /**
     * Get all active orders for a symbol.
     */
    public List<Order> getActiveOrdersList(String symbol) {
        List<Order> l = activeOrders.get(symbol);
        return l == null ? new ArrayList<>() : new ArrayList<>(l);
    }

    /**
     * Remove all orders for a symbol (e.g., after cancellation).
     */
    public void removeOrder(String symbol) {
        activeOrders.remove(symbol);
    }

    /**
     * Remove a specific order by order ID.
     */
    public void removeOrderById(String symbol, String orderId) {
        List<Order> orders = activeOrders.get(symbol);
        if (orders != null) {
            orders.removeIf(o -> o.getOrderId().equals(orderId));
            if (orders.isEmpty()) {
                activeOrders.remove(symbol);
            }
        }
    }

    /**
     * Get current order levels for a symbol.
     */
    public EnhancedOrderLevels getOrderLevels(String symbol) {
        return orderLevels.get(symbol);
    }

    /**
     * Enhanced order levels with partial profit and trailing stop support.
     */
    public static class EnhancedOrderLevels {
        double entryPrice;
        double originalStopPrice;
        double currentStopPrice;
        double finalTargetPrice;
        double riskDistance;
        double trailingDistance;
        boolean isLong;
        int originalQuantity;
        TradeTier tier;
        List<PartialTarget> partialTargets;
        boolean stopMovedToBreakeven;
        boolean trailingStopActive;

        public EnhancedOrderLevels(double entryPrice, double stopPrice, double targetPrice,
                                   OrderSide side, int quantity, TradeTier tier) {
            this(entryPrice, stopPrice, targetPrice, side, quantity, tier, tier.getPartialProfitTargets());
        }

        public EnhancedOrderLevels(double entryPrice, double stopPrice, double targetPrice,
                                   OrderSide side, int quantity, TradeTier tier,
                                   double[][] partialProfitTargets) {
            this.entryPrice = entryPrice;
            this.originalStopPrice = stopPrice;
            this.currentStopPrice = stopPrice;
            this.finalTargetPrice = targetPrice;
            this.isLong = (side == OrderSide.BUY);
            this.originalQuantity = quantity;
            this.tier = tier;
            this.riskDistance = Math.abs(entryPrice - stopPrice);
            this.stopMovedToBreakeven = false;
            this.trailingStopActive = false;
            this.trailingDistance = riskDistance;

            // Initialize partial targets
            this.partialTargets = new ArrayList<>();
            if (partialProfitTargets != null) {
                for (double[] target : partialProfitTargets) {
                    double rMultiple = target[0];
                    double percentage = target[1];
                    double price;

                    if (isLong) {
                        price = entryPrice + (riskDistance * rMultiple);
                    } else {
                        price = entryPrice - (riskDistance * rMultiple);
                    }

                    partialTargets.add(new PartialTarget(rMultiple, percentage, price));
                }
            }
        }

        // Getters
        public double getEntryPrice() { return entryPrice; }
        public double getCurrentStopPrice() { return currentStopPrice; }
        public double getFinalTargetPrice() { return finalTargetPrice; }
        public boolean isStopMovedToBreakeven() { return stopMovedToBreakeven; }
        public TradeTier getTier() { return tier; }
    }

    /**
     * Represents a partial profit target.
     */
    private static class PartialTarget {
        final double rMultiple;
        final double percentage;
        double price;
        boolean executed;

        PartialTarget(double rMultiple, double percentage, double price) {
            this.rMultiple = rMultiple;
            this.percentage = percentage;
            this.price = price;
            this.executed = false;
        }
    }
}
