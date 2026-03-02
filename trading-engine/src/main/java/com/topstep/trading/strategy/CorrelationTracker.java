package com.topstep.trading.strategy;

import com.topstep.trading.domain.Candle;
import java.util.*;

/**
 * Tracks correlations between instruments for multi-instrument trading.
 *
 * Correlation-based trading uses the relationship between instruments:
 * - NQ vs ES: ~0.95 correlation (should move together)
 * - Gold vs DXY: ~-0.80 correlation (inversely related)
 *
 * When correlated instruments diverge, it signals potential opportunities:
 * - SMT Divergence: One makes new high/low, other doesn't
 * - Mean Reversion: Correlation should eventually normalize
 * - Strength Assessment: Which instrument leads the move
 */
public class CorrelationTracker {

    // Predefined correlation pairs
    private static final Map<String, CorrelationPair> CORRELATION_PAIRS = new HashMap<>();

    static {
        // US Indices - highly correlated
        CORRELATION_PAIRS.put("NQ_ES", new CorrelationPair("NQ", "ES", 0.95, true));
        CORRELATION_PAIRS.put("ES_YM", new CorrelationPair("ES", "YM", 0.90, true));

        // Gold correlations
        CORRELATION_PAIRS.put("GC_SI", new CorrelationPair("GC", "SI", 0.85, true));   // Gold/Silver
        CORRELATION_PAIRS.put("GC_DX", new CorrelationPair("GC", "DX", -0.80, false)); // Gold/Dollar (inverse)
    }

    private final Map<String, LinkedList<Double>> priceHistory;
    private final Map<String, Double> lastPrices;
    private final int correlationPeriod;

    public CorrelationTracker(int correlationPeriod) {
        this.correlationPeriod = correlationPeriod;
        this.priceHistory = new HashMap<>();
        this.lastPrices = new HashMap<>();
    }

    /**
     * Update with a new candle.
     */
    public void update(Candle candle) {
        String symbol = candle.getSymbol();

        // Update price history
        priceHistory.computeIfAbsent(symbol, k -> new LinkedList<>());
        LinkedList<Double> history = priceHistory.get(symbol);
        history.add(candle.getClose());

        // Keep only correlation period
        while (history.size() > correlationPeriod) {
            history.removeFirst();
        }

        lastPrices.put(symbol, candle.getClose());
    }

    /**
     * Calculate current correlation between two symbols.
     */
    public double calculateCorrelation(String symbol1, String symbol2) {
        LinkedList<Double> prices1 = priceHistory.get(symbol1);
        LinkedList<Double> prices2 = priceHistory.get(symbol2);

        if (prices1 == null || prices2 == null) {
            return 0;
        }

        // Use minimum common length
        int n = Math.min(prices1.size(), prices2.size());
        if (n < 10) {
            return 0;  // Not enough data
        }

        // Convert to arrays for easier calculation
        double[] x = new double[n];
        double[] y = new double[n];

        for (int i = 0; i < n; i++) {
            x[i] = prices1.get(prices1.size() - n + i);
            y[i] = prices2.get(prices2.size() - n + i);
        }

        return pearsonCorrelation(x, y);
    }

    /**
     * Calculate Pearson correlation coefficient.
     */
    private double pearsonCorrelation(double[] x, double[] y) {
        int n = x.length;

        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0, sumY2 = 0;

        for (int i = 0; i < n; i++) {
            sumX += x[i];
            sumY += y[i];
            sumXY += x[i] * y[i];
            sumX2 += x[i] * x[i];
            sumY2 += y[i] * y[i];
        }

        double numerator = n * sumXY - sumX * sumY;
        double denominator = Math.sqrt((n * sumX2 - sumX * sumX) * (n * sumY2 - sumY * sumY));

        if (denominator == 0) {
            return 0;
        }

        return numerator / denominator;
    }

    /**
     * Check for SMT divergence between two symbols.
     * Returns true if one made new high/low and the other didn't.
     */
    public boolean hasSMTDivergence(String symbol1, String symbol2, int lookback) {
        LinkedList<Double> prices1 = priceHistory.get(symbol1);
        LinkedList<Double> prices2 = priceHistory.get(symbol2);

        if (prices1 == null || prices2 == null ||
            prices1.size() < lookback || prices2.size() < lookback) {
            return false;
        }

        // Get recent prices
        int n = Math.min(lookback, Math.min(prices1.size(), prices2.size()));

        double high1 = Double.MIN_VALUE, low1 = Double.MAX_VALUE;
        double high2 = Double.MIN_VALUE, low2 = Double.MAX_VALUE;

        for (int i = 0; i < n - 1; i++) {
            int idx1 = prices1.size() - n + i;
            int idx2 = prices2.size() - n + i;

            high1 = Math.max(high1, prices1.get(idx1));
            low1 = Math.min(low1, prices1.get(idx1));
            high2 = Math.max(high2, prices2.get(idx2));
            low2 = Math.min(low2, prices2.get(idx2));
        }

        double last1 = prices1.getLast();
        double last2 = prices2.getLast();

        // Check for divergence
        boolean symbol1NewHigh = last1 > high1;
        boolean symbol1NewLow = last1 < low1;
        boolean symbol2NewHigh = last2 > high2;
        boolean symbol2NewLow = last2 < low2;

        // Divergence: One makes new extreme, other doesn't
        return (symbol1NewHigh && !symbol2NewHigh) ||
               (symbol1NewLow && !symbol2NewLow) ||
               (symbol2NewHigh && !symbol1NewHigh) ||
               (symbol2NewLow && !symbol1NewLow);
    }

