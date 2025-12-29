package com.topstep.trading.risk;

import com.topstep.trading.domain.*;
import com.topstep.trading.event.StrategySignalEvent;

/**
 * Prop firm risk engine that enforces Topstep Express Funded Account rules:
 *
 * 1. Daily Loss Limit (DLL): Max loss per day (e.g., $1,000 for 50K account)
 * 2. Max Loss Limit (MLL): Max total loss from highest EOD balance (e.g., $2,000 for 50K account)
 * 3. Position Sizing: Based on risk per trade as % of DLL
 * 4. Max Contracts: Enforcement of contract limits
 *
 * The engine evaluates every signal and returns a RiskDecision.
 */
public class PropFirmRiskEngine {

    private static final double TICK_VALUE_ES = 12.50;  // ES: $12.50 per tick
    private static final double TICK_VALUE_NQ = 5.00;   // NQ: $5.00 per tick

    // Tick sizes for different instruments
    private static final double TICK_SIZE_ES = 0.25;      // ES/MES
    private static final double TICK_SIZE_NQ = 0.25;      // NQ/MNQ
    private static final double TICK_SIZE_6E = 0.00005;   // Euro FX
    private static final double TICK_SIZE_6J = 0.0000005; // Japanese Yen
    private static final double TICK_SIZE_6B = 0.0001;    // British Pound
    private static final double TICK_SIZE_CL = 0.01;      // Crude Oil
    private static final double TICK_SIZE_GC = 0.10;      // Gold
    private static final double TICK_SIZE_NG = 0.001;     // Natural Gas
    private static final double TICK_SIZE_SI = 0.005;     // Silver

    /**
     * Evaluate a strategy signal against account state and risk limits.
     *
     * @param signal Strategy signal with entry, stop, and target
     * @param account Current account state
     * @param limits Risk limits to enforce
     * @return RiskDecision indicating whether trade is allowed
     */
    public RiskDecision evaluate(StrategySignalEvent signal, AccountState account, RiskLimits limits) {

        // 1. Check Daily Loss Limit (DLL)
        double netDailyPnl = account.getNetDailyPnl();
        if (netDailyPnl <= -limits.getMaxDailyLoss()) {
            return RiskDecision.deny("Daily Loss Limit breached: " + String.format("%.2f", netDailyPnl));
        }

        // 2. Check Max Loss Limit (MLL) - based on highest EOD balance
        double highestBalance = account.getHighestEndOfDayBalance();
        double currentEquity = account.getEquity();
        double totalDrawdown = highestBalance - currentEquity;

        if (totalDrawdown >= limits.getMaxLossLimit()) {
            return RiskDecision.deny("Max Loss Limit breached: " + String.format("%.2f drawdown", totalDrawdown));
        }

        // 3. Check remaining daily loss room
        double remainingDailyLoss = limits.getMaxDailyLoss() + netDailyPnl; // How much room left today
        if (remainingDailyLoss <= 0) {
            return RiskDecision.deny("No daily loss room remaining");
        }

        // 4. Calculate position size based on risk per trade
        double stopDistance = Math.abs(signal.getEntryPrice() - signal.getStopPrice());
        if (stopDistance <= 0) {
            return RiskDecision.deny("Invalid stop distance: " + stopDistance);
        }

        // Get tick value for the symbol
        double tickValue = getTickValue(signal.getSymbol());

        // Calculate risk in dollars per contract
        double tickSize = getTickSize(signal.getSymbol());
        double ticksAtRisk = stopDistance / tickSize;
        double dollarRiskPerContract = ticksAtRisk * tickValue;

        if (dollarRiskPerContract <= 0) {
            return RiskDecision.deny("Invalid risk calculation: " + dollarRiskPerContract);
        }

        // Position size: Use a fraction of DLL per trade (e.g., 25% of DLL)
        // CRITICAL: Cap risk to remaining daily loss room to avoid exceeding DLL
        double riskPerTrade = Math.min(limits.getRiskPerTrade(), remainingDailyLoss);
        int quantity = (int) Math.floor(riskPerTrade / dollarRiskPerContract);

        if (quantity <= 0) {
            return RiskDecision.deny("Calculated quantity is zero (risk too high per contract)");
        }

        // 5. Enforce max contracts limit
        if (quantity > limits.getMaxContracts()) {
            quantity = limits.getMaxContracts();
        }

        // 6. Check total contracts limit
        int currentContracts = account.getTotalContracts();
        if (currentContracts + quantity > limits.getMaxTotalContracts()) {
            return RiskDecision.deny("Would exceed max total contracts: " +
                    (currentContracts + quantity) + " > " + limits.getMaxTotalContracts());
        }

        // 7. Verify minimum R:R ratio
        double targetDistance = Math.abs(signal.getTargetPrice() - signal.getEntryPrice());
        double rewardRiskRatio = targetDistance / stopDistance;

        if (rewardRiskRatio < limits.getMinRiskRewardRatio()) {
            return RiskDecision.deny("R:R too low: " + String.format("%.2f", rewardRiskRatio) +
                    " < " + limits.getMinRiskRewardRatio());
        }

        if (rewardRiskRatio > limits.getMaxRiskRewardRatio()) {
            return RiskDecision.deny("R:R too high (unrealistic): " + String.format("%.2f", rewardRiskRatio));
        }

        // 8. Build the order - round limit price to valid tick
        double roundedPrice = roundToTick(signal.getEntryPrice(), tickSize);
        Order order = Order.builder()
                .symbol(signal.getSymbol())
                .side(signal.getSide())
                .type(OrderType.LIMIT)
                .quantity(quantity)
                .limitPrice(roundedPrice)
                .build();

        String approvalReason = String.format(
                "Approved: %d contracts, $%.2f risk/trade, R:R %.2f:1, DLL room: $%.2f",
                quantity,
                dollarRiskPerContract * quantity,
                rewardRiskRatio,
                remainingDailyLoss
        );

        return RiskDecision.allow(order, approvalReason);
    }

