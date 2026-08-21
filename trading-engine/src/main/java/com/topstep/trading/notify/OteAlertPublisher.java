package com.topstep.trading.notify;

import com.topstep.trading.chart.ChartEngine;
import com.topstep.trading.chart.OteState;
import com.topstep.trading.chart.OteZoneSnapshot;
import com.topstep.trading.chartstate.ChartStateManager;
import com.topstep.trading.chartstate.ChartStateQueryAPI;
import com.topstep.trading.chartstate.LiquidityRaid;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Watches every registered instrument's active OTE zone and publishes lifecycle
 * transitions to Discord.
 *
 * <h2>Why polling rather than a listener</h2>
 *
 * {@link ChartEngine} exposes a single {@code candleTap} slot which the ICT
 * detection library already occupies, and its OTE state transitions happen at
 * roughly a dozen sites inside the per-symbol lock. Adding notification hooks to
 * those sites would mean editing the hot path of a working engine, and any bug
 * introduced there affects live position management, not just alerting.
 *
 * <p>Polling {@code getActiveOteZone} is documented as thread-safe and returns
 * immutable copies, so this class cannot perturb engine state no matter what it
 * does. The cost is up to one poll interval of latency. On 30-minute zones that
 * is irrelevant, and the safety is worth far more than the seconds.
 *
 * <h2>What gets published</h2>
 *
 * FORMING is deliberately never published. A zone can form and expire without
 * price ever approaching it, and posting every formation would train members to
 * ignore the channel. ARMED is the actionable event. REACTED and INVALIDATED are
 * published so the channel tells a complete story rather than only good news,
 * which also keeps the payout wall honest.
 */
public final class OteAlertPublisher implements AutoCloseable {

    private static final System.Logger LOG =
            System.getLogger(OteAlertPublisher.class.getName());

    private final ChartEngine chartEngine;
    private final ChartStateManager chartState;
    private final DiscordWebhookClient webhook;
    private final OteAlertFormatter formatter;
    private final NotifyConfig config;
    private final ScheduledExecutorService scheduler;

    /** Last state we published per symbol, so a transition fires exactly once. */
    private final Map<String, String> lastPublished = new HashMap<>();

    public OteAlertPublisher(ChartEngine chartEngine,
                             ChartStateManager chartState,
                             DiscordWebhookClient webhook,
                             NotifyConfig config) {
        this.chartEngine = chartEngine;
        this.chartState = chartState;
        this.webhook = webhook;
        this.config = config;
        this.formatter = new OteAlertFormatter();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ote-alert-publisher");
            t.setDaemon(true);
            return t;
        });
    }

    /** Begin polling. Idempotent guard is the caller's responsibility. */
    public void start() {
        long ms = config.pollIntervalMs();
        scheduler.scheduleWithFixedDelay(this::pollAll, ms, ms, TimeUnit.MILLISECONDS);
        LOG.log(System.Logger.Level.INFO,
                "OTE alert publisher started, polling every " + ms + "ms for "
                        + config.symbols());
    }

    private void pollAll() {
        // One bad symbol must not stop the others, and nothing thrown here may
        // escape into the scheduler, which would silently cancel the task.
        for (String symbol : config.symbols()) {
            try {
                poll(symbol);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING,
                        "OTE poll failed for " + symbol + ": " + e.getMessage());
            }
        }
    }

    private void poll(String symbol) {
        Optional<OteZoneSnapshot> maybe = chartEngine.getActiveOteZone(symbol);
        if (maybe.isEmpty()) {
            // A zone that disappears entirely (expired, or replaced) resets the
            // dedupe slot so the next zone on this symbol can publish freshly.
            lastPublished.remove(symbol);
            return;
        }

        OteZoneSnapshot zone = maybe.get();
        OteAlert.Kind kind = switch (zone.state()) {
            case ARMED       -> OteAlert.Kind.ARMED;
            case REACTED     -> OteAlert.Kind.REACTED;
            case INVALIDATED -> OteAlert.Kind.INVALIDATED;
            case FORMING, EXPIRED -> null;   // never published, see class docs
        };
        if (kind == null) return;

        OteAlert alert = toAlert(symbol, zone, kind);

        String key = alert.dedupeKey();
        if (key.equals(lastPublished.get(symbol))) return;   // already announced

        if (!passesPolicy(alert)) {
            lastPublished.put(symbol, key);   // suppressed, but do not re-evaluate
            return;
        }

        lastPublished.put(symbol, key);
        webhook.enqueue(formatter.format(alert));
    }

    /**
     * Quality gates. An alert that fails any of these is suppressed rather than
     * downgraded, because a paid channel is judged on its worst post, not its
     * average one.
     */
    private boolean passesPolicy(OteAlert alert) {
        // Invalidation is always published: members need to know a setup died,
        // and suppressing bad news is how a signal channel loses credibility.
        if (alert.kind() == OteAlert.Kind.INVALIDATED) return true;

        if (alert.raidScore() != null && alert.raidScore() < config.minRaidScore()) {
            return false;
        }
        double rr = alert.riskReward();
        if (!Double.isNaN(rr) && rr < config.minRiskReward()) {
            return false;
        }
        return true;
    }

    private OteAlert toAlert(String symbol, OteZoneSnapshot zone, OteAlert.Kind kind) {
        double tickSize = config.tickSize(symbol);
        int decimals = config.decimals(symbol);

        Integer raidScore = null;
        String raidLevel = null;
        ChartStateQueryAPI query = chartState.getQueryAPI(symbol);
        if (query != null) {
            Optional<LiquidityRaid> raid = zone.bullish()
                    ? query.getActiveBullishRaid()
                    : query.getActiveBearishRaid();
            if (raid.isPresent()) {
                raidScore = raid.get().getQualityScore();
                if (raid.get().getTargetLevel() != null) {
                    raidLevel = String.valueOf(raid.get().getTargetLevel().getType());
                }
            }
        }

        return new OteAlert(
                kind,
                symbol,
                zone.bullish(),
                zone.oteStart(),
                zone.oteSweet(),
                zone.oteEnd(),
                zone.protectiveStop(tickSize, config.stopBufferTicks()),
                zone.primaryTarget(),
                raidScore,
                raidLevel,
                sessionLabel(query),
                decimals,
                zone.taggedAt() != null ? zone.taggedAt() : Instant.now()
        );
    }

    private String sessionLabel(ChartStateQueryAPI query) {
        if (query == null) return null;
        if (query.isInNY()) return "New York";
        if (query.isInLondon()) return "London";
        if (query.isInAsia()) return "Asia";
        return null;
    }

    /** Exposed for tests and for the dashboard's health endpoint. */
    public List<String> watchedSymbols() {
        return config.symbols();
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
