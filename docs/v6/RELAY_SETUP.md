# RELAY_SETUP.md — relay payload + order lifecycle (V6 Agent 04)

Owner: Agent 04 (tasks 04.1–04.6). Hand-off: payload spec + relay settings → Agent 07 (drills D1–D8);
this file → Agent 08 (runbook).
Every relay / TradingView rule here cites `docs/PLATFORM_FACTS.md` as **PF:&lt;line&gt;** (that file cites
the vendor URL, retrieved 2026-11-05). Nothing is stated from memory (R4). Times are ET with CT (= ET − 1 h).
Placeholders only: `<RELAY_SECRET>`, `<ACCOUNT_1>`, `<ACCOUNT_2>`, `<WEBHOOK_URL>` (R5).

Code: `pine/src/75_payload.pine` (builders + the `orderAlert` hook, sorted before 80 because Pine
resolves functions top-down), `pine/src/10_inputs.pine` [Relay] group, `pine/src/90_alerts.pine`
(config warnings), `tools/payload_check.py` (+ `tools/tests/test_payload_check.py`), lint rule L10.

---

## 1. How an order reaches Tradovate

```
80_strategy order call ── alert_message = orderAlert(kind, dir, qty, stopPx, tpPx, why)
        │                         (75_payload: relay builder chosen by input "relay")
        ▼
TradingView broker emulator fills → the ONE strategy alert fires with Message
{{strategy.order.alert_message}} (PF:79-81) → webhook <WEBHOOK_URL> (Notifications tab, PF:137-138)
        ▼
CrossTrade: sync gate (PF:215-223) → Tradovate order + native OSO bracket (PF:185-191)
```

The six order calls in 80 and the event each produces:

| 80_strategy call | kind | When TV fires the fill alert | Relay effect |
|---|---|---|---|
| `strategy.entry("LONG"/"SHORT")` | ENTRY | entry fills at the confirmed 5m close | market order + OSO (stop + T1) |
| `strategy.exit("LONG_BRK"/"SHORT_BRK")` | BRACKET | TV's emulator sees SL or T1 touched | **rejected on purpose** (§3) |
| `strategy.close_all(comment="FLATTEN")` | FLATTEN | 16:40 ET (15:40 CT) bar close, or stale position after the 18:00 ET roll | `command=FLATTEN` |
| `strategy.close_all(comment="SOFT_STOP")` | SOFT_STOP | soft daily loss reached | `command=FLATTEN` |

The `strategy.risk.max_intraday_loss` / `max_intraday_filled_orders` built-ins close WITHOUT
`alert_message` (Agent 03, 80:95-99). The alert for such a fill has an empty message and fails (PF:81-83),
so no order is sent. The broker position stays under its OSO stop, and the CrossTrade Account Manager
session-loss monitor (§7) is the L2 hard stop. They are backstops only, because SOFT_STOP fires first
with a payload.

## 2. TradingView alert (ONE alert, never two — I1)

1. Chart MNQ1! 5 minutes, script `LucidICT_v6` → Settings → **Relay** group: `relay` = CROSSTRADE,
   `relay secret` = your CrossTrade secret (My Account; no spaces, PF:142-143), `accounts` = the exact
   Tradovate account names, comma-separated (PF:144), `tradingEnabled` = ON. These two inputs are
   `display.none`, so they never appear in the status line or the Data Window.
2. Alerts (clock icon) → Create → Condition: `LucidICT_v6` → **Order fills and alert() function calls** (PF:76-77).
3. Message: exactly `{{strategy.order.alert_message}}`, with nothing else (PF:79-81).
4. Notifications → Webhook URL = `<WEBHOOK_URL>` from CrossTrade (`https://app.crosstrade.io/v1/send/…`).
   It goes only in this field and never in the message (PF:137-138). TradingView needs 2FA for webhooks (PF:27-28).
5. Expiration: **Open-ended** (Premium; PF:39-48). Name: `LucidICT_v6 <BUILD_ID>` (dist line 2, `src-sha256`).
6. Any script or input change means deleting this alert and creating a new one, because the alert is a snapshot (PF:63-73, drill D8).
   The new payloads carry the new BUILD_ID in `strategy_tag`, so CrossTrade Alert History shows which
   version fired (PF:294-296).

