package com.topstep.trading.connector;

import com.topstep.trading.domain.Order;

/**
 * Interface for connecting to trading venues (Topstep, Rithmic, etc.).
 * Implementations handle connectivity, market data, and order execution.
 */
public interface TradingConnector {
    /**
     * Connect to the trading venue.
     */
    void connect() throws Exception;

    /**
     * Disconnect from the trading venue.
     */
    void disconnect();

    /**
     * Check if connected.
     */
    boolean isConnected();

    /**
     * Subscribe to market data for a symbol.
     * @param symbol Symbol to subscribe to (e.g., "ES", "NQ")
     * @param listener Listener for market data updates
     */
    void subscribeMarketData(String symbol, MarketDataListener listener);

    /**
     * Unsubscribe from market data for a symbol.
     */
    void unsubscribeMarketData(String symbol);

    /**
     * Submit an order.
     * @param order Order to submit
     * @param listener Listener for order updates
     * @return Order ID assigned by the venue
     */
    String submitOrder(Order order, OrderListener listener) throws Exception;

    /**
     * Cancel an order.
     * @param orderId Order ID to cancel
     */
    void cancelOrder(String orderId) throws Exception;

    /**
     * Get account balance.
     */
    double getAccountBalance() throws Exception;

    /**
     * Get the name of this connector.
     */
    String getName();
}
