package com.topstep.trading.connector;

import com.topstep.trading.domain.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Topstep connector implementation.
 * TODO: Implement actual Topstep API integration.
 *
 * This is a placeholder that will need to be implemented based on:
 * - Topstep's official API documentation
 * - TopstepX / Project X connectivity requirements
 * - Authentication and authorization flows
 */
public class TopstepConnector implements TradingConnector {
    private static final Logger logger = LoggerFactory.getLogger(TopstepConnector.class);

    private boolean connected = false;

    public TopstepConnector() {
        logger.warn("TopstepConnector is a placeholder. Real implementation required.");
    }

    @Override
    public void connect() throws Exception {
        logger.info("TopstepConnector.connect() - Not implemented yet");
        throw new UnsupportedOperationException(
                "Topstep connector not implemented. Use MockConnector for testing or " +
                "implement this based on Topstep's API documentation."
        );
    }

    @Override
    public void disconnect() {
        logger.info("TopstepConnector.disconnect()");
        connected = false;
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public void subscribeMarketData(String symbol, MarketDataListener listener) {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public void unsubscribeMarketData(String symbol) {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public String submitOrder(Order order, OrderListener listener) throws Exception {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public void cancelOrder(String orderId) throws Exception {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public double getAccountBalance() throws Exception {
        throw new UnsupportedOperationException("Not implemented");
    }

    @Override
    public String getName() {
        return "TopstepConnector";
    }
}
