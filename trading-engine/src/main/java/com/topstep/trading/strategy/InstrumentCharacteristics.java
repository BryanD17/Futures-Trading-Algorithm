package com.topstep.trading.strategy;

import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;

/**
 * Instrument-specific ICT strategy characteristics.
 *
 * Each instrument has unique behaviors based on:
 * - Market participants (institutions, retail, algos)
 * - Session activity (when smart money is active)
 * - Volatility patterns (ATR, typical ranges)
 * - Correlation relationships (SMT pairs)
 * - Price action tendencies (how it respects ICT levels)
 *
 * These configurations are based on ICT/SMC research for each market.
 */
public class InstrumentCharacteristics {

    // ============================================================
    // NQ - E-mini NASDAQ 100
    // ============================================================
    /**
     * NQ Characteristics:
     * - Tech-heavy index, driven by FAANG stocks
     * - Higher volatility than ES (1.5-2x the ATR)
     * - Responds strongly to: Tech earnings, FOMC, CPI
     * - Best session: NY AM killzone (9:30 AM - 12:00 PM ET)
     * - FVGs fill quickly due to high liquidity
     * - Order blocks are respected but need tighter stops
     * - SMT with ES is highly reliable
     * - Tends to make false breakouts before reversing
     */
    public static final InstrumentProfile NQ = InstrumentProfile.builder()
            .symbol("NQ")
            .contractId("CON.F.US.ENQ.H26")
            .name("E-mini NASDAQ 100")
            .tickSize(0.25)
            .tickValue(5.00)
            .pointValue(20.00)
            // ICT-specific settings
            .smtSymbol("ES")
            .smtCorrelation(0.95)
            .oteLow(0.62)           // Standard OTE
            .oteHigh(0.705)
            // Volatility settings
            .typicalDailyAtr(150.0)
            .lowVolatilityThreshold(80.0)
            .highVolatilityThreshold(200.0)
            .extremeVolatilityThreshold(300.0)
            // Position sizing
            .baseContracts(1)
            .maxContracts(2)
            .lowVolContracts(2)
            // Stop/Target adjustments
            .stopBufferPoints(5.0)
            .minStopDistance(15.0)
            .maxStopDistance(40.0)
            // Killzone preferences
            .primaryKillzones(Arrays.asList("NY_AM", "NY_PM"))
            .killzoneStartBuffer(30)  // Wait 30 min into killzone
            .killzoneEndBuffer(45)    // Stop 45 min before end
            // Behavior characteristics
            .fvgFillSpeed(0.8)        // 80% of FVGs fill within session
            .obRespectRate(0.7)       // 70% of OBs get reaction
            .breakerReliability(0.85) // 85% breaker block success
            .sweepFakeoutRate(0.6)    // 60% of sweeps are fakeouts (good for us)
            // Special notes
            .notes("Tech-driven, high volatility, excellent SMT with ES. Best for momentum plays.")
            .build();