Dry run: with `tradingEnabled` OFF, or with a placeholder secret or placeholder accounts, every payload carries
`key=<DRY_RUN>`. CrossTrade rejects that key as "Secret key missing, invalid, or inactive" (PF:142-143), so you can create the alert,
watch fills, and copy messages with no order risk. 90_alerts logs a warning when `tradingEnabled` is ON but a
placeholder is still set.

## 3. Decision: bracket exit fills (TV `strategy.exit` fill alerts)

**Problem.** The stop and T1 run at Tradovate as a native OSO, created with the entry (PF:185-191).
TradingView also simulates that bracket. When its emulator sees SL or T1 touched, it fires an
order-fill alert for the exit as well (PF:84). If that alert sent a closing order:
- if the broker OSO already filled, a new market order would open a NEW opposite position (AP-10 / I1);
- if TV filled first (data or tick differences), the market close would leave the OSO legs working,
  and a later leg fill would open an unprotected position.

**Decision: the exit-fill payload is sync-only and is rejected by the relay.** The relay must never
act on it. The BRACKET payload carries `sync_strategy=true`, `prev_market_position=long|short`,
`market_position=flat` and **`strategy_exit_block=true`**. CrossTrade documents that this flag *rejects
payloads whose prev_market_position ≠ flat, so only fresh entries from flat pass* (PF:217-218). It is
also the vendor's documented pairing for broker-managed exits: `flatten_first=true` +
`strategy_exit_block=true` + `out_of_sync=flatten`, where *"when a broker-side stop or target closes the position, the
next signal reports out-of-sync — expected; `flatten` is the recommended pairing"* (PF:229-232).
Second layer: if the payload were evaluated anyway, `out_of_sync=flatten` against an already-flat
broker withholds the order. It "withholds order and flattens the instrument if not flat" (PF:219-221),
so the worst case is a flatten and never a new position.

Why not suppress the alert? An order call without `alert_message` produces an empty message, and an empty
message fails the alert (PF:81-83). That is noisy, and it would break lint L10's guarantee for the
calls that matter. TradingView documents no per-order way to skip one fill (PF:75-85). So the message
exists, and the relay discards it by rule.

Fallbacks have no exit block (PF:334, PF:352). For them the BRACKET message is made non-actionable:
PickMyTrade gets no `token` and no `data` action (auth is the body token, PF:333), and TradersPost gets no `ticker`
(REQUIRED, PF:350). The broker-side legs own the exit there too.

## 4. Payload spec (CrossTrade, Tradovate), with a citation for each field

Reference payloads come from `python3 tools/payload_check.py --sample --build-id <BUILD_ID>` (G1: SELL 3 MNQ,
stop 30674.00, T1 30558.00). Pine emits the same fields in the same order (pinned by
`test_crosstrade_field_set_matches_pine`). Fields are separated by `;` + newline (PF:137) and sent as text/plain (PF:56).

**ENTRY**
```
key=<RELAY_SECRET>;
command=PLACE;
account=<ACCOUNT_1>,<ACCOUNT_2>;
instrument=MNQ1!;
action=SELL;
qty=3;
order_type=MARKET;
tif=DAY;
take_profit=30558.00;
stop_loss=30674.00;
flatten_first=true;
sync_strategy=true;
market_position=short;
prev_market_position=flat;
target_quantity=3;
out_of_sync=flatten;
strategy_exit_block=true;
strategy_tag=LucidICT_v6-<BUILD_ID>-ENTRY;
destination=tradovate;
```
**BRACKET** (TV-side exit fill; rejected by design)
```
key=<RELAY_SECRET>;
command=PLACE;
account=<ACCOUNT_1>,<ACCOUNT_2>;
instrument=MNQ1!;
action=BUY;
qty=3;
order_type=MARKET;
tif=DAY;
sync_strategy=true;
market_position=flat;
prev_market_position=short;
target_quantity=0;
out_of_sync=flatten;
strategy_exit_block=true;
strategy_tag=LucidICT_v6-<BUILD_ID>-BRACKET;
destination=tradovate;
```
**FLATTEN** / **SOFT_STOP** (only `strategy_tag` differs)
```
key=<RELAY_SECRET>;
command=FLATTEN;
account=<ACCOUNT_1>,<ACCOUNT_2>;
instrument=MNQ1!;
strategy_tag=LucidICT_v6-<BUILD_ID>-FLATTEN;
destination=tradovate;
```

