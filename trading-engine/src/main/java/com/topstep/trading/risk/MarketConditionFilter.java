package com.topstep.trading.risk;

import com.topstep.trading.strategy.ATRCalculator;
import com.topstep.trading.strategy.InstrumentProfile;
import com.topstep.trading.strategy.KillzoneClock;
import com.topstep.trading.strategy.KillzonePhase;
import com.topstep.trading.strategy.SilverBulletClock;

import java.time.*;
import java.util.*;

/**
 * Market Condition Filter - Additional safeguards to improve win percentage.
 *
 * SESSION-AGNOSTIC DESIGN: All instruments can trade in any session.
 * If a confluence tier aligns for an instrument, it executes regardless of session.
 * Session/killzone timing provides BONUSES only (never penalties or hard blocks).
 *
 * This filter checks various market conditions that affect trade quality:
 *
 * 1. SESSION QUALITY (bonuses only):
 *    - Bonus for session overlaps (maximum liquidity)
 *    - Bonus for Silver Bullet windows and prime killzone phases
 *    - No penalties for outside killzone, opening/closing phases, or transitions
 *
 * 2. VOLATILITY FILTERING:
 *    - Skip during extreme volatility (ATR > 2x normal)
 *    - Adjust position sizing based on current volatility
 *
 * 3. DAY-OF-WEEK:
 *    - Weekend = hard block (markets closed)
 *    - Mid-week (Tue-Thu) gets bonus
 *    - Monday/Friday = neutral (no penalty)
 *
 * 4. NEWS EVENT AVOIDANCE:
 *    - Skip 15 minutes before/after major news
 *
 * 5. SYMBOL FIT (bonuses only):
 *    - Optimal symbols for SB windows get +1 bonus
 *    - Non-optimal symbols are neutral (no penalty)
 */
public class MarketConditionFilter {

    // Chicago timezone for futures
    private static final ZoneId CT_ZONE = ZoneId.of("America/Chicago");

    // Major news event times (ET/CT)
    private static final List<LocalTime> HIGH_IMPACT_TIMES = Arrays.asList(
            LocalTime.of(7, 30),   // Pre-market economic data (8:30 ET)
            LocalTime.of(9, 0),    // Market open (10:00 ET)
            LocalTime.of(9, 30),   // NYSE open (10:30 ET)
            LocalTime.of(13, 0)    // FOMC announcements often at 14:00 ET
    );

    private final KillzoneClock killzoneClock;
    private final SilverBulletClock silverBulletClock;

    public MarketConditionFilter() {
        this.killzoneClock = new KillzoneClock();
        this.silverBulletClock = new SilverBulletClock();
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

        // Check if we're in a Silver Bullet window (premium trading time)
        boolean inSilverBulletWindow = silverBulletClock.isInSilverBulletWindow(timestamp);
        SilverBulletClock.SilverBulletWindow sbWindow = silverBulletClock.getCurrentWindow(timestamp);
        int minutesRemaining = silverBulletClock.getMinutesRemaining(timestamp);

        // SESSION-AGNOSTIC: All instruments can trade in any session.
        // Session/killzone checks are bonuses only, never penalties.
        // If a tier aligns, the instrument executes regardless of session.

        // SB window symbol fit — bonus only, no penalty for non-optimal
        condition.addFactor(evaluateOptimalSymbol(timestamp, symbol, inSilverBulletWindow, sbWindow));

        // SB window timing — bonus only, no late-window penalty
        condition.addFactor(evaluateSBWindowTiming(inSilverBulletWindow, minutesRemaining));

        // 1. Check day of week quality (weekends still hard-blocked)
        condition.addFactor(evaluateDayOfWeek(dayOfWeek, time, inSilverBulletWindow));

        // 2. Check session timing (bonuses for overlaps/SB, no penalties)
        condition.addFactor(evaluateSessionTiming(timestamp, time, inSilverBulletWindow, sbWindow));

        // 3. Check volatility
        condition.addFactor(evaluateVolatility(atrCalculator, profile));

        // 4. Check news events
        condition.addFactor(evaluateNewsEvents(zdt, symbol));

        // 5. Check killzone phase OR Silver Bullet window
        condition.addFactor(evaluateKillzoneOrSilverBullet(timestamp, inSilverBulletWindow, sbWindow));

        // Calculate overall recommendation
        condition.calculate();

        return condition;
    }

    /**
     * Evaluate day of week quality.
     * Silver Bullet windows are exempt from Monday morning penalties.
     */
    private ConditionFactor evaluateDayOfWeek(DayOfWeek day, LocalTime time, boolean inSilverBulletWindow) {
        switch (day) {
            case MONDAY:
            case FRIDAY:
                // No penalties — if tier aligns, execute
                return new ConditionFactor("Day/Time", 0, day + " - normal");

            case TUESDAY:
            case WEDNESDAY:
            case THURSDAY:
                // Mid-week is typically best — bonus
                return new ConditionFactor("Day/Time", 1,
                        day + " - optimal trading day");

            case SATURDAY:
            case SUNDAY:
                return new ConditionFactor("Day/Time", -10,
                        "Weekend - markets closed");

            default:
                return new ConditionFactor("Day/Time", 0, "Unknown day");
        }
    }

