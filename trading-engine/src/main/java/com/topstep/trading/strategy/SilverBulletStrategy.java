package com.topstep.trading.strategy;

import com.topstep.trading.domain.Candle;
import com.topstep.trading.domain.OrderSide;
import com.topstep.trading.event.EventBus;
import com.topstep.trading.event.StrategySignalEvent;
import com.topstep.trading.event.StrategySignalEvent.SignalType;
import com.topstep.trading.validation.TradeEntryValidator;

import java.time.Instant;
import java.util.List;

/**
 * ICT Silver Bullet Strategy Implementation.
 *
 * The Silver Bullet is a precise intraday setup methodology with specific
 * time windows and strict setup requirements:
 *
 * TIME WINDOWS (New York Time):
 * 1. London Open SB: 3:00 AM - 4:00 AM EST (Forex, Metals)
 * 2. NY AM SB: 10:00 AM - 11:00 AM EST (Indices, Energy, Gold)
 * 3. NY PM SB: 2:00 PM - 3:00 PM EST (Indices)
 *
 * SETUP TEMPLATE (Required Sequence):
 * 1. Liquidity Raid: Price sweeps a known liquidity level (PDH/PDL, session H/L, equal H/L)
 * 2. Market Structure Shift (MSS): Break of swing H/L on 1m/3m in reversal direction
 * 3. Displacement: Strong impulse move that creates the MSS
 * 4. Entry FVG: Fair Value Gap created by the displacement leg
 * 5. Entry: Limit order into the FVG retrace
 * 6. Stop: Beyond the raid swing / displacement candle extreme
 * 7. Target: Opposing liquidity (nearest meaningful buy/sell-side liquidity)
 *
 * TIER GRADING (Overlaid on top of SB template):
 * - Tier 4: SB + HTF OB/Breaker + SMT + Strong Displacement
 * - Tier 3: SB + OB/Breaker + (Displacement OR SMT)
 * - Tier 2: SB + MSS + FVG + Clear liquidity target
 * - Tier 1: SB Lite with tighter risk constraints
 *
 * @deprecated as of the STDV+OTE refactor. The Silver Bullet entry mechanics
 *     are absorbed by {@code StdvOteStrategy} (sequential sweep → displacement
 *     → MSS → OTE) with the killzone gate driving M3. This class has zero
 *     production references; it is retained only to preserve the historical
 *     reference implementation until SA8's post-SIM cleanup, at which point
 *     it can be deleted. Do not wire this class to any new runner.
 */
@Deprecated(forRemoval = true, since = "v2.0-stdv-ote")
public class SilverBulletStrategy implements TradingStrategy {

    private final String symbol;
    private final EventBus eventBus;
    private final SilverBulletClock sbClock;
    private final BarAggregationManager barManager;
    private final MarketStructureShiftDetector mssDetector;
    private final LiquidityDetector liquidityDetector;
    private final FvgDetector fvgDetector;
    private final OrderBlockDetector obDetector;
    private final BreakerBlockDetector breakerDetector;
    private final DisplacementDetector displacementDetector;
    private final SilverBulletClock.LiquidityTargets liquidityTargets;

    // Multi-timeframe analysis (NEW)
    private final MultiTimeframeAnalyzer mtfAnalyzer;

    // Configuration
    private final double tickSize;
    private final double tickValue;
    private final int maxCandleLookback;

    // State tracking
    private boolean isActive;
    private boolean hasLiquidityRaid;
    private boolean raidIsBullish;  // true = sell-side raid (bullish setup), false = buy-side raid
    private MarketStructureShiftDetector.MSS lastMSS;
    private FairValueGap entryFvg;
    private double raidLevel;
    private int candlesSinceRaid;

    // Trade management
    private boolean inPosition;
    private double entryPrice;
    private double stopPrice;
    private double targetPrice;

    public SilverBulletStrategy(String symbol, double tickSize, double tickValue) {
        this(symbol, tickSize, tickValue, null);
    }

