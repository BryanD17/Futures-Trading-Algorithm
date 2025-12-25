package com.topstep.trading.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.topstep.trading.domain.*;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Topstep connector implementation for LIVE trading via TopstepX API.
 *
 * This connector integrates with TopstepX's trading platform through their
 * REST API with polling for market data (SignalR not implemented).
 *
 * IMPORTANT: Before using LIVE:
 * 1. Get API credentials from Topstep
 * 2. Set environment variables (TOPSTEP_API_URL, TOPSTEP_USERNAME, TOPSTEP_API_KEY, TOPSTEP_ACCOUNT_ID)
 * 3. Test thoroughly in SIM/paper mode first
 */
public class TopstepConnector implements TradingConnector {
    private static final Logger logger = LoggerFactory.getLogger(TopstepConnector.class);

    // Bar unit constants for TopstepX API
    private static final int BAR_UNIT_MINUTE = 2;
    private static final int BAR_UNIT_HOUR = 3;
    private static final int BAR_UNIT_DAY = 4;

    // Configuration
    private final String apiUrl;
    private final String username;
    private final String apiKey;
    private final String accountId;
    private final boolean useLiveData;  // true for LIVE trading, false for SIM data

    // HTTP client
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    // State
    private volatile boolean connected = false;
    private String authToken;
    private final Map<String, MarketDataListener> marketDataListeners = new ConcurrentHashMap<>();
    private final Map<String, OrderListener> orderListeners = new ConcurrentHashMap<>();
    private final Map<String, String> symbolToContractId = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastBarTimestamp = new ConcurrentHashMap<>();

    // Schedulers
    private final ScheduledExecutorService marketDataPoller = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor();

    // Polling interval in seconds (30 seconds for near real-time data)
    private static final int POLL_INTERVAL_SECONDS = 30;

    /**
     * Create a TopstepConnector with credentials.
     */
    public TopstepConnector(String apiUrl, String username, String apiKey, String accountId) {
        this(apiUrl, username, apiKey, accountId, true);  // Default to LIVE data
    }

    /**
     * Create a TopstepConnector with credentials and data mode.
     * @param useLiveData true for real market data (LIVE), false for simulated data
     */
    public TopstepConnector(String apiUrl, String username, String apiKey, String accountId, boolean useLiveData) {
        this.apiUrl = apiUrl.endsWith("/api") ? apiUrl : apiUrl + "/api";
        this.username = username;
        this.apiKey = apiKey;
        this.accountId = accountId;
        this.useLiveData = useLiveData;

        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

        this.objectMapper = new ObjectMapper();

        logger.info("TopstepConnector initialized for account: {} (live data: {})", accountId, useLiveData);
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

        // Step 2: Start heartbeat to keep token fresh
        startHeartbeat();

        connected = true;
        logger.info("Connected to Topstep successfully");
    }

