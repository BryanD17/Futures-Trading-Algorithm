package com.topstep.trading.strategy;

import com.topstep.trading.domain.Candle;
import com.topstep.trading.domain.OrderSide;
import com.topstep.trading.event.EventBus;
import com.topstep.trading.event.StrategySignalEvent;

/**
 * ICT High-Confluence Strategy combining multiple ICT/SMC concepts:
 * 1. Higher-timeframe market structure (bias)
 * 2. Killzone session filtering
 * 3. Liquidity sweep with SMT divergence
 * 4. IFVG (Inversion Fair Value Gap) for entry zone
 * 5. Fibonacci/OTE for optimal entry pricing
 *
 * This strategy only takes trades when ALL confluences align.
 */
public class IctHighConfluenceStrategy implements TradingStrategy {

    private final String primarySymbol;
    private final String smtSymbol;
    private final EventBus eventBus;

    // Helper components
    private final IctStructureDetector structureDetector;
    private final LiquidityDetector liquidityDetector;
    private final FvgDetector fvgDetector;
    private final KillzoneClock killzoneClock;

    // Configuration
    private final double fibLow = 0.62;   // OTE zone low
    private final double fibHigh = 0.705; // OTE zone high
    private final double minRR = 2.0;     // Minimum risk:reward ratio

    public IctHighConfluenceStrategy(String primarySymbol, String smtSymbol, EventBus eventBus) {
        this.primarySymbol = primarySymbol;
        this.smtSymbol = smtSymbol;
        this.eventBus = eventBus;

        // Initialize detectors
        this.structureDetector = new IctStructureDetector(50);  // 50 candle lookback
        this.liquidityDetector = new LiquidityDetector(30);     // 30 candle lookback
        this.fvgDetector = new FvgDetector(20);                 // Keep last 20 FVGs
        this.killzoneClock = new KillzoneClock();
    }

    @Override
    public void onCandle(Candle candle, StrategyContext context) {
        // Route candle to appropriate detector
        if (candle.getSymbol().equals(primarySymbol)) {
            handlePrimaryCandle(candle, context);
        } else if (candle.getSymbol().equals(smtSymbol)) {
            handleSmtCandle(candle);
        }
    }

    /**
     * Handle candle from primary trading symbol.
     */
    private void handlePrimaryCandle(Candle candle, StrategyContext context) {
        // Update all detectors
        structureDetector.update(candle);
        liquidityDetector.updatePrimary(candle);
        fvgDetector.update(candle);

        // Don't trade if we already have a position
        if (context.hasPosition(primarySymbol)) {
            return;
        }

        // Check all confluences
        if (!checkConfluences(candle, context)) {
            return;
        }

        // All confluences met - generate signal
        generateSignal(candle, context);
    }

    /**
     * Handle candle from SMT (correlated) symbol.
     */
    private void handleSmtCandle(Candle candle) {
        liquidityDetector.updateSmt(candle);
    }

    /**
     * Check if all confluences are met for a high-probability trade.
     */
    private boolean checkConfluences(Candle candle, StrategyContext context) {
        // 1. Check killzone (time filter)
        if (!killzoneClock.isInKillzone(candle.getTimestamp())) {
            return false;
        }

        if (!killzoneClock.isTradingDay(candle.getTimestamp())) {
            return false;
        }

        // 2. Check higher-timeframe structure bias
        MarketBias bias = structureDetector.getBias();
        if (bias == MarketBias.NEUTRAL) {
            return false;
        }

        // 3. Check for recent liquidity sweep
        if (!liquidityDetector.hasRecentSweep(5)) {
            return false;
        }

        LiquiditySweep sweep = liquidityDetector.getLastSweep();
        if (sweep == null) {
            return false;
        }

        // 4. Ensure sweep direction matches structure bias
        if (bias == MarketBias.BULLISH && !sweep.isBullish()) {
            return false;
        }
        if (bias == MarketBias.BEARISH && !sweep.isBearish()) {
            return false;
        }

        // 5. Prefer sweeps with SMT divergence
        if (!sweep.hasSmtDivergence()) {
            // Optional: Could allow trades without SMT but reduce confidence
            // For now, require SMT divergence for high confluence
            return false;
        }

        // 6. Check for IFVG in the direction of the trade
        FairValueGap ifvg = fvgDetector.findNearestIfvg(candle.getClose(), bias == MarketBias.BULLISH);
        if (ifvg == null) {
            return false;
        }

        // All confluences met!
        return true;
    }

