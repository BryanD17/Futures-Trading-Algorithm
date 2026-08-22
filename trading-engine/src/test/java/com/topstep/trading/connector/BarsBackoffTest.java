package com.topstep.trading.connector;

import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Backoff for a rate-limited bar fetch.
 *
 * <p>Field incident (2026-08-17): a 7-day LIVE backfill across three symbols
 * issues ~28 chunk requests per symbol; the gateway answered every MES chunk
 * with HTTP 429. Because a throttled chunk returned the same empty list as a
 * market-closed chunk, the boot reported a successful backfill of 0 bars and
 * MES traded cold.
 */
class BarsBackoffTest {

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

    @Test
    void honoursRetryAfterInSeconds() {
        assertEquals(2_000L, TopstepConnector.retryAfterMillis(withRetryAfter("2"), 1));
    }

    @Test
    void clampsAnAbsurdRetryAfterToTheCeiling() {
        // A gateway asking for a 10-minute wait must not stall the whole boot.
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
    }

    @Test
    void unparseableRetryAfterFallsBackRatherThanThrowing() {
        // The HTTP-date form is legal and we do not parse it.
        assertEquals(500L,
            TopstepConnector.retryAfterMillis(withRetryAfter("Wed, 21 Oct 2026 07:28:00 GMT"), 1));
    }
}