    // ============================================================
    // ES - E-mini S&P 500
    // ============================================================
    /**
     * ES Characteristics:
     * - Broader market index, more stable than NQ
     * - Lower volatility, smoother price action
     * - Responds to: FOMC, NFP, GDP, broad market sentiment
     * - Best session: NY AM killzone, London/NY overlap
     * - FVGs take longer to fill (more patience needed)
     * - Order blocks are highly respected
     * - Most liquid futures contract - tight spreads
     * - More ranging behavior, fewer explosive moves
     */
    public static final InstrumentProfile ES = InstrumentProfile.builder()
            .symbol("ES")
            .contractId("CON.F.US.EP.H26")
            .name("E-mini S&P 500")
            .tickSize(0.25)
            .tickValue(12.50)
            .pointValue(50.00)
            // ICT-specific settings
            .smtSymbol("NQ")
            .smtCorrelation(0.95)
            .oteLow(0.62)
            .oteHigh(0.705)
            // Volatility settings
            .typicalDailyAtr(50.0)
            .lowVolatilityThreshold(30.0)
            .highVolatilityThreshold(70.0)
            .extremeVolatilityThreshold(100.0)
            // Position sizing
            .baseContracts(1)
            .maxContracts(2)
            .lowVolContracts(2)
            // Stop/Target adjustments
            .stopBufferPoints(2.0)
            .minStopDistance(5.0)
            .maxStopDistance(15.0)
            // Killzone preferences
            .primaryKillzones(Arrays.asList("NY_AM", "NY_PM", "LONDON_NY_OVERLAP"))
            .killzoneStartBuffer(30)
            .killzoneEndBuffer(30)
            // Behavior characteristics
            .fvgFillSpeed(0.6)        // 60% fill rate - more patient
            .obRespectRate(0.75)      // 75% OB respect - very reliable
            .breakerReliability(0.80)
            .sweepFakeoutRate(0.55)
            // Special notes
            .notes("Most liquid, stable, reliable ICT levels. Good for beginners.")
            .build();

    // ============================================================
    // GC - Gold Futures (COMEX)
    // ============================================================
    /**
     * GC (Gold) Characteristics:
     * - Safe haven asset, inverse correlation to USD
     * - BEST during London session (3 AM - 12 PM ET)
     * - Responds to: DXY, inflation data, geopolitical events, Fed policy
     * - Very clean ICT price action during London
     * - Order blocks are HIGHLY respected (institutional participation)
     * - Wider stops needed - Gold is "sweepy"
     * - SMT with Silver (SI) is reliable but slower
     * - Power of 3 works exceptionally well on Gold
     * - Asian session accumulation → London manipulation → NY distribution
     */
    public static final InstrumentProfile GC = InstrumentProfile.builder()
            .symbol("GC")
            .contractId("CON.F.US.GC.G26")
            .name("Gold Futures")
            .tickSize(0.10)
            .tickValue(10.00)
            .pointValue(100.00)
            // ICT-specific settings
            .smtSymbol("SI")          // Silver for SMT
            .smtCorrelation(0.85)
            .inverseCorrSymbol("DX")  // Dollar index - inverse
            .inverseCorrelation(-0.80)
            .oteLow(0.62)
            .oteHigh(0.79)            // Gold often goes deeper into OTE
            // Volatility settings (in dollars, not points)
            .typicalDailyAtr(25.0)    // $25 typical daily range
            .lowVolatilityThreshold(15.0)
            .highVolatilityThreshold(35.0)
            .extremeVolatilityThreshold(50.0)
            // Position sizing
            .baseContracts(1)
            .maxContracts(1)          // Gold is expensive, keep size small
            .lowVolContracts(1)
            // Stop/Target adjustments
            .stopBufferPoints(2.0)    // $2 buffer
            .minStopDistance(5.0)     // Min $5 stop
            .maxStopDistance(15.0)    // Max $15 stop
            // Killzone preferences - LONDON FOCUSED
            .primaryKillzones(Arrays.asList("LONDON", "LONDON_NY_OVERLAP"))
            .secondaryKillzones(Arrays.asList("NY_AM"))
            .killzoneStartBuffer(15)  // Gold moves fast, less buffer
            .killzoneEndBuffer(30)
            // Behavior characteristics
            .fvgFillSpeed(0.5)        // 50% - Gold leaves gaps unfilled longer
            .obRespectRate(0.85)      // 85% - Excellent OB respect
            .breakerReliability(0.90) // 90% - Breakers are gold (pun intended)
            .sweepFakeoutRate(0.70)   // 70% - Gold loves to sweep and reverse
            .power3Reliability(0.85)  // Power of 3 works great on Gold
            // Special notes
            .notes("Best during London. Watch DXY inverse correlation. Wider OTE zone. Power of 3 is key.")
            .build();

