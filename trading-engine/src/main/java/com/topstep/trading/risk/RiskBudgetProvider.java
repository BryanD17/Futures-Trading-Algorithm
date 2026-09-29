package com.topstep.trading.risk;

import com.topstep.trading.strategy.TradeTier;

/**
 * AGENT-05.10 (V5): the per-trade $ budget the strategy's risk-derived sizer
 * starts from, supplied by whoever owns the risk decision (the LIVE runner's
 * {@link LiveRiskPath}). The strategy sizes the request from the SAME budget
 * the risk engine will use, so a signal is never "requested 5 &gt; max 1"
 * because the sizer and the risk engine read different budgets.
 *
 * <p>No provider installed (the default, {@code risk.phaseAware=false}) =
 * the static {@code RiskLimits.riskPerTrade}, i.e. the proven path.
 */
@FunctionalInterface
public interface RiskBudgetProvider {

    /**
     * @param symbol             the instrument being sized
     * @param tier               the tier the signal will carry (drives the setup-quality score)
     * @param staticRiskPerTrade the profile's static {@code riskPerTrade}
     * @return the per-trade $ budget BEFORE the DLL / MLL room caps (the sizer
     *         and the risk engine both apply those)
     */
    double perTradeBudget(String symbol, TradeTier tier, double staticRiskPerTrade);
}
