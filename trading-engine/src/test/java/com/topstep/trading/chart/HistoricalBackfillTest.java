package com.topstep.trading.chart;

import com.topstep.trading.domain.Candle;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HistoricalBackfill.run with a fake fetchRange: chunk continuity, strict
 * chronological de-duplicated delivery, per-chunk fault tolerance,
 * markDelivered semantics, and market-closed (empty) chunks.
 */
class HistoricalBackfillTest {

    private static final String SYM = "MNQ";

    private static Candle at(Instant ts) {
        return new Candle(SYM, ts, 100, 101, 99, 100, 1);
    }

    /** Capture every (start, end) window the backfill requests. */
    private static final class WindowRecorder implements BiFunction<Instant, Instant, List<Candle>> {
        final List<Instant[]> windows = new ArrayList<>();
        final BiFunction<Instant, Instant, List<Candle>> delegate;

        WindowRecorder(BiFunction<Instant, Instant, List<Candle>> delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<Candle> apply(Instant start, Instant end) {
            windows.add(new Instant[] {start, end});
            return delegate.apply(start, end);
        }
    }

    @Test
    void chunksCoverTheRequestedSpanWithNoGapsOrOverlaps() {
        WindowRecorder rec = new WindowRecorder((s, e) -> List.of());
        int delivered = HistoricalBackfill.run(SYM, 3, rec, c -> {}, ts -> {});

        assertEquals(0, delivered);
        // 3 days at 6h chunks = exactly 12 windows.
        assertEquals(12, rec.windows.size());
        // Continuity: each window ends exactly where the next begins.
        for (int i = 0; i < rec.windows.size() - 1; i++) {
            assertEquals(rec.windows.get(i)[1], rec.windows.get(i + 1)[0],
                    "gap/overlap between chunk " + i + " and " + (i + 1));
        }
        // Every chunk is exactly 6h and the total span is exactly 3 days.
        for (Instant[] w : rec.windows) {
            assertEquals(Duration.ofHours(6), Duration.between(w[0], w[1]));
        }
        Instant first = rec.windows.get(0)[0];
        Instant last = rec.windows.get(rec.windows.size() - 1)[1];
        assertEquals(Duration.ofDays(3), Duration.between(first, last));
    }

    @Test
    void outOfOrderAndDuplicateCandlesDeliverStrictlyAscendingOnce() {
        // Each chunk returns its bars REVERSED, and repeats its first bar —
        // plus every chunk re-returns a candle from the previous chunk
        // (duplicate across chunk boundaries).
        AtomicReference<Instant> prevChunkBar = new AtomicReference<>();
        BiFunction<Instant, Instant, List<Candle>> fake = (start, end) -> {
            List<Candle> out = new ArrayList<>();
            out.add(at(start.plus(Duration.ofMinutes(5))));
            out.add(at(start.plus(Duration.ofMinutes(1))));   // out of order
            out.add(at(start.plus(Duration.ofMinutes(5))));   // duplicate in-chunk
            if (prevChunkBar.get() != null) {
                out.add(at(prevChunkBar.get()));              // duplicate cross-chunk
            }
            prevChunkBar.set(start.plus(Duration.ofMinutes(5)));
            return out;
        };

        List<Candle> delivered = new ArrayList<>();
        int count = HistoricalBackfill.run(SYM, 1, fake, delivered::add, ts -> {});

        // 1 day / 6h = 4 chunks × 2 unique bars = 8 delivered.
        assertEquals(8, count);
        assertEquals(8, delivered.size());
        for (int i = 1; i < delivered.size(); i++) {
            assertTrue(delivered.get(i).getTimestamp()
                            .isAfter(delivered.get(i - 1).getTimestamp()),
                    "delivery must be strictly ascending (duplicates dropped)");
        }
    }

    @Test
    void aThrowingChunkIsSkippedAndTheRestStillDelivers() {
        AtomicInteger call = new AtomicInteger();
        BiFunction<Instant, Instant, List<Candle>> fake = (start, end) -> {
            if (call.incrementAndGet() == 3) {
                throw new RuntimeException("transient 5xx");
            }
            return List.of(at(start.plus(Duration.ofMinutes(1))));
        };

        List<Candle> delivered = new ArrayList<>();
        int count = HistoricalBackfill.run(SYM, 1, fake, delivered::add, ts -> {});

        // 4 chunks, one throws → 3 bars delivered, no abort.
        assertEquals(3, count);
        assertEquals(3, delivered.size());
        assertEquals(4, call.get(), "all chunks must still be attempted");
    }

    @Test
    void markDeliveredReceivesExactlyTheMaxDeliveredTimestamp() {
        BiFunction<Instant, Instant, List<Candle>> fake = (start, end) ->
                List.of(at(start.plus(Duration.ofMinutes(2))),
                        at(start.plus(Duration.ofMinutes(1))));

        List<Candle> delivered = new ArrayList<>();
        AtomicReference<Instant> marked = new AtomicReference<>();
        HistoricalBackfill.run(SYM, 2, fake, delivered::add, marked::set);

        Instant maxDelivered = delivered.get(delivered.size() - 1).getTimestamp();
        assertNotNull(marked.get());
        assertEquals(maxDelivered, marked.get());
        assertEquals(delivered.stream().map(Candle::getTimestamp)
                        .max(Instant::compareTo).orElseThrow(),
                marked.get());
    }

    @Test
    void emptyMarketClosedChunksAreTolerated() {
        AtomicReference<Instant> marked = new AtomicReference<>();
        int count = HistoricalBackfill.run(SYM, 3, (s, e) -> List.of(),
                c -> fail("nothing should be delivered"), marked::set);

        assertEquals(0, count);
        assertNull(marked.get(), "markDelivered must not fire when nothing was delivered");

        // Null returns (defensive) are tolerated the same way.
        int countNull = HistoricalBackfill.run(SYM, 1, (s, e) -> null,
                c -> fail("nothing should be delivered"), marked::set);
        assertEquals(0, countNull);
        assertNull(marked.get());
    }
}
