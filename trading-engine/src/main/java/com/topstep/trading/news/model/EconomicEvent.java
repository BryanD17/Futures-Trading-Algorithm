package com.topstep.trading.news.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Represents a scheduled economic event with forecast data.
 * This is an immutable value object representing an upcoming economic release.
 */
public final class EconomicEvent {
    private final String id;                      // Unique identifier
    private final String name;                    // "US CPI MoM", "FOMC Rate Decision"
    private final Currency currency;              // Primary currency affected
    private final EventImpact impact;             // HIGH, MEDIUM, LOW
    private final Instant scheduledTime;
    private final Double forecast;                // Expected value (nullable)
    private final Double previous;                // Previous release value (nullable)
    private final String unit;                    // "%" or "K" or "B" etc.
    private final EventCategory category;         // INFLATION, EMPLOYMENT, CENTRAL_BANK, etc.
    private final List<String> affectedInstruments;  // ["ES", "NQ", "GC"]

    private EconomicEvent(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "id cannot be null");
        this.name = Objects.requireNonNull(builder.name, "name cannot be null");
        this.currency = Objects.requireNonNull(builder.currency, "currency cannot be null");
        this.impact = Objects.requireNonNull(builder.impact, "impact cannot be null");
        this.scheduledTime = Objects.requireNonNull(builder.scheduledTime, "scheduledTime cannot be null");
        this.forecast = builder.forecast;
        this.previous = builder.previous;
        this.unit = builder.unit;
        this.category = builder.category != null ? builder.category : EventCategory.OTHER;
        this.affectedInstruments = builder.affectedInstruments != null
            ? Collections.unmodifiableList(new ArrayList<>(builder.affectedInstruments))
            : Collections.emptyList();
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Currency getCurrency() {
        return currency;
    }

    public EventImpact getImpact() {
        return impact;
    }

    public Instant getScheduledTime() {
        return scheduledTime;
    }

    public Double getForecast() {
        return forecast;
    }

    public Double getPrevious() {
        return previous;
    }

    public String getUnit() {
        return unit;
    }

    public EventCategory getCategory() {
        return category;
    }

    public List<String> getAffectedInstruments() {
        return affectedInstruments;
    }

    /**
     * Check if the event has a forecast value.
     */
    public boolean hasForecast() {
        return forecast != null;
    }

    /**
     * Check if the event has a previous value.
     */
    public boolean hasPrevious() {
        return previous != null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EconomicEvent that = (EconomicEvent) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return String.format("EconomicEvent{id='%s', name='%s', currency=%s, impact=%s, scheduledTime=%s, forecast=%s}",
                id, name, currency, impact, scheduledTime, forecast);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String id;
        private String name;
        private Currency currency;
        private EventImpact impact;
        private Instant scheduledTime;
        private Double forecast;
        private Double previous;
        private String unit;
        private EventCategory category;
        private List<String> affectedInstruments;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder currency(Currency currency) {
            this.currency = currency;
            return this;
        }

        public Builder impact(EventImpact impact) {
            this.impact = impact;
            return this;
        }

        public Builder scheduledTime(Instant scheduledTime) {
            this.scheduledTime = scheduledTime;
            return this;
        }

        public Builder forecast(Double forecast) {
            this.forecast = forecast;
            return this;
        }

        public Builder previous(Double previous) {
            this.previous = previous;
            return this;
        }

        public Builder unit(String unit) {
            this.unit = unit;
            return this;
        }

        public Builder category(EventCategory category) {
            this.category = category;
            return this;
        }

        public Builder affectedInstruments(List<String> affectedInstruments) {
            this.affectedInstruments = affectedInstruments;
            return this;
        }

        public EconomicEvent build() {
            return new EconomicEvent(this);
        }
    }
}
