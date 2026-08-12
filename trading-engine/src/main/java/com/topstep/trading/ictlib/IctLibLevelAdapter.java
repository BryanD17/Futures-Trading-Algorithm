package com.topstep.trading.ictlib;

import com.topstep.trading.chartstate.LevelEngine;
import com.topstep.trading.chartstate.LevelType;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Publishes §S6 liquidity pools into the engine's {@link LevelEngine} so raids,
 * the chart and the confluence stack all read ONE level universe (Appendix E8).
 *
 * <p>Two level universes is the failure mode this exists to prevent: a raid
 * firing on a level the chart never drew, or a pool the chart shows that the
 * raid pipeline cannot see. Either way nobody can tell which one is lying.
 *
 * <h2>Strictly one-directional</h2>
 * ictlib → LevelEngine only. This adapter registers levels; it never reads
 * raid state back, and it never marks a level raided. Raid detection stays
 * entirely the raid pipeline's job — the pool's own PARTIAL/SWEPT lifecycle is
 * ictlib's independent read, and the two being separately derived is what makes
 * disagreement visible instead of self-confirming.
 *
 * <p>NOTE on {@code LevelEngine}'s storage: it holds ONE level per
 * {@link LevelType}, so EQUAL_HIGH and EQUAL_LOW each have a single slot. The
 * most recently confirmed pool on a side therefore occupies that side's slot.
 * That is the existing shape of the level store, not a choice made here — and
 * the slots were previously unused ({@code addEqualLevel} had no callers), so
 * nothing is displaced.
 */
public final class IctLibLevelAdapter implements LiquidityPoolDetector.PoolListener {

    /** Source tag distinguishing ictlib-published levels from native ones. */
    public static final String SOURCE = "ICTLIB_CLUSTER";

    private final Map<String, LevelEngine> engines = new ConcurrentHashMap<>();

    /** Attach a symbol's LevelEngine. Symbols never attached are simply skipped. */
    public void attach(String symbol, LevelEngine engine) {
        if (symbol == null || engine == null) return;
        engines.put(symbol, engine);
    }

    /** True when a LevelEngine is attached for the symbol. */
    public boolean isAttached(String symbol) {
        return engines.containsKey(symbol);
    }

    @Override
    public void onPoolConfirmed(String symbol, Detection pool) {
        LevelEngine engine = engines.get(symbol);
        if (engine == null || pool == null) return;

        LevelType type = pool.direction().isBullish() ? LevelType.EQUAL_HIGH : LevelType.EQUAL_LOW;
        double price = pool.meta().get("poolPrice") instanceof Number n
                ? n.doubleValue() : pool.midpoint();
        int clusterSize = pool.meta().get("clusterSize") instanceof Number n
                ? n.intValue() : 0;

        engine.addEqualLevel(type, price, clusterSize, pool.createdAt(), SOURCE);
    }
}
