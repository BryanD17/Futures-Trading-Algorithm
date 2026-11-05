# RUNBOOK — LucidICT V6 on TradingView → CrossTrade → Lucid (Tradovate)

Written by Fable Agent 08, 2026-11-05 (draft; final sign-off waits on Agents 06/07). For the owner, to be
run **alone**. Every time is **ET (CT)**; your chart is CT = ET − 1 h. Nothing here is from memory: venue
rules cite `docs/LUCID_RULES.md` (LR §), TradingView/relay facts cite `docs/PLATFORM_FACTS.md` (PF §),
payloads and failure handling cite `docs/v6/RELAY_SETUP.md` (RS §).

Predecessor: `docs/RUNBOOK_V5.md` (the Java engine trading Topstep) is **historical**. Topstep access was
revoked 2026-11-05; the Java engine no longer trades — `LIVE` fails loud and the API returns 410 (§9).

---

## 1. The system in one picture

```
 TradingView (cloud, 24/5)          CrossTrade (cloud relay)           Tradovate (Lucid accounts)
 ┌────────────────────────┐  POST   ┌──────────────────────────┐       ┌──────────────────────────┐
 │ MNQ1! 5m chart         │ ──────▶ │ key check → sync gate →  │ ────▶ │ market entry + native    │
 │ LucidICT_v6 strategy   │ webhook │ account controls → OSO   │       │ OSO bracket (stop + T1,  │
 │ ONE alert              │         │ bracket → N accounts     │       │ GTC) on every account    │
 └────────────────────────┘         └──────────────────────────┘       └──────────────────────────┘
 your computer: OFF is fine          Alert History = the log            Tradovate app = the truth
 Java engine (this repo): research lab + parity oracle only. Not in the live path.
```

Four safety layers, all required (master prompt Section 2): **L1** Pine (sizing, session gates,
flat-by, max 3 entries/day) · **L2** CrossTrade Account Manager (session loss, trailing drawdown,
trading window, auto-flatten 16:42 ET / 15:42 CT, Block Signals) · **L3** the broker-side OSO stop on every
trade · **L4** Lucid's own liquidation at 16:45 ET / 15:45 CT (reaching it = incident, §8).

## 2. The clock (memorise this row)

| what | ET | CT | source |
|---|---|---|---|
| CME trading day rolls / strategy day counters reset | 18:00 | 17:00 | Pine `maxEntriesPerSession` per CME day (A-03) |
| ASIA killzone (entries allowed) | 19:00–02:00 | 18:00–01:00 | Pine [Time] |
| LONDON killzone | 02:00–08:00 | 01:00–07:00 | Pine [Time] |
| NY_AM / NY_LUNCH / NY_PM | 09:30–12:00 / 12:00–13:30 / 13:30–15:45 | 08:30–11:00 / 11:00–12:30 / 12:30–14:45 | Pine [Time] |
| **last new entry** (default `noEntryAfter 1545`; hard ceiling 16:30) | 15:45 (hard 16:30) | 14:45 (hard 15:30) | I3 |
| **Pine flattens everything** (`flattenAt 1640`) | 16:40 | 15:40 | I3, RS §5 L8 |
| CrossTrade auto-flatten backstop | 16:42 | 15:42 | RS §7, PF B6 |
| Lucid forced flat (never rely on it) | 16:45 | 15:45 | LR §5 |
| CrossTrade trading window (signals outside are blocked) | 18:00–16:30 | 17:00–15:30 | RS §7 |
| Friday: `fridayFlattenAt` (default 1640) | 16:40 | 15:40 | Pine [Time] |

DST: Pine computes everything in `America/New_York`; changing your chart time zone changes nothing.

## 3. Setup checklist (once; ≈ 1 hour; OA-0 → OA-5)

Tick in order. Each line says where the exact clicks are.

**TradingView**
- [ ] S1 Plan **Premium** (open-ended alerts; on Essential/Plus the alert dies after 2 months — PF A3).
- [ ] S2 **2FA enabled** (webhooks refuse to fire without it — PF A2). Store the backup codes offline.
- [ ] S3 **CME real-time data** (non-professional) bought under avatar → Settings and billing → Subscriptions →
      Real-time market data (PF A7). Check the chart: no "Data is delayed" label in the status line. Delayed
      data = alerts ~10 min late = unacceptable.
- [ ] S4 Chart: **MNQ1!** (CME), **5 minutes**, chart time zone America/Chicago (any is fine). One chart,
      one strategy, one alert — never a second chart with this strategy (I1).
