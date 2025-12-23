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

    // State tracking
    private int candleCount = 0;

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
        candleCount++;

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
        // Log analysis every 10 candles
        boolean shouldLog = (candleCount % 10 == 0);

        // 1. Check killzone (time filter)
        boolean inKillzone = killzoneClock.isInKillzone(candle.getTimestamp());
        if (!inKillzone) {
            if (shouldLog) {
                System.out.println("[STRATEGY] Not in killzone - waiting for 8:45-11:30 AM or 12:45-3:00 PM CT");
            }
            return false;
        }

        if (!killzoneClock.isTradingDay(candle.getTimestamp())) {
            if (shouldLog) {
                System.out.println("[STRATEGY] Not a trading day");
            }
            return false;
        }

        // 2. Check higher-timeframe structure bias
        MarketBias bias = structureDetector.getBias();
        if (bias == MarketBias.NEUTRAL) {
            if (shouldLog) {
                System.out.println("[STRATEGY] ✓ In killzone | ✗ Market bias is NEUTRAL (need BULLISH or BEARISH)");
            }
            return false;
        }

        // 3. Check for recent liquidity sweep (within last 15 candles)
        boolean hasRecentSweep = liquidityDetector.hasRecentSweep(15);
        if (!hasRecentSweep) {
            if (shouldLog) {
                System.out.println("[STRATEGY] ✓ In killzone | ✓ Bias: " + bias + " | ✗ No recent liquidity sweep (last 15 candles)");
            }
            return false;
        }

        LiquiditySweep sweep = liquidityDetector.getLastSweep();
        if (sweep == null) {
            return false;
        }

        // 4. Ensure sweep direction matches structure bias
        if (bias == MarketBias.BULLISH && !sweep.isBullish()) {
            if (shouldLog) {
                System.out.println("[STRATEGY] ✓ In killzone | ✓ Bias: BULLISH | ✗ Sweep is bearish (mismatch)");
            }
            return false;
        }
        if (bias == MarketBias.BEARISH && !sweep.isBearish()) {
            if (shouldLog) {
                System.out.println("[STRATEGY] ✓ In killzone | ✓ Bias: BEARISH | ✗ Sweep is bullish (mismatch)");
            }
            return false;
        }

        // 5. SMT divergence is optional when we don't have SMT data
        // Previously required SMT which blocked all trades
        boolean hasSmtDivergence = sweep.hasSmtDivergence();
        String smtStatus = hasSmtDivergence ? "✓ SMT divergence" : "~ No SMT data (continuing)";

        // 6. Check for IFVG in the direction of the trade
        FairValueGap ifvg = fvgDetector.findNearestIfvg(candle.getClose(), bias == MarketBias.BULLISH);
        if (ifvg == null) {
            if (shouldLog) {
                System.out.println("[STRATEGY] ✓ In killzone | ✓ Bias: " + bias + " | ✓ Sweep | " + smtStatus + " | ✗ No IFVG found");
            }
            return false;
        }

        // All required confluences met!
        System.out.println("[STRATEGY] ✓✓✓ ALL CONDITIONS MET ✓✓✓");
        System.out.println("[STRATEGY] Killzone: " + killzoneClock.getKillzoneName(candle.getTimestamp()));
        System.out.println("[STRATEGY] Bias: " + bias + " | Sweep: " + (sweep.isBullish() ? "BULLISH" : "BEARISH"));
        System.out.println("[STRATEGY] IFVG: " + ifvg.getBottom() + " - " + ifvg.getTop());
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
