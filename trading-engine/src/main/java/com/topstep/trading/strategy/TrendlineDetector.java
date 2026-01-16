package com.topstep.trading.strategy;

import com.topstep.trading.domain.Candle;
import java.util.*;

/**
 * Trendline Detector for visual structure analysis.
 *
 * Trendlines connect swing points to identify:
 * - Support/Resistance diagonals
 * - Trend channels
 * - Breakout/breakdown zones
 *
 * DETECTION METHOD:
 * 1. Identify swing highs/lows (fractal-based)
 * 2. Connect consecutive swings with lines
 * 3. Validate trendline (minimum touches, angle constraints)
 * 4. Track trendline breaks for signals
 *
 * ICT APPLICATION:
 * - Trendline breaks often align with MSS
 * - Trendline support/resistance can confirm OB/FVG zones
 * - Channel breaks indicate potential trend continuation
 */
public class TrendlineDetector {

    private static final int DEFAULT_LOOKBACK = 100;
    private static final int MIN_SWING_SIZE = 3;
    private static final int MIN_TOUCHES = 2;
    private static final double TOUCH_TOLERANCE_RATIO = 0.002;  // 0.2% tolerance

    private final String symbol;
    private final double tickSize;
    private final int lookback;

    // Candle and swing buffers
    private final LinkedList<Candle> candleBuffer;
    private final List<SwingPoint> swingHighs;
    private final List<SwingPoint> swingLows;

    // Detected trendlines
    private final List<Trendline> resistanceTrendlines;
    private final List<Trendline> supportTrendlines;

    // Current price channel
    private Trendline upperChannel;
    private Trendline lowerChannel;

    // State
    private int updateCount = 0;

    public TrendlineDetector(String symbol, double tickSize) {
        this(symbol, tickSize, DEFAULT_LOOKBACK);
    }

    public TrendlineDetector(String symbol, double tickSize, int lookback) {
        this.symbol = symbol;
        this.tickSize = tickSize;
        this.lookback = lookback;
        this.candleBuffer = new LinkedList<>();
        this.swingHighs = new ArrayList<>();
        this.swingLows = new ArrayList<>();
        this.resistanceTrendlines = new ArrayList<>();
        this.supportTrendlines = new ArrayList<>();
    }

    /**
     * Update with new candle.
     */
    public void update(Candle candle) {
        candleBuffer.add(candle);
        while (candleBuffer.size() > lookback) {
            candleBuffer.removeFirst();
        }

        updateCount++;

        // Recalculate every 5 candles for efficiency
        if (updateCount % 5 == 0 && candleBuffer.size() >= 20) {
            detectSwings();
            detectTrendlines();
            detectChannel();
        }
    }

    /**
     * Detect swing highs and lows.
     */
    private void detectSwings() {
        swingHighs.clear();
        swingLows.clear();

        if (candleBuffer.size() < MIN_SWING_SIZE * 2 + 1) return;

        for (int i = MIN_SWING_SIZE; i < candleBuffer.size() - MIN_SWING_SIZE; i++) {
            Candle current = candleBuffer.get(i);
            boolean isSwingHigh = true;
            boolean isSwingLow = true;

            // Check if current is higher/lower than surrounding bars
            for (int j = i - MIN_SWING_SIZE; j <= i + MIN_SWING_SIZE; j++) {
                if (j == i) continue;
                Candle other = candleBuffer.get(j);

                if (other.getHigh() >= current.getHigh()) {
                    isSwingHigh = false;
                }
                if (other.getLow() <= current.getLow()) {
                    isSwingLow = false;
                }
            }

            if (isSwingHigh) {
                swingHighs.add(new SwingPoint(current.getHigh(), i, true));
            }
            if (isSwingLow) {
                swingLows.add(new SwingPoint(current.getLow(), i, false));
            }
        }
    }

    /**
     * Detect trendlines from swing points.
     */
    private void detectTrendlines() {
        resistanceTrendlines.clear();
        supportTrendlines.clear();

        // Create resistance trendlines from swing highs
        for (int i = 0; i < swingHighs.size() - 1; i++) {
            for (int j = i + 1; j < swingHighs.size(); j++) {
                SwingPoint p1 = swingHighs.get(i);
                SwingPoint p2 = swingHighs.get(j);

                Trendline tl = createTrendline(p1, p2, true);
                if (tl != null && isValidTrendline(tl, true)) {
                    resistanceTrendlines.add(tl);
                }
            }
        }

        // Create support trendlines from swing lows
        for (int i = 0; i < swingLows.size() - 1; i++) {
            for (int j = i + 1; j < swingLows.size(); j++) {
                SwingPoint p1 = swingLows.get(i);
                SwingPoint p2 = swingLows.get(j);

                Trendline tl = createTrendline(p1, p2, false);
                if (tl != null && isValidTrendline(tl, false)) {
                    supportTrendlines.add(tl);
                }
            }
        }

        // Sort by touches (most valid first)
        resistanceTrendlines.sort((a, b) -> Integer.compare(b.touches, a.touches));
        supportTrendlines.sort((a, b) -> Integer.compare(b.touches, a.touches));

        // Keep only top trendlines
        while (resistanceTrendlines.size() > 5) {
            resistanceTrendlines.remove(resistanceTrendlines.size() - 1);
        }
        while (supportTrendlines.size() > 5) {
            supportTrendlines.remove(supportTrendlines.size() - 1);
        }
    }