    public SilverBulletStrategy(String symbol, double tickSize, double tickValue, EventBus eventBus) {
        this.symbol = symbol;
        this.tickSize = tickSize;
        this.tickValue = tickValue;
        this.maxCandleLookback = 100;
        this.eventBus = eventBus;

        // Initialize components
        this.sbClock = new SilverBulletClock();
        this.barManager = new BarAggregationManager(symbol, maxCandleLookback);
        this.mssDetector = new MarketStructureShiftDetector(50, 2);  // 50 lookback, 2 candle swing confirmation
        this.liquidityDetector = new LiquidityDetector(30);
        this.fvgDetector = new FvgDetector(20);
        this.obDetector = new OrderBlockDetector(30, 10);
        this.breakerDetector = new BreakerBlockDetector(obDetector, 10);
        this.displacementDetector = new DisplacementDetector(14);  // 14 period ATR, 1.5x threshold
        this.liquidityTargets = new SilverBulletClock.LiquidityTargets();

        // Initialize multi-timeframe analysis (NEW)
        this.mtfAnalyzer = new MultiTimeframeAnalyzer(symbol, barManager);

        this.isActive = false;
        this.inPosition = false;
        this.hasLiquidityRaid = false;
        this.candlesSinceRaid = 0;
    }

    @Override
    public void onCandle(Candle candle, StrategyContext context) {
        // Process candle through bar aggregation and update MTF analysis
        java.util.Map<BarAggregationManager.Timeframe, Candle> completedCandles = barManager.processCandle(candle);
        mtfAnalyzer.update(completedCandles);

        // Update all detectors with 1m candle
        liquidityDetector.updatePrimary(candle);
        fvgDetector.update(candle);
        obDetector.update(candle);
        breakerDetector.update(candle);
        displacementDetector.update(candle);

        // Update MSS detector
        MarketStructureShiftDetector.MSS mss = mssDetector.update(candle);
        if (mss != null) {
            lastMSS = mss;
            System.out.println("[SB] " + symbol + " MSS detected: " + mss);
        }

        // Update liquidity targets
        updateLiquidityTargets(candle);

        // Track time since raid
        if (hasLiquidityRaid) {
            candlesSinceRaid++;
            // Reset if too long since raid (15 candles = 15 minutes)
            if (candlesSinceRaid > 15) {
                resetRaidState();
            }
        }

        // Check for liquidity raid
        checkForLiquidityRaid(candle);

        // Evaluate entry conditions and publish a signal if configured
        StrategySignalEvent signal = evaluateEntry(candle, context);
        if (signal != null) {
            if (eventBus != null) {
                eventBus.publish(signal);
            } else {
                System.out.println("[SB] Signal generated (no EventBus attached): " + signal);
            }
        }
    }

    /**
     * Update session liquidity targets from candle data.
     */
    private void updateLiquidityTargets(Candle candle) {
        // Update current session high/low
        if (candle.getHigh() > liquidityTargets.currentSessionHigh) {
            liquidityTargets.currentSessionHigh = candle.getHigh();
        }
        if (candle.getLow() < liquidityTargets.currentSessionLow) {
            liquidityTargets.currentSessionLow = candle.getLow();
        }
    }

    /**
     * Check for liquidity raid (sweep of known level).
     */
    private void checkForLiquidityRaid(Candle candle) {
        LiquiditySweep sweep = liquidityDetector.getLastSweep();

        if (sweep != null && liquidityDetector.hasRecentSweep(3)) {
            // New raid detected
            hasLiquidityRaid = true;
            raidIsBullish = sweep.isBullish();
            raidLevel = sweep.getSweptLevel();
            candlesSinceRaid = 0;

            System.out.println("[SB] " + symbol + " LIQUIDITY RAID: " +
                    (raidIsBullish ? "Sell-side swept (Bullish setup)" : "Buy-side swept (Bearish setup)") +
                    " at " + raidLevel);
        }
    }

