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
            if (shouldLog) {
                System.out.println("[STRATEGY] Not in killzone - waiting");
            }
            return false;
        }

        if (!killzoneClock.isTradingDay(candle.getTimestamp())) {
            return false;
        }

        // Check killzone phase - only trade during PRIME phase
        if (!phase.allowsNewEntries()) {
            if (shouldLog) {
                System.out.println("[STRATEGY] Killzone phase: " + phase + " - no new entries");
            }
            return false;
        }

        // 2. Check volatility - is it tradeable?
        if (!atrCalculator.isTradeable()) {
            if (shouldLog) {
                System.out.println("[STRATEGY] Volatility too extreme - skipping");
            }
            return false;
        }

        // 3. Check higher-timeframe structure bias
        MarketBias bias = structureDetector.getBias();
        if (bias == MarketBias.NEUTRAL) {
            if (shouldLog) {
                System.out.println("[STRATEGY] ✓ In killzone (PRIME) | ✗ Bias NEUTRAL");
            }
            return false;
        }

        // 4. Check for recent liquidity sweep
        boolean hasRecentSweep = liquidityDetector.hasRecentSweep(15);
        if (!hasRecentSweep) {
            // During opening phase, we expect to wait for sweep
            if (phase == KillzonePhase.OPENING) {
                return false;
            }
            if (shouldLog) {
                System.out.println("[STRATEGY] ✓ Killzone | ✓ Bias: " + bias + " | ✗ No sweep");
            }
            return false;
        }

        LiquiditySweep sweep = liquidityDetector.getLastSweep();
        if (sweep == null) return false;

        // 5. Ensure sweep matches bias
        if (bias == MarketBias.BULLISH && !sweep.isBullish()) return false;
        if (bias == MarketBias.BEARISH && !sweep.isBearish()) return false;

        boolean isBullish = bias == MarketBias.BULLISH;

        // 6. Check for additional confluences
        boolean hasSmtDivergence = sweep.hasSmtDivergence() ||
                correlationTracker.hasSMTDivergence(primarySymbol, smtSymbol, 10);
        hasDisplacement = displacementDetector.hasRecentDisplacement(10, isBullish);
        hasPower3Confirmation = power3Detector.isInDistribution() &&
                power3Detector.confirmsDirection(isBullish);

        // 7. Look for entry zones by tier (highest to lowest)

        // TIER 3: Breaker Block OR (IFVG + OB + Displacement + Power3)
        currentBreaker = breakerBlockDetector.findNearestBreaker(candle.getClose(), isBullish, maxPriceDistance);
        FairValueGap ifvg = fvgDetector.findNearestIfvg(candle.getClose(), isBullish);
        currentOrderBlock = orderBlockDetector.findNearestValidOb(candle.getClose(), isBullish, maxPriceDistance);

        if (currentBreaker != null) {
            // Breaker Block found - Tier 3
            currentTier = TradeTier.TIER_3;
            printTier3Signal(candle, bias, sweep, "Breaker Block", hasSmtDivergence);
            return true;
        }

        if (ifvg != null && currentOrderBlock != null && hasDisplacement && hasPower3Confirmation) {
            // Full confluence - Tier 3
            currentTier = TradeTier.TIER_3;
            currentFvg = ifvg;
            printTier3Signal(candle, bias, sweep, "IFVG+OB+Displacement+Power3", hasSmtDivergence);
            return true;
        }

        // TIER 2: OB + Displacement OR Unfilled FVG + SMT
        FairValueGap unfilledFvg = fvgDetector.findNearestUnfilledFvg(candle.getClose(), isBullish, maxPriceDistance);

        if (currentOrderBlock != null && hasDisplacement) {
            currentTier = TradeTier.TIER_2;
            printTier2Signal(candle, bias, sweep, "OB+Displacement", hasSmtDivergence);
            return true;
        }

        if (unfilledFvg != null && hasSmtDivergence) {
            currentTier = TradeTier.TIER_2;
            currentFvg = unfilledFvg;
            printTier2Signal(candle, bias, sweep, "FVG+SMT", hasSmtDivergence);
            return true;
        }

        // Also Tier 2 for mitigation at fresh zone
        currentMitigationBlock = mitigationBlockDetector.findBestMitigationZone(candle.getClose(), isBullish, maxPriceDistance);
        if (currentMitigationBlock != null && currentMitigationBlock.isFresh()) {
            currentTier = TradeTier.TIER_2;
            printTier2Signal(candle, bias, sweep, "Fresh Mitigation", hasSmtDivergence);
            return true;
        }

        // TIER 1: Any FVG OR SMT OR Displacement at OTE
        FairValueGap anyFvg = fvgDetector.findNearestFvg(candle.getClose(), isBullish, maxPriceDistance);

        if (anyFvg != null || hasSmtDivergence || hasDisplacement) {
            currentTier = TradeTier.TIER_1;
            currentFvg = anyFvg;
            printTier1Signal(candle, bias, sweep, hasSmtDivergence);
            return true;
        }

        // No valid confluence
        if (shouldLog) {
            System.out.println("[STRATEGY] ✓ Killzone | ✓ Bias | ✓ Sweep | ✗ No entry zone");
        }
        return false;
    }

    private void printTier3Signal(Candle candle, MarketBias bias, LiquiditySweep sweep,
                                   String entryType, boolean hasSmt) {
        System.out.println("\n[STRATEGY] ★★★ TIER 3 CONFLUENCE - PREMIUM SETUP ★★★");
        System.out.println("[STRATEGY] Entry Type: " + entryType);
        printCommonInfo(candle, bias, sweep, hasSmt);
    }

    private void printTier2Signal(Candle candle, MarketBias bias, LiquiditySweep sweep,
                                   String entryType, boolean hasSmt) {
        System.out.println("\n[STRATEGY] ★★ TIER 2 CONFLUENCE - STANDARD SETUP ★★");
        System.out.println("[STRATEGY] Entry Type: " + entryType);
        printCommonInfo(candle, bias, sweep, hasSmt);
    }

    private void printTier1Signal(Candle candle, MarketBias bias, LiquiditySweep sweep, boolean hasSmt) {
        System.out.println("\n[STRATEGY] ★ TIER 1 CONFLUENCE - SCALP SETUP ★");
        printCommonInfo(candle, bias, sweep, hasSmt);
    }

    private void printCommonInfo(Candle candle, MarketBias bias, LiquiditySweep sweep, boolean hasSmt) {
        System.out.println("[STRATEGY] Killzone: " + killzoneClock.getKillzoneName(candle.getTimestamp()) +
                          " | Phase: " + killzoneClock.getKillzonePhase(candle.getTimestamp()));
        System.out.println("[STRATEGY] Bias: " + bias + " | Sweep: " + (sweep.isBullish() ? "BULLISH" : "BEARISH"));
        System.out.println("[STRATEGY] SMT: " + (hasSmt ? "✓" : "~") +
                          " | Displacement: " + (hasDisplacement ? "✓" : "~") +
                          " | Power3: " + (hasPower3Confirmation ? "✓" : "~"));
        System.out.println("[STRATEGY] Volatility: " + atrCalculator.getVolatilitySummary());
        System.out.println("[STRATEGY] R:R Target: 1:" + currentTier.getRiskRewardRatio());
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
        if (swingHigh == null) return;

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
        if (riskDistance <= 0) return;

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

        eventBus.publish(signal);
    }

    private void generateBearishSignal(Candle candle, LiquiditySweep sweep) {
        Double swingLow = structureDetector.getLastSwingLow();
        if (swingLow == null) return;

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
        if (riskDistance <= 0) return;

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
    }

    // Getters for external access
    public TradeTier getCurrentTier() { return currentTier; }
    public ATRCalculator getAtrCalculator() { return atrCalculator; }
    public KillzoneClock getKillzoneClock() { return killzoneClock; }
    public CorrelationTracker getCorrelationTracker() { return correlationTracker; }
    public SessionManager createSessionManager() { return new SessionManager(); }
}
