# `com.topstep.trading.notify`

Publishes OTE zone lifecycle events from the live engine into a Discord channel.

**Status:** compiles clean, 36 assertions passing against a real loopback HTTP server.

---

## Install

Drop all files except `NotifyHarness.java` into:

```
trading-engine/src/main/java/com/topstep/trading/notify/
```

`NotifyHarness.java` goes in `src/test/java/` if you want to keep it, or convert it to JUnit since the project already has JUnit 5 and AssertJ.

**No new Gradle dependencies.** This uses JDK 21's `java.net.http.HttpClient` and a small internal JSON writer. That was deliberate: it keeps the notification layer independently compilable and testable, and it means a Jackson or OkHttp version bump can never break your alerting.

---

## Wire it up

In `TradingEngineMain`, after the chart engine and chart state manager exist:

```java
NotifyConfig notifyConfig = NotifyConfig.fromEnv();
log.info("Discord alerts: {}", notifyConfig.describe());  // safe, redacts the URL

DiscordWebhookClient webhook = new DiscordWebhookClient(notifyConfig.webhookUrl());
OteAlertPublisher alerts = new OteAlertPublisher(
        chartEngine, chartStateManager, webhook, notifyConfig);
alerts.start();

Runtime.getRuntime().addShutdownHook(new Thread(() -> {
    alerts.close();
    webhook.close();
}));
```

Environment:

```bash
export DISCORD_OTE_WEBHOOK="https://discord.com/api/webhooks/..."
export OTE_ALERT_SYMBOLS="NQ,ES"
export OTE_ALERT_POLL_MS=5000
export OTE_ALERT_MIN_RAID=5
export OTE_ALERT_MIN_RR=2.0
```

The webhook URL is a credential. Anyone holding it can post as your bot into a paid channel. Keep it out of the repo, out of logs, and out of screenshots during live sessions.

---

## Design decisions worth knowing

**Polling, not a listener.** `ChartEngine.setCandleTap` is a single slot already occupied by the ICT detection library, and OTE state transitions happen at roughly a dozen sites inside the per-symbol lock. Adding hooks there means editing the hot path of an engine that manages live positions. `getActiveOteZone` is documented thread-safe and returns immutable copies, so polling cannot perturb engine state under any circumstance. Cost is up to one poll interval of latency, which is nothing on 30m zones.

**FORMING is never published.** A zone can form and expire without price coming near it. Posting every formation trains members to ignore the channel.

**INVALIDATED is always published**, and it bypasses the quality gates. Members need to know when a setup dies. A channel that only posts good news stops being believed, and yours is attached to a payout wall where credibility is the product.

**Alerting can never break trading.** `enqueue` does not block, does not throw, and does not propagate failures. A Discord outage, a revoked webhook, or a saturated network degrades to a dropped message and a log line.

**Drop-oldest on overflow.** Under backpressure the stale alert is shed, not the fresh one. A late ARMED notice is worse than none.

**429 does not consume the retry budget.** Rate limiting is a scheduling instruction, not a failure. `retry-after` is honoured and clamped at 60s so a malformed header cannot park the worker. Permanent 4xx (401/403/404) drops immediately, since retrying a dead webhook only burns the rate limit.

---

## What the harness proves

```
javac -d out src/com/topstep/trading/notify/*.java test/NotifyHarness.java
java -cp out NotifyHarness
```

Formatter: embed shape, mandatory disclaimer footer on every payload, JSON escaping of quotes/backslashes/newlines, BigDecimal HALF_UP price rounding (quarter-tick NQ prices break `%.2f`), R:R from the sweet spot, NaN on degenerate geometry, dedupe key stability.

Transport: successful delivery, 429 with `retry-after` honoured and not counted as failure, 5xx retried to success in exactly three attempts, 404 dropped without retry, queue bounded under 40-message burst into a blocking server, unreachable host recorded as failure with nothing thrown.

---

## Before you point this at the real channel

1. Create the webhook on `#key-levels`, not on a public channel.
2. Run against a private test channel first and watch a full session.
3. Confirm the disclaimer footer renders on every embed. It is asserted in the harness but verify visually once.
4. Set `OTE_ALERT_MIN_RAID` from backtest, not intuition. Your PRD's own guidance was to start at 4 and tune; 5 is the default here because a paid channel is judged on its worst post.
5. Decide the daily volume you are comfortable with. If ARMED fires more than about six times a session on two instruments, raise the gates rather than letting members learn to scroll past.

---

## Not built yet

- **Daily pre-market levels post.** Different job: a scheduled digest of PDH/PDL/session levels from `ChartStateQueryAPI`, posted once at a fixed time. Straightforward on top of `DiscordWebhookClient`.
- **Second webhook for the free channel.** A delayed or redacted variant as a conversion funnel.
- **Kill switch from Discord.** Deliberately absent. Inbound control from Discord to a local engine that trades a Topstep account is exactly the architecture Topstep's rules are written against. Keep the flow one-directional.
