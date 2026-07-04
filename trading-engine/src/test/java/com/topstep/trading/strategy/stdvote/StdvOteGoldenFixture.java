package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.domain.Candle;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared deterministic MNQ candle fixture for the SA3 golden/scalp tests —
 * the exact same 128-candle sequence {@code StdvOteWiringIntegrationTest}
 * uses (kept byte-identical there; this copy exists so the golden regression
 * test does not depend on, or modify, the SA2 test class).
 *
 * <p>Legacy emission at commit 36f07c2 (captured from a HEAD run before any
 * SA3 change): LONG MNQ, entry 21023.0, stop 21011.0, target 21058.0,
 * RR 35/12 = 2.9166666666666665, TIER_1, size 6.
 */
final class StdvOteGoldenFixture {

    static final String SYMBOL = "MNQ";
    static final Instant WARMUP_START = Instant.parse("2026-06-15T12:00:00Z");
    static final Instant KILLZONE_OPEN = Instant.parse("2026-06-15T13:45:00Z");

    private StdvOteGoldenFixture() {}

    static Candle at(Instant ts, double o, double h, double l, double c) {
        return new Candle(SYMBOL, ts, o, h, l, c, 100);
    }

    private static Candle kz(int minutesAfterOpen, double o, double h, double l, double c) {
        return at(KILLZONE_OPEN.plus(minutesAfterOpen, ChronoUnit.MINUTES), o, h, l, c);
    }

    private static List<Candle> window15m(Instant start, double o, double h, double l, double c) {
        List<Candle> out = new ArrayList<>(15);
        double prevClose = o;
        for (int i = 0; i < 15; i++) {
            double barClose = o + (c - o) * (i + 1) / 15.0;
            double barOpen = prevClose;
            double hi = Math.max(barOpen, barClose) + 0.25;
            double lo = Math.min(barOpen, barClose) - 0.25;
            if (i == 7) hi = h;
            if (i == 3) lo = l;
            out.add(at(start.plus(i, ChronoUnit.MINUTES), barOpen, hi, lo, barClose));
            prevClose = barClose;
        }
        return out;
    }

    static List<Candle> warmupCandles() {
        List<Candle> all = new ArrayList<>();
        Instant t = WARMUP_START;
        double[][] w = {
                { 21000, 21010, 20999, 21008 },
                { 21008, 21020, 21006, 21014 },
                { 21014, 21016, 21000, 21006 },
                { 21006, 21024, 21005, 21023 },
                { 21023, 21026, 21015, 21025 },
                { 21025, 21030, 21020, 21029 },
                { 21029, 21040, 21026, 21032 },
        };
        for (double[] win : w) {
            all.addAll(window15m(t, win[0], win[1], win[2], win[3]));
            t = t.plus(15, ChronoUnit.MINUTES);
        }
        return all;
    }

    static List<Candle> killzoneCandles() {
        List<Candle> c = new ArrayList<>();
        c.add(kz(0,  21032, 21034,    21030, 21031));
        c.add(kz(1,  21031, 21032,    21026, 21027));
        c.add(kz(2,  21027, 21028,    21025, 21026));
        c.add(kz(3,  21026, 21027,    21024, 21025));
        c.add(kz(4,  21025, 21028,    21025, 21027));
        c.add(kz(5,  21027, 21029,    21026, 21028));
        c.add(kz(6,  21028, 21028.5,  21019, 21020));
        c.add(kz(7,  21020, 21021,    21014, 21017));
        c.add(kz(8,  21017, 21020,    21015.5, 21019));
        c.add(kz(9,  21019, 21024,    21017, 21023));
        c.add(kz(10, 21023, 21028,    21022, 21027));
        c.add(kz(11, 21027, 21032,    21026, 21031));
        c.add(kz(12, 21031, 21035,    21030, 21034));
        c.add(kz(13, 21034, 21034.75, 21031, 21033));
        c.add(kz(14, 21033, 21034,    21032, 21033));
        c.add(kz(15, 21033, 21033.5,  21012, 21016));
        c.add(kz(16, 21016, 21020,    21014, 21018));
        c.add(kz(17, 21018, 21028,    21017, 21027));
        c.add(kz(18, 21027, 21039.5,  21023, 21039.5));
        c.add(kz(19, 21039.5, 21046,  21036, 21045));
        c.add(kz(20, 21045, 21052,    21042, 21050));
        c.add(kz(21, 21050, 21050.5,  21036, 21037));
        c.add(kz(22, 21037, 21038,    21024, 21038));
        return c;
    }

    static List<Candle> fullFixture() {
        List<Candle> all = new ArrayList<>(warmupCandles());
        all.addAll(killzoneCandles());
        return all;
    }
}
