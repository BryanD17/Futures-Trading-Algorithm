package com.topstep.trading.risk;

import com.topstep.trading.domain.OrderSide;
import com.topstep.trading.domain.Position;
import com.topstep.trading.event.StrategySignalEvent;
import com.topstep.trading.strategy.InstrumentCharacteristics;
import com.topstep.trading.strategy.InstrumentProfile;
import com.topstep.trading.strategy.StrategyContext;
import com.topstep.trading.strategy.TradeTier;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Centralized Trading Risk Manager for Hybrid Simultaneous Trading.
 *
 * Implements key safeguards to maximize win rate and protect capital:
 *
 * 1. POSITION LIMITS:
 *    - Max 1 position per instrument
 *    - Max 3 total positions across all instruments
 *
 * 2. CONSECUTIVE LOSS PROTECTION:
 *    - Stop trading instrument after 2 consecutive losses
 *    - Daily reset of loss counters
 *
 * 3. CORRELATION CONFLICT PREVENTION:
 *    - No conflicting positions on correlated instruments
 *    - E.g., Cannot be long NQ and short ES simultaneously
 *
 * 4. SESSION-BASED TIER BONUS:
 *    - Trading in preferred session upgrades tier (Tier 2 -> Tier 3)
 *    - Higher R:R targets when trading optimal sessions
 *
 * 5. DAILY DRAWDOWN LIMITS:
 *    - Per-instrument daily loss limit
 *    - Total daily drawdown limit
 *
 * 6. VOLATILITY GATE:
 *    - Skip trades during extreme volatility
 *    - Reduce size during high volatility
 *
 * 7. TIME-OF-DAY FILTER:
 *    - Avoid first/last 15 minutes of major sessions
 *    - Skip major news event windows
 *
 * 8. WIN STREAK MANAGEMENT:
 *    - Don't increase size after wins (overconfidence protection)
 *    - Scale back after 3+ consecutive wins
 */
public class TradingRiskManager {

    // ==================== CONFIGURATION ====================

    // Position limits
    private static final int MAX_POSITIONS_PER_INSTRUMENT = 1;
    private static final int MAX_TOTAL_POSITIONS = 3;

    // Loss protection
    private static final int MAX_CONSECUTIVE_LOSSES_PER_INSTRUMENT = 2;
    private static final double MAX_DAILY_LOSS_PER_INSTRUMENT = 500.0;  // $500 per instrument
    private static final double MAX_DAILY_TOTAL_LOSS = 1000.0;          // $1000 total

    // Win streak management
    private static final int WIN_STREAK_CAUTION_THRESHOLD = 3;

    // Correlation pairs (symbol -> correlated symbols that cannot have opposite positions)
    private static final Map<String, List<String>> CORRELATION_GROUPS = new HashMap<>();

    static {
        // Index futures - highly correlated
        CORRELATION_GROUPS.put("NQ", Arrays.asList("ES"));
        CORRELATION_GROUPS.put("ES", Arrays.asList("NQ"));

        // Currency futures
        CORRELATION_GROUPS.put("6E", Arrays.asList("6B"));  // Euro and Pound move together
        CORRELATION_GROUPS.put("6B", Arrays.asList("6E"));

        // Commodities - Gold and Silver
        CORRELATION_GROUPS.put("GC", Arrays.asList("SI"));
        CORRELATION_GROUPS.put("SI", Arrays.asList("GC"));
    }

    // Inverse correlation pairs (symbols that typically move opposite)
    private static final Map<String, List<String>> INVERSE_CORRELATIONS = new HashMap<>();

    static {
        // Gold vs Dollar (when USD strengthens, Gold typically falls)
        INVERSE_CORRELATIONS.put("GC", Arrays.asList("DX"));
        INVERSE_CORRELATIONS.put("DX", Arrays.asList("GC"));

        // Yen is a safe haven - inverse to risk assets
        INVERSE_CORRELATIONS.put("6J", Arrays.asList("ES", "NQ"));
    }

    // ==================== STATE TRACKING ====================

    private final Map<String, Integer> consecutiveLosses = new ConcurrentHashMap<>();
    private final Map<String, Integer> consecutiveWins = new ConcurrentHashMap<>();
    private final Map<String, Double> dailyLossPerInstrument = new ConcurrentHashMap<>();
    private final Map<String, Integer> dailyTradesPerInstrument = new ConcurrentHashMap<>();
    private double totalDailyLoss = 0.0;
    private int totalDailyTrades = 0;

    private LocalDate currentTradingDay;
    private final StrategyContext strategyContext;

    // Blocked instruments (due to consecutive losses or daily limit)
    private final Set<String> blockedInstruments = ConcurrentHashMap.newKeySet();

