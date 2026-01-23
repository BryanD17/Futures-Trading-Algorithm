package com.topstep.trading.news.model;

/**
 * Currencies relevant to economic events.
 * Used to map events to affected instruments.
 */
public enum Currency {
    USD("US Dollar", "United States"),
    EUR("Euro", "Eurozone"),
    JPY("Japanese Yen", "Japan"),
    GBP("British Pound", "United Kingdom"),
    CHF("Swiss Franc", "Switzerland"),
    CAD("Canadian Dollar", "Canada"),
    AUD("Australian Dollar", "Australia"),
    NZD("New Zealand Dollar", "New Zealand"),
    CNY("Chinese Yuan", "China");

    private final String displayName;
    private final String region;

    Currency(String displayName, String region) {
        this.displayName = displayName;
        this.region = region;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getRegion() {
        return region;
    }
}
