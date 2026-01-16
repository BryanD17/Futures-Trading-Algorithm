package com.topstep.trading.strategy;

import com.topstep.trading.domain.Candle;
import java.util.*;

/**
 * Volume Profile Analyzer for detecting institutional activity.
 *
 * Volume Profile analysis identifies:
 * - High Volume Nodes (HVN): Price levels with significant trading activity
 * - Low Volume Nodes (LVN): Price gaps with little trading (price moves fast through)
 * - Point of Control (POC): Price level with highest volume
 * - Value Area (VA): Range containing 70% of volume
 *
 * ICT APPLICATION:
 * - HVN often aligns with Order Blocks (institutional accumulation)
 * - LVN often aligns with Fair Value Gaps (imbalance zones)
 * - POC can act as support/resistance
 * - Volume spikes indicate institutional entry/exit
 */
public class VolumeProfileAnalyzer {

    private static final int DEFAULT_LOOKBACK = 100;
    private static final int DEFAULT_PRICE_BINS = 50;
    private static final double VALUE_AREA_PCT = 0.70;

    private final String symbol;
    private final double tickSize;
    private final int lookback;
    private final int priceBins;

    // Rolling candle buffer
    private final LinkedList<Candle> candleBuffer;

    // Volume profile data
    private final Map<Integer, Long> volumeByBin;
    private double profileLow;
    private double profileHigh;
    private double binSize;

    // Calculated values
    private double poc;                    // Point of Control
    private double valueAreaHigh;          // VA High
    private double valueAreaLow;           // VA Low
    private List<VolumeNode> highVolumeNodes;
    private List<VolumeNode> lowVolumeNodes;
    private long totalVolume;
    private double averageVolume;
    private double volumeStdDev;

    // State
    private int updateCount = 0;

    public VolumeProfileAnalyzer(String symbol, double tickSize) {
        this(symbol, tickSize, DEFAULT_LOOKBACK, DEFAULT_PRICE_BINS);
    }

    public VolumeProfileAnalyzer(String symbol, double tickSize, int lookback, int priceBins) {
        this.symbol = symbol;
        this.tickSize = tickSize;
        this.lookback = lookback;
        this.priceBins = priceBins;
        this.candleBuffer = new LinkedList<>();
        this.volumeByBin = new HashMap<>();
        this.highVolumeNodes = new ArrayList<>();
        this.lowVolumeNodes = new ArrayList<>();
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

        // Recalculate profile every 10 candles for efficiency
        if (updateCount % 10 == 0 && candleBuffer.size() >= 20) {
            calculateVolumeProfile();
        }
    }

    /**
     * Calculate the full volume profile.
     */
    private void calculateVolumeProfile() {
        if (candleBuffer.isEmpty()) return;

        // Find price range
        profileLow = Double.MAX_VALUE;
        profileHigh = Double.MIN_VALUE;
        totalVolume = 0;

        for (Candle c : candleBuffer) {
            profileLow = Math.min(profileLow, c.getLow());
            profileHigh = Math.max(profileHigh, c.getHigh());
            totalVolume += c.getVolume();
        }

        if (profileHigh <= profileLow) return;

        binSize = (profileHigh - profileLow) / priceBins;
        if (binSize <= 0) return;

        // Reset volume bins
        volumeByBin.clear();

        // Distribute volume across bins
        for (Candle c : candleBuffer) {
            distributeVolume(c);
        }

        // Calculate statistics
        calculateStatistics();

        // Find POC and Value Area
        calculatePocAndValueArea();

        // Find HVN and LVN
        findVolumeNodes();
    }

    /**
     * Distribute candle's volume across touched price bins.
     */
    private void distributeVolume(Candle candle) {
        int lowBin = priceToBin(candle.getLow());
        int highBin = priceToBin(candle.getHigh());

        // Distribute volume evenly across touched bins
        int binCount = Math.max(1, highBin - lowBin + 1);
        long volumePerBin = candle.getVolume() / binCount;

        for (int bin = lowBin; bin <= highBin; bin++) {
            volumeByBin.merge(bin, volumePerBin, Long::sum);
        }
    }

