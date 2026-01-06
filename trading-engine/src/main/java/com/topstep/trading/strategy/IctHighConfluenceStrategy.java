package com.topstep.trading.strategy;

import com.topstep.trading.domain.Candle;
import com.topstep.trading.domain.OrderSide;
import com.topstep.trading.event.EventBus;
import com.topstep.trading.event.StrategySignalEvent;

/**
 * ICT High-Confluence Strategy combining multiple ICT/SMC concepts:
 *
 * ENHANCED FEATURES:
 * 1. Breaker Blocks - Failed OBs that flip (highest probability)
 * 2. Mitigation Blocks - Unmitigated price areas
 * 3. Power of 3 (AMD) - Accumulation, Manipulation, Distribution
 * 4. Killzone Phase - Opening/Prime/Closing timing
 * 5. ATR-based volatility adaptive sizing
 * 6. Dynamic R:R based on trade tier
 * 7. Correlation tracking for SMT divergence
 *
 * TIER SYSTEM (Higher tier = Higher quality = Better R:R):
 * - Tier 3: Breaker Block OR (IFVG + OB + Displacement + Power of 3) = 1:4 R:R
 * - Tier 2: Order Block + Displacement OR Unfilled FVG + SMT = 1:2 R:R
 * - Tier 1: Any FVG OR SMT OR Displacement at OTE = 1:1 R:R
 */
public class IctHighConfluenceStrategy implements TradingStrategy {

    private final String primarySymbol;
    private final String smtSymbol;
    private final EventBus eventBus;

    // Core detectors
    private final IctStructureDetector structureDetector;
    private final LiquidityDetector liquidityDetector;
    private final FvgDetector fvgDetector;
    private final OrderBlockDetector orderBlockDetector;
    private final DisplacementDetector displacementDetector;
    private final KillzoneClock killzoneClock;

    // Enhanced detectors
    private final BreakerBlockDetector breakerBlockDetector;
    private final MitigationBlockDetector mitigationBlockDetector;
    private final Power3Detector power3Detector;
    private final ATRCalculator atrCalculator;
    private final CorrelationTracker correlationTracker;

    // Configuration
    private final double fibLow = 0.62;   // OTE zone low
    private final double fibHigh = 0.705; // OTE zone high
    private final int basePositionSize = 1;
    private final int maxPositionSize = 2;
    private final double maxPriceDistance = 50.0;  // Max distance for entry zones

    // State tracking
    private int candleCount = 0;
    private volatile boolean signalPending = false;
    private volatile long lastSignalTime = 0;
    private static final long SIGNAL_TIMEOUT_MS = 60000;

    // Debug counters for diagnosing signal generation
    private int outsideKillzone = 0;
    private int notTradingDay = 0;
    private int wrongPhase = 0;
    private int volatilityBlocked = 0;
    private int neutralBias = 0;
    private int noSweep = 0;
    private int sweepMismatch = 0;
    private int noEntryZone = 0;
    private int signalsGenerated = 0;

    // Current trade setup info
    private TradeTier currentTier = null;
    private BreakerBlock currentBreaker = null;
    private FairValueGap currentFvg = null;
    private OrderBlock currentOrderBlock = null;
    private MitigationBlock currentMitigationBlock = null;
    private boolean hasDisplacement = false;
    private boolean hasPower3Confirmation = false;
    private int recommendedQuantity = 1;

    public IctHighConfluenceStrategy(String primarySymbol, String smtSymbol, EventBus eventBus) {
        this.primarySymbol = primarySymbol;
        this.smtSymbol = smtSymbol;
        this.eventBus = eventBus;

        // Initialize core detectors
        this.structureDetector = new IctStructureDetector(50);
        this.liquidityDetector = new LiquidityDetector(30);
        this.fvgDetector = new FvgDetector(20);
        this.orderBlockDetector = new OrderBlockDetector(30, 10);
        this.displacementDetector = new DisplacementDetector(20);
        this.killzoneClock = new KillzoneClock();

        // Initialize enhanced detectors
        this.breakerBlockDetector = new BreakerBlockDetector(orderBlockDetector, 10);
        this.mitigationBlockDetector = new MitigationBlockDetector(fvgDetector, orderBlockDetector, breakerBlockDetector, 20);
        this.power3Detector = new Power3Detector(30);
        this.atrCalculator = new ATRCalculator(14);  // 14-period ATR
        this.correlationTracker = new CorrelationTracker(50);
    }

