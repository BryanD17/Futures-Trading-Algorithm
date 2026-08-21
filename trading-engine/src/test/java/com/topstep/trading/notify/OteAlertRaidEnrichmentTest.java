package com.topstep.trading.notify;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.topstep.trading.chart.ChartEngine;
import com.topstep.trading.chart.OteState;
import com.topstep.trading.chartstate.ChartStateQueryAPI;
import com.topstep.trading.chartstate.KnownLevel;
import com.topstep.trading.chartstate.LevelType;
import com.topstep.trading.chartstate.LiquidityRaid;
import com.topstep.trading.chartstate.RaidDirection;
import com.topstep.trading.domain.Candle;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Proves the raid-enrichment fix end to end: a real {@link ChartEngine} driven
 * to an ARMED OTE zone, a chart-state view holding a real {@link LiquidityRaid},
 * and the resulting embed captured off a loopback HTTP server.
 *
 * <p>WHY THIS EXISTS: the publisher was originally handed its own
 * {@code ChartStateManager} that nothing ever fed, so the raid lookup always
 * came back empty, {@code raidScore} was always null, and
 * {@code OTE_ALERT_MIN_RAID} silently never gated anything. A config knob that
 * does nothing is worse than a missing one — you tune it believing it works.
 * These tests fail if that regresses.
 */
class OteAlertRaidEnrichmentTest {

    private static final String SYM = "MNQ";
    private static final double TICK = 0.25;
    private static final Instant T0 = Instant.parse("2026-01-05T10:00:00Z");

    /** Same shape as ChartEngineOteLifecycleTest: dip origin, up-leg, chop. */
    private static final double[] BARS_30M = {
            20000, 19995, 19990, 19995, 20000,
            20010, 20025, 20040,
            20035, 20030
    };

    private static Instant bucket(int i) {
        return T0.plus(Duration.ofMinutes(30L * i));
    }

    private static Candle flat(Instant ts, double p) {
        return new Candle(SYM, ts, p, p, p, p, 1);
    }

    private static Candle ohlc(Instant ts, double o, double h, double l, double c) {
        return new Candle(SYM, ts, o, h, l, c, 1);
    }

    /** A ChartEngine whose MNQ zone is ARMED — the only state that publishes. */
    private static ChartEngine engineWithArmedZone() {
        ChartEngine engine = new ChartEngine();
        engine.registerInstrument(SYM, TICK);
        for (int i = 0; i < BARS_30M.length; i++) {
            engine.onCandle(flat(bucket(i), BARS_30M[i]));
        }
        engine.onCandle(flat(bucket(10), 20030));                       // FORMING
        engine.onCandle(ohlc(bucket(10).plusSeconds(60), 20020, 20020, 20012, 20012));
        engine.onCandle(ohlc(bucket(10).plusSeconds(120), 20010, 20010, 20008, 20009.5));
        assertEquals(OteState.ARMED, engine.getActiveOteZone(SYM).orElseThrow().state(),
                "fixture precondition: the zone must be ARMED before we assert on alerts");
        return engine;
    }

    /** A raid on PDL with an explicit quality score. */
    private static LiquidityRaid raidWithQuality(int quality) {
        KnownLevel pdl = new KnownLevel(LevelType.PDL, 19990.0, T0);
        LiquidityRaid raid = new LiquidityRaid(
                SYM, pdl, RaidDirection.LOW_SWEEP, T0, 0,
                19995.0, 19985.0, 19992.0, 19993.0, 20.0, TICK);
        raid.setQualityScore(quality, List.of("test"));
        return raid;
    }

    /**
     * A ChartStateQueryAPI that answers the two directional raid accessors and
     * returns harmless defaults for everything else.
     *
     * <p>A reflective proxy rather than a hand-written stub so the test does not
     * have to track every method on a wide interface — the subject here is the
     * publisher's enrichment path, not the query API.
     */
    private static ChartStateQueryAPI queryApiWithRaid(LiquidityRaid raid) {
        return (ChartStateQueryAPI) Proxy.newProxyInstance(
                ChartStateQueryAPI.class.getClassLoader(),
                new Class<?>[] { ChartStateQueryAPI.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "getActiveBullishRaid" -> Optional.of(raid);
                    case "getActiveBearishRaid" -> Optional.empty();
                    case "isInNY" -> true;
                    case "isInLondon", "isInAsia" -> false;
                    default -> {
                        Class<?> rt = method.getReturnType();
                        if (rt == Optional.class) yield Optional.empty();
                        if (rt == List.class) yield List.of();
                        if (rt == boolean.class) yield false;
                        if (rt == int.class) yield 0;
                        if (rt == double.class) yield 0.0;
                        yield null;
                    }
                });
    }

