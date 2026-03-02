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

    private static final List<String> ALL_INSTRUMENTS = List.of(ES, NQ, GC);

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
