package com.topstep.trading.news.impact;

import com.topstep.trading.news.model.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Maps economic events to trading instruments.
 * This is the core logic that determines which events affect which instruments and how.
 *
 * CRITICAL: Get these mappings right - they drive the entire news system.
 */
public class InstrumentNewsMapper {

    // Instrument symbols we trade
    public static final String ES = "ES";     // E-mini S&P 500
    public static final String NQ = "NQ";     // E-mini Nasdaq 100
    public static final String GC = "GC";     // Gold futures
    public static final String CL = "CL";     // Crude Oil futures
    public static final String SIX_E = "6E";  // Euro FX futures
    public static final String SIX_J = "6J";  // Japanese Yen futures

    private static final List<String> ALL_INSTRUMENTS = List.of(ES, NQ, GC, CL, SIX_E, SIX_J);

    /**
     * Returns relevance score (0.0 to 1.0) for how much an event affects an instrument.
     * Higher score = more relevant = stronger gating and bias effect.
     */
    public double getRelevance(EconomicEvent event, String instrument) {
        Currency eventCurrency = event.getCurrency();
        EventCategory category = event.getCategory();
        EventImpact impact = event.getImpact();

        return switch (instrument) {
            case ES, NQ -> getEquityIndexRelevance(event);
            case GC -> getGoldRelevance(event);
            case CL -> getCrudeOilRelevance(event);
            case SIX_E -> getEuroRelevance(event);
            case SIX_J -> getYenRelevance(event);
            default -> 0.0;
        };
    }

    /**
     * ES/NQ: US equity indices
     * - Highly sensitive to Fed, US inflation, US employment
     * - Moderately sensitive to growth data
     * - Minor sensitivity to foreign data (through risk sentiment)
     */
    private double getEquityIndexRelevance(EconomicEvent event) {
        if (event.getCurrency() != Currency.USD) {
            // Foreign events have minor impact through risk sentiment
            if (event.getImpact() == EventImpact.HIGH) {
                return 0.2;  // Major foreign events can move risk sentiment
            }
            return 0.0;
        }

        // USD events
        return switch (event.getCategory()) {
            case CENTRAL_BANK -> 1.0;   // Fed is king for equities
            case INFLATION -> 0.95;     // CPI/PCE directly affects Fed expectations
            case EMPLOYMENT -> 0.9;     // NFP is major market mover
            case GDP_GROWTH -> 0.7;
            case PMI_SURVEYS -> 0.6;
            case CONSUMER -> 0.5;       // Retail sales, consumer confidence
            case HOUSING -> 0.3;
            default -> 0.2;
        };
    }

    /**
     * GC (Gold):
     * - Inverse relationship with USD strength
     * - Bullish on inflation fears
     * - Safe haven during risk-off
     * - Fed decisions are critical (real yields)
     */
    private double getGoldRelevance(EconomicEvent event) {
        if (event.getCurrency() == Currency.USD) {
            return switch (event.getCategory()) {
                case CENTRAL_BANK -> 1.0;   // Fed policy = real yields = gold price
                case INFLATION -> 0.95;     // Higher inflation = gold bullish
                case EMPLOYMENT -> 0.7;     // Strong jobs = Fed hawkish = gold bearish
                case GDP_GROWTH -> 0.5;
                default -> 0.3;
            };
        }

        // Non-USD events have limited direct impact on gold
        if (event.getImpact() == EventImpact.HIGH) {
            return 0.2;  // Major risk events can trigger safe-haven flows
        }
        return 0.0;
    }

    /**
     * CL (Crude Oil):
     * - EIA inventory is THE event (special handling)
     * - OPEC-related news (not in standard calendars usually)
     * - USD has inverse correlation (priced in dollars)
     * - Global growth expectations affect demand outlook
     */
    private double getCrudeOilRelevance(EconomicEvent event) {
        // Special case: EIA Crude Inventory
        if (isEiaInventoryEvent(event)) {
            return 1.0;  // This is THE event for crude
        }

        if (event.getCurrency() == Currency.USD) {
            return switch (event.getCategory()) {
                case CENTRAL_BANK -> 0.5;   // USD strength/weakness affects oil
                case INFLATION -> 0.4;
                case GDP_GROWTH -> 0.5;     // Growth = demand expectations
                case PMI_SURVEYS -> 0.4;    // Manufacturing = oil demand
                default -> 0.2;
            };
        }

        // Chinese data affects oil demand expectations
        if (event.getCurrency() == Currency.CNY) {
            if (event.getCategory() == EventCategory.GDP_GROWTH ||
                event.getCategory() == EventCategory.PMI_SURVEYS) {
                return 0.5;
            }
        }

        return 0.1;
    }