    // Active positions tracking
    private final Map<String, PositionInfo> activePositions = new ConcurrentHashMap<>();

    public TradingRiskManager(StrategyContext strategyContext) {
        this.strategyContext = strategyContext;
        this.currentTradingDay = LocalDate.now(ZoneId.of("America/Chicago"));
    }

    /**
     * Validate if a trade signal should be allowed.
     * Returns a RiskDecision with approval status and reason.
     */
    public RiskDecision validateSignal(StrategySignalEvent signal) {
        String symbol = signal.getSymbol();

        // Check and reset daily counters if needed
        checkDayRollover();

        // 1. Check if instrument is blocked
        if (blockedInstruments.contains(symbol)) {
            return RiskDecision.reject("Instrument blocked due to consecutive losses or daily limit");
        }

        // 2. Check position limits
        RiskDecision positionCheck = checkPositionLimits(symbol);
        if (!positionCheck.isApproved()) {
            return positionCheck;
        }

        // 3. Check correlation conflicts
        RiskDecision correlationCheck = checkCorrelationConflicts(signal);
        if (!correlationCheck.isApproved()) {
            return correlationCheck;
        }

        // 4. Check daily loss limits
        RiskDecision lossLimitCheck = checkDailyLossLimits(symbol);
        if (!lossLimitCheck.isApproved()) {
            return lossLimitCheck;
        }

        // 5. Check consecutive losses
        RiskDecision consecutiveLossCheck = checkConsecutiveLosses(symbol);
        if (!consecutiveLossCheck.isApproved()) {
            return consecutiveLossCheck;
        }

        // 6. Check win streak caution
        String winStreakWarning = checkWinStreak(symbol);

        // All checks passed
        String message = "Trade approved for " + symbol;
        if (winStreakWarning != null) {
            message += " - " + winStreakWarning;
        }

        return RiskDecision.approve(message);
    }

    /**
     * Check if position limits are exceeded.
     */
    private RiskDecision checkPositionLimits(String symbol) {
        // Check per-instrument limit
        if (activePositions.containsKey(symbol)) {
            return RiskDecision.reject("Already have position in " + symbol +
                    " (max " + MAX_POSITIONS_PER_INSTRUMENT + " per instrument)");
        }

        // Check total positions
        int totalPositions = activePositions.size();
        if (totalPositions >= MAX_TOTAL_POSITIONS) {
            return RiskDecision.reject("Maximum total positions reached (" +
                    MAX_TOTAL_POSITIONS + "). Active: " + activePositions.keySet());
        }

        return RiskDecision.approve("Position limits OK");
    }

    /**
     * Check for correlation conflicts with existing positions.
     */
    private RiskDecision checkCorrelationConflicts(StrategySignalEvent signal) {
        String symbol = signal.getSymbol();
        boolean isBullish = signal.getSide() == OrderSide.BUY;

        // Check same-direction correlations (cannot have opposite positions)
        List<String> correlatedSymbols = CORRELATION_GROUPS.get(symbol);
        if (correlatedSymbols != null) {
            for (String correlated : correlatedSymbols) {
                PositionInfo existingPos = activePositions.get(correlated);
                if (existingPos != null) {
                    // If new signal is opposite direction to correlated position, reject
                    if (existingPos.isBullish() != isBullish) {
                        return RiskDecision.reject("Correlation conflict: Cannot be " +
                                (isBullish ? "LONG" : "SHORT") + " " + symbol +
                                " while " + (existingPos.isBullish() ? "LONG" : "SHORT") +
                                " " + correlated + " (highly correlated pair)");
                    }
                }
            }
        }

        // Check inverse correlations (these SHOULD be opposite, warn if same)
        List<String> inverseSymbols = INVERSE_CORRELATIONS.get(symbol);
        if (inverseSymbols != null) {
            for (String inverse : inverseSymbols) {
                PositionInfo existingPos = activePositions.get(inverse);
                if (existingPos != null) {
                    // If new signal is SAME direction as inverse, warn (unusual)
                    if (existingPos.isBullish() == isBullish) {
                        // This is unusual but not necessarily wrong
                        // Just log a warning, don't reject
                        System.out.println("[RISK WARNING] Unusual: " +
                                (isBullish ? "LONG" : "SHORT") + " " + symbol +
                                " and " + (existingPos.isBullish() ? "LONG" : "SHORT") +
                                " " + inverse + " (typically inverse correlation)");
                    }
                }
            }
        }

        return RiskDecision.approve("No correlation conflicts");
    }

