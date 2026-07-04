package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.domain.Candle;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * SA5 scalp-mode fixture: the SA2/SA3 golden fixture with an EQUAL-LOWS
 * CLUSTER engineered into the killzone dip so the raid pipeline produces a
 * raid that LEGITIMATELY scores &ge; 6 — the strict binary gate
 * ({@code scalp.minRaidScore}, default 6) is satisfied by real scoring, not
 * by any bypass:
 *
 * <ul>
 *   <li>kz+1 low 21014.02, kz+7 low 21014.00 (unchanged from golden),
 *       kz+11 low 21013.98 — three 3-bar-fractal swing lows within the
 *       EqualLevelDetector clustering tolerance → EQUAL_LOW cluster of 3 at
 *       avg 21014.00, detected at the 14:00Z refresh (kz+15);</li>
 *   <li>kz+15 also closes the 13:45–14:00 15m bar, which flips the HTF
 *       bias to BULLISH (the runner scores raids with the bias as of the
 *       PREVIOUS bar, so the raid must come after this candle);</li>
 *   <li>the sweep candle kz+16 (low 21012) penetrates the cluster and
 *       closes back above it → {@code RaidDetector} registers a LOW_SWEEP
 *       raid on the EQUAL_LOW level with the bias now aligned;</li>
 *   <li>score = HTF bias aligned (+2) + NY AM killzone (+2) + Silver Bullet
 *       10:00–11:00 ET (+1) + strong equal level cluster&ge;3 (+1) =
 *       <b>6</b> — meets the floor with zero gate weakened;</li>
 *   <li>the cluster lows are strictly DESCENDING in time (21014.02 →
 *       21014.00 → 21013.98) so the MSS detector's prior-bearish-structure
 *       check (strictly lower swing lows) still passes.</li>
 * </ul>
 *
 * <p>Emission geometry is unchanged from the golden fixture: the impulse
 * origin is still the post-sweep low 21012, so entry 21023 / stop 21011 /
 * 1R scalp target 21035.
 *
 * <p>{@link StdvOteGoldenFixture} itself is deliberately untouched — it
 * backs {@code StdvOteLegacyGoldenTest} (legacy byte-for-byte proof).
 */
final class StdvOteScalpFixture {

    static final String SYMBOL = StdvOteGoldenFixture.SYMBOL;
    static final Instant KILLZONE_OPEN = StdvOteGoldenFixture.KILLZONE_OPEN;

    /** Expected emission geometry (identical to the golden fixture). */
    static final double EXPECTED_ENTRY = 21023.0;
    static final double EXPECTED_STOP = 21011.0;
    static final double ONE_R_TARGET = 21035.0;

    /** The engineered equal-lows cluster (raid target level, avg price). */
    static final double EQUAL_LOW_CLUSTER_PRICE = 21014.0;

    private StdvOteScalpFixture() {}

    private static Candle kz(int minutesAfterOpen, double o, double h, double l, double c) {
        return StdvOteGoldenFixture.at(
                KILLZONE_OPEN.plus(minutesAfterOpen, ChronoUnit.MINUTES), o, h, l, c);
    }

    /**
     * Golden killzone candles with the equal-lows cluster: kz+1 and kz+11
     * carry rejection wicks to 21014.02 / 21013.98 (kz+7 already prints
     * 21014.00 in the golden sequence). Everything else is byte-identical
     * to {@link StdvOteGoldenFixture#killzoneCandles()}.
     */
    static List<Candle> killzoneCandles() {
        List<Candle> c = new ArrayList<>();
        c.add(kz(0,  21032, 21034,    21030, 21031));
        c.add(kz(1,  21031, 21032,    21014.02, 21027)); // wick: cluster low #1
        c.add(kz(2,  21027, 21028,    21025, 21026));
        c.add(kz(3,  21026, 21027,    21024, 21025));
        c.add(kz(4,  21025, 21028,    21025, 21027));
        c.add(kz(5,  21027, 21029,    21026, 21028));
        c.add(kz(6,  21028, 21028.5,  21019, 21020));
        c.add(kz(7,  21020, 21021,    21014, 21017));    // cluster low #2 (golden)
        c.add(kz(8,  21017, 21020,    21015.5, 21019));
        c.add(kz(9,  21019, 21024,    21017, 21023));
        c.add(kz(10, 21023, 21028,    21022, 21027));
        c.add(kz(11, 21027, 21032,    21013.98, 21031)); // wick: cluster low #3
        c.add(kz(12, 21031, 21035,    21030, 21034));
        c.add(kz(13, 21034, 21034.75, 21031, 21033));
        c.add(kz(14, 21033, 21034,    21032, 21033));
        c.add(kz(15, 21033, 21033.5,  21016, 21016));    // sell-off toward the cluster
        c.add(kz(16, 21016, 21020,    21012, 21018));    // sweep + EQUAL_LOW raid (score 6)
        c.add(kz(17, 21018, 21028,    21017, 21027));
        c.add(kz(18, 21027, 21039.5,  21023, 21039.5));
        c.add(kz(19, 21039.5, 21046,  21036, 21045));
        c.add(kz(20, 21045, 21052,    21042, 21050));
        c.add(kz(21, 21050, 21050.5,  21036, 21037));
        c.add(kz(22, 21037, 21038,    21024, 21038));
        return c;
    }

    /** Warmup (unchanged golden) + the cluster-bearing killzone candles. */
    static List<Candle> fullFixture() {
        List<Candle> all = new ArrayList<>(StdvOteGoldenFixture.warmupCandles());
        all.addAll(killzoneCandles());
        return all;
    }
}
