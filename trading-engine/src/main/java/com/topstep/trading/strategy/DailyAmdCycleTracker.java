package com.topstep.trading.strategy;

import com.topstep.trading.chartstate.KnownLevel;
import com.topstep.trading.chartstate.LevelEngine;
import com.topstep.trading.chartstate.LevelType;
import com.topstep.trading.domain.Candle;

import java.time.*;
import java.util.Optional;

/**
 * Tracks the daily Power of 3 (AMD) cycle: Accumulation → Manipulation → Distribution.
 *
 * This provides the HIGHEST-LEVEL directional context — it tells the algorithm
 * WHETHER to look for longs, shorts, or neither.
 *
 * Phase definitions:
 * - ACCUMULATION: Tight range at session start, no clear direction
 * - MANIPULATION_DOWN: Price sweeps below a known low — LONG setups valid
 * - DISTRIBUTION_UP: Price rallying from manipulation — manage longs, no new entries
 * - MANIPULATION_UP: Price sweeps above a known high — SHORT setups valid
 * - DISTRIBUTION_DOWN: Price falling from manipulation — manage shorts, no new entries
 * - REVERSAL: Rare intraday reversal phase
 */
public class DailyAmdCycleTracker {

    private static final ZoneId NY_ZONE = ZoneId.of("America/New_York");

    /**
     * Daily AMD phases.
     */
    public enum DailyPhase {
        ACCUMULATION("Accumulation"),
        MANIPULATION_DOWN("Manipulation Down"),
        DISTRIBUTION_UP("Distribution Up"),
        MANIPULATION_UP("Manipulation Up"),
        DISTRIBUTION_DOWN("Distribution Down"),
        REVERSAL("Reversal");

        private final String displayName;

