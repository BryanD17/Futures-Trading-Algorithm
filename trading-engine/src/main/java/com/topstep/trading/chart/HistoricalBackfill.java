package com.topstep.trading.chart;

import com.topstep.trading.domain.Candle;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * Startup backfill: fetch N days of 1m history in chunks and replay it through
 * the SAME candle listener the live feed uses, oldest → newest, BEFORE live
 * polling starts.
 *
 * <p>This is the single highest-impact fix in the system. Without it:
 * <ul>
 *   <li>HTF bias stays NEUTRAL for hours after every restart (needs 15m swing
 *       structure), so gate M2 blocks everything;</li>
 *   <li>LevelEngine has no PDH/PDL for ~24h (it only learns them by living
 *       through a day rollover), so the raid pipeline is starved and raid
 *       scores fall back to values below the quality floor;</li>
 *   <li>the ChartEngine's 30m chart is empty, so the multi-hour OTE leg
 *       visible on the broker chart does not exist in memory.</li>
 * </ul>
 *
 * <p>Usage from TopstepConnector.startMarketDataPolling(), before the initial
 * live fetch (see PATCHES.md for the exact insertion):
 *
 * <pre>
 * HistoricalBackfill.run(symbol, 3,
 *     (start, end) -> fetchBarsRange(symbol, contractId, start, end),
 *     listener::onCandle,
 *     ts -> lastBarTimestamp.put(symbol, ts));
 * </pre>
 */
public final class HistoricalBackfill {

    /** Chunk width — small enough to stay under API row limits at 1m granularity. */
    private static final Duration CHUNK = Duration.ofHours(6);

    private HistoricalBackfill() {}

    /**
     * @param symbol        instrument (logging only)
     * @param days          how many days back to fill (2–3 covers PDH/PDL,
     *                      weekly opens, and full 15m/30m swing structure)
     * @param fetchRange    (startInclusive, endExclusive) → candles for that
     *                      window, ANY order, or empty list on market-closed
     * @param deliver       the same consumer the live feed uses
     *                      (must be idempotent for duplicate timestamps)
     * @param markDelivered called with the max delivered timestamp so the live
     *                      poller skips bars the backfill already replayed
     * @return number of candles delivered
     */
    public static int run(String symbol,
                          int days,
                          BiFunction<Instant, Instant, List<Candle>> fetchRange,
                          Consumer<Candle> deliver,
                          Consumer<Instant> markDelivered) {
        Instant end = Instant.now();
        Instant cursor = end.minus(Duration.ofDays(Math.max(1, days)));
        List<Candle> all = new ArrayList<>();

        while (cursor.isBefore(end)) {
            Instant chunkEnd = cursor.plus(CHUNK);
            if (chunkEnd.isAfter(end)) chunkEnd = end;
            try {
                List<Candle> chunk = fetchRange.apply(cursor, chunkEnd);
                if (chunk != null) all.addAll(chunk);
            } catch (Exception e) {
                // A failed chunk (weekend gap, transient 5xx) must not abort
                // the whole backfill — log and continue.
                System.err.println("[Backfill] " + symbol + " chunk "
                        + cursor + " → " + chunkEnd + " failed: " + e.getMessage());
            }
            cursor = chunkEnd;
        }

        // Strict chronological replay + de-dup by timestamp.
        all.sort((a, b) -> a.getTimestamp().compareTo(b.getTimestamp()));
        Instant last = null;
        int delivered = 0;
        for (Candle c : all) {
            if (last != null && !c.getTimestamp().isAfter(last)) continue;
            deliver.accept(c);
            last = c.getTimestamp();
            delivered++;
        }
        if (last != null && markDelivered != null) {
            markDelivered.accept(last);
        }
        System.out.println("[Backfill] " + symbol + ": delivered " + delivered
                + " historical 1m bars (" + days + " days). Chart memory is warm.");
        return delivered;
    }
}
