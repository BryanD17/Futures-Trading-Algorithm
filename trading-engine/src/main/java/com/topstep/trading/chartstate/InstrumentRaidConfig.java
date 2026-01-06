package com.topstep.trading.chartstate;

import java.util.HashMap;
import java.util.Map;

/**
 * Instrument-specific configuration for raid detection.
 *
 * Different instruments have different characteristics:
 * - ES is relatively calm, needs tighter tolerances
 * - NQ is more volatile, needs wider tolerances
 * - GC (Gold) can be choppy, needs clearer raids
 * - CL (Oil) is volatile, especially during inventory releases
 *
 * These configurations tune the raid detection sensitivity per instrument
 * to filter out noise while capturing genuine liquidity raids.
 */
public class InstrumentRaidConfig {

    private final String symbol;

    // Tolerance settings
    private final double toleranceTicks;          // How close counts as "at level"
    private final double minPenetrationTicks;     // Minimum sweep beyond level
    private final double strongPenetrationTicks;  // "Strong" penetration for scoring bonus

    // Raid lifecycle
    private final int maxBarsSinceRaid;           // Max bars before raid expires
    private final int minQualityThreshold;        // Minimum score to emit raid signal

    // Tick information
    private final double tickSize;                // Price per tick
    private final double tickValue;               // Dollar value per tick

    // Session-specific tolerance multipliers
    private final double asiaMultiplier;          // Usually 0.7 (tighter in Asia)
    private final double londonMultiplier;        // Usually 1.0 (standard)
    private final double nyMultiplier;            // Usually 1.0 (standard)

    private InstrumentRaidConfig(Builder builder) {
        this.symbol = builder.symbol;
        this.toleranceTicks = builder.toleranceTicks;
        this.minPenetrationTicks = builder.minPenetrationTicks;
        this.strongPenetrationTicks = builder.strongPenetrationTicks;
        this.maxBarsSinceRaid = builder.maxBarsSinceRaid;
        this.minQualityThreshold = builder.minQualityThreshold;
        this.tickSize = builder.tickSize;
        this.tickValue = builder.tickValue;
        this.asiaMultiplier = builder.asiaMultiplier;
        this.londonMultiplier = builder.londonMultiplier;
        this.nyMultiplier = builder.nyMultiplier;
    }

    // ═══════════════════════════════════════════════════════════════════
    // GETTERS
    // ═══════════════════════════════════════════════════════════════════

    public String getSymbol() { return symbol; }
    public double getToleranceTicks() { return toleranceTicks; }
    public double getMinPenetrationTicks() { return minPenetrationTicks; }
    public double getStrongPenetrationTicks() { return strongPenetrationTicks; }
    public int getMaxBarsSinceRaid() { return maxBarsSinceRaid; }
    public int getMinQualityThreshold() { return minQualityThreshold; }
    public double getTickSize() { return tickSize; }
    public double getTickValue() { return tickValue; }
    public double getAsiaMultiplier() { return asiaMultiplier; }
    public double getLondonMultiplier() { return londonMultiplier; }
    public double getNyMultiplier() { return nyMultiplier; }

    /**
     * Get tolerance in price units.
     */
    public double getTolerancePrice() {
        return toleranceTicks * tickSize;
    }

    /**
     * Get minimum penetration in price units.
     */
    public double getMinPenetrationPrice() {
        return minPenetrationTicks * tickSize;
    }

    // ═══════════════════════════════════════════════════════════════════
    // STATIC FACTORY METHODS - PRE-CONFIGURED INSTRUMENTS
    // ═══════════════════════════════════════════════════════════════════

    /**
     * E-mini S&P 500 (ES) configuration.
     * ES is relatively stable - tight tolerances work well.
     */
    public static InstrumentRaidConfig forES() {
        return new Builder("ES")
                .tickSize(0.25)
                .tickValue(12.50)
                .toleranceTicks(2.0)           // 2 ticks = 0.5 points
                .minPenetrationTicks(2.0)      // Must sweep by at least 2 ticks
                .strongPenetrationTicks(4.0)   // 4+ ticks is strong penetration
                .maxBarsSinceRaid(10)
                .minQualityThreshold(4)
                .build();
    }

