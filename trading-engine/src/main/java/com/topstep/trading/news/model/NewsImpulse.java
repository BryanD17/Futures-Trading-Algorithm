package com.topstep.trading.news.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Represents a computed impulse from an economic release.
 * The impulse magnitude decays over time using exponential decay.
 */
public final class NewsImpulse {
    private final EconomicRelease release;
    private final Currency currency;
    private final double magnitude;           // -1.0 to +1.0 (normalized)
    private final double confidence;          // 0.0 to 1.0 - how confident in signal
    private final Instant createdAt;
    private final Duration expectedDecay;     // How long impulse lasts

    public NewsImpulse(EconomicRelease release,
                       Currency currency,
                       double magnitude,
                       double confidence,
                       Instant createdAt,
                       Duration expectedDecay) {
        this.release = Objects.requireNonNull(release, "release cannot be null");
        this.currency = Objects.requireNonNull(currency, "currency cannot be null");
        this.magnitude = validateMagnitude(magnitude);
        this.confidence = validateConfidence(confidence);
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt cannot be null");
        this.expectedDecay = Objects.requireNonNull(expectedDecay, "expectedDecay cannot be null");
    }

    private static double validateMagnitude(double magnitude) {
        if (magnitude < -1.0 || magnitude > 1.0) {
            throw new IllegalArgumentException("magnitude must be between -1.0 and 1.0, got: " + magnitude);
        }
        return magnitude;
    }

    private static double validateConfidence(double confidence) {
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be between 0.0 and 1.0, got: " + confidence);
        }
        return confidence;
    }

    /**
     * Get current decayed magnitude based on time elapsed.
     * Uses exponential decay from creation time.
     *
     * @param now Current time
     * @return Decayed magnitude value
     */
    public double getCurrentMagnitude(Instant now) {
        long elapsedMillis = Duration.between(createdAt, now).toMillis();
        long decayMillis = expectedDecay.toMillis();

        if (elapsedMillis < 0) {
            // If now is before creation time (shouldn't happen), return full magnitude
            return magnitude;
        }

        if (elapsedMillis >= decayMillis * 3) {
            return 0.0;  // Fully decayed after 3x decay period
        }

        // Exponential decay: M(t) = M0 * e^(-2t/τ)
        double decayFactor = Math.exp(-2.0 * elapsedMillis / decayMillis);
        return magnitude * decayFactor;
    }

    /**
     * Check if the impulse has effectively decayed to zero.
     */
    public boolean isExpired(Instant now) {
        return Math.abs(getCurrentMagnitude(now)) < 0.01;
    }

    /**
     * Get the time remaining until the impulse is considered expired.
     */
    public Duration getTimeRemaining(Instant now) {
        Instant expiry = createdAt.plus(expectedDecay.multipliedBy(3));
        Duration remaining = Duration.between(now, expiry);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    public EconomicRelease getRelease() {
        return release;
    }

    public Currency getCurrency() {
        return currency;
    }

    public double getMagnitude() {
        return magnitude;
    }

    public double getConfidence() {
        return confidence;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Duration getExpectedDecay() {
        return expectedDecay;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        NewsImpulse that = (NewsImpulse) o;
        return Objects.equals(release.getEvent().getId(), that.release.getEvent().getId()) &&
               Objects.equals(createdAt, that.createdAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(release.getEvent().getId(), createdAt);
    }

    @Override
    public String toString() {
        return String.format("NewsImpulse{event='%s', currency=%s, magnitude=%.3f, confidence=%.2f, decay=%s}",
                release.getEvent().getName(), currency, magnitude, confidence, expectedDecay);
    }
}
