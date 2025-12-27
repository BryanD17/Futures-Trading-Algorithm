package com.topstep.trading.strategy;

import com.topstep.trading.domain.Candle;
import com.topstep.trading.domain.OrderSide;
import com.topstep.trading.event.EventBus;
import com.topstep.trading.event.StrategySignalEvent;

/**
 * Instrument-specific ICT strategy that adapts parameters based on instrument characteristics.
 *
 * Each instrument has unique behavior patterns based on:
 * - Market participants and liquidity
 * - Optimal trading sessions (killzones)
 * - Volatility characteristics
 * - OTE (Optimal Trade Entry) depth
 * - ICT concept reliability (FVG fill rate, OB respect rate, etc.)
 *
 * This strategy wraps the core ICT logic and customizes it per instrument.
 */
public class InstrumentSpecificStrategy implements TradingStrategy {

    private final InstrumentProfile profile;
    private final EventBus eventBus;

    // Core detectors (shared logic, instrument-specific parameters)
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

    // Configuration from profile
    private final double oteLow;
    private final double oteHigh;
    private final int basePositionSize;
    private final int maxPositionSize;
    private final double maxPriceDistance;

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

    public InstrumentSpecificStrategy(InstrumentProfile profile, EventBus eventBus,
                                       CorrelationTracker sharedCorrelationTracker) {
        this.profile = profile;
        this.eventBus = eventBus;

        // Use instrument-specific OTE zones
        this.oteLow = profile.getOteLow();
        this.oteHigh = profile.getOteHigh();
        this.basePositionSize = profile.getBaseContracts();
        this.maxPositionSize = profile.getMaxContracts();
        this.maxPriceDistance = profile.getMaxStopDistance() * 3;  // Entry zone max distance

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
        this.atrCalculator = new ATRCalculator(14);

        // Share correlation tracker across instruments for SMT divergence
        this.correlationTracker = sharedCorrelationTracker != null
            ? sharedCorrelationTracker
            : new CorrelationTracker(50);
    }

    /**
     * Create strategy with a new correlation tracker.
     */
    public InstrumentSpecificStrategy(InstrumentProfile profile, EventBus eventBus) {
        this(profile, eventBus, null);
    }

    @Override
    public void onCandle(Candle candle, StrategyContext context) {
        // Handle primary symbol candles
        if (candle.getSymbol().equals(profile.getSymbol())) {
            handlePrimaryCandle(candle, context);
        }
        // Handle SMT symbol candles
        else if (candle.getSymbol().equals(profile.getSmtSymbol())) {
            handleSmtCandle(candle);
        }
        // Always update correlation tracker for any symbol
        correlationTracker.update(candle);
    }

