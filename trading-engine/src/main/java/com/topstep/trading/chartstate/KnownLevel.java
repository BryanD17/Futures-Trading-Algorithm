package com.topstep.trading.chartstate;

import java.time.Instant;
import java.util.Objects;

/**
 * Represents a known liquidity level that smart money may target.
 *
 * These levels are where stop losses cluster (buy stops above highs,
 * sell stops below lows) and where institutional traders hunt for
 * liquidity before initiating major moves.
 */
public class KnownLevel {

    private final String id;                    // Unique identifier
    private final String instrument;            // Symbol (ES, NQ, GC, etc.)
    private final LevelType type;               // Type of level
    private final double price;                 // Exact price of the level
    private final Instant createdAt;            // When this level was detected
    private final Instant expiresAt;            // When this level expires (null = never)

    // For equal highs/lows - cluster information
    private final int clusterSize;              // Number of swing points in cluster
    private final double clusterSpread;         // Price spread of the cluster

    /**
     * Where this level came from (V4 Agent 03). "NATIVE" for everything the
     * LevelEngine computes itself; "ICTLIB_CLUSTER" for a §S6 liquidity pool
     * published through IctLibLevelAdapter. Purely descriptive — no consumer
     * branches on it, so levels behave identically whatever their source.
     * Defaults to NATIVE in every pre-existing constructor.
     */
    private String source = "NATIVE";

    // State tracking
    private int touchCount;                     // How many times price approached this level
    private boolean raided;                     // Has this level been swept
    private Instant raidedAt;                   // When was it raided
    private int barsSinceCreation;              // Updated each candle

    // Zone flip tracking (FIX 3: demand/supply zone flips)
    private boolean flipped = false;            // Has this level flipped polarity?
    private Instant flipTimestamp = null;        // When was it flipped?

    // Touch tracking (FIX 5: multi-touch scoring)
    private final java.util.List<Instant> touchTimestamps = new java.util.ArrayList<>();

    /**
     * Create a standard level (PDH/PDL, session levels, etc.)
     */
    public KnownLevel(String instrument, LevelType type, double price,
                      Instant createdAt, Instant expiresAt) {
        this.id = generateId(instrument, type, price, createdAt);
        this.instrument = instrument;
        this.type = type;
        this.price = price;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.clusterSize = 1;
        this.clusterSpread = 0;
        this.touchCount = 0;
        this.raided = false;
        this.barsSinceCreation = 0;
    }

    /**
     * Create an equal highs/lows level with cluster information.
     */
    public KnownLevel(String instrument, LevelType type, double price,
                      Instant createdAt, Instant expiresAt,
                      int clusterSize, double clusterSpread) {
        this.id = generateId(instrument, type, price, createdAt);
        this.instrument = instrument;
        this.type = type;
        this.price = price;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.clusterSize = clusterSize;
        this.clusterSpread = clusterSpread;
        this.touchCount = 0;
        this.raided = false;
        this.barsSinceCreation = 0;
    }

    /**
     * Convenience constructor for simple level creation without instrument/expiry.
     * Used by RaidDetector and LevelEngine for quick level creation.
     */
    public KnownLevel(LevelType type, double price, Instant createdAt) {
        this(type.name(), type, price, createdAt, null);
    }

    /**
     * Convenience constructor with cluster size (for equal highs/lows).
     * Used by RaidDetector and LevelEngine for equal level creation.
     */
    public KnownLevel(LevelType type, double price, Instant createdAt, int clusterSize) {
        this(type.name(), type, price, createdAt, null, clusterSize, 0.0);
    }

    /**
     * Equal-level constructor carrying a source tag (V4 Agent 03).
     */
    public KnownLevel(LevelType type, double price, Instant createdAt,
                      int clusterSize, String source) {
        this(type.name(), type, price, createdAt, null, clusterSize, 0.0);
        if (source != null && !source.isBlank()) this.source = source;
    }

    private static String generateId(String instrument, LevelType type, double price, Instant createdAt) {
        return String.format("%s_%s_%.5f_%d", instrument, type.name(), price, createdAt.toEpochMilli());
    }

    // ═══════════════════════════════════════════════════════════════════
    // GETTERS
    // ═══════════════════════════════════════════════════════════════════

    public String getId() {
        return id;
    }

    public String getInstrument() {
        return instrument;
    }

    public LevelType getType() {
        return type;
    }

    public double getPrice() {
        return price;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    /** Provenance tag: "NATIVE", or "ICTLIB_CLUSTER" for a §S6 pool. */
    public String getSource() {
        return source;
    }

    public int getClusterSize() {
        return clusterSize;
    }

    public double getClusterSpread() {
        return clusterSpread;
    }

    public int getTouchCount() {
        return touchCount;
    }

    public boolean isRaided() {
        return raided;
    }

    public Instant getRaidedAt() {
        return raidedAt;
    }

    public int getBarsSinceCreation() {
        return barsSinceCreation;
    }

    // ═══════════════════════════════════════════════════════════════════
    // STATE MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Mark this level as raided (swept).
     */
    public void markRaided(Instant timestamp) {
        this.raided = true;
        this.raidedAt = timestamp;
    }

    /**
     * Increment touch count when price approaches this level.
     */
    public void incrementTouchCount() {
        this.touchCount++;
    }

    /**
     * Record a touch with timestamp for multi-touch scoring (FIX 5).
     */
    public void recordTouch(Instant timestamp) {
        this.touchCount++;
        this.touchTimestamps.add(timestamp);
    }

    /**
     * Get touch timestamps for analysis.
     */
    public java.util.List<Instant> getTouchTimestamps() {
        return java.util.Collections.unmodifiableList(touchTimestamps);
    }

    /**
     * Check if this level has been flipped (zone flip detected).
     */
    public boolean isFlipped() {
        return flipped;
    }

    /**
     * Mark this level as flipped.
     */
    public void setFlipped(boolean flipped) {
        this.flipped = flipped;
    }

    /**
     * Get the timestamp when this level was flipped.
     */
    public Instant getFlipTimestamp() {
        return flipTimestamp;
    }

    /**
     * Set the flip timestamp.
     */
    public void setFlipTimestamp(Instant flipTimestamp) {
        this.flipTimestamp = flipTimestamp;
    }

    /**
     * Increment bars since creation (called each candle).
     */
    public void incrementBarsSinceCreation() {
        this.barsSinceCreation++;
    }

    /**
     * Check if this level has expired.
     */
    public boolean isExpired(Instant now) {
        return expiresAt != null && now.isAfter(expiresAt);
    }

    /**
     * Check if this level is still active (not raided and not expired).
     */
    public boolean isActive(Instant now) {
        return !raided && !isExpired(now);
    }

    // ═══════════════════════════════════════════════════════════════════
    // UTILITY METHODS
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Calculate distance from a price to this level (in price units).
     */
    public double distanceFrom(double currentPrice) {
        return Math.abs(currentPrice - price);
    }

    /**
     * Calculate distance from a price to this level (as percentage).
     */
    public double distanceFromPct(double currentPrice) {
        return Math.abs(currentPrice - price) / price * 100;
    }

    /**
     * Check if price is within tolerance of this level.
     */
    public boolean isPriceNear(double currentPrice, double toleranceTicks, double tickSize) {
        double tolerancePrice = toleranceTicks * tickSize;
        return Math.abs(currentPrice - price) <= tolerancePrice;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        KnownLevel that = (KnownLevel) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return String.format("KnownLevel{%s %s @ %.5f, raided=%s, touches=%d}",
                instrument, type.getDisplayName(), price, raided, touchCount);
    }
}
