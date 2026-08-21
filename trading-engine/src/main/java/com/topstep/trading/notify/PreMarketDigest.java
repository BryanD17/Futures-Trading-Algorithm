package com.topstep.trading.notify;

import com.topstep.trading.chart.ChartEngine;
import com.topstep.trading.chart.ChartSnapshot;
import com.topstep.trading.chart.OteZoneSnapshot;
import com.topstep.trading.chartstate.ChartStateQueryAPI;
import com.topstep.trading.chartstate.KnownLevel;
import com.topstep.trading.chartstate.LevelType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Posts one pre-market levels digest per trading day at a fixed exchange-time
 * hour, giving the channel a daily pulse independent of how often a setup arms.
 *
 * <h2>Why this is not just another scheduled task</h2>
 *
 * A daily post is a <b>commitment</b>. Members notice a missing routine faster
 * than a missing signal, and a skipped post is indistinguishable from a dead
 * bot. Three consequences shape the design:
 *
 * <ul>
 *   <li><b>It survives restarts.</b> The last posted trading day is persisted,
 *       so a restart never double-posts and never loses the day.</li>
 *   <li><b>It catches up rather than skips.</b> If the engine was down at the
 *       scheduled minute it still posts on start-up, within
 *       {@link DigestConfig#catchUpMinutes}. Late beats absent.</li>
 *   <li><b>It publishes on a cold day.</b> A symbol with no levels gets an
 *       embed saying so. Silence would be a broken promise; an honest "no
 *       levels yet" is a kept one.</li>
 * </ul>
 *
 * <p>Weekends are skipped: there is no pre-market on a closed market, and a
 * Saturday post would be noise rather than a pulse.
 *
 * <p>Like the alert publisher, this only ever <em>reads</em> engine state and
 * cannot perturb it, and nothing it does can propagate into the trading path.
 */
public final class PreMarketDigest implements AutoCloseable {

    private static final System.Logger LOG =
            System.getLogger(PreMarketDigest.class.getName());

    /** Matches the /api/chart warm tripwire, so digest and API never disagree. */
    private static final long WARM_BARS_1M = 1500;

    /** How often the schedule is evaluated. Cheap; the work is gated by date. */
    private static final long TICK_SECONDS = 60;

    private final ChartEngine chartEngine;
    private final Function<String, ChartStateQueryAPI> queryProvider;
    private final DiscordWebhookClient webhook;
    private final NotifyConfig notifyConfig;
    private final DigestConfig digestConfig;
    private final PreMarketDigestFormatter formatter = new PreMarketDigestFormatter();
    private final ScheduledExecutorService scheduler;

    /** Trading day of the most recent successful post; null until one happens. */
    private volatile LocalDate lastPostedDay;

    public PreMarketDigest(ChartEngine chartEngine,
                           Function<String, ChartStateQueryAPI> queryProvider,
                           DiscordWebhookClient webhook,
                           NotifyConfig notifyConfig,
                           DigestConfig digestConfig) {
        this.chartEngine = chartEngine;
        this.queryProvider = queryProvider;
        this.webhook = webhook;
        this.notifyConfig = notifyConfig;
        this.digestConfig = digestConfig;
        this.lastPostedDay = readState(digestConfig.stateFile());
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "premarket-digest");
            t.setDaemon(true);
            return t;
        });
    }

    /** Begin evaluating the schedule once a minute. */
    public void start() {
        scheduler.scheduleWithFixedDelay(this::tick, 5, TICK_SECONDS, TimeUnit.SECONDS);
        LOG.log(System.Logger.Level.INFO,
                "Pre-market digest scheduled for " + digestConfig.postTimeEt()
                        + " ET (catch-up " + digestConfig.catchUpMinutes() + "m), symbols "
                        + notifyConfig.symbols()
                        + ", last posted " + (lastPostedDay == null ? "never" : lastPostedDay));
    }

    private void tick() {
        try {
            ZonedDateTime nowEt = ZonedDateTime.now(DigestConfig.EXCHANGE_ZONE);
            if (shouldPost(nowEt, lastPostedDay, digestConfig)) {
                postFor(nowEt.toLocalDate(), nowEt.toInstant());
            }
        } catch (RuntimeException e) {
            // Nothing on this thread may escape: an uncaught exception silently
            // cancels a scheduleWithFixedDelay task, which would kill the daily
            // post permanently with no error after the first failure.
            LOG.log(System.Logger.Level.WARNING, "Digest tick failed: " + e.getMessage());
        }
    }

    /**
     * Pure schedule predicate, extracted so the timing rules are testable
     * without a clock, a webhook, or an engine.
     */
    static boolean shouldPost(ZonedDateTime nowEt, LocalDate lastPosted, DigestConfig cfg) {
        if (!cfg.enabled()) return false;
        LocalDate today = nowEt.toLocalDate();
        if (today.equals(lastPosted)) return false;                 // already done
        DayOfWeek dow = today.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return false;

        LocalDateTime due = LocalDateTime.of(today, cfg.postTimeEt());
        LocalDateTime now = nowEt.toLocalDateTime();
        if (now.isBefore(due)) return false;                        // not yet

        // Catch-up window: late is fine, but do not fire a "pre-market" post
        // in the afternoon because the host was off all morning.
        long lateMinutes = Duration.between(due, now).toMinutes();
        return lateMinutes <= cfg.catchUpMinutes();
    }

    /** Build and enqueue the digest for one trading day. */
    void postFor(LocalDate tradingDay, Instant now) {
        List<PreMarketDigestFormatter.SymbolSection> sections = new ArrayList<>();
        for (String symbol : notifyConfig.symbols()) {
            sections.add(sectionFor(symbol));
        }
        String payload = formatter.format(sections, tradingDay, now);

        // Marked posted on ENQUEUE, not on delivery. The webhook client already
        // retries transient failures internally; re-deriving the digest on every
        // tick during a webhook outage would burn the rate limit and could post
        // a burst of stale digests once it recovered.
        webhook.enqueue(payload);
        lastPostedDay = tradingDay;
        writeState(digestConfig.stateFile(), tradingDay);
        LOG.log(System.Logger.Level.INFO,
                "Pre-market digest posted for " + tradingDay + " (" + sections.size() + " symbols)");
    }

    private PreMarketDigestFormatter.SymbolSection sectionFor(String symbol) {
        boolean warm = false;
        try {
            ChartSnapshot snap = chartEngine.snapshot(symbol, 1);
            warm = snap != null && snap.oneMinuteBarsIngested() >= WARM_BARS_1M;
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING,
                    "Digest warmth check failed for " + symbol + ": " + e.getMessage());
        }

        Map<LevelType, Double> levels = new EnumMap<>(LevelType.class);
        int unraided = 0;
        try {
            ChartStateQueryAPI query = queryProvider.apply(symbol);
            if (query != null) {
                List<KnownLevel> all = query.getAllLevels();
                if (all != null) {
                    for (KnownLevel level : all) {
                        if (level == null || level.getType() == null) continue;
                        // Keep the most recent level of each type: the engine can
                        // hold several generations of PDH as days roll over.
                        levels.put(level.getType(), level.getPrice());
                        if (!level.isRaided()) unraided++;
                    }
                }
            }
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING,
                    "Digest level read failed for " + symbol + ": " + e.getMessage());
        }

        return new PreMarketDigestFormatter.SymbolSection(
                symbol, warm, levels, unraided, watchingText(symbol),
                notifyConfig.decimals(symbol));
    }

    /** Describe any live OTE zone, so the digest previews what may fire today. */
    private String watchingText(String symbol) {
        try {
            Optional<OteZoneSnapshot> zone = chartEngine.getActiveOteZone(symbol);
            if (zone.isEmpty()) return null;
            OteZoneSnapshot z = zone.get();
            int d = notifyConfig.decimals(symbol);
            return (z.bullish() ? "Bullish" : "Bearish") + " OTE zone, state "
                    + z.state() + "\n`" + Prices.px(z.oteStart(), d) + "` to `"
                    + Prices.px(z.oteEnd(), d) + "`";
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ── restart-durable state ─────────────────────────────────────────────

    /**
     * Read the last posted trading day. A missing or unreadable file means
     * "never posted", which is the safe direction: it can cause one extra post,
     * never a silent skip.
     */
    static LocalDate readState(Path file) {
        try {
            if (file == null || !Files.isReadable(file)) return null;
            String raw = Files.readString(file, StandardCharsets.UTF_8).trim();
            return raw.isEmpty() ? null : LocalDate.parse(raw);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    static void writeState(Path file, LocalDate day) {
        try {
            if (file == null) return;
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(file, day.toString(), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            // A failed write costs at most a duplicate post after a restart.
            LOG.log(System.Logger.Level.WARNING,
                    "Could not persist digest state to " + file + ": " + e.getMessage());
        }
    }

    /** Exposed for the health endpoint and tests. */
    public LocalDate lastPostedDay() {
        return lastPostedDay;
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