    /**
     * Get the relative strength between two symbols.
     * Positive = symbol1 stronger, Negative = symbol2 stronger
     */
    public double getRelativeStrength(String symbol1, String symbol2, int lookback) {
        LinkedList<Double> prices1 = priceHistory.get(symbol1);
        LinkedList<Double> prices2 = priceHistory.get(symbol2);

        if (prices1 == null || prices2 == null ||
            prices1.size() < lookback || prices2.size() < lookback) {
            return 0;
        }

        int startIdx1 = prices1.size() - lookback;
        int startIdx2 = prices2.size() - lookback;

        double change1 = (prices1.getLast() - prices1.get(startIdx1)) / prices1.get(startIdx1) * 100;
        double change2 = (prices2.getLast() - prices2.get(startIdx2)) / prices2.get(startIdx2) * 100;

        return change1 - change2;
    }

    /**
     * Check if correlation between pair is abnormal (deviation from expected).
     */
    public boolean hasAbnormalCorrelation(String symbol1, String symbol2) {
        String pairKey = symbol1 + "_" + symbol2;
        CorrelationPair pair = CORRELATION_PAIRS.get(pairKey);

        if (pair == null) {
            pairKey = symbol2 + "_" + symbol1;
            pair = CORRELATION_PAIRS.get(pairKey);
        }

        if (pair == null) {
            return false;  // Unknown pair
        }

        double currentCorr = calculateCorrelation(symbol1, symbol2);
        double expectedCorr = pair.expectedCorrelation;

        // Abnormal if current correlation deviates significantly from expected
        double deviation = Math.abs(currentCorr - expectedCorr);
        return deviation > 0.3;  // More than 0.3 deviation is abnormal
    }

    /**
     * Get trading suggestion based on correlation analysis.
     */
    public CorrelationSignal getCorrelationSignal(String symbol1, String symbol2) {
        if (!hasSMTDivergence(symbol1, symbol2, 10)) {
            return CorrelationSignal.NO_SIGNAL;
        }

        double relStrength = getRelativeStrength(symbol1, symbol2, 10);

        if (Math.abs(relStrength) < 0.5) {
            return CorrelationSignal.NO_SIGNAL;
        }

        // Weaker instrument likely to catch up (mean reversion)
        if (relStrength > 0) {
            // Symbol1 stronger - symbol2 might catch up (bullish on symbol2)
            return CorrelationSignal.BULLISH_SYMBOL2;
        } else {
            // Symbol2 stronger - symbol1 might catch up (bullish on symbol1)
            return CorrelationSignal.BULLISH_SYMBOL1;
        }
    }

    /**
     * Get correlation summary for two symbols.
     */
    public String getCorrelationSummary(String symbol1, String symbol2) {
        double corr = calculateCorrelation(symbol1, symbol2);
        boolean divergence = hasSMTDivergence(symbol1, symbol2, 10);
        double relStrength = getRelativeStrength(symbol1, symbol2, 10);

        return String.format("%s/%s: Corr=%.2f, Divergence=%s, RelStrength=%.2f%%",
                symbol1, symbol2, corr, divergence, relStrength);
    }

    /**
     * Reset tracker for a symbol.
     */
    public void reset(String symbol) {
        priceHistory.remove(symbol);
        lastPrices.remove(symbol);
    }

    /**
     * Reset entire tracker.
     */
    public void resetAll() {
        priceHistory.clear();
        lastPrices.clear();
    }

    /**
     * Correlation signal types.
     */
    public enum CorrelationSignal {
        NO_SIGNAL,
        BULLISH_SYMBOL1,
        BEARISH_SYMBOL1,
        BULLISH_SYMBOL2,
        BEARISH_SYMBOL2
    }

    /**
     * Predefined correlation pair data.
     */
    private static class CorrelationPair {
        final String symbol1;
        final String symbol2;
        final double expectedCorrelation;
        final boolean positiveCorrelation;

        CorrelationPair(String symbol1, String symbol2, double expectedCorrelation, boolean positiveCorrelation) {
            this.symbol1 = symbol1;
            this.symbol2 = symbol2;
            this.expectedCorrelation = expectedCorrelation;
            this.positiveCorrelation = positiveCorrelation;
        }
    }
}