    // ============================================================
    // CL - Crude Oil Futures (NYMEX)
    // ============================================================
    /**
     * CL (Crude Oil) Characteristics:
     * - HIGHLY volatile, can move $2-5 in minutes
     * - Responds to: Inventory data (Wed 10:30 AM ET), OPEC, geopolitics
     * - Best session: NY session, especially around inventory release
     * - ICT levels work but need WIDER stops
     * - Quick moves - partials are essential
     * - SMT with Natural Gas (NG) is loose but usable
     * - Correlation with CAD (6C) due to Canada oil exports
     * - Avoid trading during inventory release (too chaotic)
     * - Power of 3 happens FAST on oil
     */
    public static final InstrumentProfile CL = InstrumentProfile.builder()
            .symbol("CL")
            .contractId("CON.F.US.CL.G26")
            .name("Crude Oil Futures")
            .tickSize(0.01)
            .tickValue(10.00)
            .pointValue(1000.00)
            // ICT-specific settings
            .smtSymbol("NG")          // Natural Gas (loose correlation)
            .smtCorrelation(0.50)     // Lower correlation
            .correlatedCurrency("6C") // Canadian Dollar
            .currencyCorrelation(0.70)
            .oteLow(0.50)             // Oil often reverses earlier
            .oteHigh(0.705)
            // Volatility settings (in dollars)
            .typicalDailyAtr(2.00)    // $2 typical daily range
            .lowVolatilityThreshold(1.00)
            .highVolatilityThreshold(3.00)
            .extremeVolatilityThreshold(5.00)
            // Position sizing - BE CAREFUL with oil
            .baseContracts(1)
            .maxContracts(1)
            .lowVolContracts(1)
            // Stop/Target adjustments
            .stopBufferPoints(0.20)   // $0.20 buffer
            .minStopDistance(0.30)    // Min $0.30 stop ($300)
            .maxStopDistance(0.80)    // Max $0.80 stop ($800)
            // Killzone preferences
            .primaryKillzones(Arrays.asList("NY_AM", "NY_PM"))
            .killzoneStartBuffer(30)
            .killzoneEndBuffer(60)    // Stop earlier - oil gets choppy
            // Avoid times
            .avoidTimes(Arrays.asList(
                LocalTime.of(10, 25),  // 5 min before inventory
                LocalTime.of(10, 35)   // 5 min after inventory
            ))
            // Behavior characteristics
            .fvgFillSpeed(0.9)        // 90% - Oil fills gaps FAST
            .obRespectRate(0.60)      // 60% - Less reliable, needs confirmation
            .breakerReliability(0.70)
            .sweepFakeoutRate(0.50)   // 50/50 - harder to read
            .power3Reliability(0.70)
            // Special notes
            .notes("VERY volatile. Wider stops essential. Avoid inventory releases. Quick partials.")
            .build();

