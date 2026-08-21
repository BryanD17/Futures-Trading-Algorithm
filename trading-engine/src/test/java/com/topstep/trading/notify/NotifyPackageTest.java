package com.topstep.trading.notify;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The notify package's verification suite, converted from the standalone
 * {@code NotifyHarness} main() into JUnit 5 so it runs on every
 * {@code ./gradlew build} rather than only when someone remembers to invoke it.
 *
 * <p>Transport tests drive a real HTTP server on loopback: success, 429 with
 * {@code retry-after}, 5xx retry, permanent 4xx, queue overflow, and an
 * unreachable host. Assertions are unchanged from the harness.
 */
class NotifyPackageTest {

    // ── formatter ─────────────────────────────────────────────────────────

    private static OteAlert sample(OteAlert.Kind kind) {
        return new OteAlert(kind, "NQ", true,
                20280.25, 20305.50, 20330.75,
                20250.00, 20415.00,
                7, "PDL", "New York", 2,
                Instant.parse("2026-08-20T13:45:00Z"));
    }

    @Test
    void formatterEmbedShape() {
        String json = new OteAlertFormatter().format(sample(OteAlert.Kind.ARMED));
        assertTrue(json.startsWith("{\"embeds\":["), "embed wrapper present");
        assertTrue(json.contains("NQ") && json.contains("Bullish OTE armed"),
                "title carries symbol and direction");
        assertTrue(json.contains("20305.50"), "entry level rendered");
        assertTrue(json.contains("20250.00"), "invalidation rendered");
        assertTrue(json.contains("quality 7/10"), "raid quality surfaced");
        assertTrue(json.contains("PDL"), "raided level named");
        assertTrue(json.contains("New York"), "session surfaced");
        assertTrue(json.contains("2026-08-20T13:45:00Z"), "timestamp is ISO");
        assertTrue(json.contains("Not financial advice"),
                "MANDATORY disclaimer footer present on every payload");
    }

    @Test
    void invalidatedOmitsTargetButStillDisclaims() {
        String dead = new OteAlertFormatter().format(sample(OteAlert.Kind.INVALIDATED));
        assertFalse(dead.contains("First target"), "a dead setup must not show a target");
        assertTrue(dead.contains("Not financial advice"), "invalidated still disclaims");
    }

    @Test
    void formatterEscapesJsonMetacharacters() {
        OteAlert a = new OteAlert(OteAlert.Kind.ARMED, "NQ\"; drop\\", true,
                1, 2, 3, 0.5, 4, 6, "PD\nH", "NY", 2, Instant.EPOCH);
        String json = new OteAlertFormatter().format(a);
        assertTrue(json.contains("NQ\\\""), "quote escaped");
        assertTrue(json.contains("drop\\\\"), "backslash escaped");
        assertTrue(json.contains("PD\\nH"), "newline escaped");
        assertFalse(json.contains("PD\nH"), "no raw newline leaked into payload");
    }

    @Test
    void priceRoundingIsHalfUpAndKeepsTrailingZeros() {
        // Quarter-tick prices are the classic binary-float trap for %.2f.
        OteAlert a = new OteAlert(OteAlert.Kind.ARMED, "NQ", true,
                20280.125, 20305.675, 20330.0, 20250.0, 20415.0,
                null, null, null, 2, Instant.EPOCH);
        String json = new OteAlertFormatter().format(a);
        assertTrue(json.contains("20305.68"), "half-up rounding applied");
        assertTrue(json.contains("20330.00"), "trailing zeros preserved");
    }

    @Test
    void zeroDecimalInstrumentRendersWhole() {
        OteAlert ym = new OteAlert(OteAlert.Kind.ARMED, "YM", false,
                44100.6, 44120.4, 44150.0, 44200.0, 44000.0,
                null, null, null, 0, Instant.EPOCH);
        assertTrue(new OteAlertFormatter().format(ym).contains("44120"));
    }

