package com.topstep.trading.notify;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * Scheduling configuration for the daily pre-market levels digest.
 *
 * <p>The digest exists because a signal channel that only posts on ARMED zones
 * is silent most of the week. At the observed setup rate that is days of nothing
 * between posts, which reads as a broken product regardless of what the engine
 * is doing. A fixed daily levels post gives the channel a pulse and turns the
 * alerts into the payoff rather than the entire content.
 *
 * <p>That makes the post a <em>commitment</em>. A missing routine is noticed
 * faster than a missing signal, so the scheduler is built to survive restarts
 * (see {@code stateFile}) and to catch up after a late start rather than skip
 * the day (see {@code catchUpMinutes}).
 */
public record DigestConfig(
        boolean enabled,
        String webhookUrl,
        LocalTime postTimeEt,
        int catchUpMinutes,
        Path stateFile
) {

    /** Every schedule decision is made in exchange time, never the host's zone. */
    public static final ZoneId EXCHANGE_ZONE = ZoneId.of("America/New_York");

    private static final LocalTime DEFAULT_POST_TIME = LocalTime.of(8, 30);

    public DigestConfig {
        if (postTimeEt == null) postTimeEt = DEFAULT_POST_TIME;
        if (catchUpMinutes < 0) catchUpMinutes = 0;
        if (stateFile == null) stateFile = Paths.get("data", "digest_state.txt");
    }

    /**
     * Build from environment variables.
     *
     * <pre>
     *   OTE_DIGEST_ENABLED     optional, default true (still needs a webhook)
     *   DISCORD_DIGEST_WEBHOOK optional, falls back to DISCORD_OTE_WEBHOOK
     *   OTE_DIGEST_TIME_ET     optional, HH:mm in exchange time, default 08:30
     *   OTE_DIGEST_CATCHUP_MIN optional, default 180
     *   OTE_DIGEST_STATE_FILE  optional, default data/digest_state.txt
     * </pre>
     *
     * <p>The digest webhook is separable from the alert webhook on purpose: a
     * levels digest is reasonable in a free or preview channel while live ARMED
     * alerts stay behind the paywall.
     */
    public static DigestConfig fromEnv() {
        String webhook = firstNonBlank(
                System.getenv("DISCORD_DIGEST_WEBHOOK"),
                System.getenv("DISCORD_OTE_WEBHOOK"));
        return new DigestConfig(
                !"false".equalsIgnoreCase(trimOrNull(System.getenv("OTE_DIGEST_ENABLED"))),
                webhook,
                parseTime(System.getenv("OTE_DIGEST_TIME_ET")),
                (int) parseLong(System.getenv("OTE_DIGEST_CATCHUP_MIN"), 180L),
                parsePath(System.getenv("OTE_DIGEST_STATE_FILE")));
    }

    /** Safe for logs. The webhook is a credential and is never included. */
    public String describe() {
        return "DigestConfig[enabled=" + enabled
                + ", postTimeEt=" + postTimeEt
                + ", catchUpMin=" + catchUpMinutes
                + ", stateFile=" + stateFile
                + ", webhook=" + (hasWebhook() ? "<redacted>" : "<none>")
                + "]";
    }

    public boolean hasWebhook() {
        return webhookUrl != null && !webhookUrl.isBlank();
    }

    static LocalTime parseTime(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT_POST_TIME;
        try {
            return LocalTime.parse(raw.trim());
        } catch (DateTimeParseException e) {
            // A malformed time must not silently move the post to midnight.
            return DEFAULT_POST_TIME;
        }
    }

    private static Path parsePath(String raw) {
        return (raw == null || raw.isBlank())
                ? Paths.get("data", "digest_state.txt")
                : Paths.get(raw.trim());
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        return (b != null && !b.isBlank()) ? b : null;
    }

    private static String trimOrNull(String raw) {
        return raw == null ? null : raw.trim();
    }

    private static long parseLong(String raw, long fallback) {
        try {
            return raw == null || raw.isBlank() ? fallback : Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
