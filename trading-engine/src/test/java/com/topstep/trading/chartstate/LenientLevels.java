package com.topstep.trading.chartstate;

/**
 * V5 Agent 03 test helper: a {@link LevelEngine} whose phantom-day guard
 * ({@code levels.minBarsPerDay}, default 60) is disabled, for unit fixtures
 * that model "yesterday" with a single candle. The guard itself is proven
 * on the real tape by {@code LevelEngineParityTest}.
 */
public final class LenientLevels {

    private LenientLevels() {
    }

    public static LevelEngine of(String symbol, CandleSeries series) {
        String prev = System.getProperty("levels.minBarsPerDay");
        System.setProperty("levels.minBarsPerDay", "1");
        try {
            return new LevelEngine(symbol, series);
        } finally {
            if (prev == null) System.clearProperty("levels.minBarsPerDay");
            else System.setProperty("levels.minBarsPerDay", prev);
        }
    }
}