    /**
     * E-mini NASDAQ 100 (NQ) configuration.
     * NQ is more volatile than ES - needs wider tolerances.
     */
    public static InstrumentRaidConfig forNQ() {
        return new Builder("NQ")
                .tickSize(0.25)
                .tickValue(5.00)
                .toleranceTicks(4.0)           // 4 ticks = 1 point
                .minPenetrationTicks(4.0)      // Must sweep by at least 4 ticks
                .strongPenetrationTicks(8.0)   // 8+ ticks is strong penetration
                .maxBarsSinceRaid(10)
                .minQualityThreshold(4)
                .build();
    }

    /**
     * Gold futures (GC) configuration.
     * Gold can be choppy - needs clearer raids with higher quality threshold.
     */
    public static InstrumentRaidConfig forGC() {
        return new Builder("GC")
                .tickSize(0.10)
                .tickValue(10.00)
                .toleranceTicks(3.0)           // 3 ticks = 0.3 points
                .minPenetrationTicks(5.0)      // Must sweep by at least 5 ticks
                .strongPenetrationTicks(10.0)  // 10+ ticks is strong penetration
                .maxBarsSinceRaid(15)          // Gold moves slower
                .minQualityThreshold(5)        // Higher threshold for gold
                .build();
    }

    /**
     * Crude Oil futures (CL) configuration.
     * Oil is volatile - needs clear penetration.
     */
    public static InstrumentRaidConfig forCL() {
        return new Builder("CL")
                .tickSize(0.01)
                .tickValue(10.00)
                .toleranceTicks(3.0)           // 3 ticks = 0.03 points
                .minPenetrationTicks(5.0)      // Must sweep by at least 5 ticks
                .strongPenetrationTicks(10.0)  // 10+ ticks is strong penetration
                .maxBarsSinceRaid(10)
                .minQualityThreshold(4)
                .build();
    }

    /**
     * Natural Gas futures (NG) configuration.
     * NG is extremely volatile.
     */
    public static InstrumentRaidConfig forNG() {
        return new Builder("NG")
                .tickSize(0.001)
                .tickValue(10.00)
                .toleranceTicks(5.0)           // 5 ticks = 0.005 points
                .minPenetrationTicks(10.0)     // Must sweep by at least 10 ticks
                .strongPenetrationTicks(20.0)  // 20+ ticks is strong penetration
                .maxBarsSinceRaid(10)
                .minQualityThreshold(5)        // Higher threshold due to volatility
                .build();
    }

    /**
     * Euro FX futures (6E) configuration.
     */
    public static InstrumentRaidConfig for6E() {
        return new Builder("6E")
                .tickSize(0.00005)
                .tickValue(6.25)
                .toleranceTicks(2.0)           // 2 ticks = 1 pip
                .minPenetrationTicks(3.0)      // Must sweep by at least 3 ticks
                .strongPenetrationTicks(6.0)   // 6+ ticks is strong penetration
                .maxBarsSinceRaid(10)
                .minQualityThreshold(4)
                .build();
    }

    /**
     * British Pound futures (6B) configuration.
     */
    public static InstrumentRaidConfig for6B() {
        return new Builder("6B")
                .tickSize(0.0001)
                .tickValue(6.25)
                .toleranceTicks(2.0)           // 2 ticks = 2 pips
                .minPenetrationTicks(3.0)      // Must sweep by at least 3 ticks
                .strongPenetrationTicks(6.0)   // 6+ ticks is strong penetration
                .maxBarsSinceRaid(10)
                .minQualityThreshold(4)
                .build();
    }

    /**
     * Japanese Yen futures (6J) configuration.
     */
    public static InstrumentRaidConfig for6J() {
        return new Builder("6J")
                .tickSize(0.0000005)
                .tickValue(6.25)
                .toleranceTicks(4.0)           // 4 ticks
                .minPenetrationTicks(6.0)      // Must sweep by at least 6 ticks
                .strongPenetrationTicks(12.0)  // 12+ ticks is strong penetration
                .maxBarsSinceRaid(10)
                .minQualityThreshold(4)
                .build();
    }

