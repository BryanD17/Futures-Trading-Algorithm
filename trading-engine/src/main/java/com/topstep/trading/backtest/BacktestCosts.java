package com.topstep.trading.backtest;

import com.topstep.trading.domain.Trade;

import java.util.List;

/**
 * Explicit per-fill trading costs for backtest PnL (SA5).
 *
 * <p>The stock backtester models neither commission nor slippage — at ~1:1
 * RR those costs decide profitability, so every SA5 report shows GROSS and
 * NET side by side. Configuration (system properties, {@code -D} flags):
 *
 * <ul>
 *   <li>{@code backtest.commissionPerSide} — dollars per side per contract
 *       (default {@code 1.55}, typical all-in micro-future rate);</li>
 *   <li>{@code backtest.slippageTicks} — ticks of adverse slippage per side
 *       per contract (default {@code 1}).</li>
 * </ul>
 *
 * <p>Cost per completed trade (one round trip) =
 * {@code quantity × 2 × (commissionPerSide + slippageTicks × tickValue)}.
 */
public final class BacktestCosts {

    /** System property: commission per side per contract. Default 1.55. */
    public static final String COMMISSION_PER_SIDE_PROPERTY = "backtest.commissionPerSide";
    public static final double DEFAULT_COMMISSION_PER_SIDE = 1.55;

    /** System property: slippage in ticks per side per contract. Default 1. */
    public static final String SLIPPAGE_TICKS_PROPERTY = "backtest.slippageTicks";
    public static final int DEFAULT_SLIPPAGE_TICKS = 1;

    private final double commissionPerSide;
    private final int slippageTicks;
    private final double tickValue;

    /**
     * @param tickValue dollar value of ONE tick for ONE contract of the
     *                  backtested instrument (e.g. MNQ: 0.25 pt × $2/pt = $0.50)
     */
    public BacktestCosts(double tickValue) {
        this.tickValue = tickValue;
        this.commissionPerSide = doubleProperty(
                COMMISSION_PER_SIDE_PROPERTY, DEFAULT_COMMISSION_PER_SIDE);
        this.slippageTicks = (int) doubleProperty(
                SLIPPAGE_TICKS_PROPERTY, DEFAULT_SLIPPAGE_TICKS);
    }

    public double getCommissionPerSide() { return commissionPerSide; }
    public int getSlippageTicks() { return slippageTicks; }
    public double getTickValue() { return tickValue; }

    /** Total cost of one completed round-trip trade (both sides, all contracts). */
    public double costOf(Trade trade) {
        return costFor(trade.getQuantity());
    }

    /** Total round-trip cost for {@code contracts} contracts. */
    public double costFor(int contracts) {
        double perContractRoundTrip = 2.0 * (commissionPerSide + slippageTicks * tickValue);
        return contracts * perContractRoundTrip;
    }

    /** Sum of round-trip costs across all completed trades. */
    public double totalCosts(List<Trade> trades) {
        return trades.stream().mapToDouble(this::costOf).sum();
    }

    @Override
    public String toString() {
        return String.format(
                "BacktestCosts{commission $%.2f/side/contract, slippage %d tick(s) x $%.2f/side/contract}",
                commissionPerSide, slippageTicks, slippageTicks * tickValue);
    }

    private static double doubleProperty(String name, double defaultValue) {
        String raw = System.getProperty(name);
        if (raw == null) return defaultValue;
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            System.out.println("[BacktestCosts] WARN: invalid " + name + "='" + raw
                    + "', using default " + defaultValue);
            return defaultValue;
        }
    }
}
