package com.topstep.trading.strategy;

/**
 * Configuration for a tradeable instrument including tick values,
 * contract IDs, and trading characteristics.
 */
public class InstrumentConfig {
    private final String symbol;
    private final String contractId;
    private final double tickSize;
    private final double tickValue;
    private final double pointValue;
    private final String smtSymbol;        // Correlated symbol for SMT divergence
    private final double typicalAtr;       // Typical daily ATR for this instrument
    private final double maxPositionSize;  // Max contracts for this instrument

    public InstrumentConfig(String symbol, String contractId, double tickSize,
                           double tickValue, double pointValue, String smtSymbol,
                           double typicalAtr, double maxPositionSize) {
        this.symbol = symbol;
        this.contractId = contractId;
        this.tickSize = tickSize;
        this.tickValue = tickValue;
        this.pointValue = pointValue;
        this.smtSymbol = smtSymbol;
        this.typicalAtr = typicalAtr;
        this.maxPositionSize = maxPositionSize;
    }

    // Factory methods for common instruments

    /**
     * NQ - E-mini NASDAQ 100
     */
    public static InstrumentConfig nq() {
        return new InstrumentConfig(
            "NQ",
            "CON.F.US.ENQ.H26",  // March 2026 contract
            0.25,               // Tick size
            5.0,                // $5 per tick
            20.0,               // $20 per point (4 ticks)
            "ES",               // SMT with ES
            150.0,              // Typical daily ATR ~150 points
            2                   // Max 2 contracts
        );
    }

    /**
     * ES - E-mini S&P 500
     */
    public static InstrumentConfig es() {
        return new InstrumentConfig(
            "ES",
            "CON.F.US.EP.H26",
            0.25,
            12.50,
            50.0,
            "NQ",
            50.0,
            2
        );
    }

    /**
     * GC - Gold Futures
     * NOTE: Full-size GC is RESTRICTED by Topstep (Feb 2026).
     * All gold trades use MGC (Micro Gold) instead.
     * This config is kept for data analysis; orders go to MGC.
     */
    public static InstrumentConfig gold() {
        return new InstrumentConfig(
            "GC",
            "CON.F.US.GC.G26",  // February 2026 Gold
            0.10,
            10.0,              // $10 per tick
            100.0,             // $100 per point
            "SI",              // SMT with Silver
            25.0,              // Typical ATR ~$25
            1
        );
    }

    /**
     * MGC - Micro Gold Futures
     * Used instead of GC due to Topstep trading restrictions.
     * Topstep limits: 5 contracts for $50K account.
     * Min 4 contracts, max 5 for highest tier setups.
     */
    public static InstrumentConfig microGold() {
        return new InstrumentConfig(
            "MGC",
            "CON.F.US.MGC.G26",  // February 2026 Micro Gold
            0.10,
            1.0,               // $1.00 per tick (1/10th of GC)
            10.0,              // $10 per point (1/10th of GC)
            "SI",              // SMT with Silver
            25.0,              // Same ATR as GC (same price)
            5                  // Max 5 contracts ($50K account)
        );
    }

    /**
     * CL - Crude Oil
     */
    public static InstrumentConfig crudeOil() {
        return new InstrumentConfig(
            "CL",
            "CON.F.US.CL.G26",
            0.01,
            10.0,
            1000.0,
            "NG",              // SMT with Natural Gas
            2.0,               // Typical ATR ~$2
            1
        );
    }

    /**
     * 6E - Euro FX
     */
    public static InstrumentConfig euroFx() {
        return new InstrumentConfig(
            "6E",
            "CON.F.US.6E.H26",
            0.00005,
            6.25,
            125000.0,
            "6B",              // SMT with British Pound
            0.008,             // Typical ATR
            1
        );
    }

    /**
     * 6J - Japanese Yen
     * Min contracts: 3, max: 5 (raised to allow for 3-contract minimum setups)
     * Tick value: $6.25 per tick (12,500,000 JPY x 0.0000005)
     */
    public static InstrumentConfig japaneseYen() {
        return new InstrumentConfig(
            "6J",
            "CON.F.US.6J.H26",
            0.0000005,   // Tick size (0.5 pip)
            6.25,        // $6.25 per tick
            12500000.0,  // Point value
            "6E",        // SMT with Euro FX
            0.0007,      // Typical daily ATR
            5            // Max 5 contracts (raised to allow for 3-contract minimum setups)
        );
    }

    // Getters
    public String getSymbol() { return symbol; }
    public String getContractId() { return contractId; }
    public double getTickSize() { return tickSize; }
    public double getTickValue() { return tickValue; }
    public double getPointValue() { return pointValue; }
    public String getSmtSymbol() { return smtSymbol; }
    public double getTypicalAtr() { return typicalAtr; }
    public double getMaxPositionSize() { return maxPositionSize; }

    /**
     * Calculate dollar value of a price move.
     */
    public double calculatePnL(double priceMove, int contracts) {
        return (priceMove / tickSize) * tickValue * contracts;
    }

    @Override
    public String toString() {
        return String.format("%s (%s) - $%.2f/tick", symbol, contractId, tickValue);
    }
}
