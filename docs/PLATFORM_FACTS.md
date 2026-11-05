# PLATFORM_FACTS.md — TradingView + relay venue truth (Agent 01, V6)

Retrieval date for every entry: **2026-11-05**. Primary sources: tradingview.com
help center and Pine docs; crosstrade.io docs (docs.crosstrade.io 301-redirects
to crosstrade.io/docs); pickmytrade.trade; docs.traderspost.io. Vendor blog
posts are marked (blog). Status legend as in LUCID_RULES.md.

---

## Part A — TradingView

### A1. Plan tier needed for webhooks
- Official: https://www.tradingview.com/support/solutions/43000529348-about-webhooks/
  does **not** name a plan; it states the 2FA rule (A2) and the technical limits (A4).
- Official pricing page https://www.tradingview.com/pricing/ has a "Webhook
  notifications" row; checkmarks were not machine-readable today.
- CrossTrade (blog, prices "as listed on TradingView's pricing page in September
  2026"): https://crosstrade.io/blog/tradingview-plan-for-webhook-alerts —
  quote *"Every paid plan can send webhooks."* Basic (free): no webhooks.
- CrossTrade FAQ https://crosstrade.io/answers/faq/alerts-and-webhooks: *"You also
  need at least TradingView's Essentials plan."*
- **Verdict:** minimum = Essential (CONSERVATIVE-UNVERIFIED on TradingView's own
  text). **V6 recommendation: Premium** — the alert-expiry rule (A3) makes
  Essential/Plus unsafe for an always-on strategy alert.

### A2. 2FA requirement — VERIFIED
- Quote: *"webhook alerts are only allowed when 2-factor authentication is
  enabled."* https://www.tradingview.com/support/solutions/43000529348-about-webhooks/
- How to enable: https://www.tradingview.com/support/solutions/43000572460-how-to-configure-2fa/
  (Account Settings → Privacy and Security → Enable; authenticator app
  recommended; store backup codes — TradingView cannot recover a lost 2FA).

### A3. Alert limits per plan + expiry — VERIFIED (official) + counts (pricing page)
- Pricing page (annual-billing prices shown): Essential $12.95 — 20 price + 20
  technical alerts; Plus $29.95 — 100/100; Premium $59.95 — 400/400; Ultimate
  $199.95 — 1,000/1,000. https://www.tradingview.com/pricing/
- Expiry, official: *"The maximum lifetime of a standard alert is two months."*
  https://www.tradingview.com/support/solutions/43000520149-introduction-to-tradingview-alerts/
- Open-ended, official: *"Open-ended alerts are available for Premium and
  Ultimate plans only."* Even open-ended alerts are auto-deactivated only if ALL
  of: created > 1 year ago, not triggered > 1 year, not edited > 1 year.
  https://www.tradingview.com/support/solutions/43000688759-i-upgraded-to-the-premium-plan-but-my-alerts-still-expiring-in-2-months/
- Script-alert auto-stop, official: *"if a script alert triggers more than 15
  times within a three-minute window, it will automatically stop."*
  https://www.tradingview.com/support/solutions/43000597494-script-alerts/
  → V6 I5 (≤ 3 entries/session, ≥ 60 s spacing) cannot approach this.
- **Runbook facts:** on Premium create the ONE strategy alert with Expiration =
  Open-ended. On Essential/Plus the alert silently dies after 2 months.

### A4. Webhook technical limits — VERIFIED
Source: https://www.tradingview.com/support/solutions/43000529348-about-webhooks/
- Timeout: *"If a remote server takes longer than three seconds to process a
  request, the request will be cancelled."*
- Ports: *"only ports 80 and 443 are accepted."* IPv6 not supported.
- Content type: valid JSON body → `application/json`, else `text/plain`
  (CrossTrade's `key=...;` format is text/plain — accepted by CrossTrade).
- Delivery: *"webhooks may occasionally fail to reach the specified URL"*;
  check the alert log's "Webhook status" column. No retry documented → relay
  sync (B4) is the recovery mechanism.
- Rate: CrossTrade FAQ reports TradingView limits alerts to "reportedly around 3
  per minute on most plans" (CONSERVATIVE-UNVERIFIED, third-party).

### A5. Alert snapshots the script version + inputs at creation — VERIFIED
Source (official Pine docs): https://www.tradingview.com/pine-script-docs/concepts/alerts/
- Quote: *"When an alert is created in the charts UI, TradingView saves a mirror
  image of the script and its inputs."*
- Quote: *"Subsequent changes to your script's inputs or the chart will thus not
  affect running alerts previously created from them."* To apply changes *"you
  will need to delete the alert and create a new one in the new context."*
- Confirmed independently by CrossTrade's Tradovate strategy guide ("Alerts
  snapshot strategy settings when created, so changed inputs require a new alert").
- **V6 consequence:** every script edit (new dist sha) = delete + re-create the
  alert (drill D8; runbook 08.1 "updating the script").

### A6. Strategy alert mechanics — VERIFIED
- Condition options (official): *"Order fills and alert() function calls"*,
  *"Order fills only"*, *"alert() function calls only"*.
  https://www.tradingview.com/support/solutions/43000597494-script-alerts/
- Placeholder (official Pine docs): script users *"must include the
  `{{strategy.order.alert_message}}` placeholder"* in the Message field; order
  calls without `alert_message` yield an **empty string** for that fill.
  CrossTrade confirms the empty payload makes the alert fail → **lint must
  forbid order calls without alert_message (04.1)**.
- Order-fill alerts fire when the broker emulator fills; in strategies without
  `calc_on_every_tick`, `alert()` calls use once-per-bar-close frequency.

### A7. Real-time CME data for MNQ/MES — VERIFIED
Source: https://www.tradingview.com/support/solutions/43000471705-how-to-purchase-additional-market-data/
- Quote: *"Currently, our paid plans don't cover those fees even if you have an
  active TradingView subscription."* Real-time exchange data is bought separately
  (avatar → Settings and billing → Subscriptions → Real-time market data →
  "Explore now"); a "Data is delayed" status-line label means delayed data.
- CME futures are delayed by default (delay widely reported as ~10 min; not
  stated on the official page → CONSERVATIVE-UNVERIFIED). Delayed data = alerts
  fire ~10 min late = unacceptable for V6. **OA-0 must include the CME Group
  real-time package (non-professional).** Price not published on the help page.

### A8. Strategy Tester trade-list export — VERIFIED
Source: https://www.tradingview.com/support/solutions/43000613680-how-to-export-strategy-data/
- "Download" button in the Strategy Report exports the **current tab** as CSV;
  *"List of Trades"* tab → trades only; *"Performance Summary"* → metrics only.
- No plan restriction stated on the official page (a third-party page claims
  paid-only; irrelevant since V6 requires a paid plan anyway).
- Feeds OA-2 → parity/tv_trades.csv.

### A9. Broker panel — VERIFIED (Appendix C carry-in)
TradingView strategies do not place orders at a broker; alerts are messages.
Pine docs A5/A6 describe only alert emission; execution needs the relay (Part B).

---

## Part B — CrossTrade (default relay) for Tradovate

Docs index: https://crosstrade.io/docs/tradovate

### B1. Linking Lucid accounts ("Link Demo") — VERIFIED
Source: https://crosstrade.io/docs/getting-started/tradovate-guides/linking-your-tradovate-account
- One-time OAuth via Tradovate's own sign-in; CrossTrade stores a token, never
  the password.
- Quote: *"Tradovate's own sim accounts live here, and so does every prop firm
  account."* → *"Click **Link Demo** and sign in with the Tradovate username and
  password your firm issued."* Funded accounts also stay on Demo; Link Live with
  a firm login *"will fail"*.
- Multiple Tradovate identities per environment allowed; quote *"Account names
  must be unique across all Tradovate identities you link."* Duplicate names are
  rejected as ambiguous.
- Warning (first-trade guide https://crosstrade.io/docs/tradovate/your-first-automated-trade):
  *"The Demo label alone does not make an account disposable."* — a Lucid eval on
  Demo still counts against Lucid's limits. Use a Tradovate practice account for
  drills D1–D8 first.
- FAQ: *"Prop-firm accounts normally live in Tradovate Demo even after funding."*
  https://crosstrade.io/answers/faq/accounts-and-prop-firms

### B2. Alert syntax (semicolon format) — VERIFIED
Source: https://crosstrade.io/docs/webhooks/destinations and
https://crosstrade.io/docs/tradovate/your-first-automated-trade
- Every line ends with `;`. Webhook URL (`https://app.crosstrade.io/v1/send/xxx/yyy`)
  goes in the alert's Notifications tab, never in the message.
- Required foundation: `key`, `command`, `account`, `instrument`, `action`,
  `qty`, `order_type`; `destination=tradovate;` routes to Tradovate (omit =
  NinjaTrader 8). Only `nt8`/`tradovate` are valid destinations.
- `key=<RELAY_SECRET>;` — personal secret from My Account; stray whitespace →
  "Secret key missing, invalid, or inactive".
- `account=` = exact Tradovate account name shown in My Account → Brokers.
- `instrument=`: three forms accepted — TradingView continuous **`MNQ1!`**
  (resolved to front month; costs one `GET /contract/suggest`), NinjaTrader
  `MNQ 12-26`, Tradovate `MNQZ6`. Concrete symbols skip the lookup.
- `tif` optional on Tradovate (Day/GTC/IOC/FOK/GTD; GTD needs `expire_time`).
- Supported commands on Tradovate: `place, flatplace, closeposition, flatten,
  flatteneverything, reverse, reverseposition, cancel, cancelorders,
  cancelallorders, change, cancelreplace, cancelandbracket`. `closestrategy`
  unsupported. Unsupported commands are **rejected with an error**, never ignored.
- Rejected on Tradovate: `strategy_id, append_atm, oco_id, stop_loss_stop,
  stop_loss_limit, custom_tag`. Accepted-but-ignored: `strategy_tag`.
- `flatten_first=true;` closes any existing position on that contract first.
- JSON body alternative uses identical field names (`"destination":"tradovate"`).
  https://crosstrade.io/docs/webhooks/json-format

Minimal V6 entry (placeholders only):
```
key=<RELAY_SECRET>;
command=PLACE;
account=<ACCOUNT_1>,<ACCOUNT_2>;
instrument=MNQ1!;
action={{strategy.order.action}};
qty={{strategy.order.contracts}};
order_type=MARKET;
tif=DAY;
take_profit=<T1 price>;
stop_loss=<stop price>;
sync_strategy=true;
market_position={{strategy.market_position}};
prev_market_position={{strategy.prev_market_position}};
target_quantity={{strategy.position_size}};
out_of_sync=flatten;
destination=tradovate;
```
(Agent 04 builds this inside Pine via `alert_message=` and emits it through
`{{strategy.order.alert_message}}`; TradingView placeholders inside
alert_message are NOT expanded — Pine must substitute real values.)

### B3. Native brackets — VERIFIED: **YES**, Tradovate-native OSO
Sources: https://crosstrade.io/docs/webhooks/destinations ,
https://crosstrade.io/docs/tradovate/order-types-and-exits
- Quote: `take_profit` and `stop_loss` *attach a Tradovate-native OSO bracket*;
  *"a single broker-side OSO"*; *"setting both `take_profit` and `stop_loss`
  links the legs for you"*; *"The bracket stop is a plain Stop order."*
- Activation: exits are created Suspended and *"are activated by Tradovate on the
  entry's first fill, sized to that fill."*
- Quote: *"The exits are sent GTC whatever `tif` the entry carries, so they
  outlive the entry's session."*
- Price forms: absolute (`stop_loss=24850.00`), ticks (`20 ticks` / `20tick`),
  points (`5pt`); *"A number with no unit ... is read as an absolute price."*
  Relative offsets are converted server-side from a fresh quote; if no fresh
  quote: *"the alert **fails closed**: no order is placed."*
- Quote: *"On Tradovate the bracket prices are fixed when the order is submitted
  and are not recalculated from the fill."* → V6 sends **absolute** stop/T1
  computed in Pine (I2 satisfied: stop exists at the broker from first fill).
- Partial fills: quantity filled after the first fill is unprotected until
  CrossTrade's rebuild lands; if the stop vanishes CrossTrade cancels remaining
  entry qty and raises a Position Protection alert.
  https://crosstrade.io/docs/tradovate/webhooks
- ATM alternative (`atm_*`, https://crosstrade.io/docs/webhooks/advanced-options/tradovate-atm):
  offsets from the actual fill (bare numbers = ticks, `pt` = points);
  *"A stop is required (`atm_stops`). A target-only ATM is rejected."*;
  `atm_qtys` must sum to `qty`; cannot be combined with `take_profit`/`stop_loss`;
  managed breakeven/trailing need `tif=DAY`. Use `atm_*` if T1/T2 scale-out at
  the broker is wanted (Agent 03/04 decision; PORT_MAP TrailingStopManager row).
- Native trailing: `order_type=TRAILINGSTOP` with `stop_price` + `trail_offset`
  (points; alias `peg_difference`).

### B4. Strategy sync — VERIFIED
Source: https://crosstrade.io/docs/webhooks/advanced-options/strategy-synchronization ,
https://crosstrade.io/docs/tradovate/automating-tradingview-strategies
- `sync_strategy=true;` + required `market_position` and `prev_market_position`
  (`long|short|flat`); optional `target_quantity` (absolute size; sign ignored);
  optional `strategy_exit_block=true` (rejects payloads whose
  prev_market_position ≠ flat → only fresh entries from flat pass).
- `out_of_sync=`: **`wait`** (default; withholds order, warns, *"CrossTrade does
  not retry this signal when the position later aligns"*), **`flatten`**
  (withholds order and flattens the instrument if not flat), **`resync`**
  (corrective market order to reach `target_quantity`; required field),
  **`ignore`** (places anyway with a warning).
- Tradovate: no add-on version requirement — checks run on CrossTrade servers;
  live position *"derived from Tradovate's real-time fill stream"*.
- On `cancelreplace` only `wait`/`ignore` accepted. If a resync would flip the
  entry's direction and the alert carries absolute take_profit/stop_loss, it
  falls back to `wait`.
- Guide pairing for broker-managed exits: `flatten_first=true` +
  `strategy_exit_block=true` + `out_of_sync=flatten`. When a broker-side stop or
  target closes the position, the next signal reports out-of-sync — expected;
  `flatten` is the recommended pairing.
- **V6 default (I1/AP-10):** `out_of_sync=flatten` + `strategy_exit_block=true`
  with pyramiding=0 — a desync never produces a reversal in one action.
- Signals sent while the Tradovate link has expired are recorded as failures and
  **not queued**. Fail-closed on failed position reads (no fallback order).

### B5. Multi-account fan-out + per-account multipliers — VERIFIED
Source: https://crosstrade.io/docs/tradovate/managing-multiple-accounts ,
https://crosstrade.io/docs/tradovate/trade-copier
- Multi-account placement: `account=<A>,<B>,<C>;` — *"CrossTrade dispatches the
  same instruction to each account, with independent results and fill timing."*
  *"Sizing is uniform"*; rule of thumb: *"two or three accounts, same size, same
  contract → multi-account placement is fine."* Standard plan supports it.
- Per-account multipliers: **not in the alert**; use the **Trade Copier** (Pro+):
  leader account + followers; sizing per follower = *"Multiplier on the leader
  quantity (0.5x, 2x, ...)"*, fixed quantity, or *"A ceiling on the follower's
  resulting net position"* (max position cap). Copier groups apply one config to
  several followers. Signal mode: *"Brackets ride along as the same server-side
  OSO."* Same-environment copying (Demo→Demo) is the validated configuration.
  No follower limit; one copier per leader/follower pair.
- `cycle_accounts=true;` sends each signal to ONE account in rotation — **never
  use in V6** (breaks "same side, same time" on all accounts).
- **I1 caveat (VERIFIED):** *"Cross-account Opposing Position Protection is a
  CrossTrade NT8 Add-On feature; not available on Tradovate."*
  https://crosstrade.io/compare/crosstrade-vs-pickmytrade — so I1 rests on Pine
  construction + ONE alert, not on the relay.

### B6. Account controls — VERIFIED (Account Manager, Pro plan and above)
Source: https://crosstrade.io/docs/tradovate/account-manager
- Session loss: *"Trips when session P/L falls below the amount you set."*
- Trailing drawdown: *"trails the high watermark and trips if the account gives
  back more than your threshold."*
- Trading windows: up to five per monitor, US/Eastern; *"Outside an enabled
  window, CrossTrade webhook signals at that account are blocked before they
  reach the broker."*
- Auto-flatten at a configured US/Eastern time (optional; runs in the 5 minutes
  after the time). → L2 backstop for I3: set **16:42 ET**.
- Tripped monitor → blocks webhook signals for that account (+ optional auto-close).
  Account-level **Block Signals** and **Closing Only** apply immediately even with
  P/L monitoring off → **kill switch** (D5). Manual trades inside Tradovate cannot
  be blocked.
- Max quantity per order / max position per account: **not documented** for the
  Tradovate Account Manager → CONSERVATIVE-UNVERIFIED. Enforce size caps in Pine
  (I4) and, if the copier is used, via the follower "max position cap".
- Polling: *"An actively monitored account is polled about 6 times per minute."*

### B7. Rate limits — VERIFIED
Sources: https://crosstrade.io/docs/tradovate/webhooks ,
https://blog.crosstrade.io/understanding-tradovate-api-rate-limits (blog)
- Tradovate order placement: *"Rate limit exceeded: more than 1500 requests per
  hour"* per login; user-level 5,000 requests/hour across endpoints; penalties
  arrive as HTTP 200 with p-ticket/p-time; user-level 429 → *"stop calling for a
  full hour"*. Rejected orders count. Every follower on a login draws on the
  same budget.
- CrossTrade paces requests per linked identity; single retry with p-ticket;
  ambiguous mutations are never blindly resent (no double fill); plain
  placements are **not deduplicated** by CrossTrade or Tradovate → D7 duplicate
  alert drill must rely on `sync_strategy` (B4).
- Optional alert-side dedupe: `rate_limit=X/seconds;` + `id=<name>;`.
- Latency: CrossTrade-side turnaround ~30–40 ms (NT8 figure; FAQ).
- V6 budget: ≤ 3 entries × (entry + bracket legs) × N accounts per session ≪ 1,500.

### B8. Alert History — VERIFIED
- Every alert hitting the webhook is logged with full payload and timestamp
  (FAQ). Tradovate rejects, penalties, outages are recorded on the signal row;
  broker rejections turn the row yellow with Tradovate's reason; flatten that
  cannot be confirmed within ~6 s is reported successful **with a warning**.
  REST/MCP placements have no Alert History row.
  https://crosstrade.io/docs/tradovate/webhooks

### B9. Cost — VERIFIED
Source: https://crosstrade.io/pricing (prices as of 2026-11-05)
- Standard **$29/mo** ($279/yr): webhooks, unlimited alerts, multi-account
  placement, journal. Pro **$49/mo** ($469/yr): + Trade Copier, Account Manager,
  REST API. Elite **$99/mo** ($949/yr): + Signal Share, MCP, XT Management API.
  7-day free trial, all features. Tradovate supported on every plan; no per-
  account or per-connection charge; no fixed cap on accounts.
- **V6 requirement: Pro ($49/mo)** — Account Manager (B6) is the L2 layer.

### B10. Other Tradovate specifics — VERIFIED
- Composite `flatplace`/`reverseposition` flatten first then run the sync gate.
- `cancel_after` 1–180 min (limit orders only). Flatten confirmation checked up
  to 4× over ~3 s. GTD requires ISO-8601 `expire_time`.
- Tradovate API add-on not required (CrossTrade is the registered partner).
- Tradovate status on CrossTrade: generally available (blog
  https://crosstrade.io/blog/tradovate-webhook-trading-generally-available);
  the destinations page still carries an "early alpha" sentence in places —
  treat the Tradovate path as newer than NT8 and drill it (Agent 07).

---

## Part C — Fallback relays (same checklist)

### C1. PickMyTrade (Tradovate via pickmytrade.trade)
Sources: https://pickmytrade.trade/ , https://pickmytrade.trade/pricing ,
https://docs.pickmytrade.trade/docs/tradingview-json-alert-configuration/

| Item | Fact |
|---|---|
| Syntax | JSON only; generated in the app — *"regenerate it in PickMyTrade and paste it fresh rather than editing it"*. Webhook URL ends `/v2/add-trade-data-latest`. |
| Fields | `symbol`, `date` (`{{timenow}}`), `data` (`buy`/`sell`/`close`), `quantity`, `risk_percentage`, `price` (present → limit order), `tp` / `percentage_tp` / `dollar_tp`, `sl` / `percentage_sl` / `dollar_sl`, `trail*`, `order_type` (`MKT`), `update_tp`, `update_sl`, `reverse_order_close`, `pyramid`, `token`, `account_id`, `multiple_accounts` [{token, account_id, risk_percentage, quantity_multiplier}] |
| Auth | `token` inside the JSON body |
| Strategy sync | none documented; `reverse_order_close=true` closes an opposite position first; `pyramid=false` prevents stacking (CONSERVATIVE-UNVERIFIED equivalence to sync) |
| Brackets | `tp`/`sl` absolute, dollar or percentage; placed by PickMyTrade on Tradovate (page lists "5 TP/SL methods"; OSO-native not stated → CONSERVATIVE-UNVERIFIED) |
| Multi-account | `multiple_accounts` array with **`quantity_multiplier` per account**; "unlimited sub-accounts under a single login" |
| Account controls | none documented; kill switch = pause alerts in app (*"unless you manually pause them"*) |
| Rate limits | not published |
| Cost | **$50/mo**, $135/qtr, $500/yr (page's structured data shows $120/$350 — conflict); one subscription per broker/prop login; 7-day trial, no card |
| Link prop accounts | funded/eval accounts connect in "demo" mode with broker demo credentials |
| Instrument | continuous vs dated not documented; symbol root e.g. "NQ" |

### C2. TradersPost
Sources: https://docs.traderspost.io/docs/webhooks ,
https://docs.traderspost.io/docs/all-supported-connections/tradovate ,
https://docs.traderspost.io/docs/assets/prop-firm-trading , https://traderspost.io/pricing

| Item | Fact |
|---|---|
| Syntax | JSON; `ticker` REQUIRED; `action` ∈ buy, sell, exit, reverse, breakeven, cancel, add, resize; `sentiment` (bullish/long, bearish/short, flat); `quantity`; `signalPrice`/`price`; `orderType` market/limit/stop/stop_limit/trailing_stop; `timeInForce` day/gtc/ioc/fok/…; body ≤ 16 KiB; fields case-sensitive |
| Auth | per-strategy webhook URL (secret in URL) |
| Strategy sync | `sentiment` declares the post-trade position; `cancel` controls cancelling open orders; no out-of-sync flatten option documented |
| Brackets | `takeProfit` {limitPrice \| amount \| percent \| pnlAmount}, `stopLoss` {type stop/stop_limit/trailing_stop, stopPrice, amount, percent, trailAmount, …}; on Tradovate *"A take profit and a stop loss go out together as attached orders"* (OTO); **OCO marked unsupported**; percent trailing → fixed points |
| Multi-account | one Tradovate login lists every account; subscribe each; *"The same alert then reaches each subscribed account using that subscription's own settings"* (per-subscription sizing) |
| Account controls | "account-level risk parameters" per subscription (docs); no explicit kill switch documented |
| Market data | *"The Tradovate integration does not include market data."* → continuous `MNQ1!` *"aren't reliably resolved, so use dated contracts like MNQZ2026"*; relative exits need a price in the signal or fail |
| 2FA gotcha | Tradovate 2FA on → approve TradersPost email or live orders fail 401 |
| Lucid | *"Lucid Trading accounts can be reached through Tradovate"* — connect the Tradovate login that holds the Lucid account |
| Cost | Starter $49/mo (1 live acct), Basic $99 (2), Pro $199 (3), Premium $299 (6); extra live account **$10/mo**; 7-day paper trial |

### C3. Adapter switch summary for Agent 04
| Capability | CrossTrade | PickMyTrade | TradersPost |
|---|---|---|---|
| Broker-native bracket at first fill (I2) | **Yes** (OSO, GTC legs) | Likely (not stated as OSO) | Attached OTO, OCO unsupported |
| Strategy sync w/ flatten on desync | **Yes** (`out_of_sync=flatten`) | No | No |
| Fan-out in one message | `account=a,b` (uniform) / copier (ratio) | `multiple_accounts` + multiplier | per-subscription settings |
| Account controls / kill switch | Account Manager (Pro) | pause only | limited |
| Continuous `MNQ1!` accepted | Yes | unknown | **No** (dated contract) |
| Cost for 2–5 Lucid accounts | $49/mo | $50/mo per login | $49–$89/mo |

**Default stays CrossTrade.** Only CrossTrade documents all of: native OSO at the
broker, flatten-on-desync, Link Demo for prop accounts, and account-level signal
blocking.

---

## ⛔ OWNER-ACTION items from Part A/B
| ID | Action |
|---|---|
| OA-0 (refined) | TradingView **Premium** (open-ended alerts) + 2FA + CME Group real-time (non-pro) data; CrossTrade **Pro** ($49/mo). |
| OA-5 (refined) | Link Demo per Lucid account; Account Manager: session-loss = softDailyLoss$, auto-flatten 16:42 ET (15:42 CT), trading window 09:30–16:30 ET; keep Block Signals as kill switch. |
