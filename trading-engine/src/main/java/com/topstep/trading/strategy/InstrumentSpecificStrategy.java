package com.topstep.trading.strategy;

import com.topstep.trading.chartstate.ChartStateIntegration;
import com.topstep.trading.chartstate.ChartStateManager;
import com.topstep.trading.chartstate.LiquidityRaid;
import com.topstep.trading.chartstate.RaidDirection;
import com.topstep.trading.domain.Candle;
import com.topstep.trading.domain.OrderSide;
import com.topstep.trading.event.EventBus;
import com.topstep.trading.event.StrategySignalEvent;

import java.time.Instant;
import java.util.Optional;

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

    // Silver Bullet components
    private final SilverBulletClock silverBulletClock;
    private final MarketStructureShiftDetector mssDetector;
    private final BarAggregationManager barManager;

    // Multi-Timeframe Analysis (15m/30m bias, 5m/15m FVGs, 3m/5m OBs)
    private final MultiTimeframeAnalyzer mtfAnalyzer;

    // Enhanced Liquidity Raid Detection System
    private final ChartStateIntegration chartStateIntegration;

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

    // Silver Bullet state tracking
    private boolean sbHasLiquidityRaid = false;
    private boolean sbRaidIsBullish = false;
    private double sbRaidLevel = 0;
    private int sbCandlesSinceRaid = 0;
    private MarketStructureShiftDetector.MSS lastSBMss = null;

    // Active raid from ChartStateManager (for enhanced quality scoring)
    private LiquidityRaid currentActiveRaid = null;

    // Current trade symbol (may be micro version based on raid quality)
    private String currentTradeSymbol = null;
    private boolean usingMicroContract = false;

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

        // Initialize Silver Bullet components
        this.silverBulletClock = new SilverBulletClock();
        this.mssDetector = new MarketStructureShiftDetector(50, 2);
        this.barManager = new BarAggregationManager(profile.getSymbol(), 100);

        // Initialize Multi-Timeframe Analyzer
        this.mtfAnalyzer = new MultiTimeframeAnalyzer(profile.getSymbol(), barManager);

        // Initialize Enhanced Liquidity Raid Detection System
        this.chartStateIntegration = new ChartStateIntegration(
                profile.getSymbol(), profile.getSmtSymbol());
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

        // Silver Bullet: Update MSS detector and bar aggregation
        MarketStructureShiftDetector.MSS mss = mssDetector.update(candle);
        if (mss != null) {
            lastSBMss = mss;
        }

        // Process 1m candle into higher timeframes and update MTF analyzer
        java.util.Map<BarAggregationManager.Timeframe, Candle> completedCandles = barManager.processCandle(candle);
        mtfAnalyzer.update(completedCandles);

        // Silver Bullet: Track liquidity raids
        updateSilverBulletRaidTracking(candle);

        // Enhanced Liquidity Raid Detection: Feed candle to ChartStateManager
        // This tracks PDH/PDL, session levels, equal H/L, and detects quality-scored raids
        boolean hasSmt = correlationTracker.hasSMTDivergence(profile.getSymbol(), profile.getSmtSymbol(), 10);
        MarketBias bias = structureDetector.getBias();
        // Use explicit Boolean objects to avoid autoboxing NPE with null in ternary
        Boolean htfBullish;
        if (bias == MarketBias.BULLISH) {
            htfBullish = Boolean.TRUE;
        } else if (bias == MarketBias.BEARISH) {
            htfBullish = Boolean.FALSE;
        } else {
            htfBullish = null;  // NEUTRAL
        }
        chartStateIntegration.processCandle(candle, hasSmt, htfBullish);
    }

    /**
     * Track liquidity raids for Silver Bullet setups.
     */
    private void updateSilverBulletRaidTracking(Candle candle) {
        // Track time since raid
        if (sbHasLiquidityRaid) {
            sbCandlesSinceRaid++;
            // Reset if too long since raid (15 candles = 15 minutes on 1m chart)
            if (sbCandlesSinceRaid > 15) {
                resetSilverBulletState();
            }
        }

        // Check for new liquidity raid
        LiquiditySweep sweep = liquidityDetector.getLastSweep();
        if (sweep != null && liquidityDetector.hasRecentSweep(3)) {
            sbHasLiquidityRaid = true;
            sbRaidIsBullish = sweep.isBullish();
            sbRaidLevel = sweep.getSweptLevel();
            sbCandlesSinceRaid = 0;
        }
    }

    /**
     * Reset Silver Bullet state.
     */
    private void resetSilverBulletState() {
        sbHasLiquidityRaid = false;
        sbCandlesSinceRaid = 0;
        sbRaidLevel = 0;
        lastSBMss = null;
    }

    /**
     * Check for Silver Bullet setup during SB time windows.
     *
     * ICT Silver Bullet Requirements:
     * 1. Must be within an SB time window (3-4am, 10-11am, or 2-3pm ET)
     * 2. Price must raid a known liquidity level
     * 3. MSS (Market Structure Shift) must occur in direction of next draw
     * 4. Displacement creates entry FVG associated with MSS leg
     * 5. Entry on FVG retrace with defined invalidation
     *
     * @return true if a valid Silver Bullet setup is detected
     */
    private boolean checkSilverBulletSetup(Candle candle, Instant now, boolean shouldLog) {
        // 1. Must be in a Silver Bullet time window
        SilverBulletClock.SilverBulletWindow sbWindow = silverBulletClock.getCurrentWindow(now);
        if (!sbWindow.isActive()) {
            return false;
        }

        // 2. Check if this is an optimal symbol for the current SB window
        boolean isOptimal = silverBulletClock.isOptimalSymbol(profile.getSymbol(), now);

        // 3. Must have enough time remaining for a trade (at least 15 minutes)
        if (!silverBulletClock.hasEnoughTimeForTrade(now)) {
            if (shouldLog) {
                System.out.println("[" + profile.getSymbol() + "] Silver Bullet: Time running low in " + sbWindow.getName());
            }
            return false;
        }

        // 4. Must have a liquidity raid (sweep) within the window
        if (!sbHasLiquidityRaid) {
            if (shouldLog && candleCount % 30 == 0) {
                System.out.println("[" + profile.getSymbol() + "] Silver Bullet (" + sbWindow.getName() + "): Waiting for liquidity raid");
            }
            return false;
        }

        // 5. Must have MSS (Market Structure Shift) after the raid
        if (lastSBMss == null) {
            if (shouldLog) {
                System.out.println("[" + profile.getSymbol() + "] Silver Bullet: Raid detected, waiting for MSS");
            }
            return false;
        }

        // MSS must be in the direction opposite to the raid (reversal)
        // If raid was bullish (buy-side raid = swept highs), MSS should be bearish (selling)
        // If raid was bearish (sell-side raid = swept lows), MSS should be bullish (buying)
        boolean mssConfirmsReversal = (sbRaidIsBullish && !lastSBMss.isBullish) ||
                                       (!sbRaidIsBullish && lastSBMss.isBullish);

        if (!mssConfirmsReversal) {
            if (shouldLog) {
                System.out.println("[" + profile.getSymbol() + "] Silver Bullet: MSS direction doesn't confirm reversal");
            }
            return false;
        }

        // 6. Look for FVG created during MSS displacement for entry
        boolean isBullish = lastSBMss.isBullish;
        FairValueGap sbFvg = fvgDetector.findNearestUnfilledFvg(candle.getClose(), isBullish, maxPriceDistance);

        if (sbFvg == null) {
            // Also check for any recent FVG
            sbFvg = fvgDetector.findNearestFvg(candle.getClose(), isBullish, maxPriceDistance);
        }

        if (sbFvg == null) {
            if (shouldLog) {
                System.out.println("[" + profile.getSymbol() + "] Silver Bullet: MSS confirmed, waiting for FVG retrace");
            }
            return false;
        }

        // 7. Price must be retracing INTO the FVG (not just near it)
        boolean priceInFvg = candle.getClose() >= sbFvg.getBottom() && candle.getClose() <= sbFvg.getTop();
        boolean priceApproachingFvg = isBullish ?
            (candle.getClose() <= sbFvg.getTop() && candle.getClose() >= sbFvg.getBottom() - (sbFvg.getTop() - sbFvg.getBottom()) * 0.5) :
            (candle.getClose() >= sbFvg.getBottom() && candle.getClose() <= sbFvg.getTop() + (sbFvg.getTop() - sbFvg.getBottom()) * 0.5);

        if (!priceInFvg && !priceApproachingFvg) {
            if (shouldLog) {
                System.out.println("[" + profile.getSymbol() + "] Silver Bullet: Waiting for price to retrace to FVG");
            }
            return false;
        }

        // ★★★ SILVER BULLET SETUP CONFIRMED ★★★
        currentFvg = sbFvg;
        hasDisplacement = true;  // MSS implies displacement

        // Grade the Silver Bullet tier based on confluences
        currentTier = gradeSilverBulletTier(candle, sbWindow, isOptimal);

        // Print Silver Bullet signal
        printSilverBulletSignal(candle, sbWindow, isOptimal);

        // Reset SB state after generating signal
        resetSilverBulletState();

        return true;
    }

    /**
     * Grade Silver Bullet setup tier based on confluences + HTF analysis.
     */
    private TradeTier gradeSilverBulletTier(Candle candle, SilverBulletClock.SilverBulletWindow window, boolean isOptimalSymbol) {
        int tierScore = 0;
        boolean isBullish = lastSBMss != null && lastSBMss.isBullish;

        // Base score: Silver Bullet itself is a premium methodology
        tierScore += 2;

        // Optimal symbol for the window (+1)
        if (isOptimalSymbol) {
            tierScore += 1;
        }

        // MSS strength bonus (+1 if strong displacement)
        if (lastSBMss != null && lastSBMss.strength > 1.5) {
            tierScore += 1;
        }

        // ═══════════════════════════════════════════════════════════════════════
        // HTF ANALYSIS FOR SILVER BULLET (major scoring factors)
        // ═══════════════════════════════════════════════════════════════════════

        // 30m + 15m bias alignment (+3 - major factor)
        if (mtfAnalyzer.htfBiasAligns(isBullish)) {
            tierScore += 3;
        } else {
            // 15m bias alignment (+1)
            MarketBias htfBias15m = mtfAnalyzer.getHtfBias15m();
            if ((isBullish && htfBias15m == MarketBias.BULLISH) ||
                (!isBullish && htfBias15m == MarketBias.BEARISH)) {
                tierScore += 1;
            }
        }

        // 15m FVG present (+2 - premium entry zone)
        FairValueGap htfFvg15m = mtfAnalyzer.findHtfUnfilledFvg15m(candle.getClose(), isBullish, maxPriceDistance);
        if (htfFvg15m != null) {
            tierScore += 2;
            currentFvg = htfFvg15m;  // Use HTF FVG for entry
        } else if (currentFvg != null && !currentFvg.isFilled()) {
            // 1m unfilled FVG (+1)
            tierScore += 1;
        }

        // 5m FVG present (+1)
        FairValueGap htfFvg5m = mtfAnalyzer.findUnfilledFvg5m(candle.getClose(), isBullish, maxPriceDistance);
        if (htfFvg5m != null) {
            tierScore += 1;
            if (currentFvg == null) currentFvg = htfFvg5m;
        }

        // 5m Order Block confluence (+2)
        OrderBlock htfOb5m = mtfAnalyzer.findOb5m(candle.getClose(), isBullish, maxPriceDistance);
        if (htfOb5m != null) {
            currentOrderBlock = htfOb5m;
            tierScore += 2;
        } else {
            // 1m Order Block (+1)
            OrderBlock ob = orderBlockDetector.findNearestValidOb(candle.getClose(), isBullish, maxPriceDistance);
            if (ob != null) {
                currentOrderBlock = ob;
                tierScore += 1;
            }
        }

        // 5m Breaker Block confluence (+2)
        BreakerBlock htfBreaker5m = mtfAnalyzer.findBreaker5m(candle.getClose(), isBullish, maxPriceDistance);
        if (htfBreaker5m != null) {
            tierScore += 2;
        } else {
            // 1m Breaker (+1)
            BreakerBlock breaker = breakerBlockDetector.findNearestBreaker(candle.getClose(), isBullish, maxPriceDistance);
            if (breaker != null) {
                currentBreaker = breaker;
                tierScore += 1;
            }
        }

        // SMT divergence (+1)
        boolean hasSmt = correlationTracker.hasSMTDivergence(profile.getSymbol(), profile.getSmtSymbol(), 10);
        if (hasSmt) {
            tierScore += 1;
        }

        // Multi-TF displacement confirmation (+1)
        if (mtfAnalyzer.hasConfirmedDisplacement(isBullish)) {
            tierScore += 1;
        }

        // ═══════════════════════════════════════════════════════════════════════
        // TIER DETERMINATION (higher thresholds due to more scoring factors)
        // ═══════════════════════════════════════════════════════════════════════
        // Max possible: 2(base) + 1(optimal) + 1(mss) + 3(30m+15m) + 2(15mFVG) + 1(5mFVG) + 2(5mOB) + 2(5mBreaker) + 1(SMT) + 1(MTFDisp) = 16
        if (tierScore >= 10) {
            return TradeTier.TIER_3;  // Premium Silver Bullet (HTF confirmed)
        } else if (tierScore >= 6) {
            return TradeTier.TIER_2;  // Standard Silver Bullet
        } else {
            return TradeTier.TIER_1;  // Basic Silver Bullet
        }
    }

    /**
     * Print Silver Bullet signal details with HTF confirmation.
     */
    private void printSilverBulletSignal(Candle candle, SilverBulletClock.SilverBulletWindow window, boolean isOptimalSymbol) {
        boolean isBullish = lastSBMss != null && lastSBMss.isBullish;
        String tierStars = currentTier == TradeTier.TIER_3 ? "★★★" :
                          (currentTier == TradeTier.TIER_2 ? "★★" : "★");
        String tierLabel = currentTier == TradeTier.TIER_3 ? "PREMIUM" :
                          (currentTier == TradeTier.TIER_2 ? "STANDARD" : "BASIC");

        System.out.println("\n[" + profile.getSymbol() + "] " + tierStars + " SILVER BULLET " + tierLabel + " " + tierStars);
        System.out.println("[" + profile.getSymbol() + "] Window: " + window.getName() +
                          " | Remaining: " + silverBulletClock.getMinutesRemaining(candle.getTimestamp()) + " min");
        System.out.println("[" + profile.getSymbol() + "] Optimal Symbol: " + (isOptimalSymbol ? "✓ YES" : "~ no (still valid)"));
        System.out.println("[" + profile.getSymbol() + "] Raid: " + (sbRaidIsBullish ? "Buy-side (swept highs)" : "Sell-side (swept lows)") +
                          " @ " + String.format("%.2f", sbRaidLevel));
        System.out.println("[" + profile.getSymbol() + "] MSS: " + (lastSBMss.isBullish ? "BULLISH" : "BEARISH") +
                          " | Strength: " + String.format("%.2f", lastSBMss.strength));
        System.out.println("[" + profile.getSymbol() + "] Entry FVG: " + String.format("%.2f - %.2f", currentFvg.getBottom(), currentFvg.getTop()));

        // HTF Confirmation
        MarketBias htf30m = mtfAnalyzer.getHtfBias30m();
        MarketBias htf15m = mtfAnalyzer.getHtfBias15m();
        boolean htfAligned = mtfAnalyzer.htfBiasAligns(isBullish);
        System.out.println("[" + profile.getSymbol() + "] HTF: 30m=" + htf30m + " | 15m=" + htf15m +
                          " | Aligned: " + (htfAligned ? "✓ YES" : "~"));

        StringBuilder confluences = new StringBuilder();
        confluences.append("[" + profile.getSymbol() + "] Confluences: MSS✓, FVG✓");
        if (currentBreaker != null) confluences.append(", Breaker✓");
        if (currentOrderBlock != null) confluences.append(", OB✓");
        if (correlationTracker.hasSMTDivergence(profile.getSymbol(), profile.getSmtSymbol(), 10)) {
            confluences.append(", SMT✓");
        }
        if (mtfAnalyzer.hasConfirmedDisplacement(isBullish)) {
            confluences.append(", MTF-Disp✓");
        }
        System.out.println(confluences.toString());

        System.out.println("[" + profile.getSymbol() + "] Direction: " + (lastSBMss.isBullish ? "LONG" : "SHORT") +
                          " | R:R Target: 1:" + currentTier.getRiskRewardRatio());
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

        // PRIORITY: Silver Bullet setup check during SB windows
        Instant now = candle.getTimestamp();
        if (checkSilverBulletSetup(candle, now, shouldLog)) {
            return true;
        }

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
        // TIGHTENED: Sweep must be within 5 candles (was 15) for fresher setups
        boolean hasRecentSweep = liquidityDetector.hasRecentSweep(5);

        boolean isBullish = bias == MarketBias.BULLISH;

        // ═══════════════════════════════════════════════════════════════════════
        // ENHANCED: Check for quality-scored liquidity raids from ChartStateManager
        // ═══════════════════════════════════════════════════════════════════════
        Optional<LiquidityRaid> activeRaid = isBullish ?
                chartStateIntegration.getActiveBullishRaid() :
                chartStateIntegration.getActiveBearishRaid();

        if (activeRaid.isPresent()) {
            currentActiveRaid = activeRaid.get();
            int raidScore = currentActiveRaid.getQualityScore();

            // Log raid detection
            if (shouldLog) {
                System.out.println("[" + profile.getSymbol() + "] ★ RAID DETECTED: " +
                    currentActiveRaid.getDirection().getDisplayName() + " @ " +
                    currentActiveRaid.getTargetLevel().getType().getDisplayName() +
                    " [Score=" + raidScore + " " + currentActiveRaid.getQualityClassification() + "]");
            }

            // If raid is low quality (score < 4), consider skipping
            if (chartStateIntegration.shouldSkipDueToLowQuality(isBullish)) {
                if (shouldLog) {
                    System.out.println("[" + profile.getSymbol() + "] ✗ Raid score too low (" + raidScore + ") - SKIPPING");
                }
                return false;
            }
        } else {
            currentActiveRaid = null;
        }

        // Use original sweep detection if no ChartState raid (backward compat)
        if (!hasRecentSweep && currentActiveRaid == null) {
            if (phase == KillzonePhase.OPENING) {
                return false;
            }
            if (shouldLog) {
                System.out.println("[" + profile.getSymbol() + "] ✓ Session | ✓ Bias: " + bias + " | ✗ No sweep/raid");
            }
            return false;
        }

        LiquiditySweep sweep = liquidityDetector.getLastSweep();
        // If we have an active raid from ChartState, we can proceed even without legacy sweep
        if (sweep == null && currentActiveRaid == null) return false;

        // 6. Ensure sweep matches bias (only check if we have a sweep)
        if (sweep != null) {
            if (bias == MarketBias.BULLISH && !sweep.isBullish()) {
                // Unless we have an active raid that matches
                if (currentActiveRaid == null || !currentActiveRaid.expectsBullish()) return false;
            }
            if (bias == MarketBias.BEARISH && !sweep.isBearish()) {
                if (currentActiveRaid == null || currentActiveRaid.expectsBullish()) return false;
            }
        }

        // 7. Check for additional confluences
        boolean hasSmtDivergence = (sweep != null && sweep.hasSmtDivergence()) ||
                correlationTracker.hasSMTDivergence(profile.getSymbol(), profile.getSmtSymbol(), 10) ||
                (currentActiveRaid != null && currentActiveRaid.hasSmtConfirmation());
        hasDisplacement = displacementDetector.hasRecentDisplacement(10, isBullish);

        // If we have displacement and an active raid, confirm it
        if (hasDisplacement && currentActiveRaid != null) {
            chartStateIntegration.confirmRaidWithDisplacement(isBullish);
        }

        // Power of 3 is especially reliable for Gold
        hasPower3Confirmation = power3Detector.isInDistribution() &&
                power3Detector.confirmsDirection(isBullish);

        // 8. Look for entry zones by tier (STRICT requirements + HTF CONFIRMATION)

        // Find all potential entry structures on 1m (LTF)
        currentBreaker = breakerBlockDetector.findNearestBreaker(candle.getClose(), isBullish, maxPriceDistance);
        FairValueGap ifvg = fvgDetector.findNearestIfvg(candle.getClose(), isBullish);
        currentOrderBlock = orderBlockDetector.findNearestValidOb(candle.getClose(), isBullish, maxPriceDistance);
        FairValueGap unfilledFvg = fvgDetector.findNearestUnfilledFvg(candle.getClose(), isBullish, maxPriceDistance);
        FairValueGap anyFvg = fvgDetector.findNearestFvg(candle.getClose(), isBullish, maxPriceDistance);
        currentMitigationBlock = mitigationBlockDetector.findBestMitigationZone(candle.getClose(), isBullish, maxPriceDistance);

        // ═══════════════════════════════════════════════════════════════════════
        // HTF ANALYSIS (30m/15m/5m/3m)
        // ═══════════════════════════════════════════════════════════════════════
        MarketBias htfBias30m = mtfAnalyzer.getHtfBias30m();
        MarketBias htfBias15m = mtfAnalyzer.getHtfBias15m();
        boolean htfFullAlignment = mtfAnalyzer.htfBiasAligns(isBullish);
        boolean htf15mAligned = (isBullish && htfBias15m == MarketBias.BULLISH) ||
                                (!isBullish && htfBias15m == MarketBias.BEARISH);
        boolean htf15mNotOpposing = (isBullish && htfBias15m != MarketBias.BEARISH) ||
                                    (!isBullish && htfBias15m != MarketBias.BULLISH);

        // Find HTF zones (higher quality than 1m)
        FairValueGap htfFvg15m = mtfAnalyzer.findHtfUnfilledFvg15m(candle.getClose(), isBullish, maxPriceDistance);
        FairValueGap htfFvg5m = mtfAnalyzer.findUnfilledFvg5m(candle.getClose(), isBullish, maxPriceDistance);
        OrderBlock htfOb5m = mtfAnalyzer.findOb5m(candle.getClose(), isBullish, maxPriceDistance);
        BreakerBlock htfBreaker5m = mtfAnalyzer.findBreaker5m(candle.getClose(), isBullish, maxPriceDistance);
        boolean htfDisplacement = mtfAnalyzer.hasConfirmedDisplacement(isBullish);

        // ═══════════════════════════════════════════════════════════════════════
        // TIER 4 (Elite): Must have ALL of these:
        //   ✓ Breaker Block (1m or 5m)
        //   ✓ Power of 3 Confirmation
        //   ✓ SMT Divergence
        //   ✓ Displacement
        //   ✓ HTF: 30m+15m bias aligned
        //   ✓ HTF: 15m FVG OR 5m OB present
        // ═══════════════════════════════════════════════════════════════════════
        boolean hasBreaker = currentBreaker != null || htfBreaker5m != null;
        boolean hasHtfZone = htfFvg15m != null || htfOb5m != null;

        if (hasBreaker && hasPower3Confirmation && hasSmtDivergence && hasDisplacement &&
            htfFullAlignment && hasHtfZone) {
            currentTier = TradeTier.TIER_4;
            if (htfBreaker5m != null) currentBreaker = htfBreaker5m;  // Prefer HTF breaker tracking
            if (htfFvg15m != null) currentFvg = htfFvg15m;
            printTier4Signal(candle, bias, sweep, "Breaker+Power3+SMT+Disp [HTF:30m+15m✓]");
            return true;
        }

        // ═══════════════════════════════════════════════════════════════════════
        // TIER 3 (Premium): One of these combinations:
        //   • Breaker + (SMT OR Displacement OR Power3)
        //   • IFVG + OB + Displacement + Power3
        //   ✓ HTF: 15m bias aligned
        //   ✓ HTF: (15m FVG OR 5m FVG OR 5m OB) present
        // ═══════════════════════════════════════════════════════════════════════
        boolean hasHtfZoneT3 = htfFvg15m != null || htfFvg5m != null || htfOb5m != null;

        if (htf15mAligned && hasHtfZoneT3) {
            if (hasBreaker && (hasSmtDivergence || hasDisplacement || hasPower3Confirmation)) {
                currentTier = TradeTier.TIER_3;
                String htfInfo = htfFvg15m != null ? "15mFVG" : (htfFvg5m != null ? "5mFVG" : "5mOB");
                String combo = "Breaker+" + (hasSmtDivergence ? "SMT" : (hasDisplacement ? "Displacement" : "Power3"));
                printTier3Signal(candle, bias, sweep, combo + " [HTF:" + htfInfo + "✓]", hasSmtDivergence);
                return true;
            }

            if (ifvg != null && currentOrderBlock != null && hasDisplacement && hasPower3Confirmation) {
                currentTier = TradeTier.TIER_3;
                currentFvg = htfFvg15m != null ? htfFvg15m : (htfFvg5m != null ? htfFvg5m : ifvg);
                printTier3Signal(candle, bias, sweep, "IFVG+OB+Disp+Power3 [HTF:15m✓]", hasSmtDivergence);
                return true;
            }
        }

        // ═══════════════════════════════════════════════════════════════════════
        // TIER 2 (Standard): One of these combinations:
        //   • Order Block + Displacement + SMT (all 3 required)
        //   • Unfilled FVG + SMT + Displacement (all 3 required)
        //   • Fresh Mitigation Zone + SMT
        //   ✓ HTF: 15m bias NOT opposing
        //   ✓ HTF: (5m FVG OR 3m/5m OB) present
        // ═══════════════════════════════════════════════════════════════════════
        MultiTimeframeAnalyzer.ObResult htfObResult = mtfAnalyzer.findBestHtfOb(candle.getClose(), isBullish, maxPriceDistance);
        boolean hasHtfZoneT2 = htfFvg5m != null || htfObResult != null;

        if (htf15mNotOpposing && hasHtfZoneT2) {
            boolean obPresent = currentOrderBlock != null || htfOb5m != null;

            if (obPresent && hasDisplacement && hasSmtDivergence) {
                currentTier = TradeTier.TIER_2;
                String htfInfo = htfOb5m != null ? "5mOB" : "5mFVG";
                printTier2Signal(candle, bias, sweep, "OB+Disp+SMT [HTF:" + htfInfo + "✓]", hasSmtDivergence);
                return true;
            }

            FairValueGap fvgToUse = htfFvg5m != null ? htfFvg5m : unfilledFvg;
            if (fvgToUse != null && hasSmtDivergence && (hasDisplacement || htfDisplacement)) {
                currentTier = TradeTier.TIER_2;
                currentFvg = fvgToUse;
                printTier2Signal(candle, bias, sweep, "FVG+SMT+Disp [HTF:5m✓]", hasSmtDivergence);
                return true;
            }

            if (currentMitigationBlock != null && currentMitigationBlock.isFresh() && hasSmtDivergence) {
                currentTier = TradeTier.TIER_2;
                printTier2Signal(candle, bias, sweep, "Mitigation+SMT [HTF:5m✓]", hasSmtDivergence);
                return true;
            }
        }

        // ═══════════════════════════════════════════════════════════════════════
        // TIER 1 DISABLED - MINIMUM TIER 2 REQUIRED (3 confluences + displacement)
        // ═══════════════════════════════════════════════════════════════════════
        // After losing trades, we've tightened requirements:
        // - Tier 1 (2 confluences) is NO LONGER accepted
        // - ALL trades must have displacement (institutional move)
        // - Minimum 3 confluences required (Tier 2+)
        // This drastically reduces trade frequency but improves win rate.
        // ═══════════════════════════════════════════════════════════════════════

        if (!htf15mNotOpposing) {
            if (shouldLog) {
                System.out.println("[" + profile.getSymbol() + "] REJECTED: HTF 15m opposing direction");
            }
            return false;
        }

        boolean hasFvg = anyFvg != null || htfFvg5m != null;
        boolean hasOb = currentOrderBlock != null || htfOb5m != null;
        boolean hasDispAny = hasDisplacement || htfDisplacement;
        FairValueGap fvgToUse = htfFvg5m != null ? htfFvg5m : anyFvg;

        // Count confluences
        int confluenceCount = 0;
        if (hasFvg) confluenceCount++;
        if (hasSmtDivergence) confluenceCount++;
        if (hasDispAny) confluenceCount++;
        if (hasOb) confluenceCount++;

        // TIGHTENED: Require 3+ confluences AND displacement for ANY entry
        if (confluenceCount >= 3 && hasDispAny) {
            // Triple confluence with displacement - promoted to Tier 2
            currentTier = TradeTier.TIER_2;
            currentFvg = fvgToUse;
            String entryType = "";
            if (hasFvg) entryType += "FVG";
            if (hasSmtDivergence) entryType += (entryType.isEmpty() ? "" : "+") + "SMT";
            entryType += (entryType.isEmpty() ? "" : "+") + "Disp";
            if (hasOb) entryType += "+OB";
            printTier2Signal(candle, bias, sweep, entryType + " [PROMOTED]", hasSmtDivergence);
            return true;
        }

        // REJECTED: Less than 3 confluences OR missing displacement
        if (confluenceCount >= 2 && !hasDispAny) {
            System.out.println("[" + profile.getSymbol() + "] REJECTED: Had " + confluenceCount +
                " confluences but NO DISPLACEMENT (required for all entries)");
        } else if (confluenceCount < 3) {
            System.out.println("[" + profile.getSymbol() + "] REJECTED: Only " + confluenceCount +
                " confluences (minimum 3 required, Tier 1 disabled)");
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

    private void printTier4Signal(Candle candle, MarketBias bias, LiquiditySweep sweep,
                                   String entryType) {
        System.out.println("\n[" + profile.getSymbol() + "] ★★★★ TIER 4 CONFLUENCE - ELITE SETUP ★★★★");
        System.out.println("[" + profile.getSymbol() + "] Entry Type: " + entryType);
        printCommonInfo(candle, bias, sweep, true);  // Tier 4 always has SMT
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

    private void printTier1Signal(Candle candle, MarketBias bias, LiquiditySweep sweep, boolean hasSmt, String entryType) {
        System.out.println("\n[" + profile.getSymbol() + "] ★ TIER 1 CONFLUENCE - CONFIRMED SETUP ★");
        System.out.println("[" + profile.getSymbol() + "] Entry Type: " + entryType);
        printCommonInfo(candle, bias, sweep, hasSmt);
    }

    private void printCommonInfo(Candle candle, MarketBias bias, LiquiditySweep sweep, boolean hasSmt) {
        System.out.println("[" + profile.getSymbol() + "] Session: " + killzoneClock.getKillzoneName(candle.getTimestamp()));
        String sweepInfo = sweep != null ? (sweep.isBullish() ? "BULLISH" : "BEARISH") : "N/A";
        System.out.println("[" + profile.getSymbol() + "] Bias: " + bias + " | Sweep: " + sweepInfo);
        System.out.println("[" + profile.getSymbol() + "] SMT: " + (hasSmt ? "✓" : "~") +
                          " | Displacement: " + (hasDisplacement ? "✓" : "~") +
                          " | Power3: " + (hasPower3Confirmation ? "✓" : "~"));

        // Print raid quality info if available
        if (currentActiveRaid != null) {
            int raidScore = currentActiveRaid.getQualityScore();
            System.out.println("[" + profile.getSymbol() + "] ★ Raid Quality: " +
                    raidScore + "/10 (" + currentActiveRaid.getQualityClassification() + ") @ " +
                    currentActiveRaid.getTargetLevel().getType().getDisplayName());

            // Show contract sizing based on quality
            System.out.println("[" + profile.getSymbol() + "] 📦 Contract: " +
                    profile.getContractSizingDescription(raidScore));
        }

        System.out.println("[" + profile.getSymbol() + "] R:R Target: 1:" + currentTier.getRiskRewardRatio() +
                          " (adjusted by tier multiplier: " + profile.getTierMultiplier(currentTier) + ")");
    }

    /**
     * Generate signal with tier-based R:R and instrument-specific sizing.
     * Uses micro contracts for Standard quality (score 4-5) setups.
     */
    private void generateSignal(Candle candle, StrategyContext context) {
        MarketBias bias = structureDetector.getBias();
        LiquiditySweep sweep = liquidityDetector.getLastSweep();

        // Determine raid quality score for symbol/quantity selection
        int raidQualityScore = currentActiveRaid != null ? currentActiveRaid.getQualityScore() : 6;

        // Get appropriate symbol based on quality (micro for 4-5, full for 6+)
        currentTradeSymbol = profile.getSymbolForQuality(raidQualityScore);
        usingMicroContract = profile.shouldUseMicro(raidQualityScore);

        // Calculate position size based on quality and ATR
        double currentAtr = atrCalculator.getCurrentAtr();
        if (usingMicroContract) {
            // Using micro contracts for Standard quality
            recommendedQuantity = profile.getMicroContracts();
            System.out.println("[" + profile.getSymbol() + "] ⚡ MICRO MODE: Score " + raidQualityScore +
                    " → Using " + currentTradeSymbol + " x" + recommendedQuantity);
        } else if (currentAtr < profile.getLowVolatilityThreshold()) {
            recommendedQuantity = profile.getLowVolContracts();
        } else if (currentAtr > profile.getHighVolatilityThreshold()) {
            recommendedQuantity = 1;  // Conservative in high vol
        } else {
            recommendedQuantity = basePositionSize;
        }
        recommendedQuantity = Math.min(recommendedQuantity, maxPositionSize);

        // Check bias alignment with sweep direction (null-safe)
        boolean sweepAlignsBullish = sweep != null && sweep.isBullish();
        boolean sweepAlignsBearish = sweep != null && sweep.isBearish();
        // Also allow if we have an active raid from ChartState without legacy sweep
        boolean raidAlignsBullish = currentActiveRaid != null && currentActiveRaid.getDirection() == RaidDirection.LOW_SWEEP;
        boolean raidAlignsBearish = currentActiveRaid != null && currentActiveRaid.getDirection() == RaidDirection.HIGH_SWEEP;

        if (bias == MarketBias.BULLISH && (sweepAlignsBullish || raidAlignsBullish)) {
            generateBullishSignal(candle, sweep);
        } else if (bias == MarketBias.BEARISH && (sweepAlignsBearish || raidAlignsBearish)) {
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
        // CRITICAL: For LONG, stop MUST be BELOW entry (protect against price falling)
        double stopBuffer = profile.getStopBufferPoints();
        double baseStop = getBaseStopLevel(swingLow, true);

        // BUGFIX: Ensure baseStop is below entry for LONG trades
        // If baseStop (from FVG/OB) is above entry, use swingLow or entry - buffer instead
        if (baseStop >= entry) {
            // Use swingLow as the stop reference since that's where the sweep occurred
            baseStop = Math.min(swingLow, entry - profile.getMinStopDistance());
        }

        double stop = Math.max(baseStop - stopBuffer, entry - profile.getMaxStopDistance());
        stop = Math.min(stop, entry - profile.getMinStopDistance());

        // FINAL VALIDATION: Stop MUST be below entry for LONG
        if (stop >= entry) {
            System.err.println("[" + profile.getSymbol() + "] ❌ Invalid LONG stop calculation: stop=" +
                stop + " >= entry=" + entry + ", using fallback");
            stop = entry - profile.getMinStopDistance();
        }

        // Target: Based on tier R:R with instrument multiplier
        double riskDistance = entry - stop;
        double adjustedRR = currentTier.getRiskRewardRatio() * profile.getTierMultiplier(currentTier);
        double target = entry + (riskDistance * adjustedRR);

        if (riskDistance <= 0) return;

        String reason = buildSignalReason("Bullish", candle);

        // Use micro or full symbol based on raid quality
        String signalSymbol = currentTradeSymbol != null ? currentTradeSymbol : profile.getSymbol();

        StrategySignalEvent signal = new StrategySignalEvent(
                StrategySignalEvent.SignalType.LONG_ENTRY,
                signalSymbol,
                OrderSide.BUY,
                entry,
                stop,
                target,
                reason,
                currentTier,
                recommendedQuantity
        );

        // Log micro/full contract info
        if (usingMicroContract) {
            System.out.println("[" + profile.getSymbol() + "] 📊 LONG Signal using MICRO: " + signalSymbol + " x" + recommendedQuantity);
        }

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
        // CRITICAL FIX: For SHORT, stop MUST be ABOVE entry (protect against price rising)
        double stopBuffer = profile.getStopBufferPoints();
        double baseStop = getBaseStopLevel(swingHigh, false);

        // BUGFIX: Ensure baseStop is above entry for SHORT trades
        // If baseStop (from FVG/OB) is below entry, use swingHigh or entry + buffer instead
        if (baseStop <= entry) {
            // Use swingHigh as the stop reference since that's where the sweep occurred
            baseStop = Math.max(swingHigh, entry + profile.getMinStopDistance());
        }

        double stop = Math.min(baseStop + stopBuffer, entry + profile.getMaxStopDistance());
        stop = Math.max(stop, entry + profile.getMinStopDistance());

        // FINAL VALIDATION: Stop MUST be above entry for SHORT
        if (stop <= entry) {
            System.err.println("[" + profile.getSymbol() + "] ❌ Invalid SHORT stop calculation: stop=" +
                stop + " <= entry=" + entry + ", using fallback");
            stop = entry + profile.getMinStopDistance();
        }

        // Target: Based on tier R:R with instrument multiplier
        double riskDistance = stop - entry;
        double adjustedRR = currentTier.getRiskRewardRatio() * profile.getTierMultiplier(currentTier);
        double target = entry - (riskDistance * adjustedRR);

        if (riskDistance <= 0) return;

        String reason = buildSignalReason("Bearish", candle);

        // Use micro or full symbol based on raid quality
        String signalSymbol = currentTradeSymbol != null ? currentTradeSymbol : profile.getSymbol();

        StrategySignalEvent signal = new StrategySignalEvent(
                StrategySignalEvent.SignalType.SHORT_ENTRY,
                signalSymbol,
                OrderSide.SELL,
                entry,
                stop,
                target,
                reason,
                currentTier,
                recommendedQuantity
        );

        // Log micro/full contract info
        if (usingMicroContract) {
            System.out.println("[" + profile.getSymbol() + "] 📊 SHORT Signal using MICRO: " + signalSymbol + " x" + recommendedQuantity);
        }

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
        System.out.println("  Enhanced Raid Detection: ENABLED (quality scoring active)");
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
    public ChartStateIntegration getChartStateIntegration() { return chartStateIntegration; }
    public LiquidityRaid getCurrentActiveRaid() { return currentActiveRaid; }

    /**
     * Reset signal pending state (called when switching instruments).
     */
    public void resetSignalPending() {
        signalPending = false;
        currentActiveRaid = null;
        currentTradeSymbol = null;
        usingMicroContract = false;
    }

    /**
     * Check if currently using micro contracts.
     */
    public boolean isUsingMicroContract() {
        return usingMicroContract;
    }

    /**
     * Get the current trade symbol (may be micro version).
     */
    public String getCurrentTradeSymbol() {
        return currentTradeSymbol != null ? currentTradeSymbol : profile.getSymbol();
    }
}