    @Test
    void footerIsMandatory() {
        assertThrows(IllegalArgumentException.class, () -> new OteAlertFormatter("  "),
                "a paid channel must never post without the disclaimer");
    }

    @Test
    void riskRewardIsComputedFromTheSweetSpot() {
        assertEquals(1.9729, sample(OteAlert.Kind.ARMED).riskReward(), 0.01);
    }

    @Test
    void degenerateGeometryYieldsNaNRatherThanInfinity() {
        OteAlert degenerate = new OteAlert(OteAlert.Kind.ARMED, "NQ", true,
                100, 100, 100, 100, 200, null, null, null, 2, Instant.EPOCH);
        assertTrue(Double.isNaN(degenerate.riskReward()));
    }

    @Test
    void dedupeKeyIsStablePerZoneAndDistinctPerState() {
        assertEquals(sample(OteAlert.Kind.ARMED).dedupeKey(),
                sample(OteAlert.Kind.ARMED).dedupeKey(),
                "identical zones must produce identical keys");
        assertNotEquals(sample(OteAlert.Kind.ARMED).dedupeKey(),
                sample(OteAlert.Kind.REACTED).dedupeKey(),
                "a state transition must be publishable");
    }

    // ── config ────────────────────────────────────────────────────────────

    @Test
    void describeNeverLeaksTheWebhookUrl() {
        NotifyConfig cfg = new NotifyConfig(
                "https://discord.com/api/webhooks/123/SUPERSECRETTOKEN",
                List.of("MNQ"), 5_000L, 5, 2.0, 2,
                java.util.Map.of("MNQ", 0.25), java.util.Map.of("MNQ", 2));
        String described = cfg.describe();
        assertFalse(described.contains("SUPERSECRETTOKEN"),
                "the webhook is a credential and must never reach a log");
        assertTrue(described.contains("<redacted>"));
    }

    @Test
    void microTickSizesAreCorrectForThisDesk() {
        // MGC at the 0.25/2dp fallback would render every gold level wrong.
        NotifyConfig cfg = new NotifyConfig("https://x/y", List.of("MNQ", "MGC"),
                5_000L, 5, 2.0, 2,
                java.util.Map.of("MNQ", 0.25, "MGC", 0.10),
                java.util.Map.of("MNQ", 2, "MGC", 1));
        assertEquals(0.10, cfg.tickSize("MGC"), 1e-9);
        assertEquals(1, cfg.decimals("MGC"));
        assertEquals(0.25, cfg.tickSize("MNQ"), 1e-9);
        assertEquals(2, cfg.decimals("MNQ"));
    }

    @Test
    void missingWebhookIsRejectedRatherThanDefaulted() {
        assertThrows(IllegalArgumentException.class, () -> new NotifyConfig(
                null, List.of("MNQ"), 5_000L, 5, 2.0, 2,
                java.util.Map.of(), java.util.Map.of()));
    }

    // ── transport ─────────────────────────────────────────────────────────