    @Override
    public void onCandle(Candle candle, StrategyContext context) {
        if (candle.getSymbol().equals(primarySymbol)) {
            handlePrimaryCandle(candle, context);
        } else if (candle.getSymbol().equals(smtSymbol)) {
            handleSmtCandle(candle);
        }
    }

    private void handlePrimaryCandle(Candle candle, StrategyContext context) {
        candleCount++;

        // Reset signalPending if timeout exceeded
        if (signalPending && (System.currentTimeMillis() - lastSignalTime) > SIGNAL_TIMEOUT_MS) {
            System.out.println("[STRATEGY] Signal timeout - resetting pending flag");
            signalPending = false;
        }

        // Update ALL detectors
        updateDetectors(candle);

        // Don't trade if we already have a position
        if (context.hasPosition(primarySymbol)) {
            signalPending = false;
            return;
        }

        // Don't generate multiple signals while one is pending
        if (signalPending) {
            return;
        }

        // Check all confluences and determine tier
        if (!checkConfluences(candle, context)) {
            return;
        }

        // All confluences met - generate signal
        signalPending = true;
        lastSignalTime = System.currentTimeMillis();
        generateSignal(candle, context);
    }

    private void handleSmtCandle(Candle candle) {
        liquidityDetector.updateSmt(candle);
        correlationTracker.update(candle);
    }

    /**
     * Update all detectors with new candle data.
     */
    private void updateDetectors(Candle candle) {
        structureDetector.update(candle);
        liquidityDetector.updatePrimary(candle);
        fvgDetector.update(candle);
        orderBlockDetector.update(candle);
        displacementDetector.update(candle);
        breakerBlockDetector.update(candle);
        mitigationBlockDetector.update(candle);
        power3Detector.update(candle);
        atrCalculator.update(candle);
        correlationTracker.update(candle);
    }

