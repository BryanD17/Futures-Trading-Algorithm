package com.topstep.trading.strategy;

import com.topstep.trading.domain.Candle;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Detects Fair Value Gaps (FVGs) and Inversion FVGs (IFVGs).
 *
 * FVG: A three-candle pattern where the middle candle leaves a gap between
 * the wicks of the surrounding candles, indicating an imbalance.
 *
 * IFVG: An FVG that gets filled and then acts as support (bullish) or resistance (bearish).
 */
public class FvgDetector {

    private final List<Candle> candles;
    private final List<FairValueGap> fvgs;
    private final int maxFvgs;

    public FvgDetector(int maxFvgs) {
        this.maxFvgs = maxFvgs;
        this.candles = new ArrayList<>();
        this.fvgs = new ArrayList<>();
    }

    /**
     * Update with a new candle and detect FVGs.
     */
    public void update(Candle candle) {
        candles.add(candle);

        // Keep only last 100 candles for efficiency
        if (candles.size() > 100) {
            candles.remove(0);
        }

        // Detect new FVGs
        if (candles.size() >= 3) {
            detectFvg();
        }

        // Update existing FVGs to check for fills and inversions
        updateFvgs(candle);
    }

    /**
     * Detect FVG from the last three candles.
     */
    private void detectFvg() {
        int size = candles.size();
        Candle candle1 = candles.get(size - 3);
        Candle candle2 = candles.get(size - 2);
        Candle candle3 = candles.get(size - 1);

        // Bullish FVG: Gap between candle1 high and candle3 low (candle2 leaves the gap)
        double bullishGapBottom = candle1.getHigh();
        double bullishGapTop = candle3.getLow();

        if (bullishGapTop > bullishGapBottom) {
            // Valid bullish FVG
            FairValueGap fvg = new FairValueGap(true, bullishGapTop, bullishGapBottom, candle2.getTimestamp());
            System.out.println("[FVG] BULLISH FVG detected: " + bullishGapBottom + " - " + bullishGapTop);
            addFvg(fvg);
        }

        // Bearish FVG: Gap between candle1 low and candle3 high
        double bearishGapTop = candle1.getLow();
        double bearishGapBottom = candle3.getHigh();

        if (bearishGapTop > bearishGapBottom) {
            // Valid bearish FVG
            FairValueGap fvg = new FairValueGap(false, bearishGapTop, bearishGapBottom, candle2.getTimestamp());
            System.out.println("[FVG] BEARISH FVG detected: " + bearishGapBottom + " - " + bearishGapTop);
            addFvg(fvg);
        }
    }

    /**
     * Add a new FVG to the list.
     */
    private void addFvg(FairValueGap fvg) {
        fvgs.add(fvg);

        // Keep only the most recent FVGs
        if (fvgs.size() > maxFvgs) {
            fvgs.remove(0);
        }
    }

    /**
     * Update existing FVGs to check for fills and inversions.
     */
    private void updateFvgs(Candle candle) {
        for (FairValueGap fvg : fvgs) {
            // Check if FVG is filled
            if (!fvg.isFilled()) {
                boolean filled = false;

                if (fvg.isBullish()) {
                    // Bullish FVG is filled when price comes back down into the gap
                    if (candle.getLow() <= fvg.getTop()) {
                        filled = true;
                    }
                } else {
                    // Bearish FVG is filled when price comes back up into the gap
                    if (candle.getHigh() >= fvg.getBottom()) {
                        filled = true;
                    }
                }

                if (filled) {
                    fvg.setFilled(true);
                }
            }

            // Check for inversion
            if (fvg.isFilled() && !fvg.isInverted()) {
                if (fvg.isBullish()) {
                    // Bullish IFVG: Price fills the gap and then bounces off it as support
                    if (candle.getLow() <= fvg.getTop() && candle.getClose() > fvg.getMidpoint()) {
                        fvg.setInverted(true);
                        System.out.println("[FVG] BULLISH IFVG (inversion) at " + fvg.getBottom() + " - " + fvg.getTop());
                    }
                } else {
                    // Bearish IFVG: Price fills the gap and then rejects it as resistance
                    if (candle.getHigh() >= fvg.getBottom() && candle.getClose() < fvg.getMidpoint()) {
                        fvg.setInverted(true);
                        System.out.println("[FVG] BEARISH IFVG (inversion) at " + fvg.getBottom() + " - " + fvg.getTop());
                    }
                }
            }
        }
    }

    /**
     * Get all detected FVGs.
     */
    public List<FairValueGap> getAllFvgs() {
        return new ArrayList<>(fvgs);
    }

    /**
     * Get only unfilled FVGs.
     */
    public List<FairValueGap> getUnfilledFvgs() {
        return fvgs.stream()
                .filter(fvg -> !fvg.isFilled())
                .collect(Collectors.toList());
    }

    /**
     * Get inverted FVGs (IFVGs).
     */
    public List<FairValueGap> getInvertedFvgs() {
        return fvgs.stream()
                .filter(FairValueGap::isInverted)
                .collect(Collectors.toList());
    }

    /**
     * Find the nearest IFVG to a given price.
     */
    public FairValueGap findNearestIfvg(double price, boolean bullish) {
        return fvgs.stream()
                .filter(FairValueGap::isInverted)
                .filter(fvg -> fvg.isBullish() == bullish)
                .min((a, b) -> {
                    double distA = Math.abs(a.getMidpoint() - price);
                    double distB = Math.abs(b.getMidpoint() - price);
                    return Double.compare(distA, distB);
                })
                .orElse(null);
    }

    /**
     * Find the nearest unfilled FVG to a given price within a max distance.
     * This is less strict than IFVG but still provides an entry zone.
     */
    public FairValueGap findNearestUnfilledFvg(double price, boolean bullish, double maxDistance) {
        return fvgs.stream()
                .filter(fvg -> !fvg.isFilled())
                .filter(fvg -> fvg.isBullish() == bullish)
                .filter(fvg -> Math.abs(fvg.getMidpoint() - price) <= maxDistance)
                .min((a, b) -> {
                    double distA = Math.abs(a.getMidpoint() - price);
                    double distB = Math.abs(b.getMidpoint() - price);
                    return Double.compare(distA, distB);
                })
                .orElse(null);
    }

    /**
     * Find any FVG (filled or unfilled) near price for entry zone reference.
     */
    public FairValueGap findNearestFvg(double price, boolean bullish, double maxDistance) {
        return fvgs.stream()
                .filter(fvg -> fvg.isBullish() == bullish)
                .filter(fvg -> Math.abs(fvg.getMidpoint() - price) <= maxDistance)
                .min((a, b) -> {
                    double distA = Math.abs(a.getMidpoint() - price);
                    double distB = Math.abs(b.getMidpoint() - price);
                    return Double.compare(distA, distB);
                })
                .orElse(null);
    }

    /**
     * Check if any FVG exists in the given direction.
     */
    public boolean hasFvgInDirection(boolean bullish) {
        return fvgs.stream().anyMatch(fvg -> fvg.isBullish() == bullish);
    }

    /**
     * Reset the detector.
     */
    public void reset() {
        candles.clear();
        fvgs.clear();
    }
}