    /**
     * Convert price to bin index.
     */
    private int priceToBin(double price) {
        if (binSize <= 0) return 0;
        int bin = (int) ((price - profileLow) / binSize);
        return Math.max(0, Math.min(priceBins - 1, bin));
    }

    /**
     * Convert bin index to price.
     */
    private double binToPrice(int bin) {
        return profileLow + (bin + 0.5) * binSize;
    }

    /**
     * Calculate volume statistics.
     */
    private void calculateStatistics() {
        if (volumeByBin.isEmpty()) {
            averageVolume = 0;
            volumeStdDev = 0;
            return;
        }

        // Calculate mean
        double sum = 0;
        for (long vol : volumeByBin.values()) {
            sum += vol;
        }
        averageVolume = sum / volumeByBin.size();

        // Calculate standard deviation
        double sumSquares = 0;
        for (long vol : volumeByBin.values()) {
            sumSquares += Math.pow(vol - averageVolume, 2);
        }
        volumeStdDev = Math.sqrt(sumSquares / volumeByBin.size());
    }

    /**
     * Calculate Point of Control and Value Area.
     */
    private void calculatePocAndValueArea() {
        if (volumeByBin.isEmpty()) {
            poc = (profileLow + profileHigh) / 2;
            valueAreaHigh = profileHigh;
            valueAreaLow = profileLow;
            return;
        }

        // Find POC (bin with highest volume)
        int pocBin = 0;
        long maxVolume = 0;
        for (Map.Entry<Integer, Long> entry : volumeByBin.entrySet()) {
            if (entry.getValue() > maxVolume) {
                maxVolume = entry.getValue();
                pocBin = entry.getKey();
            }
        }
        poc = binToPrice(pocBin);

        // Calculate Value Area (70% of volume centered on POC)
        long targetVolume = (long) (totalVolume * VALUE_AREA_PCT);
        long accumulatedVolume = volumeByBin.getOrDefault(pocBin, 0L);

        int lowBin = pocBin;
        int highBin = pocBin;

        while (accumulatedVolume < targetVolume && (lowBin > 0 || highBin < priceBins - 1)) {
            long volumeLower = (lowBin > 0) ? volumeByBin.getOrDefault(lowBin - 1, 0L) : 0;
            long volumeHigher = (highBin < priceBins - 1) ? volumeByBin.getOrDefault(highBin + 1, 0L) : 0;

            if (volumeLower >= volumeHigher && lowBin > 0) {
                lowBin--;
                accumulatedVolume += volumeLower;
            } else if (highBin < priceBins - 1) {
                highBin++;
                accumulatedVolume += volumeHigher;
            } else if (lowBin > 0) {
                lowBin--;
                accumulatedVolume += volumeLower;
            } else {
                break;
            }
        }

        valueAreaLow = binToPrice(lowBin);
        valueAreaHigh = binToPrice(highBin);
    }

    /**
     * Find High Volume Nodes and Low Volume Nodes.
     */
    private void findVolumeNodes() {
        highVolumeNodes.clear();
        lowVolumeNodes.clear();

        if (volumeByBin.isEmpty() || volumeStdDev == 0) return;

        // HVN: Volume > mean + 1 stdDev
        // LVN: Volume < mean - 0.5 stdDev
        double hvnThreshold = averageVolume + volumeStdDev;
        double lvnThreshold = averageVolume - (volumeStdDev * 0.5);

        for (Map.Entry<Integer, Long> entry : volumeByBin.entrySet()) {
            int bin = entry.getKey();
            long volume = entry.getValue();
            double price = binToPrice(bin);

            if (volume > hvnThreshold) {
                highVolumeNodes.add(new VolumeNode(price, volume, VolumeNodeType.HVN));
            } else if (volume < lvnThreshold) {
                lowVolumeNodes.add(new VolumeNode(price, volume, VolumeNodeType.LVN));
            }
        }

        // Sort by price
        highVolumeNodes.sort(Comparator.comparingDouble(n -> n.price));
        lowVolumeNodes.sort(Comparator.comparingDouble(n -> n.price));
    }