- [ ] S5 Paste `pine/dist/LucidICT_v6.pine` (OA-1 steps, master prompt Appendix D) → compiles with zero
      errors → note the 16-hex **BUILD_ID** on line 2.
- [ ] S6 Inputs (gear → Inputs): leave every default. **[Account]** `Plan profile` = the plan you hold
      (`LUCID_FLEX_50K_EVAL` first; later `LUCID_FLEX_50K_FUNDED`). The profile sets the caps only
      (LucidFlex 50K: 40 micros max, MLL $2,000, soft daily loss $600 CONSERVATIVE until OA-7, drawdown
      buffer $200 — `config/lucid_profiles.yaml`, LR §6–§8). `max micros per trade` 5 (strategy cap; raise
      only after parity + 5 clean sessions, §7).
- [ ] S7 **[Relay]**: `relay = CROSSTRADE`; `relay secret` = your CrossTrade key; `accounts` = exact Tradovate
      account name(s), comma-separated; `tradingEnabled` **OFF** until §7 step 3 says ON. These two fields
      are hidden from the chart (display.none) — still never screenshot the dialog.

**CrossTrade** (Pro, $49/mo — Account Manager is the L2 layer, PF B9)
- [ ] S8 My Account → Brokers → Tradovate → **Link Demo** with the Tradovate login Lucid issued (NOT Link
      Live — it fails for prop logins, PF B1). Every Lucid account (eval or funded) lives on Demo. Names must be
      unique across logins (PF B1).
- [ ] S9 Account Manager per Lucid account (RS §7): monitoring ON; **Session loss $600** (= softDailyLoss$);
      **Trailing drawdown $1,800** (= MLL $2,000 − $200 buffer); **Trading window 18:00–16:30 ET
      (17:00–15:30 CT)** Sun–Fri; **Auto-flatten 16:42 ET (15:42 CT)**; tripped → block signals + close
      positions. Find the **Block Signals** toggle — that is your kill switch (§5).
- [ ] S10 Copy the secret key + webhook URL into a password manager. They never go in the repo, chat, PRs or
      screenshots (R5).

**The ONE alert** (RS §2)
- [ ] S11 Alerts (clock icon) → Create → Condition `LucidICT_v6` → **"Order fills and alert() function calls"**;
      Message exactly `{{strategy.order.alert_message}}`; Notifications → **Webhook URL** = the CrossTrade URL;
      Expiration **Open-ended**; Name `LucidICT_v6 <BUILD_ID>`.
- [ ] S12 With `tradingEnabled` OFF every payload is a **DRY RUN** (`key=<DRY_RUN>`), rejected by CrossTrade as
      "Secret key missing, invalid, or inactive" (RS §2). Leave it like this for ≥ 1 session; read the Alert log
      (Alerts panel → log) and CrossTrade Alert History to see the messages flow.
- [ ] S13 Run the drills: `docs/v6/DRILL_SCRIPT.md` D1–D8 on a Tradovate **practice** account, then D1/D2/D4 on
      one Lucid eval at 1 micro. Fable stamps `docs/v6/DRILL_REPORT.md`.

**Lucid**
- [ ] S14 OA-4 (hedging wording) and OA-7 (daily loss limit amount) sent to support and the replies pasted into
      `docs/LUCID_RULES.md` §2 / §6 (exact questions are printed there).

## 4. Daily pre-open checklist (≈ 3 minutes, before 18:00 ET / 17:00 CT or before your first killzone)

| # | check | where | if not |
|---|---|---|---|
| D1 | the alert is **active** (not paused/expired), name shows the current BUILD_ID | TradingView → Alerts | re-create (§6); a 2-month expiry means you are not on Premium |
| D2 | Alert log: yesterday's rows have **Webhook status OK** (no red) | Alerts → log | red = relay unreachable → §8 L4 |
| D3 | no "Data is delayed" label | chart status line | renew real-time data before trading |
| D4 | Tradovate: **flat** on every bot account, **no working orders** | Tradovate → Positions / Orders | a survivor = incident §8 (flatten it now, then find out why) |
| D5 | CrossTrade: Block Signals **OFF** for trading accounts, monitors not tripped, Tradovate link **not expired** | Account Manager / Brokers | expired link: re-Link Demo; signals during the outage were **not queued** (PF B4) |
| D6 | Alert History: last row's `strategy_tag` carries the current BUILD_ID | My Account → Alert History | an old id = an old alert still alive → §6 |
| D7 | roll week? (the Thursday before the 3rd Friday of Mar / Jun / Sep / Dec) | calendar | set `relay instrument` to the dated contract, e.g. `MNQZ6` (RS R-7), re-create the alert |
| D8 | you are not planning to trade these accounts by hand today (OA-6) | — | if you must, Block Signals first and pause the alert |

