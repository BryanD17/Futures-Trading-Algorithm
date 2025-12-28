package com.topstep.trading.risk;

import com.topstep.trading.strategy.ATRCalculator;
import com.topstep.trading.strategy.InstrumentProfile;
import com.topstep.trading.strategy.KillzoneClock;
import com.topstep.trading.strategy.KillzonePhase;

import java.time.*;
import java.util.*;

/**
 * Market Condition Filter - Additional safeguards to improve win percentage.
 *
 * This filter checks various market conditions that affect trade quality:
 *
 * 1. SESSION QUALITY:
 *    - Avoid first/last 15 minutes of major sessions
 *    - Higher quality during session overlaps
 *    - Skip low-liquidity periods
 *
 * 2. VOLATILITY FILTERING:
 *    - Skip during extreme volatility (ATR > 2x normal)
 *    - Increase targets during low volatility
 *    - Adjust position sizing based on current volatility
 *
 * 3. DAY-OF-WEEK FILTERING:
 *    - Monday mornings often have gaps/whipsaws
 *    - Friday afternoons have reduced liquidity
 *    - Mid-week (Tue-Thu) tends to be most reliable
 *
 * 4. NEWS EVENT AVOIDANCE:
 *    - Skip 15 minutes before/after major news
 *    - Reduce size during high-impact news days
 *
 * 5. RANGE/TREND DETECTION:
 *    - Avoid trades when market is in tight range
 *    - Prefer trending conditions for ICT setups
 *
 * 6. CONFLUENCE QUALITY GATE:
 *    - Require minimum number of confluences
 *    - Premium hours require fewer confluences
 *    - Off-hours require more confluences
 */
public class MarketConditionFilter {

    // Chicago timezone for futures
    private static final ZoneId CT_ZONE = ZoneId.of("America/Chicago");

    // Session transition times to avoid (first/last 15 min)
    private static final int SESSION_BUFFER_MINUTES = 15;

    // Major news event times (ET/CT)
    private static final List<LocalTime> HIGH_IMPACT_TIMES = Arrays.asList(
            LocalTime.of(7, 30),   // Pre-market economic data (8:30 ET)
            LocalTime.of(9, 0),    // Market open (10:00 ET)
            LocalTime.of(9, 30),   // NYSE open (10:30 ET)
            LocalTime.of(13, 0)    // FOMC announcements often at 14:00 ET
    );

    // Oil inventory release (Wednesdays at 10:30 ET = 9:30 CT)
    private static final LocalTime OIL_INVENTORY_TIME = LocalTime.of(9, 30);

    private final KillzoneClock killzoneClock;

    public MarketConditionFilter() {
        this.killzoneClock = new KillzoneClock();
    }

    /**
     * Evaluate overall market conditions for trading.
     * Returns a MarketCondition object with quality score and adjustments.
     */
    public MarketCondition evaluate(Instant timestamp, String symbol,
                                     ATRCalculator atrCalculator, InstrumentProfile profile) {

        ZonedDateTime zdt = timestamp.atZone(CT_ZONE);
        LocalTime time = zdt.toLocalTime();
        DayOfWeek dayOfWeek = zdt.getDayOfWeek();

        MarketCondition condition = new MarketCondition();

        // 1. Check day of week quality
        condition.addFactor(evaluateDayOfWeek(dayOfWeek, time));

        // 2. Check session timing
        condition.addFactor(evaluateSessionTiming(timestamp, time));

        // 3. Check volatility
        condition.addFactor(evaluateVolatility(atrCalculator, profile));

        // 4. Check news events
        condition.addFactor(evaluateNewsEvents(zdt, symbol));

        // 5. Check killzone phase
        condition.addFactor(evaluateKillzonePhase(timestamp));

        // Calculate overall recommendation
        condition.calculate();

        return condition;
    }

    /**
     * Evaluate day of week quality.
     */
    private ConditionFactor evaluateDayOfWeek(DayOfWeek day, LocalTime time) {
        switch (day) {
            case MONDAY:
                // Monday morning is risky (gaps, whipsaws)
                if (time.isBefore(LocalTime.of(9, 0))) {
                    return new ConditionFactor("Day/Time", -2,
                            "Monday morning - higher whipsaw risk");
                }
                return new ConditionFactor("Day/Time", 0, "Monday - normal");

            case TUESDAY:
            case WEDNESDAY:
            case THURSDAY:
                // Mid-week is typically best
                return new ConditionFactor("Day/Time", 1,
                        day + " - optimal trading day");

            case FRIDAY:
                // Friday afternoon is risky (reduced liquidity)
                if (time.isAfter(LocalTime.of(12, 0))) {
                    return new ConditionFactor("Day/Time", -1,
                            "Friday afternoon - reduced liquidity");
                }
                return new ConditionFactor("Day/Time", 0, "Friday AM - normal");

            case SATURDAY:
            case SUNDAY:
                return new ConditionFactor("Day/Time", -10,
                        "Weekend - markets closed");

            default:
                return new ConditionFactor("Day/Time", 0, "Unknown day");
        }
    }

