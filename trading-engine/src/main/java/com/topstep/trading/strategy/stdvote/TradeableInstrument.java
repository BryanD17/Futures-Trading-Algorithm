package com.topstep.trading.strategy.stdvote;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Strict instrument allow-list for the STDV+OTE refactor.
 *
 * Only MNQ, MES, MGC are tradeable. Routing of full-size NQ/ES/GC, or any
 * other symbol, is rejected by the engine at startup (see SA5 wiring in
 * {@code MultiInstrumentEngine}). This registry is the single source of
 * truth for tick size, tick value, point value, micro size bounds, raid
 * quality threshold, and the SMT correlate symbol.
 *
 * The hard size band {@code [minMicros, maxMicros]} is {@code [5, 20]} for
 * all three instruments. If buffer-based sizing cannot fund 5 micros, the
 * order is skipped — partial sub-5 orders are never produced.
 *
 * Contract specs MUST satisfy {@code pointValue == tickValue / tickSize}
 * (asserted at construction). The user has been advised in the baseline
 * doc to confirm these against the broker before relying on them in live
 * sizing math.
 */
public final class TradeableInstrument {

    /** The three allowed symbols. */
    public enum Symbol {
        MNQ,
        MES,
        MGC
    }

    /** Immutable per-instrument spec carried through the engine. */
    public static final class Spec {
        private final Symbol symbol;
        private final double tickSize;
        private final double tickValue;
        private final double pointValue;
        private final int minMicros;
        private final int maxMicros;
        private final int raidMinQuality;
        private final String correlate;

        Spec(Symbol symbol,
             double tickSize,
             double tickValue,
             double pointValue,
             int minMicros,
             int maxMicros,
             int raidMinQuality,
             String correlate) {
            if (minMicros < 5 || maxMicros > 20 || minMicros > maxMicros) {
                throw new IllegalArgumentException(
                        "Size band out of [5,20] for " + symbol
                                + ": minMicros=" + minMicros
                                + " maxMicros=" + maxMicros);
            }
            double expectedPointValue = tickValue / tickSize;
            if (Math.abs(expectedPointValue - pointValue) > 1e-9) {
                throw new IllegalArgumentException(
                        "pointValue != tickValue/tickSize for " + symbol
                                + ": tickValue=" + tickValue
                                + " tickSize=" + tickSize
                                + " expected=" + expectedPointValue
                                + " actual=" + pointValue);
            }
            this.symbol = symbol;
            this.tickSize = tickSize;
            this.tickValue = tickValue;
            this.pointValue = pointValue;
            this.minMicros = minMicros;
            this.maxMicros = maxMicros;
            this.raidMinQuality = raidMinQuality;
            this.correlate = correlate;
        }

        public Symbol symbol() { return symbol; }
        public double tickSize() { return tickSize; }
        public double tickValue() { return tickValue; }
        public double pointValue() { return pointValue; }
        public int minMicros() { return minMicros; }
        public int maxMicros() { return maxMicros; }
        public int raidMinQuality() { return raidMinQuality; }
        public String correlate() { return correlate; }

        /**
         * Round a price to the instrument's tick. Uses banker's-style integer
         * rounding via {@link Math#round(double)} (round-half-up to even
         * integer in the integer math; price-level rounding precision is
         * dominated by tick size, not rounding mode).
         */
        public double roundToTick(double price) {
            return Math.round(price / tickSize) * tickSize;
        }
    }

    private static final Map<Symbol, Spec> SPECS;

    static {
        Map<Symbol, Spec> m = new LinkedHashMap<>();
        m.put(Symbol.MNQ, new Spec(Symbol.MNQ, 0.25, 0.50,  2.00, 5, 20, 5, "MES"));
        m.put(Symbol.MES, new Spec(Symbol.MES, 0.25, 1.25,  5.00, 5, 20, 5, "MNQ"));
        m.put(Symbol.MGC, new Spec(Symbol.MGC, 0.10, 1.00, 10.00, 5, 20, 6, "DXY"));
        SPECS = Collections.unmodifiableMap(m);
    }

    private TradeableInstrument() {
        // utility holder
    }

    /** Lookup spec by enum. */
    public static Spec of(Symbol s) {
        Spec sp = SPECS.get(s);
        if (sp == null) {
            throw new IllegalArgumentException("unknown symbol " + s);
        }
        return sp;
    }

    /**
     * Best-effort symbol resolution for an incoming string. Accepts only the
     * three allowed roots (case-insensitive). Returns empty for full-size
     * NQ/ES/GC and anything else — the caller must reject such orders.
     */
    public static Optional<Symbol> resolve(String raw) {
        if (raw == null) return Optional.empty();
        String s = raw.trim().toUpperCase();
        if (s.isEmpty()) return Optional.empty();
        try {
            return Optional.of(Symbol.valueOf(s));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public static boolean isTradeable(String raw) {
        return resolve(raw).isPresent();
    }

    /** All three specs in declaration order. */
    public static List<Spec> all() {
        return List.copyOf(SPECS.values());
    }
}
