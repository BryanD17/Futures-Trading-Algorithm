package com.topstep.trading.news.model;

/**
 * Categories of economic events.
 * Used to determine relevance to specific instruments.
 */
public enum EventCategory {
    CENTRAL_BANK("Central Bank"),      // Fed decisions, FOMC minutes
    INFLATION("Inflation"),            // CPI, PPI, PCE
    EMPLOYMENT("Employment"),          // NFP, Jobless Claims, ADP, Unemployment Rate
    GDP_GROWTH("GDP/Growth"),          // GDP, Industrial Production
    PMI_SURVEYS("PMI/Surveys"),        // ISM Manufacturing/Services, PMI
    HOUSING("Housing"),                // Housing Starts, Existing Home Sales, Building Permits
    CONSUMER("Consumer"),              // Retail Sales, Consumer Confidence, Michigan Sentiment
    TRADE("Trade"),                    // Trade Balance
    OTHER("Other");

    private final String displayName;

    EventCategory(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