| Field | Value / source in Pine | Citation |
|---|---|---|
| `key` | input `relay secret` (display.none); `<DRY_RUN>` when not live | PF:142-143 |
| `command` | `PLACE` (ENTRY, BRACKET), `FLATTEN` (FLATTEN/SOFT_STOP) | PF:139, PF:149-152 |
| `account` | input `accounts`, spaces stripped; fan-out `a,b` | PF:144, PF:241-243 |
| `instrument` | chart `syminfo.ticker` (`MNQ1!` or a dated MNQ) unless overridden | PF:145-147 |
| `action` / `qty` / `order_type` | BUY/SELL, Pine `entryQty` (I4-capped), MARKET | PF:139-140, PF:165-167 |
| `tif` | DAY. The OSO legs are GTC regardless | PF:148, PF:190-191 |
| `take_profit` / `stop_loss` | **absolute** `sigT1` / `sigStop` (format.mintick) | PF:185-198 (fixed at submission, PF:196-197) |
| `flatten_first` | true (ENTRY only) | PF:155, PF:229 |
| `sync_strategy`, `market_position`, `prev_market_position`, `target_quantity` | Pine values (placeholders are not expanded inside alert_message, PF:178-180) | PF:215-218 |
| `out_of_sync` | flatten | PF:219-223, PF:233-234 |
| `strategy_exit_block` | true (ENTRY, BRACKET) | PF:217-218, PF:229-234 |
| `strategy_tag` | `LucidICT_v6-<BUILD_ID>-<KIND>` (drill D8) | accepted-but-ignored on Tradovate PF:154; visible in Alert History PF:294-296 |
| `destination` | tradovate | PF:140-141 |

Never sent: `strategy_id, append_atm, oco_id, stop_loss_stop, stop_loss_limit, custom_tag` (rejected,
PF:153-154), `cycle_accounts` (PF:252-253), `atm_*` (cannot combine with take_profit/stop_loss, PF:206).
`payload_check.py` fails on any of them.

## 5. Order lifecycle (04.4)

