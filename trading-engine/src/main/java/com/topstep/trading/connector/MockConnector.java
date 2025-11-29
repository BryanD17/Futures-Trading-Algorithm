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

        // Start generating mock candles every 5 seconds
        scheduler.scheduleAtFixedRate(() -> generateMockCandle(symbol), 0, 5, TimeUnit.SECONDS);
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

            // Random walk with small increments
            double priceChange = (random.nextDouble() - 0.5) * 10.0;
            double open = basePrice;
            double close = basePrice + priceChange;
            double high = Math.max(open, close) + random.nextDouble() * 5.0;
            double low = Math.min(open, close) - random.nextDouble() * 5.0;
            long volume = 1000 + random.nextInt(9000);

            // Update current price
            currentPrices.put(symbol, close);

            Candle candle = new Candle(
                    symbol,
                    Instant.now(),
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