    /**
     * Silver futures (SI) configuration.
     */
    public static InstrumentRaidConfig forSI() {
        return new Builder("SI")
                .tickSize(0.005)
                .tickValue(25.00)
                .toleranceTicks(2.0)           // 2 ticks = 0.01 points
                .minPenetrationTicks(4.0)      // Must sweep by at least 4 ticks
                .strongPenetrationTicks(8.0)   // 8+ ticks is strong penetration
                .maxBarsSinceRaid(12)
                .minQualityThreshold(5)
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════
    // CONFIG REGISTRY
    // ═══════════════════════════════════════════════════════════════════

    private static final Map<String, InstrumentRaidConfig> CONFIGS = new HashMap<>();

    static {
        // Pre-register all known instruments
        register(forES());
        register(forNQ());
        register(forGC());
        register(forCL());
        register(forNG());
        register(for6E());
        register(for6B());
        register(for6J());
        register(forSI());
    }

    private static void register(InstrumentRaidConfig config) {
        CONFIGS.put(config.getSymbol().toUpperCase(), config);
    }

    /**
     * Get configuration for an instrument.
     * Returns default config if not found.
     */
    public static InstrumentRaidConfig forSymbol(String symbol) {
        InstrumentRaidConfig config = CONFIGS.get(symbol.toUpperCase());
        if (config != null) {
            return config;
        }
        // Return default config for unknown instruments
        return new Builder(symbol)
                .tickSize(0.01)
                .tickValue(10.00)
                .toleranceTicks(3.0)
                .minPenetrationTicks(5.0)
                .strongPenetrationTicks(10.0)
                .maxBarsSinceRaid(10)
                .minQualityThreshold(4)
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════
    // BUILDER
    // ═══════════════════════════════════════════════════════════════════

    public static class Builder {
        private final String symbol;
        private double toleranceTicks = 3.0;
        private double minPenetrationTicks = 5.0;
        private double strongPenetrationTicks = 10.0;
        private int maxBarsSinceRaid = 10;
        private int minQualityThreshold = 4;
        private double tickSize = 0.01;
        private double tickValue = 10.00;
        private double asiaMultiplier = 0.7;
        private double londonMultiplier = 1.0;
        private double nyMultiplier = 1.0;

        public Builder(String symbol) {
            this.symbol = symbol;
        }

        public Builder toleranceTicks(double ticks) {
            this.toleranceTicks = ticks;
            return this;
        }

        public Builder minPenetrationTicks(double ticks) {
            this.minPenetrationTicks = ticks;
            return this;
        }

        public Builder strongPenetrationTicks(double ticks) {
            this.strongPenetrationTicks = ticks;
            return this;
        }

        public Builder maxBarsSinceRaid(int bars) {
            this.maxBarsSinceRaid = bars;
            return this;
        }

        public Builder minQualityThreshold(int threshold) {
            this.minQualityThreshold = threshold;
            return this;
        }

        public Builder tickSize(double size) {
            this.tickSize = size;
            return this;
        }

        public Builder tickValue(double value) {
            this.tickValue = value;
            return this;
        }

        public Builder asiaMultiplier(double multiplier) {
            this.asiaMultiplier = multiplier;
            return this;
        }

        public Builder londonMultiplier(double multiplier) {
            this.londonMultiplier = multiplier;
            return this;
        }

        public Builder nyMultiplier(double multiplier) {
            this.nyMultiplier = multiplier;
            return this;
        }

        public InstrumentRaidConfig build() {
            return new InstrumentRaidConfig(this);
        }
    }

    @Override
    public String toString() {
        return String.format("InstrumentRaidConfig{%s: tol=%.1f, minPen=%.1f, maxBars=%d, minQ=%d}",
                symbol, toleranceTicks, minPenetrationTicks, maxBarsSinceRaid, minQualityThreshold);
    }
}