    private StrategySignalEvent evaluateEntry(Candle candle, StrategyContext context) {
        Instant now = candle.getTimestamp();

        // FILTER 1: Must be in a Silver Bullet window
        if (!sbClock.isInSilverBulletWindow(now)) {
            return null;
        }

        // FILTER 2: Must have enough time remaining
        if (!sbClock.hasEnoughTimeForTrade(now)) {
            return null;
        }

        // FILTER 3: Symbol should be optimal for current window
        if (!sbClock.isOptimalSymbol(symbol, now)) {
            // Allow but reduce tier
        }

        // FILTER 4: Already in position
        if (inPosition) {
            return null;
        }

        // FILTER 5: No existing position in account
        if (context.getOpenPositions().containsKey(symbol)) {
            return null;
        }

        // SILVER BULLET SETUP CHECK
        StrategySignalEvent signal = checkSilverBulletSetup(candle, now);

        return signal;
    }

    /**
     * Check for complete Silver Bullet setup.
     */
    private StrategySignalEvent checkSilverBulletSetup(Candle candle, Instant now) {
        // Step 1: Must have a recent liquidity raid
        if (!hasLiquidityRaid || candlesSinceRaid > 10) {
            return null;
        }

        // Step 2: Must have MSS in direction of reversal (opposite of raid direction)
        boolean expectedMssDirection = raidIsBullish;  // Bullish raid -> Bullish MSS
        if (!mssDetector.hasRecentMSS(8)) {
            return null;
        }

        MarketStructureShiftDetector.MSS mss = mssDetector.getLastMSS();
        if (mss.isBullish != expectedMssDirection) {
            return null;
        }

        // Step 2.5: HTF BIAS CHECK (NEW) - 15m bias must not oppose trade direction
        MarketBias bias15m = mtfAnalyzer.getHtfBias15m();
        boolean htf15mOpposing = (expectedMssDirection && bias15m == MarketBias.BEARISH) ||
                                 (!expectedMssDirection && bias15m == MarketBias.BULLISH);
        if (htf15mOpposing) {
            System.out.println("[SB] " + symbol + " REJECTED: 15m bias (" + bias15m +
                    ") opposes trade direction (" + (expectedMssDirection ? "BULLISH" : "BEARISH") + ")");
            return null;
        }

        // Step 3: Find entry FVG created after MSS
        FairValueGap fvg = findEntryFvg(expectedMssDirection, candle.getClose());
        if (fvg == null) {
            return null;
        }

        // Step 4: Price must be retracing into FVG
        boolean isInFvg = isInFvgZone(candle, fvg);
        if (!isInFvg) {
            return null;
        }

        // SETUP CONFIRMED - Calculate levels and grade tier
        entryFvg = fvg;

        // Calculate entry, stop, and target
        OrderSide side = expectedMssDirection ? OrderSide.BUY : OrderSide.SELL;
        double entry = calculateEntryPrice(fvg, expectedMssDirection);
        double stop = calculateStopPrice(mss, expectedMssDirection);
        double target = calculateTargetPrice(expectedMssDirection, entry, stop);

        this.entryPrice = entry;
        this.stopPrice = stop;
        this.targetPrice = target;

        // Validate stop distance
        double stopDistance = Math.abs(entry - stop);
        double targetDistance = Math.abs(target - entry);
        double rr = targetDistance / stopDistance;

        if (rr < 2.0) {
            System.out.println("[SB] " + symbol + " R:R too low: " + String.format("%.2f", rr));
            return null;
        }

        // ═══════════════════════════════════════════════════════════════════════════
        // MANDATORY CONFLUENCE VALIDATION (NEW - Simplified for Silver Bullet)
        // Check critical confluences: Displacement, HTF confirmation
        // ═══════════════════════════════════════════════════════════════════════════
        if (!validateMandatoryConfluencesSilverBullet(expectedMssDirection, candle)) {
            return null;  // Mandatory validation failed
        }

        // Grade the tier
        TradeTier tier = gradeTier(mss, fvg, now);

        // Create signal
        System.out.println("\n========================================");
        System.out.println("[SB] SILVER BULLET SETUP - " + symbol);
        System.out.println("  Window: " + sbClock.getCurrentWindow(now).getName());
        System.out.println("  Direction: " + (expectedMssDirection ? "BULLISH (Long)" : "BEARISH (Short)"));
        System.out.println("  HTF: 30m=" + mtfAnalyzer.getHtfBias30m() + ", 15m=" + mtfAnalyzer.getHtfBias15m() +
                          " | Aligned: " + (mtfAnalyzer.htfBiasAligns(expectedMssDirection) ? "✓" : "~"));
        System.out.println("  Raid Level: " + raidLevel);
        System.out.println("  MSS Level: " + mss.breakLevel);
        System.out.println("  Entry FVG: " + fvg.getBottom() + " - " + fvg.getTop());
        System.out.println("  Entry: " + entry);
        System.out.println("  Stop: " + stop + " (distance: " + String.format("%.2f", stopDistance) + ")");
        System.out.println("  Target: " + target + " (R:R = " + String.format("%.2f", rr) + ")");
        System.out.println("  Tier: " + tier);
        System.out.println("========================================\n");

        // Reset state after generating signal
        resetRaidState();

        SignalType signalType = side == OrderSide.BUY ? SignalType.LONG_ENTRY : SignalType.SHORT_ENTRY;
        return new StrategySignalEvent(
                signalType,
                symbol,
                side,
                entry,
                stop,
                target,
                "Silver Bullet " + tier + " - " + sbClock.getCurrentWindow(now).getName(),
                tier,
                1
        );
    }

