package com.topstep.trading.strategy;

import com.topstep.trading.domain.Candle;
import com.topstep.trading.domain.OrderSide;
import com.topstep.trading.event.EventBus;
import com.topstep.trading.event.StrategySignalEvent;
import com.topstep.trading.strategy.BarAggregationManager.Timeframe;
import com.topstep.trading.strategy.HtfTrendAnalyzer.HtfTrendState;
import com.topstep.trading.chartstate.RaidDetector;
import com.topstep.trading.chartstate.LiquidityRaid;
import com.topstep.trading.chartstate.LevelEngine;
import com.topstep.trading.chartstate.EqualLevelDetector;
import com.topstep.trading.chartstate.CandleSeries;
import com.topstep.trading.chartstate.ChartStateQueryAPI;
import com.topstep.trading.news.MacroNewsManager;
import com.topstep.trading.news.model.GatingAction;
import com.topstep.trading.news.model.MacroAlignment;
import com.topstep.trading.news.model.TradeGatingDecision;
import com.topstep.trading.validation.MandatoryConfluenceValidator;
import com.topstep.trading.validation.ValidationResult;

import java.util.Map;
import java.util.Optional;

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
 * 8. MULTI-TIMEFRAME ANALYSIS - HTF bias alignment (30m + 15m)
 *
 * TIER SYSTEM (Higher tier = Higher quality = Better R:R):
 * - Tier 4: Strong HTF Trend + Breaker Block + Power3 + SMT + Displacement + 5m Zone + Liquidity Target = 1:5 R:R
 * - Tier 3: HTF Trend Aligned + 5m Zone Confluence + Displacement + (SMT OR Power3) = 1:4 R:R
 * - Tier 2: HTF Not Opposing + OB/FVG + Displacement + SMT = 1:3 R:R
 * - Tier 1: DISABLED - Requires minimum Tier 2
 *
 * 3-LAYER CASCADE DECISION FRAMEWORK (NEW):
 * Layer 1: HTF Trend Direction (15m/30m) → Determines IF and WHICH direction to trade
 * Layer 2: 5m Continuation Zone → Confirms pullback to confluent zone
 * Layer 3: 1m Displacement Entry → Precise entry trigger with FVG retracement
 *
 * Each layer must pass before the next is evaluated. This is a SEQUENTIAL GATE,
 * not a flat confluence checklist.
 */
public class IctHighConfluenceStrategy implements TradingStrategy {

    private final String primarySymbol;
    private final String smtSymbol;
    private final EventBus eventBus;

    // Core detectors (1-minute timeframe)
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

    // Multi-timeframe analysis (NEW)
    private final BarAggregationManager barManager;
    private final MultiTimeframeAnalyzer mtfAnalyzer;

    // 3-Layer Cascade Components (MULTI-TIMEFRAME TREND INTEGRATION)
    private final HtfTrendAnalyzer htfTrendAnalyzer;            // Layer 1: HTF trend state machine
    private final ContinuationPatternDetector continuationDetector; // Layer 2: 5m continuation zones
    private final LiquidityTargetIdentifier liquidityTargetId;     // Liquidity target identification

    // Advanced market structure detectors (NEW)
    private final VolumeProfileAnalyzer volumeProfileAnalyzer;
    private final ConsolidationDetector consolidationDetector;
    private final TrendlineDetector trendlineDetector;
    private final CandleSeries candleSeries;
    private final LevelEngine levelEngine;
    private final EqualLevelDetector equalLevelDetector;
    private final RaidDetector raidDetector;

    // Macro news integration (NEW)
    private MacroNewsManager macroNewsManager;  // Optional - set via setter

    // Mandatory confluence validation (NEW)
    private MandatoryConfluenceValidator mandatoryValidator;  // Initialized after chartState is created

    // Configuration
    private final double fibLow = 0.62;   // OTE zone low
    private final double fibHigh = 0.705; // OTE zone high
    private final int basePositionSize = 1;
    private final int maxPositionSize = 2;
    private final double maxPriceDistance = 50.0;  // Max distance for entry zones

    // ═══════════════════════════════════════════════════════════════════════════
    // ASYMMETRIC DIRECTIONAL FILTERS (Based on trade recap analysis)
    // Longs: 37.5% win rate, -$1,106.89 net
    // Shorts: 60% win rate, +$2,509.31 net
    // Solution: Require stronger confluence for longs
    // ═══════════════════════════════════════════════════════════════════════════
    private boolean requireMacroAlignedForLongs = true;      // Longs need ALIGNED macro, not just NEUTRAL
    private boolean requireSmtForLongs = true;               // Longs MUST have SMT divergence
    private TradeTier minimumTierForLongs = TradeTier.TIER_3; // Longs need Tier 3+, shorts can be Tier 2

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
    private int htfBiasNotAligned = 0;  // NEW: HTF bias rejection counter
    private int htfTrendRanging = 0;  // NEW: HTF trend ranging — no trades
    private int htfTrendOpposing = 0; // NEW: HTF trend opposing direction
    private int no5mContinuationZone = 0; // NEW: No 5m continuation zone
    private int no1mDisplacement = 0; // NEW: No 1m displacement trigger
    private int noSweep = 0;
    private int sweepMismatch = 0;
    private int noEntryZone = 0;
    private int htfRequirementsNotMet = 0;  // NEW: HTF tier requirements not met
    private int newsGatingBlocked = 0;  // NEW: Blocked by macro news
    private int macroOpposingBlocked = 0;  // NEW: Blocked by opposing macro bias
    private int longMacroNotAligned = 0;  // NEW: Long rejected - macro not ALIGNED
    private int longMissingSmtDivergence = 0;  // NEW: Long rejected - no SMT
    private int longTierTooLow = 0;  // NEW: Long rejected - tier below minimum
    private int symbolDisabled = 0;  // NEW: Symbol trading disabled
    private int symbolTierTooLow = 0;  // NEW: Symbol tier requirement not met
    private int signalsGenerated = 0;

    // Current trade setup info
    private TradeTier currentTier = null;
    private BreakerBlock currentBreaker = null;
    private FairValueGap currentFvg = null;
    private OrderBlock currentOrderBlock = null;
    private MitigationBlock currentMitigationBlock = null;
    private boolean hasDisplacement = false;
    private boolean hasPower3Confirmation = false;
    private boolean hasHtfAlignment = false;  // NEW: Track HTF alignment
    private int htfConfluenceScore = 0;       // NEW: Track HTF score
    private int recommendedQuantity = 1;

    // 3-Layer Cascade state
    private HtfTrendState currentHtfTrendState = HtfTrendState.RANGING;
    private int continuationZoneScore = 0;
    private boolean hasLayer3EntryTrigger = false;
    private int liquidityTargetBonus = 0;
    private LiquidityTargetIdentifier.LiquidityTarget currentLiquidityTarget = null;

    // Advanced confluence state (NEW)
    private int volumeConfluenceScore = 0;
    private int consolidationScore = 0;
    private int trendlineScore = 0;
    private boolean hasVolumeSpike = false;
    private boolean isConsolidating = false;
    private boolean hasTrendlineBreak = false;
    private SmtDivergenceResult smtResult = null;

    // Macro news state (NEW)
    private MacroAlignment macroAlignment = MacroAlignment.NEUTRAL;
    private double newsSizeMultiplier = 1.0;
    private int macroConfluenceAdjustment = 0;

    // Session boundary tracking for HTF candle finalization
    private java.time.Instant lastCandleTimestamp = null;
    private static final long SESSION_GAP_THRESHOLD_SECONDS = 120; // 2 minutes — any gap > this triggers session boundary