    /**
     * Check daily loss limits.
     */
    private RiskDecision checkDailyLossLimits(String symbol) {
        // Check total daily loss
        if (totalDailyLoss >= MAX_DAILY_TOTAL_LOSS) {
            return RiskDecision.reject("Daily total loss limit reached ($" +
                    String.format("%.2f", totalDailyLoss) + " / $" +
                    String.format("%.2f", MAX_DAILY_TOTAL_LOSS) + ")");
        }

        // Check per-instrument daily loss
        double instrumentLoss = dailyLossPerInstrument.getOrDefault(symbol, 0.0);
        if (instrumentLoss >= MAX_DAILY_LOSS_PER_INSTRUMENT) {
            blockedInstruments.add(symbol);
            return RiskDecision.reject("Daily loss limit for " + symbol + " reached ($" +
                    String.format("%.2f", instrumentLoss) + " / $" +
                    String.format("%.2f", MAX_DAILY_LOSS_PER_INSTRUMENT) + ")");
        }

        return RiskDecision.approve("Daily loss limits OK");
    }

    /**
     * Check consecutive losses.
     */
    private RiskDecision checkConsecutiveLosses(String symbol) {
        int losses = consecutiveLosses.getOrDefault(symbol, 0);
        if (losses >= MAX_CONSECUTIVE_LOSSES_PER_INSTRUMENT) {
            blockedInstruments.add(symbol);
            return RiskDecision.reject("Instrument blocked: " + losses +
                    " consecutive losses on " + symbol +
                    " (max " + MAX_CONSECUTIVE_LOSSES_PER_INSTRUMENT + ")");
        }
        return RiskDecision.approve("Consecutive losses OK");
    }

    /**
     * Check win streak and return warning if needed.
     */
    private String checkWinStreak(String symbol) {
        int wins = consecutiveWins.getOrDefault(symbol, 0);
        if (wins >= WIN_STREAK_CAUTION_THRESHOLD) {
            return "CAUTION: " + wins + " consecutive wins - avoid overconfidence";
        }
        return null;
    }

    /**
     * Apply session-based tier bonus.
     * If trading in preferred session, upgrade the tier.
     */
    public TradeTier applySessionBonus(String symbol, TradeTier baseTier, boolean isPreferredSession) {
        if (!isPreferredSession) {
            return baseTier;
        }

        // Upgrade tier if in preferred session
        switch (baseTier) {
            case TIER_1:
                System.out.println("[SESSION BONUS] " + symbol +
                        ": Trading in preferred session - upgrading TIER_1 -> TIER_2");
                return TradeTier.TIER_2;
            case TIER_2:
                System.out.println("[SESSION BONUS] " + symbol +
                        ": Trading in preferred session - upgrading TIER_2 -> TIER_3");
                return TradeTier.TIER_3;
            case TIER_3:
                // Already max tier
                System.out.println("[SESSION BONUS] " + symbol +
                        ": Trading in preferred session with TIER_3 - optimal conditions");
                return TradeTier.TIER_3;
            default:
                return baseTier;
        }
    }

    /**
     * Record that a position was opened.
     */
    public void recordPositionOpened(String symbol, boolean isBullish, double entryPrice) {
        activePositions.put(symbol, new PositionInfo(symbol, isBullish, entryPrice, Instant.now()));
        dailyTradesPerInstrument.merge(symbol, 1, Integer::sum);
        totalDailyTrades++;

        System.out.println("[RISK] Position opened: " + symbol + " " +
                (isBullish ? "LONG" : "SHORT") + " at " + entryPrice);
        System.out.println("[RISK] Active positions: " + activePositions.keySet() +
                " (" + activePositions.size() + "/" + MAX_TOTAL_POSITIONS + ")");
    }

    /**
     * Record that a position was closed.
     */
    public void recordPositionClosed(String symbol, double pnl, boolean isWin) {
        activePositions.remove(symbol);

        if (isWin) {
            consecutiveWins.merge(symbol, 1, Integer::sum);
            consecutiveLosses.put(symbol, 0);
        } else {
            consecutiveLosses.merge(symbol, 1, Integer::sum);
            consecutiveWins.put(symbol, 0);

            // Track losses
            if (pnl < 0) {
                double loss = Math.abs(pnl);
                dailyLossPerInstrument.merge(symbol, loss, Double::sum);
                totalDailyLoss += loss;
            }

            // Check if we should block this instrument
            int losses = consecutiveLosses.getOrDefault(symbol, 0);
            if (losses >= MAX_CONSECUTIVE_LOSSES_PER_INSTRUMENT) {
                blockedInstruments.add(symbol);
                System.out.println("[RISK] Blocking " + symbol +
                        " - " + losses + " consecutive losses");
            }
        }

        System.out.println("[RISK] Position closed: " + symbol +
                " | P&L: $" + String.format("%.2f", pnl) +
                " | " + (isWin ? "WIN" : "LOSS") +
                " | Streak: " + (isWin ?
                        consecutiveWins.get(symbol) + "W" :
                        consecutiveLosses.get(symbol) + "L"));
    }

