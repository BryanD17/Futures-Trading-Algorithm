package com.topstep.trading.news;

import java.time.Duration;

/**
 * Configuration for the macro news system.
 * Use the builder pattern or setters to customize settings.
 */
public class MacroNewsConfig {

    // ═══════════════════════════════════════════════════════════════════════
    // GATING SETTINGS
    // ═══════════════════════════════════════════════════════════════════════

    /** Time before HIGH impact events to block new trades */
    private Duration highImpactPreBuffer = Duration.ofMinutes(5);

    /** Time after HIGH impact events to reduce trading */
    private Duration highImpactPostBuffer = Duration.ofMinutes(10);

    /** Time before MEDIUM impact events to block new trades */
    private Duration mediumImpactPreBuffer = Duration.ofMinutes(2);

    /** Time after MEDIUM impact events to reduce trading */
    private Duration mediumImpactPostBuffer = Duration.ofMinutes(5);

    /** Minimum relevance score (0-1) for an event to trigger gating */
    private double minRelevanceForGating = 0.5;

    /** Size multiplier when reducing size for MEDIUM impact events */
    private double mediumImpactSizeMultiplier = 0.7;

    /** Size multiplier when reducing size for HIGH impact events (post-event) */
    private double highImpactPostEventSizeMultiplier = 0.5;

    // ═══════════════════════════════════════════════════════════════════════
    // IMPULSE DECAY SETTINGS
    // ═══════════════════════════════════════════════════════════════════════

    /** How long HIGH impact impulses last */
    private Duration highImpactDecay = Duration.ofHours(4);

    /** How long MEDIUM impact impulses last */
    private Duration mediumImpactDecay = Duration.ofHours(2);

    /** How long LOW impact impulses last */
    private Duration lowImpactDecay = Duration.ofMinutes(30);

    // ═══════════════════════════════════════════════════════════════════════
    // BIAS ALIGNMENT THRESHOLDS
    // ═══════════════════════════════════════════════════════════════════════

    /** Minimum absolute bias to count as ALIGNED (bullish news + bullish technical) */
    private double alignmentThreshold = 0.3;

    /** Bias level that triggers OPPOSING classification */
    private double strongOppositionThreshold = 0.5;

    /** Bias level that should block the trade entirely */
    private double blockingOppositionThreshold = 0.6;

    // ═══════════════════════════════════════════════════════════════════════
    // SURPRISE CALCULATION SETTINGS
    // ═══════════════════════════════════════════════════════════════════════

    /** Z-score threshold below which a surprise is considered INLINE */
    private double inlineSurpriseThreshold = 0.5;

    // ═══════════════════════════════════════════════════════════════════════
    // API SETTINGS
    // ═══════════════════════════════════════════════════════════════════════

    /** API key for calendar provider (Trading Economics, etc.) */
    private String calendarApiKey;

    /** API base URL */
    private String calendarApiUrl = "https://api.tradingeconomics.com";

    /** How often to refresh calendar data */
    private Duration calendarRefreshInterval = Duration.ofMinutes(5);

    /** Timeout for API requests */
    private Duration apiRequestTimeout = Duration.ofSeconds(30);

    // ═══════════════════════════════════════════════════════════════════════
    // CONSTRUCTOR AND FACTORY METHODS
    // ═══════════════════════════════════════════════════════════════════════

    public MacroNewsConfig() {
        // Default constructor with default values
    }

    /**
     * Create a configuration with sensible defaults for live trading.
     */
    public static MacroNewsConfig defaults() {
        return new MacroNewsConfig();
    }

    /**
     * Create a configuration optimized for backtesting.
     */
    public static MacroNewsConfig forBacktest() {
        MacroNewsConfig config = new MacroNewsConfig();
        config.calendarRefreshInterval = Duration.ofDays(1);  // No refresh needed
        config.apiRequestTimeout = Duration.ofSeconds(5);     // Quick timeout
        return config;
    }

    /**
     * Create an aggressive configuration with tighter gating.
     */
    public static MacroNewsConfig aggressive() {
        MacroNewsConfig config = new MacroNewsConfig();
        config.highImpactPreBuffer = Duration.ofMinutes(10);
        config.highImpactPostBuffer = Duration.ofMinutes(15);
        config.mediumImpactPreBuffer = Duration.ofMinutes(5);
        config.mediumImpactPostBuffer = Duration.ofMinutes(10);
        config.minRelevanceForGating = 0.3;
        config.blockingOppositionThreshold = 0.4;
        return config;
    }

