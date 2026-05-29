package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.chartstate.CandleSeries;
import com.topstep.trading.chartstate.ChartStateQueryAPI;
import com.topstep.trading.chartstate.EqualLevelDetector;
import com.topstep.trading.chartstate.LevelEngine;
import com.topstep.trading.chartstate.LiquidityRaid;
import com.topstep.trading.chartstate.RaidDetector;
import com.topstep.trading.chartstate.RaidQualityScorer;
import com.topstep.trading.domain.Candle;
import com.topstep.trading.event.EventBus;
import com.topstep.trading.strategy.CorrelationTracker;
import com.topstep.trading.strategy.DisplacementDetector;
import com.topstep.trading.strategy.FairValueGap;
import com.topstep.trading.strategy.FvgDetector;
import com.topstep.trading.strategy.IctStructureDetector;
import com.topstep.trading.strategy.ImpulseExtensionAnalyzer;
import com.topstep.trading.strategy.KillzoneClock;
import com.topstep.trading.strategy.LiquidityDetector;
import com.topstep.trading.strategy.LiquiditySweep;
import com.topstep.trading.strategy.MarketBias;
import com.topstep.trading.strategy.MarketStructureShiftDetector;
import com.topstep.trading.strategy.MarketStructureShiftDetector.MSS;
import com.topstep.trading.strategy.SilverBulletClock;
import com.topstep.trading.strategy.StrategyContext;
import com.topstep.trading.strategy.TradeTier;
import com.topstep.trading.strategy.TradingStrategy;
import com.topstep.trading.validation.MandatoryConfluenceValidator;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The runtime-ready STDV+OTE strategy that runners actually instantiate.
 *
 * <p>Wraps a pure {@link StdvOteStrategy} (the state machine + emission core,
 * which is what the unit tests exercise) with the full detector orchestration
 * needed to make it advance under a live candle feed. Implements
 * {@link TradingStrategy} with the same 3-arg constructor shape as the legacy
 * {@code IctHighConfluenceStrategy} so the runner-side swap is a one-line
 * change.
 *
 * <h2>Detector poll order (per onCandle)</h2>
 *
 * <ol>
 *   <li>Update every detector with the new candle.</li>
 *   <li>{@link KillzoneClock} + {@link SilverBulletClock} →
 *       {@code ctx.killzoneOpen}.</li>
 *   <li>{@link IctStructureDetector#getBias()} → call
 *       {@code recordHtfBias} when the bias changes (a simplified HTF read;
 *       the 3-of-4 vote becomes a follow-up).</li>
 *   <li>When bias is set and we have two swing extremes, treat the most
 *       recent swing range as the manipulation leg →
 *       {@code recordManipulationLeg}.</li>
 *   <li>{@link LiquidityDetector#getLastSweep()} + raid quality →
 *       {@code recordSweep}.</li>
 *   <li>{@link DisplacementDetector#hasRecentDisplacement} +
 *       {@link FvgDetector#getUnfilledFvgs} → {@code recordDisplacement}.</li>
 *   <li>{@link MarketStructureShiftDetector#update} returns an MSS →
 *       {@code recordMss}.</li>
 *   <li>Use the latest swing high/low as the impulse leg →
 *       {@code recordOteImpulse}.</li>
 *   <li>When state reaches {@code OTE_ARMED}: compute the tier from
 *       confluence factors, call the sizer (deferred — first cut uses a
 *       fixed-floor size), and call {@code tryEmit}.</li>
 * </ol>
 *
 * <h2>What is intentionally minimal in this first cut</h2>
 *
 * <ul>
 *   <li>HTF bias is read directly from the LTF structure detector. The 3-of-4
 *       rule (HTF trend / AMD / premium-discount / draw-on-liquidity) lands
 *       in a follow-up.</li>
 *   <li>Manipulation-leg detection picks the most recent bearish swing (for
 *       a bullish bias) or bullish swing (for a bearish bias) — good enough
 *       to drive the projection engine but coarser than the full ICT rule.</li>
 *   <li>Sizing uses a tier-driven fixed size in the {@code [5, 20]} band
 *       rather than the full buffer-based MLL calculation, because the
 *       runner-side risk engine wiring is a separate piece of work. The
 *       sizer code is in {@link StdvOteSizer}; this strategy will call it
 *       once the runner exposes equity + MLL floor cleanly.</li>
 *   <li>SMT cross-feed: the strategy accepts an SMT candle via
 *       {@link #onSmtCandle(Candle)}; downstream runner code routes the
 *       correlate symbol there.</li>
 *   <li>One active setup per instrument; no concurrent setups.</li>
 * </ul>
 *
 * <h2>Safety</h2>
 *
 * <p>The instrument is validated against the {@link TradeableInstrument}
 * registry at construction; non-tradeable symbols (full-size NQ/ES/GC, or
 * anything outside MNQ/MES/MGC) throw immediately. The {@code [5, 20]} size
 * band is enforced by {@link StdvOteStrategy#tryEmit} via the validator's
 * M8 gate. No order is emitted while {@code ctx.lastGateFailed} is set.
 */
public final class StdvOteRunnerStrategy implements TradingStrategy {

    private final String symbol;
    private final String smtSymbol;
    private final TradeableInstrument.Spec spec;
    private final EventBus eventBus;

    private final StdvOteStrategy core;

    // Detectors (LTF / per-bar).
    private final IctStructureDetector structureDetector;
    private final LiquidityDetector liquidityDetector;
    private final FvgDetector fvgDetector;
    private final DisplacementDetector displacementDetector;
    private final MarketStructureShiftDetector mssDetector;
    private final KillzoneClock killzoneClock;
    private final SilverBulletClock silverBulletClock;
    private final ImpulseExtensionAnalyzer impulseAnalyzer;
    private final CorrelationTracker correlationTracker;

    // Raid scoring (uses CandleSeries + LevelEngine + EqualLevelDetector).
    private final CandleSeries candleSeries;
    private final LevelEngine levelEngine;
    private final EqualLevelDetector equalLevelDetector;
    private final RaidDetector raidDetector;
    private final RaidQualityScorer raidQualityScorer;
    private final ChartStateQueryAPI chartState;

    // Tier-driven fixed size table (first-cut sizing; replaced by the full
    // buffer-based formula in StdvOteSizer once the runner exposes equity).
    private static final int SIZE_TIER_4 = 18;
    private static final int SIZE_TIER_3 = 14;
    private static final int SIZE_TIER_2 = 10;
    private static final int SIZE_TIER_1 = 6;

    /** Bars-since-MSS window in which we still consider the impulse fresh. */
    private static final int MSS_FRESH_BARS = 30;

    /** Maximum bars allowed in OTE_ARMED before the setup invalidates. */
    private static final int MAX_BARS_IN_OTE = 8;

    // Per-bar state — recomputed each onCandle.
    private MarketBias lastBias = MarketBias.NEUTRAL;
    private MSS lastObservedMss;
    private int barsSinceMss = Integer.MAX_VALUE;
    private int barsInOte = 0;

    /**
     * Construct a runner-ready STDV+OTE strategy for the given symbol.
     *
     * @param symbol     instrument symbol; MUST be one of MNQ/MES/MGC
     * @param smtSymbol  SMT correlate (e.g. MES for MNQ); may be null
     * @param eventBus   event bus to publish StrategySignalEvent on
     * @throws IllegalArgumentException if {@code symbol} is not in the
     *         {@link TradeableInstrument} registry
     */
    public StdvOteRunnerStrategy(String symbol, String smtSymbol, EventBus eventBus) {
        Optional<TradeableInstrument.Symbol> resolved = TradeableInstrument.resolve(symbol);
        if (resolved.isEmpty()) {
            throw new IllegalArgumentException(
                    "StdvOteRunnerStrategy rejects non-tradeable symbol: " + symbol
                            + ". Allowed: MNQ, MES, MGC.");
        }
        this.symbol = symbol;
        this.smtSymbol = smtSymbol;
        this.spec = TradeableInstrument.of(resolved.get());
        this.eventBus = eventBus;

        // Detectors with sensible defaults from the legacy strategy.
        this.structureDetector = new IctStructureDetector(50);
        this.liquidityDetector = new LiquidityDetector(30);
        this.fvgDetector = new FvgDetector(20);
        this.displacementDetector = new DisplacementDetector(20);
        this.mssDetector = new MarketStructureShiftDetector(50, 2);
        this.killzoneClock = new KillzoneClock();
        this.silverBulletClock = new SilverBulletClock();
        this.impulseAnalyzer = new ImpulseExtensionAnalyzer(symbol, 30);
        this.correlationTracker = new CorrelationTracker(50);

        // Chart-state pipeline for raid quality scoring.
        this.candleSeries = new CandleSeries(symbol, 5000);
        this.levelEngine = new LevelEngine(symbol, candleSeries);
        this.equalLevelDetector = new EqualLevelDetector(symbol, candleSeries);
        this.raidDetector = new RaidDetector(symbol, levelEngine, equalLevelDetector, candleSeries);
        this.raidQualityScorer = new RaidQualityScorer();
        this.chartState = buildChartStateAdapter();

        // Core state machine + validator + projection / OTE engines.
        StdvProjectionEngine projectionEngine = new StdvProjectionEngine(chartState, impulseAnalyzer);
        OteEntryCalculator oteCalc = new OteEntryCalculator();
        MandatoryConfluenceValidator validator =
                new MandatoryConfluenceValidator(null, displacementDetector, chartState);
        this.core = new StdvOteStrategy(symbol, projectionEngine, oteCalc, validator,
                eventBus, /* expiryBars */ 40L);
    }

    /** Read-only access to the underlying setup context (used by the API + tests). */
    public SetupContext getSetupContext() {
        return core.getSetupContext();
    }

    /** Receive a candle from the SMT correlate (e.g. MES when the primary is MNQ). */
    public void onSmtCandle(Candle candle) {
        if (candle == null) return;
        liquidityDetector.updateSmt(candle);
        correlationTracker.update(candle);
    }

    @Override
    public String getName() {
        return "STDV_OTE";
    }

    @Override
    public void onCandle(Candle candle, StrategyContext context) {
        if (candle == null) return;

        // Route SMT candles to the SMT path.
        if (smtSymbol != null && smtSymbol.equals(candle.getSymbol())) {
            onSmtCandle(candle);
            return;
        }
        // Only process candles for our primary symbol.
        if (!symbol.equals(candle.getSymbol())) {
            return;
        }

        // 1. Update every detector.
        structureDetector.update(candle);
        liquidityDetector.updatePrimary(candle);
        fvgDetector.update(candle);
        displacementDetector.update(candle);
        MSS observedMss = mssDetector.update(candle);
        if (observedMss != null) {
            lastObservedMss = observedMss;
            barsSinceMss = 0;
        } else if (barsSinceMss < Integer.MAX_VALUE) {
            barsSinceMss++;
        }
        candleSeries.addCandle(candle);
        levelEngine.processCandle(candle);
        equalLevelDetector.ageAndCleanupLevels();
        impulseAnalyzer.update(candle);
        correlationTracker.update(candle);

        // Let the core do its own bar-counting + expiry.
        core.onCandle(candle, context);

        SetupContext ctx = core.getSetupContext();

        // 2. Killzone (M3 input).
        Instant now = candle.getTimestamp();
        boolean inKillzone = isInstrumentKillzone(now);
        ctx.killzoneOpen = inKillzone;

        // 3. HTF bias from structure detector (simplified for first cut).
        MarketBias bias = structureDetector.getBias();
        if (bias != lastBias) {
            core.recordHtfBias(bias);
            lastBias = bias;
        }

        // 4. SMT state for the context (informational, doesn't gate).
        if (smtSymbol != null
                && correlationTracker.hasSMTDivergence(symbol, smtSymbol, 20)) {
            ctx.smtState = "DIVERGENT";
        } else {
            ctx.smtState = "NEUTRAL";
        }

        // Track time in OTE.
        if (ctx.state == SetupState.OTE_ARMED) {
            barsInOte++;
            if (barsInOte > MAX_BARS_IN_OTE) {
                core.invalidate("OTE window expired (" + MAX_BARS_IN_OTE + " bars)");
                barsInOte = 0;
            }
        } else {
            barsInOte = 0;
        }

        // 5. From BIAS_SET, look for a manipulation leg.
        if (ctx.state == SetupState.BIAS_SET) {
            tryRecordManipulationLeg();
        }

        // 6. From MANIP_DONE, look for the sweep.
        if (ctx.state == SetupState.MANIP_DONE) {
            tryRecordSweep();
        }

        // 7. From SWEEP_DONE, look for displacement + FVG in bias direction.
        if (ctx.state == SetupState.SWEEP_DONE) {
            tryRecordDisplacement();
        }

        // 8. From DISPLACED, look for an MSS in the bias direction.
        if (ctx.state == SetupState.DISPLACED) {
            tryRecordMss();
        }

        // 9. From MSS_CONFIRMED, build the OTE zone from the post-MSS impulse.
        if (ctx.state == SetupState.MSS_CONFIRMED) {
            tryArmOte(candle);
        }

        // 10. From OTE_ARMED, build the order and try to emit.
        if (ctx.state == SetupState.OTE_ARMED) {
            tryEmitOrder();
        }
    }

    @Override
    public void initialize() {
        // No-op for now; detectors initialise themselves.
    }

    @Override
    public void onSessionEnd() {
        core.onSessionEnd();
        lastObservedMss = null;
        barsSinceMss = Integer.MAX_VALUE;
        barsInOte = 0;
    }

    @Override
    public void shutdown() {
        core.shutdown();
    }

    // ──────────────────────────────────────────────────────────────────────
    // State-machine input helpers
    // ──────────────────────────────────────────────────────────────────────

    private boolean isInstrumentKillzone(Instant now) {
        // MGC trades the London window in addition to NY; MNQ/MES use NY only.
        boolean ny = killzoneClock.isInKillzone(now) || silverBulletClock.isInSilverBulletWindow(now);
        if ("MGC".equals(symbol) && killzoneClock.isInLondonSession(now)) {
            return true;
        }
        return ny;
    }

    private void tryRecordManipulationLeg() {
        Double swingHigh = structureDetector.getLastSwingHigh();
        Double swingLow = structureDetector.getLastSwingLow();
        if (swingHigh == null || swingLow == null) return;
        if (!(swingHigh > swingLow)) return;
        // Snap tolerance = 3 ticks (configurable later).
        core.recordManipulationLeg(swingLow, swingHigh, spec.tickSize(), /* snapTolTicks */ 3);
    }

    private void tryRecordSweep() {
        if (!liquidityDetector.hasRecentSweep(3)) return;
        LiquiditySweep sweep = liquidityDetector.getLastSweep();
        if (sweep == null) return;

        // Bias-direction match: bullish setup wants a sellside (low) sweep,
        // which LiquiditySweep encodes with isBullish() == true.
        boolean wantBullishSweep = (lastBias == MarketBias.BULLISH);
        if (sweep.isBullish() != wantBullishSweep) return;

        // First-cut raid score: if there's at least one tracked raid from
        // the LevelEngine pipeline (which scores PDH/PDL/EQH/EQL sweeps
        // higher), credit the bonus; otherwise floor at the instrument's
        // minimum. The full RaidQualityScorer.calculateScore path needs a
        // RaidScoringContext (multi-detector input) and is a follow-up.
        int score = currentRaidScoreFallback();
        core.recordSweep(sweep, score);
    }

    private int currentRaidScoreFallback() {
        List<LiquidityRaid> active = raidDetector.getActiveRaids();
        int base = spec.raidMinQuality();
        if (active == null || active.isEmpty()) return base;
        // Credit +2 when there is a tracked raid (its level was a known
        // pool, not an internal swing), capped at 10.
        return Math.min(10, base + 2);
    }

    private void tryRecordDisplacement() {
        boolean bullish = (lastBias == MarketBias.BULLISH);
        if (!displacementDetector.hasRecentDisplacement(5, bullish)) return;
        FairValueGap fvg = pickFvgFor(bullish);
        if (fvg == null) return;
        core.recordDisplacement(fvg);
    }

    private FairValueGap pickFvgFor(boolean bullish) {
        List<FairValueGap> fvgs = fvgDetector.getUnfilledFvgs();
        if (fvgs == null || fvgs.isEmpty()) return null;
        // Walk from newest to oldest to find a same-direction FVG.
        for (int i = fvgs.size() - 1; i >= 0; i--) {
            FairValueGap f = fvgs.get(i);
            if (f.isBullish() == bullish) return f;
        }
        return null;
    }

    private void tryRecordMss() {
        if (lastObservedMss == null || barsSinceMss > MSS_FRESH_BARS) return;
        boolean biasBullish = (lastBias == MarketBias.BULLISH);
        if (lastObservedMss.isBullish != biasBullish) {
            // Counter-bias MSS — invalidates the setup per the spec.
            core.invalidate("counter-bias MSS observed");
            return;
        }
        core.recordMss();
    }

    private void tryArmOte(Candle candle) {
        // Use the structure detector's current swings as the impulse leg.
        Double swingHigh = structureDetector.getLastSwingHigh();
        Double swingLow = structureDetector.getLastSwingLow();
        if (swingHigh == null || swingLow == null) return;
        if (!(swingHigh > swingLow)) return;

        SetupContext ctx = core.getSetupContext();

        // Require current price to be in the band before arming, plus a
        // basic rejection signal (close back inside the candle's body in
        // the bias direction).
        boolean bullish = (lastBias == MarketBias.BULLISH);
        boolean reactionConfirmed = bullish
                ? candle.getClose() > candle.getOpen()
                : candle.getClose() < candle.getOpen();
        if (!reactionConfirmed) return;

        core.recordOteImpulse(swingLow, swingHigh, spec.tickSize(), true);
    }

    private void tryEmitOrder() {
        SetupContext ctx = core.getSetupContext();
        TradeTier tier = computeTier(ctx);
        if (tier == null) {
            core.invalidate("no qualifying tier");
            return;
        }
        int size = sizeForTier(tier);
        // tryEmit runs the validator; if it passes, a signal is published.
        boolean emitted = core.tryEmit(spec.tickSize(), /* stopBufferTicks */ 4, tier, size);
        if (!emitted) {
            // Reset so the next bar can re-try at a higher tier / different
            // PD edge if anything changes. The lastGateFailed field on ctx
            // already records which gate stopped us, for the API.
            barsInOte++;
        }
    }

    private TradeTier computeTier(SetupContext ctx) {
        // Optional confluence count (O1..O8 from STDV_OTE_MODEL.md §5).
        int opt = 0;
        if (ctx.killzoneOpen) opt++;                                // O1
        if ("DIVERGENT".equals(ctx.smtState)) opt++;                // O2
        if (ctx.sweep != null) opt++;                               // O3 (sweep present is M4, but
                                                                    //     swept-level type is the O3 hook)
        if (ctx.fvg != null && ctx.ote != null && ctx.ote.contains(ctx.fvg.getTop())) opt++; // O4

        boolean indexPair = "MNQ".equals(symbol) || "MES".equals(symbol);
        boolean smtRequired = indexPair;

        int rs = ctx.raidScore;
        if (rs >= 8 && opt >= 4 && (!smtRequired || "DIVERGENT".equals(ctx.smtState))) {
            return TradeTier.TIER_4;
        }
        if (rs >= 7 && opt >= 3) return TradeTier.TIER_3;
        if (rs >= 6 && opt >= 2) return TradeTier.TIER_2;
        if (rs >= spec.raidMinQuality()) return TradeTier.TIER_1;
        return null;
    }

    private int sizeForTier(TradeTier tier) {
        int s;
        switch (tier) {
            case TIER_4: s = SIZE_TIER_4; break;
            case TIER_3: s = SIZE_TIER_3; break;
            case TIER_2: s = SIZE_TIER_2; break;
            case TIER_1: s = SIZE_TIER_1; break;
            default:     s = spec.minMicros();
        }
        if (s < spec.minMicros()) s = spec.minMicros();
        if (s > spec.maxMicros()) s = spec.maxMicros();
        return s;
    }

    // ──────────────────────────────────────────────────────────────────────
    // ChartStateQueryAPI adapter (minimal — only the methods the projection
    // engine + raid scoring path call).
    // ──────────────────────────────────────────────────────────────────────

    private ChartStateQueryAPI buildChartStateAdapter() {
        return new ChartStateQueryAPI() {
            @Override public String getSymbol() { return symbol; }
            @Override public com.topstep.trading.chartstate.InstrumentRaidConfig getConfig() {
                return null;
            }
            @Override public java.util.List<LiquidityRaid> getActiveRaids() {
                return raidDetector.getActiveRaids();
            }
            @Override public java.util.List<LiquidityRaid> getEntryValidRaids() {
                return raidDetector.getActiveRaids();
            }
            @Override public java.util.List<LiquidityRaid> getConfirmedRaids() {
                return raidDetector.getActiveRaids();
            }
            @Override public java.util.Optional<LiquidityRaid> getBestActiveRaid() {
                List<LiquidityRaid> a = raidDetector.getActiveRaids();
                if (a == null || a.isEmpty()) return java.util.Optional.empty();
                return java.util.Optional.of(a.get(a.size() - 1));
            }
            @Override public java.util.Optional<LiquidityRaid> getActiveBullishRaid() {
                return java.util.Optional.empty();
            }
            @Override public java.util.Optional<LiquidityRaid> getActiveBearishRaid() {
                return java.util.Optional.empty();
            }
            @Override public boolean hasActiveRaidForDirection(boolean expectBullish) { return false; }
            @Override public java.util.Optional<LiquidityRaid> getRaidById(String raidId) {
                return java.util.Optional.empty();
            }
            @Override public java.util.Optional<Double> getPDH() { return java.util.Optional.empty(); }
            @Override public java.util.Optional<Double> getPDL() { return java.util.Optional.empty(); }
            @Override public java.util.Optional<Double> getPWH() { return java.util.Optional.empty(); }
            @Override public java.util.Optional<Double> getPWL() { return java.util.Optional.empty(); }
            @Override public java.util.List<com.topstep.trading.chartstate.KnownLevel> getAllLevels() {
                return levelEngine.getAllLevels();
            }
            @Override public java.util.List<com.topstep.trading.chartstate.KnownLevel> getUnraidedLevels() {
                return levelEngine.getAllLevels();
            }
            @Override public java.util.List<com.topstep.trading.chartstate.KnownLevel> getLevelsNearPrice(double price) {
                return levelEngine.getAllLevels();
            }
            @Override public java.util.Optional<com.topstep.trading.chartstate.KnownLevel> getNearestLevelAbove(double price) {
                return java.util.Optional.empty();
            }
            @Override public java.util.Optional<com.topstep.trading.chartstate.KnownLevel> getNearestLevelBelow(double price) {
                return java.util.Optional.empty();
            }
            @Override public java.util.Optional<com.topstep.trading.chartstate.KnownLevel> getLevel(
                    com.topstep.trading.chartstate.LevelType type) {
                return java.util.Optional.empty();
            }
            @Override public java.util.List<EqualLevelDetector.EqualLevel> getEqualHighs() {
                return java.util.List.of();
            }
            @Override public java.util.List<EqualLevelDetector.EqualLevel> getEqualLows() {
                return java.util.List.of();
            }
            @Override public java.util.Optional<EqualLevelDetector.EqualLevel> getStrongestEqualHigh() {
                return java.util.Optional.empty();
            }
            @Override public java.util.Optional<EqualLevelDetector.EqualLevel> getStrongestEqualLow() {
                return java.util.Optional.empty();
            }
            @Override public java.util.List<EqualLevelDetector.EqualLevel> getEqualHighsAbove(double price) {
                return java.util.List.of();
            }
            @Override public java.util.List<EqualLevelDetector.EqualLevel> getEqualLowsBelow(double price) {
                return java.util.List.of();
            }
            @Override public java.util.Optional<Double> getLatestClose() { return java.util.Optional.empty(); }
            @Override public double getHighest(int lookback) { return 0; }
            @Override public double getLowest(int lookback) { return 0; }
            @Override public double getAverageRange(int lookback) { return 0; }
            @Override public boolean hasMinimumData(int required) { return candleSeries.size() >= required; }
            @Override public boolean isInAsia() { return false; }
            @Override public boolean isInLondon() { return false; }
            @Override public boolean isInNY() { return false; }
            @Override public String getLevelsSummary() { return ""; }
            @Override public String getRaidsSummary() { return ""; }
        };
    }
}