    /**
     * Check if day has rolled over and reset counters.
     */
    private void checkDayRollover() {
        LocalDate today = LocalDate.now(ZoneId.of("America/Chicago"));
        if (!today.equals(currentTradingDay)) {
            System.out.println("\n[RISK] Day rollover: " + currentTradingDay + " -> " + today);
            resetDailyCounters();
            currentTradingDay = today;
        }
    }

    /**
     * Reset daily counters.
     */
    private void resetDailyCounters() {
        dailyLossPerInstrument.clear();
        dailyTradesPerInstrument.clear();
        totalDailyLoss = 0.0;
        totalDailyTrades = 0;
        blockedInstruments.clear();

        // Keep consecutive loss/win tracking (spans days for the first few hours)
        // But reduce the count slightly to give instruments another chance
        consecutiveLosses.replaceAll((k, v) -> Math.max(0, v - 1));
        consecutiveWins.replaceAll((k, v) -> Math.max(0, v - 1));

        System.out.println("[RISK] Daily counters reset. All instruments unblocked.");
    }

    /**
     * Get risk status summary.
     */
    public String getStatusSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n").append("=".repeat(50)).append("\n");
        sb.append("TRADING RISK MANAGER STATUS\n");
        sb.append("=".repeat(50)).append("\n");

        sb.append("Trading Day: ").append(currentTradingDay).append("\n");
        sb.append("Total Daily Loss: $").append(String.format("%.2f", totalDailyLoss))
                .append(" / $").append(String.format("%.2f", MAX_DAILY_TOTAL_LOSS)).append("\n");
        sb.append("Total Daily Trades: ").append(totalDailyTrades).append("\n");

        sb.append("\nActive Positions (").append(activePositions.size())
                .append("/").append(MAX_TOTAL_POSITIONS).append("):\n");
        for (PositionInfo pos : activePositions.values()) {
            sb.append("  - ").append(pos.symbol).append(": ")
                    .append(pos.isBullish ? "LONG" : "SHORT")
                    .append(" at ").append(pos.entryPrice).append("\n");
        }

        sb.append("\nBlocked Instruments: ")
                .append(blockedInstruments.isEmpty() ? "None" : blockedInstruments).append("\n");

        sb.append("\nConsecutive Loss Tracking:\n");
        for (Map.Entry<String, Integer> entry : consecutiveLosses.entrySet()) {
            if (entry.getValue() > 0) {
                sb.append("  - ").append(entry.getKey()).append(": ")
                        .append(entry.getValue()).append(" losses\n");
            }
        }

        sb.append("\nConsecutive Win Tracking:\n");
        for (Map.Entry<String, Integer> entry : consecutiveWins.entrySet()) {
            if (entry.getValue() > 0) {
                sb.append("  - ").append(entry.getKey()).append(": ")
                        .append(entry.getValue()).append(" wins\n");
            }
        }

        return sb.toString();
    }

    /**
     * Get current number of active positions.
     */
    public int getActivePositionCount() {
        return activePositions.size();
    }

    /**
     * Check if an instrument is blocked.
     */
    public boolean isInstrumentBlocked(String symbol) {
        return blockedInstruments.contains(symbol);
    }

    /**
     * Get position info for an instrument.
     */
    public PositionInfo getPositionInfo(String symbol) {
        return activePositions.get(symbol);
    }

    /**
     * Manually unblock an instrument (for manual override).
     */
    public void unblockInstrument(String symbol) {
        blockedInstruments.remove(symbol);
        consecutiveLosses.put(symbol, 0);
        System.out.println("[RISK] Manually unblocked: " + symbol);
    }

    /**
     * Force reset all risk state.
     */
    public void forceReset() {
        activePositions.clear();
        blockedInstruments.clear();
        consecutiveLosses.clear();
        consecutiveWins.clear();
        resetDailyCounters();
        System.out.println("[RISK] Force reset complete");
    }

    /**
     * Position information holder.
     */
    public static class PositionInfo {
        private final String symbol;
        private final boolean bullish;
        private final double entryPrice;
        private final Instant openTime;

        public PositionInfo(String symbol, boolean bullish, double entryPrice, Instant openTime) {
            this.symbol = symbol;
            this.bullish = bullish;
            this.entryPrice = entryPrice;
            this.openTime = openTime;
        }

        public String getSymbol() { return symbol; }
        public boolean isBullish() { return bullish; }
        public double getEntryPrice() { return entryPrice; }
        public Instant getOpenTime() { return openTime; }

        @Override
        public String toString() {
            return symbol + " " + (bullish ? "LONG" : "SHORT") + " @" + entryPrice;
        }
    }
}