    /**
     * Check all confluences and determine trade tier.
     */
    private boolean checkConfluences(Candle candle, StrategyContext context) {
        boolean shouldLog = (candleCount % 10 == 0);

        // Reset current setup
        currentTier = null;
        currentBreaker = null;
        currentFvg = null;
        currentOrderBlock = null;
        currentMitigationBlock = null;
        hasDisplacement = false;
        hasPower3Confirmation = false;

        // 1. Check killzone and phase
        boolean inKillzone = killzoneClock.isInKillzone(candle.getTimestamp());
        KillzonePhase phase = killzoneClock.getKillzonePhase(candle.getTimestamp());

        if (!inKillzone) {
            outsideKillzone++;
            return false;
        }

        if (!killzoneClock.isTradingDay(candle.getTimestamp())) {
            notTradingDay++;
            return false;
        }

        // Check killzone phase - only trade during PRIME phase
        if (!phase.allowsNewEntries()) {
            wrongPhase++;
            return false;
        }

        // 2. Check volatility - is it tradeable?
        if (!atrCalculator.isTradeable()) {
            volatilityBlocked++;
            return false;
        }

        // 3. Check higher-timeframe structure bias
        MarketBias bias = structureDetector.getBias();
        if (bias == MarketBias.NEUTRAL) {
            neutralBias++;
            return false;
        }

        // 4. Check for recent liquidity sweep
        // TIGHTENED: Sweep must be within 5 candles (was 15) for fresher setups
        boolean hasRecentSweep = liquidityDetector.hasRecentSweep(5);
        if (!hasRecentSweep) {
            noSweep++;
            return false;
        }

        LiquiditySweep sweep = liquidityDetector.getLastSweep();
        if (sweep == null) {
            // This shouldn't happen if hasRecentSweep returned true
            System.out.println("[STRATEGY] WARNING: hasRecentSweep=true but getLastSweep returned null");
            return false;
        }

        // 5. Ensure sweep matches bias
        if (bias == MarketBias.BULLISH && !sweep.isBullish()) {
            sweepMismatch++;
            return false;
        }
        if (bias == MarketBias.BEARISH && !sweep.isBearish()) {
            sweepMismatch++;
            return false;
        }

        boolean isBullish = bias == MarketBias.BULLISH;

        // 6. Check for additional confluences
        boolean hasSmtDivergence = sweep.hasSmtDivergence() ||
                correlationTracker.hasSMTDivergence(primarySymbol, smtSymbol, 10);
        hasDisplacement = displacementDetector.hasRecentDisplacement(10, isBullish);
        hasPower3Confirmation = power3Detector.isInDistribution() &&
                power3Detector.confirmsDirection(isBullish);

        // 7. Look for entry zones by tier (highest to lowest)
        // Count confluences for tier determination
        int confluenceCount = 0;

        // TIER 4: Breaker Block + Power3 + SMT + Displacement (ALL required)
        currentBreaker = breakerBlockDetector.findNearestBreaker(candle.getClose(), isBullish, maxPriceDistance);
        FairValueGap ifvg = fvgDetector.findNearestIfvg(candle.getClose(), isBullish);
        currentOrderBlock = orderBlockDetector.findNearestValidOb(candle.getClose(), isBullish, maxPriceDistance);

        if (currentBreaker != null && hasPower3Confirmation && hasSmtDivergence && hasDisplacement) {
            // Elite setup - ALL confluences aligned
            currentTier = TradeTier.TIER_4;
            printTier4Signal(candle, bias, sweep, "Breaker+Power3+SMT+Displacement", hasSmtDivergence);
            return true;
        }

        // TIER 3: Breaker Block + Displacement + (SMT OR Power3)
        //     OR: (IFVG + OB + Displacement + Power3)
        // TIGHTENED: Displacement is now REQUIRED for Tier 3
        if (currentBreaker != null && hasDisplacement) {
            confluenceCount = 2;  // Breaker + Displacement counts as 2
            if (hasSmtDivergence) confluenceCount++;
            if (hasPower3Confirmation) confluenceCount++;

            if (confluenceCount >= 3) {
                // Breaker + Displacement + at least 1 more confirmation = Tier 3
                currentTier = TradeTier.TIER_3;
                String entryType = "Breaker+Displacement";
                if (hasSmtDivergence) entryType += "+SMT";
                if (hasPower3Confirmation) entryType += "+Power3";
                printTier3Signal(candle, bias, sweep, entryType, hasSmtDivergence);
                return true;
            }
        }

        if (ifvg != null && currentOrderBlock != null && hasDisplacement && hasPower3Confirmation) {
            // Full confluence without Breaker - Tier 3
            currentTier = TradeTier.TIER_3;
            currentFvg = ifvg;
            printTier3Signal(candle, bias, sweep, "IFVG+OB+Displacement+Power3", hasSmtDivergence);
            return true;
        }

        // TIER 2: (OB + Displacement + SMT)
        //     OR: (Unfilled FVG + SMT + Displacement)
        //     OR: (Fresh Mitigation + SMT)
        FairValueGap unfilledFvg = fvgDetector.findNearestUnfilledFvg(candle.getClose(), isBullish, maxPriceDistance);

        if (currentOrderBlock != null && hasDisplacement && hasSmtDivergence) {
            // Triple confluence with OB
            currentTier = TradeTier.TIER_2;
            printTier2Signal(candle, bias, sweep, "OB+Displacement+SMT", hasSmtDivergence);
            return true;
        }

        if (unfilledFvg != null && hasSmtDivergence && hasDisplacement) {
            // Triple confluence with FVG
            currentTier = TradeTier.TIER_2;
            currentFvg = unfilledFvg;
            printTier2Signal(candle, bias, sweep, "FVG+SMT+Displacement", hasSmtDivergence);
            return true;
        }

        currentMitigationBlock = mitigationBlockDetector.findBestMitigationZone(candle.getClose(), isBullish, maxPriceDistance);
        if (currentMitigationBlock != null && currentMitigationBlock.isFresh() && hasSmtDivergence && hasDisplacement) {
            // Fresh mitigation + SMT + Displacement (TIGHTENED: displacement now required)
            currentTier = TradeTier.TIER_2;
            printTier2Signal(candle, bias, sweep, "Fresh Mitigation+SMT+Displacement", hasSmtDivergence);
            return true;
        }

        // ═══════════════════════════════════════════════════════════════════════════
        // TIER 1 DISABLED - MINIMUM TIER 2 REQUIRED (3 confluences + displacement)
        // ═══════════════════════════════════════════════════════════════════════════
        // After losing trades, we've tightened requirements:
        // - Tier 1 (2 confluences) is NO LONGER accepted
        // - ALL trades must have displacement (institutional move)
        // - Minimum 3 confluences required (Tier 2+)
        // This drastically reduces trade frequency but improves win rate.
        // ═══════════════════════════════════════════════════════════════════════════

        FairValueGap anyFvg = fvgDetector.findNearestFvg(candle.getClose(), isBullish, maxPriceDistance);

        confluenceCount = 0;
        if (anyFvg != null) confluenceCount++;
        if (hasSmtDivergence) confluenceCount++;
        if (hasDisplacement) confluenceCount++;
        if (currentOrderBlock != null) confluenceCount++;

        // TIGHTENED: Require 3+ confluences AND displacement for ANY entry
        if (confluenceCount >= 3 && hasDisplacement) {
            // Triple confluence with displacement - promoted to Tier 2
            currentTier = TradeTier.TIER_2;
            currentFvg = anyFvg;
            String entryType = "";
            if (anyFvg != null) entryType += "FVG";
            if (hasSmtDivergence) entryType += (entryType.isEmpty() ? "" : "+") + "SMT";
            entryType += (entryType.isEmpty() ? "" : "+") + "Displacement";
            if (currentOrderBlock != null) entryType += "+OB";
            printTier2Signal(candle, bias, sweep, entryType + " [PROMOTED]", hasSmtDivergence);
            return true;
        }

        // REJECTED: Less than 3 confluences OR missing displacement
        // Log rejection reason for debugging
        if (confluenceCount >= 2 && !hasDisplacement) {
            System.out.println("[" + primarySymbol + "] REJECTED: Had " + confluenceCount +
                " confluences but NO DISPLACEMENT (required for all entries)");
        } else if (confluenceCount < 3) {
            System.out.println("[" + primarySymbol + "] REJECTED: Only " + confluenceCount +
                " confluences (minimum 3 required, Tier 1 disabled)");
        }

        noEntryZone++;
        return false;
    }