## 5. Kill switch — three ways, fastest first

1. **CrossTrade → Account Manager → Block Signals ON** (per account; immediate; PF B6). Stops every new order
   from reaching Tradovate. Does **not** touch an open position — its OSO stop stays working.
2. **TradingView → Alerts → pause/delete the LucidICT_v6 alert.** Stops the messages at the source; the
   strategy keeps simulating silently. Deleting means re-creating later (§6).
3. **Tradovate → Flatten** the account(s) by hand. The only switch that closes a position. The relay cannot block
   your manual action (PF B6). If you flatten manually while the alert is still live, do 1 or 2 **first** so the
   next signal cannot re-enter.

Use all three for a real emergency, in that order. Write the time (ET + CT) down for §8.

## 6. Updating the script (every time the dist file changes — even one line)

Why: an alert is a **snapshot** of the script and its inputs at creation (PF A5). Editing the script, or
any input, leaves the old alert firing the old code until you delete it.

1. Fable merges the change; `pine/dist/LucidICT_v6.pine` line 2 shows a **new** BUILD_ID.
2. Pine Editor → open `LucidICT_v6` → select all → paste the new file → Save. Check zero errors.
3. Re-check [Account]/[Relay] inputs (a paste keeps saved inputs, a fresh "Add to chart" does not).
4. **Delete** the old alert. Create the ONE alert again (§3 S11) named with the new id.
5. Confirm on the next bar: Alert log / Alert History `strategy_tag=LucidICT_v6-<NEW>-…` (drill D8).
6. Never run two alerts "to be safe" — two alerts on the same accounts is the hedge-risk pattern I1 forbids.

Changing an input also requires step 4 (same snapshot rule).

## 7. Go-live ladder (do not skip a rung — master prompt 08.2)

| rung | gate | evidence | who stamps |
|---|---|---|---|
| 1 | Strategy Tester parity vs the Java oracle | `PARITY_REPORT.md` (OA-2/OA-3 exports → `tools/run_parity.sh`): zero hard fails, every mismatch root-caused | Fable Agent 06 |
| 2 | Demo drills D1–D8 PASS, then D1/D2/D4 on one Lucid eval at 1 micro | `docs/v6/DRILL_REPORT.md` | Fable Agent 07 |
| 3 | **one** Lucid eval, `max micros per trade` = **1**, `tradingEnabled` ON (delete + re-create the alert), **5 sessions** with zero Lucid rule flags, zero incidents | your Tradovate statements + Alert History; LR §3/§4 (no HFT / microscalp flag emails) | owner, in the ledger |
| 4 | normal size on that eval: `max micros per trade` back to 5 (re-create the alert) | 5 more clean sessions | owner |
| 5 | add accounts: `accounts = <ACCOUNT_1>,<ACCOUNT_2>` (same profile, same size — RS §6) or the Trade Copier for ratios; re-create the alert | first session's Alert History shows every account filled same side, same time | owner + Fable spot check |

Rung 3 flip, exactly: Settings → Relay → `tradingEnabled` ON → OK → **delete the alert → create it again**
(§6 step 4). Until the alert is re-created the ON switch has no effect (snapshot rule).

## 8. Incident playbook (from RS §5 lifecycle table; row ids match it)