    /**
     * Find an entry FVG in the expected direction.
     */
    private FairValueGap findEntryFvg(boolean bullish, double currentPrice) {
        // Look for unfilled FVG first, then any FVG
        double maxDistance = Math.abs(currentPrice * 0.005);  // 0.5% max distance

        FairValueGap fvg = fvgDetector.findNearestUnfilledFvg(currentPrice, bullish, maxDistance);
        if (fvg != null) {
            return fvg;
        }

        return fvgDetector.findNearestFvg(currentPrice, bullish, maxDistance);
    }

    /**
     * Check if current price is in FVG retrace zone.
     */
    private boolean isInFvgZone(Candle candle, FairValueGap fvg) {
        if (fvg.isBullish()) {
            // For bullish FVG, price should retrace down into the gap
            return candle.getLow() <= fvg.getTop() && candle.getLow() >= fvg.getBottom();
        } else {
            // For bearish FVG, price should retrace up into the gap
            return candle.getHigh() >= fvg.getBottom() && candle.getHigh() <= fvg.getTop();
        }
    }

    /**
     * Calculate entry price (midpoint of FVG for limit order).
     */
    private double calculateEntryPrice(FairValueGap fvg, boolean bullish) {
        // Enter at the midpoint of the FVG
        return fvg.getMidpoint();
    }

    /**
     * Calculate stop price (beyond the raid/MSS swing).
     */
    private double calculateStopPrice(MarketStructureShiftDetector.MSS mss, boolean bullish) {
        if (bullish) {
            // For bullish: stop below the displacement low or raid level
            double stopLevel = Math.min(mss.displacementLow, raidLevel);
            return stopLevel - (tickSize * 2);  // 2 ticks buffer
        } else {
            // For bearish: stop above the displacement high or raid level
            double stopLevel = Math.max(mss.displacementHigh, raidLevel);
            return stopLevel + (tickSize * 2);
        }
    }

    /**
     * Calculate target price (opposing liquidity).
     */
    private double calculateTargetPrice(boolean bullish, double entry, double stop) {
        if (bullish) {
            // Target buy-side liquidity (session high, previous highs)
            double target = liquidityTargets.currentSessionHigh;
            if (target <= entry) {
                // Use 3R minimum if no clear target
                target = entry + (entry - stop) * 3;
            }
            return target;
        } else {
            // Target sell-side liquidity (session low, previous lows)
            double target = liquidityTargets.currentSessionLow;
            if (target >= entry) {
                target = entry - (stop - entry) * 3;
            }
            return target;
        }
    }