    /**
     * Generate a trading signal based on confluences.
     */
    private void generateSignal(Candle candle, StrategyContext context) {
        MarketBias bias = structureDetector.getBias();
        LiquiditySweep sweep = liquidityDetector.getLastSweep();

        if (bias == MarketBias.BULLISH && sweep.isBullish()) {
            generateBullishSignal(candle, sweep, context);
        } else if (bias == MarketBias.BEARISH && sweep.isBearish()) {
            generateBearishSignal(candle, sweep, context);
        }
    }

    /**
     * Generate a bullish (long) signal.
     */
    private void generateBullishSignal(Candle candle, LiquiditySweep sweep, StrategyContext context) {
        // Entry zone: Fibonacci 62%-70.5% retracement from sweep low to recent high
        Double swingHigh = structureDetector.getLastSwingHigh();
        if (swingHigh == null) {
            return;
        }

        double swingLow = sweep.getSweptLevel();
        double range = swingHigh - swingLow;

        // OTE zone (Optimal Trade Entry)
        double entryLow = swingLow + (range * fibLow);
        double entryHigh = swingLow + (range * fibHigh);
        double entry = (entryLow + entryHigh) / 2.0;  // Midpoint of OTE zone

        // Stop: Below the sweep low (or IFVG low)
        FairValueGap ifvg = fvgDetector.findNearestIfvg(candle.getClose(), true);
        double stop = ifvg != null ? Math.min(swingLow, ifvg.getBottom()) - 5.0 : swingLow - 5.0;

        // Target: Recent swing high or liquidity pool above
        double riskDistance = entry - stop;
        double target = entry + (riskDistance * minRR);  // Minimum 2R

        // Ensure valid R:R
        if (riskDistance <= 0 || (target - entry) / riskDistance < minRR) {
            return;
        }

        // Emit signal
        String reason = String.format("Bullish: HT Bias, Liquidity Sweep w/ SMT, IFVG, OTE Zone in %s",
                killzoneClock.getKillzoneName(candle.getTimestamp()));

        StrategySignalEvent signal = new StrategySignalEvent(
                StrategySignalEvent.SignalType.LONG_ENTRY,
                primarySymbol,
                OrderSide.BUY,
                entry,
                stop,
                target,
                reason
        );

        eventBus.publish(signal);
    }

    /**
     * Generate a bearish (short) signal.
     */
    private void generateBearishSignal(Candle candle, LiquiditySweep sweep, StrategyContext context) {
        // Entry zone: Fibonacci 62%-70.5% retracement from sweep high to recent low
        Double swingLow = structureDetector.getLastSwingLow();
        if (swingLow == null) {
            return;
        }

        double swingHigh = sweep.getSweptLevel();
        double range = swingHigh - swingLow;

        // OTE zone (Optimal Trade Entry) - for bearish, we go from high down
        double entryHigh = swingHigh - (range * fibLow);
        double entryLow = swingHigh - (range * fibHigh);
        double entry = (entryLow + entryHigh) / 2.0;  // Midpoint of OTE zone

        // Stop: Above the sweep high (or IFVG high)
        FairValueGap ifvg = fvgDetector.findNearestIfvg(candle.getClose(), false);
        double stop = ifvg != null ? Math.max(swingHigh, ifvg.getTop()) + 5.0 : swingHigh + 5.0;

        // Target: Recent swing low or liquidity pool below
        double riskDistance = stop - entry;
        double target = entry - (riskDistance * minRR);  // Minimum 2R

        // Ensure valid R:R
        if (riskDistance <= 0 || (entry - target) / riskDistance < minRR) {
            return;
        }

        // Emit signal
        String reason = String.format("Bearish: HT Bias, Liquidity Sweep w/ SMT, IFVG, OTE Zone in %s",
                killzoneClock.getKillzoneName(candle.getTimestamp()));

        StrategySignalEvent signal = new StrategySignalEvent(
                StrategySignalEvent.SignalType.SHORT_ENTRY,
                primarySymbol,
                OrderSide.SELL,
                entry,
                stop,
                target,
                reason
        );

        eventBus.publish(signal);
    }

    @Override
    public String getName() {
        return "ICT High Confluence Strategy";
    }

    @Override
    public void initialize() {
        // Strategy initialization
        System.out.println("Initializing " + getName() + " for " + primarySymbol + " (SMT: " + smtSymbol + ")");
    }

    @Override
    public void shutdown() {
        // Cleanup
        System.out.println("Shutting down " + getName());
    }
}