    /**
     * Create a conservative configuration with looser gating.
     */
    public static MacroNewsConfig conservative() {
        MacroNewsConfig config = new MacroNewsConfig();
        config.highImpactPreBuffer = Duration.ofMinutes(3);
        config.highImpactPostBuffer = Duration.ofMinutes(5);
        config.mediumImpactPreBuffer = Duration.ofMinutes(1);
        config.mediumImpactPostBuffer = Duration.ofMinutes(3);
        config.minRelevanceForGating = 0.7;
        config.blockingOppositionThreshold = 0.7;
        return config;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // GETTERS AND SETTERS
    // ═══════════════════════════════════════════════════════════════════════

    public Duration getHighImpactPreBuffer() {
        return highImpactPreBuffer;
    }

    public MacroNewsConfig setHighImpactPreBuffer(Duration highImpactPreBuffer) {
        this.highImpactPreBuffer = highImpactPreBuffer;
        return this;
    }

    public Duration getHighImpactPostBuffer() {
        return highImpactPostBuffer;
    }

    public MacroNewsConfig setHighImpactPostBuffer(Duration highImpactPostBuffer) {
        this.highImpactPostBuffer = highImpactPostBuffer;
        return this;
    }

    public Duration getMediumImpactPreBuffer() {
        return mediumImpactPreBuffer;
    }

    public MacroNewsConfig setMediumImpactPreBuffer(Duration mediumImpactPreBuffer) {
        this.mediumImpactPreBuffer = mediumImpactPreBuffer;
        return this;
    }

    public Duration getMediumImpactPostBuffer() {
        return mediumImpactPostBuffer;
    }

    public MacroNewsConfig setMediumImpactPostBuffer(Duration mediumImpactPostBuffer) {
        this.mediumImpactPostBuffer = mediumImpactPostBuffer;
        return this;
    }

    public double getMinRelevanceForGating() {
        return minRelevanceForGating;
    }

    public MacroNewsConfig setMinRelevanceForGating(double minRelevanceForGating) {
        this.minRelevanceForGating = minRelevanceForGating;
        return this;
    }

    public double getMediumImpactSizeMultiplier() {
        return mediumImpactSizeMultiplier;
    }

    public MacroNewsConfig setMediumImpactSizeMultiplier(double mediumImpactSizeMultiplier) {
        this.mediumImpactSizeMultiplier = mediumImpactSizeMultiplier;
        return this;
    }

    public double getHighImpactPostEventSizeMultiplier() {
        return highImpactPostEventSizeMultiplier;
    }

    public MacroNewsConfig setHighImpactPostEventSizeMultiplier(double highImpactPostEventSizeMultiplier) {
        this.highImpactPostEventSizeMultiplier = highImpactPostEventSizeMultiplier;
        return this;
    }

    public Duration getHighImpactDecay() {
        return highImpactDecay;
    }

    public MacroNewsConfig setHighImpactDecay(Duration highImpactDecay) {
        this.highImpactDecay = highImpactDecay;
        return this;
    }

    public Duration getMediumImpactDecay() {
        return mediumImpactDecay;
    }

    public MacroNewsConfig setMediumImpactDecay(Duration mediumImpactDecay) {
        this.mediumImpactDecay = mediumImpactDecay;
        return this;
    }

    public Duration getLowImpactDecay() {
        return lowImpactDecay;
    }

    public MacroNewsConfig setLowImpactDecay(Duration lowImpactDecay) {
        this.lowImpactDecay = lowImpactDecay;
        return this;
    }

    public double getAlignmentThreshold() {
        return alignmentThreshold;
    }

    public MacroNewsConfig setAlignmentThreshold(double alignmentThreshold) {
        this.alignmentThreshold = alignmentThreshold;
        return this;
    }

    public double getStrongOppositionThreshold() {
        return strongOppositionThreshold;
    }

    public MacroNewsConfig setStrongOppositionThreshold(double strongOppositionThreshold) {
        this.strongOppositionThreshold = strongOppositionThreshold;
        return this;
    }

    public double getBlockingOppositionThreshold() {
        return blockingOppositionThreshold;
    }

    public MacroNewsConfig setBlockingOppositionThreshold(double blockingOppositionThreshold) {
        this.blockingOppositionThreshold = blockingOppositionThreshold;
        return this;
    }

    public double getInlineSurpriseThreshold() {
        return inlineSurpriseThreshold;
    }

    public MacroNewsConfig setInlineSurpriseThreshold(double inlineSurpriseThreshold) {
        this.inlineSurpriseThreshold = inlineSurpriseThreshold;
        return this;
    }

    public String getCalendarApiKey() {
        return calendarApiKey;
    }

    public MacroNewsConfig setCalendarApiKey(String calendarApiKey) {
        this.calendarApiKey = calendarApiKey;
        return this;
    }

    public String getCalendarApiUrl() {
        return calendarApiUrl;
    }

    public MacroNewsConfig setCalendarApiUrl(String calendarApiUrl) {
        this.calendarApiUrl = calendarApiUrl;
        return this;
    }

    public Duration getCalendarRefreshInterval() {
        return calendarRefreshInterval;
    }

    public MacroNewsConfig setCalendarRefreshInterval(Duration calendarRefreshInterval) {
        this.calendarRefreshInterval = calendarRefreshInterval;
        return this;
    }

    public Duration getApiRequestTimeout() {
        return apiRequestTimeout;
    }

    public MacroNewsConfig setApiRequestTimeout(Duration apiRequestTimeout) {
        this.apiRequestTimeout = apiRequestTimeout;
        return this;
    }

    @Override
    public String toString() {
        return String.format("MacroNewsConfig{highPreBuffer=%s, highPostBuffer=%s, " +
                "mediumPreBuffer=%s, mediumPostBuffer=%s, minRelevance=%.2f, " +
                "alignmentThreshold=%.2f, strongOpposition=%.2f}",
                highImpactPreBuffer, highImpactPostBuffer,
                mediumImpactPreBuffer, mediumImpactPostBuffer,
                minRelevanceForGating, alignmentThreshold, strongOppositionThreshold);
    }
}