    /**
     * Grade the setup tier based on confluences including HTF analysis.
     */
    private TradeTier gradeTier(MarketStructureShiftDetector.MSS mss, FairValueGap fvg, Instant now) {
        int confluenceScore = 0;
        boolean isBullish = fvg.isBullish();

        // ═══════════════════════════════════════════════════════════════════════════
        // HTF ALIGNMENT SCORING (NEW - Major factor for tier determination)
        // ═══════════════════════════════════════════════════════════════════════════
        // Full HTF alignment (30m + 15m) = +4 points (major boost)
        if (mtfAnalyzer.htfBiasAligns(isBullish)) {
            confluenceScore += 4;
        } else if (mtfAnalyzer.getHtfBias15m() == (isBullish ? MarketBias.BULLISH : MarketBias.BEARISH)) {
            // 15m aligned but not 30m = +2 points
            confluenceScore += 2;
        }

        // Check for HTF FVG or OB confluence (NEW)
        MultiTimeframeAnalyzer.FvgResult htfFvg = mtfAnalyzer.findBestHtfFvg(fvg.getMidpoint(), isBullish, 20);
        if (htfFvg != null) {
            confluenceScore += 2;  // HTF FVG alignment
        }

        MultiTimeframeAnalyzer.ObResult htfOb = mtfAnalyzer.findBestHtfOb(fvg.getMidpoint(), isBullish, 20);
        if (htfOb != null) {
            confluenceScore += 2;  // HTF OB alignment
        }

        // Check for Order Block alignment (1m)
        OrderBlock ob = obDetector.findNearestValidOb(fvg.getMidpoint(), isBullish, 10);
        if (ob != null) {
            confluenceScore += 2;
        }

        // Check for Breaker Block alignment
        BreakerBlock breaker = breakerDetector.findNearestBreaker(fvg.getMidpoint(), isBullish, 10);
        if (breaker != null) {
            confluenceScore += 3;  // Breakers are higher quality
        }

        // Check for HTF Breaker (5m) - even better
        BreakerBlock htfBreaker = mtfAnalyzer.findBestBreaker(fvg.getMidpoint(), isBullish, 20);
        if (htfBreaker != null) {
            confluenceScore += 2;  // Additional points for HTF breaker
        }

        // Check for strong displacement
        if (mss.strength > tickSize * 5) {
            confluenceScore += 1;
        }

        // Check for MTF displacement confirmation
        if (mtfAnalyzer.hasConfirmedDisplacement(isBullish)) {
            confluenceScore += 1;
        }

        // Check for SMT divergence
        LiquiditySweep sweep = liquidityDetector.getLastSweep();
        if (sweep != null && sweep.hasSmtDivergence()) {
            confluenceScore += 2;
        }

        // Check if symbol is optimal for window
        if (sbClock.isOptimalSymbol(symbol, now)) {
            confluenceScore += 1;
        }

        // Grade tier based on score (adjusted thresholds for HTF integration)
        // With HTF scoring, max possible is ~18 points
        if (confluenceScore >= 10) {
            return TradeTier.TIER_4;  // Elite SB with HTF alignment
        } else if (confluenceScore >= 7) {
            return TradeTier.TIER_3;  // Premium SB
        } else if (confluenceScore >= 4) {
            return TradeTier.TIER_2;  // Standard SB
        } else {
            return TradeTier.TIER_1;  // Lite SB (only if HTF not opposing)
        }
    }