    private void handlePrimaryCandle(Candle candle, StrategyContext context) {
        candleCount++;

        // Reset signalPending if timeout exceeded
        if (signalPending && (System.currentTimeMillis() - lastSignalTime) > SIGNAL_TIMEOUT_MS) {
            System.out.println("[" + profile.getSymbol() + "] Signal timeout - resetting pending flag");
            signalPending = false;
        }

        // Update ALL detectors
        updateDetectors(candle);

        // Don't trade if we already have a position
        if (context.hasPosition(profile.getSymbol())) {
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
    }

    /**
     * Check all confluences and determine trade tier.
     * Adjusted based on instrument-specific reliability rates.
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

        // 1. Check if this instrument prefers the current killzone/session
        String killzoneName = killzoneClock.getKillzoneName(candle.getTimestamp());
        boolean isPreferredSession = isPreferredSession(killzoneName);

        boolean inKillzone = killzoneClock.isInKillzone(candle.getTimestamp());
        KillzonePhase phase = killzoneClock.getKillzonePhase(candle.getTimestamp());

        // For instruments with specific session preferences, allow trading even outside standard killzones
        if (!inKillzone && !isPreferredSession) {
            if (shouldLog) {
                System.out.println("[" + profile.getSymbol() + "] Not in preferred session - waiting");
            }
            return false;
        }

        if (!killzoneClock.isTradingDay(candle.getTimestamp())) {
            return false;
        }

        // Check killzone phase (if in a standard killzone)
        if (inKillzone && !phase.allowsNewEntries()) {
            if (shouldLog) {
                System.out.println("[" + profile.getSymbol() + "] Killzone phase: " + phase + " - no new entries");
            }
            return false;
        }

        // 2. Check volatility - use instrument-specific thresholds
        double currentAtr = atrCalculator.getCurrentAtr();
        if (currentAtr > profile.getExtremeVolatilityThreshold()) {
            if (shouldLog) {
                System.out.println("[" + profile.getSymbol() + "] Volatility too extreme - skipping");
            }
            return false;
        }

        // 3. Check times to avoid (e.g., news releases for CL)
        if (profile.shouldAvoidTime(candle.getTimestamp().atZone(java.time.ZoneId.of("America/New_York")).toLocalTime())) {
            if (shouldLog) {
                System.out.println("[" + profile.getSymbol() + "] Avoiding scheduled time (news/data release)");
            }
            return false;
        }

        // 4. Check higher-timeframe structure bias
        MarketBias bias = structureDetector.getBias();
        if (bias == MarketBias.NEUTRAL) {
            if (shouldLog) {
                System.out.println("[" + profile.getSymbol() + "] ✓ In session | ✗ Bias NEUTRAL");
            }
            return false;
        }

        // 5. Check for recent liquidity sweep
        boolean hasRecentSweep = liquidityDetector.hasRecentSweep(15);
        if (!hasRecentSweep) {
            if (phase == KillzonePhase.OPENING) {
                return false;
            }
            if (shouldLog) {
                System.out.println("[" + profile.getSymbol() + "] ✓ Session | ✓ Bias: " + bias + " | ✗ No sweep");
            }
            return false;
        }

        LiquiditySweep sweep = liquidityDetector.getLastSweep();
        if (sweep == null) return false;

        // 6. Ensure sweep matches bias
        if (bias == MarketBias.BULLISH && !sweep.isBullish()) return false;
        if (bias == MarketBias.BEARISH && !sweep.isBearish()) return false;

        boolean isBullish = bias == MarketBias.BULLISH;

        // 7. Check for additional confluences
        boolean hasSmtDivergence = sweep.hasSmtDivergence() ||
                correlationTracker.hasSMTDivergence(profile.getSymbol(), profile.getSmtSymbol(), 10);
        hasDisplacement = displacementDetector.hasRecentDisplacement(10, isBullish);

        // Power of 3 is especially reliable for Gold
        hasPower3Confirmation = power3Detector.isInDistribution() &&
                power3Detector.confirmsDirection(isBullish);

        // 8. Look for entry zones by tier (adjusted by instrument reliability)

        // TIER 3: Breaker Block (if reliable for this instrument) OR full confluence
        currentBreaker = breakerBlockDetector.findNearestBreaker(candle.getClose(), isBullish, maxPriceDistance);
        FairValueGap ifvg = fvgDetector.findNearestIfvg(candle.getClose(), isBullish);
        currentOrderBlock = orderBlockDetector.findNearestValidOb(candle.getClose(), isBullish, maxPriceDistance);

        // Breaker reliability threshold based on instrument profile
        boolean breakerIsReliable = profile.getBreakerReliability() >= 0.80;

        if (currentBreaker != null && breakerIsReliable) {
            currentTier = TradeTier.TIER_3;
            printTier3Signal(candle, bias, sweep, "Breaker Block", hasSmtDivergence);
            return true;
        }

        // Full confluence + Power3 (especially strong for Gold)
        boolean power3IsReliable = profile.getPower3Reliability() >= 0.75;
        if (ifvg != null && currentOrderBlock != null && hasDisplacement &&
            hasPower3Confirmation && power3IsReliable) {
            currentTier = TradeTier.TIER_3;
            currentFvg = ifvg;
            printTier3Signal(candle, bias, sweep, "IFVG+OB+Displacement+Power3", hasSmtDivergence);
            return true;
        }

        // TIER 2: OB + Displacement OR Unfilled FVG + SMT
        FairValueGap unfilledFvg = fvgDetector.findNearestUnfilledFvg(candle.getClose(), isBullish, maxPriceDistance);

        // OB reliability based on profile
        boolean obIsReliable = profile.getObRespectRate() >= 0.70;
        if (currentOrderBlock != null && hasDisplacement && obIsReliable) {
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

        // Mitigation zone for Tier 2
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

        if (shouldLog) {
            System.out.println("[" + profile.getSymbol() + "] ✓ Session | ✓ Bias | ✓ Sweep | ✗ No entry zone");
        }
        return false;
    }

    /**
     * Check if the current session is preferred for this instrument.
     */
    private boolean isPreferredSession(String killzoneName) {
        // Check primary killzones
        if (profile.getPrimaryKillzones() != null) {
            for (String kz : profile.getPrimaryKillzones()) {
                if (killzoneName.toUpperCase().contains(kz.toUpperCase())) {
                    return true;
                }
            }
        }
        // Check secondary killzones
        if (profile.getSecondaryKillzones() != null) {
            for (String kz : profile.getSecondaryKillzones()) {
                if (killzoneName.toUpperCase().contains(kz.toUpperCase())) {
                    return true;
                }
            }
        }
        return false;
    }

    private void printTier3Signal(Candle candle, MarketBias bias, LiquiditySweep sweep,
                                   String entryType, boolean hasSmt) {
        System.out.println("\n[" + profile.getSymbol() + "] ★★★ TIER 3 CONFLUENCE - PREMIUM SETUP ★★★");
        System.out.println("[" + profile.getSymbol() + "] Entry Type: " + entryType);
        printCommonInfo(candle, bias, sweep, hasSmt);
    }

    private void printTier2Signal(Candle candle, MarketBias bias, LiquiditySweep sweep,
                                   String entryType, boolean hasSmt) {
        System.out.println("\n[" + profile.getSymbol() + "] ★★ TIER 2 CONFLUENCE - STANDARD SETUP ★★");
        System.out.println("[" + profile.getSymbol() + "] Entry Type: " + entryType);
        printCommonInfo(candle, bias, sweep, hasSmt);
    }

    private void printTier1Signal(Candle candle, MarketBias bias, LiquiditySweep sweep, boolean hasSmt) {
        System.out.println("\n[" + profile.getSymbol() + "] ★ TIER 1 CONFLUENCE - SCALP SETUP ★");
        printCommonInfo(candle, bias, sweep, hasSmt);
    }

    private void printCommonInfo(Candle candle, MarketBias bias, LiquiditySweep sweep, boolean hasSmt) {
        System.out.println("[" + profile.getSymbol() + "] Session: " + killzoneClock.getKillzoneName(candle.getTimestamp()));
        System.out.println("[" + profile.getSymbol() + "] Bias: " + bias + " | Sweep: " + (sweep.isBullish() ? "BULLISH" : "BEARISH"));
        System.out.println("[" + profile.getSymbol() + "] SMT: " + (hasSmt ? "✓" : "~") +
                          " | Displacement: " + (hasDisplacement ? "✓" : "~") +
                          " | Power3: " + (hasPower3Confirmation ? "✓" : "~"));
        System.out.println("[" + profile.getSymbol() + "] R:R Target: 1:" + currentTier.getRiskRewardRatio() +
                          " (adjusted by tier multiplier: " + profile.getTierMultiplier(currentTier) + ")");
    }

    /**
     * Generate signal with tier-based R:R and instrument-specific sizing.
     */
    private void generateSignal(Candle candle, StrategyContext context) {
        MarketBias bias = structureDetector.getBias();
        LiquiditySweep sweep = liquidityDetector.getLastSweep();

        // Calculate position size based on ATR and instrument limits
        double currentAtr = atrCalculator.getCurrentAtr();
        if (currentAtr < profile.getLowVolatilityThreshold()) {
            recommendedQuantity = profile.getLowVolContracts();
        } else if (currentAtr > profile.getHighVolatilityThreshold()) {
            recommendedQuantity = 1;  // Conservative in high vol
        } else {
            recommendedQuantity = basePositionSize;
        }
        recommendedQuantity = Math.min(recommendedQuantity, maxPositionSize);

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

        // Entry: Based on instrument-specific OTE zone
        double entry = calculateEntry(candle.getClose(), swingLow, range, true);

        // Stop: Below entry zone with instrument-specific buffer
        double stopBuffer = profile.getStopBufferPoints();
        double baseStop = getBaseStopLevel(swingLow, true);
        double stop = Math.max(baseStop - stopBuffer, entry - profile.getMaxStopDistance());
        stop = Math.min(stop, entry - profile.getMinStopDistance());

        // Target: Based on tier R:R with instrument multiplier
        double riskDistance = entry - stop;
        double adjustedRR = currentTier.getRiskRewardRatio() * profile.getTierMultiplier(currentTier);
        double target = entry + (riskDistance * adjustedRR);

        if (riskDistance <= 0) return;

        String reason = buildSignalReason("Bullish", candle);

        StrategySignalEvent signal = new StrategySignalEvent(
                StrategySignalEvent.SignalType.LONG_ENTRY,
                profile.getSymbol(),
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

        // Entry: Based on instrument-specific OTE zone
        double entry = calculateEntry(candle.getClose(), swingHigh, range, false);

        // Stop: Above entry zone with instrument-specific buffer
        double stopBuffer = profile.getStopBufferPoints();
        double baseStop = getBaseStopLevel(swingHigh, false);
        double stop = Math.min(baseStop + stopBuffer, entry + profile.getMaxStopDistance());
        stop = Math.max(stop, entry + profile.getMinStopDistance());

        // Target: Based on tier R:R with instrument multiplier
        double riskDistance = stop - entry;
        double adjustedRR = currentTier.getRiskRewardRatio() * profile.getTierMultiplier(currentTier);
        double target = entry - (riskDistance * adjustedRR);

        if (riskDistance <= 0) return;

        String reason = buildSignalReason("Bearish", candle);

        StrategySignalEvent signal = new StrategySignalEvent(
                StrategySignalEvent.SignalType.SHORT_ENTRY,
                profile.getSymbol(),
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

        // Default to instrument-specific OTE zone
        if (bullish) {
            double entryLow = swingLevel + (range * oteLow);
            double entryHigh = swingLevel + (range * oteHigh);
            return (entryLow + entryHigh) / 2.0;
        } else {
            double entryHigh = swingLevel - (range * oteLow);
            double entryLow = swingLevel - (range * oteHigh);
            return (entryLow + entryHigh) / 2.0;
        }
    }

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

    private String buildSignalReason(String direction, Candle candle) {
        StringBuilder reason = new StringBuilder();
        reason.append(profile.getSymbol()).append(" ").append(direction).append(": ").append(currentTier);

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
        return "ICT Strategy for " + profile.getSymbol() + " (" + profile.getName() + ")";
    }

    @Override
    public void initialize() {
        System.out.println("Initializing " + getName());
        System.out.println("  Symbol: " + profile.getSymbol() + " | SMT: " + profile.getSmtSymbol());
        System.out.println("  OTE Zone: " + oteLow + " - " + oteHigh);
        System.out.println("  Primary Killzones: " + profile.getPrimaryKillzones());
        System.out.println("  ICT Reliability: Breaker=" + profile.getBreakerReliability() +
                          ", OB=" + profile.getObRespectRate() +
                          ", Power3=" + profile.getPower3Reliability());
        System.out.println("  Position Sizing: Base=" + basePositionSize + ", Max=" + maxPositionSize);
    }

    @Override
    public void shutdown() {
        System.out.println("Shutting down " + getName());
    }

    // Getters
    public InstrumentProfile getProfile() { return profile; }
    public TradeTier getCurrentTier() { return currentTier; }
    public ATRCalculator getAtrCalculator() { return atrCalculator; }
    public KillzoneClock getKillzoneClock() { return killzoneClock; }
    public CorrelationTracker getCorrelationTracker() { return correlationTracker; }

    /**
     * Reset signal pending state (called when switching instruments).
     */
    public void resetSignalPending() {
        signalPending = false;
    }
}