    public IctHighConfluenceStrategy(String primarySymbol, String smtSymbol, EventBus eventBus) {
        this.primarySymbol = primarySymbol;
        this.smtSymbol = smtSymbol;
        this.eventBus = eventBus;

        // Initialize core detectors (1-minute timeframe)
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

        // Initialize multi-timeframe analysis (NEW)
        // 100 candles per timeframe provides ~1.5 hours of 1m data, ~8 hours of 5m, etc.
        this.barManager = new BarAggregationManager(primarySymbol, 100);
        this.mtfAnalyzer = new MultiTimeframeAnalyzer(primarySymbol, barManager);

        // Initialize 3-Layer Cascade Components (MULTI-TIMEFRAME TREND INTEGRATION)
        this.htfTrendAnalyzer = new HtfTrendAnalyzer(primarySymbol, barManager);
        this.continuationDetector = new ContinuationPatternDetector(primarySymbol, barManager);
        // LiquidityTargetIdentifier initialized after LevelEngine (below)

        // Initialize advanced market structure detectors (NEW)
        double tickSize = getTickSizeForSymbol(primarySymbol);
        this.volumeProfileAnalyzer = new VolumeProfileAnalyzer(primarySymbol, tickSize);
        this.consolidationDetector = new ConsolidationDetector(primarySymbol, tickSize);
        this.trendlineDetector = new TrendlineDetector(primarySymbol, tickSize);

        // Initialize candle series and raid detection
        this.candleSeries = new CandleSeries(primarySymbol, 5000);  // 5000 candle capacity
        this.levelEngine = new LevelEngine(primarySymbol, candleSeries);
        this.equalLevelDetector = new EqualLevelDetector(primarySymbol, candleSeries);
        this.raidDetector = new RaidDetector(primarySymbol, levelEngine, equalLevelDetector, candleSeries);

        // Initialize LiquidityTargetIdentifier (needs levelEngine)
        this.liquidityTargetId = new LiquidityTargetIdentifier(primarySymbol, levelEngine);

        // Setup automatic raid confirmation listener
        setupRaidConfirmation();

        // Initialize mandatory confluence validator
        initializeMandatoryValidator();
    }

    /**
     * Initialize the mandatory confluence validator.
     * This validates ALL mandatory confluences before allowing trade entry.
     */
    private void initializeMandatoryValidator() {
        // Create a simple ChartStateQueryAPI adapter for the validator
        ChartStateQueryAPI chartStateAdapter = createChartStateAdapter();

        // Initialize validator with required components
        this.mandatoryValidator = new MandatoryConfluenceValidator(
                mtfAnalyzer,
                displacementDetector,
                chartStateAdapter
        );

        System.out.println("[" + primarySymbol + "] Mandatory Confluence Validator initialized");
    }

    /**
     * Create a ChartStateQueryAPI adapter that provides raid access.
     */
    private ChartStateQueryAPI createChartStateAdapter() {
        return new ChartStateQueryAPI() {
            @Override
            public String getSymbol() {
                return primarySymbol;
            }

            @Override
            public com.topstep.trading.chartstate.InstrumentRaidConfig getConfig() {
                return com.topstep.trading.chartstate.InstrumentRaidConfig.forSymbol(primarySymbol);
            }

            @Override
            public java.util.List<LiquidityRaid> getActiveRaids() {
                return raidDetector.getActiveRaids();
            }

            @Override
            public java.util.List<LiquidityRaid> getEntryValidRaids() {
                return raidDetector.getEntryValidRaids();
            }

            @Override
            public java.util.List<LiquidityRaid> getConfirmedRaids() {
                return raidDetector.getConfirmedRaids();
            }

            @Override
            public Optional<LiquidityRaid> getBestActiveRaid() {
                return raidDetector.getBestActiveRaid();
            }

            @Override
            public Optional<LiquidityRaid> getActiveBullishRaid() {
                return raidDetector.getActiveRaidByDirection(
                        com.topstep.trading.chartstate.RaidDirection.LOW_SWEEP);
            }

            @Override
            public Optional<LiquidityRaid> getActiveBearishRaid() {
                return raidDetector.getActiveRaidByDirection(
                        com.topstep.trading.chartstate.RaidDirection.HIGH_SWEEP);
            }

            @Override
            public boolean hasActiveRaidForDirection(boolean expectBullish) {
                return raidDetector.hasActiveRaidForDirection(expectBullish);
            }

            @Override
            public Optional<LiquidityRaid> getRaidById(String raidId) {
                return raidDetector.getRaidById(raidId);
            }

            // Delegate level queries to levelEngine
            @Override
            public Optional<Double> getPDH() {
                return levelEngine.getPDH();
            }

            @Override
            public Optional<Double> getPDL() {
                return levelEngine.getPDL();
            }

            @Override
            public Optional<Double> getPWH() {
                return levelEngine.getPWH();
            }

            @Override
            public Optional<Double> getPWL() {
                return levelEngine.getPWL();
            }

            @Override
            public java.util.List<com.topstep.trading.chartstate.KnownLevel> getAllLevels() {
                return levelEngine.getAllLevels();
            }

            @Override
            public java.util.List<com.topstep.trading.chartstate.KnownLevel> getUnraidedLevels() {
                return levelEngine.getUnraidedLevels();
            }

            @Override
            public java.util.List<com.topstep.trading.chartstate.KnownLevel> getLevelsNearPrice(double price) {
                return levelEngine.getLevelsNearPrice(price);
            }

            @Override
            public Optional<com.topstep.trading.chartstate.KnownLevel> getNearestLevelAbove(double price) {
                return levelEngine.getNearestLevelAbove(price);
            }

            @Override
            public Optional<com.topstep.trading.chartstate.KnownLevel> getNearestLevelBelow(double price) {
                return levelEngine.getNearestLevelBelow(price);
            }

            @Override
            public Optional<com.topstep.trading.chartstate.KnownLevel> getLevel(
                    com.topstep.trading.chartstate.LevelType type) {
                return levelEngine.getLevel(type);
            }

            @Override
            public java.util.List<com.topstep.trading.chartstate.EqualLevelDetector.EqualLevel> getEqualHighs() {
                return equalLevelDetector.getEqualHighs();
            }

            @Override
            public java.util.List<com.topstep.trading.chartstate.EqualLevelDetector.EqualLevel> getEqualLows() {
                return equalLevelDetector.getEqualLows();
            }

            @Override
            public Optional<com.topstep.trading.chartstate.EqualLevelDetector.EqualLevel> getStrongestEqualHigh() {
                return equalLevelDetector.getStrongestEqualHigh();
            }

            @Override
            public Optional<com.topstep.trading.chartstate.EqualLevelDetector.EqualLevel> getStrongestEqualLow() {
                return equalLevelDetector.getStrongestEqualLow();
            }

            @Override
            public java.util.List<com.topstep.trading.chartstate.EqualLevelDetector.EqualLevel> getEqualHighsAbove(double price) {
                return equalLevelDetector.getEqualHighsAbove(price);
            }

            @Override
            public java.util.List<com.topstep.trading.chartstate.EqualLevelDetector.EqualLevel> getEqualLowsBelow(double price) {
                return equalLevelDetector.getEqualLowsBelow(price);
            }

            @Override
            public Optional<Double> getLatestClose() {
                return candleSeries.getLatestClose();
            }

            @Override
            public double getHighest(int lookback) {
                return candleSeries.getHighest(lookback);
            }

            @Override
            public double getLowest(int lookback) {
                return candleSeries.getLowest(lookback);
            }

            @Override
            public double getAverageRange(int lookback) {
                return candleSeries.getAverageRange(lookback);
            }

            @Override
            public boolean hasMinimumData(int required) {
                return candleSeries.size() >= required;
            }

            @Override
            public boolean isInAsia() {
                return false;  // Not needed for validation
            }

            @Override
            public boolean isInLondon() {
                return false;  // Not needed for validation
            }

            @Override
            public boolean isInNY() {
                return false;  // Not needed for validation
            }

            @Override
            public String getLevelsSummary() {
                return levelEngine.toString();
            }

            @Override
            public String getRaidsSummary() {
                return raidDetector.getRaidsSummary();
            }
        };
    }

    /**
     * Set the MacroNewsManager for news-based gating and bias.
     * This is optional - if not set, news integration is disabled.
     *
     * @param macroNewsManager The MacroNewsManager instance
     */
    public void setMacroNewsManager(MacroNewsManager macroNewsManager) {
        this.macroNewsManager = macroNewsManager;
        System.out.println("[" + primarySymbol + "] MacroNewsManager integration enabled");
    }

    /**
     * Get tick size for symbol (instrument-specific).
     */
    private double getTickSizeForSymbol(String symbol) {
        if (symbol.contains("ES") || symbol.contains("MES")) return 0.25;
        if (symbol.contains("NQ") || symbol.contains("MNQ")) return 0.25;
        if (symbol.contains("GC")) return 0.10;
        return 0.25;  // Default
    }