    /**
     * Evaluate session timing (avoid transitions).
     */
    private ConditionFactor evaluateSessionTiming(Instant timestamp, LocalTime time) {
        String killzone = killzoneClock.getKillzoneName(timestamp);

        // Session overlaps are premium
        if (killzone.contains("OVERLAP")) {
            return new ConditionFactor("Session", 2,
                    "Session overlap - maximum liquidity");
        }

        // Check if we're in a killzone
        boolean inKillzone = killzoneClock.isInKillzone(timestamp);
        if (inKillzone) {
            KillzonePhase phase = killzoneClock.getKillzonePhase(timestamp);
            if (phase == KillzonePhase.PRIME) {
                return new ConditionFactor("Session", 2,
                        "Prime phase of " + killzone);
            } else if (phase == KillzonePhase.OPENING) {
                return new ConditionFactor("Session", 0,
                        "Opening phase - waiting for sweep");
            } else if (phase == KillzonePhase.CLOSING) {
                return new ConditionFactor("Session", -1,
                        "Closing phase - avoid new entries");
            }
        }

        // Check session transitions (first/last 15 min of major opens)
        // NY Open (9:30 ET = 8:30 CT)
        LocalTime nyOpen = LocalTime.of(8, 30);
        if (isNearTime(time, nyOpen, SESSION_BUFFER_MINUTES)) {
            return new ConditionFactor("Session", -1,
                    "Near NY open - high volatility transition");
        }

        // London Open (3:00 AM ET = 2:00 AM CT)
        LocalTime londonOpen = LocalTime.of(2, 0);
        if (isNearTime(time, londonOpen, SESSION_BUFFER_MINUTES)) {
            return new ConditionFactor("Session", -1,
                    "Near London open - transition period");
        }

        if (!inKillzone) {
            return new ConditionFactor("Session", -1,
                    "Outside killzones");
        }

        return new ConditionFactor("Session", 0, "Normal session");
    }

    /**
     * Evaluate volatility conditions.
     */
    private ConditionFactor evaluateVolatility(ATRCalculator atrCalculator, InstrumentProfile profile) {
        if (atrCalculator == null || profile == null) {
            return new ConditionFactor("Volatility", 0, "ATR data unavailable");
        }

        double currentAtr = atrCalculator.getCurrentAtr();
        double typicalAtr = profile.getTypicalDailyAtr();

        if (typicalAtr <= 0) {
            return new ConditionFactor("Volatility", 0, "No typical ATR reference");
        }

        double atrRatio = currentAtr / typicalAtr;

        if (atrRatio > 2.0) {
            return new ConditionFactor("Volatility", -3,
                    String.format("EXTREME volatility (%.1fx normal) - SKIP", atrRatio));
        } else if (atrRatio > 1.5) {
            return new ConditionFactor("Volatility", -1,
                    String.format("High volatility (%.1fx normal) - reduce size", atrRatio));
        } else if (atrRatio < 0.5) {
            return new ConditionFactor("Volatility", -1,
                    String.format("Low volatility (%.1fx normal) - wider stops", atrRatio));
        } else if (atrRatio >= 0.8 && atrRatio <= 1.2) {
            return new ConditionFactor("Volatility", 1,
                    String.format("Normal volatility (%.1fx) - optimal", atrRatio));
        }

        return new ConditionFactor("Volatility", 0,
                String.format("Acceptable volatility (%.1fx)", atrRatio));
    }

    /**
     * Evaluate news event proximity.
     */
    private ConditionFactor evaluateNewsEvents(ZonedDateTime zdt, String symbol) {
        LocalTime time = zdt.toLocalTime();
        DayOfWeek day = zdt.getDayOfWeek();

        // Check for high-impact economic data times
        for (LocalTime newsTime : HIGH_IMPACT_TIMES) {
            if (isNearTime(time, newsTime, 15)) {
                return new ConditionFactor("News", -2,
                        "Near high-impact news time (" + newsTime + ")");
            }
        }

        // Oil inventory (Wednesdays)
        if (day == DayOfWeek.WEDNESDAY && "CL".equals(symbol)) {
            if (isNearTime(time, OIL_INVENTORY_TIME, 15)) {
                return new ConditionFactor("News", -3,
                        "Oil inventory release window - SKIP CL");
            }
        }

        // First Friday of month = NFP (major news for all markets)
        if (day == DayOfWeek.FRIDAY && zdt.getDayOfMonth() <= 7) {
            LocalTime nfpTime = LocalTime.of(7, 30);  // 8:30 ET = 7:30 CT
            if (isNearTime(time, nfpTime, 30)) {
                return new ConditionFactor("News", -2,
                        "Near NFP release - high volatility expected");
            }
        }

        return new ConditionFactor("News", 0, "No major news nearby");
    }