    /**
     * Create trendline from two swing points.
     */
    private Trendline createTrendline(SwingPoint p1, SwingPoint p2, boolean isResistance) {
        if (p1.barIndex >= p2.barIndex) return null;

        double slope = (p2.price - p1.price) / (p2.barIndex - p1.barIndex);
        double intercept = p1.price - slope * p1.barIndex;

        return new Trendline(slope, intercept, p1.barIndex, p2.barIndex, isResistance);
    }

    /**
     * Validate trendline (check touches and angle).
     */
    private boolean isValidTrendline(Trendline tl, boolean isResistance) {
        int touches = 0;
        double tolerance = candleBuffer.getLast().getClose() * TOUCH_TOLERANCE_RATIO;

        List<SwingPoint> swings = isResistance ? swingHighs : swingLows;

        for (SwingPoint swing : swings) {
            double expectedPrice = tl.getPriceAt(swing.barIndex);
            if (Math.abs(swing.price - expectedPrice) <= tolerance) {
                touches++;
            }
        }

        tl.touches = touches;

        // Require minimum touches
        if (touches < MIN_TOUCHES) return false;

        // Check angle constraints (not too steep or flat)
        double anglePerBar = Math.abs(tl.slope);
        double avgPrice = candleBuffer.getLast().getClose();
        double angleRatio = anglePerBar / avgPrice;

        // Reject if angle is too steep (>1% per bar) or too flat (<0.001% per bar)
        return angleRatio <= 0.01 && angleRatio >= 0.00001;
    }

