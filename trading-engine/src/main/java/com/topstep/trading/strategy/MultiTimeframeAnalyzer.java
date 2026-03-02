package com.topstep.trading.strategy;

import com.topstep.trading.domain.Candle;
import com.topstep.trading.strategy.BarAggregationManager.Timeframe;

import java.util.*;

/**
 * Multi-Timeframe Analyzer for ICT Concepts.
 *
 * Runs ICT detectors (FVG, OB, Structure) on multiple timeframes to provide
 * higher-quality signals with HTF confirmation.
 *
 * Timeframe Hierarchy:
 * - 30m/1h: HTF bias and major liquidity levels (context)
 * - 15m: Premium zones (high-quality FVGs, OBs, Breakers)
 * - 5m: Standard zones (good-quality FVGs, OBs)
 * - 3m: Execution precision (MSS, displacement)
 * - 1m: Entry triggers (FVG retrace, exact entry)
 *
 * ICT Concept Quality by Timeframe:
 * - HTF FVG (15m+): Very high probability of fill, excellent entry zone
 * - HTF OB (5m+): More reliable reaction expected
 * - HTF Structure: Defines the "true" market bias
 */
public class MultiTimeframeAnalyzer {

    private final String symbol;
    private final BarAggregationManager barManager;

    // Per-timeframe detectors
    private final Map<Timeframe, FvgDetector> fvgDetectors;
    private final Map<Timeframe, OrderBlockDetector> obDetectors;
    private final Map<Timeframe, IctStructureDetector> structureDetectors;
    private final Map<Timeframe, DisplacementDetector> displacementDetectors;
    private final Map<Timeframe, BreakerBlockDetector> breakerDetectors;

    // Timeframes for each type of analysis
    private static final Timeframe[] FVG_TIMEFRAMES = {Timeframe.M1, Timeframe.M5, Timeframe.M15};
    private static final Timeframe[] OB_TIMEFRAMES = {Timeframe.M1, Timeframe.M3, Timeframe.M5};
    private static final Timeframe[] STRUCTURE_TIMEFRAMES = {Timeframe.M15, Timeframe.M30, Timeframe.H1};
    private static final Timeframe[] DISPLACEMENT_TIMEFRAMES = {Timeframe.M1, Timeframe.M3, Timeframe.M5};

    // Configuration
    private final int fvgLookback;
    private final int obLookback;
    private final int structureLookback;

    public MultiTimeframeAnalyzer(String symbol, BarAggregationManager barManager) {
        this(symbol, barManager, 20, 30, 50);
    }

    public MultiTimeframeAnalyzer(String symbol, BarAggregationManager barManager,
                                   int fvgLookback, int obLookback, int structureLookback) {
        this.symbol = symbol;
        this.barManager = barManager;
        this.fvgLookback = fvgLookback;
        this.obLookback = obLookback;
        this.structureLookback = structureLookback;

        // Initialize detectors for each timeframe
        this.fvgDetectors = new EnumMap<>(Timeframe.class);
        this.obDetectors = new EnumMap<>(Timeframe.class);
        this.structureDetectors = new EnumMap<>(Timeframe.class);
        this.displacementDetectors = new EnumMap<>(Timeframe.class);
        this.breakerDetectors = new EnumMap<>(Timeframe.class);

        // Create FVG detectors
        for (Timeframe tf : FVG_TIMEFRAMES) {
            fvgDetectors.put(tf, new FvgDetector(fvgLookback));
        }

        // Create OB detectors
        for (Timeframe tf : OB_TIMEFRAMES) {
            OrderBlockDetector obDetector = new OrderBlockDetector(obLookback, 10);
            obDetectors.put(tf, obDetector);
            breakerDetectors.put(tf, new BreakerBlockDetector(obDetector, 10));
        }

        // Create structure detectors for HTF
        for (Timeframe tf : STRUCTURE_TIMEFRAMES) {
            structureDetectors.put(tf, new IctStructureDetector(structureLookback));
        }

        // Create displacement detectors
        for (Timeframe tf : DISPLACEMENT_TIMEFRAMES) {
            displacementDetectors.put(tf, new DisplacementDetector(20));
        }
    }

