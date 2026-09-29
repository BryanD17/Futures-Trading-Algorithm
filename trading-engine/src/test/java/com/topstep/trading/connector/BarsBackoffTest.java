package com.topstep.trading.connector;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Backoff for a rate-limited bar fetch.
 *
 * <p>Field incident (LIVE 2026-09-29, Main 78699f1): during the 7-day boot
 * backfill the gateway returned HTTP 429 for 29 MES and 14 MGC chunk
 * requests ({@code HTTP error fetching bars for MES: 429 -}). A throttled
 * chunk returned the same empty list as a market-closed chunk, so the
 * backfill silently came up short. Pure backoff cases ported from #151;
 * HTTP cases added for AGENT-05.12 against a local fake gateway.
 */
class BarsBackoffTest {

    private static final String TWO_BARS = "{\"bars\":["
        + "{\"t\":\"2026-09-29T14:00:00Z\",\"o\":4000.1,\"h\":4001.0,\"l\":3999.5,\"c\":4000.5,\"v\":120},"
        + "{\"t\":\"2026-09-29T14:01:00Z\",\"o\":4000.5,\"h\":4002.0,\"l\":4000.0,\"c\":4001.7,\"v\":95}"
        + "],\"success\":true,\"errorCode\":0}";

    private static Response withRetryAfter(String headerValue) {
        Response.Builder builder = new Response.Builder()
            .request(new Request.Builder().url("https://example.invalid/").build())
            .protocol(Protocol.HTTP_1_1)
            .code(429)
            .message("Too Many Requests");
        if (headerValue != null) {
            builder.header("Retry-After", headerValue);
        }
        return builder.build();
    }

    private FakeTopstepX fake;
    private TopstepConnector connector;
    private final List<Long> sleeps = new CopyOnWriteArrayList<>();
    private ListAppender<ILoggingEvent> logs;
    private Logger connectorLogger;

    @BeforeEach
    void start() throws Exception {
        fake = new FakeTopstepX();
        connector = fake.connector();
        connector.sleeper = sleeps::add;   // no real waiting
        connectorLogger = (Logger) LoggerFactory.getLogger(TopstepConnector.class);
        logs = new ListAppender<>();
        logs.start();
        connectorLogger.addAppender(logs);
    }

    @AfterEach
    void stop() {
        connectorLogger.detachAppender(logs);
        fake.close();
    }

    private TopstepConnector.BarsFetch fetch() {
        return connector.fetchBarsWithRetry("MES", "CON.F.US.MES.Z26",
            Instant.parse("2026-09-29T14:00:00Z"), Instant.parse("2026-09-29T20:00:00Z"),
            2, 1, TopstepConnector.BARS_MAX_ATTEMPTS);
    }

    // ── pure backoff arithmetic (from #151) ─────────────────────────────

    @Test
    void honoursRetryAfterInSeconds() {
        assertEquals(2_000L, TopstepConnector.retryAfterMillis(withRetryAfter("2"), 1));
    }

    @Test
    void clampsAnAbsurdRetryAfterToTheCeiling() {
        assertEquals(TopstepConnector.BARS_BACKOFF_MAX_MS,
            TopstepConnector.retryAfterMillis(withRetryAfter("600"), 1));
    }

    @Test
    void fallsBackToExponentialBackoffWithoutTheHeader() {
        assertEquals(500L, TopstepConnector.retryAfterMillis(withRetryAfter(null), 1));
        assertEquals(1_000L, TopstepConnector.retryAfterMillis(withRetryAfter(null), 2));
        assertEquals(2_000L, TopstepConnector.retryAfterMillis(withRetryAfter(null), 3));
    }

    @Test
    void exponentialBackoffIsClampedToo() {
        assertEquals(TopstepConnector.BARS_BACKOFF_MAX_MS,
            TopstepConnector.retryAfterMillis(withRetryAfter(null), 20));
        assertEquals(TopstepConnector.BARS_BACKOFF_MAX_MS,
            TopstepConnector.retryAfterMillis((String) null, 500));
    }

