package com.topstep.trading.execution;

import com.topstep.trading.connector.TopstepConnector;
import com.topstep.trading.connector.TopstepConnector.BrokerOrder;
import com.topstep.trading.connector.TopstepConnector.BrokerPosition;
import com.topstep.trading.connector.TopstepConnector.BrokerSnapshot;
import com.topstep.trading.domain.ContractSpecs;
import com.topstep.trading.domain.OrderSide;
import com.topstep.trading.domain.OrderStatus;
import com.topstep.trading.domain.RiskLimits;
import com.topstep.trading.strategy.TradeTier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/**
 * Manages OCO (One Cancels Other) bracket orders for position protection.
 *
 * ENHANCED: Now supports multi-level take profits based on tier configuration.
 *
 * When an entry order fills, this manager:
 * 1. Submits a Stop Loss (Stop Market) order for full quantity
 * 2. Submits multiple Take Profit (Limit) orders at different R-multiples
 * 3. When first TP fills, moves SL to breakeven
 * 4. When SL fills, cancels all remaining TP orders (OCO)
 *
 * Directional rules:
 * - LONG position: SL = Sell Stop Market (below entry), TP = Sell Limit (above entry)
 * - SHORT position: SL = Buy Stop Market (above entry), TP = Buy Limit (below entry)
 *
 * <h2>AGENT-05.11 (V5, LIVE defect 2026-09-29): the stop can never be lost</h2>
 * <ul>
 *   <li>Every stop change is PLACE-THEN-CANCEL: the new stop is placed first
 *       and the old one is cancelled only after TopstepX acknowledged the new
 *       one. Only if the new stop cannot be placed alongside the old one does
 *       it fall back to cancel-then-place (retry with backoff, then the old
 *       level, then FLATTEN by market + GateDecisionEvent
 *       "BRACKET: stop lost — flattened").</li>
 *   <li>The quantity a stop protects comes from the POSITION (the
 *       {@link #setPositionQuantityProvider provider}, i.e. the engine's
 *       Position after the partial), falling back to the take-profit ledger —
 *       never a counter that can read 0. A quantity &lt;= 0 never cancels
 *       anything.</li>
 *   <li>After every stop operation the BROKER's view (Position/searchOpen +
 *       Order/searchOpen) is logged and enforced: exactly one stop, size ==
 *       position size. {@link #reconcileWithBroker()} repeats that every 30 s
 *       and adopts broker positions the engine does not track.</li>
 *   <li>A cancel refused with "order not open" (code 5 / 2) means the order is
 *       GONE — never "still working". Every log line that states a broker
 *       state follows a broker response.</li>
 *   <li>When the position goes flat, every remaining order for the symbol is
 *       cancelled at the broker and the empty book is verified.</li>
 * </ul>
 *
 * <h2>AGENT-05.14 (V5, LIVE 2026-09-30 15:34 PT): adoption safety</h2>
 * <ul>
 *   <li>ONE stop per position: an acknowledged stop counts as working for
 *       {@link #STOP_ACK_GRACE_MS} even if Order/searchOpen does not list it
 *       yet; after that it is re-queried by id, and cancelled before any
 *       replacement. More than one working stop -&gt; extras cancelled.</li>
 *   <li>An adopted position larger than {@code RiskLimits.getMaxContracts()}
 *       is OBSERVE-ONLY: registered, never protected / flattened / cancelled.</li>
 *   <li>Other adopted positions get a stop at a REAL risk distance
 *       ({@link #adoptedStopPrice}), clamped to the correct side of the last
 *       price, and wait for a price when none is known.</li>
 * </ul>
 */
public class BracketOrderManager {

    /**
     * Represents a single take profit level.
     */
    public static class TakeProfitLevel {
        public final double rMultiple;      // e.g., 1.0 = 1R, 2.0 = 2R
        public final double percentage;     // e.g., 0.50 = 50% of position
        public final double price;          // calculated price level
        public final int quantity;          // contracts to close at this level
        public String orderId;              // assigned after submission
        public boolean filled = false;

        public TakeProfitLevel(double rMultiple, double percentage, double price, int quantity) {
            this.rMultiple = rMultiple;
            this.percentage = percentage;
            this.price = price;
            this.quantity = quantity;
        }
    }

    /**
     * Represents a linked bracket (SL + multiple TPs) for a position.
     */
    public static class BracketOrder {
        public final String symbol;
        public final String entryOrderId;
        public final double entryPrice;
        public final int totalQuantity;
        public final OrderSide entrySide;
        public final TradeTier tier;

        // CRITICAL: volatile for thread-safe access from callback threads
        public volatile String stopOrderId;
        public volatile double stopPrice;
        public double originalStopPrice;    // Keep track for breakeven calculation
        public volatile int remainingQuantity;       // Contracts still open (TP ledger / position)
        /** AGENT-05.11: size of the working stop as last acknowledged by the broker. */
        public volatile int stopQuantity;
        /**
         * AGENT-05.11: where the stop is MEANT to be (the breakeven price once a
         * move was requested, even if that move failed). Reconciliation
         * re-places a missing stop here.
         */
        public volatile double targetStopPrice;
        /** AGENT-05.11: bracket built for a broker position the engine did not open. */
        public volatile boolean adopted = false;
        /**
         * AGENT-05.14: adopted position the engine can NOT own (size &gt;
         * maxContracts, sticky until the broker is flat). The engine places no
         * stop / take-profit / flatten for it and never cancels its orders; it
         * is only registered (entry gates, total-contracts gate) and released
         * when the broker goes flat.
         */
        public volatile boolean observeOnly = false;

        // Multi-level take profits
        public List<TakeProfitLevel> takeProfitLevels = new ArrayList<>();

        // CRITICAL: volatile flags for thread-safe checks from callback threads
        public volatile boolean stopFilled = false;
        public volatile boolean allTakesProfitFilled = false;
        public volatile boolean canceled = false;
        public volatile boolean movedToBreakeven = false;

        // Price-based breakeven trigger for single-contract positions
        // When only 1 contract runs to full target, move stop to breakeven at 1R price level
        public volatile double breakevenTriggerPrice = 0.0;  // 0 = no trigger
        public volatile boolean isSingleContractRunner = false;

        // Legacy single TP (for backward compatibility)
        public String takeProfitOrderId;
        public double takeProfitPrice;
        public boolean takeProfitFilled = false;

        // When the bracket was created ≈ entry fill time; used as the
        // entryTime on Trade records built from live bracket exits.
        public final java.time.Instant createdAt = java.time.Instant.now();

        public BracketOrder(String symbol, String entryOrderId, double entryPrice,
                           int quantity, OrderSide entrySide, TradeTier tier) {
            this.symbol = symbol;
            this.entryOrderId = entryOrderId;
            this.entryPrice = entryPrice;
            this.totalQuantity = quantity;
            this.remainingQuantity = quantity;
            this.stopQuantity = quantity;
            this.entrySide = entrySide;
            this.tier = tier != null ? tier : TradeTier.TIER_1;
        }

        public boolean isLong() {
            return entrySide == OrderSide.BUY;
        }

        public OrderSide getExitSide() {
            return isLong() ? OrderSide.SELL : OrderSide.BUY;
        }

        public int getTotalFilledTpQuantity() {
            int filled = 0;
            for (TakeProfitLevel tp : takeProfitLevels) {
                if (tp.filled) {
                    filled += tp.quantity;
                }
            }
            return filled;
        }

