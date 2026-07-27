package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.domain.Candle;

import java.util.ArrayList;
import java.util.List;

/**
 * Fractal swing detection over an arbitrary candle series (V3 Agent 05).
 *
 * <p>A bar is a swing high of strength N when its high strictly exceeds the
 * highs of the N bars on EACH side (mirror for lows) — i.e. an ESTABLISHED
 * extreme, which is what the audit calls the correct dealing range on the
 * governing timeframe (as opposed to the micro reversal leg the 2022 model
 * draws fibs on). Pure functions; one copy shared by the R0 D1 dealing
 * range and the optional H4 structure consult (C7 hygiene: one swing
 * formula, not three).
 */
public final class FractalSwings {

    private FractalSwings() {}

    /** All confirmed swing highs (oldest → newest) of the given strength. */
    public static List<Double> swingHighs(List<Candle> bars, int strength) {
        List<Double> out = new ArrayList<>();
        for (int i = strength; i < bars.size() - strength; i++) {
            boolean isSwing = true;
            for (int j = 1; j <= strength && isSwing; j++) {
                isSwing = bars.get(i).getHigh() > bars.get(i - j).getHigh()
                        && bars.get(i).getHigh() > bars.get(i + j).getHigh();
            }
            if (isSwing) out.add(bars.get(i).getHigh());
        }
        return out;
    }

    /** All confirmed swing lows (oldest → newest) of the given strength. */
    public static List<Double> swingLows(List<Candle> bars, int strength) {
        List<Double> out = new ArrayList<>();
        for (int i = strength; i < bars.size() - strength; i++) {
            boolean isSwing = true;
            for (int j = 1; j <= strength && isSwing; j++) {
                isSwing = bars.get(i).getLow() < bars.get(i - j).getLow()
                        && bars.get(i).getLow() < bars.get(i + j).getLow();
            }
            if (isSwing) out.add(bars.get(i).getLow());
        }
        return out;
    }

    /**
     * Structure direction from the last two swing pairs: HH+HL → +1 (bull),
     * LH+LL → -1 (bear), anything else (or not enough swings) → 0.
     */
    public static int direction(List<Candle> bars, int strength) {
        List<Double> highs = swingHighs(bars, strength);
        List<Double> lows = swingLows(bars, strength);
        if (highs.size() < 2 || lows.size() < 2) return 0;
        boolean hh = highs.get(highs.size() - 1) > highs.get(highs.size() - 2);
        boolean hl = lows.get(lows.size() - 1) > lows.get(lows.size() - 2);
        boolean lh = highs.get(highs.size() - 1) < highs.get(highs.size() - 2);
        boolean ll = lows.get(lows.size() - 1) < lows.get(lows.size() - 2);
        if (hh && hl) return 1;
        if (lh && ll) return -1;
        return 0;
    }
}
