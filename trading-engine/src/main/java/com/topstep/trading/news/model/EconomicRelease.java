package com.topstep.trading.news.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Represents an actual economic release with outcome data.
 * Contains the event, actual value, and computed surprise metrics.
 */
public final class EconomicRelease {
    private final EconomicEvent event;
    private final Double actual;               // Released value
    private final Instant releaseTime;

    // Computed fields (set by SurpriseCalculator)
    private Double surprise;                   // actual - forecast
    private Double surpriseZScore;             // Standardized surprise
    private SurpriseDirection direction;       // BETTER_THAN_EXPECTED, WORSE, INLINE

    private EconomicRelease(Builder builder) {
        this.event = Objects.requireNonNull(builder.event, "event cannot be null");
        this.actual = builder.actual;
        this.releaseTime = Objects.requireNonNull(builder.releaseTime, "releaseTime cannot be null");
        this.surprise = builder.surprise;
        this.surpriseZScore = builder.surpriseZScore;
        this.direction = builder.direction;
    }

    public EconomicEvent getEvent() {
        return event;
    }

    public Double getActual() {
        return actual;
    }

    public Instant getReleaseTime() {
        return releaseTime;
    }

    public Double getSurprise() {
        return surprise;
    }

    public void setSurprise(Double surprise) {
        this.surprise = surprise;
    }

    public Double getSurpriseZScore() {
        return surpriseZScore;
    }

    public void setSurpriseZScore(Double surpriseZScore) {
        this.surpriseZScore = surpriseZScore;
    }

    public SurpriseDirection getSurpriseDirection() {
        return direction;
    }

    public void setSurpriseDirection(SurpriseDirection direction) {
        this.direction = direction;
    }

    /**
     * Check if the release has an actual value.
     */
    public boolean hasActual() {
        return actual != null;
    }

    /**
     * Check if surprise metrics have been calculated.
     */
    public boolean hasCalculatedSurprise() {
        return direction != null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EconomicRelease that = (EconomicRelease) o;
        return Objects.equals(event.getId(), that.event.getId()) &&
               Objects.equals(releaseTime, that.releaseTime);
    }

    @Override
    public int hashCode() {
        return Objects.hash(event.getId(), releaseTime);
    }

    @Override
    public String toString() {
        return String.format("EconomicRelease{event='%s', actual=%s, surprise=%s, direction=%s}",
                event.getName(), actual, surprise, direction);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private EconomicEvent event;
        private Double actual;
        private Instant releaseTime;
        private Double surprise;
        private Double surpriseZScore;
        private SurpriseDirection direction;

        public Builder event(EconomicEvent event) {
            this.event = event;
            return this;
        }

        public Builder actual(Double actual) {
            this.actual = actual;
            return this;
        }

        public Builder releaseTime(Instant releaseTime) {
            this.releaseTime = releaseTime;
            return this;
        }

        public Builder surprise(Double surprise) {
            this.surprise = surprise;
            return this;
        }

        public Builder surpriseZScore(Double surpriseZScore) {
            this.surpriseZScore = surpriseZScore;
            return this;
        }

        public Builder direction(SurpriseDirection direction) {
            this.direction = direction;
            return this;
        }

        public EconomicRelease build() {
            return new EconomicRelease(this);
        }
    }
}
