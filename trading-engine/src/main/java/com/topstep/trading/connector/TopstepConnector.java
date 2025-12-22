package com.topstep.trading.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.topstep.trading.domain.*;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Topstep connector implementation for LIVE trading.
 *
 * This connector integrates with Topstep's trading platform through their
 * API gateway. Topstep uses different connectivity options:
 * - Rithmic (most common)
 * - CQG
 * - TopstepX/Project X
 *
 * This implementation uses a REST + WebSocket pattern that should be adapted
 * based on the specific API documentation from Topstep.
 *
 * IMPORTANT: Before using LIVE:
 * 1. Get API credentials from Topstep
 * 2. Set environment variables (see createConnector in LiveEngineRunner)
 * 3. Test thoroughly in SIM/paper mode first
 */
public class TopstepConnector implements TradingConnector {
    private static final Logger logger = LoggerFactory.getLogger(TopstepConnector.class);

    // Configuration
    private final String apiUrl;
    private final String username;
    private final String apiKey;  // API key for TopstepX authentication
    private final String accountId;

    // HTTP client
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    // WebSocket for streaming
    private WebSocket marketDataWebSocket;
    private WebSocket orderWebSocket;

    // State
    private volatile boolean connected = false;
    private String authToken;
    private final Map<String, MarketDataListener> marketDataListeners = new ConcurrentHashMap<>();
    private final Map<String, OrderListener> orderListeners = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor();

    // Candle aggregation (for converting ticks to candles if needed)
    private final Map<String, CandleAggregator> candleAggregators = new ConcurrentHashMap<>();

    /**
     * Create a TopstepConnector with credentials.
     */
    public TopstepConnector(String apiUrl, String username, String apiKey, String accountId) {
        // Ensure API URL ends with /api for TopstepX
        this.apiUrl = apiUrl.endsWith("/api") ? apiUrl : apiUrl + "/api";
        this.username = username;
        this.apiKey = apiKey;
        this.accountId = accountId;

        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

        this.objectMapper = new ObjectMapper();

        logger.info("TopstepConnector initialized for account: {}", accountId);
    }

    /**
     * Default constructor (requires environment variables).
     */
    public TopstepConnector() {
        this(
            getRequiredEnv("TOPSTEP_API_URL"),
            getRequiredEnv("TOPSTEP_USERNAME"),
            getRequiredEnv("TOPSTEP_API_KEY"),
            getRequiredEnv("TOPSTEP_ACCOUNT_ID")
        );
    }