        public boolean hasUnfilledTakeProfits() {
            for (TakeProfitLevel tp : takeProfitLevels) {
                if (!tp.filled && tp.orderId != null) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Callback interface for bracket events.
     */
    public interface BracketListener {
        /**
         * Also called (AGENT-05) when an UNPROTECTED position is flattened by
         * market because no stop could be placed — the position is closed by
         * a protective exit either way, so the same P&amp;L/close funnel applies.
         */
        void onStopLossFilled(BracketOrder bracket, double fillPrice);
        void onTakeProfitFilled(BracketOrder bracket, double fillPrice);
        void onPartialTakeProfitFilled(BracketOrder bracket, TakeProfitLevel level, double fillPrice);
        void onBracketCanceled(BracketOrder bracket, String reason);
        void onStopMovedToBreakeven(BracketOrder bracket, double newStopPrice);

        /**
         * AGENT-05.11: a broker position the engine did not track was adopted
         * (startup or reconciliation). The runner registers it so no second
         * entry is opened on that symbol and its exit books P&amp;L.
         */
        default void onPositionAdopted(BracketOrder bracket) {
            // Default no-op
        }

        /**
         * AGENT-05.13: the bracket was dropped because the BROKER is flat for
         * its symbol (reconciliation saw no position on two consecutive passes
         * and no fill callback arrived). Distinct from a plain cancel: the
         * position is gone at the broker, so the runner must release it from
         * AccountState. The exit price is NOT known on this path. The default
         * delegates to {@link #onBracketCanceled} (pre-05.13 behaviour).
         */
        default void onBrokerFlat(BracketOrder bracket, String reason) {
            onBracketCanceled(bracket, reason);
        }
    }

    /** Outcome of a cancel, as the broker answered it. */
    enum CancelResult {
        /** TopstepX acknowledged the cancel. */
        CANCELLED,
        /** TopstepX says the order is not open (already cancelled / filled). */
        NOT_WORKING,
        /** The cancel call failed; the order's broker state is unverified. */
        FAILED
    }

    // Track active brackets by symbol
    private final Map<String, BracketOrder> activeBrackets = new ConcurrentHashMap<>();

    // Track order ID to bracket mapping for quick lookup
    private final Map<String, BracketOrder> orderIdToBracket = new ConcurrentHashMap<>();

    // Track TP order ID to level mapping
    private final Map<String, TakeProfitLevel> tpOrderIdToLevel = new ConcurrentHashMap<>();

    private final TopstepConnector connector;
    private BracketListener listener;

    // Breakeven buffer (in ticks or minimum move)
    private static final double BREAKEVEN_BUFFER_TICKS = 2;

    // ── AGENT-05 (V5 RC-17): never leave a LIVE position without a stop ──
    /** Attempts per protective-order submission before escalating. */
    static final int PROTECTIVE_RETRIES = 3;
    private volatile com.topstep.trading.event.EventBus eventBus;
    private final java.util.concurrent.atomic.AtomicLong unprotectedFlattens = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong protectiveFailures = new java.util.concurrent.atomic.AtomicLong();

    // ── AGENT-05.11 ──
    /** Symbol -> absolute open quantity of the engine's Position (&lt;= 0 = unknown/flat). */
    private volatile ToIntFunction<String> positionQuantityProvider;
    /** Backoff base between protective retries (attempt n waits n * base). */
    private volatile long retryBackoffMs = 150;
    /** Consecutive reconciliation passes that saw a tracked symbol FLAT at the broker. */
    private final Map<String, Integer> flatSightings = new ConcurrentHashMap<>();
    /** Consecutive reconciliation passes that saw an UNTRACKED broker position. */
    private final Map<String, Integer> untrackedSightings = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong stopsRestored = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong positionsAdopted = new java.util.concurrent.atomic.AtomicLong();

    // ── AGENT-05.14 (LIVE 2026-09-30 15:34 PT: two stops for one adopted 45-lot) ──
    /**
     * A stop TopstepX acknowledged with an order id counts as WORKING for this
     * long even when Order/searchOpen does not list it yet (the LIVE view lagged
     * ~100 ms behind the acknowledgement and a second stop was placed).
     */
    public static final long STOP_ACK_GRACE_MS = 5_000;
    /** Minimum distance (ticks of the symbol) of a stop placed for an adopted position. */
    public static final int ADOPTED_MIN_STOP_TICKS = 8;
    /** A stop on the wrong side of the last price is clamped this many ticks beyond it. */
    public static final int WRONG_SIDE_CLAMP_TICKS = 4;
    /** Stop order id -&gt; time (clock ms) TopstepX acknowledged it. */
    private final Map<String, Long> stopAckedAt = new ConcurrentHashMap<>();
    /** Symbol -&gt; last known price (last candle close). */
    private final Map<String, Double> lastPrices = new ConcurrentHashMap<>();
    private volatile LongSupplier clock = System::currentTimeMillis;
    private volatile Supplier<RiskLimits> riskLimitsProvider;
    private final java.util.concurrent.atomic.AtomicLong observeOnlyAdoptions = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong extraStopsCancelled = new java.util.concurrent.atomic.AtomicLong();

    /**
     * AGENT-05.14: the ACTIVE risk limits (maxContracts decides observe-only
     * adoption; riskPerTrade sizes the stop of an adopted position). Without a
     * provider {@link RiskLimits#topstep50k()} is used.
     */
    public void setRiskLimitsProvider(Supplier<RiskLimits> provider) {
        this.riskLimitsProvider = provider;
    }

    /** Test hook: the clock used for the acknowledged-stop grace window. */
    void setClock(LongSupplier clock) {
        this.clock = clock != null ? clock : System::currentTimeMillis;
    }

    /** Adopted positions registered OBSERVE-ONLY. */
    public long getObserveOnlyAdoptionCount() { return observeOnlyAdoptions.get(); }

    /** Extra working stops cancelled by the one-stop invariant. */
    public long getExtraStopsCancelledCount() { return extraStopsCancelled.get(); }

    private RiskLimits limits() {
        Supplier<RiskLimits> p = this.riskLimitsProvider;
        RiskLimits l = null;
        if (p != null) {
            try {
                l = p.get();
            } catch (RuntimeException e) {
                System.err.println("[BRACKET] ERROR risk limits lookup failed: " + e.getMessage() + " — using topstep50k");
            }
        }
        return l != null ? l : RiskLimits.topstep50k();
    }

    /**
     * AGENT-05.14: last known price for {@code symbol} (the runner feeds every
     * candle close). An engine-owned ADOPTED position still waiting for its
     * first price gets its stop placed here.
     */
    public void onLastPrice(String symbol, double price) {
        if (symbol == null || !(price > 0) || Double.isNaN(price)) return;
        Double prev = lastPrices.put(symbol, price);
        BracketOrder b = activeBrackets.get(symbol);
        if (prev == null && b != null && b.adopted && !b.observeOnly && b.stopOrderId == null
                && !b.canceled && !b.stopFilled) {
            System.err.println("[BRACKET] first price " + price + " for adopted " + symbol
                    + " awaiting a stop — verifying the broker and placing it");
            verifyAtBroker(b, "first price for adopted position");
        }
    }

    private double lastPrice(String symbol) {
        Double p = lastPrices.get(symbol);
        return p == null ? Double.NaN : p;
    }

    private boolean inAckGrace(String orderId) {
        if (orderId == null) return false;
        Long t = stopAckedAt.get(orderId);
        return t != null && clock.getAsLong() - t < STOP_ACK_GRACE_MS;
    }

    private long msSinceAck(String orderId) {
        Long t = orderId == null ? null : stopAckedAt.get(orderId);
        return t == null ? -1 : clock.getAsLong() - t;
    }

    private static double floorToTick(double px, double tick) {
        return tick > 0 ? Math.floor(px / tick + 1e-9) * tick : px;
    }

    private static double ceilToTick(double px, double tick) {
        return tick > 0 ? Math.ceil(px / tick - 1e-9) * tick : px;
    }

    private static double roundToTick(double px, double tick) {
        return tick > 0 ? Math.round(px / tick) * tick : px;
    }

    /**
     * AGENT-05.14 (rule C): the protective stop for an engine-owned ADOPTED
     * position — never at the entry price. Distance (ticks) =
     * max(floor(riskPerTrade / (qty x tickValue)), {@link #ADOPTED_MIN_STOP_TICKS}),
     * on the loss side of the (tick-aligned) entry.
     */
    double adoptedStopPrice(String symbol, boolean isLong, double entry, int qty) {
        double tick = ContractSpecs.tickSize(symbol);
        double tickValue = ContractSpecs.tickValue(symbol);
        RiskLimits lim = limits();
        long riskTicks = 0;
        if (lim.getRiskPerTrade() > 0 && tickValue > 0 && qty > 0) {
            riskTicks = (long) Math.floor(lim.getRiskPerTrade() / (qty * tickValue) + 1e-9);
        }
        long ticks = Math.max(riskTicks, ADOPTED_MIN_STOP_TICKS);
        double base = roundToTick(entry, tick);
        double px = isLong ? base - ticks * tick : base + ticks * tick;
        px = roundToTick(px, tick);
        System.err.println("[BRACKET] adopted " + symbol + " stop distance " + ticks + " ticks ("
                + String.format("%.2f", ticks * tick) + " pts) = max(riskPerTrade $" + lim.getRiskPerTrade()
                + " / (" + qty + " x $" + tickValue + "/tick) = " + riskTicks + " ticks, min "
                + ADOPTED_MIN_STOP_TICKS + " ticks) -> " + (isLong ? "SELL" : "BUY") + " STOP @ " + px
                + " (entry " + base + ")");
        return px;
    }

    /**
     * AGENT-05.14: a long's stop must be BELOW the last price and a short's
     * ABOVE it (TopstepX rejects the other side: "Order price is outside
     * allowed range"). A wrong-side stop is clamped {@link #WRONG_SIDE_CLAMP_TICKS}
     * ticks beyond the last price. No last price: returned unchanged when
     * {@code requirePrice} is false, NaN (= wait) when true.
     */
    double clampToMarket(BracketOrder b, double stop, boolean requirePrice) {
        double last = lastPrice(b.symbol);
        if (Double.isNaN(last)) {
            return requirePrice ? Double.NaN : stop;
        }
        double tick = ContractSpecs.tickSize(b.symbol);
        if (b.isLong() && stop >= last) {
            double c = floorToTick(last - WRONG_SIDE_CLAMP_TICKS * tick, tick);
            System.err.println("[BRACKET] WARN " + b.symbol + " SELL STOP @ " + stop + " would be on the WRONG side of the last price "
                    + last + " — clamped to " + c + " (" + WRONG_SIDE_CLAMP_TICKS + " ticks below the market)");
            return c;
        }
        if (!b.isLong() && stop <= last) {
            double c = ceilToTick(last + WRONG_SIDE_CLAMP_TICKS * tick, tick);
            System.err.println("[BRACKET] WARN " + b.symbol + " BUY STOP @ " + stop + " would be on the WRONG side of the last price "
                    + last + " — clamped to " + c + " (" + WRONG_SIDE_CLAMP_TICKS + " ticks above the market)");
            return c;
        }
        return stop;
    }

    /** Optional: publish GateDecisionEvent "BRACKET" on protective failures. */
    public void setEventBus(com.topstep.trading.event.EventBus bus) {
        this.eventBus = bus;
    }

    /**
     * AGENT-05.11: where the protected quantity comes from — the engine's
     * Position for the symbol (absolute size). Without a provider (or when it
     * reports &lt;= 0) the take-profit ledger (total - filled TPs) is used.
     */
    public void setPositionQuantityProvider(ToIntFunction<String> provider) {
        this.positionQuantityProvider = provider;
    }

    /** Test hook: backoff base between protective retries. */
    void setRetryBackoffMs(long ms) {
        this.retryBackoffMs = Math.max(0, ms);
    }

    /** Positions flattened by market because no stop could be placed. */
    public long getUnprotectedFlattenCount() { return unprotectedFlattens.get(); }

    /** Protective-order submissions that failed (each attempt counted). */
    public long getProtectiveFailureCount() { return protectiveFailures.get(); }

    /** Stops re-placed because the broker showed a position without one. */
    public long getStopsRestoredCount() { return stopsRestored.get(); }

    /** Broker positions adopted (startup / reconciliation). */
    public long getPositionsAdoptedCount() { return positionsAdopted.get(); }

    private void backoff(int attempt) {
        long ms = retryBackoffMs * attempt;
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private void publishBracketEvent(String symbol, String reason, double a, double b) {
        com.topstep.trading.event.EventBus bus = this.eventBus;
        if (bus != null) {
            com.topstep.trading.event.EngineTelemetry.publish(bus, new com.topstep.trading.event.GateDecisionEvent(
                    symbol, java.time.Instant.now(), null, "IN_POSITION", "BRACKET", reason, a, b));
        }
    }

    /**
     * Submit a stop with up to {@link #PROTECTIVE_RETRIES} attempts (backoff
     * between them); null when all failed. A non-null id means TopstepX
     * acknowledged the order.
     */
    private String submitStopWithRetry(BracketOrder bracket, int quantity, double stopPrice) {
        if (quantity <= 0) {
            // AGENT-05.11: the LIVE defect tried to place "qty 0" — never again.
            System.err.println("[BRACKET] ERROR refusing to submit a stop for " + bracket.symbol
                    + " with quantity " + quantity + " (must be > 0)");
            return null;
        }
        Exception last = null;
        for (int attempt = 1; attempt <= PROTECTIVE_RETRIES; attempt++) {
            if (attempt > 1) backoff(attempt - 1);
            try {
                return connector.submitStopOrder(
                    bracket.symbol,
                    bracket.getExitSide(),
                    quantity,
                    stopPrice,
                    (id, status, price, qty) -> handleStopOrderUpdate(bracket, status, price)
                );
            } catch (Exception e) {
                last = e;
                protectiveFailures.incrementAndGet();
                System.err.println("[BRACKET] ERROR stop submit attempt " + attempt + "/" + PROTECTIVE_RETRIES
                        + " for " + bracket.symbol + " (" + quantity + " @ " + stopPrice + ") rejected: " + e.getMessage());
            }
        }
        System.err.println("[BRACKET] ERROR stop could not be placed for " + bracket.symbol
                + " after " + PROTECTIVE_RETRIES + " attempts: " + (last != null ? last.getMessage() : "?"));
        return null;
    }

    /** Record a broker-acknowledged stop as the bracket's working stop. */
    private void installStop(BracketOrder bracket, String id, int quantity, double price) {
        bracket.stopOrderId = id;
        bracket.stopPrice = price;
        bracket.stopQuantity = quantity;
        orderIdToBracket.put(id, bracket);
        // AGENT-05.14: start the acknowledged-stop grace window (first ack only).
        stopAckedAt.putIfAbsent(id, clock.getAsLong());
    }

    /**
     * Cancel one order and report what the BROKER said. Every log line here
     * follows the broker's response.
     */
    private CancelResult cancelAtBroker(String orderId, String reason) {
        if (orderId == null) return CancelResult.NOT_WORKING;
        try {
            connector.cancelOrder(orderId);
            System.out.println("[BRACKET] Order " + orderId + " cancelled (TopstepX acknowledged): " + reason);
            return CancelResult.CANCELLED;
        } catch (TopstepConnector.OrderNotWorkingException gone) {
            System.out.println("[BRACKET] Order " + orderId + " was NOT open at TopstepX (code " + gone.getCode()
                    + ") — already cancelled or filled; nothing left to cancel (" + reason + ")");
            return CancelResult.NOT_WORKING;
        } catch (Exception e) {
            protectiveFailures.incrementAndGet();
            System.err.println("[BRACKET] ERROR cancel of order " + orderId + " FAILED (" + reason + "): "
                    + e.getMessage() + " — its state at the broker is UNVERIFIED");
            return CancelResult.FAILED;
        }
    }

    /** {@link #cancelAtBroker} with {@link #PROTECTIVE_RETRIES} attempts for FAILED. */
    private CancelResult cancelWithRetry(String orderId, String reason) {
        CancelResult r = CancelResult.FAILED;
        for (int attempt = 1; attempt <= PROTECTIVE_RETRIES; attempt++) {
            if (attempt > 1) backoff(attempt - 1);
            r = cancelAtBroker(orderId, reason);
            if (r != CancelResult.FAILED) return r;
        }
        return r;
    }

    /** The broker's positions + working orders, or null (logged) when unavailable. */
    private BrokerSnapshot brokerSnapshot(String context) {
        try {
            BrokerSnapshot snap = connector.fetchBrokerSnapshot();
            if (snap == null) {
                System.err.println("[BRACKET] broker view unavailable (" + context
                        + ") — stop state NOT verified; reconciliation will retry");
            }
            return snap;
        } catch (Exception e) {
            System.err.println("[BRACKET] ERROR broker query failed (" + context + "): " + e.getMessage()
                    + " — stop state NOT verified; reconciliation will retry");
            return null;
        }
    }

    /**
     * AGENT-05.11: the quantity the stop must protect, from the POSITION.
     * The provider (engine Position, updated by the listener before this is
     * called) wins; otherwise the TP ledger. Never a free-running counter.
     */
    int resolveProtectedQuantity(BracketOrder bracket) {
        int ledger = bracket.totalQuantity - bracket.getTotalFilledTpQuantity();
        ToIntFunction<String> p = this.positionQuantityProvider;
        int fromPosition = 0;
        if (p != null) {
            try {
                fromPosition = p.applyAsInt(bracket.symbol);
            } catch (RuntimeException e) {
                System.err.println("[BRACKET] ERROR position quantity lookup failed for " + bracket.symbol
                        + ": " + e.getMessage() + " — using TP ledger " + ledger);
            }
        }
        if (fromPosition > 0) {
            if (fromPosition != ledger && !bracket.adopted) {
                System.err.println("[BRACKET] WARN " + bracket.symbol + " position size " + fromPosition
                        + " != TP ledger " + ledger + " — protecting the POSITION size " + fromPosition);
            }
            return fromPosition;
        }
        return Math.max(0, ledger);
    }

    /**
     * Last resort: the position has NO stop. Cancel the bracket's other
     * legs, close the position by MARKET, log at ERROR and publish a
     * GateDecisionEvent "BRACKET: stop lost — flattened". The market fill is
     * routed through the listener's stop-fill funnel (P&amp;L booked,
     * PositionClosedEvent) and then every remaining order for the symbol is
     * cancelled at the broker.
     */
    private void flattenUnprotected(BracketOrder bracket, int quantity, String why) {
        if (bracket.observeOnly) {
            // AGENT-05.14 (rule D): never for a position the engine does not own.
            System.err.println("[BRACKET] ERROR refusing to flatten OBSERVE-ONLY " + bracket.symbol + " (" + why
                    + ") — the engine never closes a position it does not own");
            return;
        }
        unprotectedFlattens.incrementAndGet();
        System.err.println("[BRACKET] ERROR " + bracket.symbol + " position UNPROTECTED (" + why
                + ") — flattening " + quantity + " by MARKET");
        publishBracketEvent(bracket.symbol, "BRACKET: stop lost — flattened (" + why + ")",
                quantity, bracket.stopPrice);
        bracket.canceled = true;
        for (TakeProfitLevel tp : bracket.takeProfitLevels) {
            if (tp.orderId != null && !tp.filled) {
                cancelAtBroker(tp.orderId, "unprotected flatten");
            }
        }
        if (bracket.takeProfitOrderId != null && !bracket.takeProfitFilled) {
            cancelAtBroker(bracket.takeProfitOrderId, "unprotected flatten");
        }
        removeBracket(bracket);
        if (quantity <= 0) return;
        try {
            com.topstep.trading.domain.Order close = new com.topstep.trading.domain.Order(
                    bracket.symbol, bracket.getExitSide(),
                    com.topstep.trading.domain.OrderType.MARKET, quantity, 0.0);
            connector.submitOrder(close, (id, status, price, qty) -> {
                if (status == OrderStatus.FILLED) {
                    bracket.stopFilled = true;
                    if (listener != null) {
                        listener.onStopLossFilled(bracket, price != null ? price : bracket.stopPrice);
                    }
                    sweepSymbolOrders(bracket.symbol, "flat after unprotected flatten");
                }
            });
        } catch (Exception e) {
            System.err.println("[BRACKET] CRITICAL flatten order FAILED for " + bracket.symbol
                    + ": " + e.getMessage() + " — MANUAL INTERVENTION REQUIRED");
            publishBracketEvent(bracket.symbol,
                    "BRACKET: CRITICAL flatten failed — manual intervention required: " + e.getMessage(),
                    quantity, Double.NaN);
        }
    }

    /**
     * Replace the working stop with one for {@code quantity} at
     * {@code newPrice}. PLACE-THEN-CANCEL: the old stop is cancelled only
     * after TopstepX acknowledged the new one. If the new stop cannot be
     * placed alongside the old one, falls back to cancel-then-place (retry,
     * then the old level), and FLATTENS as the last resort. Never ends with a
     * position and no working stop. The broker's view is verified afterwards.
     *
     * @return true when a stop at {@code newPrice} is working
     */
    private boolean replaceStop(BracketOrder bracket, int quantity, double newPrice, String why) {
        synchronized (bracket) {
            if (quantity <= 0) {
                System.err.println("[BRACKET] ERROR " + why + " for " + bracket.symbol + ": protected quantity resolved to "
                        + quantity + " — NOT touching the working stop " + bracket.stopOrderId);
                verifyAtBroker(bracket, why + " (quantity guard)");
                return false;
            }
            String oldId = bracket.stopOrderId;

            // ── Phase A: place the new stop FIRST ──
            String newId = submitStopWithRetry(bracket, quantity, newPrice);
            if (newId != null) {
                installStop(bracket, newId, quantity, newPrice);
                System.out.println("[BRACKET] New stop " + newId + " acknowledged by TopstepX: "
                        + bracket.getExitSide() + " STOP " + quantity + " @ " + newPrice + " (" + why + ")");
                if (oldId != null && !oldId.equals(newId)) {
                    CancelResult r = cancelWithRetry(oldId, why + ": superseded by " + newId);
                    orderIdToBracket.remove(oldId);
                    stopAckedAt.remove(oldId);
                    if (r == CancelResult.FAILED) {
                        System.err.println("[BRACKET] ERROR superseded stop " + oldId + " could not be cancelled — the broker may show "
                                + "TWO stops for " + bracket.symbol + "; verifying now");
                        publishBracketEvent(bracket.symbol, "BRACKET: superseded stop " + oldId
                                + " cancel failed — verifying broker", quantity, newPrice);
                    }
                }
                verifyAtBroker(bracket, why);
                return true;
            }

            // ── Phase B: TopstepX refused a second stop — cancel-then-place ──
            System.err.println("[BRACKET] ERROR new stop for " + bracket.symbol + " (" + why + ") could not be placed while stop "
                    + oldId + " is working — falling back to cancel-then-place");
            if (oldId != null) {
                CancelResult r = cancelWithRetry(oldId, why + ": cancel-then-place fallback");
                if (r == CancelResult.FAILED) {
                    BrokerSnapshot snap = brokerSnapshot(why + ": old-stop check");
                    boolean stillWorking = snap == null || containsOrder(snap, oldId);
                    if (stillWorking) {
                        System.err.println("[BRACKET] ERROR stop " + oldId + " for " + bracket.symbol
                                + (snap == null ? " — broker state UNKNOWN (query failed)"
                                                : " is still WORKING at the broker (verified)")
                                + "; no replacement placed (" + why + "); reconciliation will re-check");
                        return false;
                    }
                    System.err.println("[BRACKET] stop " + oldId + " is NOT working at the broker (verified) despite the failed cancel");
                }
                orderIdToBracket.remove(oldId);
                stopAckedAt.remove(oldId);
                bracket.stopOrderId = null;
                bracket.stopQuantity = 0;
            }
            newId = submitStopWithRetry(bracket, quantity, newPrice);
            boolean atNew = newId != null;
            double placedAt = newPrice;
            if (newId == null && Math.abs(newPrice - bracket.stopPrice) > 1e-12) {
                System.err.println("[BRACKET] ERROR re-placing the previous stop level " + bracket.stopPrice
                        + " for " + bracket.symbol);
                newId = submitStopWithRetry(bracket, quantity, bracket.stopPrice);
                placedAt = bracket.stopPrice;
            }
            if (newId == null) {
                flattenUnprotected(bracket, quantity, why);
                return false;
            }
            installStop(bracket, newId, quantity, placedAt);
            System.out.println("[BRACKET] Stop " + newId + " acknowledged by TopstepX: " + bracket.getExitSide()
                    + " STOP " + quantity + " @ " + placedAt + " (" + why + ", cancel-then-place)");
            verifyAtBroker(bracket, why);
            return atNew;
        }
    }

    private static boolean containsOrder(BrokerSnapshot snap, String orderId) {
        for (BrokerOrder o : snap.openOrders) {
            if (o.orderId.equals(orderId)) return true;
        }
        return false;
    }

    /**
     * Log the broker's view after a stop operation and enforce "exactly one
     * stop, size == position size". Unavailable view -&gt; logged, not assumed.
     */
    private void verifyAtBroker(BracketOrder bracket, String context) {
        BrokerSnapshot snap = brokerSnapshot(context);
        if (snap != null) {
            enforceProtection(bracket, snap, context);
        }
    }

    private static String describe(BrokerSnapshot snap, String symbol, OrderSide exitSide) {
        BrokerPosition pos = snap.position(symbol);
        return "position " + (pos == null ? "FLAT" : pos.toString()) + ", working stops " + snap.stopsFor(symbol, exitSide);
    }

    /**
     * Make the broker match "one stop, size == position size" for a tracked
     * bracket. Uses only primitive broker operations (no replaceStop), so it
     * never recurses (the single re-query below is bounded).
     *
     * <p>AGENT-05.14: a stop TopstepX acknowledged within
     * {@link #STOP_ACK_GRACE_MS} counts as WORKING even when Order/searchOpen
     * does not list it yet. After the grace window a missing acknowledged stop
     * is looked for once more by id; still missing -&gt; it is CANCELLED first
     * (not-found ignored) and only then replaced. More than one working stop
     * -&gt; the extras are cancelled down to one (the tracked one kept).
     */
    private void enforceProtection(BracketOrder bracket, BrokerSnapshot snap, String context) {
        enforceProtection(bracket, snap, context, true);
    }

    private void enforceProtection(BracketOrder bracket, BrokerSnapshot snap, String context, boolean mayRequery) {
        synchronized (bracket) {
            if (bracket.observeOnly) {
                // AGENT-05.14 (rule B): never touch a position the engine does not own.
                System.out.println("[BRACKET] OBSERVE-ONLY " + bracket.symbol + " after " + context + ": "
                        + describe(snap, bracket.symbol, bracket.getExitSide()) + " — not managed by the engine");
                return;
            }
            OrderSide exitSide = bracket.getExitSide();
            BrokerPosition pos = snap.position(bracket.symbol);
            List<BrokerOrder> stops = snap.stopsFor(bracket.symbol, exitSide);
            if (pos == null) {
                System.out.println("[BRACKET] BROKER VIEW " + bracket.symbol + " after " + context + ": "
                        + describe(snap, bracket.symbol, exitSide));
                return;
            }
            if (pos.isLong != bracket.isLong()) {
                System.err.println("[BRACKET] ERROR BROKER VIEW " + bracket.symbol + " after " + context + ": broker position "
                        + pos + " is on the OTHER side of the engine bracket (" + (bracket.isLong() ? "LONG" : "SHORT")
                        + ") — manual check required");
                publishBracketEvent(bracket.symbol, "BRACKET: broker position side differs from bracket — " + pos,
                        pos.size, pos.averagePrice);
                return;
            }
            int size = pos.size;
            String tracked = bracket.stopOrderId;
            boolean trackedListed = tracked != null && containsId(stops, tracked);

            if (tracked != null && !trackedListed) {
                if (inAckGrace(tracked)) {
                    // ── acknowledged but not listed yet: it IS working ──
                    System.out.println("[BRACKET] BROKER VIEW " + bracket.symbol + " after " + context + ": "
                            + describe(snap, bracket.symbol, exitSide) + " — acknowledged stop " + tracked
                            + " not listed by Order/searchOpen yet (acked " + msSinceAck(tracked) + " ms ago, grace "
                            + STOP_ACK_GRACE_MS + " ms): counted as WORKING, nothing re-placed");
                    if (!stops.isEmpty()) {
                        cancelExtraStops(bracket, stops, null, 1 + stops.size(), context);
                    }
                    return;
                }
                if (mayRequery) {
                    // ── (i) look for the acknowledged id specifically ──
                    BrokerSnapshot again = brokerSnapshot(context + ": re-check acknowledged stop " + tracked);
                    if (again == null) {
                        System.err.println("[BRACKET] ERROR " + bracket.symbol + ": acknowledged stop " + tracked
                                + " not listed and the re-check failed — NOTHING placed (a second stop could result);"
                                + " reconciliation re-checks");
                        return;
                    }
                    if (!containsOrder(again, tracked)) {
                        // ── (ii) still missing after the grace window: cancel it FIRST ──
                        System.err.println("[BRACKET] ERROR " + bracket.symbol + ": acknowledged stop " + tracked
                                + " still NOT listed by the broker " + msSinceAck(tracked) + " ms after its acknowledgement"
                                + " — cancelling it before any replacement");
                        CancelResult r = cancelWithRetry(tracked, "acknowledged stop missing from the broker view");
                        if (r == CancelResult.FAILED) {
                            System.err.println("[BRACKET] ERROR cancel of the unlisted stop " + tracked + " for " + bracket.symbol
                                    + " FAILED — NO replacement placed (it may still be working; two stops could"
                                    + " result); reconciliation re-checks");
                            publishBracketEvent(bracket.symbol, "BRACKET: unlisted stop " + tracked
                                    + " could not be cancelled — no replacement (" + context + ")", size, bracket.stopPrice);
                            return;
                        }
                        forgetStop(bracket, tracked);
                    }
                    enforceProtection(bracket, again, context, false);
                    return;
                }
                // mayRequery == false: this IS the re-queried view and the id was
                // listed there (otherwise forgetStop cleared it) — unreachable in
                // practice; fall through to the listed-stops handling.
            }

            if (stops.isEmpty()) {
                rePlaceMissingStop(bracket, size, context);
                return;
            }

            BrokerOrder keep = null;
            for (BrokerOrder o : stops) {
                if (o.orderId.equals(bracket.stopOrderId)) keep = o;
            }
            if (keep == null) {
                keep = stops.get(0);
                System.err.println("[BRACKET] WARN " + bracket.symbol + ": engine believed stop " + bracket.stopOrderId
                        + " is working; the broker's working stop is " + keep + " — adopting it");
                if (bracket.stopOrderId != null) forgetStop(bracket, bracket.stopOrderId);
                installStop(bracket, keep.orderId, keep.size, Double.isNaN(keep.stopPrice) ? bracket.stopPrice : keep.stopPrice);
                connector.trackExistingOrder(keep.orderId, bracket.symbol, keep.size, exitSide, bracket.stopPrice,
                        (id, status, price, qty) -> handleStopOrderUpdate(bracket, status, price));
            }
            if (stops.size() > 1) {
                cancelExtraStops(bracket, stops, keep, stops.size(), context);
            }
            if (keep.size != size) {
                double price = Double.isNaN(keep.stopPrice) ? bracket.stopPrice : keep.stopPrice;
                System.err.println("[BRACKET] ERROR stop " + keep + " size " + keep.size + " != broker position size " + size
                        + " for " + bracket.symbol + " — resizing (place-then-cancel)");
                String id = submitStopWithRetry(bracket, size, price);
                if (id != null) {
                    installStop(bracket, id, size, price);
                    cancelWithRetry(keep.orderId, "resized to " + size + " by " + id);
                    orderIdToBracket.remove(keep.orderId);
                    stopAckedAt.remove(keep.orderId);
                    bracket.remainingQuantity = size;
                    System.out.println("[BRACKET] Stop resized: " + id + " " + exitSide + " STOP " + size + " @ " + price
                            + " acknowledged by TopstepX (" + context + ")");
                } else {
                    System.err.println("[BRACKET] ERROR resize failed — stop " + keep + " stays working (verified) with size "
                            + keep.size + " vs position " + size + "; reconciliation will retry");
                    publishBracketEvent(bracket.symbol, "BRACKET: stop size " + keep.size + " != position " + size
                            + " and resize failed", size, price);
                }
                return;
            }
            bracket.stopQuantity = keep.size;
            System.out.println("[BRACKET] BROKER VIEW " + bracket.symbol + " after " + context + ": "
                    + describe(snap, bracket.symbol, exitSide) + " — OK (1 stop, size " + keep.size
                    + " == position " + size + ")");
        }
    }

    private static boolean containsId(List<BrokerOrder> orders, String orderId) {
        for (BrokerOrder o : orders) {
            if (o.orderId.equals(orderId)) return true;
        }
        return false;
    }

    /** Drop a stop id from the bracket's tracking (the broker side was handled by the caller). */
    private void forgetStop(BracketOrder bracket, String id) {
        orderIdToBracket.remove(id);
        stopAckedAt.remove(id);
        if (id.equals(bracket.stopOrderId)) {
            bracket.stopOrderId = null;
            bracket.stopQuantity = 0;
        }
    }

    /**
     * AGENT-05.14 hard invariant: one position, ONE working stop. Every listed
     * stop other than {@code keep} is cancelled ({@code keep == null}: the
     * tracked stop is acknowledged but not listed yet, so every listed stop is
     * an extra).
     */
    private void cancelExtraStops(BracketOrder bracket, List<BrokerOrder> listed, BrokerOrder keep, int total,
                                  String context) {
        List<BrokerOrder> extras = new ArrayList<>();
        for (BrokerOrder o : listed) {
            if (o != keep) extras.add(o);
        }
        if (extras.isEmpty()) return;
        String keptId = keep != null ? keep.orderId : bracket.stopOrderId;
        System.err.println("[BRACKET] ERROR INVARIANT " + bracket.symbol + ": " + total
                + " working stops for one position " + listed + (keep == null ? " + acknowledged #" + keptId : "")
                + " — cancelling " + extras.size() + " extra(s), keeping " + keptId + " (" + context + ")");
        publishBracketEvent(bracket.symbol, "BRACKET: " + total + " working stops — extras cancelled (" + context + ")",
                total, bracket.stopPrice);
        for (BrokerOrder o : extras) {
            cancelWithRetry(o.orderId, "extra stop (" + context + ")");
            orderIdToBracket.remove(o.orderId);
            stopAckedAt.remove(o.orderId);
            extraStopsCancelled.incrementAndGet();
        }
    }

    /**
     * The broker shows the position with NO working stop and the engine has no
     * acknowledged stop in its grace window: place one. Adopted positions get a
     * REAL risk distance (never the entry price) and wait for a price;
     * wrong-side stops are clamped. Flatten only for engine-owned positions.
     */
    private void rePlaceMissingStop(BracketOrder bracket, int size, String context) {
        OrderSide exitSide = bracket.getExitSide();
        double desired = bracket.targetStopPrice > 0 ? bracket.targetStopPrice : bracket.stopPrice;
        if (bracket.adopted && Double.isNaN(lastPrice(bracket.symbol))) {
            System.err.println("[BRACKET] ERROR adopted " + bracket.symbol + " " + (bracket.isLong() ? "LONG " : "SHORT ")
                    + size + " @ " + bracket.entryPrice + " has NO working stop and NO last price yet — WAITING for a"
                    + " price before placing one (never at the entry price) (" + context + ")");
            return;
        }
        if (bracket.adopted && !(desired > 0)) {
            desired = adoptedStopPrice(bracket.symbol, bracket.isLong(), bracket.entryPrice, size);
            bracket.targetStopPrice = desired;
            bracket.originalStopPrice = desired;
        }
        double price = clampToMarket(bracket, desired, false);
        System.err.println("[BRACKET] ERROR BROKER VIEW " + bracket.symbol + " after " + context + ": position "
                + (bracket.isLong() ? "LONG " : "SHORT ") + size + ", working stops [] and no acknowledged stop within the "
                + STOP_ACK_GRACE_MS + " ms grace — position has NO working stop; placing " + exitSide + " STOP " + size
                + " @ " + price);
        publishBracketEvent(bracket.symbol, "BRACKET: broker position without a stop — re-placing ("
                + context + ")", size, price);
        String id = submitStopWithRetry(bracket, size, price);
        double placedAt = price;
        if (id == null && bracket.stopPrice > 0 && Math.abs(price - bracket.stopPrice) > 1e-12) {
            double prev = clampToMarket(bracket, bracket.stopPrice, false);
            if (Math.abs(prev - price) > 1e-12) {
                id = submitStopWithRetry(bracket, size, prev);
                placedAt = prev;
            }
        }
        if (id == null) {
            flattenUnprotected(bracket, size, "stop missing at broker and could not be re-placed (" + context + ")");
            return;
        }
        installStop(bracket, id, size, placedAt);
        stopsRestored.incrementAndGet();
        bracket.remainingQuantity = size;
        System.err.println("[BRACKET] Stop " + id + " re-placed and acknowledged by TopstepX: " + exitSide
                + " STOP " + size + " @ " + placedAt + " (" + context + ")");
    }

    /**
     * Position is flat: cancel EVERY remaining order for the symbol at the
     * broker and verify the book is empty. Refuses (and logs) if the broker
     * still shows a position — a stop is never stripped from an open position.
     */
    void sweepSymbolOrders(String symbol, String why) {
        BrokerSnapshot snap = brokerSnapshot("flat sweep " + symbol);
        if (snap == null) {
            System.err.println("[BRACKET] could NOT verify that " + symbol + " has no working orders after " + why
                    + " (broker view unavailable)");
            return;
        }
        BrokerPosition pos = snap.position(symbol);
        if (pos != null) {
            System.err.println("[BRACKET] ERROR broker still shows " + pos + " after " + why
                    + " — NOT cancelling its orders; reconciliation will verify");
            return;
        }
        List<BrokerOrder> orders = snap.ordersFor(symbol);
        for (BrokerOrder o : orders) {
            cancelAtBroker(o.orderId, "position flat (" + why + ")");
        }
        BrokerSnapshot after = orders.isEmpty() ? snap : brokerSnapshot("flat sweep verify " + symbol);
        if (after == null) {
            System.err.println("[BRACKET] could NOT verify the " + symbol + " order book after cancelling "
                    + orders.size() + " order(s) (broker view unavailable)");
            return;
        }
        List<BrokerOrder> left = after.ordersFor(symbol);
        if (left.isEmpty()) {
            System.out.println("[BRACKET] BROKER VIEW " + symbol + " after " + why + ": FLAT, 0 working orders (verified; "
                    + orders.size() + " cancelled)");
        } else {
            System.err.println("[BRACKET] ERROR BROKER VIEW " + symbol + " after " + why + ": FLAT but still working "
                    + left + " — manual check required");
            publishBracketEvent(symbol, "BRACKET: flat but orders still working " + left, left.size(), Double.NaN);
        }
    }

    public BracketOrderManager(TopstepConnector connector) {
        this.connector = connector;
    }

    public void setListener(BracketListener listener) {
        this.listener = listener;
    }

    /**
     * An ADOPTED bracket for {@code symbol} is superseded by an engine
     * bracket: drop it from tracking and return its stop id (cancelled once
     * the new stop is acknowledged). Null when there is nothing to supersede.
     * Returns "" (skip) when a normal bracket already exists.
     */
    private String supersedeAdopted(String symbol) {
        BracketOrder existing = activeBrackets.get(symbol);
        if (existing == null) return null;
        if (!existing.adopted) {
            System.out.println("[BRACKET] Warning: Bracket already exists for " + symbol + ", skipping");
            return "";
        }
        System.out.println("[BRACKET] Engine bracket supersedes the ADOPTED bracket for " + symbol
                + "; adopted stop " + existing.stopOrderId + " is cancelled after the new stop is acknowledged");
        existing.canceled = true;
        removeBracket(existing);
        return existing.stopOrderId;
    }

    private void cancelSupersededAdoptedStop(String adoptedStopId) {
        if (adoptedStopId != null && !adoptedStopId.isEmpty()) {
            cancelWithRetry(adoptedStopId, "superseded adopted stop");
        }
    }

    /**
     * Create and submit an ENHANCED bracket with multi-level take profits.
     *
     * @param symbol The trading symbol
     * @param entryOrderId The entry order ID
     * @param entryPrice The actual fill price
     * @param quantity The filled quantity
     * @param entrySide BUY for long, SELL for short
     * @param stopPrice The stop loss price
     * @param finalTargetPrice The final take profit price (full R:R target)
     * @param tier The trade tier (determines partial profit levels)
     * @param tickSize The instrument's tick size for breakeven calculation
     */
    public void createBracketWithPartials(String symbol, String entryOrderId, double entryPrice,
                                          int quantity, OrderSide entrySide,
                                          double stopPrice, double finalTargetPrice,
                                          TradeTier tier, double tickSize) {

        // Check if bracket already exists
        String adoptedStop = supersedeAdopted(symbol);
        if ("".equals(adoptedStop)) {
            return;
        }

        BracketOrder bracket = new BracketOrder(symbol, entryOrderId, entryPrice, quantity, entrySide, tier);
        bracket.stopPrice = stopPrice;
        bracket.originalStopPrice = stopPrice;
        bracket.targetStopPrice = stopPrice;
        bracket.takeProfitPrice = finalTargetPrice;

        OrderSide exitSide = bracket.getExitSide();
        double riskDistance = Math.abs(entryPrice - stopPrice);

        System.out.println("\n[BRACKET] Creating TIERED bracket for " + symbol + " (" + tier + "):");
        System.out.println("  Entry: " + (bracket.isLong() ? "LONG" : "SHORT") + " " + quantity + " @ " + entryPrice);
        System.out.println("  Stop Loss: " + exitSide + " STOP @ " + stopPrice);
        System.out.println("  Risk Distance: " + String.format("%.2f", riskDistance));

        // Calculate take profit levels based on tier
        double[][] partialTargets = tier.getPartialProfitTargets();
        int remainingQty = quantity;
        List<TakeProfitLevel> levels = new ArrayList<>();

        System.out.println("  Take Profit Levels:");

        // SMALL POSITION OPTIMIZATION: For 1-2 contracts, use simplified distribution
        // to ensure a "runner" always reaches the tier's full R:R target.
        // Without this fix, Math.max(1,...) consumes all contracts at early TP levels.
        if (quantity <= 2 && partialTargets.length > 1) {
            double lastRMultiple = partialTargets[partialTargets.length - 1][0];  // Tier's full target

            if (quantity == 1) {
                // 1 CONTRACT: Single TP at the tier's FULL R:R target
                // Run for the big target instead of closing at 1R
                double levelPrice;
                if (bracket.isLong()) {
                    levelPrice = entryPrice + (riskDistance * lastRMultiple);
                } else {
                    levelPrice = entryPrice - (riskDistance * lastRMultiple);
                }
                TakeProfitLevel level = new TakeProfitLevel(lastRMultiple, 1.0, levelPrice, 1);
                levels.add(level);
                remainingQty = 0;

                // Set price-based breakeven trigger at 1R for protection
                bracket.isSingleContractRunner = true;
                if (bracket.isLong()) {
                    bracket.breakevenTriggerPrice = entryPrice + riskDistance;  // 1R above entry
                } else {
                    bracket.breakevenTriggerPrice = entryPrice - riskDistance;  // 1R below entry
                }

                System.out.println("    TP1: 1 contract @ " + String.format("%.2f", levelPrice) +
                    " (100% at " + lastRMultiple + "R) [single-contract: full target]");
                System.out.println("    Breakeven trigger: " +
                    String.format("%.2f", bracket.breakevenTriggerPrice) + " (1R price-based)");

            } else {
                // 2 CONTRACTS: 1 at 1R (lock-in + breakeven move), 1 at full target (runner)
                double firstR = partialTargets[0][0];  // Always 1R

                // TP1: Lock-in at 1R
                double price1;
                if (bracket.isLong()) {
                    price1 = entryPrice + (riskDistance * firstR);
                } else {
                    price1 = entryPrice - (riskDistance * firstR);
                }
                levels.add(new TakeProfitLevel(firstR, 0.50, price1, 1));
                System.out.println("    TP1: 1 contract @ " + String.format("%.2f", price1) +
                    " (50% at " + firstR + "R) [lock-in + breakeven]");

                // TP2: Runner at full tier target
                double price2;
                if (bracket.isLong()) {
                    price2 = entryPrice + (riskDistance * lastRMultiple);
                } else {
                    price2 = entryPrice - (riskDistance * lastRMultiple);
                }
                levels.add(new TakeProfitLevel(lastRMultiple, 0.50, price2, 1));
                System.out.println("    TP2: 1 contract @ " + String.format("%.2f", price2) +
                    " (50% at " + lastRMultiple + "R) [runner]");

                remainingQty = 0;
            }
        } else {
            // STANDARD DISTRIBUTION: 3+ contracts use normal tier-based partials
            for (int i = 0; i < partialTargets.length && remainingQty > 0; i++) {
                double rMultiple = partialTargets[i][0];
                double percentage = partialTargets[i][1];

                // Calculate quantity for this level
                int levelQty;
                if (i == partialTargets.length - 1) {
                    // Last level gets remaining quantity
                    levelQty = remainingQty;
                } else {
                    levelQty = Math.max(1, (int) Math.round(quantity * percentage));
                    levelQty = Math.min(levelQty, remainingQty);
                }

                // Calculate price for this level
                double levelPrice;
                if (bracket.isLong()) {
                    levelPrice = entryPrice + (riskDistance * rMultiple);
                } else {
                    levelPrice = entryPrice - (riskDistance * rMultiple);
                }

                TakeProfitLevel level = new TakeProfitLevel(rMultiple, percentage, levelPrice, levelQty);
                levels.add(level);
                remainingQty -= levelQty;

                System.out.println("    TP" + (i + 1) + ": " + levelQty + " contracts @ " +
                    String.format("%.2f", levelPrice) + " (" + String.format("%.0f%%", percentage * 100) +
                    " at " + rMultiple + "R)");

                if (remainingQty <= 0) break;
            }
        }

        bracket.takeProfitLevels = levels;

        // Submit Stop Loss order (full quantity). AGENT-05: retry, then
        // flatten — a filled entry is NEVER left without a stop.
        String stopOrderId = submitStopWithRetry(bracket, quantity, stopPrice);
        if (stopOrderId == null) {
            flattenUnprotected(bracket, quantity, "initial stop submission failed");
            return;
        }
        installStop(bracket, stopOrderId, quantity, stopPrice);
        System.out.println("  ✓ Stop Loss acknowledged by TopstepX: " + stopOrderId + " (qty: " + quantity + ")");
        cancelSupersededAdoptedStop(adoptedStop);

        // Submit Take Profit orders for each level
        for (int i = 0; i < levels.size(); i++) {
            TakeProfitLevel level = levels.get(i);
            try {
                String tpOrderId = connector.submitTakeProfitOrder(
                    symbol,
                    exitSide,
                    level.quantity,
                    level.price,
                    (id, status, price, qty) -> handlePartialTakeProfitUpdate(bracket, level, status, price, tickSize)
                );
                level.orderId = tpOrderId;
                orderIdToBracket.put(tpOrderId, bracket);
                tpOrderIdToLevel.put(tpOrderId, level);
                System.out.println("  ✓ TP" + (i + 1) + " acknowledged by TopstepX: " + tpOrderId);
            } catch (Exception e) {
                protectiveFailures.incrementAndGet();
                System.err.println("  ❌ ERROR Failed to submit TP" + (i + 1) + ": " + e.getMessage()
                        + " — stop " + stopOrderId + " (acknowledged) protects the position");
                // Continue with other TPs
            }
        }

        // Register the active bracket
        activeBrackets.put(symbol, bracket);
        System.out.println("[BRACKET] Tiered bracket active for " + symbol);
        verifyAtBroker(bracket, "bracket creation");
    }

    /**
     * Legacy method - Create single-level bracket (backward compatible).
     */
    public void createBracket(String symbol, String entryOrderId, double entryPrice,
                              int quantity, OrderSide entrySide,
                              double stopPrice, double takeProfitPrice) {

        // Check if bracket already exists for this symbol (idempotency guard)
        String adoptedStop = supersedeAdopted(symbol);
        if ("".equals(adoptedStop)) {
            return;
        }

        BracketOrder bracket = new BracketOrder(symbol, entryOrderId, entryPrice, quantity, entrySide, null);
        bracket.stopPrice = stopPrice;
        bracket.originalStopPrice = stopPrice;
        bracket.targetStopPrice = stopPrice;
        bracket.takeProfitPrice = takeProfitPrice;

        OrderSide exitSide = bracket.getExitSide();

        System.out.println("[BRACKET] Creating OCO bracket for " + symbol + ":");
        System.out.println("  Entry: " + (bracket.isLong() ? "LONG" : "SHORT") + " @ " + entryPrice);
        System.out.println("  Stop Loss: " + exitSide + " STOP @ " + stopPrice);
        System.out.println("  Take Profit: " + exitSide + " LIMIT @ " + takeProfitPrice);

        // Submit Stop Loss order. AGENT-05: retry, then flatten.
        String stopOrderId = submitStopWithRetry(bracket, quantity, stopPrice);
        if (stopOrderId == null) {
            flattenUnprotected(bracket, quantity, "initial stop submission failed");
            return;
        }
        installStop(bracket, stopOrderId, quantity, stopPrice);
        System.out.println("  ✓ Stop Loss acknowledged by TopstepX: " + stopOrderId);
        cancelSupersededAdoptedStop(adoptedStop);

        // Submit Take Profit order (retry). AGENT-05: a failed TP no longer
        // CANCELS the working stop (that left the position with neither) —
        // the bracket stays registered with its stop; ERROR + telemetry.
        String tpOrderId = null;
        for (int attempt = 1; attempt <= PROTECTIVE_RETRIES && tpOrderId == null; attempt++) {
            try {
                tpOrderId = connector.submitTakeProfitOrder(
                    symbol,
                    exitSide,
                    quantity,
                    takeProfitPrice,
                    (id, status, price, qty) -> handleTakeProfitOrderUpdate(bracket, status, price)
                );
            } catch (Exception e) {
                protectiveFailures.incrementAndGet();
                System.err.println("  ❌ ERROR Take Profit submit attempt " + attempt + "/" + PROTECTIVE_RETRIES
                        + ": " + e.getMessage());
            }
        }
        if (tpOrderId != null) {
            bracket.takeProfitOrderId = tpOrderId;
            orderIdToBracket.put(tpOrderId, bracket);
            System.out.println("  ✓ Take Profit acknowledged by TopstepX: " + tpOrderId);
        } else {
            System.err.println("  ❌ ERROR no Take Profit for " + symbol + " — STOP-ONLY bracket (stop "
                    + stopOrderId + " acknowledged)");
            publishBracketEvent(symbol, "BRACKET: take-profit could not be placed — stop-only protection",
                    quantity, takeProfitPrice);
        }

        // Register the active bracket
        activeBrackets.put(symbol, bracket);
        System.out.println("[BRACKET] OCO bracket active for " + symbol);
        verifyAtBroker(bracket, "bracket creation");
    }

    /**
     * Handle partial take profit fill (from multi-level TP).
     */
    private void handlePartialTakeProfitUpdate(BracketOrder bracket, TakeProfitLevel level,
                                                OrderStatus status, Double fillPrice, double tickSize) {
        if (status != OrderStatus.FILLED) {
            return;
        }
        synchronized (bracket) {
            if (bracket.canceled || level.filled) {
                return;
            }
            level.filled = true;
            // AGENT-05.11: remaining comes from the TP ledger here, and the
            // stop quantity below from the POSITION — no decrementing counter.
            bracket.remainingQuantity = Math.max(0, bracket.totalQuantity - bracket.getTotalFilledTpQuantity());

            System.out.println("\n🎯 PARTIAL TP FILLED: " + bracket.symbol);
            System.out.println("  Level: " + level.rMultiple + "R @ " + fillPrice);
            System.out.println("  Quantity: " + level.quantity + " contracts");
            System.out.println("  Remaining: " + bracket.remainingQuantity + " contracts");

            // Notify listener (the runner reduces the engine Position here)
            if (listener != null) {
                listener.onPartialTakeProfitFilled(bracket, level, fillPrice != null ? fillPrice : level.price);
            }

            int protectedQty = resolveProtectedQuantity(bracket);
            if (protectedQty <= 0) {
                bracket.allTakesProfitFilled = true;
                System.out.println("  ✓ All take profits filled for " + bracket.symbol + " — position flat");

                // Position fully closed: cancel the stop, then sweep the book.
                if (bracket.stopOrderId != null && !bracket.stopFilled) {
                    cancelAtBroker(bracket.stopOrderId, "All take profits filled");
                }
                removeBracket(bracket);
                sweepSymbolOrders(bracket.symbol, "final take profit");

                if (listener != null) {
                    listener.onTakeProfitFilled(bracket, fillPrice != null ? fillPrice : level.price);
                }
                return;
            }
            bracket.remainingQuantity = protectedQty;
            if (!bracket.hasUnfilledTakeProfits()) {
                System.err.println("[BRACKET] WARN " + bracket.symbol + ": no take-profit left working but " + protectedQty
                        + " contract(s) open — the stop keeps protecting them");
            }

            // ONE stop operation for the partial: move to breakeven with the
            // position quantity, or (already moved / not needed) resize.
            if (!bracket.movedToBreakeven && moveStopToBreakeven(bracket, tickSize)) {
                return;
            }
            if (bracket.stopQuantity != protectedQty || bracket.stopOrderId == null) {
                updateStopLossQuantity(bracket, protectedQty);
            } else {
                verifyAtBroker(bracket, "partial take profit");
            }
        }
    }

    /**
     * Move stop loss to breakeven (entry price + small buffer), for the
     * quantity of the POSITION.
     *
     * @return true when a move was attempted (the stop was handled), false
     *         when no move was needed
     */
    private boolean moveStopToBreakeven(BracketOrder bracket, double tickSize) {
        double buffer = tickSize * BREAKEVEN_BUFFER_TICKS;
        double newStopPrice;

        if (bracket.isLong()) {
            newStopPrice = bracket.entryPrice + buffer;
            // Only move if it's actually better (higher stop for long)
            if (newStopPrice <= bracket.stopPrice) {
                System.out.println("[BRACKET] Breakeven not needed - stop already at or above entry");
                return false;
            }
        } else {
            newStopPrice = bracket.entryPrice - buffer;
            // Only move if it's actually better (lower stop for short)
            if (newStopPrice >= bracket.stopPrice) {
                System.out.println("[BRACKET] Breakeven not needed - stop already at or below entry");
                return false;
            }
        }

        int quantity = resolveProtectedQuantity(bracket);
        System.out.println("\n[BRACKET] 🔒 MOVING STOP TO BREAKEVEN: " + bracket.symbol);
        System.out.println("  Entry: " + bracket.entryPrice);
        System.out.println("  Old Stop: " + bracket.stopPrice + " (" + bracket.stopOrderId + ")");
        System.out.println("  New Stop: " + newStopPrice + " (breakeven + " + BREAKEVEN_BUFFER_TICKS + " ticks)");
        System.out.println("  Quantity: " + quantity + " (from the position)");

        // Reconciliation re-places a missing stop HERE even if the move fails.
        bracket.targetStopPrice = newStopPrice;
        // AGENT-05.11: place-then-cancel; flatten only as the last resort.
        if (replaceStop(bracket, quantity, newStopPrice, "breakeven move")) {
            bracket.movedToBreakeven = true;
            bracket.remainingQuantity = quantity;
            System.out.println("  ✓ Breakeven stop working: " + bracket.stopOrderId + " (" + quantity + " @ "
                    + newStopPrice + ", TopstepX acknowledged)");

            if (listener != null) {
                listener.onStopMovedToBreakeven(bracket, newStopPrice);
            }
        } else if (bracket.canceled) {
            System.err.println("  ❌ ERROR breakeven move failed for " + bracket.symbol + " — position was flattened");
        } else {
            System.err.println("  ❌ ERROR breakeven move failed for " + bracket.symbol + " — working stop is "
                    + bracket.stopOrderId + " @ " + bracket.stopPrice + " (see BROKER VIEW)");
        }
        return true;
    }

    /**
     * Update stop loss quantity after partial TP fills (place-then-cancel).
     */
    private void updateStopLossQuantity(BracketOrder bracket, int quantity) {
        if (quantity <= 0) {
            System.err.println("[BRACKET] ERROR stop quantity update for " + bracket.symbol + " resolved to "
                    + quantity + " — working stop untouched");
            return;
        }

        System.out.println("[BRACKET] Updating stop quantity to " + quantity + " for " + bracket.symbol);

        if (replaceStop(bracket, quantity, bracket.stopPrice, "stop quantity update")) {
            bracket.remainingQuantity = quantity;
            System.out.println("  ✓ Stop updated: " + bracket.stopOrderId + " (qty: " + quantity + ", TopstepX acknowledged)");
        } else if (bracket.canceled) {
            System.err.println("  ❌ ERROR stop quantity update failed for " + bracket.symbol + " — position was flattened");
        } else {
            System.err.println("  ❌ ERROR stop quantity update failed for " + bracket.symbol + " — working stop is "
                    + bracket.stopOrderId + " (see BROKER VIEW)");
        }
    }

    /**
     * Handle stop loss order status update.
     */
    private void handleStopOrderUpdate(BracketOrder bracket, OrderStatus status, Double fillPrice) {
        if (status != OrderStatus.FILLED) {
            return;
        }
        synchronized (bracket) {
            if (bracket.canceled || bracket.stopFilled) {
                return;
            }
            bracket.stopFilled = true;
            System.out.println("\n⛔ STOP LOSS FILLED: " + bracket.symbol + " @ " + fillPrice);

            // OCO: Cancel all remaining take profit orders
            for (TakeProfitLevel tp : bracket.takeProfitLevels) {
                if (tp.orderId != null && !tp.filled) {
                    cancelAtBroker(tp.orderId, "Stop Loss filled (OCO)");
                }
            }

            // Legacy single TP
            if (bracket.takeProfitOrderId != null && !bracket.takeProfitFilled) {
                cancelAtBroker(bracket.takeProfitOrderId, "Stop Loss filled (OCO)");
            }

            removeBracket(bracket);
            sweepSymbolOrders(bracket.symbol, "stop loss fill");

            if (listener != null) {
                listener.onStopLossFilled(bracket, fillPrice != null ? fillPrice : bracket.stopPrice);
            }
        }
    }

    /**
     * Handle legacy single take profit order status update.
     */
    private void handleTakeProfitOrderUpdate(BracketOrder bracket, OrderStatus status, Double fillPrice) {
        if (status != OrderStatus.FILLED) {
            return;
        }
        synchronized (bracket) {
            if (bracket.canceled || bracket.takeProfitFilled) {
                return;
            }
            bracket.takeProfitFilled = true;
            System.out.println("\n🎯 TAKE PROFIT FILLED: " + bracket.symbol + " @ " + fillPrice);

            if (bracket.stopOrderId != null && !bracket.stopFilled) {
                cancelAtBroker(bracket.stopOrderId, "Take Profit filled (OCO)");
            }

            removeBracket(bracket);
            sweepSymbolOrders(bracket.symbol, "take profit fill");

            if (listener != null) {
                listener.onTakeProfitFilled(bracket, fillPrice != null ? fillPrice : bracket.takeProfitPrice);
            }
        }
    }

    /**
     * Remove a bracket from tracking.
     */
    private void removeBracket(BracketOrder bracket) {
        activeBrackets.remove(bracket.symbol, bracket);

        if (bracket.stopOrderId != null) {
            orderIdToBracket.remove(bracket.stopOrderId);
            stopAckedAt.remove(bracket.stopOrderId);
        }

        for (TakeProfitLevel tp : bracket.takeProfitLevels) {
            if (tp.orderId != null) {
                orderIdToBracket.remove(tp.orderId);
                tpOrderIdToLevel.remove(tp.orderId);
            }
        }

        // Legacy
        if (bracket.takeProfitOrderId != null) {
            orderIdToBracket.remove(bracket.takeProfitOrderId);
        }
    }

    /**
     * Cancel all orders in a bracket (e.g., when position is manually closed).
     */
    public void cancelBracket(String symbol, String reason) {
        BracketOrder bracket = activeBrackets.get(symbol);
        if (bracket == null) {
            return;
        }

        synchronized (bracket) {
            bracket.canceled = true;
            System.out.println("[BRACKET] Canceling bracket for " + symbol + ": " + reason);

            if (bracket.stopOrderId != null && !bracket.stopFilled) {
                cancelAtBroker(bracket.stopOrderId, reason);
            }

            for (TakeProfitLevel tp : bracket.takeProfitLevels) {
                if (tp.orderId != null && !tp.filled) {
                    cancelAtBroker(tp.orderId, reason);
                }
            }

            // Legacy
            if (bracket.takeProfitOrderId != null && !bracket.takeProfitFilled) {
                cancelAtBroker(bracket.takeProfitOrderId, reason);
            }

            removeBracket(bracket);
        }

        if (listener != null) {
            listener.onBracketCanceled(bracket, reason);
        }
    }

    /**
     * Check if a symbol has an active bracket.
     */
    public boolean hasBracket(String symbol) {
        return activeBrackets.containsKey(symbol);
    }

    /**
     * Cancel all active brackets (used when shutting down without flattening).
     */
    public void cancelAllBrackets(String reason) {
        for (String symbol : activeBrackets.keySet()) {
            cancelBracket(symbol, reason);
        }
    }

    /**
     * Get the active bracket for a symbol.
     */
    public BracketOrder getBracket(String symbol) {
        return activeBrackets.get(symbol);
    }

    /**
     * Get count of active brackets.
     */
    public int getActiveBracketCount() {
        return activeBrackets.size();
    }

    // ── AGENT-05.11: broker reconciliation + adoption ─────────────────────

    /**
     * Engine start: adopt every broker position the engine does not track
     * immediately (no in-flight entry can exist yet), then enforce stops.
     */
    public void reconcileOnStartup() {
        reconcile(true);
    }

    /**
     * Periodic (every 30 s) broker reconciliation. For each tracked bracket:
     * broker position without a stop -&gt; re-place it at the intended stop
     * price (ERROR + event); stop size != position size -&gt; fix it; extra
     * stops -&gt; cancel. Broker FLAT on two consecutive passes -&gt; sweep the
     * symbol's orders and drop the bracket. An untracked broker position seen
     * on two consecutive passes (one pass could be an entry fill still in
     * flight) is adopted.
     *
     * <p>AGENT-05.14: OBSERVE-ONLY brackets are never enforced; their broker
     * orders are never cancelled (not even on broker-flat), and a change of
     * the owner's position is re-registered (still observe-only).
     */
    public void reconcileWithBroker() {
        reconcile(false);
    }

    private synchronized void reconcile(boolean startup) {
        String ctx = startup ? "startup reconciliation" : "reconciliation";
        BrokerSnapshot snap = brokerSnapshot(ctx);
        if (snap == null) {
            return;
        }
        for (BracketOrder bracket : new ArrayList<>(activeBrackets.values())) {
            if (bracket.canceled || bracket.stopFilled) continue;
            BrokerPosition pos = snap.position(bracket.symbol);
            if (bracket.observeOnly) {
                reconcileObserveOnly(bracket, pos, snap, ctx);
                continue;
            }
            if (pos == null) {
                int n = flatSightings.merge(bracket.symbol, 1, Integer::sum);
                if (n < 2) {
                    System.out.println("[RECONCILE] broker shows NO position for tracked " + bracket.symbol
                            + " (1st sighting — a fill callback may be in flight); re-checking next pass");
                    continue;
                }
                flatSightings.remove(bracket.symbol);
                System.err.println("[RECONCILE] ERROR broker FLAT for " + bracket.symbol + " on 2 passes but the bracket is "
                        + "still tracked (no fill callback seen) — cancelling its orders and dropping the bracket");
                publishBracketEvent(bracket.symbol, "BRACKET: broker flat, bracket dropped (no fill callback)",
                        bracket.remainingQuantity, bracket.stopPrice);
                synchronized (bracket) {
                    bracket.canceled = true;
                    removeBracket(bracket);
                }
                sweepSymbolOrders(bracket.symbol, "broker flat (reconciliation)");
                if (listener != null) {
                    // AGENT-05.13: typed callback so the runner releases the position.
                    listener.onBrokerFlat(bracket, "BROKER_FLAT: broker shows no position (reconciliation)");
                }
                continue;
            }
            flatSightings.remove(bracket.symbol);
            enforceProtection(bracket, snap, ctx);
        }

        java.util.Set<String> seen = new java.util.HashSet<>();
        for (BrokerPosition pos : snap.positions) {
            seen.add(pos.symbol);
            if (activeBrackets.containsKey(pos.symbol)) {
                untrackedSightings.remove(pos.symbol);
                continue;
            }
            int n = untrackedSightings.merge(pos.symbol, 1, Integer::sum);
            if (!startup && n < 2) {
                System.err.println("[RECONCILE] WARN broker shows " + pos + " that the engine does not track "
                        + "(1st sighting — may be an entry fill in flight); adopting next pass if still untracked");
                continue;
            }
            untrackedSightings.remove(pos.symbol);
            adoptPosition(pos, snap, ctx, false);
        }
        untrackedSightings.keySet().retainAll(seen);
    }

    /**
     * AGENT-05.14: an OBSERVE-ONLY bracket is only kept in step with the
     * broker: flat on two passes -&gt; released (the owner's orders are NOT
     * swept); side/size changed -&gt; re-registered, still observe-only.
     */
    private void reconcileObserveOnly(BracketOrder bracket, BrokerPosition pos, BrokerSnapshot snap, String ctx) {
        if (pos == null) {
            int n = flatSightings.merge(bracket.symbol, 1, Integer::sum);
            if (n < 2) {
                System.out.println("[RECONCILE] OBSERVE-ONLY " + bracket.symbol
                        + ": broker shows NO position (1st sighting); releasing next pass if still flat");
                return;
            }
            flatSightings.remove(bracket.symbol);
            System.err.println("[BRACKET] OBSERVE-ONLY " + bracket.symbol + ": broker FLAT on 2 passes — releasing it"
                    + " (the owner's orders are not touched)");
            synchronized (bracket) {
                bracket.canceled = true;
                removeBracket(bracket);
            }
            if (listener != null) {
                listener.onBrokerFlat(bracket, "BROKER_FLAT: observe-only position closed at the broker (reconciliation)");
            }
            return;
        }
        flatSightings.remove(bracket.symbol);
        if (pos.isLong != bracket.isLong() || pos.size != bracket.totalQuantity) {
            System.err.println("[BRACKET] OBSERVE-ONLY " + bracket.symbol + " changed at the broker: "
                    + (bracket.isLong() ? "LONG " : "SHORT ") + bracket.totalQuantity + " @ " + bracket.entryPrice
                    + " -> " + pos + " — re-registering it (still OBSERVE-ONLY until the broker is flat)");
            synchronized (bracket) {
                bracket.canceled = true;
                removeBracket(bracket);
            }
            if (listener != null) {
                listener.onBrokerFlat(bracket, "OBSERVE-ONLY position changed at the broker (" + ctx + ")");
            }
            adoptPosition(pos, snap, ctx, true);
            return;
        }
        List<BrokerOrder> stops = snap.stopsFor(bracket.symbol, bracket.getExitSide());
        System.out.println("[BRACKET] OBSERVE-ONLY " + bracket.symbol + " (" + ctx + "): broker " + pos
                + ", orders " + snap.ordersFor(bracket.symbol) + " — not managed by the engine"
                + (stops.size() > 1 ? " (WARN: " + stops.size() + " owner stops, left untouched)" : ""));
    }

    /**
     * Adopt a broker position the engine does not track.
     *
     * <p>AGENT-05.14 rule: a position whose size exceeds
     * {@code RiskLimits.getMaxContracts()} can never have been opened by this
     * engine (every entry is capped there) — it is registered OBSERVE-ONLY: no
     * stop, no take-profit, no flatten, the owner's orders are never cancelled
     * ({@code forceObserveOnly}: the symbol was already observe-only). Any
     * other position may be the engine's (restart): its working stop is kept
     * (size enforced), otherwise a stop is placed at a REAL risk distance
     * ({@link #adoptedStopPrice}, clamped to the market side, waiting for a
     * price when none is known yet); if even that cannot be placed, flatten.
     */
    private void adoptPosition(BrokerPosition pos, BrokerSnapshot snap, String ctx, boolean forceObserveOnly) {
        positionsAdopted.incrementAndGet();
        System.err.println("\n[BRACKET] !!! UNTRACKED BROKER POSITION " + pos + " (" + ctx
                + ") — ADOPTING it; broker orders for the symbol: " + snap.ordersFor(pos.symbol));
        publishBracketEvent(pos.symbol, "BRACKET: untracked broker position adopted — " + pos,
                pos.size, pos.averagePrice);

        OrderSide entrySide = pos.isLong ? OrderSide.BUY : OrderSide.SELL;
        BracketOrder b = new BracketOrder(pos.symbol, "ADOPTED-" + System.currentTimeMillis(),
                pos.averagePrice, pos.size, entrySide, null);
        b.adopted = true;
        b.movedToBreakeven = true; // no engine-side breakeven logic for adopted positions
        List<BrokerOrder> stops = snap.stopsFor(pos.symbol, b.getExitSide());
        int maxContracts = limits().getMaxContracts();
        String side = pos.isLong ? "LONG" : "SHORT";

        if (forceObserveOnly || pos.size > maxContracts) {
            b.observeOnly = true;
            // The owner's stop (if any) is REPORTED only — never tracked, resized or cancelled.
            double ownerStop = stops.isEmpty() || Double.isNaN(stops.get(0).stopPrice) ? 0.0 : stops.get(0).stopPrice;
            b.stopPrice = ownerStop;
            b.targetStopPrice = ownerStop;
            b.originalStopPrice = ownerStop;
            activeBrackets.put(pos.symbol, b);
            observeOnlyAdoptions.incrementAndGet();
            String why = pos.size > maxContracts ? "size > maxContracts " + maxContracts
                    : "symbol already observe-only until the broker is flat";
            System.err.println("[BRACKET] OBSERVE-ONLY adopted " + pos.symbol + " " + side + " " + pos.size + " @ "
                    + pos.averagePrice + " (" + why + "): engine will NOT place stops or flatten it");
            System.err.println("[BRACKET] OBSERVE-ONLY " + pos.symbol + ": the owner's orders " + snap.ordersFor(pos.symbol)
                    + " are left untouched; new engine entries on " + pos.symbol + " are blocked until the broker is flat");
            publishBracketEvent(pos.symbol, "BRACKET: OBSERVE-ONLY adopted " + pos + " (" + why + ")",
                    pos.size, pos.averagePrice);
            notifyAdopted(b);
            return;
        }

        synchronized (b) {
            if (!stops.isEmpty()) {
                BrokerOrder keep = stops.get(0);
                double px = Double.isNaN(keep.stopPrice) ? 0.0 : keep.stopPrice;
                b.targetStopPrice = px;
                b.originalStopPrice = px;
                installStop(b, keep.orderId, keep.size, px);
                connector.trackExistingOrder(keep.orderId, pos.symbol, keep.size, b.getExitSide(), px,
                        (id, status, price, qty) -> handleStopOrderUpdate(b, status, price));
                activeBrackets.put(pos.symbol, b);
                System.err.println("[BRACKET] adopted " + pos.symbol + " with its working broker stop " + keep);
                enforceProtection(b, snap, "adoption");
            } else {
                activeBrackets.put(pos.symbol, b);
                if (Double.isNaN(lastPrice(pos.symbol))) {
                    System.err.println("[BRACKET] adopted " + pos + " has NO working stop and NO last price yet — WAITING"
                            + " for a price before placing one (never at the entry price); next candle / reconciliation retries");
                } else {
                    double px = clampToMarket(b, adoptedStopPrice(pos.symbol, pos.isLong, pos.averagePrice, pos.size), false);
                    b.stopPrice = px;
                    b.targetStopPrice = px;
                    b.originalStopPrice = px;
                    System.err.println("[BRACKET] adopted " + pos + " has NO working stop — placing " + b.getExitSide()
                            + " STOP " + pos.size + " @ " + px + " (risk distance, last price " + lastPrice(pos.symbol) + ")");
                    String id = submitStopWithRetry(b, pos.size, px);
                    if (id == null) {
                        flattenUnprotected(b, pos.size, "adopted position: no stop could be placed");
                        return;
                    }
                    installStop(b, id, pos.size, px);
                    System.err.println("[BRACKET] adopted " + pos.symbol + ": stop " + id + " acknowledged by TopstepX ("
                            + b.getExitSide() + " STOP " + pos.size + " @ " + px + ")");
                    verifyAtBroker(b, "adoption");
                }
            }
        }
        notifyAdopted(b);
    }

    private void notifyAdopted(BracketOrder b) {
        if (listener != null) {
            try {
                listener.onPositionAdopted(b);
            } catch (RuntimeException e) {
                System.err.println("[BRACKET] ERROR onPositionAdopted listener failed for " + b.symbol + ": " + e);
            }
        }
    }

    /**
     * Arm the price-based breakeven trigger on an existing bracket at an
     * arbitrary trigger price (SCALP mode: entry +/- 0.5R behind the
     * {@code scalp.breakevenAtHalfR} flag). Reuses the exact mechanism the
     * single-contract runner path uses — {@link #checkPriceBreakevenTrigger}
     * fires {@code moveStopToBreakeven} when price touches the trigger.
     *
     * No-op when the symbol has no active bracket or the stop already moved.
     *
     * @param symbol       the trading symbol
     * @param triggerPrice price at which the stop moves to breakeven
     */
    public void armPriceBreakevenTrigger(String symbol, double triggerPrice) {
        BracketOrder bracket = activeBrackets.get(symbol);
        if (bracket == null || bracket.canceled || bracket.stopFilled || bracket.movedToBreakeven) {
            return;
        }
        // Mark as a price-triggered runner so checkPriceBreakevenTrigger
        // (already called on every market-data candle) monitors it.
        bracket.isSingleContractRunner = true;
        bracket.breakevenTriggerPrice = triggerPrice;
        System.out.println("[BRACKET] Breakeven trigger armed for " + symbol
                + " @ " + triggerPrice);
    }

    /**
     * Check price-based breakeven trigger for single-contract runner positions.
     *
     * For 1-contract positions targeting the full tier R:R (e.g., 3R, 4R, 5R),
     * there is no partial TP fill to trigger the normal breakeven move.
     * Instead, we monitor price and move to breakeven when price reaches 1R.
     *
     * Call this method on each market data update for symbols with active brackets.
     *
     * @param symbol The trading symbol
     * @param currentPrice The current market price
     * @param tickSize The instrument's tick size for breakeven buffer calculation
     */
    public void checkPriceBreakevenTrigger(String symbol, double currentPrice, double tickSize) {
        BracketOrder bracket = activeBrackets.get(symbol);
        if (bracket == null || bracket.canceled || bracket.stopFilled || bracket.movedToBreakeven) {
            return;
        }

        // Only applies to single-contract runner positions
        if (!bracket.isSingleContractRunner || bracket.breakevenTriggerPrice == 0.0) {
            return;
        }

        // Check if price has reached the breakeven trigger level (1R)
        boolean triggered = false;
        if (bracket.isLong() && currentPrice >= bracket.breakevenTriggerPrice) {
            triggered = true;
        } else if (!bracket.isLong() && currentPrice <= bracket.breakevenTriggerPrice) {
            triggered = true;
        }

        if (triggered) {
            System.out.println("\n[BRACKET] Price reached 1R breakeven trigger for " + symbol +
                " (price: " + currentPrice + ", trigger: " + bracket.breakevenTriggerPrice + ")");
            synchronized (bracket) {
                if (!bracket.movedToBreakeven && !bracket.canceled) {
                    moveStopToBreakeven(bracket, tickSize);
                }
            }
        }
    }
}