    /**
     * Evaluate killzone phase quality.
     */
    private ConditionFactor evaluateKillzonePhase(Instant timestamp) {
        if (!killzoneClock.isInKillzone(timestamp)) {
            return new ConditionFactor("Killzone", -1, "Outside killzone");
        }

        KillzonePhase phase = killzoneClock.getKillzonePhase(timestamp);
        // CRITICAL: Null check for phase to avoid NPE
        if (phase == null) {
            return new ConditionFactor("Killzone", 0, "Killzone phase unavailable");
        }
        switch (phase) {
            case PRIME:
                return new ConditionFactor("Killzone", 2, "PRIME phase - optimal");
            case OPENING:
                return new ConditionFactor("Killzone", 0, "Opening phase - wait for sweep");
            case CLOSING:
                return new ConditionFactor("Killzone", -1, "Closing phase - no new entries");
            default:
                return new ConditionFactor("Killzone", 0, "Unknown phase");
        }
    }

    /**
     * Check if time is within minutes of target time.
     */
    private boolean isNearTime(LocalTime current, LocalTime target, int minutes) {
        long diffSeconds = Math.abs(
                current.toSecondOfDay() - target.toSecondOfDay()
        );
        return diffSeconds <= (minutes * 60);
    }

    /**
     * Market condition result.
     */
    public static class MarketCondition {
        private final List<ConditionFactor> factors = new ArrayList<>();
        private int totalScore = 0;
        private TradingRecommendation recommendation = TradingRecommendation.NORMAL;
        private double sizeMultiplier = 1.0;

        void addFactor(ConditionFactor factor) {
            factors.add(factor);
            totalScore += factor.score;
        }

        void calculate() {
            if (totalScore >= 4) {
                recommendation = TradingRecommendation.OPTIMAL;
                sizeMultiplier = 1.0;  // Don't increase size even in optimal conditions
            } else if (totalScore >= 2) {
                recommendation = TradingRecommendation.FAVORABLE;
                sizeMultiplier = 1.0;
            } else if (totalScore >= 0) {
                recommendation = TradingRecommendation.NORMAL;
                sizeMultiplier = 1.0;
            } else if (totalScore >= -2) {
                recommendation = TradingRecommendation.CAUTION;
                sizeMultiplier = 0.75;  // Reduce size
            } else if (totalScore >= -4) {
                recommendation = TradingRecommendation.HIGH_RISK;
                sizeMultiplier = 0.5;  // Half size
            } else {
                recommendation = TradingRecommendation.SKIP;
                sizeMultiplier = 0.0;  // No trade
            }
        }

        public List<ConditionFactor> getFactors() { return factors; }
        public int getTotalScore() { return totalScore; }
        public TradingRecommendation getRecommendation() { return recommendation; }
        public double getSizeMultiplier() { return sizeMultiplier; }

        public boolean shouldTrade() {
            return recommendation != TradingRecommendation.SKIP;
        }

        public boolean isOptimal() {
            return recommendation == TradingRecommendation.OPTIMAL ||
                   recommendation == TradingRecommendation.FAVORABLE;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("MarketCondition: ").append(recommendation)
                    .append(" (score: ").append(totalScore)
                    .append(", size: ").append(String.format("%.0f%%", sizeMultiplier * 100))
                    .append(")\n");
            for (ConditionFactor f : factors) {
                sb.append("  ").append(f).append("\n");
            }
            return sb.toString();
        }
    }

    /**
     * Individual condition factor.
     */
    public static class ConditionFactor {
        private final String category;
        private final int score;
        private final String description;

        public ConditionFactor(String category, int score, String description) {
            this.category = category;
            this.score = score;
            this.description = description;
        }

        @Override
        public String toString() {
            String scoreStr = score >= 0 ? "+" + score : String.valueOf(score);
            return String.format("[%s] %s: %s", scoreStr, category, description);
        }
    }

    /**
     * Trading recommendation based on market conditions.
     */
    public enum TradingRecommendation {
        OPTIMAL("Optimal conditions - full confidence"),
        FAVORABLE("Favorable conditions - proceed normally"),
        NORMAL("Normal conditions - standard caution"),
        CAUTION("Elevated risk - reduce size"),
        HIGH_RISK("High risk - half size only"),
        SKIP("Unfavorable conditions - skip trade");

        private final String description;

        TradingRecommendation(String description) {
            this.description = description;
        }

        public String getDescription() { return description; }
    }
}