    /**
     * Detect price channel from parallel trendlines.
     */
    private void detectChannel() {
        upperChannel = null;
        lowerChannel = null;

        if (resistanceTrendlines.isEmpty() || supportTrendlines.isEmpty()) return;

        // Find parallel trendlines (similar slope)
        for (Trendline resistance : resistanceTrendlines) {
            for (Trendline support : supportTrendlines) {
                double slopeDiff = Math.abs(resistance.slope - support.slope);
                double avgSlope = (Math.abs(resistance.slope) + Math.abs(support.slope)) / 2;

                // Check if slopes are similar (within 20%)
                if (avgSlope > 0 && slopeDiff / avgSlope < 0.2) {
                    upperChannel = resistance;
                    lowerChannel = support;
                    return;
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // QUERY API
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Get projected trendline value at current bar.
     */
    public Optional<Double> getNearestResistance(double currentPrice) {
        if (resistanceTrendlines.isEmpty()) return Optional.empty();

        int currentBar = candleBuffer.size() - 1;
        Double nearest = null;
        double nearestDist = Double.MAX_VALUE;

        for (Trendline tl : resistanceTrendlines) {
            double projected = tl.getPriceAt(currentBar);
            if (projected > currentPrice) {
                double dist = projected - currentPrice;
                if (dist < nearestDist) {
                    nearestDist = dist;
                    nearest = projected;
                }
            }
        }

        return Optional.ofNullable(nearest);
    }

    /**
     * Get projected support trendline at current bar.
     */
    public Optional<Double> getNearestSupport(double currentPrice) {
        if (supportTrendlines.isEmpty()) return Optional.empty();

        int currentBar = candleBuffer.size() - 1;
        Double nearest = null;
        double nearestDist = Double.MAX_VALUE;

        for (Trendline tl : supportTrendlines) {
            double projected = tl.getPriceAt(currentBar);
            if (projected < currentPrice) {
                double dist = currentPrice - projected;
                if (dist < nearestDist) {
                    nearestDist = dist;
                    nearest = projected;
                }
            }
        }

        return Optional.ofNullable(nearest);
    }

    /**
     * Check if price has broken above resistance trendline.
     */
    public boolean hasBrokenResistance(double currentPrice) {
        if (resistanceTrendlines.isEmpty()) return false;

        int currentBar = candleBuffer.size() - 1;
        for (Trendline tl : resistanceTrendlines) {
            double projected = tl.getPriceAt(currentBar);
            if (currentPrice > projected + (tickSize * 2)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if price has broken below support trendline.
     */
    public boolean hasBrokenSupport(double currentPrice) {
        if (supportTrendlines.isEmpty()) return false;

        int currentBar = candleBuffer.size() - 1;
        for (Trendline tl : supportTrendlines) {
            double projected = tl.getPriceAt(currentBar);
            if (currentPrice < projected - (tickSize * 2)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if currently within a channel.
     */
    public boolean isInChannel() {
        return upperChannel != null && lowerChannel != null;
    }

    /**
     * Get channel width at current bar.
     */
    public Optional<Double> getChannelWidth() {
        if (!isInChannel()) return Optional.empty();

        int currentBar = candleBuffer.size() - 1;
        double upper = upperChannel.getPriceAt(currentBar);
        double lower = lowerChannel.getPriceAt(currentBar);

        return Optional.of(upper - lower);
    }

    /**
     * Check for channel breakout.
     */
    public Optional<ChannelBreakout> getChannelBreakout(double currentPrice) {
        if (!isInChannel()) return Optional.empty();

        int currentBar = candleBuffer.size() - 1;
        double upper = upperChannel.getPriceAt(currentBar);
        double lower = lowerChannel.getPriceAt(currentBar);

        if (currentPrice > upper + (tickSize * 2)) {
            return Optional.of(ChannelBreakout.BULLISH);
        } else if (currentPrice < lower - (tickSize * 2)) {
            return Optional.of(ChannelBreakout.BEARISH);
        }

        return Optional.empty();
    }

    /**
     * Calculate trendline confluence score.
     */
    public int getConfluenceScore(double price, boolean isBullish) {
        int score = 0;

        // Trendline break bonus
        if (isBullish && hasBrokenResistance(price)) {
            score += 2;
        } else if (!isBullish && hasBrokenSupport(price)) {
            score += 2;
        }

        // Channel breakout bonus
        Optional<ChannelBreakout> breakout = getChannelBreakout(price);
        if (breakout.isPresent()) {
            boolean matchesBias = (breakout.get() == ChannelBreakout.BULLISH && isBullish) ||
                                  (breakout.get() == ChannelBreakout.BEARISH && !isBullish);
            if (matchesBias) {
                score += 2;
            } else {
                score -= 2;  // Trading against channel breakout
            }
        }

        // Near trendline support/resistance
        Optional<Double> support = getNearestSupport(price);
        Optional<Double> resistance = getNearestResistance(price);

        if (isBullish && support.isPresent()) {
            double dist = price - support.get();
            if (dist < tickSize * 10) {
                score += 1;  // Near support for long
            }
        }

        if (!isBullish && resistance.isPresent()) {
            double dist = resistance.get() - price;
            if (dist < tickSize * 10) {
                score += 1;  // Near resistance for short
            }
        }

        return score;
    }

    // Getters
    public List<Trendline> getResistanceTrendlines() { return new ArrayList<>(resistanceTrendlines); }
    public List<Trendline> getSupportTrendlines() { return new ArrayList<>(supportTrendlines); }
    public Trendline getUpperChannel() { return upperChannel; }
    public Trendline getLowerChannel() { return lowerChannel; }

    public void reset() {
        candleBuffer.clear();
        swingHighs.clear();
        swingLows.clear();
        resistanceTrendlines.clear();
        supportTrendlines.clear();
        upperChannel = null;
        lowerChannel = null;
        updateCount = 0;
    }

    // Data classes
    public static class SwingPoint {
        public final double price;
        public final int barIndex;
        public final boolean isHigh;

        public SwingPoint(double price, int barIndex, boolean isHigh) {
            this.price = price;
            this.barIndex = barIndex;
            this.isHigh = isHigh;
        }
    }

    public static class Trendline {
        public final double slope;
        public final double intercept;
        public final int startBar;
        public final int endBar;
        public final boolean isResistance;
        public int touches;

        public Trendline(double slope, double intercept, int startBar, int endBar, boolean isResistance) {
            this.slope = slope;
            this.intercept = intercept;
            this.startBar = startBar;
            this.endBar = endBar;
            this.isResistance = isResistance;
            this.touches = 0;
        }

        public double getPriceAt(int barIndex) {
            return slope * barIndex + intercept;
        }

        @Override
        public String toString() {
            return String.format("Trendline{%s, slope=%.4f, touches=%d}",
                    isResistance ? "RESISTANCE" : "SUPPORT", slope, touches);
        }
    }

    public enum ChannelBreakout {
        BULLISH,
        BEARISH
    }
}
