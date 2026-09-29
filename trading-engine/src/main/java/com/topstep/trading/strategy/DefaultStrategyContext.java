package com.topstep.trading.strategy;

import com.topstep.trading.domain.AccountState;
import com.topstep.trading.domain.Position;

import java.time.Instant;
import java.util.Map;

/**
 * Default implementation of StrategyContext for SIM and LIVE modes.
 */
public class DefaultStrategyContext implements StrategyContext {

    private final AccountState accountState;
    private Instant currentTime;
    /** AGENT-05.10: null = static riskPerTrade (risk.phaseAware=false, the proven path). */
    private volatile com.topstep.trading.risk.RiskBudgetProvider riskBudgetProvider;

    public DefaultStrategyContext(AccountState accountState) {
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

    /**
     * Set the current time (for simulation/live updates).
     */
    public void setCurrentTime(Instant currentTime) {
        this.currentTime = currentTime;
    }

    /** AGENT-05.10: install (or clear with null) the per-trade budget source the strategy sizes from. */
    public void setRiskBudgetProvider(com.topstep.trading.risk.RiskBudgetProvider provider) {
        this.riskBudgetProvider = provider;
    }

    public com.topstep.trading.risk.RiskBudgetProvider getRiskBudgetProvider() {
        return riskBudgetProvider;
    }

    @Override
    public double perTradeRiskBudget(String symbol, TradeTier tier, double staticRiskPerTrade) {
        com.topstep.trading.risk.RiskBudgetProvider p = riskBudgetProvider;
        return p == null ? staticRiskPerTrade : p.perTradeBudget(symbol, tier, staticRiskPerTrade);
    }
}
