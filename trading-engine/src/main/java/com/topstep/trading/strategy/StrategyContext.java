package com.topstep.trading.strategy;

import com.topstep.trading.domain.AccountState;
import com.topstep.trading.domain.Position;
import java.time.Instant;
import java.util.Map;

/**
 * Provides context information to trading strategies.
 * Strategies can query account state, positions, and other relevant data.
 */
public interface StrategyContext {

    /**
     * Get the current account state.
     */
    AccountState getAccountState();

    /**
     * Get current open positions.
     */
    Map<String, Position> getOpenPositions();

    /**
     * Get current timestamp.
     */
    Instant getCurrentTime();

    /**
     * Check if the account has a position in the given symbol.
     */
    boolean hasPosition(String symbol);

    /**
     * Get position for a specific symbol, or null if no position exists.
     */
    Position getPosition(String symbol);

    /**
     * Get the total number of open contracts across all positions.
     */
    int getTotalContracts();
}
