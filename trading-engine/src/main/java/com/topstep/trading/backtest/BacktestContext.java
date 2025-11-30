package com.topstep.trading.backtest;

import com.topstep.trading.domain.AccountState;
import com.topstep.trading.domain.Position;
import com.topstep.trading.strategy.StrategyContext;

import java.time.Instant;
import java.util.Map;

/**
 * Strategy context implementation for backtesting.
 */
public class BacktestContext implements StrategyContext {

    private final AccountState accountState;
    private Instant currentTime;

    public BacktestContext(AccountState accountState) {
        this.accountState = accountState;
        this.currentTime = Instant.now();
    }

    @Override
    public AccountState getAccountState() {
        return accountState;
    }

    @Override
    public Map<String, Position> getOpenPositions() {
        return accountState.getPositions();
    }

    @Override
    public Instant getCurrentTime() {
        return currentTime;
    }

    @Override
    public boolean hasPosition(String symbol) {
        return accountState.hasPosition(symbol);
    }

    @Override
    public Position getPosition(String symbol) {
        return accountState.getPosition(symbol);
    }

    @Override
    public int getTotalContracts() {
        return accountState.getTotalContracts();
    }

    public void setCurrentTime(Instant currentTime) {
        this.currentTime = currentTime;
    }
}