    private void printTier4Signal(Candle candle, MarketBias bias, LiquiditySweep sweep,
                                   String entryType, boolean hasSmt) {
        System.out.println("\n[" + primarySymbol + "] ★★★★ TIER 4 CONFLUENCE - ELITE SETUP ★★★★");
        System.out.println("[" + primarySymbol + "] Entry Type: " + entryType);
        printCommonInfo(candle, bias, sweep, hasSmt);
    }

    private void printTier3Signal(Candle candle, MarketBias bias, LiquiditySweep sweep,
                                   String entryType, boolean hasSmt) {
        System.out.println("\n[" + primarySymbol + "] ★★★ TIER 3 CONFLUENCE - PREMIUM SETUP ★★★");
        System.out.println("[" + primarySymbol + "] Entry Type: " + entryType);
        printCommonInfo(candle, bias, sweep, hasSmt);
    }

    private void printTier2Signal(Candle candle, MarketBias bias, LiquiditySweep sweep,
                                   String entryType, boolean hasSmt) {
        System.out.println("\n[" + primarySymbol + "] ★★ TIER 2 CONFLUENCE - STANDARD SETUP ★★");
        System.out.println("[" + primarySymbol + "] Entry Type: " + entryType);
        printCommonInfo(candle, bias, sweep, hasSmt);
    }

    private void printTier1Signal(Candle candle, MarketBias bias, LiquiditySweep sweep,
                                   String entryType, boolean hasSmt) {
        System.out.println("\n[" + primarySymbol + "] ★ TIER 1 CONFLUENCE - CONFIRMED SETUP ★");
        System.out.println("[" + primarySymbol + "] Entry Type: " + entryType);
        printCommonInfo(candle, bias, sweep, hasSmt);
    }

    private void printCommonInfo(Candle candle, MarketBias bias, LiquiditySweep sweep, boolean hasSmt) {
        System.out.println("[" + primarySymbol + "] Session: " + killzoneClock.getKillzoneName(candle.getTimestamp()));
        System.out.println("[" + primarySymbol + "] Bias: " + bias + " | Sweep: " + (sweep.isBullish() ? "BULLISH" : "BEARISH"));
        System.out.println("[" + primarySymbol + "] SMT: " + (hasSmt ? "✓" : "~") +
                          " | Displacement: " + (hasDisplacement ? "✓" : "~") +
                          " | Power3: " + (hasPower3Confirmation ? "✓" : "~"));
        double adjustedRR = currentTier.getRiskRewardRatio() * currentTier.getTierMultiplier();
        System.out.println("[" + primarySymbol + "] R:R Target: 1:" + currentTier.getRiskRewardRatio() +
                          " (adjusted by tier multiplier: " + currentTier.getTierMultiplier() + ")");
    }