    // ═══════════════════════════════════════════════════════════════════
    // QUERY API
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Check if price is at a High Volume Node.
     */
    public boolean isAtHighVolumeNode(double price, double tolerance) {
        for (VolumeNode node : highVolumeNodes) {
            if (Math.abs(node.price - price) <= tolerance) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if price is at a Low Volume Node (fast move zone).
     */
    public boolean isAtLowVolumeNode(double price, double tolerance) {
        for (VolumeNode node : lowVolumeNodes) {
            if (Math.abs(node.price - price) <= tolerance) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if current volume is significantly above average (institutional activity).
     */
    public boolean hasVolumeSpike(Candle candle, double multiplier) {
        if (averageVolume <= 0) return false;
        double threshold = averageVolume * multiplier;
        return candle.getVolume() > threshold;
    }

    /**
     * Get nearest HVN to a price.
     */
    public Optional<VolumeNode> getNearestHvn(double price) {
        VolumeNode nearest = null;
        double nearestDist = Double.MAX_VALUE;

        for (VolumeNode node : highVolumeNodes) {
            double dist = Math.abs(node.price - price);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = node;
            }
        }

        return Optional.ofNullable(nearest);
    }

    /**
     * Get nearest LVN (imbalance zone) to a price.
     */
    public Optional<VolumeNode> getNearestLvn(double price) {
        VolumeNode nearest = null;
        double nearestDist = Double.MAX_VALUE;

        for (VolumeNode node : lowVolumeNodes) {
            double dist = Math.abs(node.price - price);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = node;
            }
        }

        return Optional.ofNullable(nearest);
    }

    /**
     * Check if price is within Value Area.
     */
    public boolean isInValueArea(double price) {
        return price >= valueAreaLow && price <= valueAreaHigh;
    }

    /**
     * Check if price is near POC.
     */
    public boolean isNearPoc(double price, double tolerance) {
        return Math.abs(price - poc) <= tolerance;
    }

    /**
     * Calculate volume-based confluence score for a price level.
     * Higher score = more institutional interest at this level.
     */
    public int getVolumeConfluenceScore(double price, double tolerance) {
        int score = 0;

        // At HVN: +2 (institutional accumulation zone)
        if (isAtHighVolumeNode(price, tolerance)) {
            score += 2;
        }

        // Near POC: +1 (high activity reference)
        if (isNearPoc(price, tolerance * 2)) {
            score += 1;
        }

        // At LVN: +1 for entries expecting fast moves
        if (isAtLowVolumeNode(price, tolerance)) {
            score += 1;
        }

        return score;
    }

    // Getters
    public double getPoc() { return poc; }
    public double getValueAreaHigh() { return valueAreaHigh; }
    public double getValueAreaLow() { return valueAreaLow; }
    public List<VolumeNode> getHighVolumeNodes() { return new ArrayList<>(highVolumeNodes); }
    public List<VolumeNode> getLowVolumeNodes() { return new ArrayList<>(lowVolumeNodes); }
    public long getTotalVolume() { return totalVolume; }
    public double getAverageVolume() { return averageVolume; }

    /**
     * Volume node data class.
     */
    public static class VolumeNode {
        public final double price;
        public final long volume;
        public final VolumeNodeType type;

        public VolumeNode(double price, long volume, VolumeNodeType type) {
            this.price = price;
            this.volume = volume;
            this.type = type;
        }

        @Override
        public String toString() {
            return String.format("%s @ %.2f (vol=%d)", type, price, volume);
        }
    }

    public enum VolumeNodeType {
        HVN,  // High Volume Node
        LVN   // Low Volume Node
    }
}
