package com.topstep.trading.connector;

import com.topstep.trading.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.*;

/**
 * Mock connector for testing without real connectivity.
 * Simulates market data and order fills.
 */
public class MockConnector implements TradingConnector {
    private static final Logger logger = LoggerFactory.getLogger(MockConnector.class);

    // ── TEST-PROFILE ACCELERATION (SIM verification, Agent 11) ──────────
    // Defaults preserve the historical behavior exactly (one wall-clock
    // stamped candle every 5s). For fast end-to-end SIM proof runs:
    //   -Dmock.candleIntervalMs=25   emit a candle every 25ms
    //   -Dmock.virtualClock=true     stamp candles on a virtual timeline
    //                                advancing 1 MINUTE per candle so the
    //                                15m/30m aggregators complete bars as
    //                                if real 1m data streamed in
    //   -Dmock.virtualMinutes=2000   how far in the past the virtual
    //                                timeline starts (default 2000 min)
    private static final long CANDLE_INTERVAL_MS =
            Long.getLong("mock.candleIntervalMs", 5000L);
    private static final boolean VIRTUAL_CLOCK =
            Boolean.getBoolean("mock.virtualClock");
    private static final long VIRTUAL_START_MINUTES =
            Long.getLong("mock.virtualMinutes", 2000L);
    private final Map<String, Instant> virtualTime = new ConcurrentHashMap<>();

    // ── SIM WARM BOOT (V2 Agent 02) ─────────────────────────────────────
    // SIM boots WARM like LIVE does: N days of SYNTHETIC 1m history
    // (SimWarmBoot, seeded + deterministic) replayed through the SAME
    // listener path before the first live-sim tick. -Dsim.warmBoot=false
    // restores the old cold boot (used by tests that need the cold path).
    private static final boolean WARM_BOOT =
            !"false".equalsIgnoreCase(System.getProperty("sim.warmBoot", "true"));
    /** Symbols already warm-booted — a re-subscription must not replay twice. */
    private final java.util.Set<String> warmBootedSymbols = ConcurrentHashMap.newKeySet();
    /**
     * Watermark of the last candle timestamp emitted per symbol (warm boot
     * or tick). Live-sim ticks are dropped rather than ever emitting a
     * duplicate or backwards timestamp — mirrors HistoricalBackfill's
     * de-dup discipline.
     */
    private final Map<String, Instant> lastEmittedTs = new ConcurrentHashMap<>();

    /**
     * SIM-only scripted tape (V4 follow-up). Non-null unless
     * {@code -Dsim.tape=RANDOM} restores the historical random walk.
     */
    private final SimChoreographyTape choreography =
            SimChoreographyTape.enabled()
                    ? new SimChoreographyTape(SimWarmBoot.configuredSeed())
                    : null;

    private boolean connected;
    private final Map<String, MarketDataListener> marketDataListeners;
    private final Map<String, OrderListener> orderListeners;
    private final ScheduledExecutorService scheduler;
    private final Random random;
    private double accountBalance;

    // Current simulated prices
    private final Map<String, Double> currentPrices;

    public MockConnector(double initialBalance) {
        this.connected = false;
        this.marketDataListeners = new ConcurrentHashMap<>();
        this.orderListeners = new ConcurrentHashMap<>();
        this.scheduler = Executors.newScheduledThreadPool(2);
        this.random = new Random();
        this.accountBalance = initialBalance;
        this.currentPrices = new ConcurrentHashMap<>();

        // Initialize with typical futures prices
        currentPrices.put("ES", 5000.0);
        currentPrices.put("NQ", 17000.0);
        currentPrices.put("YM", 38000.0);
        currentPrices.put("RTY", 2000.0);
        // Micros the STDV+OTE engine actually subscribes
        currentPrices.put("MNQ", 20000.0);
        currentPrices.put("MES", 5000.0);
        currentPrices.put("MGC", 2400.0);
    }

    @Override
    public void connect() {
        logger.info("MockConnector connecting...");
        connected = true;
        logger.info("MockConnector connected");
    }