    @Test
    void successfulDelivery() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        HttpServer server = serve(ex -> {
            received.add(body(ex));
            respond(ex, 204, "");
        });
        try (DiscordWebhookClient c = client(server, 128, 0)) {
            c.enqueue("{\"content\":\"one\"}");
            c.enqueue("{\"content\":\"two\"}");
            waitFor(() -> c.sentCount() == 2, 5000);
            assertEquals(2, c.sentCount(), "both payloads delivered");
            assertTrue(received.get(0).contains("one"), "payload arrived intact");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rateLimitHonoursRetryAfterAndIsNotAFailure() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = serve(ex -> {
            body(ex);
            if (hits.incrementAndGet() == 1) {
                ex.getResponseHeaders().add("retry-after", "0.2");
                respond(ex, 429, "{\"retry_after\":0.2}");
            } else {
                respond(ex, 204, "");
            }
        });
        try (DiscordWebhookClient c = client(server, 128, 0)) {
            long t0 = System.currentTimeMillis();
            c.enqueue("{\"content\":\"rl\"}");
            waitFor(() -> c.sentCount() == 1, 5000);
            long elapsed = System.currentTimeMillis() - t0;
            assertEquals(1, c.sentCount(), "429 retried and eventually delivered");
            assertTrue(elapsed >= 200, "retry-after honoured (waited >= 200ms)");
            assertEquals(2, hits.get(), "429 did not consume the attempt budget");
            assertEquals(0, c.failedCount(), "rate limiting is scheduling, not failure");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void serverErrorsAreRetriedToSuccess() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = serve(ex -> {
            body(ex);
            if (hits.incrementAndGet() < 3) respond(ex, 503, "nope");
            else respond(ex, 204, "");
        });
        try (DiscordWebhookClient c = client(server, 128, 0)) {
            c.enqueue("{\"content\":\"5xx\"}");
            waitFor(() -> c.sentCount() == 1, 8000);
            assertEquals(1, c.sentCount(), "5xx retried to success");
            assertEquals(3, hits.get(), "took exactly three attempts");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void permanentClientErrorDropsImmediately() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = serve(ex -> {
            body(ex);
            hits.incrementAndGet();
            respond(ex, 404, "unknown webhook");
        });
        try (DiscordWebhookClient c = client(server, 128, 0)) {
            c.enqueue("{\"content\":\"gone\"}");
            waitFor(() -> c.droppedCount() == 1, 5000);
            assertEquals(1, c.droppedCount(), "404 dropped rather than retried");
            assertEquals(1, hits.get(), "only one request made");
            assertEquals(0, c.failedCount(), "not a transient failure");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void queueOverflowShedsOldestAndStaysBounded() throws Exception {
        HttpServer server = serve(ex -> {
            body(ex);
            try { Thread.sleep(400); } catch (InterruptedException ignored) { }
            respond(ex, 204, "");
        });
        try (DiscordWebhookClient c = client(server, 4, 0)) {
            for (int i = 0; i < 40; i++) c.enqueue("{\"content\":\"" + i + "\"}");
            assertTrue(c.droppedCount() > 0, "overflow shed messages instead of blocking");
            assertTrue(c.queueDepth() <= 4, "queue stayed bounded");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unreachableHostIsRecordedAsFailureAndNothingEscapes() throws Exception {
        // Port 1: connection refused on every attempt. The contract is that a
        // dead webhook can never propagate into the trading path.
        try (DiscordWebhookClient c = new DiscordWebhookClient(
                "http://127.0.0.1:1/webhook", 8, 0, Duration.ofMillis(200))) {
            assertDoesNotThrow(() -> c.enqueue("{\"content\":\"void\"}"));
            waitFor(() -> c.failedCount() == 1, 15000);
            assertEquals(1, c.failedCount(), "unreachable host recorded as failure");
        }
    }

    // ── plumbing ──────────────────────────────────────────────────────────

    private interface Handler { void handle(HttpExchange ex) throws Exception; }

    private static HttpServer serve(Handler h) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/webhook", ex -> {
            try { h.handle(ex); } catch (Exception e) { throw new java.io.IOException(e); }
        });
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        return server;
    }

    private static DiscordWebhookClient client(HttpServer s, int cap, long interval) {
        return new DiscordWebhookClient(
                "http://127.0.0.1:" + s.getAddress().getPort() + "/webhook",
                cap, interval, Duration.ofSeconds(2));
    }

    private static String body(HttpExchange ex) {
        try (InputStream in = ex.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static void respond(HttpExchange ex, int code, String bodyText) {
        try {
            byte[] out = bodyText.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(code, out.length == 0 ? -1 : out.length);
            if (out.length > 0) {
                try (OutputStream os = ex.getResponseBody()) { os.write(out); }
            }
            ex.close();
        } catch (Exception ignored) {
        }
    }

    private static void waitFor(BooleanSupplier cond, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) return;
            Thread.sleep(25);
        }
    }
}