    /**
     * Update all detectors with newly completed candles.
     * Call this after barManager.processCandle().
     */
    public void update(Map<Timeframe, Candle> completedCandles) {
        for (Map.Entry<Timeframe, Candle> entry : completedCandles.entrySet()) {
            Timeframe tf = entry.getKey();
            Candle candle = entry.getValue();

            // Update FVG detectors
            if (fvgDetectors.containsKey(tf)) {
                fvgDetectors.get(tf).update(candle);
            }

            // Update OB detectors
            if (obDetectors.containsKey(tf)) {
                obDetectors.get(tf).update(candle);
                breakerDetectors.get(tf).update(candle);
            }

            // Update structure detectors
            if (structureDetectors.containsKey(tf)) {
                structureDetectors.get(tf).update(candle);
            }

            // Update displacement detectors
            if (displacementDetectors.containsKey(tf)) {
                displacementDetectors.get(tf).update(candle);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HTF BIAS ANALYSIS (30m + 15m must align)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Get HTF bias from 30m structure.
     */
    public MarketBias getHtfBias30m() {
        IctStructureDetector detector = structureDetectors.get(Timeframe.M30);
        return detector != null ? detector.getBias() : MarketBias.NEUTRAL;
    }

    /**
     * Get HTF bias from 15m structure.
     */
    public MarketBias getHtfBias15m() {
        IctStructureDetector detector = structureDetectors.get(Timeframe.M15);
        return detector != null ? detector.getBias() : MarketBias.NEUTRAL;
    }

    /**
     * Get HTF bias from 1h structure.
     */
    public MarketBias getHtfBias1h() {
        IctStructureDetector detector = structureDetectors.get(Timeframe.H1);
        return detector != null ? detector.getBias() : MarketBias.NEUTRAL;
    }

    /**
     * Check if HTF bias aligns (30m and 15m agree).
     * This is a strong confirmation for trade direction.
     */
    public boolean hasHtfBiasAlignment() {
        MarketBias bias30m = getHtfBias30m();
        MarketBias bias15m = getHtfBias15m();

        // Both must be non-neutral and matching
        return bias30m != MarketBias.NEUTRAL &&
               bias15m != MarketBias.NEUTRAL &&
               bias30m == bias15m;
    }

    /**
     * Check if HTF bias aligns with a specific direction.
     */
    public boolean htfBiasAligns(boolean bullish) {
        MarketBias expected = bullish ? MarketBias.BULLISH : MarketBias.BEARISH;
        return getHtfBias30m() == expected && getHtfBias15m() == expected;
    }

    /**
     * Get the aligned HTF bias (returns NEUTRAL if not aligned).
     */
    public MarketBias getAlignedHtfBias() {
        if (hasHtfBiasAlignment()) {
            return getHtfBias30m();
        }
        return MarketBias.NEUTRAL;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MULTI-TIMEFRAME FVG DETECTION (15m and 5m for quality)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Find HTF FVG on 15m timeframe.
     * These are high-quality zones with excellent fill probability.
     */
    public FairValueGap findHtfFvg15m(double price, boolean bullish, double maxDistance) {
        FvgDetector detector = fvgDetectors.get(Timeframe.M15);
        if (detector == null) return null;
        return detector.findNearestFvg(price, bullish, maxDistance);
    }

    /**
     * Find HTF unfilled FVG on 15m timeframe.
     */
    public FairValueGap findHtfUnfilledFvg15m(double price, boolean bullish, double maxDistance) {
        FvgDetector detector = fvgDetectors.get(Timeframe.M15);
        if (detector == null) return null;
        return detector.findNearestUnfilledFvg(price, bullish, maxDistance);
    }

    /**
     * Find FVG on 5m timeframe.
     * Good-quality zones for standard entries.
     */
    public FairValueGap findFvg5m(double price, boolean bullish, double maxDistance) {
        FvgDetector detector = fvgDetectors.get(Timeframe.M5);
        if (detector == null) return null;
        return detector.findNearestFvg(price, bullish, maxDistance);
    }

    /**
     * Find unfilled FVG on 5m timeframe.
     */
    public FairValueGap findUnfilledFvg5m(double price, boolean bullish, double maxDistance) {
        FvgDetector detector = fvgDetectors.get(Timeframe.M5);
        if (detector == null) return null;
        return detector.findNearestUnfilledFvg(price, bullish, maxDistance);
    }

    /**
     * Find the best FVG across 15m and 5m timeframes.
     * Prefers 15m (higher quality) if available within range.
     */
    public FvgResult findBestHtfFvg(double price, boolean bullish, double maxDistance) {
        // First try 15m (premium quality)
        FairValueGap fvg15m = findHtfUnfilledFvg15m(price, bullish, maxDistance);
        if (fvg15m != null) {
            return new FvgResult(fvg15m, Timeframe.M15, FvgQuality.PREMIUM);
        }

        // Fall back to 5m (standard quality)
        FairValueGap fvg5m = findUnfilledFvg5m(price, bullish, maxDistance);
        if (fvg5m != null) {
            return new FvgResult(fvg5m, Timeframe.M5, FvgQuality.STANDARD);
        }

        return null;
    }

    /**
     * Check if price is in a 15m FVG (premium entry zone).
     */
    public boolean isPriceInHtfFvg(double price, boolean bullish) {
        FvgDetector detector = fvgDetectors.get(Timeframe.M15);
        if (detector == null) return false;

        FairValueGap fvg = detector.findNearestFvg(price, bullish, Double.MAX_VALUE);
        if (fvg == null) return false;

        return price >= fvg.getBottom() && price <= fvg.getTop();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MULTI-TIMEFRAME ORDER BLOCK DETECTION (5m and 3m for quality)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Find Order Block on 5m timeframe.
     * More reliable than 1m OBs.
     */
    public OrderBlock findOb5m(double price, boolean bullish, double maxDistance) {
        OrderBlockDetector detector = obDetectors.get(Timeframe.M5);
        if (detector == null) return null;
        return detector.findNearestValidOb(price, bullish, maxDistance);
    }

    /**
     * Find Order Block on 3m timeframe.
     */
    public OrderBlock findOb3m(double price, boolean bullish, double maxDistance) {
        OrderBlockDetector detector = obDetectors.get(Timeframe.M3);
        if (detector == null) return null;
        return detector.findNearestValidOb(price, bullish, maxDistance);
    }

    /**
     * Find the best Order Block across 5m and 3m timeframes.
     * Prefers 5m (higher quality) if available within range.
     */
    public ObResult findBestHtfOb(double price, boolean bullish, double maxDistance) {
        // First try 5m (premium quality)
        OrderBlock ob5m = findOb5m(price, bullish, maxDistance);
        if (ob5m != null) {
            return new ObResult(ob5m, Timeframe.M5, ObQuality.PREMIUM);
        }

        // Fall back to 3m (standard quality)
        OrderBlock ob3m = findOb3m(price, bullish, maxDistance);
        if (ob3m != null) {
            return new ObResult(ob3m, Timeframe.M3, ObQuality.STANDARD);
        }

        return null;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MULTI-TIMEFRAME BREAKER DETECTION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Find Breaker Block on 5m timeframe.
     */
    public BreakerBlock findBreaker5m(double price, boolean bullish, double maxDistance) {
        BreakerBlockDetector detector = breakerDetectors.get(Timeframe.M5);
        if (detector == null) return null;
        return detector.findNearestBreaker(price, bullish, maxDistance);
    }

    /**
     * Find Breaker Block on 3m timeframe.
     */
    public BreakerBlock findBreaker3m(double price, boolean bullish, double maxDistance) {
        BreakerBlockDetector detector = breakerDetectors.get(Timeframe.M3);
        if (detector == null) return null;
        return detector.findNearestBreaker(price, bullish, maxDistance);
    }

    /**
     * Find the best Breaker across timeframes.
     */
    public BreakerBlock findBestBreaker(double price, boolean bullish, double maxDistance) {
        BreakerBlock breaker5m = findBreaker5m(price, bullish, maxDistance);
        if (breaker5m != null) return breaker5m;

        return findBreaker3m(price, bullish, maxDistance);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MULTI-TIMEFRAME DISPLACEMENT DETECTION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Check for displacement on 5m timeframe (stronger confirmation).
     */
    public boolean hasDisplacement5m(int lookback, boolean bullish) {
        DisplacementDetector detector = displacementDetectors.get(Timeframe.M5);
        if (detector == null) return false;
        return detector.hasRecentDisplacement(lookback, bullish);
    }

    /**
     * Check for displacement on 3m timeframe.
     */
    public boolean hasDisplacement3m(int lookback, boolean bullish) {
        DisplacementDetector detector = displacementDetectors.get(Timeframe.M3);
        if (detector == null) return false;
        return detector.hasRecentDisplacement(lookback, bullish);
    }

    /**
     * Check for multi-timeframe displacement confirmation.
     * Returns true if displacement exists on multiple timeframes.
     */
    public boolean hasConfirmedDisplacement(boolean bullish) {
        boolean disp5m = hasDisplacement5m(5, bullish);
        boolean disp3m = hasDisplacement3m(8, bullish);
        return disp5m || disp3m;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HTF SWING LEVELS (for liquidity targets)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Get 30m swing high (major resistance/liquidity).
     */
    public Double getSwingHigh30m(int lookback) {
        if (!barManager.hasEnoughCandles(Timeframe.M30, lookback)) return null;
        return barManager.getSwingHigh(Timeframe.M30, lookback);
    }

    /**
     * Get 30m swing low (major support/liquidity).
     */
    public Double getSwingLow30m(int lookback) {
        if (!barManager.hasEnoughCandles(Timeframe.M30, lookback)) return null;
        return barManager.getSwingLow(Timeframe.M30, lookback);
    }

    /**
     * Get 15m swing high.
     */
    public Double getSwingHigh15m(int lookback) {
        if (!barManager.hasEnoughCandles(Timeframe.M15, lookback)) return null;
        return barManager.getSwingHigh(Timeframe.M15, lookback);
    }

    /**
     * Get 15m swing low.
     */
    public Double getSwingLow15m(int lookback) {
        if (!barManager.hasEnoughCandles(Timeframe.M15, lookback)) return null;
        return barManager.getSwingLow(Timeframe.M15, lookback);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HTF CONFLUENCE SCORING
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Calculate HTF confluence score for a trade setup.
     * Higher score = more HTF confirmation = higher probability.
     *
     * @param price Current price
     * @param bullish Trade direction
     * @param maxDistance Maximum distance for zone detection
     * @return Score from 0 to 10
     */
    public HtfConfluenceResult calculateHtfConfluence(double price, boolean bullish, double maxDistance) {
        int score = 0;
        List<String> confluences = new ArrayList<>();

        // HTF Bias Alignment (30m + 15m) - Major factor (+3)
        if (htfBiasAligns(bullish)) {
            score += 3;
            confluences.add("30m+15m Bias Aligned");
        } else if (getHtfBias15m() == (bullish ? MarketBias.BULLISH : MarketBias.BEARISH)) {
            score += 1;
            confluences.add("15m Bias Aligned");
        }

        // 15m FVG present (+2)
        FairValueGap fvg15m = findHtfUnfilledFvg15m(price, bullish, maxDistance);
        if (fvg15m != null) {
            score += 2;
            confluences.add("15m FVG");
        }

        // 5m FVG present (+1)
        FairValueGap fvg5m = findUnfilledFvg5m(price, bullish, maxDistance);
        if (fvg5m != null) {
            score += 1;
            confluences.add("5m FVG");
        }

        // 5m Order Block (+2)
        OrderBlock ob5m = findOb5m(price, bullish, maxDistance);
        if (ob5m != null) {
            score += 2;
            confluences.add("5m OB");
        }

        // 5m Breaker (+2)
        BreakerBlock breaker5m = findBreaker5m(price, bullish, maxDistance);
        if (breaker5m != null) {
            score += 2;
            confluences.add("5m Breaker");
        }

        // Multi-TF Displacement (+1)
        if (hasConfirmedDisplacement(bullish)) {
            score += 1;
            confluences.add("MTF Displacement");
        }

        return new HtfConfluenceResult(score, confluences, fvg15m != null ? fvg15m : fvg5m, ob5m, breaker5m);
    }

    /**
     * Check if minimum HTF requirements are met for a tier.
     *
     * Enhanced with 3-Layer Cascade integration:
     * - Tier 4: Requires STRONG HTF trend (30m+15m aligned) + HTF zone (15m FVG or 5m OB)
     * - Tier 3: Requires HTF trending (15m bias aligned) + HTF zone
     * - Tier 2: Requires HTF not opposing + any zone present
     * - Tier 1: Requires HTF not opposing
     *
     * Note: The HtfTrendAnalyzer gate (Layer 1) should have already filtered
     * before reaching this method. This provides additional zone-level requirements.
     */
    public boolean meetsHtfRequirements(TradeTier tier, boolean bullish, double price, double maxDistance) {
        switch (tier) {
            case TIER_4:
                // Elite: Must have 30m+15m bias alignment AND (15m FVG OR 5m OB)
                // The HtfTrendAnalyzer should report STRONG_BULLISH or STRONG_BEARISH
                return htfBiasAligns(bullish) &&
                       (findHtfUnfilledFvg15m(price, bullish, maxDistance) != null ||
                        findOb5m(price, bullish, maxDistance) != null);

            case TIER_3:
                // Premium: Must have 15m bias aligned AND (15m FVG OR 5m FVG OR 5m OB)
                // OR: 30m+15m aligned (even without specific zone)
                MarketBias bias15m = getHtfBias15m();
                boolean bias15mOk = (bullish && bias15m == MarketBias.BULLISH) ||
                                    (!bullish && bias15m == MarketBias.BEARISH);

                if (bias15mOk) {
                    // Has aligned bias — check for zone OR strong bias alignment
                    if (findHtfUnfilledFvg15m(price, bullish, maxDistance) != null ||
                        findUnfilledFvg5m(price, bullish, maxDistance) != null ||
                        findOb5m(price, bullish, maxDistance) != null) {
                        return true;
                    }
                    // Allow if 30m+15m strongly aligned (trend continuation)
                    return htfBiasAligns(bullish);
                }
                return false;

            case TIER_2:
                // Standard: Must have 15m bias non-opposing AND (5m FVG OR 3m/5m OB)
                // Also accepts if continuation zone present (cascade Layer 2)
                MarketBias bias15mT2 = getHtfBias15m();
                boolean bias15mNotOpposing = (bullish && bias15mT2 != MarketBias.BEARISH) ||
                                              (!bullish && bias15mT2 != MarketBias.BULLISH);
                return bias15mNotOpposing &&
                       (findUnfilledFvg5m(price, bullish, maxDistance) != null ||
                        findBestHtfOb(price, bullish, maxDistance) != null ||
                        hasConfirmedDisplacement(bullish));

            case TIER_1:
            default:
                // Confirmed: 15m bias must not be opposing
                MarketBias bias15mT1 = getHtfBias15m();
                return (bullish && bias15mT1 != MarketBias.BEARISH) ||
                       (!bullish && bias15mT1 != MarketBias.BULLISH);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RESULT CLASSES
    // ═══════════════════════════════════════════════════════════════════════════

    public enum FvgQuality {
        PREMIUM,    // 15m FVG - highest quality
        STANDARD,   // 5m FVG - good quality
        BASIC       // 1m FVG - execution quality
    }

    public enum ObQuality {
        PREMIUM,    // 5m OB - highest quality
        STANDARD,   // 3m OB - good quality
        BASIC       // 1m OB - execution quality
    }

    public static class FvgResult {
        public final FairValueGap fvg;
        public final Timeframe timeframe;
        public final FvgQuality quality;

        public FvgResult(FairValueGap fvg, Timeframe timeframe, FvgQuality quality) {
            this.fvg = fvg;
            this.timeframe = timeframe;
            this.quality = quality;
        }
    }

    public static class ObResult {
        public final OrderBlock ob;
        public final Timeframe timeframe;
        public final ObQuality quality;

        public ObResult(OrderBlock ob, Timeframe timeframe, ObQuality quality) {
            this.ob = ob;
            this.timeframe = timeframe;
            this.quality = quality;
        }
    }

    public static class HtfConfluenceResult {
        public final int score;
        public final List<String> confluences;
        public final FairValueGap htfFvg;
        public final OrderBlock htfOb;
        public final BreakerBlock htfBreaker;

        public HtfConfluenceResult(int score, List<String> confluences,
                                    FairValueGap htfFvg, OrderBlock htfOb, BreakerBlock htfBreaker) {
            this.score = score;
            this.confluences = confluences;
            this.htfFvg = htfFvg;
            this.htfOb = htfOb;
            this.htfBreaker = htfBreaker;
        }

        public boolean hasHtfConfluence() {
            return score >= 3;
        }

        public boolean hasStrongHtfConfluence() {
            return score >= 5;
        }

        public boolean hasPremiumHtfConfluence() {
            return score >= 7;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HTF CONFIRMATION CHECK (MANDATORY for all trades)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Check for Higher Timeframe (HTF) confirmation - MANDATORY for trade entry.
     *
     * This method checks multiple HTF confirmation types in order of priority:
     * 1. 15-minute FVG in trade direction (highest priority)
     * 2. 5-minute FVG in trade direction
     * 3. 5-minute Order Block in trade direction
     * 4. HTF Market Structure Shift aligned
     * 5. HTF Bias aligned with trade direction
     *
     * At least ONE of these must be present for a valid trade.
     *
     * @param symbol The instrument symbol
     * @param bullish True for bullish entries, false for bearish
     * @return HtfConfirmationResult with confirmation status and details
     */
    public HtfConfirmationResult checkHtfConfirmation(String symbol, boolean bullish) {
        double maxDistance = 100.0;  // Maximum distance to search for zones

        // Priority 1: Check 15m FVG (Premium HTF confirmation)
        FairValueGap fvg15m = findHtfUnfilledFvg15m(Double.MAX_VALUE, bullish, maxDistance);
        if (fvg15m != null) {
            return HtfConfirmationResult.confirmed("15mFVG", "15-minute FVG aligned with trade direction");
        }

        // Priority 2: Check 5m FVG (Standard HTF confirmation)
        FairValueGap fvg5m = findUnfilledFvg5m(Double.MAX_VALUE, bullish, maxDistance);
        if (fvg5m != null) {
            return HtfConfirmationResult.confirmed("5mFVG", "5-minute FVG aligned with trade direction");
        }

        // Priority 3: Check 5m Order Block
        OrderBlock ob5m = findOb5m(Double.MAX_VALUE, bullish, maxDistance);
        if (ob5m != null) {
            return HtfConfirmationResult.confirmed("5mOB", "5-minute Order Block aligned with trade direction");
        }

        // Priority 4: Check HTF Market Structure Shift
        // An MSS in the trade direction on 15m+ is strong confirmation
        IctStructureDetector struct15m = structureDetectors.get(Timeframe.M15);
        if (struct15m != null) {
            MarketBias bias15m = struct15m.getBias();
            MarketBias expectedBias = bullish ? MarketBias.BULLISH : MarketBias.BEARISH;
            if (bias15m == expectedBias && struct15m.hasRecentMss(10)) {
                return HtfConfirmationResult.confirmed("HTF_MSS", "15-minute Market Structure Shift in trade direction");
            }
        }

        // Priority 5: Check HTF Bias alignment (30m + 15m)
        if (htfBiasAligns(bullish)) {
            return HtfConfirmationResult.confirmed("HTF_BIAS", "HTF Bias (30m+15m) aligned with trade direction");
        }

        // Priority 6: Check single 15m bias alignment (weaker but acceptable)
        MarketBias bias15m = getHtfBias15m();
        MarketBias expectedBias = bullish ? MarketBias.BULLISH : MarketBias.BEARISH;
        if (bias15m == expectedBias) {
            return HtfConfirmationResult.confirmed("15m_BIAS", "15-minute bias aligned with trade direction");
        }

        // No HTF confirmation found
        return HtfConfirmationResult.notConfirmed(
                "No HTF confirmation found. Checked: 15mFVG, 5mFVG, 5mOB, HTF_MSS, HTF_BIAS - none confirmed.");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 3-LAYER CASCADE GATING (NEW — Sequential decision framework)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * LAYER 1 CHECK: Evaluate HTF directional bias gate.
     *
     * This is the FIRST and MOST IMPORTANT check in the cascade.
     * If HTF is not trending, NO trades are taken regardless of lower-TF setups.
     *
     * @param htfTrendAnalyzer The HTF trend state machine
     * @param bullish Desired trade direction
     * @return true if Layer 1 passes (HTF allows this direction)
     */
    public boolean passesLayer1Gate(HtfTrendAnalyzer htfTrendAnalyzer, boolean bullish) {
        if (htfTrendAnalyzer == null) {
            // Fallback to existing bias check if HTF analyzer not available
            return htfBiasAligns(bullish) ||
                   (bullish ? getHtfBias15m() != MarketBias.BEARISH :
                              getHtfBias15m() != MarketBias.BULLISH);
        }

        // Primary gate: HTF trend must allow this direction
        return htfTrendAnalyzer.allowsDirection(bullish);
    }

    /**
     * LAYER 2 CHECK: Evaluate 5m continuation zone.
     *
     * Only called when Layer 1 passes. Checks if the 5m has pulled back
     * to a confluent zone with 2+ overlapping structures.
     *
     * @param continuationDetector The 5m continuation pattern detector
     * @param bullish Desired trade direction
     * @return true if Layer 2 passes (valid pullback zone identified)
     */
    public boolean passesLayer2Gate(ContinuationPatternDetector continuationDetector, boolean bullish) {
        if (continuationDetector == null) {
            // Fallback: check for any HTF zone
            return true;  // Don't block if detector unavailable
        }

        // Check for valid or activated continuation zone
        return continuationDetector.hasValidZone(bullish) ||
               continuationDetector.hasActivatedZone(bullish);
    }

    /**
     * LAYER 3 CHECK: Evaluate 1m displacement entry trigger.
     *
     * Only called when Layer 2 passes. Checks for displacement on 1m
     * that creates an FVG and breaks a swing point (MSS).
     *
     * @param displacementDetector The 1m displacement detector
     * @param bullish Desired trade direction
     * @param lookback How many candles back to check
     * @return true if Layer 3 passes (displacement entry trigger fired)
     */
    public boolean passesLayer3Gate(DisplacementDetector displacementDetector,
                                     boolean bullish, int lookback) {
        if (displacementDetector == null) {
            return false;
        }

        // Full Layer 3: displacement + FVG + MSS
        if (displacementDetector.hasLayer3EntryTrigger(lookback, bullish)) {
            return true;
        }

        // Acceptable fallback: displacement without full FVG/MSS
        // (allows trades when displacement is clear but FVG gap is tight)
        return displacementDetector.hasRecentDisplacement(lookback, bullish);
    }

    /**
     * Run the full 3-layer cascade check.
     *
     * @param htfTrendAnalyzer Layer 1: HTF trend state
     * @param continuationDetector Layer 2: 5m continuation zone
     * @param displacementDetector Layer 3: 1m displacement trigger
     * @param bullish Desired trade direction
     * @return CascadeResult with pass/fail and which layer blocked
     */
    public CascadeResult evaluateThreeLayerCascade(
            HtfTrendAnalyzer htfTrendAnalyzer,
            ContinuationPatternDetector continuationDetector,
            DisplacementDetector displacementDetector,
            boolean bullish) {

        // Layer 1: HTF Trend Direction
        if (!passesLayer1Gate(htfTrendAnalyzer, bullish)) {
            String htfState = htfTrendAnalyzer != null ?
                    htfTrendAnalyzer.getTrendState().getDisplayName() : "UNKNOWN";
            return CascadeResult.blocked(1, "HTF trend does not allow " +
                    (bullish ? "LONGS" : "SHORTS") + " (state=" + htfState + ")");
        }

        // Layer 2: 5m Continuation Zone
        if (!passesLayer2Gate(continuationDetector, bullish)) {
            return CascadeResult.blocked(2, "No 5m continuation zone identified for " +
                    (bullish ? "bullish" : "bearish") + " pullback");
        }

        // Layer 3: 1m Displacement Entry
        if (!passesLayer3Gate(displacementDetector, bullish, 10)) {
            return CascadeResult.blocked(3, "No 1m displacement trigger for " +
                    (bullish ? "bullish" : "bearish") + " entry");
        }

        // All layers passed
        boolean isFullTrigger = displacementDetector.hasLayer3EntryTrigger(10, bullish);
        int zoneScore = continuationDetector != null ?
                continuationDetector.getZoneConfluenceScore(bullish) : 0;
        boolean strongTrend = htfTrendAnalyzer != null &&
                htfTrendAnalyzer.isStrongTrend(bullish);

        return CascadeResult.passed(strongTrend, zoneScore, isFullTrigger);
    }

    /**
     * Result of the 3-layer cascade evaluation.
     */
    public static class CascadeResult {
        private final boolean passed;
        private final int blockedAtLayer;  // 0 = not blocked, 1-3 = which layer blocked
        private final String blockReason;
        private final boolean strongHtfTrend;
        private final int zoneConfluenceScore;
        private final boolean fullEntryTrigger;

        private CascadeResult(boolean passed, int blockedAtLayer, String blockReason,
                              boolean strongHtfTrend, int zoneConfluenceScore, boolean fullEntryTrigger) {
            this.passed = passed;
            this.blockedAtLayer = blockedAtLayer;
            this.blockReason = blockReason;
            this.strongHtfTrend = strongHtfTrend;
            this.zoneConfluenceScore = zoneConfluenceScore;
            this.fullEntryTrigger = fullEntryTrigger;
        }

        public static CascadeResult blocked(int layer, String reason) {
            return new CascadeResult(false, layer, reason, false, 0, false);
        }

        public static CascadeResult passed(boolean strongTrend, int zoneScore, boolean fullTrigger) {
            return new CascadeResult(true, 0, null, strongTrend, zoneScore, fullTrigger);
        }

        public boolean isPassed() { return passed; }
        public boolean isBlocked() { return !passed; }
        public int getBlockedAtLayer() { return blockedAtLayer; }
        public String getBlockReason() { return blockReason; }
        public boolean isStrongHtfTrend() { return strongHtfTrend; }
        public int getZoneConfluenceScore() { return zoneConfluenceScore; }
        public boolean isFullEntryTrigger() { return fullEntryTrigger; }

        @Override
        public String toString() {
            if (passed) {
                return String.format("CASCADE PASSED [strong=%s, zone=%d, fullTrigger=%s]",
                        strongHtfTrend, zoneConfluenceScore, fullEntryTrigger);
            }
            return String.format("CASCADE BLOCKED at Layer %d: %s", blockedAtLayer, blockReason);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RESET
    // ═══════════════════════════════════════════════════════════════════════════

    public void reset() {
        // Note: Individual detectors don't have reset methods, but their internal
        // state is managed by the candles they process. The barManager reset
        // handles the candle storage.
    }
}
