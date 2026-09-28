package com.topstep.trading.domain;

/**
 * AGENT-05 (V5 RC-17): tick SIZE per root symbol so dollar P&amp;L is computed
 * as {@code points / tickSize * tickValue} — never {@code points * tickValue}
 * (which under-reported MNQ P&amp;L 4x: $0.50/tick is $2.00/point).
 * Values match TradeableInstrument / PropFirmRiskEngine / TopstepConnector.
 */
public final class ContractSpecs {

    private ContractSpecs() {}

    /** Minimum price increment for a root symbol; 0.25 (index) when unknown. */
    public static double tickSize(String symbol) {
        if (symbol == null) return 0.25;
        switch (symbol.trim().toUpperCase()) {
            case "ES": case "MES": case "NQ": case "MNQ": return 0.25;
            case "YM": case "MYM": return 1.0;
            case "RTY": case "M2K": return 0.10;
            case "GC": case "MGC": return 0.10;
            case "SI": return 0.005;
            case "NG": return 0.001;
            default: return 0.25;
        }
    }

    /** Dollar value of one full POINT given a per-tick value. */
    public static double pointValue(String symbol, double tickValue) {
        return tickValue / tickSize(symbol);
    }
}
