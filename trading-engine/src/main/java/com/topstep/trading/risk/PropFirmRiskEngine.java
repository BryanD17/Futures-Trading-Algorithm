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
    private static final double TICK_VALUE_MES = 1.25;  // MES (Micro ES): $1.25 per tick (1/10th of ES)
    private static final double TICK_VALUE_NQ = 5.00;   // NQ: $5.00 per tick (0.25 point)
    private static final double TICK_VALUE_MNQ = 0.50;  // MNQ (Micro NQ): $0.50 per tick (1/10th of NQ)
    private static final double TICK_VALUE_GC = 10.00;  // Gold: $10.00 per tick (100 oz × 0.10)
    private static final double TICK_VALUE_MGC = 1.00;  // MGC (Micro Gold): $1.00 per tick (1/10th of GC)
    private static final double TICK_VALUE_NG = 10.00;  // Natural Gas: $10.00 per tick (10,000 MMBtu × 0.001)
    private static final double TICK_VALUE_SI = 25.00;  // Silver: $25.00 per tick (5,000 oz × 0.005)

    // Tick sizes (minimum price increment) for different instruments
    private static final double TICK_SIZE_ES = 0.25;      // ES/MES
    private static final double TICK_SIZE_NQ = 0.25;      // NQ/MNQ
    private static final double TICK_SIZE_GC = 0.10;      // Gold
    private static final double TICK_SIZE_NG = 0.001;     // Natural Gas
    private static final double TICK_SIZE_SI = 0.005;     // Silver

    /**
     * Evaluate a strategy signal against account state and risk limits.
     * Uses the static riskPerTrade from RiskLimits (backward compatible).
     *
     * @param signal Strategy signal with entry, stop, and target
     * @param account Current account state
     * @param limits Risk limits to enforce
     * @return RiskDecision indicating whether trade is allowed
     */
    public RiskDecision evaluate(StrategySignalEvent signal, AccountState account, RiskLimits limits) {
        return evaluate(signal, account, limits, limits.getRiskPerTrade());
    }

    /**
     * Evaluate a strategy signal with dynamic risk per trade from PhaseAwareRiskCalculator.
     * The dynamic risk replaces the static $250 value based on account phase, zone, and setup quality.
     *
     * CRITICAL: All existing DLL/MLL/max contract checks remain unchanged.
     * The dynamic risk is an ADDITIONAL layer on top of existing safety checks.
     *
     * @param signal Strategy signal with entry, stop, and target
     * @param account Current account state
     * @param limits Risk limits to enforce
     * @param dynamicRiskPerTrade Dollar risk for this specific trade (from PhaseAwareRiskCalculator)
     * @return RiskDecision indicating whether trade is allowed
     */
    public RiskDecision evaluate(StrategySignalEvent signal, AccountState account, RiskLimits limits,
                                  double dynamicRiskPerTrade) {
        String sym = signal.getSymbol();

        // 1. Check Daily Loss Limit (DLL) — SACRED.
        double netDailyPnl = account.getNetDailyPnl();
        if (netDailyPnl <= -limits.getMaxDailyLoss()) {
            return deny(signal, "RISK: Daily Loss Limit breached: " + String.format("%.2f", netDailyPnl)
                    + " <= -" + String.format("%.2f", limits.getMaxDailyLoss()),
                    netDailyPnl, -limits.getMaxDailyLoss());
        }

        // 2. Check Max Loss Limit (MLL) - based on highest EOD balance — SACRED.
        double highestBalance = account.getHighestEndOfDayBalance();
        double currentEquity = account.getEquity();
        double totalDrawdown = highestBalance - currentEquity;

        if (totalDrawdown >= limits.getMaxLossLimit()) {
            return deny(signal, "RISK: Max Loss Limit breached: " + String.format("%.2f drawdown", totalDrawdown)
                    + " >= " + String.format("%.2f", limits.getMaxLossLimit()),
                    totalDrawdown, limits.getMaxLossLimit());
        }

        // 3. Check remaining daily loss room
        double remainingDailyLoss = limits.getMaxDailyLoss() + netDailyPnl; // How much room left today
        if (remainingDailyLoss <= 0) {
            return deny(signal, "RISK: No daily loss room remaining: " + String.format("%.2f", remainingDailyLoss),
                    remainingDailyLoss, 0);
        }
        double remainingMllRoom = limits.getMaxLossLimit() - totalDrawdown;

        // 3b. Trade-frequency gates (scalp discipline; ported semantics from
        // the Monte Carlo RiskProfile). A limit of 0 disables the gate, so
        // legacy profiles (topstep50k/100k/150k) enforce neither. BLOCKING:
        // these run before sizing so a blocked day never reaches the market.
        if (limits.getMaxTradesPerDay() > 0
                && account.getTradesToday() >= limits.getMaxTradesPerDay()) {
            return deny(signal, "RISK: Max trades per day reached: "
                    + account.getTradesToday() + " >= " + limits.getMaxTradesPerDay(),
                    account.getTradesToday(), limits.getMaxTradesPerDay());
        }
        if (limits.getMaxConsecutiveLosses() > 0
                && account.getConsecutiveLosses() >= limits.getMaxConsecutiveLosses()) {
            return deny(signal, "RISK: Max consecutive losses reached: "
                    + account.getConsecutiveLosses() + " >= " + limits.getMaxConsecutiveLosses(),
                    account.getConsecutiveLosses(), limits.getMaxConsecutiveLosses());
        }

        // 4. Geometry.
        double stopDistance = Math.abs(signal.getEntryPrice() - signal.getStopPrice());
        if (stopDistance <= 0) {
            return deny(signal, "RISK: Invalid stop distance: " + stopDistance, stopDistance, 0);
        }
        double tickValue = getTickValue(sym);
        double tickSize = getTickSize(sym);

        // 5. SIZE — ONE sizer (V5 RC-14). The budget is the per-trade risk
        //    capped by the DLL room AND the MLL room; the risk-derived size
        //    comes from the SAME function the strategy used
        //    (StdvOteSizer.riskDerived). The strategy's requested size is
        //    HONOURED when it fits the envelope; it is never silently
        //    re-sized — an oversize request is reduced LOUDLY (reason +
        //    log) to the envelope, and a stop too wide for even
        //    size.minMicros is DENIED with both dollar numbers.
        double budget = com.topstep.trading.strategy.stdvote.StdvOteSizer.riskBudget(
                dynamicRiskPerTrade, remainingDailyLoss, remainingMllRoom);
        int hardCap = Math.min(limits.getMaxContracts(), RiskConfig.maxMicros());
        int minMicros = RiskConfig.minMicros();
        com.topstep.trading.strategy.stdvote.StdvOteSizer.RiskSize derived =
                com.topstep.trading.strategy.stdvote.StdvOteSizer.riskDerived(
                        budget, signal.getEntryPrice(), signal.getStopPrice(),
                        tickSize, tickValue, minMicros, hardCap);
        double dollarRiskPerContract = derived.perContract();
        System.out.println("[POSITION SIZING] " + sym +
            ": stopDist=" + String.format("%.4f", stopDistance) +
            ", tickSize=" + tickSize +
            ", ticks=" + String.format("%.1f", derived.stopTicks()) +
            ", $/contract=$" + String.format("%.2f", dollarRiskPerContract) +
            ", budget=$" + String.format("%.2f", budget) +
            ", requested=" + signal.getQuantity());
        if (derived.denied()) {
            return deny(signal, derived.reason(), derived.needDollars(), derived.haveDollars());
        }
        // Envelope for the REQUESTED size (FABLE-REJECT #1): the $ risk of
        // the FINAL order never exceeds the budget. derived.contracts() is
        // exactly floor(budget / $perMicro) capped at min(maxContracts,
        // size.maxMicros) — budget already = min(riskPerTrade, DLL room,
        // MLL room). A request above it is DENIED with both numbers; the
        // engine never trims (and never silently re-sizes).
        int envelope = derived.contracts();
        int requested = signal.getQuantity();
        int quantity;
        String sizeNote;
        if (requested <= 0) {
            quantity = envelope;
            sizeNote = "risk-derived " + quantity;
        } else if (requested <= envelope) {
            quantity = requested;
            sizeNote = "honoured requested " + requested + " (risk-derived max " + envelope + ")";
        } else {
            double reqRisk = requested * dollarRiskPerContract;
            if (reqRisk > budget + 1e-9) {
                return deny(signal, String.format(
                        "RISK: requested %d micros x $%.2f = $%.2f > risk budget $%.2f (max %d micros)",
                        requested, dollarRiskPerContract, reqRisk, budget, envelope),
                        reqRisk, budget);
            }
            return deny(signal, String.format(
                    "RISK: requested %d micros > max contracts %d (maxContracts %d, size.maxMicros %d)",
                    requested, hardCap, limits.getMaxContracts(), RiskConfig.maxMicros()),
                    requested, hardCap);
        }

        // 6. Check total contracts limit — SACRED (max contracts).
        int currentContracts = account.getTotalContracts();
        if (currentContracts + quantity > limits.getMaxTotalContracts()) {
            return deny(signal, "RISK: Would exceed max total contracts: " +
                    (currentContracts + quantity) + " > " + limits.getMaxTotalContracts(),
                    currentContracts + quantity, limits.getMaxTotalContracts());
        }

        // 7. R:R — ONE band (V5 RC-13): the floor is RiskLimits.rrFloor, the
        //    same value the validator's M7 reads. There is NO second ceiling
        //    here any more: the band's ceiling (rrCeiling) is enforced once,
        //    by the validator against the final target.
        double targetDistance = Math.abs(signal.getTargetPrice() - signal.getEntryPrice());
        double rewardRiskRatio = targetDistance / stopDistance;

        if (rewardRiskRatio + 1e-9 < limits.getRrFloor()) {
            return deny(signal, "RISK: R:R too low: " + String.format("%.2f", rewardRiskRatio) +
                    " < " + limits.getRrFloor(), rewardRiskRatio, limits.getRrFloor());
        }

        // 8. Build the order - round limit price to valid tick
        double roundedPrice = roundToTick(signal.getEntryPrice(), tickSize);
        Order order = Order.builder()
                .symbol(sym)
                .side(signal.getSide())
                .type(OrderType.LIMIT)
                .quantity(quantity)
                .limitPrice(roundedPrice)
                .build();

        String approvalReason = String.format(
                "Approved: %d contracts (%s), $%.2f risk ($%.2f/micro), budget $%.2f (base $%.2f), R:R %.2f:1, DLL room: $%.2f",
                quantity,
                sizeNote,
                quantity * dollarRiskPerContract,
                dollarRiskPerContract,
                budget,
                limits.getRiskPerTrade(),
                rewardRiskRatio,
                remainingDailyLoss
        );

        return RiskDecision.allow(order, approvalReason);
    }

    // ── Telemetry (V5 RC-17): every deny publishes a GateDecisionEvent ──
    private volatile com.topstep.trading.event.EventBus eventBus;
    private final java.util.concurrent.atomic.AtomicLong denials = new java.util.concurrent.atomic.AtomicLong();

    /** Optional: publish a GateDecisionEvent for every denial. */
    public void setEventBus(com.topstep.trading.event.EventBus bus) {
        this.eventBus = bus;
    }

    /** Total denials since construction (telemetry counter). */
    public long getDenialCount() {
        return denials.get();
    }

    private RiskDecision deny(StrategySignalEvent signal, String reason, double a, double b) {
        denials.incrementAndGet();
        String gate = reason.startsWith("SIZE") ? "SIZE" : "RISK";
        com.topstep.trading.event.EventBus bus = this.eventBus;
        if (bus != null) {
            com.topstep.trading.event.EngineTelemetry.publish(bus, new com.topstep.trading.event.GateDecisionEvent(
                    signal.getSymbol(), signal.getCandleTime(), null, "SIGNAL",
                    gate, reason, a, b));
        }
        return RiskDecision.deny(reason);
    }

    /**
     * Get tick value for a given symbol (dollar value per tick).
     */
    private double getTickValue(String symbol) {
        switch (symbol.toUpperCase()) {
            case "ES":
                return TICK_VALUE_ES;
            case "MES":
                return TICK_VALUE_MES;  // Micro ES: $1.25/tick
            case "NQ":
                return TICK_VALUE_NQ;
            case "MNQ":
                return TICK_VALUE_MNQ;  // Micro NQ: $0.50/tick
            case "GC":
                return TICK_VALUE_GC;
            case "MGC":
                return TICK_VALUE_MGC;  // Micro Gold: $1.00/tick
            case "NG":
                return TICK_VALUE_NG;
            case "SI":
                return TICK_VALUE_SI;
            default:
                // Default to ES tick value for unknown instruments
                System.out.println("[RISK] WARNING: Unknown symbol '" + symbol + "' using ES tick value");
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
            case "GC":
            case "MGC":
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
            case "SI":
                // Silver: $25/tick - needs 4x to handle $0.25 stop ($1,000 max)
                return 4.0;
            case "NQ":
                // NQ: $5/tick, 50-point stop = 200 ticks * $5 = $1,000 - needs 4x
                return 4.0;
            case "MNQ":
                // MNQ: $0.50/tick - micro contract is 1/10th so less risk multiplier needed
                return 2.0;
            case "GC":
            case "NG":
                // Gold, NatGas: $10/tick - needs 3x to handle typical stops ($750 max)
                return 3.0;
            case "MGC":
                // Micro Gold: $1.00/tick - needs less multiplier
                return 2.0;
            case "MES":
                // Micro contracts have smaller tick values - standard multiplier
                return 2.0;
            default:
                // ES and others: standard 2x ($500 max)
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