| # | Event | TradingView state | Relay (CrossTrade) state | Broker (Tradovate) state | Owner action |
|---|---|---|---|---|---|
| L1 | **Entry fill** | position ±q at the 5m close; LONG_BRK/SHORT_BRK working | ENTRY payload passes the sync gate (prev flat = broker flat) → PLACE; Alert History row green (PF:294-296) | market fill ±q; OSO stop + T1 activated on the first fill, sized to it, GTC (PF:188-191) | none. Spot-check the bracket on the first trades (D2) |
| L2 | **T1 hit** (V6 has no partials: T1 = full exit, Agent 03 X2) | TV exit fill → BRACKET payload | rejected by `strategy_exit_block` (§3). Alert History shows the rejection (expected) | OSO limit leg fills, stop leg cancelled by the OSO; flat | none. A rejected BRACKET row is normal |
| L3 | **Stop fill at the broker, TV does not see it** (or sees it later/never) | TV still ±q (until its own emulator fills → L2) | next ENTRY: prev flat vs broker flat → in sync. If TV is still in a position it sends no entry (pyramiding 0, flatOk) | flat (stop leg filled) | none. If TV stays in a position the broker no longer holds, the 16:40 ET FLATTEN (no-op at the broker) realigns it. D3 drills this |
| L3b | **TV bracket fills, broker leg not yet** (data difference) | flat | BRACKET payload rejected; next ENTRY from flat while the broker is still ±q → out_of_sync → **withhold + flatten** (PF:219-221) | still ±q under its OSO until a leg fills, or the relay flattens it on the next signal | none. If it persists to 16:40 ET, FLATTEN closes it |
| L4 | **Relay down / webhook fails** | normal; Alert log "Webhook status" shows the failure (PF:57-59) | nothing received. No retry (PF:57-59); expired link signals are not queued (PF:235-236) | ENTRY missed → flat (safe). FLATTEN missed → position stays under its OSO stop (I2) | Check Alert log + Alert History. If a position is open at 16:40 ET with no FLATTEN, flatten it in Tradovate. Auto-flatten 16:42 ET (15:42 CT) is the L2 backstop (§7) |
| L5 | **Duplicate alert** (same ENTRY twice) | one position | 2nd ENTRY: prev flat vs broker ±q → out_of_sync → withhold + flatten (PF:219-221). Placements are not de-duplicated (PF:286-289) | the 1st position is flattened: **no double position**, but the trade is lost | Check Alert History. Delete any second alert at once (I1, D6/D7) |
| L6 | **TradingView alert stopped** (expired / >15 triggers in 3 min / deleted, PF:37-45) | strategy keeps simulating; no messages | idle | open position keeps its OSO; no new entries | Re-create the alert (§2). Premium + Open-ended prevents expiry |
| L7 | **Tradovate rejection** (margin, rate limit, contract) | TV believes ±q | row turns yellow with Tradovate's reason (PF:296-298) | flat (nothing placed). A stop-leg loss after a partial fill → Position Protection alert, remaining entry cancelled (PF:199-202) | Read the reason in Alert History. TV is now out of sync: the next ENTRY is refused by TV (not flat) until TV exits, and the next sync gate realigns. If unsure, disable the alert |
| L8 | **Flatten at 16:40 ET (15:40 CT)** | close_all FLATTEN at the 16:35–16:40 bar close (I3) | `command=FLATTEN` (not sync-gated) | flat. Flatten unconfirmed in ~6 s is reported success + warning (PF:297-298) | none. Lucid's own forced flat is 16:45 ET / 15:45 CT (LUCID_RULES:124-125). Account Manager auto-flatten 16:42 ET is the backstop |
| L9 | **Owner kill switch** | alert paused/deleted (TV keeps simulating) | Account Manager **Block Signals** or **Closing Only** applies immediately (PF:270-272) | flatten manually in Tradovate if a position is open. Manual trades cannot be blocked by the relay (PF:272) | 1) CrossTrade → Account Manager → Block Signals; 2) TradingView → pause the alert; 3) Tradovate → flatten. D5 |
| L10 | **Soft daily stop / built-in risk halt** | SOFT_STOP close_all (payload) or a built-in halt (empty message) | FLATTEN, or a failed alert (built-in) | flat, or under OSO plus the session-loss monitor (§7) | none. Investigate if a built-in halt fired (it means SOFT_STOP did not) |

## 6. Multiple accounts (04.6)

- **One strategy instance, one alert, N accounts:** `accounts = <ACCOUNT_1>,<ACCOUNT_2>`. CrossTrade
  *"dispatches the same instruction to each account, with independent results and fill timing"* and
  *"sizing is uniform"* (PF:241-244), so every account gets the same side, the same time, and the same qty (I1).
- **Per-account multiplier:** not available in the alert (PF:245). Use CrossTrade **Trade Copier**
  (Pro): leader = `<ACCOUNT_1>` (the only account in `accounts`), followers with a multiplier and a
  **max position cap** (PF:245-251). Brackets ride along as the same OSO (PF:249-250). Copy Demo→Demo only (PF:250).
  Use one method or the other, never both for the same account.
- **LucidFlex accounts in the relay:** each Lucid account (eval or funded) is linked with **Link Demo**
  using the Tradovate login Lucid issued (PF:116-131). Account names must be unique across linked
  identities (PF:125-126). Name the accounts in `accounts` exactly as My Account → Brokers shows them.
- **Size:** Pine caps qty per order by the profile (I4). All accounts in one list must share the same
  AccountProfile, or the cap is wrong for the smaller one; otherwise use the copier with a lower multiplier.
- **NEVER** two strategies (or two alerts, or a second chart) on overlapping accounts. CrossTrade's
  cross-account opposing-position protection is NOT available on Tradovate (PF:254-257), so I1 rests on one
  Pine instance + one alert. Never `cycle_accounts` (PF:252-253). Do not trade these accounts by hand while
  the alert is on (OA-6).
- Rate budget: ≤ 3 entries × (entry + 2 legs) × N accounts per session, far below 1,500/h per login (PF:281-292).