    /**
     * Evaluate session timing — bonuses only, no penalties.
     * SESSION-AGNOSTIC: If a tier aligns, execute regardless of session timing.
     */
    private ConditionFactor evaluateSessionTiming(Instant timestamp, LocalTime time,
                                                   boolean inSilverBulletWindow,
                                                   SilverBulletClock.SilverBulletWindow sbWindow) {
        String killzone = killzoneClock.getKillzoneName(timestamp);

        // Session overlaps are premium — bonus
        if (killzone.contains("OVERLAP")) {
            return new ConditionFactor("Session", 2,
                    "Session overlap - maximum liquidity");
        }

        // Silver Bullet windows are PREMIUM trading times — bonus
        if (inSilverBulletWindow && sbWindow != null && sbWindow.isActive()) {
            return new ConditionFactor("Session", 2,
                    "Silver Bullet window: " + sbWindow.getName() + " - premium ICT setup time");
        }

        // Check if we're in a killzone — bonus for prime, neutral otherwise
        boolean inKillzone = killzoneClock.isInKillzone(timestamp);
        if (inKillzone) {
            KillzonePhase phase = killzoneClock.getKillzonePhase(timestamp);
            if (phase == KillzonePhase.PRIME) {
                return new ConditionFactor("Session", 2,
                        "Prime phase of " + killzone);
            }
            // Opening/closing phases — neutral, no penalty
            return new ConditionFactor("Session", 0,
                    "Killzone " + (phase != null ? phase : "active") + " - " + killzone);
        }

        // Outside killzone — neutral, no penalty
        return new ConditionFactor("Session", 0, "Outside killzone - session-agnostic");
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
     * Evaluate killzone phase OR Silver Bullet window quality — bonuses only, no penalties.
     * SESSION-AGNOSTIC: Outside killzone/SB is neutral (0), not penalized.
     */
    private ConditionFactor evaluateKillzoneOrSilverBullet(Instant timestamp,
                                                           boolean inSilverBulletWindow,
                                                           SilverBulletClock.SilverBulletWindow sbWindow) {
        // Silver Bullet windows are PREMIUM — give significant bonus
        if (inSilverBulletWindow && sbWindow != null && sbWindow.isActive()) {
            int minutesRemaining = silverBulletClock.getMinutesRemaining(timestamp);
            if (minutesRemaining >= 30) {
                return new ConditionFactor("Killzone/SB", 2,
                        "Silver Bullet PRIME - " + minutesRemaining + " min remaining");
            } else if (minutesRemaining >= 15) {
                return new ConditionFactor("Killzone/SB", 1,
                        "Silver Bullet active - " + minutesRemaining + " min remaining");
            } else {
                return new ConditionFactor("Killzone/SB", 0,
                        "Silver Bullet closing - " + minutesRemaining + " min remaining");
            }
        }

        // Killzone evaluation — bonuses for prime, neutral otherwise
        if (killzoneClock.isInKillzone(timestamp)) {
            KillzonePhase phase = killzoneClock.getKillzonePhase(timestamp);
            if (phase == null) {
                return new ConditionFactor("Killzone/SB", 0, "Killzone phase unavailable");
            }
            if (phase == KillzonePhase.PRIME) {
                return new ConditionFactor("Killzone/SB", 2, "PRIME phase - optimal");
            }
            // Opening/closing — neutral, no penalty
            return new ConditionFactor("Killzone/SB", 0, phase + " phase - neutral");
        }

        // Outside killzone and SB — neutral, no penalty
        return new ConditionFactor("Killzone/SB", 0, "Outside killzone/SB - session-agnostic");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SILVER BULLET QUALITY BONUSES (session-agnostic: bonuses only, no penalties)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Evaluate symbol fit for Silver Bullet window — bonus only, no penalty.
     * SESSION-AGNOSTIC: Non-optimal symbols are neutral (0), not rejected.
     *
     * Optimal symbols per window (get +1 bonus):
     * - London SB (3-4 AM ET): GC, MGC, SI (Metals)
     * - NY AM SB (10-11 AM ET): ES, MES, NQ, MNQ, GC, MGC (Indices & Gold)
     * - NY PM SB (2-3 PM ET): ES, MES, NQ, MNQ (Indices only)
     */
    private ConditionFactor evaluateOptimalSymbol(Instant timestamp, String symbol,
                                                   boolean inSilverBulletWindow,
                                                   SilverBulletClock.SilverBulletWindow sbWindow) {
        if (!inSilverBulletWindow || sbWindow == null || !sbWindow.isActive()) {
            return new ConditionFactor("Symbol Fit", 0, "Outside SB window");
        }

        boolean isOptimal = silverBulletClock.isOptimalSymbol(symbol, timestamp);

        if (isOptimal) {
            return new ConditionFactor("Symbol Fit", 1,
                    symbol + " is OPTIMAL for " + sbWindow.getName());
        } else {
            // Non-optimal — neutral, no penalty. If tier aligns, execute.
            return new ConditionFactor("Symbol Fit", 0,
                    symbol + " is non-optimal for " + sbWindow.getName() + " - no penalty");
        }
    }

    /**
     * Evaluate Silver Bullet window timing — bonus only, no penalty.
     * SESSION-AGNOSTIC: Late SB window is neutral (0), not rejected.
     *
     * First 30 min of SB window gets a +1 bonus for fresh liquidity.
     */
    private ConditionFactor evaluateSBWindowTiming(boolean inSilverBulletWindow, int minutesRemaining) {
        if (!inSilverBulletWindow) {
            return new ConditionFactor("SB Timing", 0, "Outside SB window");
        }

        if (minutesRemaining >= 30) {
            return new ConditionFactor("SB Timing", 1,
                    "First 30 min of SB window - OPTIMAL (" + minutesRemaining + " min left)");
        } else {
            // Late in SB window — neutral, no penalty. If tier aligns, execute.
            return new ConditionFactor("SB Timing", 0,
                    "Late SB window (" + minutesRemaining + " min left) - no penalty");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════

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