    /**
     * Get tick value for a given symbol (dollar value per tick).
     */
    private double getTickValue(String symbol) {
        if (symbol.startsWith("ES") || symbol.equals("MES")) {
            return TICK_VALUE_ES;
        } else if (symbol.startsWith("NQ") || symbol.equals("MNQ")) {
            return TICK_VALUE_NQ;
        }
        // Default to ES tick value
        return TICK_VALUE_ES;
    }

    /**
     * Get tick size for a given symbol (minimum price increment).
     */
    private double getTickSize(String symbol) {
        switch (symbol.toUpperCase()) {
            case "ES":
            case "MES":
                return TICK_SIZE_ES;
            case "NQ":
            case "MNQ":
                return TICK_SIZE_NQ;
            case "6E":
                return TICK_SIZE_6E;
            case "6J":
                return TICK_SIZE_6J;
            case "6B":
                return TICK_SIZE_6B;
            case "CL":
                return TICK_SIZE_CL;
            case "GC":
                return TICK_SIZE_GC;
            case "NG":
                return TICK_SIZE_NG;
            case "SI":
                return TICK_SIZE_SI;
            default:
                // Default to ES tick size
                return TICK_SIZE_ES;
        }
    }

    /**
     * Round a price to the nearest valid tick increment.
     * This prevents floating point precision issues like 1.1828750000000001
     */
    private double roundToTick(double price, double tickSize) {
        // Round to nearest tick
        double ticks = Math.round(price / tickSize);
        double rounded = ticks * tickSize;

        // Handle floating point artifacts by rounding to appropriate decimal places
        int decimals = getDecimalPlaces(tickSize);
        double multiplier = Math.pow(10, decimals);
        return Math.round(rounded * multiplier) / multiplier;
    }

    /**
     * Get the number of decimal places in a tick size.
     */
    private int getDecimalPlaces(double tickSize) {
        String tickStr = String.valueOf(tickSize);
        int decimalIndex = tickStr.indexOf('.');
        if (decimalIndex < 0) {
            return 0;
        }
        // Remove trailing zeros for proper count
        String decimals = tickStr.substring(decimalIndex + 1).replaceAll("0+$", "");
        return decimals.length() > 0 ? tickStr.substring(decimalIndex + 1).length() : 0;
    }

    /**
     * Check if account is in good standing (not breached any limits).
     */
    public boolean isAccountInGoodStanding(AccountState account, RiskLimits limits) {
        // Check DLL
        if (account.getNetDailyPnl() <= -limits.getMaxDailyLoss()) {
            return false;
        }

        // Check MLL
        double totalDrawdown = account.getHighestEndOfDayBalance() - account.getEquity();
        if (totalDrawdown >= limits.getMaxLossLimit()) {
            return false;
        }

        return true;
    }

    /**
     * Check if profit target has been reached.
     */
    public boolean hasMetProfitTarget(AccountState account, RiskLimits limits) {
        double totalPnl = account.getRealizedPnL();
        return totalPnl >= limits.getProfitTarget();
    }
}