## 7. Relay account controls (OA-5, CrossTrade Pro — Account Manager, PF:259-276)

For each linked Lucid account:
| Control | Setting | Why |
|---|---|---|
| Session loss | = profile softDailyLoss$ (Pine input `[Account]`; e.g. LucidFlex 50K preset) | L2 hard stop behind SOFT_STOP and the built-in (PF:261) |
| Trailing drawdown | profile MLL − drawdownBuffer$ | L2 for the MLL (PF:262-263) |
| Trading window | 18:00–16:30 ET (17:00–15:30 CT) Sun–Fri, or narrower | signals outside the window are blocked before the broker (PF:264-266) |
| Auto-flatten | **16:42 ET** (15:42 CT) | I3 backstop between Pine's 16:40 and Lucid's 16:45 (PF:267-268) |
| Tripped action | block signals + auto-close | PF:269-270 |
| Kill switch | Block Signals / Closing Only toggle | immediate (PF:270-272) |
| Max qty / position | not documented for Tradovate (PF:273-275). Pine I4 cap; copier max position cap if used | |

## 8. Fallback relays (input `relay`)

Same `orderAlert` → OrderEvent → `pmtPayload` / `tpPayload`. These are CONSERVATIVE-UNVERIFIED (no strategy
sync, no flatten-on-desync, PF:361-373). Switch only if CrossTrade loses Tradovate native brackets
(04 FAILURE clause).
- **PICKMYTRADE** (JSON, PF:331-336): entry `{"symbol":"MNQ","date":…,"data":"buy|sell","quantity":q,"order_type":"MKT","tp":T1,"sl":stop,"pyramid":false,"reverse_order_close":false,"token":"<RELAY_SECRET>","account_id":"<ACCOUNT_1>","multiple_accounts":[…quantity_multiplier 1…]}`;
  flatten `"data":"close"`; bracket fill = no token, no action.
- **TRADERSPOST** (JSON, PF:350-356; secret in the webhook address): entry `{"ticker":"MNQZ2026","action":"buy|sell","sentiment":"long|short","quantity":q,"orderType":"market","timeInForce":"day","takeProfit":{"limitPrice":T1},"stopLoss":{"type":"stop","stopPrice":stop}}`;
  flatten `{"ticker":…,"action":"exit","sentiment":"flat","cancel":true}`; bracket fill = no ticker.
  Set `relay instrument` to the dated contract (continuous symbols are not resolved, PF:356); 90_alerts warns.
  BUILD_ID is not carried, because neither fallback documents an ignored field.

## 9. Checking real payloads (04.5)

1. Set `log masked payloads` ON (Relay group). Pine Logs (⋯ → Pine Logs) then show each payload with
   `<RELAY_SECRET>` / `<ACCOUNT_n>` substituted. Alternatively copy 3 rows from the Alert log Message
   column (dry run: `key=<DRY_RUN>`).
2. Paste them into a file, then run `python3 tools/payload_check.py samples.txt --build-id <BUILD_ID> --cap 5`
   (`--relay PICKMYTRADE|TRADERSPOST` for fallbacks; `--live` makes placeholders errors).
   Any header text is ignored, collapsed one-line Alert-log rows are fine, and a key is never printed.
3. Expected: ENTRY / BRACKET / FLATTEN all PASS. Paste the output into the PR (A-07).

## 10. Residual risks (for Agent 07)

- R-1 Compile: `75_payload.pine` (UDT, `log.info("{0}", …)`, `str.format_time`, `display.none` on
  input.string) is static-only until OA-1.
- R-2 `command=FLATTEN` field set: PF:149 lists the command, but no flatten-specific field list is
  quoted. D4 must confirm it flattens and cancels the OSO legs on every listed account.
- R-3 `strategy_exit_block` rejects the BRACKET message: drill D2/D3 must show the Alert History rejection
  and no order.
- R-4 L5 duplicate ENTRY flattens the live trade (safe, but the trade is lost). L3b relies on the next
  signal or the 16:40 FLATTEN.
- R-5 Built-in `strategy.risk` halts send no payload (§1). L2 session-loss is the hard stop.
- R-6 Fallback relays: OSO-ness and sync are not documented (PF:334-335, PF:352-353).
