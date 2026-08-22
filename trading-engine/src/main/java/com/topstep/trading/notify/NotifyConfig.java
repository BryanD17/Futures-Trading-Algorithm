package com.topstep.trading.notify;

import java.util.List;
import java.util.Map;

/**
 * Configuration for the Discord notification layer.
 *
 * <p>The webhook URL is a credential: anyone holding it can post as the bot into
 * a paid channel. It is read from the environment and never logged, never
 * serialised, and must never be committed. {@link #describe()} exists so
 * start-up logging can prove the config loaded without leaking the secret.
 */
public record NotifyConfig(
        String webhookUrl,
        List<String> symbols,
        long pollIntervalMs,
        int minRaidScore,
        double minRiskReward,
        int stopBufferTicks,
        Map<String, Double> tickSizes,
        Map<String, Integer> decimals
) {

    /**
     * Tick sizes for the instruments this desk trades.
     *
     * <p>The micros are the ones that matter here: this engine runs
     * {@code active=[MNQ, MGC]} with MES as an SMT-only feed. Without the micro
     * entries MGC would fall through to the 0.25 / 2-decimal default and every
     * gold level would render at the wrong precision.
     */
    private static final Map<String, Double> DEFAULT_TICKS = Map.of(
            "MNQ", 0.25,
            "MES", 0.25,
            "MGC", 0.10,
            "ES", 0.25,
            "NQ", 0.25,
            "YM", 1.0,
            "GC", 0.10,
            "CL", 0.01
    );

    private static final Map<String, Integer> DEFAULT_DECIMALS = Map.of(
            "MNQ", 2,
            "MES", 2,
            "MGC", 1,
            "ES", 2,
            "NQ", 2,
            "YM", 0,
            "GC", 1,
            "CL", 2
    );

    public NotifyConfig {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            throw new IllegalArgumentException(
                    "DISCORD_OTE_WEBHOOK is not set. Refusing to start the notifier "
                            + "with no destination.");
        }
        if (symbols == null || symbols.isEmpty()) {
            throw new IllegalArgumentException("at least one symbol required");
        }
        if (pollIntervalMs < 1_000) {
            // Below a second adds no value on 30m zones and only burns CPU.
            throw new IllegalArgumentException("pollIntervalMs must be >= 1000");
        }
        symbols = List.copyOf(symbols);
        tickSizes = Map.copyOf(tickSizes);
        decimals = Map.copyOf(decimals);
    }

    /**
     * Build from environment variables.
     *
     * <pre>
     *   DISCORD_OTE_WEBHOOK   required, the channel webhook URL
     *   OTE_ALERT_SYMBOLS     optional, comma separated, default "MNQ,MGC"
     *   OTE_ALERT_POLL_MS     optional, default 5000
     *   OTE_ALERT_MIN_RAID    optional, default 5
     *   OTE_ALERT_MIN_RR      optional, default 2.0
     * </pre>
     */
    public static NotifyConfig fromEnv() {
        return new NotifyConfig(
                System.getenv("DISCORD_OTE_WEBHOOK"),
                parseSymbols(System.getenv("OTE_ALERT_SYMBOLS")),
                parseLong(System.getenv("OTE_ALERT_POLL_MS"), 5_000L),
                (int) parseLong(System.getenv("OTE_ALERT_MIN_RAID"), 5L),
                parseDouble(System.getenv("OTE_ALERT_MIN_RR"), 2.0),
                2,
                DEFAULT_TICKS,
                DEFAULT_DECIMALS
        );
    }

    public double tickSize(String symbol) {
        return tickSizes.getOrDefault(symbol, 0.25);
    }

    public int decimals(String symbol) {
        return decimals.getOrDefault(symbol, 2);
    }

    /** Safe for logs. Deliberately omits the webhook URL. */
    public String describe() {
        return "NotifyConfig[symbols=" + symbols
                + ", pollMs=" + pollIntervalMs
                + ", minRaid=" + minRaidScore
                + ", minRR=" + minRiskReward
                + ", webhook=<redacted>]";
    }

    private static List<String> parseSymbols(String raw) {
        // Defaults follow the engine's active set (MNQ, MGC). MES is deliberately
        // absent: it runs as an SMT-only feed with no setup funnel of its own, so
        // it can never produce an OTE zone to alert on.
        if (raw == null || raw.isBlank()) return List.of("MNQ", "MGC");
        return List.of(raw.split(",")).stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toUpperCase(java.util.Locale.ROOT))
                .toList();
    }

    private static long parseLong(String raw, long fallback) {
        try {
            return raw == null || raw.isBlank() ? fallback : Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double parseDouble(String raw, double fallback) {
        try {
            return raw == null || raw.isBlank() ? fallback : Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
