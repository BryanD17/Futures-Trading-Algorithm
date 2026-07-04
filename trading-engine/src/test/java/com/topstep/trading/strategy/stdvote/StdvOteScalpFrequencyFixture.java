package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.domain.Candle;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * SA4 two-trade fixture: extends the SA2/SA3 golden fixture with a second
 * complete, legitimate setup inside the SAME NY AM killzone (opens 9:45 ET).
 *
 * <p>Act 1 = the exact golden fixture (warmup + kz 0..22) ending in the
 * LONG MNQ emission @ 21023 / stop 21011. Then:
 *
 * <ol>
 *   <li>{@code fill/close candle} (kz+23): dips to 21022 (fills the 21023
 *       buy limit in the ExecutionEngine funnel) and trades 21039 high (the
 *       1R target 21035 fills → {@code closePosition} → the funnel counts
 *       the trade and publishes {@link com.topstep.trading.event.PositionClosedEvent}).</li>
 *   <li>7 bridge candles (kz+24..30): gentle drift while the re-arm
 *       cooldown (default 5 bars) elapses.</li>
 *   <li>Act 2 (kz+31..53): the same 23-candle price pattern as act 1's
 *       killzone sequence — every sequence gate genuinely fires a second
 *       time (fresh sweep of 21014 at kz+46, fresh displacement FVG
 *       [21020, 21023], fresh MSS above 21035, OTE retrace) producing a
 *       second LONG emission @ 21023 at kz+53 (10:38 ET, still inside the
 *       9:45–12:30 killzone).</li>
 * </ol>
 */
final class StdvOteScalpFrequencyFixture {

    static final String SYMBOL = StdvOteGoldenFixture.SYMBOL;
    static final Instant KILLZONE_OPEN = StdvOteGoldenFixture.KILLZONE_OPEN;

    /** Expected geometry shared by both emissions. */
    static final double EXPECTED_ENTRY = 21023.0;
    static final double EXPECTED_STOP = 21011.0;
    static final double ONE_R_TARGET = 21035.0;

    private StdvOteScalpFrequencyFixture() {}

    private static Candle kz(int minutesAfterOpen, double o, double h, double l, double c) {
        return StdvOteGoldenFixture.at(
                KILLZONE_OPEN.plus(minutesAfterOpen, ChronoUnit.MINUTES), o, h, l, c);
    }

    /** Candle that fills the 21023 buy limit AND the 21035 target (round trip). */
    static List<Candle> fillAndCloseCandles() {
        return List.of(kz(23, 21038, 21039, 21022, 21036));
    }

    /** Bridge candles while the re-arm cooldown elapses. */
    static List<Candle> bridgeCandles() {
        List<Candle> c = new ArrayList<>();
        c.add(kz(24, 21036, 21037,   21033,   21034));
        c.add(kz(25, 21034, 21035,   21032.5, 21033.5));
        c.add(kz(26, 21033.5, 21034.5, 21032, 21033));
        c.add(kz(27, 21033, 21034,   21031.5, 21032.5));
        c.add(kz(28, 21032.5, 21033.5, 21031, 21032));
        c.add(kz(29, 21032, 21033,   21031,  21032));
        c.add(kz(30, 21032, 21033,   21031,  21032));
        return c;
    }

    /** Act 2: the golden killzone pattern replayed at kz+31..53. */
    static List<Candle> secondActCandles() {
        List<Candle> base = StdvOteGoldenFixture.killzoneCandles();
        List<Candle> out = new ArrayList<>(base.size());
        for (int i = 0; i < base.size(); i++) {
            Candle b = base.get(i);
            out.add(StdvOteGoldenFixture.at(
                    KILLZONE_OPEN.plus(31 + i, ChronoUnit.MINUTES),
                    b.getOpen(), b.getHigh(), b.getLow(), b.getClose()));
        }
        return out;
    }

    /** Everything: warmup + act 1 + round trip + bridge + act 2. */
    static List<Candle> fullTwoTradeFixture() {
        List<Candle> all = new ArrayList<>(StdvOteGoldenFixture.fullFixture());
        all.addAll(fillAndCloseCandles());
        all.addAll(bridgeCandles());
        all.addAll(secondActCandles());
        return all;
    }
}