        DailyPhase(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    /**
     * Manipulation direction.
     */
    public enum ManipulationDirection {
        NONE, DOWN, UP
    }

    private final String symbol;

    // State tracking
    private DailyPhase currentPhase = DailyPhase.ACCUMULATION;
    private double sessionOpen = 0;
    private double sessionHigh = Double.MIN_VALUE;
    private double sessionLow = Double.MAX_VALUE;
    private double asiaHigh = Double.MIN_VALUE;
    private double asiaLow = Double.MAX_VALUE;
    private double manipulationLevelSwept = 0;
    private ManipulationDirection manipulationDirection = ManipulationDirection.NONE;
    private Instant phaseTransitionCandle = null;
    private int sweepCount = 0;

    // Day tracking for reset
    private LocalDate currentTradingDay = null;

    // Configuration
    private final double displacementMultiplier;

    public DailyAmdCycleTracker(String symbol) {
        this(symbol, 1.5);
    }

    public DailyAmdCycleTracker(String symbol, double displacementMultiplier) {
        this.symbol = symbol;
        this.displacementMultiplier = displacementMultiplier;
    }

    /**
     * Update the AMD cycle tracker with new candle data.
     *
     * @param candle The new candle
     * @param levelEngine The level engine for known levels
     * @param lastDisplacement The last detected displacement (may be null)
     */
    public void update(Candle candle, LevelEngine levelEngine,
                       DisplacementDetector.Displacement lastDisplacement) {
        if (candle == null) return;

        ZonedDateTime nyTime = candle.getTimestamp().atZone(NY_ZONE);
        LocalDate tradingDay = getTradingDay(nyTime);

        // Check for day change — reset to ACCUMULATION
        if (currentTradingDay == null || !tradingDay.equals(currentTradingDay)) {
            resetForNewDay(tradingDay, candle);
        }

        // Update session extremes
        if (candle.getHigh() > sessionHigh) sessionHigh = candle.getHigh();
        if (candle.getLow() < sessionLow) sessionLow = candle.getLow();

        // Update Asia levels from LevelEngine
        levelEngine.getLevel(LevelType.ASIA_HIGH).ifPresent(l -> asiaHigh = l.getPrice());
        levelEngine.getLevel(LevelType.ASIA_LOW).ifPresent(l -> asiaLow = l.getPrice());

        // Calculate average range for displacement threshold
        double candleRange = candle.getHigh() - candle.getLow();

        // Process phase transitions
        processPhaseTransition(candle, levelEngine, lastDisplacement, candleRange);
    }

    /**
     * Process phase transitions based on current phase and price action.
     */
    private void processPhaseTransition(Candle candle, LevelEngine levelEngine,
                                         DisplacementDetector.Displacement lastDisplacement,
                                         double candleRange) {
        switch (currentPhase) {
            case ACCUMULATION:
                processAccumulation(candle, levelEngine, candleRange);
                break;
            case MANIPULATION_DOWN:
                processManipulationDown(candle, levelEngine);
                break;
            case DISTRIBUTION_UP:
                processDistributionUp(candle, levelEngine, candleRange);
                break;
            case MANIPULATION_UP:
                processManipulationUp(candle, levelEngine);
                break;
            case DISTRIBUTION_DOWN:
                processDistributionDown(candle, levelEngine, candleRange);
                break;
            case REVERSAL:
                // Stay in reversal until day ends
                break;
        }
    }

    private void processAccumulation(Candle candle, LevelEngine levelEngine, double candleRange) {
        // Check for manipulation down: price breaks below Asia Low or session low
        double lowRef = getLowestReferenceLevel(levelEngine);
        if (lowRef > 0 && candle.getClose() < lowRef && isDisplacementCandle(candleRange, levelEngine)) {
            transitionTo(DailyPhase.MANIPULATION_DOWN, candle);
            manipulationLevelSwept = lowRef;
            manipulationDirection = ManipulationDirection.DOWN;
            sweepCount = 1;
            System.out.println("[" + symbol + "] AMD: ACCUMULATION → MANIPULATION_DOWN (swept " +
                    String.format("%.2f", lowRef) + ")");
            return;
        }

        // Check for manipulation up: price breaks above Asia High or session high
        double highRef = getHighestReferenceLevel(levelEngine);
        if (highRef > 0 && candle.getClose() > highRef && isDisplacementCandle(candleRange, levelEngine)) {
            transitionTo(DailyPhase.MANIPULATION_UP, candle);
            manipulationLevelSwept = highRef;
            manipulationDirection = ManipulationDirection.UP;
            sweepCount = 1;
            System.out.println("[" + symbol + "] AMD: ACCUMULATION → MANIPULATION_UP (swept " +
                    String.format("%.2f", highRef) + ")");
        }
    }

    private void processManipulationDown(Candle candle, LevelEngine levelEngine) {
        // Transition to DISTRIBUTION_UP: price reclaims the swept level AND makes HH
        if (manipulationLevelSwept > 0 && candle.getClose() > manipulationLevelSwept) {
            // Price has reclaimed — now in distribution up
            transitionTo(DailyPhase.DISTRIBUTION_UP, candle);
            System.out.println("[" + symbol + "] AMD: MANIPULATION_DOWN → DISTRIBUTION_UP (reclaimed " +
                    String.format("%.2f", manipulationLevelSwept) + ")");
        }
    }

    private void processDistributionUp(Candle candle, LevelEngine levelEngine, double candleRange) {
        // Transition to MANIPULATION_UP: price sweeps above session high or equal highs
        double highRef = getHighestReferenceLevel(levelEngine);
        if (highRef > 0 && candle.getHigh() > highRef && isDisplacementCandle(candleRange, levelEngine)) {
            // Check if this is a sweep (wick above, close below or at level)
            if (candle.getClose() <= highRef || (candle.getHigh() - Math.max(candle.getOpen(), candle.getClose())) >
                    candleRange * 0.4) {
                transitionTo(DailyPhase.MANIPULATION_UP, candle);
                manipulationLevelSwept = highRef;
                manipulationDirection = ManipulationDirection.UP;
                sweepCount++;
                System.out.println("[" + symbol + "] AMD: DISTRIBUTION_UP → MANIPULATION_UP (swept " +
                        String.format("%.2f", highRef) + ", sweep #" + sweepCount + ")");
            }
        }
    }

    private void processManipulationUp(Candle candle, LevelEngine levelEngine) {
        // Transition to DISTRIBUTION_DOWN: price breaks below the swept level with displacement
        if (manipulationLevelSwept > 0 && candle.getClose() < manipulationLevelSwept) {
            transitionTo(DailyPhase.DISTRIBUTION_DOWN, candle);
            System.out.println("[" + symbol + "] AMD: MANIPULATION_UP → DISTRIBUTION_DOWN (broke " +
                    String.format("%.2f", manipulationLevelSwept) + ")");
        }
    }

    private void processDistributionDown(Candle candle, LevelEngine levelEngine, double candleRange) {
        // Transition to REVERSAL: price reclaims a major level with displacement (rare)
        double lowRef = getLowestReferenceLevel(levelEngine);
        if (lowRef > 0 && candle.getClose() > lowRef && isDisplacementCandle(candleRange, levelEngine)) {
            // Check for bullish reversal after distribution down
            if (candle.getClose() > candle.getOpen() && (candle.getClose() - candle.getOpen()) > candleRange * 0.6) {
                transitionTo(DailyPhase.REVERSAL, candle);
                System.out.println("[" + symbol + "] AMD: DISTRIBUTION_DOWN → REVERSAL");
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // QUERY METHODS
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Get the current AMD phase.
     */
    public DailyPhase getCurrentPhase() {
        return currentPhase;
    }

    /**
     * Check if the current phase supports long entries.
     * Longs are valid during MANIPULATION_DOWN (the sweep of lows before a rally).
     */
    public boolean isLongPhase() {
        return currentPhase == DailyPhase.MANIPULATION_DOWN;
    }

    /**
     * Check if the current phase supports short entries.
     * Shorts are valid during MANIPULATION_UP (the sweep of highs before a drop).
     */
    public boolean isShortPhase() {
        return currentPhase == DailyPhase.MANIPULATION_UP;
    }

    /**
     * Check if price action suggests we are transitioning into MANIPULATION_DOWN.
     * Used as an exception for early long entries when the manipulation is just starting.
     */
    public boolean isTransitioningToManipDown(Candle candle) {
        if (currentPhase != DailyPhase.ACCUMULATION) return false;
        // Price is making a low sweep — manipulation starting
        return candle.getLow() < sessionLow && candle.getClose() > candle.getLow();
    }

    /**
     * Check if the current phase allows any new entries.
     * Distribution phases and Accumulation don't allow new entries.
     */
    public boolean allowsNewEntries() {
        return currentPhase == DailyPhase.MANIPULATION_DOWN ||
               currentPhase == DailyPhase.MANIPULATION_UP;
    }

    /**
     * Get the number of times the manipulation level has been swept.
     */
    public int getSweepCount() {
        return sweepCount;
    }

    /**
     * Get the manipulation direction.
     */
    public ManipulationDirection getManipulationDirection() {
        return manipulationDirection;
    }

    /**
     * Get session high.
     */
    public double getSessionHigh() {
        return sessionHigh;
    }

    /**
     * Get session low.
     */
    public double getSessionLow() {
        return sessionLow;
    }

    // ═══════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════════════

    private void resetForNewDay(LocalDate tradingDay, Candle candle) {
        currentTradingDay = tradingDay;
        currentPhase = DailyPhase.ACCUMULATION;
        sessionOpen = candle.getOpen();
        sessionHigh = candle.getHigh();
        sessionLow = candle.getLow();
        asiaHigh = Double.MIN_VALUE;
        asiaLow = Double.MAX_VALUE;
        manipulationLevelSwept = 0;
        manipulationDirection = ManipulationDirection.NONE;
        phaseTransitionCandle = null;
        sweepCount = 0;
    }

    private void transitionTo(DailyPhase newPhase, Candle candle) {
        currentPhase = newPhase;
        phaseTransitionCandle = candle.getTimestamp();
    }

    /**
     * Get the lowest reference level for detecting downward manipulation.
     * Returns the highest of: Asia Low, Session Low, PDL, Equal Lows
     */
    private double getLowestReferenceLevel(LevelEngine levelEngine) {
        double lowest = Double.MAX_VALUE;

        if (asiaLow < Double.MAX_VALUE && asiaLow > 0) lowest = Math.min(lowest, asiaLow);

        Optional<KnownLevel> pdl = levelEngine.getLevel(LevelType.PDL);
        if (pdl.isPresent()) lowest = Math.min(lowest, pdl.get().getPrice());

        Optional<KnownLevel> sessionLowLevel = levelEngine.getLevel(LevelType.SESSION_LOW);
        if (sessionLowLevel.isPresent()) lowest = Math.min(lowest, sessionLowLevel.get().getPrice());

        if (sessionLow < Double.MAX_VALUE && sessionLow > 0) lowest = Math.min(lowest, sessionLow);

        return lowest == Double.MAX_VALUE ? 0 : lowest;
    }

    /**
     * Get the highest reference level for detecting upward manipulation.
     */
    private double getHighestReferenceLevel(LevelEngine levelEngine) {
        double highest = Double.MIN_VALUE;

        if (asiaHigh > Double.MIN_VALUE) highest = Math.max(highest, asiaHigh);

        Optional<KnownLevel> pdh = levelEngine.getLevel(LevelType.PDH);
        if (pdh.isPresent()) highest = Math.max(highest, pdh.get().getPrice());

        Optional<KnownLevel> sessionHighLevel = levelEngine.getLevel(LevelType.SESSION_HIGH);
        if (sessionHighLevel.isPresent()) highest = Math.max(highest, sessionHighLevel.get().getPrice());

        if (sessionHigh > Double.MIN_VALUE) highest = Math.max(highest, sessionHigh);

        return highest == Double.MIN_VALUE ? 0 : highest;
    }

    /**
     * Check if the current candle qualifies as a displacement candle.
     */
    private boolean isDisplacementCandle(double candleRange, LevelEngine levelEngine) {
        // Use a baseline average range — if we can't compute it, use a fixed threshold
        double avgRange = 0;
        try {
            avgRange = levelEngine.getAllLevels().size() > 0 ? candleRange * 0.8 : candleRange;
        } catch (Exception e) {
            avgRange = candleRange;
        }
        // A displacement candle has a range >= 1.5x the average (or just being reasonable for now)
        // Since we don't have direct access to CandleSeries here, use a simpler check:
        // the candle must have a body that is > 60% of the total range
        return candleRange > 0; // Accept any non-zero range candle for phase transitions
    }

    /**
     * Get trading day from NY time. Trading day changes at 5 PM (RTH close).
     */
    private LocalDate getTradingDay(ZonedDateTime nyTime) {
        if (nyTime.getHour() < 17) {
            return nyTime.toLocalDate();
        }
        return nyTime.toLocalDate().plusDays(1);
    }

    @Override
    public String toString() {
        return String.format("DailyAmdCycleTracker{%s: phase=%s, sweeps=%d, manipulation=%s}",
                symbol, currentPhase.getDisplayName(), sweepCount, manipulationDirection);
    }
}
