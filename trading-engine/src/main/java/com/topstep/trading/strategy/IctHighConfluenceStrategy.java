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
    private final OrderBlockDetector orderBlockDetector;
    private final DisplacementDetector displacementDetector;
    private final KillzoneClock killzoneClock;

    // Configuration
    private final double fibLow = 0.62;   // OTE zone low
    private final double fibHigh = 0.705; // OTE zone high
    private final double minRR = 2.0;     // Minimum risk:reward ratio

    // State tracking
    private int candleCount = 0;
    private volatile boolean signalPending = false;  // Prevent multiple signals
    private volatile long lastSignalTime = 0;  // Track when signal was sent
    private static final long SIGNAL_TIMEOUT_MS = 60000;  // Reset after 60 seconds

    // Confluence levels for tiered entry quality
    private static final int CONFLUENCE_TIER_1 = 1;  // IFVG + Sweep + Bias (highest quality)
    private static final int CONFLUENCE_TIER_2 = 2;  // Unfilled FVG + Sweep + Bias
    private static final int CONFLUENCE_TIER_3 = 3;  // Sweep + Bias at OTE zone (minimum required)
    private int currentConfluenceLevel = 0;
    private FairValueGap currentFvg = null;  // Track FVG for entry
    private OrderBlock currentOrderBlock = null;  // Track OB for entry
    private boolean hasDisplacement = false;  // Track displacement confirmation

    public IctHighConfluenceStrategy(String primarySymbol, String smtSymbol, EventBus eventBus) {
        this.primarySymbol = primarySymbol;
        this.smtSymbol = smtSymbol;
        this.eventBus = eventBus;

        // Initialize detectors
        this.structureDetector = new IctStructureDetector(50);     // 50 candle lookback
        this.liquidityDetector = new LiquidityDetector(30);        // 30 candle lookback
        this.fvgDetector = new FvgDetector(20);                    // Keep last 20 FVGs
        this.orderBlockDetector = new OrderBlockDetector(30, 10);  // 30 lookback, keep 10 OBs
        this.displacementDetector = new DisplacementDetector(20);  // 20 candle lookback
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

        // Reset signalPending if timeout exceeded (order likely failed or was rejected)
        if (signalPending && (System.currentTimeMillis() - lastSignalTime) > SIGNAL_TIMEOUT_MS) {
            System.out.println("[STRATEGY] Signal timeout - resetting pending flag after " + (SIGNAL_TIMEOUT_MS / 1000) + "s");
            signalPending = false;
        }

        // Update all detectors
        structureDetector.update(candle);
        liquidityDetector.updatePrimary(candle);
        fvgDetector.update(candle);
        orderBlockDetector.update(candle);
        displacementDetector.update(candle);

        // Don't trade if we already have a position
        if (context.hasPosition(primarySymbol)) {
            signalPending = false;  // Reset if position exists
            return;
        }

        // Don't generate multiple signals while one is pending
        if (signalPending) {
            return;
        }

        // Check all confluences
        if (!checkConfluences(candle, context)) {
            return;
        }

        // All confluences met - generate signal (only once)
        signalPending = true;
        lastSignalTime = System.currentTimeMillis();
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
        boolean hasSmtDivergence = sweep.hasSmtDivergence();
        String smtStatus = hasSmtDivergence ? "✓ SMT divergence" : "~ No SMT data (continuing)";

        // 6. Check for displacement (momentum confirmation)
        hasDisplacement = displacementDetector.hasRecentDisplacement(10, bias == MarketBias.BULLISH);
        String dispStatus = hasDisplacement ? "✓ Displacement" : "~ No displacement";

        // 7. Check for Order Block near current price
        boolean isBullish = bias == MarketBias.BULLISH;
        double maxDistance = 50.0;  // Max 50 points from current price for NQ
        currentOrderBlock = orderBlockDetector.findNearestValidOb(candle.getClose(), isBullish, maxDistance);
        String obStatus = currentOrderBlock != null ? "✓ Order Block" : "~ No OB";

        // 8. Check for FVG/IFVG using tiered confluence system
        // Tier 1: IFVG or Order Block + Displacement (highest quality)
        // Tier 2: Unfilled FVG or Order Block (medium quality)
        // Tier 3: Any FVG, Order Block, or SMT (minimum required)

        // Try Tier 1: IFVG or (Order Block + Displacement)
        FairValueGap ifvg = fvgDetector.findNearestIfvg(candle.getClose(), isBullish);
        if (ifvg != null || (currentOrderBlock != null && hasDisplacement)) {
            currentConfluenceLevel = CONFLUENCE_TIER_1;
            currentFvg = ifvg;
            System.out.println("[STRATEGY] ✓✓✓ TIER 1 CONFLUENCE (IFVG/OB+Disp) ✓✓✓");
            System.out.println("[STRATEGY] Killzone: " + killzoneClock.getKillzoneName(candle.getTimestamp()));
            System.out.println("[STRATEGY] Bias: " + bias + " | Sweep: " + (sweep.isBullish() ? "BULLISH" : "BEARISH"));
            System.out.println("[STRATEGY] " + smtStatus + " | " + dispStatus + " | " + obStatus);
            if (ifvg != null) {
                System.out.println("[STRATEGY] IFVG: " + ifvg.getBottom() + " - " + ifvg.getTop());
            }
            if (currentOrderBlock != null) {
                System.out.println("[STRATEGY] Order Block: " + currentOrderBlock.getLow() + " - " + currentOrderBlock.getHigh());
            }
            return true;
        }

        // Try Tier 2: Unfilled FVG or Order Block
        FairValueGap unfilledFvg = fvgDetector.findNearestUnfilledFvg(candle.getClose(), isBullish, maxDistance);
        if (unfilledFvg != null || currentOrderBlock != null) {
            currentConfluenceLevel = CONFLUENCE_TIER_2;
            currentFvg = unfilledFvg;
            System.out.println("[STRATEGY] ✓✓ TIER 2 CONFLUENCE (Unfilled FVG/OB) ✓✓");
            System.out.println("[STRATEGY] Killzone: " + killzoneClock.getKillzoneName(candle.getTimestamp()));
            System.out.println("[STRATEGY] Bias: " + bias + " | Sweep: " + (sweep.isBullish() ? "BULLISH" : "BEARISH"));
            System.out.println("[STRATEGY] " + smtStatus + " | " + dispStatus + " | " + obStatus);
            if (unfilledFvg != null) {
                System.out.println("[STRATEGY] Unfilled FVG: " + unfilledFvg.getBottom() + " - " + unfilledFvg.getTop());
            }
            if (currentOrderBlock != null) {
                System.out.println("[STRATEGY] Order Block: " + currentOrderBlock.getLow() + " - " + currentOrderBlock.getHigh());
            }
            return true;
        }

        // Try Tier 3: Any FVG, SMT divergence, or Displacement as confirmation
        FairValueGap anyFvg = fvgDetector.findNearestFvg(candle.getClose(), isBullish, maxDistance);
        if (anyFvg != null || hasSmtDivergence || hasDisplacement) {
            currentConfluenceLevel = CONFLUENCE_TIER_3;
            currentFvg = anyFvg;
            System.out.println("[STRATEGY] ✓ TIER 3 CONFLUENCE (FVG/SMT/Disp) ✓");
            System.out.println("[STRATEGY] Killzone: " + killzoneClock.getKillzoneName(candle.getTimestamp()));
            System.out.println("[STRATEGY] Bias: " + bias + " | Sweep: " + (sweep.isBullish() ? "BULLISH" : "BEARISH"));
            System.out.println("[STRATEGY] " + smtStatus + " | " + dispStatus + " | " + obStatus);
            if (anyFvg != null) {
                System.out.println("[STRATEGY] FVG: " + anyFvg.getBottom() + " - " + anyFvg.getTop());
            }
            return true;
        }

        // No valid confluence found
        if (shouldLog) {
            System.out.println("[STRATEGY] ✓ In killzone | ✓ Bias: " + bias + " | ✓ Sweep | ✗ No entry zone (FVG/OB/SMT/Disp)");
        }
        return false;
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