    /**
     * 6E (Euro futures):
     * - EUR events are direct (bullish EUR = bullish 6E)
     * - USD events are inverse (bullish USD = bearish 6E)
     * - ECB vs Fed differential is key
     */
    private double getEuroRelevance(EconomicEvent event) {
        if (event.getCurrency() == Currency.EUR) {
            return switch (event.getCategory()) {
                case CENTRAL_BANK -> 1.0;   // ECB decisions are direct
                case INFLATION -> 0.85;     // EUR CPI affects ECB expectations
                case GDP_GROWTH -> 0.6;
                case PMI_SURVEYS -> 0.55;   // German PMI especially
                case EMPLOYMENT -> 0.5;
                default -> 0.3;
            };
        }

        if (event.getCurrency() == Currency.USD) {
            return switch (event.getCategory()) {
                case CENTRAL_BANK -> 0.95;  // Fed affects the other side of EUR/USD
                case INFLATION -> 0.8;
                case EMPLOYMENT -> 0.75;
                case GDP_GROWTH -> 0.5;
                default -> 0.3;
            };
        }

        return 0.0;
    }

    /**
     * 6J (Yen futures):
     * - JPY events are direct (but BOJ is famously dovish/stable)
     * - USD events are inverse
     * - Risk-off flows strengthen yen (safe haven)
     * - Carry trade dynamics matter
     */
    private double getYenRelevance(EconomicEvent event) {
        if (event.getCurrency() == Currency.JPY) {
            return switch (event.getCategory()) {
                case CENTRAL_BANK -> 1.0;   // BOJ decisions (rare policy changes are huge)
                case INFLATION -> 0.7;      // Japan CPI
                case GDP_GROWTH -> 0.4;
                default -> 0.2;
            };
        }

        if (event.getCurrency() == Currency.USD) {
            return switch (event.getCategory()) {
                case CENTRAL_BANK -> 0.95;  // Fed affects USD/JPY spread
                case INFLATION -> 0.8;
                case EMPLOYMENT -> 0.75;
                default -> 0.4;
            };
        }

        // Risk events can trigger yen safe-haven flows
        if (event.getImpact() == EventImpact.HIGH) {
            return 0.3;
        }

        return 0.0;
    }

    /**
     * Returns the directional relationship between an event surprise and instrument direction.
     *
     * @return +1 if positive surprise (better than expected) is BULLISH for instrument
     *         -1 if positive surprise is BEARISH for instrument
     *          0 if relationship is unclear/neutral
     */
    public int getDirectionalSign(EconomicEvent event, String instrument) {
        return switch (instrument) {
            case ES, NQ -> getEquityDirectionalSign(event);
            case GC -> getGoldDirectionalSign(event);
            case CL -> getCrudeDirectionalSign(event);
            case SIX_E -> getEuroDirectionalSign(event);
            case SIX_J -> getYenDirectionalSign(event);
            default -> 0;
        };
    }

    /**
     * Equity indices direction:
     * - Strong economy = bullish (unless Fed fears dominate)
     * - Hot inflation = bearish (Fed will hike)
     * - Hawkish Fed = bearish
     */
    private int getEquityDirectionalSign(EconomicEvent event) {
        if (event.getCurrency() != Currency.USD) {
            return 0;  // Foreign events: unclear direction
        }

        return switch (event.getCategory()) {
            case CENTRAL_BANK ->
                // Hawkish surprise = bearish for equities
                // Note: This is simplified - actual Fed reaction depends on statement
                -1;
            case INFLATION ->
                // Hot inflation = Fed fears = bearish
                -1;
            case EMPLOYMENT ->
                // Strong employment can be bullish (economy) or bearish (Fed)
                // In current regime, lean bearish (Fed-focused)
                -1;
            case GDP_GROWTH, PMI_SURVEYS, CONSUMER ->
                // Strong growth = bullish for equities
                1;
            default -> 0;
        };
    }

