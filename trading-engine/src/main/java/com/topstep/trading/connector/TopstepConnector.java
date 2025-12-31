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
    private boolean useLiveData;  // true for LIVE trading, false for SIM data (auto-detected)

    // Discovered contracts cache (symbol name -> contract ID)
    private final Map<String, String> discoveredContracts = new ConcurrentHashMap<>();
    private volatile boolean contractsDiscovered = false;

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

    // Track pending orders for fill status polling
    private final Map<String, PendingOrder> pendingOrders = new ConcurrentHashMap<>();

    // Schedulers - use sized thread pool for market data to handle multiple symbols
    private final ScheduledExecutorService marketDataPoller = Executors.newScheduledThreadPool(4);
    private final ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService orderStatusPoller = Executors.newSingleThreadScheduledExecutor();

    // Track subscription status per symbol
    private final Map<String, Boolean> subscriptionStatus = new ConcurrentHashMap<>();

    // Polling interval in seconds (30 seconds for near real-time data)
    private static final int POLL_INTERVAL_SECONDS = 30;
    // Order status polling interval (faster - every 2 seconds to detect fills quickly)
    private static final int ORDER_POLL_INTERVAL_SECONDS = 2;

    /**
     * Tracks a pending order awaiting fill confirmation.
     */
    private static class PendingOrder {
        final String orderId;
        final String symbol;
        final int quantity;
        final OrderSide side;
        final double limitPrice;
        final OrderListener listener;
        final Instant submittedAt;

        PendingOrder(String orderId, String symbol, int quantity, OrderSide side,
                    double limitPrice, OrderListener listener) {
            this.orderId = orderId;
            this.symbol = symbol;
            this.quantity = quantity;
            this.side = side;
            this.limitPrice = limitPrice;
            this.listener = listener;
            this.submittedAt = Instant.now();
        }
    }

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

        // Step 3: Start order status polling to detect fills
        startOrderStatusPolling();

        logger.info("Connected to Topstep successfully");
    }

    /**
     * Start polling for order status updates.
     * This detects when orders are filled and triggers callbacks.
     */
    private void startOrderStatusPolling() {
        orderStatusPoller.scheduleAtFixedRate(() -> {
            try {
                pollOrderStatuses();
            } catch (Exception e) {
                logger.error("Error polling order statuses: {}", e.getMessage());
            }
        }, ORDER_POLL_INTERVAL_SECONDS, ORDER_POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
        logger.info("Order status polling started (every {}s)", ORDER_POLL_INTERVAL_SECONDS);
    }

    /**
     * Poll for order status updates and notify listeners of fills.
     */
    private void pollOrderStatuses() {
        if (pendingOrders.isEmpty()) {
            return; // No orders to check
        }

        try {
            // Get numeric account ID
            String numericAccountId = getNumericAccountId();

            // Search for recent orders
            String searchUrl = apiUrl + "/Order/search";
            Map<String, Object> searchRequest = new HashMap<>();
            searchRequest.put("accountId", Integer.parseInt(numericAccountId));
            searchRequest.put("startTimestamp", Instant.now().minusSeconds(24 * 60 * 60).toString());
            searchRequest.put("endTimestamp", Instant.now().toString());

            String requestBody = objectMapper.writeValueAsString(searchRequest);

            Request request = new Request.Builder()
                .url(searchUrl)
                .header("Authorization", "Bearer " + authToken)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(requestBody, MediaType.parse("application/json")))
                .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    logger.warn("Order search failed: {}", response.code());
                    return;
                }

                String responseBody = response.body().string();
                JsonNode json = objectMapper.readTree(responseBody);

                // Parse orders array
                JsonNode ordersNode = json.has("orders") ? json.get("orders") : json;
                if (!ordersNode.isArray()) {
                    return;
                }

                // Check each order against pending orders
                for (JsonNode orderNode : ordersNode) {
                    String orderId = orderNode.has("orderId")
                        ? orderNode.get("orderId").asText()
                        : (orderNode.has("id") ? orderNode.get("id").asText() : null);

                    if (orderId == null) continue;

                    PendingOrder pending = pendingOrders.get(orderId);
                    if (pending == null) continue;

                    // Check order status (2 = Filled, 3 = Cancelled)
                    int status = orderNode.has("status") ? orderNode.get("status").asInt() : 0;

                    if (status == 2) { // Filled
                        double fillPrice = orderNode.has("filledPrice")
                            ? orderNode.get("filledPrice").asDouble()
                            : (orderNode.has("fillPrice")
                                ? orderNode.get("fillPrice").asDouble()
                                : (orderNode.has("avgFillPrice")
                                    ? orderNode.get("avgFillPrice").asDouble()
                                    : pending.limitPrice));

                        int fillQty = orderNode.has("fillVolume")
                            ? orderNode.get("fillVolume").asInt()
                            : (orderNode.has("filledSize")
                                ? orderNode.get("filledSize").asInt()
                                : pending.quantity);

                        logger.info("Order {} FILLED: {} {} @ {}",
                            orderId, pending.symbol, fillQty, fillPrice);

                        // Notify listener
                        if (pending.listener != null) {
                            pending.listener.onOrderUpdate(orderId, OrderStatus.FILLED, fillPrice, fillQty);
                        }

                        // Remove from pending
                        pendingOrders.remove(orderId);
                        orderListeners.remove(orderId);

                    } else if (status == 3) { // Cancelled
                        logger.info("Order {} CANCELLED", orderId);

                        if (pending.listener != null) {
                            pending.listener.onOrderUpdate(orderId, OrderStatus.CANCELED, null, null);
                        }

                        pendingOrders.remove(orderId);
                        orderListeners.remove(orderId);

                    } else if (status == 5) { // Rejected
                        logger.info("Order {} REJECTED", orderId);

                        if (pending.listener != null) {
                            pending.listener.onOrderUpdate(orderId, OrderStatus.REJECTED, null, null);
                        }

                        pendingOrders.remove(orderId);
                        orderListeners.remove(orderId);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error in pollOrderStatuses: {}", e.getMessage());
        }
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
     * Uses auto-detection to determine SIM vs LIVE mode.
     */
    private String searchContract(String symbol) throws Exception {
        logger.info("Searching for contract: {}", symbol);

        // First, discover all available contracts and auto-detect SIM/LIVE mode
        if (!contractsDiscovered) {
            discoverAndCacheContracts();
        }

        // Check if we have a cached contract for this symbol
        String cachedContractId = findCachedContractForSymbol(symbol);
        if (cachedContractId != null) {
            logger.info("Using cached contract for {}: {}", symbol, cachedContractId);
            return cachedContractId;
        }

        // If not in cache, try fallback contract ID with correct ProjectX format
        String fallbackContractId = getFallbackContractId(symbol);
        if (fallbackContractId != null) {
            logger.info("Using fallback contract ID: {}", fallbackContractId);
            return fallbackContractId;
        }

        throw new IOException("No contracts found for symbol: " + symbol);
    }

    /**
     * Find a cached contract ID for a symbol by matching name patterns.
     */
    private String findCachedContractForSymbol(String symbol) {
        // Direct match by symbol name pattern
        // Contracts have names like "ESH6", "NQH6", "6EH6", "CLG6", etc.
        String upperSymbol = symbol.toUpperCase();

        for (Map.Entry<String, String> entry : discoveredContracts.entrySet()) {
            String contractName = entry.getKey().toUpperCase();
            String contractId = entry.getValue();

            // Match by symbol prefix (e.g., "ES" matches "ESH6", "6E" matches "6EH6")
            if (contractName.startsWith(upperSymbol)) {
                return contractId;
            }
        }

        return null;
    }

    /**
     * Discover all available contracts and cache them.
     * Auto-detects SIM vs LIVE mode based on which returns contracts.
     */
    private void discoverAndCacheContracts() {
        logger.info("=== DISCOVERING AVAILABLE CONTRACTS (AUTO-DETECT MODE) ===");

        // First try with current useLiveData setting
        int liveContracts = tryDiscoverContracts(useLiveData);

        if (liveContracts == 0) {
            // No contracts found with current setting, try the opposite
            boolean oppositeMode = !useLiveData;
            logger.info("No contracts found with live={}. Trying live={}...", useLiveData, oppositeMode);

            int simContracts = tryDiscoverContracts(oppositeMode);

            if (simContracts > 0) {
                // Found contracts with opposite mode - switch to it!
                logger.warn("*** AUTO-SWITCHING TO {} MODE ***", oppositeMode ? "LIVE" : "SIM");
                logger.warn("Your account appears to be a {} account. Using live={} for all requests.",
                    oppositeMode ? "LIVE" : "SIM/Combine", oppositeMode);
                useLiveData = oppositeMode;
            } else {
                logger.error("No contracts found with either live=true or live=false!");
                logger.error("Please verify your TopstepX API credentials and account status.");
            }
        }

        contractsDiscovered = true;
        logger.info("=== CONTRACT DISCOVERY COMPLETE (live={}, {} contracts cached) ===",
            useLiveData, discoveredContracts.size());
    }

    /**
     * Try to discover contracts with the given live flag.
     * Returns the number of contracts found and caches them.
     */
    private int tryDiscoverContracts(boolean liveFlag) {
        try {
            String searchUrl = apiUrl + "/Contract/search";
            String searchBody = objectMapper.writeValueAsString(Map.of(
                "searchText", "",
                "live", liveFlag
            ));

            Request request = new Request.Builder()
                .url(searchUrl)
                .header("Authorization", "Bearer " + authToken)
                .post(RequestBody.create(searchBody, MediaType.parse("application/json")))
                .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    String responseBody = response.body().string();
                    JsonNode json = objectMapper.readTree(responseBody);
                    JsonNode contracts = json.has("contracts") ? json.get("contracts") : json;

                    if (contracts.isArray() && contracts.size() > 0) {
                        logger.info("FOUND {} contracts with live={}:", contracts.size(), liveFlag);

                        // Cache all contracts
                        discoveredContracts.clear();
                        for (JsonNode contract : contracts) {
                            String id = contract.has("id") ? contract.get("id").asText() : "";
                            String name = contract.has("name") ? contract.get("name").asText() : "";
                            if (!id.isEmpty() && !name.isEmpty()) {
                                discoveredContracts.put(name, id);
                                logger.info("  Cached: {} -> {}", name, id);
                            }
                        }

                        return contracts.size();
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Error discovering contracts with live={}: {}", liveFlag, e.getMessage());
        }

        return 0;
    }

    /**
     * Get a fallback contract ID based on ProjectX contract patterns.
     * Uses CORRECT ProjectX root codes discovered from the API.
     * Contract IDs follow pattern: CON.F.US.{root}.{month}{year}
     * Month codes: F=Jan, G=Feb, H=Mar, J=Apr, K=May, M=Jun, N=Jul, Q=Aug, U=Sep, V=Oct, X=Nov, Z=Dec
     */
    private String getFallbackContractId(String symbol) {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        int month = now.getMonthValue();
        int year = now.getYear() % 100; // 2-digit year

        // Different products have different expiration patterns:
        // - Index futures (ES, NQ): Quarterly (H, M, U, Z)
        // - Energy (CL, NG): Monthly
        // - Metals (GC, SI): Specific months (Feb, Apr, Jun, Aug, Oct, Dec for GC)
        // - Currencies: Quarterly (H, M, U, Z)

        String monthCode;
        String root;

        switch (symbol.toUpperCase()) {
            // INDEX FUTURES - Quarterly expiration
            case "ES":
                root = "EP";
                monthCode = getQuarterlyMonthCode(month, now.getDayOfMonth());
                if (month == 12 && now.getDayOfMonth() > 15) year++;
                break;
            case "NQ":
                root = "ENQ";
                monthCode = getQuarterlyMonthCode(month, now.getDayOfMonth());
                if (month == 12 && now.getDayOfMonth() > 15) year++;
                break;
            case "YM":
                root = "YM";
                monthCode = getQuarterlyMonthCode(month, now.getDayOfMonth());
                if (month == 12 && now.getDayOfMonth() > 15) year++;
                break;
            case "RTY":
                root = "RTY";
                monthCode = getQuarterlyMonthCode(month, now.getDayOfMonth());
                if (month == 12 && now.getDayOfMonth() > 15) year++;
                break;

            // MICRO INDEX - Quarterly
            case "MES":
                root = "MES";
                monthCode = getQuarterlyMonthCode(month, now.getDayOfMonth());
                if (month == 12 && now.getDayOfMonth() > 15) year++;
                break;
            case "MNQ":
                root = "MNQ";
                monthCode = getQuarterlyMonthCode(month, now.getDayOfMonth());
                if (month == 12 && now.getDayOfMonth() > 15) year++;
                break;

            // ENERGY - Monthly expiration (use next month's contract)
            case "CL":
                root = "CLE";  // ProjectX uses CLE, not CL
                monthCode = getNextMonthCode(month);
                if (month == 12) year++;
                break;
            case "NG":
                root = "NGE";  // ProjectX uses NGE, not NG
                monthCode = getNextMonthCode(month);
                if (month == 12) year++;
                break;
            case "HO":  // Heating Oil
                root = "HOE";
                monthCode = getNextMonthCode(month);
                if (month == 12) year++;
                break;

            // METALS - Specific expiration months
            case "GC":
                root = "GCE";  // ProjectX uses GCE, not GC
                monthCode = getGoldMonthCode(month);
                // Gold trades Feb, Apr, Jun, Aug, Oct, Dec
                break;
            case "SI":
                root = "SIE";  // ProjectX uses SIE, not SI
                monthCode = getQuarterlyMonthCode(month, now.getDayOfMonth());
                if (month == 12 && now.getDayOfMonth() > 15) year++;
                break;

            // CURRENCY FUTURES - Quarterly (using CORRECT ProjectX roots!)
            case "6E":
                root = "EU6";  // ProjectX uses EU6, not E6
                monthCode = getQuarterlyMonthCode(month, now.getDayOfMonth());
                if (month == 12 && now.getDayOfMonth() > 15) year++;
                break;
            case "6J":
                root = "JY6";  // ProjectX uses JY6, not J6
                monthCode = getQuarterlyMonthCode(month, now.getDayOfMonth());
                if (month == 12 && now.getDayOfMonth() > 15) year++;
                break;
            case "6B":
                root = "BP6";  // ProjectX uses BP6, not B6
                monthCode = getQuarterlyMonthCode(month, now.getDayOfMonth());
                if (month == 12 && now.getDayOfMonth() > 15) year++;
                break;
            case "6C":
                root = "CA6";  // ProjectX uses CA6, not C6
                monthCode = getQuarterlyMonthCode(month, now.getDayOfMonth());
                if (month == 12 && now.getDayOfMonth() > 15) year++;
                break;
            case "6A":
                root = "DA6";  // ProjectX uses DA6, not A6
                monthCode = getQuarterlyMonthCode(month, now.getDayOfMonth());
                if (month == 12 && now.getDayOfMonth() > 15) year++;
                break;

            default:
                logger.warn("No fallback contract ID mapping for symbol: {}", symbol);
                return null;
        }

        String contractId = String.format("CON.F.US.%s.%s%02d", root, monthCode, year);
        logger.info("Generated fallback contract ID for {}: {} (using ProjectX root: {})",
            symbol, contractId, root);
        return contractId;
    }

    /**
     * Get quarterly month code (H=Mar, M=Jun, U=Sep, Z=Dec).
     */
    private String getQuarterlyMonthCode(int currentMonth, int dayOfMonth) {
        if (currentMonth <= 3) {
            return (currentMonth == 3 && dayOfMonth > 15) ? "M" : "H";
        } else if (currentMonth <= 6) {
            return (currentMonth == 6 && dayOfMonth > 15) ? "U" : "M";
        } else if (currentMonth <= 9) {
            return (currentMonth == 9 && dayOfMonth > 15) ? "Z" : "U";
        } else {
            return (currentMonth == 12 && dayOfMonth > 15) ? "H" : "Z";
        }
    }

    /**
     * Get next month's code for monthly contracts.
     */
    private String getNextMonthCode(int currentMonth) {
        int nextMonth = (currentMonth % 12) + 1;
        return switch (nextMonth) {
            case 1 -> "F";
            case 2 -> "G";
            case 3 -> "H";
            case 4 -> "J";
            case 5 -> "K";
            case 6 -> "M";
            case 7 -> "N";
            case 8 -> "Q";
            case 9 -> "U";
            case 10 -> "V";
            case 11 -> "X";
            case 12 -> "Z";
            default -> "H";
        };
    }

    /**
     * Get Gold (GC) contract month code.
     * Gold trades Feb (G), Apr (J), Jun (M), Aug (Q), Oct (V), Dec (Z).
     */
    private String getGoldMonthCode(int currentMonth) {
        // Find next gold contract month
        if (currentMonth <= 2) return "G";  // February
        if (currentMonth <= 4) return "J";  // April
        if (currentMonth <= 6) return "M";  // June
        if (currentMonth <= 8) return "Q";  // August
        if (currentMonth <= 10) return "V"; // October
        return "Z";  // December
    }

    /**
     * Fetch historical bars from TopstepX API.
     */
    private void fetchBars(String symbol, String contractId) {
        try {
            // Calculate time range - use wider window for better diagnostics
            ZonedDateTime endTime = ZonedDateTime.now(ZoneOffset.UTC);
            ZonedDateTime startTime = endTime.minusMinutes(60);  // 60 minutes for robustness

            logger.debug("Fetching bars for {} using contract ID: {} (live={})", symbol, contractId, useLiveData);
            String barsUrl = apiUrl + "/History/retrieveBars";

            Map<String, Object> requestMap = new HashMap<>();
            requestMap.put("contractId", contractId);
            requestMap.put("live", useLiveData);  // Use auto-detected live flag
            requestMap.put("startTime", startTime.format(DateTimeFormatter.ISO_INSTANT));
            requestMap.put("endTime", endTime.format(DateTimeFormatter.ISO_INSTANT));
            requestMap.put("unit", BAR_UNIT_MINUTE);
            requestMap.put("unitNumber", 1);
            requestMap.put("limit", 30);  // Request more bars
            requestMap.put("includePartialBar", false);  // Exclude partial bars for cleaner data

            String requestBody = objectMapper.writeValueAsString(requestMap);

            Request request = new Request.Builder()
                .url(barsUrl)
                .header("Authorization", "Bearer " + authToken)
                .post(RequestBody.create(requestBody, MediaType.parse("application/json")))
                .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String body = response.body() != null ? response.body().string() : "No body";
                    logger.error("HTTP error fetching bars for {}: {} - {}", symbol, response.code(), body);
                    return;
                }

                String responseBody = response.body().string();
                JsonNode json = objectMapper.readTree(responseBody);

                // CRITICAL: Check success and errorCode FIRST before parsing bars
                boolean success = json.path("success").asBoolean(true);  // Default true if not present
                int errorCode = json.path("errorCode").asInt(0);
                String errorMessage = json.path("errorMessage").asText(null);

                if (!success || errorCode != 0) {
                    logger.error("API ERROR fetching bars for {}: errorCode={}, errorMessage='{}', contractId={}, live={}",
                        symbol, errorCode, errorMessage, contractId, useLiveData);

                    // Provide actionable error messages based on error code
                    if (errorCode == 1) {
                        logger.error("ErrorCode 1 typically means: Invalid contract ID or account not authorized for this contract.");
                        logger.error("Try: 1) Verify contract ID exists 2) Check if account is SIM vs LIVE 3) Verify API permissions");
                    }
                    return;
                }

                // Extract bars array
                JsonNode bars = json.has("bars") ? json.get("bars") : json;
                if (bars == null || bars.isNull() || !bars.isArray() || bars.isEmpty()) {
                    // This is normal during market closed hours - log at debug level
                    logger.debug("No bars returned for {} (market may be closed)", symbol);
                    return;
                }

                MarketDataListener listener = marketDataListeners.get(symbol);
                if (listener == null) {
                    logger.warn("No listener registered for symbol: {}", symbol);
                    return;
                }

                Instant lastTimestamp = lastBarTimestamp.get(symbol);
                int totalBarsReceived = bars.size();

                logger.info("Received {} bars for {} (last processed: {})",
                    totalBarsReceived, symbol, lastTimestamp != null ? lastTimestamp : "none");

                // CRITICAL FIX: Parse all bars, sort by timestamp, then deliver in chronological order
                // This prevents out-of-order candle delivery which can affect indicator calculations
                java.util.List<Candle> parsedCandles = new java.util.ArrayList<>();

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
                            logger.warn("No timestamp in bar for {}: {}", symbol, bar);
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

                        // Create candle and add to list
                        Candle candle = new Candle(symbol, timestamp, open, high, low, close, volume);
                        parsedCandles.add(candle);

                    } catch (Exception e) {
                        logger.error("Error parsing bar for {}: {}", symbol, e.getMessage());
                    }
                }

                // Sort candles by timestamp ASCENDING (oldest first) for correct indicator processing
                parsedCandles.sort((c1, c2) -> c1.getTimestamp().compareTo(c2.getTimestamp()));

                // Deliver candles in chronological order
                int newBarsCount = 0;
                Instant maxTimestamp = lastTimestamp;

                for (Candle candle : parsedCandles) {
                    listener.onCandle(candle);
                    newBarsCount++;
                    logger.info("CANDLE {} | {} | O:{} H:{} L:{} C:{} V:{}",
                        symbol, candle.getTimestamp(), candle.getOpen(), candle.getHigh(),
                        candle.getLow(), candle.getClose(), candle.getVolume());

                    // Track the maximum timestamp
                    if (maxTimestamp == null || candle.getTimestamp().isAfter(maxTimestamp)) {
                        maxTimestamp = candle.getTimestamp();
                    }
                }

                // Update lastBarTimestamp to the max delivered timestamp
                if (maxTimestamp != null && (lastTimestamp == null || maxTimestamp.isAfter(lastTimestamp))) {
                    lastBarTimestamp.put(symbol, maxTimestamp);
                }

                int skippedBarsCount = totalBarsReceived - newBarsCount;
                if (newBarsCount > 0) {
                    logger.info("Delivered {} new candles for {} in chronological order (skipped {} already processed)",
                        newBarsCount, symbol, skippedBarsCount);
                } else if (skippedBarsCount > 0) {
                    logger.info("All {} bars for {} already processed (no new data)",
                        skippedBarsCount, symbol);
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
        logger.info("Performing initial fetch for {}", symbol);
        fetchBars(symbol, contractId);

        // Schedule periodic polling
        marketDataPoller.scheduleAtFixedRate(
            () -> {
                logger.debug("Polling market data for {} (scheduled)", symbol);
                fetchBars(symbol, contractId);
            },
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
        orderStatusPoller.shutdown();

        try {
            marketDataPoller.awaitTermination(5, TimeUnit.SECONDS);
            heartbeatScheduler.awaitTermination(5, TimeUnit.SECONDS);
            orderStatusPoller.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Clear state
        marketDataListeners.clear();
        orderListeners.clear();
        symbolToContractId.clear();
        lastBarTimestamp.clear();
        pendingOrders.clear();

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
            subscriptionStatus.put(symbol, true);
            startMarketDataPolling(symbol, contractId);
            logger.info("Successfully subscribed to market data for {} (contract: {})", symbol, contractId);
        } catch (Exception e) {
            logger.error("Failed to subscribe to market data for {}: {}", symbol, e.getMessage());
            subscriptionStatus.put(symbol, false);
            listener.onError(symbol, e);
            // CRITICAL: Throw exception so caller knows subscription failed
            throw new RuntimeException("Subscription failed for " + symbol + ": " + e.getMessage(), e);
        }
    }

    /**
     * Check if a symbol subscription is active.
     */
    public boolean isSubscriptionActive(String symbol) {
        return subscriptionStatus.getOrDefault(symbol, false);
    }

    @Override
    public void unsubscribeMarketData(String symbol) {
        logger.info("Unsubscribing from market data for: {}", symbol);

        marketDataListeners.remove(symbol);
        symbolToContractId.remove(symbol);
        lastBarTimestamp.remove(symbol);
        subscriptionStatus.remove(symbol);

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

        // CRITICAL: Validate and include limit price for LIMIT orders
        if (order.getType() == OrderType.LIMIT) {
            if (order.getLimitPrice() == null || order.getLimitPrice() <= 0) {
                orderListeners.remove(clientOrderId);
                throw new IllegalArgumentException("LIMIT order requires a valid limit price, got: " + order.getLimitPrice());
            }
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

            // Check if order was successful - CRITICAL: Default to false for safety
            boolean success;
            if (json.has("success")) {
                success = json.get("success").asBoolean();
            } else {
                // If no explicit success field, check for orderId presence as implicit success
                success = json.has("orderId") || json.has("id");
                if (!success) {
                    logger.warn("API response missing 'success' field and no order ID found: {}", responseBody);
                }
            }
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

            // CRITICAL: Track order in pendingOrders for fill status polling
            // This enables the polling mechanism to detect fills and trigger callbacks
            PendingOrder pending = new PendingOrder(
                serverId,
                order.getSymbol(),
                order.getQuantity(),
                order.getSide(),
                order.getLimitPrice() != null ? order.getLimitPrice() : 0.0,
                listener
            );
            pendingOrders.put(serverId, pending);
            logger.info("Order {} added to pending orders for fill tracking", serverId);

            // Notify listener of submission
            listener.onOrderSubmitted(order);

            return serverId;
        }
    }

    /**
     * Submit a stop loss order (Stop Market) for position protection.
     * ProjectX Gateway: type 4 = Stop Market
     *
     * @param symbol The trading symbol
     * @param side The order side (opposite of position - SELL for long positions, BUY for short)
     * @param quantity Number of contracts
     * @param stopPrice The stop trigger price
     * @param listener Callback for order updates
     * @return The order ID
     */
    public String submitStopOrder(String symbol, OrderSide side, int quantity, double stopPrice,
                                  OrderListener listener) throws Exception {
        logger.info("Submitting STOP order: {} {} {} @ stop {}",
            side, quantity, symbol, stopPrice);

        // Get contract ID for symbol
        String contractId = symbolToContractId.get(symbol);
        if (contractId == null) {
            contractId = searchContract(symbol);
            symbolToContractId.put(symbol, contractId);
        }

        // Get numeric account ID
        String numericAccountId = getNumericAccountId();

        // Build stop order request
        // ProjectX Gateway: type 4 = Stop Market
        Map<String, Object> orderMap = new HashMap<>();
        orderMap.put("accountId", Integer.parseInt(numericAccountId));
        orderMap.put("contractId", contractId);
        orderMap.put("type", 4);  // Stop Market
        orderMap.put("side", side == OrderSide.BUY ? 0 : 1);
        orderMap.put("size", quantity);
        orderMap.put("stopPrice", stopPrice);

        String orderBody = objectMapper.writeValueAsString(orderMap);
        logger.info("Stop order request body: {}", orderBody);

        String orderUrl = apiUrl + "/Order/place";
        Request request = new Request.Builder()
            .url(orderUrl)
            .header("Authorization", "Bearer " + authToken)
            .post(RequestBody.create(orderBody, MediaType.parse("application/json")))
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String body = response.body() != null ? response.body().string() : "No body";
                throw new IOException("Stop order submission failed: " + response.code() + " - " + body);
            }

            String responseBody = response.body().string();
            logger.info("Stop order response: {}", responseBody);
            JsonNode json = objectMapper.readTree(responseBody);

            boolean success = json.has("success") ? json.get("success").asBoolean() : json.has("orderId");
            if (!success) {
                String errorMsg = json.has("errorMessage") ? json.get("errorMessage").asText() : "Unknown error";
                throw new IOException("Stop order rejected: " + errorMsg);
            }

            String orderId = json.has("orderId") ? json.get("orderId").asText() :
                             (json.has("id") ? json.get("id").asText() : "unknown");
            logger.info("Stop order submitted successfully: {}", orderId);

            // CRITICAL: Track stop order in pendingOrders for fill status polling
            if (listener != null) {
                orderListeners.put(orderId, listener);
                PendingOrder pending = new PendingOrder(
                    orderId,
                    symbol,
                    quantity,
                    side,
                    stopPrice,
                    listener
                );
                pendingOrders.put(orderId, pending);
                logger.info("Stop order {} added to pending orders for fill tracking", orderId);
            }

            return orderId;
        }
    }

    /**
     * Submit a take profit order (Limit) for position exit.
     *
     * @param symbol The trading symbol
     * @param side The order side (opposite of position - SELL for long positions, BUY for short)
     * @param quantity Number of contracts
     * @param limitPrice The limit price for take profit
     * @param listener Callback for order updates
     * @return The order ID
     */
    public String submitTakeProfitOrder(String symbol, OrderSide side, int quantity, double limitPrice,
                                        OrderListener listener) throws Exception {
        logger.info("Submitting TAKE PROFIT order: {} {} {} @ limit {}",
            side, quantity, symbol, limitPrice);

        // Get contract ID for symbol
        String contractId = symbolToContractId.get(symbol);
        if (contractId == null) {
            contractId = searchContract(symbol);
            symbolToContractId.put(symbol, contractId);
        }

        // Get numeric account ID
        String numericAccountId = getNumericAccountId();

        // Build limit order request
        Map<String, Object> orderMap = new HashMap<>();
        orderMap.put("accountId", Integer.parseInt(numericAccountId));
        orderMap.put("contractId", contractId);
        orderMap.put("type", 1);  // Limit
        orderMap.put("side", side == OrderSide.BUY ? 0 : 1);
        orderMap.put("size", quantity);
        orderMap.put("limitPrice", limitPrice);

        String orderBody = objectMapper.writeValueAsString(orderMap);
        logger.info("Take profit order request body: {}", orderBody);

        String orderUrl = apiUrl + "/Order/place";
        Request request = new Request.Builder()
            .url(orderUrl)
            .header("Authorization", "Bearer " + authToken)
            .post(RequestBody.create(orderBody, MediaType.parse("application/json")))
            .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String body = response.body() != null ? response.body().string() : "No body";
                throw new IOException("Take profit order submission failed: " + response.code() + " - " + body);
            }

            String responseBody = response.body().string();
            logger.info("Take profit order response: {}", responseBody);
            JsonNode json = objectMapper.readTree(responseBody);

            boolean success = json.has("success") ? json.get("success").asBoolean() : json.has("orderId");
            if (!success) {
                String errorMsg = json.has("errorMessage") ? json.get("errorMessage").asText() : "Unknown error";
                throw new IOException("Take profit order rejected: " + errorMsg);
            }

            String orderId = json.has("orderId") ? json.get("orderId").asText() :
                             (json.has("id") ? json.get("id").asText() : "unknown");
            logger.info("Take profit order submitted successfully: {}", orderId);

            // CRITICAL: Track take profit order in pendingOrders for fill status polling
            if (listener != null) {
                orderListeners.put(orderId, listener);
                PendingOrder pending = new PendingOrder(
                    orderId,
                    symbol,
                    quantity,
                    side,
                    limitPrice,
                    listener
                );
                pendingOrders.put(orderId, pending);
                logger.info("Take profit order {} added to pending orders for fill tracking", orderId);
            }

            return orderId;
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

        // Get numeric account ID (required by ProjectX Gateway)
        String numericAccountId = getNumericAccountId();

        String cancelUrl = apiUrl + "/Order/cancel";
        // ProjectX Gateway requires both accountId and orderId for cancellation
        Map<String, Object> cancelMap = new HashMap<>();
        cancelMap.put("accountId", Integer.parseInt(numericAccountId));
        cancelMap.put("orderId", numericOrderId);
        String cancelBody = objectMapper.writeValueAsString(cancelMap);

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