    // ============================================================
    // 6E - Euro FX Futures (CME)
    // ============================================================
    /**
     * 6E (Euro) Characteristics:
     * - Most traded currency pair (EUR/USD futures)
     * - INVERSE correlation to DXY (Dollar Index)
     * - Best session: London and London/NY overlap
     * - Responds to: ECB, Fed, NFP, EU economic data
     * - Very clean ICT price action
     * - Order blocks are respected well
     * - SMT with British Pound (6B) is reliable
     * - Tends to range during Asian session, move during London
     * - Power of 3 works well - Asian accumulation → London move
     */
    public static final InstrumentProfile EURO = InstrumentProfile.builder()
            .symbol("6E")
            .contractId("CON.F.US.6E.H26")
            .name("Euro FX Futures")
            .tickSize(0.00005)
            .tickValue(6.25)
            .pointValue(125000.00)
            // ICT-specific settings
            .smtSymbol("6B")          // British Pound
            .smtCorrelation(0.85)
            .inverseCorrSymbol("DX")
            .inverseCorrelation(-0.95)
            .oteLow(0.62)
            .oteHigh(0.705)
            // Volatility settings (in pips, 1 pip = 0.0001)
            .typicalDailyAtr(0.0080)  // 80 pips typical
            .lowVolatilityThreshold(0.0050)
            .highVolatilityThreshold(0.0120)
            .extremeVolatilityThreshold(0.0200)
            // Position sizing
            .baseContracts(1)
            .maxContracts(2)
            .lowVolContracts(2)
            // Stop/Target adjustments (in price terms)
            .stopBufferPoints(0.0005) // 5 pips buffer
            .minStopDistance(0.0010)  // 10 pips min
            .maxStopDistance(0.0030)  // 30 pips max
            // Killzone preferences - LONDON FOCUSED
            .primaryKillzones(Arrays.asList("LONDON", "LONDON_NY_OVERLAP"))
            .secondaryKillzones(Arrays.asList("NY_AM"))
            .killzoneStartBuffer(20)
            .killzoneEndBuffer(30)
            // Behavior characteristics
            .fvgFillSpeed(0.65)
            .obRespectRate(0.75)
            .breakerReliability(0.80)
            .sweepFakeoutRate(0.65)
            .power3Reliability(0.80)
            // Special notes
            .notes("Clean ICT action. Best during London. Watch DXY inverse. SMT with 6B reliable.")
            .build();

    // ============================================================
    // 6J - Japanese Yen Futures (CME)
    // ============================================================
    /**
     * 6J (Yen) Characteristics:
     * - Safe haven currency (like Gold)
     * - Best session: Asian session and London open
     * - Responds to: BOJ policy, risk sentiment, carry trade flows
     * - INVERSE to risk assets (when stocks fall, Yen rises)
     * - Slower price action, good for patient traders
     * - Order blocks hold well due to institutional flows
     * - SMT with Euro (6E) is moderate
     * - Unique: Yen strengthens during market fear
     */
    public static final InstrumentProfile YEN = InstrumentProfile.builder()
            .symbol("6J")
            .contractId("CON.F.US.6J.H26")
            .name("Japanese Yen Futures")
            .tickSize(0.0000005)
            .tickValue(6.25)
            .pointValue(12500000.00)
            // ICT-specific settings
            .smtSymbol("6E")
            .smtCorrelation(0.60)     // Moderate correlation
            .oteLow(0.62)
            .oteHigh(0.79)            // Yen can go deeper
            // Volatility settings
            .typicalDailyAtr(0.00070)
            .lowVolatilityThreshold(0.00040)
            .highVolatilityThreshold(0.00100)
            .extremeVolatilityThreshold(0.00150)
            // Position sizing
            .baseContracts(1)
            .maxContracts(1)
            .lowVolContracts(1)
            // Stop/Target adjustments
            .stopBufferPoints(0.000050)
            .minStopDistance(0.000100)
            .maxStopDistance(0.000300)
            // Killzone preferences - ASIAN FOCUSED
            .primaryKillzones(Arrays.asList("ASIAN", "LONDON"))
            .killzoneStartBuffer(15)
            .killzoneEndBuffer(30)
            // Behavior characteristics
            .fvgFillSpeed(0.50)       // Slow fills
            .obRespectRate(0.80)
            .breakerReliability(0.75)
            .sweepFakeoutRate(0.55)
            .power3Reliability(0.70)
            // Special notes
            .notes("Safe haven - rises during fear. Best in Asian session. Patient trading required.")
            .build();

    /**
     * Get instrument profile by symbol.
     */
    public static InstrumentProfile getProfile(String symbol) {
        switch (symbol.toUpperCase()) {
            case "NQ": return NQ;
            case "ES": return ES;
            case "GC": return GC;
            case "CL": return CL;
            case "6E": return EURO;
            case "6J": return YEN;
            default: return NQ; // Default to NQ
        }
    }

    /**
     * Get all available profiles.
     */
    public static List<InstrumentProfile> getAllProfiles() {
        return Arrays.asList(NQ, ES, GC, CL, EURO, YEN);
    }
}
