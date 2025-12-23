package com.topstep.trading.strategy;

import com.topstep.trading.domain.Candle;
import java.util.ArrayList;
import java.util.List;

/**
 * Detects liquidity sweeps and SMT (Smart Money Tool) divergence.
 *
 * Liquidity sweeps: Price runs obvious highs/lows and then rejects.
 * SMT divergence: Correlation between two symbols (e.g., ES and NQ) breaks down,
 * suggesting one instrument is weaker and may reverse.
 */
public class LiquidityDetector {

    private final List<Candle> primaryCandles;
    private final List<Candle> smtCandles;
    private final int lookbackPeriod;

    private LiquiditySweep lastSweep;
    private int candleCountAtLastSweep = 0;  // Track when sweep was detected
    private int totalCandleCount = 0;        // Total candles processed

    public LiquidityDetector(int lookbackPeriod) {
        this.lookbackPeriod = lookbackPeriod;
        this.primaryCandles = new ArrayList<>();
        this.smtCandles = new ArrayList<>();
    }

    /**
     * Update with a new candle from the primary symbol.
     */
    public void updatePrimary(Candle candle) {
        primaryCandles.add(candle);
        totalCandleCount++;
        if (primaryCandles.size() > lookbackPeriod) {
            primaryCandles.remove(0);
        }
        detectLiquiditySweeps();
    }

    /**
     * Update with a new candle from the SMT symbol (correlated instrument).
     */
    public void updateSmt(Candle candle) {
        smtCandles.add(candle);
        if (smtCandles.size() > lookbackPeriod) {
            smtCandles.remove(0);
        }
    }

    /**
     * Detect liquidity sweeps in the primary symbol.
     */
    private void detectLiquiditySweeps() {
        if (primaryCandles.size() < 5) {
            return;
        }

        int size = primaryCandles.size();
        Candle latest = primaryCandles.get(size - 1);
        Candle prev = primaryCandles.get(size - 2);

        // Find recent swing high (liquidity pool)
        double recentHigh = primaryCandles.stream()
                .skip(Math.max(0, size - 10))
                .mapToDouble(Candle::getHigh)
                .max()
                .orElse(0.0);

        // Find recent swing low (liquidity pool)
        double recentLow = primaryCandles.stream()
                .skip(Math.max(0, size - 10))
                .mapToDouble(Candle::getLow)
                .min()
                .orElse(Double.MAX_VALUE);

        // Bullish sweep: price wicks below recent low and then closes back above it
        if (latest.getLow() < recentLow && latest.getClose() > recentLow) {
            boolean hasSmtDiv = checkBullishSmtDivergence(recentLow);
            System.out.println("[LIQUIDITY] BULLISH SWEEP detected at " + recentLow +
                " (low: " + latest.getLow() + ", close: " + latest.getClose() +
                ", SMT: " + hasSmtDiv + ")");
            lastSweep = new LiquiditySweep(true, recentLow, latest.getTimestamp(), hasSmtDiv);
            candleCountAtLastSweep = totalCandleCount;
        }
        // Bearish sweep: price wicks above recent high and then closes back below it
        else if (latest.getHigh() > recentHigh && latest.getClose() < recentHigh) {
            boolean hasSmtDiv = checkBearishSmtDivergence(recentHigh);
            System.out.println("[LIQUIDITY] BEARISH SWEEP detected at " + recentHigh +
                " (high: " + latest.getHigh() + ", close: " + latest.getClose() +
                ", SMT: " + hasSmtDiv + ")");
            lastSweep = new LiquiditySweep(false, recentHigh, latest.getTimestamp(), hasSmtDiv);
            candleCountAtLastSweep = totalCandleCount;
        }
    }

    /**
     * Check for bullish SMT divergence.
     * Primary makes a lower low, but SMT symbol does NOT make a lower low.
     */
    private boolean checkBullishSmtDivergence(double primaryLow) {
        if (smtCandles.isEmpty() || primaryCandles.size() < 10) {
            return false;
        }

        // Find the previous low in primary
        double previousPrimaryLow = primaryCandles.stream()
                .limit(primaryCandles.size() - 2)
                .mapToDouble(Candle::getLow)
                .min()
                .orElse(Double.MAX_VALUE);

        // Check if primary made a lower low
        if (primaryLow >= previousPrimaryLow) {
            return false;
        }

        // Find corresponding lows in SMT symbol
        int smtSize = smtCandles.size();
        if (smtSize < 5) {
            return false;
        }

        double recentSmtLow = smtCandles.stream()
                .skip(Math.max(0, smtSize - 5))
                .mapToDouble(Candle::getLow)
                .min()
                .orElse(Double.MAX_VALUE);

        double previousSmtLow = smtCandles.stream()
                .limit(smtSize - 5)
                .mapToDouble(Candle::getLow)
                .min()
                .orElse(Double.MAX_VALUE);

        // SMT divergence: SMT did NOT make a lower low
        return recentSmtLow > previousSmtLow;
    }

    /**
     * Check for bearish SMT divergence.
     * Primary makes a higher high, but SMT symbol does NOT make a higher high.
     */
    private boolean checkBearishSmtDivergence(double primaryHigh) {
        if (smtCandles.isEmpty() || primaryCandles.size() < 10) {
            return false;
        }

        // Find the previous high in primary
        double previousPrimaryHigh = primaryCandles.stream()
                .limit(primaryCandles.size() - 2)
                .mapToDouble(Candle::getHigh)
                .max()
                .orElse(0.0);

        // Check if primary made a higher high
        if (primaryHigh <= previousPrimaryHigh) {
            return false;
        }

        // Find corresponding highs in SMT symbol
        int smtSize = smtCandles.size();
        if (smtSize < 5) {
            return false;
        }

        double recentSmtHigh = smtCandles.stream()
                .skip(Math.max(0, smtSize - 5))
                .mapToDouble(Candle::getHigh)
                .max()
                .orElse(0.0);

        double previousSmtHigh = smtCandles.stream()
                .limit(smtSize - 5)
                .mapToDouble(Candle::getHigh)
                .max()
                .orElse(0.0);

        // SMT divergence: SMT did NOT make a higher high
        return recentSmtHigh < previousSmtHigh;
    }

    /**
     * Get the last detected liquidity sweep.
     */
    public LiquiditySweep getLastSweep() {
        return lastSweep;
    }

    /**
     * Check if there's a recent liquidity sweep (within last N candles).
     */
    public boolean hasRecentSweep(int withinCandles) {
        if (lastSweep == null || candleCountAtLastSweep == 0) {
            return false;
        }

        // Calculate how many candles have passed since the sweep
        int candlesSinceSweep = totalCandleCount - candleCountAtLastSweep;
        return candlesSinceSweep <= withinCandles;
    }

    /**
     * Reset the detector.
     */
    public void reset() {
        primaryCandles.clear();
        smtCandles.clear();
        lastSweep = null;
        candleCountAtLastSweep = 0;
        totalCandleCount = 0;
    }
}