    private static NotifyConfig config(String webhookUrl, int minRaid, double minRr) {
        return new NotifyConfig(webhookUrl, List.of(SYM), 1_000L, minRaid, minRr, 2,
                Map.of(SYM, TICK), Map.of(SYM, 2));
    }

    // ── the two assertions SA2 asked for ──────────────────────────────────

    @Test
    void raidScoreReachesTheFormattedEmbed() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        HttpServer server = serve(ex -> {
            received.add(body(ex));
            respond(ex, 204);
        });
        Function<String, ChartStateQueryAPI> provider = s -> queryApiWithRaid(raidWithQuality(7));
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/webhook";

        try (DiscordWebhookClient webhook = new DiscordWebhookClient(url, 32, 0, Duration.ofSeconds(2));
             OteAlertPublisher publisher = new OteAlertPublisher(
                     engineWithArmedZone(), provider, webhook, config(url, 5, 1.0))) {
            publisher.start();
            waitFor(() -> !received.isEmpty(), 8000);

            assertFalse(received.isEmpty(), "an ARMED zone with a passing raid must publish");
            String embed = received.get(0);
            assertTrue(embed.contains("quality 7/10"),
                    "raid quality must reach the embed — this is the whole fix: " + embed);
            assertTrue(embed.contains("PDL"), "the raided level must be named");
            assertTrue(embed.contains("Not financial advice"), "disclaimer is mandatory");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void minRaidScoreNowActuallyGates() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        HttpServer server = serve(ex -> {
            received.add(body(ex));
            respond(ex, 204);
        });
        // Raid quality 3 against a floor of 9: must be suppressed. Before the
        // fix raidScore was null, the gate short-circuited, and this published.
        Function<String, ChartStateQueryAPI> provider = s -> queryApiWithRaid(raidWithQuality(3));
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/webhook";

        try (DiscordWebhookClient webhook = new DiscordWebhookClient(url, 32, 0, Duration.ofSeconds(2));
             OteAlertPublisher publisher = new OteAlertPublisher(
                     engineWithArmedZone(), provider, webhook, config(url, 9, 1.0))) {
            publisher.start();
            Thread.sleep(3_000);   // several poll cycles at 1s

            assertTrue(received.isEmpty(),
                    "a raid below OTE_ALERT_MIN_RAID must be suppressed, got: " + received);
            assertEquals(0, webhook.sentCount(), "nothing should have been enqueued at all");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void managerConstructorStillCompilesAndYieldsNoRaid() throws Exception {
        // The original ChartStateManager form is retained for callers/tests that
        // have no strategy to borrow a view from. It must still work — it simply
        // enriches nothing, which is honest rather than wrong.
        List<String> received = new CopyOnWriteArrayList<>();
        HttpServer server = serve(ex -> {
            received.add(body(ex));
            respond(ex, 204);
        });
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/webhook";
        try (DiscordWebhookClient webhook = new DiscordWebhookClient(url, 32, 0, Duration.ofSeconds(2));
             OteAlertPublisher publisher = new OteAlertPublisher(
                     engineWithArmedZone(),
                     new com.topstep.trading.chartstate.ChartStateManager(),
                     webhook, config(url, 5, 1.0))) {
            publisher.start();
            waitFor(() -> !received.isEmpty(), 8000);
            assertFalse(received.isEmpty(), "still publishes; only the raid fields are absent");
            assertFalse(received.get(0).contains("quality"),
                    "an unfed manager has no raid to report");
        } finally {
            server.stop(0);
        }
    }

    // ── plumbing ──────────────────────────────────────────────────────────

    private interface Handler { void handle(HttpExchange ex) throws Exception; }

    private static HttpServer serve(Handler h) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/webhook", ex -> {
            try { h.handle(ex); } catch (Exception e) { throw new java.io.IOException(e); }
        });
        server.setExecutor(Executors.newFixedThreadPool(2));
        server.start();
        return server;
    }

    private static String body(HttpExchange ex) {
        try (InputStream in = ex.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static void respond(HttpExchange ex, int code) {
        try {
            ex.sendResponseHeaders(code, -1);
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