| you see | what it is | do this |
|---|---|---|
| Alert History row **rejected** right after an exit, reason "prev_market_position is not flat" or "missing action/qty" | **L2 normal.** TradingView's own bracket-fill message; the relay rejects it by design — the broker OSO already did the exit (RS §3) | nothing |
| TradingView shows a position, Tradovate is **flat** | **L3**: the broker stop/T1 filled; TV has not seen it yet | nothing. TV realigns at the latest at the 16:40 ET (15:40 CT) FLATTEN (a no-op at the broker). TV sends no new entry while it thinks it holds one |
| Tradovate holds a position with its two legs, TradingView is **flat** | **L3b**: TV's emulator exited first (data difference) | nothing; the next ENTRY's `flatten_first` clears it, or the 16:40 ET FLATTEN does. If still open at 16:41 ET (15:41 CT) flatten by hand |
| Alert log **Webhook status red** | **L4**: relay unreachable; the message is lost (no retry, PF A4) | an ENTRY lost = safe (flat). A FLATTEN lost = the position sits under its OSO stop → check Tradovate at 16:41 ET (15:41 CT); auto-flatten 16:42 ET (15:42 CT) is the backstop; flatten by hand if needed |
| two identical ENTRY rows seconds apart | **L5**: duplicate alert | final position must be 1× or flat, never 2× (drill D7 recorded which). If you ever see 2×: §5 all three, then report |
| alert silently gone / "expired" | **L6**: expiry (2 months on non-Premium) or the 15-triggers-in-3-min auto-stop (PF A3) | re-create (§6). Open positions keep their OSO |
| Alert History row **yellow** with a Tradovate reason | **L7**: broker rejection (margin, size, contract) | TV now thinks it holds a position it does not → it will not re-enter until its own exit; nothing to do unless it repeats → pause the alert and read the reason |
| 16:40 ET (15:40 CT) came and Tradovate is **not flat** | **L8 failed** (FLATTEN lost or rejected) | flatten by hand **before 16:45 ET (15:45 CT)** — Lucid liquidates at 16:45 (LR §5) and that is an incident to write up |
| you pressed a kill switch | **L9** | after the dust settles: §5 order; un-block / re-create only after you know why |
| position closed with `SOFT_STOP` tag, or trading stopped mid-day with no payload | **L10**: soft daily loss (payload) or a built-in `strategy.risk` halt (no payload) | nothing until 18:00 ET (17:00 CT); if the built-in fired (no SOFT_STOP row) tell Fable — it means SOFT_STOP did not fire first |
| email from Lucid about HFT / microscalping / hedging | L4-level incident | Block Signals + pause the alert; keep the email; Fable reviews hold times (I5/I6) before anything restarts |

Write every incident in `docs/v6/INCIDENTS.md` (date, ET/CT, what, which row, what you did). Fable reads it.

## 9. What NOT to do

- **Do not trade the bot's accounts by hand while the alert is live** (OA-6). The relay syncs against the
  broker position: your manual trade looks like a desync and gets flattened; your manual position has no
  bracket the relay knows about. If you must: Block Signals + pause first.
- **Do not add a second chart, a second alert, or a second strategy on an overlapping account.** Charts share
  no state; CrossTrade's cross-account opposing-position protection is NT8-only (PF B5). This is how hedging
  happens and how the Topstep account died.
- **Do not edit payloads by hand**, never type an alert message yourself, never put the webhook URL in the message.
- **Do not use `cycle_accounts`**, the Trade Copier Demo→Live, or two sizing methods on one account (RS §6).
- **Do not change Pine inputs on a live alert** without re-creating the alert (§6).
- **Do not rely on Lucid's 16:45 ET (15:45 CT) liquidation.** Reaching it is an incident.
- **Do not start the Java engine in LIVE**: it refuses with "Topstep removed in V6 — live trading runs on
  TradingView" and `POST /api/control/start?mode=LIVE` returns 410. It is the lab (§10).

## 10. The Java lab (what this repo still does for you)

| task | command | output |
|---|---|---|
| regenerate the oracle signals from the real tape | `./gradlew :trading-engine:test --tests '*GoldenSignalExport*'` (JDK 21) | `parity/java_signals.csv`, `parity/bars_*.csv` |
| parity vs your TradingView export | `tools/run_parity.sh --tv-tz America/Chicago` | `PARITY_REPORT.md` |
| backtest / Monte Carlo on the Lucid profile | `docs/QUICK_START.md` → BACKTEST / SIM (defaults to `LUCID_FLEX_50K_EVAL`) | dashboard "Research mode" |
| rebuild + lint the Pine file | `python3 tools/pine_build.py && python3 tools/pine_lint.py pine/dist/LucidICT_v6.pine` | `pine/dist/LucidICT_v6.pine` |
| check a payload you copied from Pine Logs | `python3 tools/payload_check.py samples.txt --build-id <BUILD_ID>` | PASS/FAIL per field |

## 11. Monthly / recurring

- TradingView Premium and CME real-time data renewals; CrossTrade Pro renewal; the Tradovate link in
  CrossTrade (re-Link Demo if "expired").
- Quarterly roll week (§4 D7): dated contract in `relay instrument` on the Thursday before the 3rd Friday,
  back to empty (chart symbol) after the roll; both changes = re-create the alert.
- After every merged `pine/src` change: §6.
- Keep `docs/LUCID_RULES.md` current: when Lucid changes a rule, Fable re-fetches (Agent 01 procedure).

---
Cold-read status (Agent 08 SUCCESS): see master prompt Appendix A, A-08.