    /**
     * Authenticate with TopstepX API using API key.
     */
    private void authenticate() throws Exception {
        logger.info("Authenticating with TopstepX...");

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
     * Search for a contract by symbol and get its contract ID.
     * ES -> CON.F.US.EP.H25 (current front month)
     */
    private String searchContract(String symbol) throws Exception {
        logger.info("Searching for contract: {}", symbol);

        String searchUrl = apiUrl + "/Contract/search";
        String searchBody = objectMapper.writeValueAsString(Map.of(
            "searchText", symbol,
            "live", useLiveData
        ));

        Request request = new Request.Builder()
            .url(searchUrl)
            .header("Authorization", "Bearer " + authToken)
            .post(RequestBody.create(searchBody, MediaType.parse("application/json")))
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String body = response.body() != null ? response.body().string() : "No body";
                throw new IOException("Contract search failed: " + response.code() + " - " + body);
            }

            String responseBody = response.body().string();
            logger.debug("Contract search response: {}", responseBody);
            JsonNode json = objectMapper.readTree(responseBody);

            // TopstepX returns { "contracts": [...], "success": true }
            JsonNode contracts = json.has("contracts") ? json.get("contracts") : json;

            if (contracts.isArray() && contracts.size() > 0) {
                // Find the best matching contract (front month futures)
                for (JsonNode contract : contracts) {
                    String contractId = contract.has("id") ? contract.get("id").asText() : "";
                    String name = contract.has("name") ? contract.get("name").asText() : "";
                    String description = contract.has("description") ? contract.get("description").asText() : "";

                    // Match based on symbol patterns
                    // ES futures: CON.F.US.EP.xxx or similar
                    // NQ futures: CON.F.US.ENQ.xxx or similar
                    if (!contractId.isEmpty()) {
                        logger.info("Found contract: {} - {} ({})", contractId, name, description);
                        return contractId;
                    }
                }
            }

            // If no contracts found via search, try common contract ID patterns
            String fallbackContractId = getFallbackContractId(symbol);
            if (fallbackContractId != null) {
                logger.info("Using fallback contract ID: {}", fallbackContractId);
                return fallbackContractId;
            }

            throw new IOException("No contracts found for symbol: " + symbol);
        }
    }

    /**
     * Get a fallback contract ID based on common patterns.
     * Contract IDs follow pattern: CON.F.US.{root}.{month}{year}
     * Month codes: F=Jan, G=Feb, H=Mar, J=Apr, K=May, M=Jun, N=Jul, Q=Aug, U=Sep, V=Oct, X=Nov, Z=Dec
     */
    private String getFallbackContractId(String symbol) {
        // Determine current front month
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        int month = now.getMonthValue();
        int year = now.getYear() % 100; // 2-digit year

        // Futures typically roll to next quarter: Mar (H), Jun (M), Sep (U), Dec (Z)
        String monthCode;
        if (month <= 3) {
            monthCode = "H"; // March
        } else if (month <= 6) {
            monthCode = "M"; // June
        } else if (month <= 9) {
            monthCode = "U"; // September
        } else {
            monthCode = "Z"; // December
        }

        // If we're past the contract month, move to next quarter
        if ((month == 3 && now.getDayOfMonth() > 15) ||
            (month == 6 && now.getDayOfMonth() > 15) ||
            (month == 9 && now.getDayOfMonth() > 15) ||
            (month == 12 && now.getDayOfMonth() > 15)) {
            if (monthCode.equals("H")) monthCode = "M";
            else if (monthCode.equals("M")) monthCode = "U";
            else if (monthCode.equals("U")) monthCode = "Z";
            else {
                monthCode = "H";
                year++;
            }
        }

        // Map common symbols to their root codes
        String root = switch (symbol.toUpperCase()) {
            case "ES" -> "EP";      // E-mini S&P 500
            case "NQ" -> "ENQ";     // E-mini NASDAQ 100
            case "MES" -> "MES";    // Micro E-mini S&P 500
            case "MNQ" -> "MNQ";    // Micro E-mini NASDAQ 100
            case "YM" -> "YM";      // E-mini Dow
            case "RTY" -> "RTY";    // E-mini Russell 2000
            case "CL" -> "CL";      // Crude Oil
            case "GC" -> "GC";      // Gold
            default -> null;
        };

        if (root != null) {
            return String.format("CON.F.US.%s.%s%02d", root, monthCode, year);
        }

        return null;
    }

    /**
     * Fetch historical bars from TopstepX API.
     */
    private void fetchBars(String symbol, String contractId) {
        try {
            // Calculate time range - last 10 minutes to get recent data
            ZonedDateTime endTime = ZonedDateTime.now(ZoneOffset.UTC);
            ZonedDateTime startTime = endTime.minusMinutes(10);

            String barsUrl = apiUrl + "/History/retrieveBars";

            Map<String, Object> requestMap = new HashMap<>();
            requestMap.put("contractId", contractId);
            requestMap.put("live", useLiveData);
            requestMap.put("startTime", startTime.format(DateTimeFormatter.ISO_INSTANT));
            requestMap.put("endTime", endTime.format(DateTimeFormatter.ISO_INSTANT));
            requestMap.put("unit", BAR_UNIT_MINUTE);
            requestMap.put("unitNumber", 1);
            requestMap.put("limit", 10);
            requestMap.put("includePartialBar", true);

            String requestBody = objectMapper.writeValueAsString(requestMap);

            Request request = new Request.Builder()
                .url(barsUrl)
                .header("Authorization", "Bearer " + authToken)
                .post(RequestBody.create(requestBody, MediaType.parse("application/json")))
                .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String body = response.body() != null ? response.body().string() : "No body";
                    logger.error("Failed to fetch bars: {} - {}", response.code(), body);
                    return;
                }

                String responseBody = response.body().string();
                logger.debug("Bars response: {}", responseBody);
                JsonNode json = objectMapper.readTree(responseBody);

                // Extract bars array
                JsonNode bars = json.has("bars") ? json.get("bars") : json;
                if (!bars.isArray()) {
                    logger.warn("No bars array in response");
                    return;
                }

                MarketDataListener listener = marketDataListeners.get(symbol);
                if (listener == null) {
                    logger.warn("No listener registered for symbol: {}", symbol);
                    return;
                }

                Instant lastTimestamp = lastBarTimestamp.get(symbol);
                int newBarsCount = 0;

                // Process each bar
                for (JsonNode bar : bars) {
                    try {
                        // Parse timestamp - try different field names
                        Instant timestamp;
                        if (bar.has("t")) {
                            timestamp = Instant.parse(bar.get("t").asText());
                        } else if (bar.has("timestamp")) {
                            timestamp = Instant.parse(bar.get("timestamp").asText());
                        } else if (bar.has("time")) {
                            timestamp = Instant.ofEpochMilli(bar.get("time").asLong());
                        } else {
                            logger.warn("No timestamp in bar: {}", bar);
                            continue;
                        }

                        // Skip bars we've already processed
                        if (lastTimestamp != null && !timestamp.isAfter(lastTimestamp)) {
                            continue;
                        }

                        // Parse OHLCV - try different field names
                        double open = getDoubleField(bar, "o", "open");
                        double high = getDoubleField(bar, "h", "high");
                        double low = getDoubleField(bar, "l", "low");
                        double close = getDoubleField(bar, "c", "close");
                        long volume = getLongField(bar, "v", "volume");

                        // Create candle
                        Candle candle = new Candle(
                            symbol,
                            timestamp,
                            open,
                            high,
                            low,
                            close,
                            volume
                        );

                        // Deliver to listener
                        listener.onCandle(candle);
                        newBarsCount++;

                        // Update last timestamp
                        if (lastTimestamp == null || timestamp.isAfter(lastTimestamp)) {
                            lastBarTimestamp.put(symbol, timestamp);
                        }

                    } catch (Exception e) {
                        logger.error("Error parsing bar: {}", e.getMessage());
                    }
                }

                if (newBarsCount > 0) {
                    logger.info("Delivered {} new candles for {}", newBarsCount, symbol);
                }
            }
        } catch (Exception e) {
            logger.error("Error fetching bars for {}: {}", symbol, e.getMessage());
        }
    }

    private double getDoubleField(JsonNode node, String... fieldNames) {
        for (String name : fieldNames) {
            if (node.has(name)) {
                return node.get(name).asDouble();
            }
        }
        return 0.0;
    }

    private long getLongField(JsonNode node, String... fieldNames) {
        for (String name : fieldNames) {
            if (node.has(name)) {
                return node.get(name).asLong();
            }
        }
        return 0L;
    }

    /**
     * Start heartbeat to periodically refresh token if needed.
     */
    private void startHeartbeat() {
        heartbeatScheduler.scheduleAtFixedRate(() -> {
            if (connected) {
                // Re-authenticate periodically to keep token fresh (every 30 minutes)
                try {
                    authenticate();
                    logger.debug("Token refreshed successfully");
                } catch (Exception e) {
                    logger.error("Failed to refresh token: {}", e.getMessage());
                }
            }
        }, 30, 30, TimeUnit.MINUTES);
    }

    /**
     * Start polling for market data.
     */
    private void startMarketDataPolling(String symbol, String contractId) {
        logger.info("Starting market data polling for {} (contract: {}), interval: {}s",
            symbol, contractId, POLL_INTERVAL_SECONDS);

        // Initial fetch immediately
        fetchBars(symbol, contractId);

        // Schedule periodic polling
        marketDataPoller.scheduleAtFixedRate(
            () -> fetchBars(symbol, contractId),
            POLL_INTERVAL_SECONDS,
            POLL_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        );
    }

    @Override
    public void disconnect() {
        logger.info("Disconnecting from Topstep...");

        connected = false;

        // Shutdown schedulers
        marketDataPoller.shutdown();
        heartbeatScheduler.shutdown();

        try {
            marketDataPoller.awaitTermination(5, TimeUnit.SECONDS);
            heartbeatScheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Clear state
        marketDataListeners.clear();
        orderListeners.clear();
        symbolToContractId.clear();
        lastBarTimestamp.clear();

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

        // Search for contract ID and start polling
        try {
            String contractId = searchContract(symbol);
            symbolToContractId.put(symbol, contractId);
            startMarketDataPolling(symbol, contractId);
        } catch (Exception e) {
            logger.error("Failed to subscribe to market data for {}: {}", symbol, e.getMessage());
            listener.onError(symbol, e);
        }
    }

    @Override
    public void unsubscribeMarketData(String symbol) {
        logger.info("Unsubscribing from market data for: {}", symbol);

        marketDataListeners.remove(symbol);
        symbolToContractId.remove(symbol);
        lastBarTimestamp.remove(symbol);

        // Note: The poller will stop processing this symbol since listener is removed
    }

    @Override
    public String submitOrder(Order order, OrderListener listener) throws Exception {
        logger.info("Submitting order: {} {} {} @ {}",
            order.getSide(), order.getQuantity(), order.getSymbol(), order.getPrice());

        // Get contract ID for symbol
        String contractId = symbolToContractId.get(order.getSymbol());
        if (contractId == null) {
            contractId = searchContract(order.getSymbol());
            symbolToContractId.put(order.getSymbol(), contractId);
        }

        // Get account ID (numeric)
        String numericAccountId = getNumericAccountId();

        // Generate order ID
        String clientOrderId = "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        order.setOrderId(clientOrderId);

        // Register listener
        orderListeners.put(clientOrderId, listener);

        // Build order request for TopstepX
        String orderUrl = apiUrl + "/Order/place";

        Map<String, Object> orderMap = new HashMap<>();
        orderMap.put("accountId", Integer.parseInt(numericAccountId));
        orderMap.put("contractId", contractId);
        // ProjectX Gateway: type 1=Limit, 2=Market (per official docs)
        orderMap.put("type", order.getType() == OrderType.MARKET ? 2 : 1);
        // ProjectX Gateway: side 0=Buy/Bid, 1=Sell/Ask (per official docs)
        orderMap.put("side", order.getSide() == OrderSide.BUY ? 0 : 1);
        orderMap.put("size", order.getQuantity());
        if (order.getType() == OrderType.LIMIT && order.getLimitPrice() != null) {
            orderMap.put("limitPrice", order.getLimitPrice());
        }

        String orderBody = objectMapper.writeValueAsString(orderMap);
        logger.info("Order request body: {}", orderBody);

        Request request = new Request.Builder()
            .url(orderUrl)
            .header("Authorization", "Bearer " + authToken)
            .post(RequestBody.create(orderBody, MediaType.parse("application/json")))
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String body = response.body() != null ? response.body().string() : "No body";
                orderListeners.remove(clientOrderId);
                throw new IOException("Order submission failed: " + response.code() + " - " + body);
            }

            String responseBody = response.body().string();
            logger.info("Order response: {}", responseBody);
            JsonNode json = objectMapper.readTree(responseBody);

            // Check if order was successful
            boolean success = json.has("success") ? json.get("success").asBoolean() : true;
            if (!success) {
                String errorMsg = json.has("errorMessage") ? json.get("errorMessage").asText() : "Unknown error";
                int errorCode = json.has("errorCode") ? json.get("errorCode").asInt() : -1;
                orderListeners.remove(clientOrderId);
                throw new IOException("Order rejected by TopstepX: " + errorMsg + " (code: " + errorCode + ")");
            }

            // Get server order ID
            String serverId = clientOrderId;
            if (json.has("orderId") && !json.get("orderId").isNull()) {
                serverId = json.get("orderId").asText();
                if (!serverId.equals(clientOrderId)) {
                    orderListeners.remove(clientOrderId);
                    orderListeners.put(serverId, listener);
                    order.setOrderId(serverId);
                }
            } else if (json.has("id") && !json.get("id").isNull()) {
                serverId = json.get("id").asText();
                orderListeners.remove(clientOrderId);
                orderListeners.put(serverId, listener);
                order.setOrderId(serverId);
            }

            logger.info("Order submitted successfully: {}", serverId);

            // Notify listener of submission
            listener.onOrderSubmitted(order);

            return serverId;
        }
    }

    /**
     * Get the numeric account ID from the TopstepX account.
     */
    private String getNumericAccountId() throws Exception {
        String accountUrl = apiUrl + "/Account/search";
        String requestBody = objectMapper.writeValueAsString(Map.of(
            "onlyActiveAccounts", true
        ));

        Request request = new Request.Builder()
            .url(accountUrl)
            .header("Authorization", "Bearer " + authToken)
            .post(RequestBody.create(requestBody, MediaType.parse("application/json")))
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Failed to get account: " + response.code());
            }

            String responseBody = response.body().string();
            JsonNode json = objectMapper.readTree(responseBody);
            JsonNode accounts = json.has("accounts") ? json.get("accounts") : json;

            if (accounts.isArray() && accounts.size() > 0) {
                for (JsonNode account : accounts) {
                    String accName = account.has("name") ? account.get("name").asText() : "";
                    if (accName.contains(accountId) || accountId.contains(accName) || accounts.size() == 1) {
                        if (account.has("id")) {
                            return account.get("id").asText();
                        }
                    }
                }
            }

            throw new IOException("Could not find numeric account ID");
        }
    }

    @Override
    public void cancelOrder(String orderId) throws Exception {
        logger.info("Cancelling order: {}", orderId);

        // Parse order ID - must be numeric (server-assigned ID)
        long numericOrderId;
        try {
            numericOrderId = Long.parseLong(orderId);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "Cannot cancel order with non-numeric ID: " + orderId +
                ". Use the server-assigned order ID from submitOrder response.");
        }

        String cancelUrl = apiUrl + "/Order/cancel";
        String cancelBody = objectMapper.writeValueAsString(Map.of(
            "orderId", numericOrderId
        ));

        Request request = new Request.Builder()
            .url(cancelUrl)
            .header("Authorization", "Bearer " + authToken)
            .post(RequestBody.create(cancelBody, MediaType.parse("application/json")))
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String body = response.body() != null ? response.body().string() : "No body";
                throw new IOException("Order cancellation failed: " + response.code() + " - " + body);
            }

            logger.info("Order cancelled successfully: {}", orderId);

            // Notify listener
            OrderListener listener = orderListeners.remove(orderId);
            if (listener != null) {
                Order dummyOrder = Order.builder()
                    .orderId(orderId)
                    .symbol("UNKNOWN")
                    .side(OrderSide.BUY)
                    .type(OrderType.MARKET)
                    .quantity(0)
                    .build();
                listener.onOrderCanceled(dummyOrder);
            }
        }
    }

    @Override
    public double getAccountBalance() throws Exception {
        logger.info("Fetching account balance...");

        String accountUrl = apiUrl + "/Account/search";
        String requestBody = objectMapper.writeValueAsString(Map.of(
            "onlyActiveAccounts", true
        ));

        Request request = new Request.Builder()
            .url(accountUrl)
            .header("Authorization", "Bearer " + authToken)
            .post(RequestBody.create(requestBody, MediaType.parse("application/json")))
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String body = response.body() != null ? response.body().string() : "No body";
                throw new IOException("Failed to get balance: " + response.code() + " - " + body);
            }

            String responseBody = response.body().string();
            logger.debug("Account response: {}", responseBody);
            JsonNode json = objectMapper.readTree(responseBody);

            double balance = 0;
            JsonNode accountsArray = json.has("accounts") ? json.get("accounts") : json;

            if (accountsArray.isArray()) {
                for (JsonNode account : accountsArray) {
                    String accId = account.has("id") ? account.get("id").asText() : "";
                    String accName = account.has("name") ? account.get("name").asText() : "";

                    if (accId.equals(accountId) || accName.contains(accountId) ||
                        accountId.contains(accId) || accountId.contains(accName) || accountsArray.size() == 1) {

                        if (account.has("balance")) {
                            balance = account.get("balance").asDouble();
                        } else if (account.has("accountBalance")) {
                            balance = account.get("accountBalance").asDouble();
                        } else if (account.has("equity")) {
                            balance = account.get("equity").asDouble();
                        }

                        if (balance > 0) {
                            logger.info("Found account: {} with balance: ${}", accName, balance);
                            break;
                        }
                    }
                }
            }

            if (balance == 0) {
                logger.warn("Could not find balance in response, using default");
                balance = 50000;
            }

            logger.info("Account balance: ${}", String.format("%.2f", balance));
            return balance;
        }
    }

    @Override
    public String getName() {
        return "TopstepConnector";
    }
}