    /**
     * Gold direction:
     * - Inverse to USD strength
     * - Bullish on inflation
     * - Bullish on dovish Fed
     */
    private int getGoldDirectionalSign(EconomicEvent event) {
        if (event.getCurrency() != Currency.USD) {
            return 0;
        }

        return switch (event.getCategory()) {
            case CENTRAL_BANK ->
                // Hawkish Fed = higher real yields = bearish gold
                -1;
            case INFLATION ->
                // Hot inflation = gold hedge demand = bullish
                // But also = Fed hawkish = bearish
                // Net effect: slight bearish in Fed-focused regime
                -1;
            case EMPLOYMENT ->
                // Strong jobs = hawkish Fed = bearish gold
                -1;
            case GDP_GROWTH ->
                // Strong growth = risk-on = bearish gold (less safe-haven demand)
                -1;
            default -> 0;
        };
    }

    /**
     * Crude oil direction:
     * - EIA inventory: build = bearish, draw = bullish
     * - Strong economy = demand = bullish
     * - Strong USD = bearish (priced in dollars)
     */
    private int getCrudeDirectionalSign(EconomicEvent event) {
        if (isEiaInventoryEvent(event)) {
            // SPECIAL: Inventory BUILD (positive actual) = BEARISH
            // So positive surprise = bearish, return -1
            return -1;
        }

        if (event.getCurrency() == Currency.USD) {
            return switch (event.getCategory()) {
                case CENTRAL_BANK ->
                    // Hawkish = strong USD = bearish oil
                    -1;
                case GDP_GROWTH, PMI_SURVEYS ->
                    // Strong growth = demand = bullish oil
                    1;
                default -> 0;
            };
        }

        if (event.getCurrency() == Currency.CNY) {
            // Strong China = oil demand = bullish
            return 1;
        }

        return 0;
    }

    /**
     * Euro direction:
     * - EUR events: positive surprise = bullish 6E
     * - USD events: positive surprise = bearish 6E (inverse)
     */
    private int getEuroDirectionalSign(EconomicEvent event) {
        if (event.getCurrency() == Currency.EUR) {
            return switch (event.getCategory()) {
                case CENTRAL_BANK ->
                    // Hawkish ECB = bullish EUR
                    1;
                case INFLATION ->
                    // Hot EUR inflation = ECB hawkish = bullish EUR
                    1;
                case GDP_GROWTH, EMPLOYMENT, PMI_SURVEYS ->
                    // Strong EUR economy = bullish EUR
                    1;
                default -> 0;
            };
        }

        if (event.getCurrency() == Currency.USD) {
            // USD strength = EUR weakness
            // So positive USD surprise = bearish 6E
            return switch (event.getCategory()) {
                case CENTRAL_BANK, INFLATION, EMPLOYMENT, GDP_GROWTH, PMI_SURVEYS -> -1;
                default -> 0;
            };
        }

        return 0;
    }

    /**
     * Yen direction:
     * - JPY events: positive surprise = bullish 6J
     * - USD events: positive surprise = bearish 6J (inverse)
     * Note: 6J is JPY/USD, so bullish 6J = strong yen
     */
    private int getYenDirectionalSign(EconomicEvent event) {
        if (event.getCurrency() == Currency.JPY) {
            return switch (event.getCategory()) {
                case CENTRAL_BANK ->
                    // Hawkish BOJ (rare) = bullish JPY
                    1;
                case INFLATION ->
                    // Hot Japan inflation = potential BOJ action = bullish JPY
                    1;
                default -> 0;
            };
        }

        if (event.getCurrency() == Currency.USD) {
            // USD strength = JPY weakness = bearish 6J
            return -1;
        }

        return 0;
    }

    /**
     * Check if this is an EIA Crude Oil Inventory event.
     */
    public boolean isEiaInventoryEvent(EconomicEvent event) {
        String name = event.getName().toLowerCase();
        return (name.contains("eia") && name.contains("crude") &&
               (name.contains("inventory") || name.contains("stocks"))) ||
               event.getCategory() == EventCategory.ENERGY;
    }

    /**
     * Get all instruments affected by an event (relevance >= 0.3).
     */
    public List<String> getAffectedInstruments(EconomicEvent event) {
        return ALL_INSTRUMENTS.stream()
            .filter(inst -> getRelevance(event, inst) >= 0.3)
            .collect(Collectors.toList());
    }

    /**
     * Get all instruments affected by an event above a custom threshold.
     */
    public List<String> getAffectedInstruments(EconomicEvent event, double relevanceThreshold) {
        return ALL_INSTRUMENTS.stream()
            .filter(inst -> getRelevance(event, inst) >= relevanceThreshold)
            .collect(Collectors.toList());
    }

    /**
     * Get all supported instruments.
     */
    public List<String> getAllInstruments() {
        return ALL_INSTRUMENTS;
    }
}
