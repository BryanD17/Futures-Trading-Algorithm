package com.topstep.trading.connector;

import com.topstep.trading.chart.ChartEngine;
import com.topstep.trading.domain.Candle;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V2 Agent 02: the SIM warm boot must be deterministic (seeded), delivered
 * strictly ascending with a watermark handoff to live-sim ticks, warm
 * enough (>= 1500 bars) to flip /api/chart's warm flag, and STRUCTURED
 * enough that the ChartEngine forms at least one OTE zone during replay
 * (a pure random walk would not satisfy that).
 */
class SimWarmBootTest {

    private static final Instant END = Instant.parse("2026-07-08T14:00:00Z");

    @AfterEach
    void cleanup() {
        System.clearProperty(SimWarmBoot.DAYS_PROPERTY);
        System.clearProperty(SimWarmBoot.SEED_PROPERTY);
    }

    @Test
    void generatorIsDeterministicForSameSeedAndDays() {
        List<Candle> a = SimWarmBoot.generate("MNQ", 20000.0, 3, 42L, END);
        List<Candle> b = SimWarmBoot.generate("MNQ", 20000.0, 3, 42L, END);
        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) {
            Candle ca = a.get(i), cb = b.get(i);
            assertEquals(ca.getTimestamp(), cb.getTimestamp(), "ts diverged at " + i);
            assertEquals(ca.getOpen(), cb.getOpen());
            assertEquals(ca.getHigh(), cb.getHigh());
            assertEquals(ca.getLow(), cb.getLow());
            assertEquals(ca.getClose(), cb.getClose());
            assertEquals(ca.getVolume(), cb.getVolume());
        }
        // A different seed produces a DIFFERENT series (not accidentally
        // seed-independent).
        List<Candle> c = SimWarmBoot.generate("MNQ", 20000.0, 3, 7L, END);
        boolean anyDiff = false;
        for (int i = 0; i < a.size() && !anyDiff; i++) {
            anyDiff = a.get(i).getClose() != c.get(i).getClose();
        }
        assertTrue(anyDiff, "different seed must change the series");
    }

    @Test
    void seriesIsClockAlignedAscendingAndAnchored() {
        List<Candle> series = SimWarmBoot.generate("MNQ", 20000.0, 3, 42L, END);
        assertEquals(3 * 1440, series.size());
        for (int i = 1; i < series.size(); i++) {
            assertEquals(Duration.ofMinutes(1),
                    Duration.between(series.get(i - 1).getTimestamp(), series.get(i).getTimestamp()),
                    "candles must be exactly one minute apart (strictly ascending)");
        }
        Candle last = series.get(series.size() - 1);
        assertTrue(last.getTimestamp().isBefore(END),
                "series must end strictly before the live-tick era");
        assertEquals(20000.0, last.getClose(), 1e-9,
                "final close anchors to base price so live ticks continue seamlessly");
        // Sanity: highs/lows envelope open/close on every candle.
        for (Candle c : series) {
            assertTrue(c.getHigh() >= Math.max(c.getOpen(), c.getClose()));
            assertTrue(c.getLow() <= Math.min(c.getOpen(), c.getClose()));
        }
    }

    @Test
    void threeDayReplayWarmsTheChartAndFormsAnOteZone() {
        ChartEngine chart = new ChartEngine();
        chart.registerInstrument("MNQ", 0.25);

        boolean zoneSeen = false;
        List<Candle> series = SimWarmBoot.generate("MNQ", 20000.0, 3, 42L, END);
        for (Candle c : series) {
            chart.onCandle(c);
            if (!zoneSeen && chart.getActiveOteZone("MNQ").isPresent()) {
                zoneSeen = true;
            }
        }
        long bars = chart.snapshot("MNQ", 10).oneMinuteBarsIngested();
        assertTrue(bars >= 1500, "3-day replay must exceed the warm threshold, got " + bars);
        assertTrue(zoneSeen,
                "the structured synthetic path must form at least one OTE zone during replay");
    }

    @Test
    void daysAndSeedPropertiesAreHonoredAndClamped() {
        assertEquals(1, SimWarmBoot.clampDays(0));
        assertEquals(1, SimWarmBoot.clampDays(-5));
        assertEquals(7, SimWarmBoot.clampDays(99));
        assertEquals(3, SimWarmBoot.clampDays(3));

        System.clearProperty(SimWarmBoot.DAYS_PROPERTY);
        assertEquals(3, SimWarmBoot.configuredDays(), "default depth is 3 days");
        System.setProperty(SimWarmBoot.DAYS_PROPERTY, "99");
        assertEquals(7, SimWarmBoot.configuredDays(), "depth clamps to 7");
        System.setProperty(SimWarmBoot.DAYS_PROPERTY, "1");
        assertEquals(1, SimWarmBoot.configuredDays());

        System.clearProperty(SimWarmBoot.SEED_PROPERTY);
        assertEquals(42L, SimWarmBoot.configuredSeed(), "default seed is 42");
        System.setProperty(SimWarmBoot.SEED_PROPERTY, "1234");
        assertEquals(1234L, SimWarmBoot.configuredSeed());
    }

    @Test
    void mockConnectorRepliesHistoryThenTicksStrictlyAfterWatermark() throws Exception {
        // 1-day depth keeps the test fast (1440 candles through the listener).
        System.setProperty(SimWarmBoot.DAYS_PROPERTY, "1");
        MockConnector mock = new MockConnector(50_000.0);
        mock.connect();

        List<Candle> received = new ArrayList<>();
        Object lock = new Object();
        mock.subscribeMarketData("MNQ", c -> {
            synchronized (lock) { received.add(c); }
        });

        // The warm boot runs synchronously inside subscribeMarketData.
        int historyCount;
        Instant watermark;
        synchronized (lock) {
            assertTrue(received.size() >= 1440,
                    "warm boot must deliver the full synthetic day before ticks");
            historyCount = 1440;
            watermark = received.get(historyCount - 1).getTimestamp();
            for (int i = 1; i < historyCount; i++) {
                assertTrue(received.get(i).getTimestamp()
                                .isAfter(received.get(i - 1).getTimestamp()),
                        "warm-boot delivery must be strictly ascending");
            }
        }

        // The immediate first tick (initialDelay 0) lands asynchronously;
        // wait briefly, then assert every post-history candle is strictly
        // after the watermark (the handoff guarantee).
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            synchronized (lock) {
                if (received.size() > historyCount) break;
            }
            Thread.sleep(50);
        }
        mock.disconnect();
        synchronized (lock) {
            assertTrue(received.size() > historyCount,
                    "a live-sim tick must follow the warm boot");
            for (int i = historyCount; i < received.size(); i++) {
                assertTrue(received.get(i).getTimestamp().isAfter(watermark),
                        "live-sim ticks must be strictly after the warm-boot watermark");
            }
        }
    }
}
