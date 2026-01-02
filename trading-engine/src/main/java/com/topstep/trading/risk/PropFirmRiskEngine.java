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

    // Tick values (dollar value per tick) for different instruments
    private static final double TICK_VALUE_ES = 12.50;  // ES: $12.50 per tick (0.25 point)
    private static final double TICK_VALUE_NQ = 5.00;   // NQ: $5.00 per tick (0.25 point)
    private static final double TICK_VALUE_6E = 6.25;   // Euro FX: $6.25 per tick (125,000 EUR × 0.00005)
    private static final double TICK_VALUE_6J = 6.25;   // Japanese Yen: $6.25 per tick (12,500,000 JPY × 0.0000005)
    private static final double TICK_VALUE_6B = 6.25;   // British Pound: $6.25 per tick (62,500 GBP × 0.0001)
    private static final double TICK_VALUE_CL = 10.00;  // Crude Oil: $10.00 per tick (1,000 barrels × 0.01)
    private static final double TICK_VALUE_GC = 10.00;  // Gold: $10.00 per tick (100 oz × 0.10)
    private static final double TICK_VALUE_NG = 10.00;  // Natural Gas: $10.00 per tick (10,000 MMBtu × 0.001)
    private static final double TICK_VALUE_SI = 25.00;  // Silver: $25.00 per tick (5,000 oz × 0.005)

    // Tick sizes (minimum price increment) for different instruments
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

        // Debug logging for position sizing
        System.out.println("[POSITION SIZING] " + signal.getSymbol() +
            ": stopDist=" + String.format("%.7f", stopDistance) +
            ", tickSize=" + tickSize +
            ", ticks=" + String.format("%.1f", ticksAtRisk) +
            ", $/contract=$" + String.format("%.2f", dollarRiskPerContract));

        if (dollarRiskPerContract <= 0) {
            return RiskDecision.deny("Invalid risk calculation: " + dollarRiskPerContract);
        }

        // Position size: Use a fraction of DLL per trade (e.g., 25% of DLL)
        // CRITICAL: Cap risk to remaining daily loss room to avoid exceeding DLL
        double riskPerTrade = Math.min(limits.getRiskPerTrade(), remainingDailyLoss);
        int quantity = (int) Math.floor(riskPerTrade / dollarRiskPerContract);

        // MINIMUM 1 CONTRACT: If calculation gives 0 but risk is within acceptable limit, allow 1 contract
        // This prevents missing trades on instruments with naturally wider stops
        // Use higher multiplier for high-tick-value instruments (SI, GC, NG, CL)
        if (quantity <= 0) {
            double maxRiskMultiplier = getMaxRiskMultiplier(signal.getSymbol());
            double maxAllowedRisk = riskPerTrade * maxRiskMultiplier;

            // Use small tolerance (0.01) to handle floating point precision issues
            // e.g., $1250.0000001 should be treated as equal to $1250.00
            if (dollarRiskPerContract <= maxAllowedRisk + 0.01) {
                // Risk is within acceptable limit - allow 1 contract with warning
                quantity = 1;
                System.out.println("[POSITION SIZING] " + signal.getSymbol() +
                    ": Using minimum 1 contract (risk $" + String.format("%.2f", dollarRiskPerContract) +
                    " exceeds budget $" + String.format("%.2f", riskPerTrade) +
                    " but within " + String.format("%.0fx", maxRiskMultiplier) + " limit)");
            } else {
                // Risk is too high even for 1 contract
                return RiskDecision.deny("Risk too high per contract: $" +
                    String.format("%.2f", dollarRiskPerContract) + " > " +
                    String.format("%.0fx", maxRiskMultiplier) + " budget $" +
                    String.format("%.2f", maxAllowedRisk));
            }
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
        switch (symbol.toUpperCase()) {
            case "ES":
            case "MES":
                return TICK_VALUE_ES;
            case "NQ":
            case "MNQ":
                return TICK_VALUE_NQ;
            case "6E":
                return TICK_VALUE_6E;
            case "6J":
                return TICK_VALUE_6J;
            case "6B":
                return TICK_VALUE_6B;
            case "CL":
                return TICK_VALUE_CL;
            case "GC":
                return TICK_VALUE_GC;
            case "NG":
                return TICK_VALUE_NG;
            case "SI":
                return TICK_VALUE_SI;
            default:
                // Default to ES tick value for unknown instruments
                return TICK_VALUE_ES;
        }
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
     * Get the maximum risk multiplier for minimum 1 contract sizing.
     * Higher tick value instruments get a larger multiplier to accommodate typical stop distances.
     */
    private double getMaxRiskMultiplier(String symbol) {
        switch (symbol.toUpperCase()) {
            case "6J":
                // Japanese Yen: tiny tick size (0.0000005) means 200+ ticks for typical stops
                // 200 ticks * $6.25 = $1,250 typical risk - needs 5x ($1,250 max)
                return 5.0;
            case "SI":
                // Silver: $25/tick - needs 4x to handle $0.25 stop ($1,000 max)
                return 4.0;
            case "NQ":
            case "MNQ":
                // NQ: $5/tick, 50-point stop = 200 ticks * $5 = $1,000 - needs 4x
                return 4.0;
            case "GC":
            case "NG":
            case "CL":
                // Gold, NatGas, Crude: $10/tick - needs 3x to handle typical stops ($750 max)
                return 3.0;
            default:
                // ES, 6E, 6B and others: standard 2x ($500 max)
                return 2.0;
        }
    }

    /**
     * Get the number of decimal places in a tick size.
     * FIXED: Properly returns count based on significant decimal places needed for rounding.
     */
    private int getDecimalPlaces(double tickSize) {
        String tickStr = String.valueOf(tickSize);
        int decimalIndex = tickStr.indexOf('.');
        if (decimalIndex < 0) {
            return 0;
        }

        String afterDecimal = tickStr.substring(decimalIndex + 1);

        // Handle scientific notation (e.g., 5.0E-5 for 0.00005, or 5.0E-7 for 0.0000005)
        int eIndex = afterDecimal.toUpperCase().indexOf('E');
        if (eIndex >= 0) {
            int exp = Integer.parseInt(afterDecimal.substring(eIndex + 1));
            // For 5.0E-5, exp = -5, we need 5 decimal places
            // For 5.0E-7, exp = -7, we need 7 decimal places
            return Math.abs(exp);
        }

        // For regular notation (e.g., "0.25", "0.10", "0.001")
        // Return the length of the decimal portion to preserve precision
        return afterDecimal.length();
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