    private static String getRequiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException("Required environment variable not set: " + name);
        }
        return value;
    }

    @Override
    public void connect() throws Exception {
        logger.info("Connecting to Topstep...");

        // Step 1: Authenticate
        authenticate();

        // Step 2: Connect WebSocket for market data
        connectMarketDataWebSocket();

        // Step 3: Connect WebSocket for orders
        connectOrderWebSocket();

        // Step 4: Start heartbeat
        startHeartbeat();

        connected = true;
        logger.info("Connected to Topstep successfully");
    }

    /**
     * Authenticate with TopstepX API using API key.
     */
    private void authenticate() throws Exception {
        logger.info("Authenticating with TopstepX...");

        // TopstepX uses /Auth/loginKey endpoint for API key authentication
        String authUrl = apiUrl + "/Auth/loginKey";
        String authBody = objectMapper.writeValueAsString(Map.of(
            "userName", username,
            "apiKey", apiKey
        ));

        logger.info("Auth URL: {}", authUrl);

        Request request = new Request.Builder()
            .url(authUrl)
            .post(RequestBody.create(authBody, MediaType.parse("application/json")))
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String body = response.body() != null ? response.body().string() : "No body";
                throw new IOException("Authentication failed: " + response.code() + " - " + body);
            }

            String responseBody = response.body().string();
            logger.debug("Auth response: {}", responseBody);
            JsonNode json = objectMapper.readTree(responseBody);

            // TopstepX returns token in different possible fields
            if (json.has("token")) {
                authToken = json.get("token").asText();
            } else if (json.has("accessToken")) {
                authToken = json.get("accessToken").asText();
            } else if (json.has("Token")) {
                authToken = json.get("Token").asText();
            } else {
                throw new IOException("No auth token in response: " + responseBody);
            }

            logger.info("Authentication successful");
        }
    }

    /**
     * Connect to market data WebSocket.
     */
    private void connectMarketDataWebSocket() {
        String wsUrl = apiUrl.replace("https://", "wss://").replace("http://", "ws://")
            + "/ws/marketdata?token=" + authToken;

        Request request = new Request.Builder().url(wsUrl).build();

        marketDataWebSocket = httpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                logger.info("Market data WebSocket connected");
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                handleMarketDataMessage(text);
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                logger.error("Market data WebSocket error: {}", t.getMessage());
                // Attempt reconnect
                if (connected) {
                    scheduleReconnect();
                }
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                logger.info("Market data WebSocket closed: {} - {}", code, reason);
            }
        });
    }

    /**
     * Connect to order WebSocket.
     */
    private void connectOrderWebSocket() {
        String wsUrl = apiUrl.replace("https://", "wss://").replace("http://", "ws://")
            + "/ws/orders?token=" + authToken + "&accountId=" + accountId;

        Request request = new Request.Builder().url(wsUrl).build();

        orderWebSocket = httpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                logger.info("Order WebSocket connected");
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                handleOrderMessage(text);
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                logger.error("Order WebSocket error: {}", t.getMessage());
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                logger.info("Order WebSocket closed: {} - {}", code, reason);
            }
        });
    }

    /**
     * Handle incoming market data message.
     */
    private void handleMarketDataMessage(String message) {
        try {
            JsonNode json = objectMapper.readTree(message);
            String type = json.has("type") ? json.get("type").asText() : "";
            String symbol = json.has("symbol") ? json.get("symbol").asText() : "";

            MarketDataListener listener = marketDataListeners.get(symbol);
            if (listener == null) return;

            // Handle different message types
            if ("tick".equals(type) || "quote".equals(type)) {
                // Aggregate ticks into candles
                CandleAggregator aggregator = candleAggregators.get(symbol);
                if (aggregator != null) {
                    double price = json.get("price").asDouble();
                    int volume = json.has("volume") ? json.get("volume").asInt() : 1;

                    Candle candle = aggregator.addTick(price, volume);
                    if (candle != null) {
                        listener.onCandle(candle);
                    }
                }
            } else if ("candle".equals(type) || "bar".equals(type)) {
                // Direct candle data
                Candle candle = new Candle(
                    symbol,
                    Instant.ofEpochMilli(json.get("timestamp").asLong()),
                    json.get("open").asDouble(),
                    json.get("high").asDouble(),
                    json.get("low").asDouble(),
                    json.get("close").asDouble(),
                    json.get("volume").asLong()
                );
                listener.onCandle(candle);
            }

        } catch (Exception e) {
            logger.error("Error processing market data: {}", e.getMessage());
        }
    }

    /**
     * Handle incoming order message.
     */
    private void handleOrderMessage(String message) {
        try {
            JsonNode json = objectMapper.readTree(message);
            String orderId = json.has("orderId") ? json.get("orderId").asText() : "";

            OrderListener listener = orderListeners.get(orderId);
            if (listener == null) return;

            // Parse order status
            String statusStr = json.has("status") ? json.get("status").asText() : "UNKNOWN";
            OrderStatus status = parseOrderStatus(statusStr);

            Double fillPrice = json.has("fillPrice") ? json.get("fillPrice").asDouble() : null;
            Integer fillQty = json.has("fillQuantity") ? json.get("fillQuantity").asInt() : null;

            listener.onOrderUpdate(orderId, status, fillPrice, fillQty);

            // Clean up completed orders
            if (status == OrderStatus.FILLED || status == OrderStatus.CANCELED || status == OrderStatus.REJECTED) {
                orderListeners.remove(orderId);
            }

        } catch (Exception e) {
            logger.error("Error processing order message: {}", e.getMessage());
        }
    }

    private OrderStatus parseOrderStatus(String status) {
        return switch (status.toUpperCase()) {
            case "PENDING", "NEW", "WORKING" -> OrderStatus.PENDING;
            case "PARTIAL", "PARTIALLY_FILLED" -> OrderStatus.PARTIALLY_FILLED;
            case "FILLED", "COMPLETE" -> OrderStatus.FILLED;
            case "CANCELED", "CANCELLED" -> OrderStatus.CANCELED;
            case "REJECTED" -> OrderStatus.REJECTED;
            default -> OrderStatus.PENDING;
        };
    }

    /**
     * Start heartbeat to keep connections alive.
     */
    private void startHeartbeat() {
        heartbeatScheduler.scheduleAtFixedRate(() -> {
            if (connected && marketDataWebSocket != null) {
                marketDataWebSocket.send("{\"type\":\"heartbeat\"}");
            }
            if (connected && orderWebSocket != null) {
                orderWebSocket.send("{\"type\":\"heartbeat\"}");
            }
        }, 30, 30, TimeUnit.SECONDS);
    }

    /**
     * Schedule reconnection attempt.
     */
    private void scheduleReconnect() {
        heartbeatScheduler.schedule(() -> {
            try {
                logger.info("Attempting to reconnect...");
                connectMarketDataWebSocket();
                // Resubscribe to all symbols
                for (String symbol : marketDataListeners.keySet()) {
                    sendSubscription(symbol);
                }
            } catch (Exception e) {
                logger.error("Reconnect failed: {}", e.getMessage());
                scheduleReconnect();
            }
        }, 5, TimeUnit.SECONDS);
    }

    @Override
    public void disconnect() {
        logger.info("Disconnecting from Topstep...");

        connected = false;

        // Close WebSockets
        if (marketDataWebSocket != null) {
            marketDataWebSocket.close(1000, "Disconnecting");
        }
        if (orderWebSocket != null) {
            orderWebSocket.close(1000, "Disconnecting");
        }

        // Shutdown heartbeat
        heartbeatScheduler.shutdown();

        // Clear state
        marketDataListeners.clear();
        orderListeners.clear();
        candleAggregators.clear();

        logger.info("Disconnected from Topstep");
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public void subscribeMarketData(String symbol, MarketDataListener listener) {
        logger.info("Subscribing to market data for: {}", symbol);

        marketDataListeners.put(symbol, listener);
        candleAggregators.put(symbol, new CandleAggregator(symbol, 60)); // 1-minute candles

        sendSubscription(symbol);
    }

    private void sendSubscription(String symbol) {
        if (marketDataWebSocket != null) {
            try {
                String subMessage = objectMapper.writeValueAsString(Map.of(
                    "type", "subscribe",
                    "symbol", symbol,
                    "dataType", "candle",
                    "interval", "1min"
                ));
                marketDataWebSocket.send(subMessage);
            } catch (Exception e) {
                logger.error("Failed to send subscription: {}", e.getMessage());
            }
        }
    }

    @Override
    public void unsubscribeMarketData(String symbol) {
        logger.info("Unsubscribing from market data for: {}", symbol);

        marketDataListeners.remove(symbol);
        candleAggregators.remove(symbol);

        if (marketDataWebSocket != null) {
            try {
                String unsubMessage = objectMapper.writeValueAsString(Map.of(
                    "type", "unsubscribe",
                    "symbol", symbol
                ));
                marketDataWebSocket.send(unsubMessage);
            } catch (Exception e) {
                logger.error("Failed to send unsubscription: {}", e.getMessage());
            }
        }
    }

    @Override
    public String submitOrder(Order order, OrderListener listener) throws Exception {
        logger.info("Submitting order: {} {} {} @ {}",
            order.getSide(), order.getQuantity(), order.getSymbol(), order.getPrice());

        // Generate order ID
        String orderId = "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        order.setOrderId(orderId);

        // Register listener
        orderListeners.put(orderId, listener);

        // Build order request
        String orderUrl = apiUrl + "/orders";
        String orderBody = objectMapper.writeValueAsString(Map.of(
            "clientOrderId", orderId,
            "accountId", accountId,
            "symbol", order.getSymbol(),
            "side", order.getSide().toString(),
            "type", order.getType().toString(),
            "quantity", order.getQuantity(),
            "price", order.getPrice(),
            "timeInForce", "DAY"
        ));

        Request request = new Request.Builder()
            .url(orderUrl)
            .header("Authorization", "Bearer " + authToken)
            .post(RequestBody.create(orderBody, MediaType.parse("application/json")))
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String body = response.body() != null ? response.body().string() : "No body";
                orderListeners.remove(orderId);
                throw new IOException("Order submission failed: " + response.code() + " - " + body);
            }

            String responseBody = response.body().string();
            JsonNode json = objectMapper.readTree(responseBody);

            // Get server-assigned order ID if different
            if (json.has("orderId")) {
                String serverOrderId = json.get("orderId").asText();
                if (!serverOrderId.equals(orderId)) {
                    orderListeners.remove(orderId);
                    orderListeners.put(serverOrderId, listener);
                    order.setOrderId(serverOrderId);
                    orderId = serverOrderId;
                }
            }

            logger.info("Order submitted successfully: {}", orderId);
            return orderId;
        }
    }

    @Override
    public void cancelOrder(String orderId) throws Exception {
        logger.info("Cancelling order: {}", orderId);

        String cancelUrl = apiUrl + "/orders/" + orderId + "/cancel";

        Request request = new Request.Builder()
            .url(cancelUrl)
            .header("Authorization", "Bearer " + authToken)
            .post(RequestBody.create("", MediaType.parse("application/json")))
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String body = response.body() != null ? response.body().string() : "No body";
                throw new IOException("Order cancellation failed: " + response.code() + " - " + body);
            }

            logger.info("Order cancelled successfully: {}", orderId);
        }
    }

    @Override
    public double getAccountBalance() throws Exception {
        logger.info("Fetching account balance...");

        String balanceUrl = apiUrl + "/accounts/" + accountId + "/balance";

        Request request = new Request.Builder()
            .url(balanceUrl)
            .header("Authorization", "Bearer " + authToken)
            .get()
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String body = response.body() != null ? response.body().string() : "No body";
                throw new IOException("Failed to get balance: " + response.code() + " - " + body);
            }

            String responseBody = response.body().string();
            JsonNode json = objectMapper.readTree(responseBody);

            // Try different field names
            double balance;
            if (json.has("balance")) {
                balance = json.get("balance").asDouble();
            } else if (json.has("equity")) {
                balance = json.get("equity").asDouble();
            } else if (json.has("availableBalance")) {
                balance = json.get("availableBalance").asDouble();
            } else {
                throw new IOException("No balance field in response");
            }

            logger.info("Account balance: ${}", String.format("%.2f", balance));
            return balance;
        }
    }

    @Override
    public String getName() {
        return "TopstepConnector";
    }

    /**
     * Helper class to aggregate ticks into candles.
     */
    private static class CandleAggregator {
        private final String symbol;
        private final int intervalSeconds;

        private Instant currentBarStart;
        private double open, high, low, close;
        private long volume;

        public CandleAggregator(String symbol, int intervalSeconds) {
            this.symbol = symbol;
            this.intervalSeconds = intervalSeconds;
        }

        public synchronized Candle addTick(double price, int tickVolume) {
            Instant now = Instant.now();
            long barStartEpoch = (now.getEpochSecond() / intervalSeconds) * intervalSeconds;
            Instant barStart = Instant.ofEpochSecond(barStartEpoch);

            // Check if we're in a new bar
            if (currentBarStart == null || !barStart.equals(currentBarStart)) {
                Candle completedCandle = null;

                // Complete previous candle if exists
                if (currentBarStart != null) {
                    completedCandle = new Candle(symbol, currentBarStart, open, high, low, close, volume);
                }

                // Start new candle
                currentBarStart = barStart;
                open = price;
                high = price;
                low = price;
                close = price;
                volume = tickVolume;

                return completedCandle;
            } else {
                // Update current candle
                high = Math.max(high, price);
                low = Math.min(low, price);
                close = price;
                volume += tickVolume;

                return null;
            }
        }
    }
}