    /**
     * Reset the raid tracking state.
     */
    /**
     * Validate mandatory confluences for Silver Bullet strategy (simplified version).
     *
     * Checks:
     * 1. Confirmed Displacement
     * 2. HTF Confirmation
     * 3. Market Condition (simplified - already in SB window = positive)
     *
     * @param isBullish Expected trade direction
     * @param candle Current candle
     * @return true if validation passes, false otherwise
     */
    private boolean validateMandatoryConfluencesSilverBullet(boolean isBullish, Candle candle) {
        List<String> failures = new java.util.ArrayList<>();
        List<String> confirmations = new java.util.ArrayList<>();

        // ═══════════════════════════════════════════════════════════════
        // CHECK 1: Confirmed Displacement (MANDATORY)
        // ═══════════════════════════════════════════════════════════════
        boolean hasDisplacement = displacementDetector.hasRecentDisplacement(5, isBullish);
        if (!hasDisplacement) {
            failures.add("✗ Displacement not confirmed. No recent displacement in trade direction.");
        } else {
            DisplacementDetector.Displacement disp = displacementDetector.getLastDisplacement();
            confirmations.add(String.format("✓ Displacement: Confirmed (%.2f pts over %d candles)",
                    disp.getMoveSize(), disp.getCandleCount()));
        }

        // ═══════════════════════════════════════════════════════════════
        // CHECK 2: HTF Confirmation (MANDATORY)
        // ═══════════════════════════════════════════════════════════════
        HtfConfirmationResult htf = mtfAnalyzer.checkHtfConfirmation(symbol, isBullish);
        if (!htf.isConfirmed()) {
            failures.add("✗ No HTF confirmation. Requires: 15mFVG, 5mFVG, 5mOB, HTF_MSS, or HTF_BIAS.");
        } else {
            confirmations.add(String.format("✓ HTF: %s (%s)", htf.getConfirmationType(), htf.getDetails()));
        }

        // ═══════════════════════════════════════════════════════════════
        // CHECK 3: Market Condition (Simplified - SB window = good timing)
        // ═══════════════════════════════════════════════════════════════
        // Already in SB window with time remaining = positive market condition
        confirmations.add("✓ Market: Silver Bullet window (optimal timing)");

        // ═══════════════════════════════════════════════════════════════
        // BUILD RESULT
        // ═══════════════════════════════════════════════════════════════
        boolean passed = failures.isEmpty();

        if (!passed) {
            // Log rejection
            System.out.println(String.format("[%s] ❌ SB ENTRY REJECTED - Failed mandatory confluences:", symbol));
            for (String failure : failures) {
                System.out.println("  " + failure);
            }
            return false;
        }

        // Log approval
        System.out.println(String.format("[%s] ✅ SB ENTRY APPROVED - Mandatory confluences confirmed:", symbol));
        for (String conf : confirmations) {
            System.out.println("  " + conf);
        }
        return true;
    }

    private void resetRaidState() {
        hasLiquidityRaid = false;
        candlesSinceRaid = 0;
        raidLevel = 0;
        lastMSS = null;
        entryFvg = null;
    }

    public boolean shouldExit(Candle candle, StrategyContext context) {
        // Exit logic is handled by bracket orders (SL/TP on exchange)
        return false;
    }

    public String getSymbol() {
        return symbol;
    }

    public void reset() {
        barManager.reset();
        mssDetector.reset();
        liquidityDetector.reset();
        fvgDetector.reset();
        obDetector.reset();
        breakerDetector.reset();
        displacementDetector.reset();

        isActive = false;
        inPosition = false;
        resetRaidState();
    }

    @Override
    public void shutdown() {
        reset();
    }

    @Override
    public String getName() {
        return "ICT Silver Bullet Strategy";
    }

    /**
     * Get the Silver Bullet clock for external access.
     */
    public SilverBulletClock getSilverBulletClock() {
        return sbClock;
    }

    /**
     * Get current SB window info.
     */
    public String getWindowInfo(Instant now) {
        return sbClock.getDetailedInfo(now);
    }

    /**
     * Get the multi-timeframe analyzer for external access.
     */
    public MultiTimeframeAnalyzer getMtfAnalyzer() {
        return mtfAnalyzer;
    }
}