    /**
     * Generate signal with tier-based R:R and quantity.
     */
    private void generateSignal(Candle candle, StrategyContext context) {
        MarketBias bias = structureDetector.getBias();
        LiquiditySweep sweep = liquidityDetector.getLastSweep();

        // Calculate position size based on ATR
        recommendedQuantity = atrCalculator.getRecommendedPositionSize(basePositionSize, maxPositionSize);

        if (bias == MarketBias.BULLISH && sweep.isBullish()) {
            generateBullishSignal(candle, sweep);
        } else if (bias == MarketBias.BEARISH && sweep.isBearish()) {
            generateBearishSignal(candle, sweep);
        }
    }

    private void generateBullishSignal(Candle candle, LiquiditySweep sweep) {
        Double swingHigh = structureDetector.getLastSwingHigh();
        if (swingHigh == null) {
            System.out.println("[STRATEGY] Cannot generate bullish signal: no swing high detected yet");
            signalPending = false;
            return;
        }

        double swingLow = sweep.getSweptLevel();
        double range = swingHigh - swingLow;

        // Entry: Based on entry zone type
        double entry = calculateEntry(candle.getClose(), swingLow, range, true);

        // Stop: Below entry zone with ATR adjustment
        double stopMultiplier = atrCalculator.getStopMultiplier();
        double baseStop = getBaseStopLevel(swingLow, true);
        double stop = baseStop - (5.0 * stopMultiplier);

        // Target: Based on tier R:R
        double riskDistance = entry - stop;
        double target = entry + (riskDistance * currentTier.getRiskRewardRatio());

        // Validate R:R
        if (riskDistance <= 0) {
            System.out.println("[STRATEGY] Invalid bullish signal: risk distance <= 0 (entry=" +
                              String.format("%.2f", entry) + ", stop=" + String.format("%.2f", stop) + ")");
            signalPending = false;
            return;
        }

        String reason = buildSignalReason("Bullish", candle);

        StrategySignalEvent signal = new StrategySignalEvent(
                StrategySignalEvent.SignalType.LONG_ENTRY,
                primarySymbol,
                OrderSide.BUY,
                entry,
                stop,
                target,
                reason,
                currentTier,
                recommendedQuantity
        );

        signalsGenerated++;
        eventBus.publish(signal);
    }

    private void generateBearishSignal(Candle candle, LiquiditySweep sweep) {
        Double swingLow = structureDetector.getLastSwingLow();
        if (swingLow == null) {
            System.out.println("[STRATEGY] Cannot generate bearish signal: no swing low detected yet");
            signalPending = false;
            return;
        }

        double swingHigh = sweep.getSweptLevel();
        double range = swingHigh - swingLow;

        // Entry: Based on entry zone type
        double entry = calculateEntry(candle.getClose(), swingHigh, range, false);

        // Stop: Above entry zone with ATR adjustment
        double stopMultiplier = atrCalculator.getStopMultiplier();
        double baseStop = getBaseStopLevel(swingHigh, false);
        double stop = baseStop + (5.0 * stopMultiplier);

        // Target: Based on tier R:R
        double riskDistance = stop - entry;
        double target = entry - (riskDistance * currentTier.getRiskRewardRatio());

        // Validate R:R
        if (riskDistance <= 0) {
            System.out.println("[STRATEGY] Invalid bearish signal: risk distance <= 0 (entry=" +
                              String.format("%.2f", entry) + ", stop=" + String.format("%.2f", stop) + ")");
            signalPending = false;
            return;
        }

        String reason = buildSignalReason("Bearish", candle);

        StrategySignalEvent signal = new StrategySignalEvent(
                StrategySignalEvent.SignalType.SHORT_ENTRY,
                primarySymbol,
                OrderSide.SELL,
                entry,
                stop,
                target,
                reason,
                currentTier,
                recommendedQuantity
        );

        signalsGenerated++;
        eventBus.publish(signal);
    }