    @Test
    void unparseableRetryAfterFallsBackRatherThanThrowing() {
        assertEquals(500L,
            TopstepConnector.retryAfterMillis(withRetryAfter("Wed, 21 Oct 2026 07:28:00 GMT"), 1));
    }

    // ── HTTP behaviour against the fake gateway ─────────────────────────

    @Test
    void a429ThenA200YieldsTheBars() {
        fake.bars.add(new FakeTopstepX.Scripted(429, null, ""));
        fake.bars.add(new FakeTopstepX.Scripted(200, null, TWO_BARS));

        TopstepConnector.BarsFetch f = fetch();

        assertFalse(f.throttled);
        assertEquals(2, f.candles.size());
        assertEquals(4001.7, f.candles.get(1).getClose(), 1e-9);
        assertEquals(2, fake.count("/api/History/retrieveBars"));
        assertEquals(List.of(500L), sleeps);
        assertEquals(0, connector.getThrottledBarFetches());
    }

    @Test
    void retryAfterHeaderFromTheGatewayIsHonoured() {
        fake.bars.add(new FakeTopstepX.Scripted(429, "3", ""));
        fake.bars.add(new FakeTopstepX.Scripted(429, "600", ""));
        fake.bars.add(new FakeTopstepX.Scripted(200, null, TWO_BARS));

        TopstepConnector.BarsFetch f = fetch();

        assertEquals(2, f.candles.size());
        assertEquals(List.of(3_000L, TopstepConnector.BARS_BACKOFF_MAX_MS), sleeps);
    }

    @Test
    void persistent429IsReportedThrottledNotAsAnEmptyMarket() {
        fake.defaultBars = new FakeTopstepX.Scripted(429, null, "");

        TopstepConnector.BarsFetch f = fetch();

        assertTrue(f.throttled);
        assertTrue(f.candles.isEmpty());
        assertEquals(TopstepConnector.BARS_MAX_ATTEMPTS, fake.count("/api/History/retrieveBars"));
        assertEquals(List.of(500L, 1_000L, 2_000L, 4_000L), sleeps);
        assertEquals(1, connector.getThrottledBarFetches());
        assertTrue(logs.list.stream().anyMatch(e -> e.getLevel() == Level.ERROR
                && e.getFormattedMessage().startsWith("THROTTLED bars for MES")),
            "a throttled range must be logged at ERROR as THROTTLED");
    }

    @Test
    void anEmptyButSuccessfulRangeIsNotThrottled() {
        TopstepConnector.BarsFetch f = fetch();
        assertFalse(f.throttled);
        assertTrue(f.candles.isEmpty());
        assertTrue(sleeps.isEmpty());
    }

    @Test
    void totalBackoffAcrossTheBootIsBoundedByTheBudget() {
        // Every request throttled with the maximum Retry-After, for far more
        // chunks than a 7-day x 3-symbol backfill issues.
        fake.defaultBars = new FakeTopstepX.Scripted(429, "600", "");
        for (int i = 0; i < 20; i++) {
            assertTrue(fetch().throttled);
        }
        long slept = sleeps.stream().mapToLong(Long::longValue).sum();
        assertEquals(TopstepConnector.BARS_BACKOFF_BUDGET_MS, slept,
            "total added delay is capped by the shared budget");
        assertEquals(0, connector.getBarsBackoffBudgetRemainingMs());
        // Once the budget is spent a 429 is reported at once, with no sleep.
        int before = sleeps.size();
        assertTrue(fetch().throttled);
        assertEquals(before, sleeps.size());
    }

    @Test
    void singleAttemptFetchDoesNotSleep() {
        fake.defaultBars = new FakeTopstepX.Scripted(429, null, "");
        TopstepConnector.BarsFetch f = connector.fetchBarsWithRetry("MES", "CON.F.US.MES.Z26",
            Instant.parse("2026-09-29T14:00:00Z"), Instant.parse("2026-09-29T15:00:00Z"), 2, 1, 1);
        assertTrue(f.throttled);
        assertTrue(sleeps.isEmpty());
        assertEquals(1, fake.count("/api/History/retrieveBars"));
    }
}