    /**
     * Setup automatic raid confirmation after MSS/Displacement.
     * This connects RaidDetector to the displacement detection system.
     */
    private void setupRaidConfirmation() {
        raidDetector.addRaidListener(raid -> {
            // When a new raid is detected, check if displacement follows
            System.out.println("[" + primarySymbol + "] AUTO-RAID: New raid detected - " +
                    raid.getDirection().getDisplayName() + " @ " + raid.getTargetLevel().getType().getDisplayName());
        });
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

        // ═══════════════════════════════════════════════════════════════════════════
        // FINAL VALIDATION: Asymmetric Long/Short Requirements
        // Based on trade recap: Longs 37.5% WR (-$1,106), Shorts 60% WR (+$2,509)
        // ═══════════════════════════════════════════════════════════════════════════
        MarketBias finalBias = structureDetector.getBias();
        boolean isBullishEntry = (finalBias == MarketBias.BULLISH);

        if (!validateAsymmetricRequirements(isBullishEntry, candle)) {
            return;  // Validation failed, don't generate signal
        }

        // ═══════════════════════════════════════════════════════════════════════════
        // MANDATORY CONFLUENCE VALIDATION (NEW - Critical filter for high win rate)
        // All 6 checks must pass: Bias/Sweep, Displacement, Raid Quality, HTF, Market Condition, Not Promoted
        // ═══════════════════════════════════════════════════════════════════════════
        if (!validateMandatoryConfluences(finalBias, isBullishEntry, candle)) {
            return;  // Mandatory validation failed, don't generate signal
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
     * Called when the trading session ends.
     * Force-finalizes any in-progress HTF candles as partial candles so downstream
     * analysis doesn't lose the data accumulated in incomplete HTF windows.
     */
    @Override
    public void onSessionEnd() {
        Map<Timeframe, Candle> partialCandles = barManager.forceCompleteAll();
        if (!partialCandles.isEmpty()) {
            System.out.println("[" + primarySymbol + "] SESSION END: Finalized " +
                    partialCandles.size() + " partial HTF candles: " + partialCandles.keySet());
            // Feed partial candles to MTF detectors for completeness
            mtfAnalyzer.update(partialCandles);
            htfTrendAnalyzer.update(partialCandles);
            continuationDetector.update(partialCandles);
        }
        lastCandleTimestamp = null;
    }

    /**
     * Update all detectors with new candle data.
     */
    private void updateDetectors(Candle candle) {
        // SESSION GAP DETECTION: If the time gap between consecutive candles
        // exceeds the threshold (2 minutes), this indicates a session boundary
        // or data gap. Force-complete any in-progress HTF candles as partial
        // before starting the new session's aggregation.
        if (lastCandleTimestamp != null) {
            long gapSeconds = java.time.Duration.between(lastCandleTimestamp, candle.getTimestamp()).getSeconds();
            if (gapSeconds > SESSION_GAP_THRESHOLD_SECONDS) {
                Map<Timeframe, Candle> partialCandles = barManager.forceCompleteAll();
                if (!partialCandles.isEmpty()) {
                    System.out.println("[" + primarySymbol + "] SESSION GAP (" + gapSeconds +
                            "s): Finalized " + partialCandles.size() + " partial HTF candles");
                    mtfAnalyzer.update(partialCandles);
                    htfTrendAnalyzer.update(partialCandles);
                    continuationDetector.update(partialCandles);
                }
            }
        }
        lastCandleTimestamp = candle.getTimestamp();

        // Update 1-minute detectors
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

        // Update multi-timeframe analysis
        // Process candle through bar aggregation to get completed higher-TF candles
        Map<Timeframe, Candle> completedCandles = barManager.processCandle(candle);
        // Update MTF detectors with any newly completed candles
        mtfAnalyzer.update(completedCandles);

        // Update 3-Layer Cascade components (MULTI-TIMEFRAME TREND INTEGRATION)
        htfTrendAnalyzer.update(completedCandles);        // Layer 1: HTF trend state
        continuationDetector.update(completedCandles);    // Layer 2: 5m continuation zones

        // Update advanced market structure detectors (NEW)
        candleSeries.addCandle(candle);
        levelEngine.processCandle(candle);
        volumeProfileAnalyzer.update(candle);
        consolidationDetector.update(candle);
        trendlineDetector.update(candle);
        equalLevelDetector.ageAndCleanupLevels();

        // Update raid detector with context for SMT/HTF bias + cascade data
        MarketBias bias1m = structureDetector.getBias();
        Boolean htfBullish = (bias1m == MarketBias.BULLISH) ? Boolean.TRUE :
                             (bias1m == MarketBias.BEARISH) ? Boolean.FALSE : null;
        boolean hasSmt = correlationTracker.hasSMTDivergence(primarySymbol, smtSymbol, 10);
        boolean isBullish1mCtx = bias1m == MarketBias.BULLISH;
        RaidDetector.RaidDetectionContext raidContext =
                RaidDetector.RaidDetectionContext.fullWithCascade(
                        hasSmt, htfBullish,
                        htfTrendAnalyzer.isStrongTrend(isBullish1mCtx),
                        continuationDetector.getZoneConfluenceScore(isBullish1mCtx),
                        displacementDetector.hasLayer3EntryTrigger(10, isBullish1mCtx),
                        liquidityTargetId.getTargetAlignmentBonus(candle.getClose(), isBullish1mCtx)
                );
        raidDetector.processCandle(candle, raidContext);

        // AUTO-CONFIRM RAIDS: Check for displacement/MSS after raid detection
        autoConfirmActiveRaids(candle);
    }

    /**
     * Automatically confirm active raids when displacement or MSS/FVG is detected.
     */
    private void autoConfirmActiveRaids(Candle candle) {
        for (LiquidityRaid raid : raidDetector.getActiveRaids()) {
            if (raid.getState() == com.topstep.trading.chartstate.RaidState.ACTIVE) {
                boolean isBullishRaid = raid.getDirection() == com.topstep.trading.chartstate.RaidDirection.LOW_SWEEP;

                // Check displacement in the raid direction
                boolean hasDisp = displacementDetector.hasRecentDisplacement(5, isBullishRaid);

                // Check if structure bias matches raid direction (MSS confirmation)
                MarketBias currentBias = structureDetector.getBias();
                boolean hasMss = (isBullishRaid && currentBias == MarketBias.BULLISH) ||
                                 (!isBullishRaid && currentBias == MarketBias.BEARISH);

                // Check for FVG in raid direction
                boolean hasFvg = fvgDetector.hasFvgInDirection(isBullishRaid);

                if (hasDisp || (hasMss && hasFvg)) {
                    raidDetector.confirmRaid(raid.getId(), hasDisp, hasMss, hasFvg);
                    System.out.println("[" + primarySymbol + "] AUTO-RAID CONFIRMED: " + raid.getId() +
                            " (disp=" + hasDisp + ", mss=" + hasMss + ", fvg=" + hasFvg + ")");
                }
            }
        }
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
        hasHtfAlignment = false;
        htfConfluenceScore = 0;
        volumeConfluenceScore = 0;
        consolidationScore = 0;
        trendlineScore = 0;
        hasVolumeSpike = false;
        isConsolidating = false;
        hasTrendlineBreak = false;
        smtResult = null;
        macroAlignment = MacroAlignment.NEUTRAL;
        newsSizeMultiplier = 1.0;
        macroConfluenceAdjustment = 0;
        // Reset cascade state
        currentHtfTrendState = htfTrendAnalyzer.getTrendState();
        continuationZoneScore = 0;
        hasLayer3EntryTrigger = false;
        liquidityTargetBonus = 0;
        currentLiquidityTarget = null;

        // ═══════════════════════════════════════════════════════════════════════════
        // 0. MACRO NEWS GATING CHECK (NEW - First check before any other filters)
        // ═══════════════════════════════════════════════════════════════════════════
        if (macroNewsManager != null && macroNewsManager.isRunning()) {
            TradeGatingDecision gatingDecision = macroNewsManager.checkTradeGating(primarySymbol);

            if (gatingDecision.getAction() == GatingAction.BLOCK) {
                newsGatingBlocked++;
                if (shouldLog) {
                    System.out.println("[" + primarySymbol + "] BLOCKED by news gating: " +
                            gatingDecision.getReason());
                }
                return false;
            }

            // Store size multiplier for later use in position sizing
            newsSizeMultiplier = gatingDecision.getSizeMultiplier();
            if (newsSizeMultiplier < 1.0 && shouldLog) {
                System.out.println("[" + primarySymbol + "] NEWS: Size reduced to " +
                        String.format("%.0f%%", newsSizeMultiplier * 100) +
                        " (" + gatingDecision.getReason() + ")");
            }
        }

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

        // 3. Check 1-minute structure bias (quick filter)
        MarketBias bias1m = structureDetector.getBias();
        if (bias1m == MarketBias.NEUTRAL) {
            neutralBias++;
            return false;
        }

        boolean isBullish1m = bias1m == MarketBias.BULLISH;

        // ═══════════════════════════════════════════════════════════════════════════
        // 3.5. 3-LAYER CASCADE CHECK (PRIMARY DIRECTIONAL FILTER)
        // This is THE most important check — HTF trend as the dominant gate.
        // If HTF is ranging, NO trades. If HTF opposes, NO trades.
        // ═══════════════════════════════════════════════════════════════════════════
        currentHtfTrendState = htfTrendAnalyzer.getTrendState();

        // LAYER 1: HTF Trend Direction Gate
        if (!htfTrendAnalyzer.isTrending()) {
            htfTrendRanging++;
            if (shouldLog) {
                System.out.println("[" + primarySymbol + "] CASCADE L1 BLOCKED: HTF ranging (" +
                        htfTrendAnalyzer.getSummary() + ") — NO TRADES");
            }
            return false;
        }

        if (!htfTrendAnalyzer.allowsDirection(isBullish1m)) {
            htfTrendOpposing++;
            if (shouldLog) {
                System.out.println("[" + primarySymbol + "] CASCADE L1 BLOCKED: HTF trend opposes " +
                        (isBullish1m ? "LONGS" : "SHORTS") + " (" +
                        currentHtfTrendState.getDisplayName() + ")");
            }
            return false;
        }

        // LAYER 2: 5m Continuation Zone (optional but boosts tier)
        ContinuationPatternDetector.ContinuationZone bestZone =
                continuationDetector.getBestActiveZone(isBullish1m);
        continuationZoneScore = continuationDetector.getZoneConfluenceScore(isBullish1m);

        if (bestZone != null && shouldLog) {
            System.out.println("[" + primarySymbol + "] CASCADE L2: 5m zone found — " + bestZone);
        }

        // LAYER 3: 1m Displacement (checked later in existing displacement detection)
        hasLayer3EntryTrigger = displacementDetector.hasLayer3EntryTrigger(10, isBullish1m);

        // Liquidity Target Analysis
        currentLiquidityTarget = liquidityTargetId.findNextTarget(candle.getClose(), isBullish1m)
                .orElse(null);
        liquidityTargetBonus = liquidityTargetId.getTargetAlignmentBonus(candle.getClose(), isBullish1m);

        if (currentLiquidityTarget != null && shouldLog) {
            System.out.println("[" + primarySymbol + "] LIQUIDITY TARGET: " + currentLiquidityTarget);
        }

        // Check if nearest significant target already swept (move may be exhausted)
        if (liquidityTargetId.isNearestTargetSwept(candle.getClose(), isBullish1m, maxPriceDistance)) {
            if (shouldLog) {
                System.out.println("[" + primarySymbol + "] WARNING: Nearest significant target already swept — move may be exhausted");
            }
            // Don't block, but reduce score
            liquidityTargetBonus = Math.max(0, liquidityTargetBonus - 1);
        }

        // Log cascade state
        if (shouldLog) {
            System.out.println("[" + primarySymbol + "] CASCADE: HTF=" + currentHtfTrendState.getDisplayName() +
                    ", 5mZone=" + continuationZoneScore + ", L3Trigger=" + hasLayer3EntryTrigger +
                    ", LiqTarget=" + liquidityTargetBonus +
                    (htfTrendAnalyzer.isInFavorableZone(candle.getClose(), isBullish1m) ?
                            " [FAVORABLE ZONE]" : " [UNFAVORABLE ZONE]"));
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

        // 5. Ensure sweep matches 1m bias
        if (bias1m == MarketBias.BULLISH && !sweep.isBullish()) {
            sweepMismatch++;
            return false;
        }
        if (bias1m == MarketBias.BEARISH && !sweep.isBearish()) {
            sweepMismatch++;
            return false;
        }

        boolean isBullish = bias1m == MarketBias.BULLISH;

        // ═══════════════════════════════════════════════════════════════════════════
        // 6. MULTI-TIMEFRAME BIAS CHECK (NEW - Critical for high-probability setups)
        // ═══════════════════════════════════════════════════════════════════════════
        // Check if HTF (30m + 15m) bias aligns with our intended direction
        MarketBias bias30m = mtfAnalyzer.getHtfBias30m();
        MarketBias bias15m = mtfAnalyzer.getHtfBias15m();
        hasHtfAlignment = mtfAnalyzer.htfBiasAligns(isBullish);

        // Log HTF bias status periodically
        if (shouldLog) {
            System.out.println("[" + primarySymbol + "] HTF Bias: 30m=" + bias30m + ", 15m=" + bias15m +
                    ", Aligned=" + hasHtfAlignment + " (want " + (isBullish ? "BULLISH" : "BEARISH") + ")");
        }

        // For Tier 3+ setups, HTF alignment is REQUIRED
        // For Tier 2, at least 15m must not oppose
        boolean htf15mNotOpposing = (isBullish && bias15m != MarketBias.BEARISH) ||
                                    (!isBullish && bias15m != MarketBias.BULLISH);

        if (!htf15mNotOpposing) {
            // 15m bias is actively opposing our direction - reject
            htfBiasNotAligned++;
            if (shouldLog) {
                System.out.println("[" + primarySymbol + "] REJECTED: 15m bias opposes trade direction");
            }
            return false;
        }

        // Calculate HTF confluence score for tier grading
        MultiTimeframeAnalyzer.HtfConfluenceResult htfResult =
                mtfAnalyzer.calculateHtfConfluence(candle.getClose(), isBullish, maxPriceDistance);
        htfConfluenceScore = htfResult.score;

        // ═══════════════════════════════════════════════════════════════════════════
        // 6.3. MACRO NEWS ALIGNMENT CHECK (NEW - Adjust confluence based on news)
        // ═══════════════════════════════════════════════════════════════════════════
        if (macroNewsManager != null && macroNewsManager.isRunning()) {
            macroAlignment = macroNewsManager.getMacroAlignment(primarySymbol, isBullish);
            macroConfluenceAdjustment = macroNewsManager.getConfluenceAdjustment(primarySymbol, isBullish);

            if (shouldLog) {
                double newsBias = macroNewsManager.getNewsBiasModifier(primarySymbol);
                System.out.println("[" + primarySymbol + "] MACRO: Alignment=" + macroAlignment +
                        ", Bias=" + String.format("%.2f", newsBias) +
                        ", Confluence adj=" + (macroConfluenceAdjustment >= 0 ? "+" : "") + macroConfluenceAdjustment);
            }

            // Check for strong opposing macro bias that should block the trade
            if (macroNewsManager.shouldBlockOnOpposition(primarySymbol, isBullish)) {
                macroOpposingBlocked++;
                System.out.println("[" + primarySymbol + "] BLOCKED by strong opposing macro bias");
                return false;
            }
        }

        // ═══════════════════════════════════════════════════════════════════════════
        // 6.5. ADVANCED MARKET STRUCTURE ANALYSIS (NEW)
        // ═══════════════════════════════════════════════════════════════════════════
        double tickSize = getTickSizeForSymbol(primarySymbol);
        double tolerance = tickSize * 5;

        // Volume Profile: Check for HVN/LVN, volume spikes
        volumeConfluenceScore = volumeProfileAnalyzer.getVolumeConfluenceScore(candle.getClose(), tolerance);
        hasVolumeSpike = volumeProfileAnalyzer.hasVolumeSpike(candle, 2.0);  // 2x average volume

        // Consolidation: Check market state and breakout potential
        isConsolidating = consolidationDetector.isConsolidating();
        consolidationScore = consolidationDetector.getConfluenceScore(candle.getClose(), isBullish);

        // Trendline: Check for breaks and proximity
        trendlineScore = trendlineDetector.getConfluenceScore(candle.getClose(), isBullish);
        hasTrendlineBreak = isBullish ? trendlineDetector.hasBrokenResistance(candle.getClose())
                                      : trendlineDetector.hasBrokenSupport(candle.getClose());

        // Check for confirmed raids (highest probability setups)
        Optional<LiquidityRaid> confirmedRaid = raidDetector.getRaidByDirection(
                isBullish ? com.topstep.trading.chartstate.RaidDirection.LOW_SWEEP
                          : com.topstep.trading.chartstate.RaidDirection.HIGH_SWEEP);

        // CONSOLIDATION WARNING: Avoid trading in tight consolidation
        if (isConsolidating && consolidationDetector.isTightConsolidation()) {
            if (shouldLog) {
                System.out.println("[" + primarySymbol + "] WARNING: In tight consolidation - " +
                        consolidationDetector.getAdvice());
            }
            // Don't reject, but the negative score will affect tier determination
        }

        // VOLUME SPIKE BONUS: Institutional activity detected
        if (hasVolumeSpike && shouldLog) {
            System.out.println("[" + primarySymbol + "] VOLUME SPIKE detected (2x+ average) - institutional activity");
        }

        // TRENDLINE BREAK BONUS
        if (hasTrendlineBreak && shouldLog) {
            System.out.println("[" + primarySymbol + "] TRENDLINE BREAK detected - momentum confirmation");
        }

        // CONFIRMED RAID: Use if available (high probability)
        if (confirmedRaid.isPresent() && confirmedRaid.get().getState() == com.topstep.trading.chartstate.RaidState.CONFIRMED) {
            if (shouldLog) {
                System.out.println("[" + primarySymbol + "] CONFIRMED RAID available: " +
                        confirmedRaid.get().getQualityClassification() + " (score=" +
                        confirmedRaid.get().getQualityScore() + ")");
            }
        }

        // 7. Check for additional confluences
        // Build detailed SMT divergence result (NEW)
        boolean basicSmtCheck = sweep.hasSmtDivergence() ||
                correlationTracker.hasSMTDivergence(primarySymbol, smtSymbol, 10);

        if (basicSmtCheck) {
            // Calculate relative strength and build detailed result
            double relStrength = correlationTracker.getRelativeStrength(primarySymbol, smtSymbol, 10);
            double correlation = correlationTracker.calculateCorrelation(primarySymbol, smtSymbol);
            double expectedCorr = 0.85;  // Expected high correlation for ES/NQ
            boolean abnormalCorr = correlationTracker.hasAbnormalCorrelation(primarySymbol, smtSymbol);

            // Determine strength (0-5 scale) based on relative strength
            int strength = Math.min(5, (int)(Math.abs(relStrength) * 10));
            if (abnormalCorr) strength = Math.min(5, strength + 1);  // Bonus for abnormal

            // Determine type based on direction
            SmtDivergenceResult.DivergenceType smtType = isBullish ?
                    SmtDivergenceResult.DivergenceType.BULLISH_SMT :
                    SmtDivergenceResult.DivergenceType.BEARISH_SMT;

            smtResult = new SmtDivergenceResult(smtType, primarySymbol, smtSymbol,
                    strength, relStrength, correlation, expectedCorr, abnormalCorr);

            if (shouldLog) {
                System.out.println("[" + primarySymbol + "] SMT: " + smtResult.getDescription() +
                        " (score=" + smtResult.getConfluenceScore() + ")");
            }
        } else {
            smtResult = SmtDivergenceResult.none(primarySymbol, smtSymbol);
        }

        boolean hasSmtDivergence = smtResult.hasDivergence() && smtResult.alignsWith(isBullish);
        hasDisplacement = displacementDetector.hasRecentDisplacement(10, isBullish);
        hasPower3Confirmation = power3Detector.isInDistribution() &&
                power3Detector.confirmsDirection(isBullish);

        // 8. Look for entry zones by tier (highest to lowest)
        // Count confluences for tier determination
        int confluenceCount = 0;

        // Calculate total advanced score from new detectors
        int advancedScore = volumeConfluenceScore + consolidationScore + trendlineScore;
        if (hasVolumeSpike) advancedScore += 1;
        if (hasTrendlineBreak) advancedScore += 1;
        // Add SMT confluence score (NEW)
        advancedScore += smtResult.getConfluenceScore();
        // Add macro news alignment score (NEW)
        advancedScore += macroConfluenceAdjustment;
        if (confirmedRaid.isPresent() && confirmedRaid.get().getState() == com.topstep.trading.chartstate.RaidState.CONFIRMED) {
            advancedScore += confirmedRaid.get().getQualityScore() / 10;  // Scale raid quality
        }

        // Find 1m entry zones
        currentBreaker = breakerBlockDetector.findNearestBreaker(candle.getClose(), isBullish, maxPriceDistance);
        FairValueGap ifvg = fvgDetector.findNearestIfvg(candle.getClose(), isBullish);
        currentOrderBlock = orderBlockDetector.findNearestValidOb(candle.getClose(), isBullish, maxPriceDistance);

        // Also check for HTF entry zones (5m/15m)
        BreakerBlock htfBreaker = mtfAnalyzer.findBestBreaker(candle.getClose(), isBullish, maxPriceDistance);
        MultiTimeframeAnalyzer.FvgResult htfFvgResult = mtfAnalyzer.findBestHtfFvg(candle.getClose(), isBullish, maxPriceDistance);
        MultiTimeframeAnalyzer.ObResult htfObResult = mtfAnalyzer.findBestHtfOb(candle.getClose(), isBullish, maxPriceDistance);

        // ═══════════════════════════════════════════════════════════════════════════
        // TIER 4: ELITE SETUP - Requires STRONG HTF trend + premium confluences + 5m zone + liquidity target
        // Strong HTF Trend + Breaker Block + Power3 + SMT + Displacement + 5m Zone + Target
        // ═══════════════════════════════════════════════════════════════════════════
        boolean isStrongHtf = htfTrendAnalyzer.isStrongTrend(isBullish);

        if (isStrongHtf && hasHtfAlignment && currentBreaker != null && hasPower3Confirmation && hasSmtDivergence && hasDisplacement) {
            // Tier 4 now requires cascade components for maximum quality
            boolean hasCascadeBoost = continuationZoneScore >= 2 || liquidityTargetBonus >= 1;
            if (mtfAnalyzer.meetsHtfRequirements(TradeTier.TIER_4, isBullish, candle.getClose(), maxPriceDistance)) {
                currentTier = TradeTier.TIER_4;
                String entryType = "StrongHTF+Breaker+Power3+SMT+Displacement";
                if (continuationZoneScore >= 2) entryType += "+5mZone(" + continuationZoneScore + ")";
                if (liquidityTargetBonus >= 1) entryType += "+LiqTarget";
                if (hasLayer3EntryTrigger) entryType += "+L3Trigger";
                printTier4Signal(candle, bias1m, sweep, entryType, hasSmtDivergence);
                return true;
            }
        }

        // ═══════════════════════════════════════════════════════════════════════════
        // TIER 3: PREMIUM SETUP - HTF trending + 5m zone OR strong confluences
        // HTF Trend + Breaker + Displacement + (SMT OR Power3)
        // OR: HTF Trend + 5m Zone(2+) + Displacement + (SMT OR Power3)
        // OR: HTF Trend + IFVG + OB + Displacement + Power3
        // ═══════════════════════════════════════════════════════════════════════════
        if (hasHtfAlignment || currentHtfTrendState.isTrending()) {
            // Check for Breaker-based Tier 3
            if (currentBreaker != null && hasDisplacement) {
                confluenceCount = 2;  // Breaker + Displacement counts as 2
                if (hasSmtDivergence) confluenceCount++;
                if (hasPower3Confirmation) confluenceCount++;
                if (continuationZoneScore >= 2) confluenceCount++;  // 5m zone confluence bonus
                if (liquidityTargetBonus >= 1) confluenceCount++;   // Liquidity target bonus

                if (confluenceCount >= 3) {
                    if (mtfAnalyzer.meetsHtfRequirements(TradeTier.TIER_3, isBullish, candle.getClose(), maxPriceDistance)) {
                        currentTier = TradeTier.TIER_3;
                        String entryType = "HTF(" + currentHtfTrendState.getDisplayName() + ")+Breaker+Displacement";
                        if (hasSmtDivergence) entryType += "+SMT";
                        if (hasPower3Confirmation) entryType += "+Power3";
                        if (continuationZoneScore >= 2) entryType += "+5mZone(" + continuationZoneScore + ")";
                        if (liquidityTargetBonus >= 1) entryType += "+LiqTarget";
                        printTier3Signal(candle, bias1m, sweep, entryType, hasSmtDivergence);
                        return true;
                    }
                }
            }

            // 5m Zone + Displacement + confirmation = Tier 3 (NEW pathway via cascade)
            if (continuationZoneScore >= 2 && hasDisplacement && (hasSmtDivergence || hasPower3Confirmation)) {
                if (mtfAnalyzer.meetsHtfRequirements(TradeTier.TIER_3, isBullish, candle.getClose(), maxPriceDistance)) {
                    currentTier = TradeTier.TIER_3;
                    String entryType = "HTF(" + currentHtfTrendState.getDisplayName() + ")+5mZone(" + continuationZoneScore + ")+Displacement";
                    if (hasSmtDivergence) entryType += "+SMT";
                    if (hasPower3Confirmation) entryType += "+Power3";
                    if (hasLayer3EntryTrigger) entryType += "+L3Trigger";
                    printTier3Signal(candle, bias1m, sweep, entryType, hasSmtDivergence);
                    return true;
                }
            }

            // Check for IFVG+OB Tier 3
            if (ifvg != null && currentOrderBlock != null && hasDisplacement && hasPower3Confirmation) {
                if (mtfAnalyzer.meetsHtfRequirements(TradeTier.TIER_3, isBullish, candle.getClose(), maxPriceDistance)) {
                    currentTier = TradeTier.TIER_3;
                    currentFvg = ifvg;
                    printTier3Signal(candle, bias1m, sweep, "HTF+IFVG+OB+Displacement+Power3", hasSmtDivergence);
                    return true;
                }
            }

            // Check for HTF zones as Tier 3 (HTF breaker or HTF FVG + displacement)
            if (htfBreaker != null && hasDisplacement && (hasSmtDivergence || hasPower3Confirmation)) {
                currentTier = TradeTier.TIER_3;
                currentBreaker = htfBreaker;  // Use HTF breaker for entry
                String entryType = "HTF_Breaker(5m)+Displacement";
                if (hasSmtDivergence) entryType += "+SMT";
                if (hasPower3Confirmation) entryType += "+Power3";
                if (continuationZoneScore >= 1) entryType += "+5mZone(" + continuationZoneScore + ")";
                printTier3Signal(candle, bias1m, sweep, entryType, hasSmtDivergence);
                return true;
            }
        }

        // ═══════════════════════════════════════════════════════════════════════════
        // TIER 2: STANDARD SETUP - HTF trending (even weak) + strong confluences
        // (OB + Displacement + SMT)
        // OR: (Unfilled FVG + SMT + Displacement)
        // OR: (Fresh Mitigation + SMT + Displacement)
        // OR: (HTF FVG/OB + Displacement)
        // OR: (5m Zone + Displacement) — NEW cascade pathway
        // ═══════════════════════════════════════════════════════════════════════════
        FairValueGap unfilledFvg = fvgDetector.findNearestUnfilledFvg(candle.getClose(), isBullish, maxPriceDistance);

        // Verify HTF requirements for Tier 2
        boolean htfTier2Ok = mtfAnalyzer.meetsHtfRequirements(TradeTier.TIER_2, isBullish, candle.getClose(), maxPriceDistance);

        if (htfTier2Ok) {
            if (currentOrderBlock != null && hasDisplacement && hasSmtDivergence) {
                currentTier = TradeTier.TIER_2;
                String detail = "OB+Displacement+SMT (HTF=" + htfConfluenceScore +
                        ", trend=" + currentHtfTrendState.getDisplayName() + ")";
                if (continuationZoneScore >= 1) detail += "+5mZone(" + continuationZoneScore + ")";
                if (liquidityTargetBonus >= 1) detail += "+LiqTarget";
                printTier2Signal(candle, bias1m, sweep, detail, hasSmtDivergence);
                return true;
            }

            if (unfilledFvg != null && hasSmtDivergence && hasDisplacement) {
                currentTier = TradeTier.TIER_2;
                currentFvg = unfilledFvg;
                String detail = "FVG+SMT+Displacement (HTF=" + htfConfluenceScore +
                        ", trend=" + currentHtfTrendState.getDisplayName() + ")";
                if (continuationZoneScore >= 1) detail += "+5mZone(" + continuationZoneScore + ")";
                printTier2Signal(candle, bias1m, sweep, detail, hasSmtDivergence);
                return true;
            }

            currentMitigationBlock = mitigationBlockDetector.findBestMitigationZone(candle.getClose(), isBullish, maxPriceDistance);
            if (currentMitigationBlock != null && currentMitigationBlock.isFresh() && hasSmtDivergence && hasDisplacement) {
                currentTier = TradeTier.TIER_2;
                printTier2Signal(candle, bias1m, sweep, "Fresh Mitigation+SMT+Displacement (HTF=" + htfConfluenceScore + ")", hasSmtDivergence);
                return true;
            }

            // HTF FVG or OB with displacement qualifies for Tier 2
            if (htfFvgResult != null && hasDisplacement) {
                currentTier = TradeTier.TIER_2;
                currentFvg = htfFvgResult.fvg;
                printTier2Signal(candle, bias1m, sweep, "HTF_FVG(" + htfFvgResult.timeframe.getLabel() + ")+Displacement (HTF=" + htfConfluenceScore + ")", hasSmtDivergence);
                return true;
            }

            if (htfObResult != null && hasDisplacement && hasSmtDivergence) {
                currentTier = TradeTier.TIER_2;
                currentOrderBlock = htfObResult.ob;
                printTier2Signal(candle, bias1m, sweep, "HTF_OB(" + htfObResult.timeframe.getLabel() + ")+Displacement+SMT (HTF=" + htfConfluenceScore + ")", hasSmtDivergence);
                return true;
            }

            // NEW cascade pathway: 5m continuation zone + displacement = Tier 2
            if (continuationZoneScore >= 1 && hasDisplacement) {
                currentTier = TradeTier.TIER_2;
                printTier2Signal(candle, bias1m, sweep,
                        "5mZone(" + continuationZoneScore + ")+Displacement (HTF=" + htfConfluenceScore +
                                ", trend=" + currentHtfTrendState.getDisplayName() + ")",
                        hasSmtDivergence);
                return true;
            }
        } else {
            htfRequirementsNotMet++;
        }

        // ═══════════════════════════════════════════════════════════════════════════
        // TIER 1 DISABLED - MINIMUM TIER 2 REQUIRED (3 confluences + displacement + HTF)
        // ═══════════════════════════════════════════════════════════════════════════
        // After integrating MTF analysis, requirements are even stricter:
        // - Tier 1 (2 confluences) is NO LONGER accepted
        // - ALL trades must have displacement (institutional move)
        // - ALL trades must pass HTF requirements for their tier
        // - Minimum 3 confluences required (Tier 2+)
        // This drastically reduces trade frequency but improves win rate.
        // ═══════════════════════════════════════════════════════════════════════════

        FairValueGap anyFvg = fvgDetector.findNearestFvg(candle.getClose(), isBullish, maxPriceDistance);

        confluenceCount = 0;
        if (anyFvg != null) confluenceCount++;
        if (hasSmtDivergence) confluenceCount++;
        if (hasDisplacement) confluenceCount++;
        if (currentOrderBlock != null) confluenceCount++;
        // Add HTF confluence bonus
        if (htfConfluenceScore >= 3) confluenceCount++;
        // Add advanced score bonus (NEW)
        if (advancedScore >= 3) confluenceCount++;  // Volume + trendline + consolidation alignment

        // TIGHTENED: Require 3+ confluences AND displacement AND HTF requirements for ANY entry
        if (confluenceCount >= 3 && hasDisplacement && htfTier2Ok) {
            currentTier = TradeTier.TIER_2;
            currentFvg = anyFvg;
            String entryType = "";
            if (anyFvg != null) entryType += "FVG";
            if (hasSmtDivergence) entryType += (entryType.isEmpty() ? "" : "+") + "SMT";
            entryType += (entryType.isEmpty() ? "" : "+") + "Displacement";
            if (currentOrderBlock != null) entryType += "+OB";
            entryType += " [HTF=" + htfConfluenceScore + "]";
            printTier2Signal(candle, bias1m, sweep, entryType + " [PROMOTED]", hasSmtDivergence);
            return true;
        }

        // REJECTED: Log rejection reason for debugging
        if (confluenceCount >= 2 && !hasDisplacement) {
            System.out.println("[" + primarySymbol + "] REJECTED: Had " + confluenceCount +
                " confluences but NO DISPLACEMENT (required for all entries)");
        } else if (!htfTier2Ok && confluenceCount >= 3) {
            System.out.println("[" + primarySymbol + "] REJECTED: Had " + confluenceCount +
                " confluences but HTF requirements not met (15m=" + bias15m + ", score=" + htfConfluenceScore + ")");
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
        System.out.println("[" + primarySymbol + "] HTF: 30m=" + mtfAnalyzer.getHtfBias30m() +
                          ", 15m=" + mtfAnalyzer.getHtfBias15m() +
                          " | Aligned: " + (hasHtfAlignment ? "✓" : "~") +
                          " | Score: " + htfConfluenceScore);
        System.out.println("[" + primarySymbol + "] SMT: " + (hasSmt ? "✓" : "~") +
                          " | Displacement: " + (hasDisplacement ? "✓" : "~") +
                          " | Power3: " + (hasPower3Confirmation ? "✓" : "~"));
        // Print advanced scores (NEW)
        System.out.println("[" + primarySymbol + "] Volume: " + volumeConfluenceScore +
                          (hasVolumeSpike ? " (SPIKE)" : "") +
                          " | Consolidation: " + consolidationScore +
                          (isConsolidating ? " (RANGING)" : "") +
                          " | Trendline: " + trendlineScore +
                          (hasTrendlineBreak ? " (BREAK)" : ""));
        // Print macro news info (NEW)
        if (macroNewsManager != null && macroNewsManager.isRunning()) {
            double newsBias = macroNewsManager.getNewsBiasModifier(primarySymbol);
            System.out.println("[" + primarySymbol + "] Macro: " + macroAlignment +
                              " | NewsBias: " + String.format("%.2f", newsBias) +
                              " | SizeMult: " + String.format("%.0f%%", newsSizeMultiplier * 100));
        }
        double adjustedRR = currentTier.getRiskRewardRatio() * currentTier.getTierMultiplier();
        System.out.println("[" + primarySymbol + "] R:R Target: 1:" + currentTier.getRiskRewardRatio() +
                          " (adjusted by tier multiplier: " + currentTier.getTierMultiplier() + ")");
    }

    /**
     * Validate asymmetric long/short requirements.
     * Based on trade recap analysis:
     * - Longs: 37.5% win rate, -$1,106.89 → need stronger requirements
     * - Shorts: 60% win rate, +$2,509.31 → keep as is
     *
     * @param isBullish true if this is a long entry
     * @param candle current candle for logging
     * @return true if validation passes, false to reject the trade
     */
    private boolean validateAsymmetricRequirements(boolean isBullish, Candle candle) {
        // ═══════════════════════════════════════════════════════════════════════════
        // LONG-SPECIFIC REQUIREMENTS (because longs are underperforming)
        // ═══════════════════════════════════════════════════════════════════════════
        if (isBullish) {
            // 1. Longs MUST have macro ALIGNED (not just NEUTRAL)
            if (requireMacroAlignedForLongs && macroNewsManager != null && macroNewsManager.isRunning()) {
                if (macroAlignment != MacroAlignment.ALIGNED) {
                    longMacroNotAligned++;
                    System.out.println("[" + primarySymbol + "] LONG REJECTED: Macro not ALIGNED (was " +
                            macroAlignment + "). Longs require ALIGNED macro bias.");
                    return false;
                }
            }

            // 2. Longs MUST have SMT divergence
            if (requireSmtForLongs && (smtResult == null || !smtResult.hasDivergence())) {
                longMissingSmtDivergence++;
                System.out.println("[" + primarySymbol + "] LONG REJECTED: No SMT divergence. " +
                        "Longs require SMT confirmation.");
                return false;
            }

            // 3. Longs need minimum Tier 3 (shorts can be Tier 2)
            if (currentTier != null && currentTier.getLevel() < minimumTierForLongs.getLevel()) {
                longTierTooLow++;
                System.out.println("[" + primarySymbol + "] LONG REJECTED: Tier " + currentTier +
                        " below minimum " + minimumTierForLongs + " for longs.");
                return false;
            }
        }

        return true;  // All validations passed
    }

    /**
     * Validate ALL mandatory confluences for trade entry.
     *
     * This method enforces the 6 MANDATORY requirements identified from
     * real trade analysis comparing winning vs losing setups:
     *
     * 1. Bias/Sweep Alignment (opposite directions)
     * 2. Confirmed Displacement
     * 3. Raid Quality ≥ 5
     * 4. HTF Confirmation
     * 5. Market Condition ≥ 0
     * 6. Not Promoted
     *
     * ALL checks must pass or the trade is rejected.
     *
     * @param bias The market bias (BULLISH or BEARISH)
     * @param isBullish True for bullish entry, false for bearish
     * @param candle Current candle
     * @return true if all mandatory confluences are met, false otherwise
     */
    private boolean validateMandatoryConfluences(MarketBias bias, boolean isBullish, Candle candle) {
        // Get raid direction from active raids
        com.topstep.trading.chartstate.RaidDirection raidDirection = null;
        Optional<LiquidityRaid> raidOpt = raidDetector.getBestActiveRaid();
        if (raidOpt.isPresent()) {
            raidDirection = raidOpt.get().getDirection();
        }

        // Calculate market condition score
        // For now, use a simplified version based on session and volatility
        // Future: integrate with full MarketConditionFilter if available
        int marketConditionScore = calculateSimpleMarketConditionScore(candle);

        // Check if this trade was promoted (currently always false - future enhancement)
        boolean wasPromoted = false;

        // Run mandatory validation
        ValidationResult result = mandatoryValidator.validateEntry(
                primarySymbol,
                bias,
                raidDirection,
                isBullish,
                marketConditionScore,
                wasPromoted
        );

        if (result.failed()) {
            // Log detailed rejection reasons
            System.out.println(result.getSummary());
            return false;
        }

        // Log approval with confirmations
        System.out.println(result.getSummary());
        return true;
    }

    /**
     * Calculate a simple market condition score for validation.
     * This is a simplified version - integrate with MarketConditionFilter for full scoring.
     *
     * @param candle Current candle
     * @return Market condition score (higher is better, 0 is minimum acceptable)
     */
    private int calculateSimpleMarketConditionScore(Candle candle) {
        int score = 0;

        // Killzone check (+2)
        if (killzoneClock.isInKillzone(candle.getTimestamp())) {
            KillzonePhase phase = killzoneClock.getKillzonePhase(candle.getTimestamp());
            if (phase == KillzonePhase.PRIME) {
                score += 2;
            } else if (phase == KillzonePhase.OPENING) {
                score += 1;
            }
        }

        // Volatility check (+1 if tradeable, -3 if extreme)
        if (atrCalculator.isTradeable()) {
            score += 1;
        } else {
            score -= 3;
        }

        // HTF alignment (+2 if aligned)
        if (hasHtfAlignment) {
            score += 2;
        }

        // Macro alignment (+1 if aligned, -2 if opposing)
        if (macroAlignment == MacroAlignment.ALIGNED) {
            score += 1;
        } else if (macroAlignment == MacroAlignment.OPPOSING) {
            score -= 2;
        }

        return score;
    }

    /**
     * Generate signal with tier-based R:R and quantity.
     */
    private void generateSignal(Candle candle, StrategyContext context) {
        MarketBias bias = structureDetector.getBias();
        LiquiditySweep sweep = liquidityDetector.getLastSweep();

        // Calculate position size based on ATR
        recommendedQuantity = atrCalculator.getRecommendedPositionSize(basePositionSize, maxPositionSize);

        // Apply news size multiplier (NEW)
        if (newsSizeMultiplier < 1.0) {
            int adjustedQty = (int) Math.max(1, Math.round(recommendedQuantity * newsSizeMultiplier));
            if (adjustedQty != recommendedQuantity) {
                System.out.println("[" + primarySymbol + "] Size adjusted by news: " +
                        recommendedQuantity + " -> " + adjustedQty +
                        " (" + String.format("%.0f%%", newsSizeMultiplier * 100) + " multiplier)");
                recommendedQuantity = adjustedQty;
            }
        }

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

        // Target: Based on tier R:R (adjusted by tier multiplier for more realistic targets)
        double riskDistance = entry - stop;
        double adjustedRR = currentTier.getRiskRewardRatio() * currentTier.getTierMultiplier();
        double target = entry + (riskDistance * adjustedRR);

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

        // Target: Based on tier R:R (adjusted by tier multiplier for more realistic targets)
        double riskDistance = stop - entry;
        double adjustedRR = currentTier.getRiskRewardRatio() * currentTier.getTierMultiplier();
        double target = entry - (riskDistance * adjustedRR);

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

        // HTF alignment info
        if (hasHtfAlignment) {
            reason.append(" [HTF✓]");
        } else if (htfConfluenceScore >= 3) {
            reason.append(" [HTF=").append(htfConfluenceScore).append("]");
        }

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
        System.out.println("  Features: Breaker Blocks, Mitigation, Power of 3, ATR Sizing, MTF Analysis");
        System.out.println("  MTF Timeframes: 30m+15m bias, 5m zones, 3m displacement");
        System.out.println("  Tiers: 4 (HTF+Elite) | 3 (HTF+Premium) | 2 (15m+Standard) | 1 (DISABLED)");
    }

    @Override
    public void shutdown() {
        System.out.println("Shutting down " + getName());

        // Print diagnostic summary
        System.out.println("\n" + "=".repeat(60));
        System.out.println("STRATEGY DIAGNOSTIC SUMMARY (with MTF)");
        System.out.println("=".repeat(60));
        System.out.println("Total candles processed: " + candleCount);
        System.out.println("Signals generated: " + signalsGenerated);
        System.out.println("\nRejection breakdown:");
        System.out.println("  Outside killzone:      " + outsideKillzone + " (" + pct(outsideKillzone) + ")");
        System.out.println("  Not trading day:       " + notTradingDay + " (" + pct(notTradingDay) + ")");
        System.out.println("  Wrong phase:           " + wrongPhase + " (" + pct(wrongPhase) + ")");
        System.out.println("  Volatility blocked:    " + volatilityBlocked + " (" + pct(volatilityBlocked) + ")");
        System.out.println("  Neutral bias (1m):     " + neutralBias + " (" + pct(neutralBias) + ")");
        System.out.println("  HTF bias opposing:     " + htfBiasNotAligned + " (" + pct(htfBiasNotAligned) + ")");
        System.out.println("  --- 3-LAYER CASCADE FILTERS ---");
        System.out.println("  HTF trend ranging:     " + htfTrendRanging + " (" + pct(htfTrendRanging) + ")");
        System.out.println("  HTF trend opposing:    " + htfTrendOpposing + " (" + pct(htfTrendOpposing) + ")");
        System.out.println("  No 5m continuation:    " + no5mContinuationZone + " (" + pct(no5mContinuationZone) + ")");
        System.out.println("  No 1m displacement:    " + no1mDisplacement + " (" + pct(no1mDisplacement) + ")");
        System.out.println("  News gating blocked:   " + newsGatingBlocked + " (" + pct(newsGatingBlocked) + ")");
        System.out.println("  Macro opposing blocked:" + macroOpposingBlocked + " (" + pct(macroOpposingBlocked) + ")");
        System.out.println("  --- ASYMMETRIC FILTERS (Longs harder than Shorts) ---");
        System.out.println("  Long: macro not aligned: " + longMacroNotAligned + " (" + pct(longMacroNotAligned) + ")");
        System.out.println("  Long: missing SMT:       " + longMissingSmtDivergence + " (" + pct(longMissingSmtDivergence) + ")");
        System.out.println("  Long: tier too low:      " + longTierTooLow + " (" + pct(longTierTooLow) + ")");
        System.out.println("  Symbol disabled:         " + symbolDisabled + " (" + pct(symbolDisabled) + ")");
        System.out.println("  Symbol tier too low:     " + symbolTierTooLow + " (" + pct(symbolTierTooLow) + ")");
        System.out.println("  ---");
        System.out.println("  No recent sweep:       " + noSweep + " (" + pct(noSweep) + ")");
        System.out.println("  Sweep direction mismatch: " + sweepMismatch + " (" + pct(sweepMismatch) + ")");
        System.out.println("  HTF tier req not met:  " + htfRequirementsNotMet + " (" + pct(htfRequirementsNotMet) + ")");
        System.out.println("  No entry zone found:   " + noEntryZone + " (" + pct(noEntryZone) + ")");
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

    // MTF analysis getters (NEW)
    public MultiTimeframeAnalyzer getMtfAnalyzer() { return mtfAnalyzer; }
    public BarAggregationManager getBarManager() { return barManager; }
    public boolean hasHtfAlignment() { return hasHtfAlignment; }
    public int getHtfConfluenceScore() { return htfConfluenceScore; }

    // 3-Layer Cascade getters (MULTI-TIMEFRAME TREND INTEGRATION)
    public HtfTrendAnalyzer getHtfTrendAnalyzer() { return htfTrendAnalyzer; }
    public ContinuationPatternDetector getContinuationDetector() { return continuationDetector; }
    public LiquidityTargetIdentifier getLiquidityTargetId() { return liquidityTargetId; }
    public HtfTrendState getCurrentHtfTrendState() { return currentHtfTrendState; }
    public int getContinuationZoneScore() { return continuationZoneScore; }
    public boolean hasLayer3Trigger() { return hasLayer3EntryTrigger; }
    public int getLiquidityTargetBonus() { return liquidityTargetBonus; }

    // Advanced market structure getters (NEW)
    public VolumeProfileAnalyzer getVolumeProfileAnalyzer() { return volumeProfileAnalyzer; }
    public ConsolidationDetector getConsolidationDetector() { return consolidationDetector; }
    public TrendlineDetector getTrendlineDetector() { return trendlineDetector; }
    public RaidDetector getRaidDetector() { return raidDetector; }
    public EqualLevelDetector getEqualLevelDetector() { return equalLevelDetector; }
    public int getVolumeConfluenceScore() { return volumeConfluenceScore; }
    public int getConsolidationScore() { return consolidationScore; }
    public int getTrendlineScore() { return trendlineScore; }
    public boolean hasVolumeSpike() { return hasVolumeSpike; }
    public boolean isConsolidating() { return isConsolidating; }
    public boolean hasTrendlineBreak() { return hasTrendlineBreak; }

    // Macro news getters (NEW)
    public MacroNewsManager getMacroNewsManager() { return macroNewsManager; }
    public MacroAlignment getMacroAlignment() { return macroAlignment; }
    public double getNewsSizeMultiplier() { return newsSizeMultiplier; }
    public int getMacroConfluenceAdjustment() { return macroConfluenceAdjustment; }

    // ═══════════════════════════════════════════════════════════════════════════
    // ASYMMETRIC FILTER CONFIGURATION (Adjustable at runtime)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Configure whether longs require ALIGNED macro bias.
     * Default: true (based on trade recap showing longs underperform)
     */
    public void setRequireMacroAlignedForLongs(boolean require) {
        this.requireMacroAlignedForLongs = require;
        System.out.println("[" + primarySymbol + "] Longs require ALIGNED macro: " + require);
    }

    /**
     * Configure whether longs require SMT divergence.
     * Default: true (based on trade recap)
     */
    public void setRequireSmtForLongs(boolean require) {
        this.requireSmtForLongs = require;
        System.out.println("[" + primarySymbol + "] Longs require SMT: " + require);
    }

    /**
     * Set minimum tier for long entries.
     * Default: TIER_3 (shorts can be TIER_2)
     */
    public void setMinimumTierForLongs(TradeTier tier) {
        this.minimumTierForLongs = tier;
        System.out.println("[" + primarySymbol + "] Minimum tier for longs: " + tier);
    }

    // Getters for current settings
    public boolean isRequireMacroAlignedForLongs() { return requireMacroAlignedForLongs; }
    public boolean isRequireSmtForLongs() { return requireSmtForLongs; }
    public TradeTier getMinimumTierForLongs() { return minimumTierForLongs; }
}