    /**
     * Calculate entry price based on available entry zones.
     */
    private double calculateEntry(double currentPrice, double swingLevel, double range, boolean bullish) {
        // Priority: Breaker > Mitigation > FVG > OB > OTE zone

        if (currentBreaker != null) {
            return currentBreaker.getOptimalEntry();
        }

        if (currentMitigationBlock != null) {
            return currentMitigationBlock.getMidpoint();
        }

        if (currentFvg != null) {
            return bullish ? currentFvg.getTop() - (currentFvg.getTop() - currentFvg.getBottom()) * 0.25
                          : currentFvg.getBottom() + (currentFvg.getTop() - currentFvg.getBottom()) * 0.25;
        }

        if (currentOrderBlock != null) {
            return currentOrderBlock.getMidpoint();
        }

        // Default to OTE zone
        if (bullish) {
            double entryLow = swingLevel + (range * fibLow);
            double entryHigh = swingLevel + (range * fibHigh);
            return (entryLow + entryHigh) / 2.0;
        } else {
            double entryHigh = swingLevel - (range * fibLow);
            double entryLow = swingLevel - (range * fibHigh);
            return (entryLow + entryHigh) / 2.0;
        }
    }

    /**
     * Get base stop level from entry zones.
     */
    private double getBaseStopLevel(double swingLevel, boolean bullish) {
        if (currentBreaker != null) {
            return bullish ? currentBreaker.getLow() : currentBreaker.getHigh();
        }

        if (currentFvg != null) {
            return bullish ? currentFvg.getBottom() : currentFvg.getTop();
        }

        if (currentOrderBlock != null) {
            return bullish ? currentOrderBlock.getLow() : currentOrderBlock.getHigh();
        }

        return swingLevel;
    }

    /**
     * Build signal reason string.
     */
    private String buildSignalReason(String direction, Candle candle) {
        StringBuilder reason = new StringBuilder();
        reason.append(direction).append(": ").append(currentTier);

        if (currentBreaker != null) {
            reason.append(", Breaker Block");
        } else if (currentMitigationBlock != null) {
            reason.append(", Mitigation Zone");
        } else if (currentFvg != null) {
            reason.append(", FVG");
        } else if (currentOrderBlock != null) {
            reason.append(", Order Block");
        }

        if (hasDisplacement) reason.append(", Displacement");
        if (hasPower3Confirmation) reason.append(", Power3");

        reason.append(" in ").append(killzoneClock.getKillzoneName(candle.getTimestamp()));

        return reason.toString();
    }

    @Override
    public String getName() {
        return "ICT Enhanced High Confluence Strategy";
    }

    @Override
    public void initialize() {
        System.out.println("Initializing " + getName());
        System.out.println("  Primary: " + primarySymbol + " | SMT: " + smtSymbol);
        System.out.println("  Features: Breaker Blocks, Mitigation, Power of 3, ATR Sizing");
        System.out.println("  Tiers: 3 (1:4 R:R) | 2 (1:2 R:R) | 1 (1:1 R:R)");
    }

    @Override
    public void shutdown() {
        System.out.println("Shutting down " + getName());

        // Print diagnostic summary
        System.out.println("\n" + "=".repeat(60));
        System.out.println("STRATEGY DIAGNOSTIC SUMMARY");
        System.out.println("=".repeat(60));
        System.out.println("Total candles processed: " + candleCount);
        System.out.println("Signals generated: " + signalsGenerated);
        System.out.println("\nRejection breakdown:");
        System.out.println("  Outside killzone:     " + outsideKillzone + " (" + pct(outsideKillzone) + ")");
        System.out.println("  Not trading day:      " + notTradingDay + " (" + pct(notTradingDay) + ")");
        System.out.println("  Wrong phase:          " + wrongPhase + " (" + pct(wrongPhase) + ")");
        System.out.println("  Volatility blocked:   " + volatilityBlocked + " (" + pct(volatilityBlocked) + ")");
        System.out.println("  Neutral bias:         " + neutralBias + " (" + pct(neutralBias) + ")");
        System.out.println("  No recent sweep:      " + noSweep + " (" + pct(noSweep) + ")");
        System.out.println("  Sweep direction mismatch: " + sweepMismatch + " (" + pct(sweepMismatch) + ")");
        System.out.println("  No entry zone found:  " + noEntryZone + " (" + pct(noEntryZone) + ")");
        System.out.println("=".repeat(60));
    }

    private String pct(int count) {
        if (candleCount == 0) return "0%";
        return String.format("%.1f%%", (count * 100.0) / candleCount);
    }

    // Getters for external access
    public TradeTier getCurrentTier() { return currentTier; }
    public ATRCalculator getAtrCalculator() { return atrCalculator; }
    public KillzoneClock getKillzoneClock() { return killzoneClock; }
    public CorrelationTracker getCorrelationTracker() { return correlationTracker; }
    public SessionManager createSessionManager() { return new SessionManager(); }
}
