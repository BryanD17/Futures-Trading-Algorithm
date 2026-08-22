package com.topstep.trading.notify;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Posts pre-encoded JSON payloads to a Discord webhook.
 *
 * <p><b>The contract that matters:</b> {@link #enqueue} never blocks, never
 * throws, and never propagates a failure to the caller. Alerts are a courtesy
 * feature bolted onto a trading engine; a Discord outage, a revoked webhook, or
 * a saturated network must not be able to stall or crash the path that manages
 * live positions. Everything here degrades to a dropped message and a log line.
 *
 * <p><b>Rate limiting.</b> Discord allows roughly 30 requests per minute per
 * webhook. Two mechanisms keep us inside that: a hard minimum interval between
 * sends, and honouring {@code retry-after} when the server pushes back with a
 * 429. The interval is enforced on the worker thread, so a burst of alerts
 * queues rather than being rejected.
 *
 * <p><b>Retry policy.</b> Transient failures (IO errors, 5xx) are retried with
 * exponential backoff. A 429 sleeps for the server-specified duration and does
 * not count against the attempt budget, because it is a scheduling instruction
 * rather than a failure. Other 4xx responses are dropped immediately: a 401 or
 * 404 means the webhook is wrong or deleted, and retrying a permanent client
 * error just burns the rate limit.
 *
 * <p><b>Backpressure.</b> The queue is bounded. On overflow the oldest pending
 * alert is discarded rather than the newest, because in a market context the
 * most recent state is the useful one; a stale ARMED notice delivered late is
 * worse than no notice.
 */
public final class DiscordWebhookClient implements AutoCloseable {

    private static final System.Logger LOG =
            System.getLogger(DiscordWebhookClient.class.getName());

    /** Discord's documented ceiling is ~30/min. 2s spacing keeps us under it. */
    private static final long DEFAULT_MIN_INTERVAL_MS = 2_000;
    private static final int MAX_ATTEMPTS = 3;
    private static final int DEFAULT_QUEUE_CAPACITY = 128;
    /** Guard against a hostile or buggy retry-after that would park the worker. */
    private static final long MAX_RETRY_AFTER_MS = 60_000;

    private final URI endpoint;
    private final HttpClient http;
    private final BlockingQueue<String> queue;
    private final Thread worker;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final long minIntervalMs;

    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    /** Test seam: counts down once per terminal outcome (sent, dropped, failed). */
    private volatile CountDownLatch settled;

    private long lastSendAt = 0L;

    public DiscordWebhookClient(String webhookUrl) {
        this(webhookUrl, DEFAULT_QUEUE_CAPACITY, DEFAULT_MIN_INTERVAL_MS, Duration.ofSeconds(10));
    }

    public DiscordWebhookClient(String webhookUrl, int queueCapacity,
                                long minIntervalMs, Duration timeout) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            throw new IllegalArgumentException("webhookUrl must not be blank");
        }
        this.endpoint = URI.create(webhookUrl);
        this.minIntervalMs = Math.max(0, minIntervalMs);
        this.queue = new ArrayBlockingQueue<>(Math.max(1, queueCapacity));
        this.http = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.worker = new Thread(this::drainLoop, "discord-webhook");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    /**
     * Queue a payload for delivery. Returns false if it was dropped.
     *
     * <p>Non-blocking by construction. The boolean is for metrics and tests;
     * callers on the market-data path are expected to ignore it.
     */
    public boolean enqueue(String jsonPayload) {
        if (jsonPayload == null || !running.get()) return false;
        // Drop-oldest: shed stale state, keep the freshest view of the market.
        while (!queue.offer(jsonPayload)) {
            String evicted = queue.poll();
            if (evicted == null) continue;
            dropped.incrementAndGet();
            countDown();
            LOG.log(System.Logger.Level.WARNING,
                    "Discord queue full, dropped oldest alert");
        }
        return true;
    }

    private void drainLoop() {
        while (running.get() || !queue.isEmpty()) {
            String payload;
            try {
                payload = queue.poll(250, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (payload == null) continue;
            try {
                deliver(payload);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                // Belt and braces: the worker must never die.
                failed.incrementAndGet();
                countDown();
                LOG.log(System.Logger.Level.ERROR, "Discord worker caught unexpected error", e);
            }
        }
    }

    private void deliver(String payload) throws InterruptedException {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            respectInterval();

            HttpResponse<String> response;
            try {
                HttpRequest request = HttpRequest.newBuilder(endpoint)
                        .header("Content-Type", "application/json")
                        .header("User-Agent", "CertifiedTraders-Engine/1.0")
                        .timeout(Duration.ofSeconds(10))
                        .POST(HttpRequest.BodyPublishers.ofString(payload))
                        .build();
                response = http.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                // Network-level failure: worth retrying.
                if (attempt == MAX_ATTEMPTS) {
                    failed.incrementAndGet();
                    countDown();
                    LOG.log(System.Logger.Level.ERROR,
                            "Discord delivery failed after " + MAX_ATTEMPTS + " attempts: "
                                    + e.getMessage());
                    return;
                }
                backoff(attempt);
                continue;
            }

            int code = response.statusCode();

            if (code >= 200 && code < 300) {
                sent.incrementAndGet();
                countDown();
                return;
            }

            if (code == 429) {
                long waitMs = parseRetryAfterMs(response);
                LOG.log(System.Logger.Level.WARNING,
                        "Discord rate limited, waiting " + waitMs + "ms");
                Thread.sleep(waitMs);
                // Deliberately does not consume an attempt: this is scheduling,
                // not failure. Bounded by MAX_RETRY_AFTER_MS above.
                attempt--;
                continue;
            }

            if (code >= 400 && code < 500) {
                // 401/403/404 mean the webhook is wrong or gone. Retrying cannot
                // help and would only consume the rate limit budget.
                dropped.incrementAndGet();
                countDown();
                LOG.log(System.Logger.Level.ERROR,
                        "Discord rejected payload with " + code + " (permanent, dropping). "
                                + "Check the webhook URL is still valid.");
                return;
            }

            // 5xx: Discord's problem, retry.
            if (attempt == MAX_ATTEMPTS) {
                failed.incrementAndGet();
                countDown();
                LOG.log(System.Logger.Level.ERROR,
                        "Discord returned " + code + " after " + MAX_ATTEMPTS + " attempts");
                return;
            }
            backoff(attempt);
        }
    }

    /**
     * Discord sends {@code retry-after} in seconds (possibly fractional) on the
     * REST API. Clamped so a malformed header cannot park the worker forever.
     */
    private long parseRetryAfterMs(HttpResponse<String> response) {
        long fallback = 1_000L;
        try {
            String header = response.headers().firstValue("retry-after").orElse(null);
            if (header == null) return fallback;
            double seconds = Double.parseDouble(header.trim());
            long ms = (long) Math.ceil(seconds * 1000.0);
            return Math.max(100L, Math.min(ms, MAX_RETRY_AFTER_MS));
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private void respectInterval() throws InterruptedException {
        if (minIntervalMs <= 0) return;
        long since = System.currentTimeMillis() - lastSendAt;
        if (since < minIntervalMs) {
            Thread.sleep(minIntervalMs - since);
        }
        lastSendAt = System.currentTimeMillis();
    }

    private void backoff(int attempt) throws InterruptedException {
        Thread.sleep(Math.min(500L * (1L << (attempt - 1)), 8_000L));
    }

    private void countDown() {
        CountDownLatch l = settled;
        if (l != null) l.countDown();
    }

    /** Test seam. Not part of the production contract. */
    void expectSettled(CountDownLatch latch) {
        this.settled = latch;
    }

    public long sentCount()    { return sent.get(); }
    public long droppedCount() { return dropped.get(); }
    public long failedCount()  { return failed.get(); }
    public int  queueDepth()   { return queue.size(); }

    /** Stops accepting new alerts and gives the worker a bounded window to drain. */
    @Override
    public void close() {
        running.set(false);
        try {
            worker.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        http.close();
    }
}
