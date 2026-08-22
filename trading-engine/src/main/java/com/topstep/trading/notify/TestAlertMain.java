package com.topstep.trading.notify;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * One-shot delivery proof: pushes a synthetic MNQ alert through the REAL
 * {@link DiscordWebhookClient} to whatever {@code DISCORD_OTE_WEBHOOK} points
 * at, then reports what the transport actually did.
 *
 * <p>WHY: an ARMED zone fires roughly once every day or two, so without this
 * you discover a misconfigured webhook, a revoked token, or a wrong channel two
 * days later — at the exact moment a real signal was supposed to land. This
 * proves the pipeline on demand, in seconds.
 *
 * <p>The payload is marked as a test in three independent places: a plain-text
 * banner above the embed, the title, and the footer. Any one of them alone
 * could be missed on a phone; all three cannot be. It still carries the
 * mandatory not-financial-advice line, because the disclaimer is not something
 * a test gets to opt out of.
 *
 * <p>Point this at a private staff channel, never at the paid one.
 */
public final class TestAlertMain {

    private TestAlertMain() {}

    /** Realistic MNQ geometry: ~29.5k, quarter-tick prices, R:R just over 2. */
    private static OteAlert syntheticMnqAlert() {
        return new OteAlert(
                OteAlert.Kind.ARMED,
                "MNQ",
                true,
                29480.25,   // 0.62 edge
                29455.50,   // 0.705 sweet spot
                29430.75,   // 0.79 edge
                29390.00,   // invalidation
                29610.00,   // first target
                7,          // raid quality
                "PDL",
                "New York",
                2,
                Instant.now());
    }

    public static void main(String[] args) {
        String webhook = System.getenv("DISCORD_OTE_WEBHOOK");
        if (webhook == null || webhook.isBlank()) {
            System.err.println("DISCORD_OTE_WEBHOOK is not set in THIS shell.");
            System.err.println("Environment variables do not cross processes: set it in the");
            System.err.println("same window, immediately before running this command.");
            System.exit(2);
        }

        OteAlert alert = syntheticMnqAlert();
        OteAlertFormatter formatter = new OteAlertFormatter(
                "TEST MESSAGE — not a live signal. Educational only. Not financial advice.");
        String payload = formatter.format(alert,
                "🧪 **TEST ALERT — SYNTHETIC DATA, NOT A LIVE SIGNAL** 🧪\n"
                        + "Sent manually to verify the alert pipeline. "
                        + "Do not trade this. The prices below are fabricated.");

        System.out.println("Sending synthetic MNQ alert...");
        System.out.println("  entry (0.705) : " + Prices.px(alert.zoneSweet(), 2));
        System.out.println("  invalidation  : " + Prices.px(alert.invalidation(), 2));
        System.out.println("  target        : " + Prices.px(alert.target(), 2));
        System.out.printf ("  R:R           : %.2f%n", alert.riskReward());
        System.out.println("  payload bytes : " + payload.length());
        System.out.println();

        int exit = 1;
        // Short min-interval: this is a single message, not a burst.
        try (DiscordWebhookClient client = new DiscordWebhookClient(
                webhook, 4, 0L, java.time.Duration.ofSeconds(10))) {

            CountDownLatch settled = new CountDownLatch(1);
            client.expectSettled(settled);
            client.enqueue(payload);

            if (!settled.await(60, TimeUnit.SECONDS)) {
                System.err.println("TIMEOUT: no terminal outcome within 60s.");
                System.err.println("The worker is still retrying, or the host is unreachable.");
            } else if (client.sentCount() == 1) {
                System.out.println("DELIVERED — HTTP 2xx from Discord.");
                System.out.println("Open the target channel; the embed should be there now.");
                exit = 0;
            } else if (client.droppedCount() == 1) {
                System.err.println("DROPPED — Discord returned a permanent 4xx.");
                System.err.println("That means 401/403/404: the webhook URL is wrong, was");
                System.err.println("deleted, or belongs to a different server. It was NOT");
                System.err.println("retried, because retrying a dead webhook only burns the");
                System.err.println("rate limit. Re-create it and try again.");
            } else if (client.failedCount() == 1) {
                System.err.println("FAILED — transient errors exhausted all 3 attempts.");
                System.err.println("Network-level failure or repeated 5xx from Discord.");
                System.err.println("Any 429 rate-limit waits are logged above and do NOT");
                System.err.println("count against those attempts.");
            }

            System.out.println();
            System.out.println("transport: sent=" + client.sentCount()
                    + " dropped=" + client.droppedCount()
                    + " failed=" + client.failedCount());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Interrupted while waiting for delivery.");
        }
        System.exit(exit);
    }
}