    @Override
    public void disconnect() {
        logger.info("MockConnector disconnecting...");
        connected = false;
        marketDataListeners.clear();
        orderListeners.clear();
        scheduler.shutdown();
        logger.info("MockConnector disconnected");
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public void subscribeMarketData(String symbol, MarketDataListener listener) {
        if (!connected) {
            throw new IllegalStateException("Not connected");
        }

        marketDataListeners.put(symbol, listener);
        logger.info("Subscribed to market data for symbol: {}", symbol);

        // ── SIM WARM BOOT: replay synthetic history through the SAME
        // listener the ticks below use, oldest → newest, BEFORE the first
        // live-sim tick. Runs once per symbol per connector instance.
        if (WARM_BOOT && warmBootedSymbols.add(symbol)) {
            int days = SimWarmBoot.configuredDays();
            long seed = SimWarmBoot.configuredSeed();
            java.util.List<Candle> history = SimWarmBoot.generate(
                    symbol,
                    currentPrices.getOrDefault(symbol, 5000.0),
                    days, seed, Instant.now());
            Instant watermark = null;
            int delivered = 0;
            for (Candle c : history) {
                // Strict ascending replay (generator guarantees it; the
                // check keeps the discipline explicit and future-proof).
                if (watermark != null && !c.getTimestamp().isAfter(watermark)) {
                    continue;
                }
                listener.onCandle(c);
                watermark = c.getTimestamp();
                delivered++;
            }
            if (watermark != null) {
                lastEmittedTs.put(symbol, watermark);
                // Ticks continue seamlessly from the last backfilled close.
                currentPrices.put(symbol, history.get(history.size() - 1).getClose());
                // Virtual-clock acceleration (if on) continues the SAME
                // timeline instead of jumping backwards.
                virtualTime.put(symbol, watermark);
            }
            logger.info("[SimWarmBoot] {}: delivered {} SYNTHETIC 1m bars ({} days, seed={}). Chart memory is warm.",
                    symbol, delivered, days, seed);

            // ── TIER 2 (V3 Agent 04): synthetic H1 seed via the SEEDING
            // API only — mirrors the live connector's HTF backfill shape.
            // Never through listener.onCandle (Critical Rule 8).
            try {
                int htfDays = SimWarmBoot.configuredHtfDays();
                java.util.List<Candle> h1 = SimWarmBoot.generateHourly(
                        symbol, currentPrices.getOrDefault(symbol, 5000.0),
                        htfDays, seed, Instant.now());
                com.topstep.trading.strategy.HtfSeriesRegistry.get(symbol).ifPresentOrElse(mgr -> {
                    com.topstep.trading.strategy.BarAggregationManager.SeedResult res =
                            mgr.seedHigherTimeframe(h1);
                    logger.info("[HTF-Backfill {}] seeded {} H1 bars -> {} H4, {} D1 ({} days, {} refused, SYNTHETIC)",
                            symbol, res.h1Seeded(), res.h4Derived(), res.d1Derived(),
                            htfDays, res.refused());
                }, () -> logger.warn("[HTF-Backfill {}] no aggregation manager registered — skipping seed", symbol));
            } catch (Exception e) {
                logger.warn("[HTF-Backfill {}] non-fatal failure: {} — continuing without HTF depth",
                        symbol, e.getMessage());
            }
        }

        // Start generating mock candles (default: every 5 seconds; see the
        // test-profile acceleration properties at the top of the class)
        scheduler.scheduleAtFixedRate(() -> generateMockCandle(symbol),
                0, CANDLE_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    public void unsubscribeMarketData(String symbol) {
        marketDataListeners.remove(symbol);
        logger.info("Unsubscribed from market data for symbol: {}", symbol);
    }

    @Override
    public String submitOrder(Order order, OrderListener listener) {
        if (!connected) {
            throw new IllegalStateException("Not connected");
        }

        logger.info("Mock order submitted: {}", order);
        orderListeners.put(order.getOrderId(), listener);

        // Simulate order acceptance
        scheduler.schedule(() -> {
            listener.onOrderSubmitted(order);

            // Simulate fill after 1-3 seconds
            int fillDelay = 1000 + random.nextInt(2000);
            scheduler.schedule(() -> simulateFill(order, listener), fillDelay, TimeUnit.MILLISECONDS);
        }, 100, TimeUnit.MILLISECONDS);

        return order.getOrderId();
    }

    @Override
    public void cancelOrder(String orderId) {
        logger.info("Mock order canceled: {}", orderId);
        OrderListener listener = orderListeners.remove(orderId);
        if (listener != null) {
            // Create a dummy order for the callback
            Order dummyOrder = Order.builder()
                    .orderId(orderId)
                    .symbol("MOCK")
                    .side(OrderSide.BUY)
                    .type(OrderType.MARKET)
                    .quantity(1)
                    .build();
            listener.onOrderCanceled(dummyOrder);
        }
    }

    @Override
    public double getAccountBalance() {
        return accountBalance;
    }

    @Override
    public String getName() {
        return "MockConnector";
    }

    /**
     * Generate a mock candle for testing.
     */
    private void generateMockCandle(String symbol) {
        MarketDataListener listener = marketDataListeners.get(symbol);
        if (listener == null) {
            return;
        }

        try {
            double basePrice = currentPrices.getOrDefault(symbol, 5000.0);

            // Random walk with small increments. NOTE (V4 follow-up): this
            // memoryless walk cannot produce the sweep -> displacement ->
            // MSS -> retrace sequence the engine trades, so on it the funnel
            // stalls at SWEEP_DONE forever and the SIM validates nothing.
            // sim.tape=CHOREOGRAPHY (the default) replaces it below; RANDOM
            // restores this walk for tests that want unstructured noise.
            double priceChange = (random.nextDouble() - 0.5) * 10.0;
            double open = basePrice;
            double close = basePrice + priceChange;
            double high = Math.max(open, close) + random.nextDouble() * 5.0;
            double low = Math.min(open, close) - random.nextDouble() * 5.0;
            long volume = 1000 + random.nextInt(9000);

            Instant ts;
            if (VIRTUAL_CLOCK) {
                // Virtual timeline: 1 minute per candle so HTF aggregation
                // (15m/30m) completes bars under acceleration.
                ts = virtualTime.merge(symbol,
                        Instant.now().minus(java.time.Duration.ofMinutes(VIRTUAL_START_MINUTES)),
                        (cur, seed) -> cur.plus(java.time.Duration.ofMinutes(1)));
            } else {
                ts = Instant.now();
            }

            // Watermark: a live-sim tick must never emit a duplicate or
            // backwards timestamp relative to the warm boot (or a prior
            // tick). Drop the tick instead — the next one advances.
            Instant watermark = lastEmittedTs.get(symbol);
            if (watermark != null && !ts.isAfter(watermark)) {
                return;
            }
            lastEmittedTs.put(symbol, ts);

            // V4 follow-up: the scripted ICT tape. It owns the whole candle
            // (open through volume) so the phases stay internally consistent.
            if (choreography != null) {
                Candle scripted = choreography.next(symbol, ts, open);
                currentPrices.put(symbol, scripted.getClose());
                listener.onCandle(scripted);
                return;
            }

            // Update current price (RANDOM tape only).
            currentPrices.put(symbol, close);

            Candle candle = new Candle(
                    symbol,
                    ts,
                    open,
                    high,
                    low,
                    close,
                    volume,
                    TradingSession.NEW_YORK
            );

            listener.onCandle(candle);
        } catch (Exception e) {
            logger.error("Error generating mock candle", e);
            listener.onError(symbol, e);
        }
    }

    /**
     * Simulate order fill.
     */
    private void simulateFill(Order order, OrderListener listener) {
        // Randomly decide if order gets filled (90% success rate)
        if (random.nextDouble() < 0.9) {
            double fillPrice;
            if (order.getType() == OrderType.LIMIT && order.getLimitPrice() != null) {
                fillPrice = order.getLimitPrice();
            } else {
                fillPrice = currentPrices.getOrDefault(order.getSymbol(), 5000.0);
            }

            listener.onOrderFilled(order, order.getQuantity(), fillPrice);
            logger.info("Mock order filled: {} @ {}", order.getOrderId(), fillPrice);
        } else {
            listener.onOrderRejected(order, "Mock rejection for testing");
            logger.info("Mock order rejected: {}", order.getOrderId());
        }

        orderListeners.remove(order.getOrderId());
    }
}
