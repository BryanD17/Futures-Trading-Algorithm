# DRILL_SCRIPT.md — Agent 07 relay + demo safety drills D1–D8 (owner clicks, Fable verifies)

Written by Fable Agent 07, 2026-11-05. Status: ⛔ **OWNER-ACTION OA-5** (needs the owner at the keyboard).
Companions: `docs/v6/RELAY_SETUP.md` (payload spec §4, lifecycle table §5, account controls §7),
`docs/PLATFORM_FACTS.md` (every relay/TradingView fact, cited as PF:§), `docs/v6/DRILL_REPORT.md`
(the template you fill), `TRADINGVIEW_MIGRATION_MASTER_PROMPT_V6.txt` Appendix D (OA-5 setup).
Every time below is **ET (CT)**. CT = ET − 1 h.

## 0. Ground rules

1. **Order of accounts.** All eight drills run first on a **Tradovate practice (sim) account that is
   NOT a Lucid account** — CrossTrade links Tradovate's own sim accounts through the same *Link Demo*
   button (PF:B1), and *"The Demo label alone does not make an account disposable"*: a Lucid eval on
   Demo counts against Lucid's limits (PF:B1). Only after D1–D8 PASS on the practice account do D1,
   D2 and D4 run **once** on **one** Lucid eval at **1 micro** (§10).
2. **When.** A weekday, during RTH **09:30–15:30 ET (08:30–14:30 CT)**, so Tradovate fills instantly
   and no drill straddles the 16:30 ET (15:30 CT) no-entry / 16:40 ET (15:40 CT) flatten / 16:45 ET
   (15:45 CT) Lucid forced-flat window. Never on roll week (RELAY_SETUP R-7).
3. **The test tool.** TradingView fires an order-fill alert only when a real setup fills, which can
   take days. The drills therefore send the **exact payload Pine would send** straight to the
   CrossTrade webhook URL with `curl` — the webhook is an ordinary HTTPS POST of `text/plain`
   (PF:A4), and CrossTrade's semicolon format is what `tools/payload_check.py --sample` prints
   (RELAY_SETUP §4). This exercises everything downstream of TradingView (relay auth, sync gate,
   OSO, Alert History, account controls). D8 and the self-test cover the TradingView side.
   If CrossTrade's My Account page offers its own "send a test alert" button, you may use it instead
   of curl for the same payloads (not documented in PLATFORM_FACTS → CONSERVATIVE-UNVERIFIED; curl
   is the documented path).
4. **Secrets.** The payload files contain your real key and account name. Keep them in
   `~/drills/` (outside the repo). Never paste the key, the webhook URL, or an unmasked payload into
   the PR, chat, or screenshots (R5; `.githooks/pre-commit` blocks the repo side). Screenshots of
   CrossTrade Alert History are fine **after** the key column is cropped or blurred.
5. **Evidence per drill** = (a) the Tradovate screenshot(s) named in the drill, (b) the CrossTrade
   Alert History row(s) — My Account → Alert History (PF:B8), (c) the time you clicked, ET and CT.
   Put them in `docs/v6/DRILL_REPORT.md` under the drill's heading. Fable marks PASS/FAIL; never
   "PASS with caveat" (Agent 07 FAILURE clause).
6. **Stop conditions.** Any unexpected position, any order without a visible stop, any opposite
   position anywhere → **stop drilling**, flatten in Tradovate, Block Signals in CrossTrade, write
   what you saw. That is itself a FAIL result routed to the owning agent.

## 1. One-time preparation (≈ 20 min; OA-5 steps 1–4 in Appendix D)

| step | action | check |
|---|---|---|
| P1 | Tradovate: make sure you have a **practice (sim) account** on the same Tradovate login, or create one from Tradovate's Application Settings. Note its exact name (e.g. `DEMO12345`). | name noted |
| P2 | crosstrade.io → My Account → Brokers → Tradovate → **Link Demo** → sign in with that Tradovate login (PF:B1). Confirm the practice account AND the Lucid account(s) are listed with distinct names. | both listed |
| P3 | My Account → copy the **secret key** and the **webhook URL** into `~/drills/secret.txt` only. | file outside repo |
| P4 | CrossTrade Pro → Account Manager → the **practice** account: monitoring ON, session loss **$600**, trailing drawdown **$1,800**, trading window **18:00–16:30 ET (17:00–15:30 CT)** Sun–Fri, auto-flatten **16:42 ET (15:42 CT)**, tripped → block signals + close positions (RELAY_SETUP §7). Leave **Block Signals OFF** for now. Screenshot the panel. | screenshot P4 |
| P5 | In the repo: `python3 tools/pine_build.py && sed -n 2p pine/dist/LucidICT_v6.pine` → note the 16-hex `src-sha256` = **BUILD_ID**. Then `python3 tools/payload_check.py --sample --build-id <BUILD_ID> > ~/drills/samples_masked.txt`. This prints the four reference payloads (ENTRY SELL 3 / BRACKET / FLATTEN / SOFT_STOP) with placeholders. | file created |
| P6 | Make the drill payload files in `~/drills/` by copying from `samples_masked.txt` and editing **only**: `key=` → your key; `account=` → the practice account name; `qty=` → `1`; `target_quantity=` → `1`; `take_profit=` / `stop_loss=` → prices relative to the live MNQ quote (see each drill). Files: `entry_sell.txt`, `entry_buy.txt`, `flatten.txt`, `bracket.txt`. Keep every other line exactly as printed (field order matters for nothing, content does). | 4 files |
| P7 | Verify each file before use (secret is never printed): `python3 tools/payload_check.py ~/drills/entry_sell.txt --build-id <BUILD_ID> --cap 5 --live` → PASS. `--live` makes a leftover placeholder an error. | PASS ×4 |
| P8 | The send command (used in every drill; `$URL` from secret.txt): `curl -sS -X POST "$URL" -H 'Content-Type: text/plain' --data-binary @"$HOME/drills/<file>"` — then open CrossTrade Alert History and Tradovate side by side. | — |
| P9 | TradingView: chart MNQ1! 5 m, LucidICT_v6 on it, Settings → Relay: `relay = CROSSTRADE`, secret and accounts filled in, **tradingEnabled OFF** (dry run), `log masked payloads` ON. Create the ONE alert exactly per RELAY_SETUP §2 (condition "Order fills and alert() function calls", message `{{strategy.order.alert_message}}`, webhook URL, Open-ended, name `LucidICT_v6 <BUILD_ID>`). | alert exists, DRY RUN |

> Prices in the drills: a "safe" stop/target pair for a SELL at quote **Q** is `stop_loss = Q + 20.00`,
> `take_profit = Q − 20.00` (80 ticks each side — well above the 8-tick I6 floors, far enough not to be
> hit during the drill). For a BUY swap the signs. Use the current MNQ quote from Tradovate's DOM at
> the moment you send; absolute prices are what Pine sends (PF:B3).

## 2. D1 — single entry through the relay → order at the broker, correct qty

| | |
|---|---|
| Setup | Practice account flat, no working orders (Tradovate → Positions / Orders empty). Block Signals OFF. `entry_sell.txt` with `qty=1`, `target_quantity=1`, `prev_market_position=flat`, `market_position=short`, prices per the rule above. |
| Action | P8 with `entry_sell.txt`. Note the time (ET/CT). |
| Expected — relay | Alert History: one new row, green/accepted, `command=PLACE`, `qty 1`, `strategy_tag=LucidICT_v6-<BUILD_ID>-ENTRY` visible in the payload column (PF:B8). |
| Expected — broker | Tradovate Positions: **short 1 MNQ** on the practice account within seconds. Orders: the entry shows *Filled*. |
| Expected — TV | nothing (curl bypassed TradingView; the DRY-RUN alert sent nothing). |
| Evidence | S1 Tradovate Positions panel showing −1 MNQ; S2 the Alert History row (key cropped). |
| PASS | position −1 (not −2, not 0), one Alert History row, qty 1. **FAIL** if qty ≠ 1, two rows, or any row on the Lucid account. |

## 3. D2 — bracket present at the broker immediately after the fill (I2)

| | |
|---|---|
| Setup | Immediately after D1 (still short 1). Do nothing else. |
| Action | Open Tradovate → **Orders** (working orders) within 10 s of the D1 fill. Then open the order's detail / the position's bracket view. |
| Expected — broker | Two **working** orders linked to the position: a **Stop** at exactly your `stop_loss` price and a **Limit** at exactly your `take_profit`, both **GTC**, both for 1 — the Tradovate-native OSO CrossTrade attached (PF:B3: *"activated by Tradovate on the entry's first fill, sized to that fill"*; *"sent GTC whatever tif the entry carries"*). Prices equal the payload's absolute values to the tick (PF:B3: *"fixed when the order is submitted"*). |
| Expected — relay | the same D1 row; no extra rows. |
| Then (part 2, the BRACKET message) | P8 with `bracket.txt` (`prev_market_position=short`, `market_position=flat`, `target_quantity=0`, **no** action/qty/order_type/tif — exactly as `--sample` prints it). |
| Expected — relay (part 2) | Alert History: a **rejected** row. Reason text is either the exit block (*prev_market_position is not flat*) or the missing foundation (*missing action / qty / order_type*) — **both are the designed rejection** (RELAY_SETUP §3, R-3). No order is sent. |
| Expected — broker (part 2) | Position still −1; both OSO legs still working, untouched. |
| Evidence | S3 Tradovate Orders panel showing Stop + Limit legs (prices, GTC, qty 1) within 10 s of the fill; S4 the rejected BRACKET row with its reason. |
| PASS | both legs present at submission prices, GTC, qty 1; BRACKET row rejected; position unchanged. **FAIL** if either leg is missing, a leg is DAY, a price differs, or the BRACKET message changed the position or the legs. |

## 4. D3 — desync: the broker is not where the strategy thinks → relay flattens, never reverses

| | |
|---|---|
| Setup | Still short 1 with the OSO from D2. |
| Action 1 | In Tradovate, **manually close** the position (Positions → Flatten / close). Confirm flat and that Tradovate cancelled the OSO legs (Orders empty). Time it. |
| Action 2 (the realistic desync) | Manually **buy 1 MNQ** in Tradovate so the practice account is **long 1 with no stop** (a state the strategy knows nothing about). Then P8 with `entry_sell.txt` again (it still says `prev_market_position=flat`). |
| Expected — relay | Alert History row: **out of sync** (live position long vs `prev_market_position=flat`), action = `out_of_sync=flatten` → the SELL is **withheld** and the instrument is **flattened** (PF:B4: *"withholds order and flattens the instrument if not flat"*). No reversal, no short. |
| Expected — broker | Position goes from +1 to **flat**. No new order filled. |
| Expected — TV | unaffected. |
| Evidence | S5 Tradovate Positions showing flat after action 2 with the fill history (+1 manual, −1 relay flatten); S6 the out-of-sync Alert History row. |
| PASS | the relay flattened and placed nothing; at no instant was the account short. **FAIL** if the SELL was placed (would be a reversal: AP-10 / I1), or if the account stayed long with the row "accepted". |

## 5. D4 — flat-by: the FLATTEN message closes the position AND cancels the OSO legs

| | |
|---|---|
| Setup | Flat. P8 with `entry_buy.txt` (BUY 1, `market_position=long`, stop = Q − 20, target = Q + 20). Confirm +1 and the two OSO legs (as D2). |
| Action | P8 with `flatten.txt` (`command=FLATTEN; account=<practice>; instrument=MNQ1!; strategy_tag=...-FLATTEN; destination=tradovate;` — no sync fields, exactly as `--sample` prints). Time it. |
| Expected — relay | Alert History row accepted, `FLATTEN`. If the row carries a warning "flatten not confirmed within ~6 s" it still counts only if the broker is in fact flat (PF:B8). |
| Expected — broker | Position **flat** and **Orders empty** — the stop and the limit leg are both cancelled (PF:§B11: a flatten with account + instrument filters *"cancels the working orders that go with them"*; RELAY_SETUP R-2). |
| Ordering note (RELAY_SETUP L3b / R-4) | **flatten_first vs the sync gate.** The ENTRY payload carries `flatten_first=true` *and* `sync_strategy=true`; which runs first is not documented (PF:§B11). D7 observes it. FLATTEN itself is not sync-gated, so this drill is independent of that ordering. |
| Live timing check (optional, same day) | Leave the TradingView alert active in DRY RUN and watch the Alert log at **16:40 ET (15:40 CT)**: if the strategy held a simulated position, the FLATTEN fill alert appears there with `key=<DRY_RUN>` — proving the 16:35–16:40 bar close triggers it (I3). Not required for PASS. |
| Backstop check (optional, once) | On a day you can watch: open a manual +1 on the practice account at 16:41 ET (15:41 CT) and confirm the Account Manager auto-flatten closes it in the window after **16:42 ET (15:42 CT)** (PF:B6 *"runs in the 5 minutes after the time"*). |
| Evidence | S7 Positions flat + Orders empty right after FLATTEN; S8 the FLATTEN row. |
| PASS | flat, zero working orders, one accepted FLATTEN row. **FAIL** if either OSO leg is still working after the flatten (an orphaned GTC leg can OPEN a position later: RELAY_SETUP §3). |

## 6. D5 — kill switch: relay Block Signals + TradingView alert stop → no orders

| | |
|---|---|
| Setup | Flat. |
| Action 1 | CrossTrade → Account Manager → practice account → **Block Signals ON** (PF:B6 *"apply immediately even with P/L monitoring off"*). Then P8 with `entry_buy.txt`. |
| Expected 1 | Alert History row **blocked** for that account; Tradovate: nothing. |
| Action 2 | Block Signals OFF. In TradingView → Alerts → the LucidICT_v6 alert → **Pause** (or the stop toggle). Wait for a 5 m bar close. The strategy keeps simulating; no webhook is sent. |
| Expected 2 | TradingView Alert log shows no new entry for the alert after the pause; Alert History shows no new row. |
| Action 3 | Un-pause the alert (its snapshot is unchanged, so this is safe) — or, if you deleted it, re-create per RELAY_SETUP §2 (D8). |
| Evidence | S9 the blocked row; S10 TradingView Alerts panel with the alert paused + the empty Alert log window. |
| PASS | both actions produced zero orders. Also record the **third** switch for the runbook: Tradovate's own Flatten button works regardless of the relay (PF:B6: manual trades cannot be blocked). |

## 7. D6 — I1 drill: exactly one strategy alert; an opposite signal while in a position is blocked

| | |
|---|---|
| Setup — part 1 | TradingView → Alerts panel. |
| Expected 1 | **Exactly one** alert whose condition names `LucidICT_v6`, and no other alert (any script) whose webhook is the CrossTrade URL. Any second alert = delete it now. Screenshot the Alerts list (S11). |
| Setup — part 2 | Practice account: P8 `entry_buy.txt` → long 1 with OSO (verify as D2). |
| Action 2 | P8 `entry_sell.txt` — an *opposite* entry, `prev_market_position=flat`, while the broker is long. |
| Expected 2 — relay | out of sync (live long ≠ prev flat) → `out_of_sync=flatten`: SELL **withheld**, position **flattened** (same mechanism as D3). At no time short. |
| Expected 2 — broker | long 1 → flat; Orders empty (legs cancelled by the flatten). |
| Pine side (same invariant, proven separately) | OA-2's **SELF-TEST PASS** label (Agent 03 §03.8) covers the strategy-side I1 cases (pyramiding 0, `flatOk`, no opposite entry until flat for a full bar). Paste that screenshot here too (it comes from OA-2 step 5). |
| Evidence | S11 Alerts list; S12 Positions flat + fill history after action 2; S13 the out-of-sync row. |
| PASS | one alert; the opposite signal never created a short. **FAIL** if the account was ever short, or a reversal order appears in Tradovate's order history. |

## 8. D7 — duplicate alert / rejected order → no double position (records the flatten_first ordering)

| | |
|---|---|
| Setup | Flat, Orders empty. |
| Action 1 (duplicate) | P8 `entry_buy.txt` **twice within ~5 s** (same file both times). |
| Expected — relay | Row 1 accepted → long 1 + OSO. Row 2 is one of two documented outcomes (RELAY_SETUP L5, PF:§B11 — the ordering of `flatten_first` vs the sync gate is undocumented): **(a)** `flatten_first` runs first → position + legs cleared, then the sync gate sees flat = flat → the BUY is **re-placed once**: final state long **1** with a fresh OSO at the same prices; or **(b)** the sync gate runs first → live long vs prev flat → out of sync → **withhold + flatten**: final state **flat**. |
| Expected — broker | final position is **1 or 0 — never 2**, and if 1, exactly two working legs (never four). |
| Record | Write which outcome, (a) or (b), in DRILL_REPORT — the runbook's L5 row and RELAY_SETUP R-4 are updated from it. |
| Action 2 (rejected order) | Flatten first (P8 `flatten.txt`, verify flat). Make `entry_reject.txt` = `entry_buy.txt` with `qty=1000` and `target_quantity=1000`. P8 it. |
| Expected — relay | Alert History row turns **yellow** with Tradovate's rejection reason (margin / size) (PF:B8). |
| Expected — broker | nothing placed; flat; no partial position. If Tradovate partially filled (unlikely on sim): CrossTrade's Position Protection cancels the remainder (PF:B3); the position MUST carry its stop — screenshot it, flatten manually, and report. |
| Then | P8 `flatten.txt` again → flat and Orders empty (closes the drill). |
| Evidence | S14 Positions after the duplicate (1 or 0) + Orders (2 or 0 legs); S15 both duplicate rows; S16 the yellow rejected row. |
| PASS | never 2 contracts, never 4 legs; the rejected order left the account flat. |

## 9. D8 — alert re-creation after a script edit → the new version is the one that fires

| | |
|---|---|
| Why | An alert is a snapshot of the script and its inputs at creation (PF:A5). After any edit the OLD alert keeps firing the OLD code until it is deleted. |
| Setup | Fable merges any `pine/src` change (even a comment) so `pine/dist/LucidICT_v6.pine` line 2 shows a **new** 16-hex `src-sha256` (= `BUILD_ID`, carried in every payload's `strategy_tag`, RELAY_SETUP §4). Record OLD and NEW ids. |
| Action | 1. Pine Editor → open `LucidICT_v6` → select all → paste the NEW dist → Save → (the chart updates). 2. Alerts → **delete** the old `LucidICT_v6 <OLD>` alert. 3. Create the ONE alert again per RELAY_SETUP §2, named `LucidICT_v6 <NEW>`. 4. Settings → Relay → `log masked payloads` ON; wait for any order-fill alert (DRY RUN) **or** read the status line / Pine Logs on the next bar. |
| Expected — TV | Alerts list: exactly one alert, name carries NEW. Pine Logs / Alert log Message: `strategy_tag=LucidICT_v6-<NEW>-…`. The Alert log for the OLD alert shows no entries after its deletion time. |
| Expected — relay | the next real payload's row shows `<NEW>` in `strategy_tag` (ignored by Tradovate, visible in Alert History, PF:B2/B8). With DRY RUN it is rejected (`key=<DRY_RUN>`), which is fine — the tag is still readable. |
| Evidence | S17 Alerts list (one alert, NEW in the name); S18 a Pine Logs line or Alert-log Message containing `LucidICT_v6-<NEW>-`; the two ids written in DRILL_REPORT. |
| PASS | only the NEW-tagged alert exists and its payloads carry NEW. **FAIL** if two alerts exist or a payload still carries OLD. |

## 10. Lucid eval pass — D1, D2, D4 at 1 micro (only after §2–§9 are all PASS)

| | |
|---|---|
| Pre-conditions | DRILL_REPORT D1–D8 PASS stamped by Fable; OA-4 (hedging confirmation) and OA-7 answered or explicitly waived by the owner; Account Manager for the **Lucid** account set per RELAY_SETUP §7 (screenshot). The TradingView alert stays **DRY RUN** throughout — nothing but your curl touches the eval. |
| Lucid-rule note | These three trades are **manual-equivalent, same-direction, single-account, 1 micro, with a stop at submission** — permitted activity (LUCID_RULES §1/§4). OA-6 (no manual trading on bot accounts) applies once the alert is LIVE, which it is not yet. Each costs one round-trip commission on the eval (LUCID_RULES §14). |
| D1-L | `entry_buy_lucid.txt` = `entry_buy.txt` with `account=<LUCID_ACCOUNT>` and `qty=1`. P8 it. Expected: +1 MNQ **micro** on the Lucid account only; the practice account untouched. Evidence S19. |
| D2-L | Within 10 s: Tradovate Orders shows Stop + Limit, GTC, qty 1, at the payload prices. Evidence S20. |
| D4-L | `flatten_lucid.txt` (account = the Lucid account). P8 it. Expected: flat, Orders empty. Evidence S21 + the Alert History rows (S22). |
| PASS | all three identical to the practice-account results. **FAIL** → stop; no Lucid account is wired to a live alert until the owning agent fixes it. |
| After | Tradovate → verify flat; CrossTrade → Block Signals ON for the Lucid account until go-live day (runbook §5). |

## 11. What Fable does with the results

1. Reads every screenshot/row against the PASS criteria above; stamps D1–D8 and D1-L/D2-L/D4-L in
   `docs/v6/DRILL_REPORT.md` (never "accept with caveat").
2. D7's outcome (a)/(b) → updates RELAY_SETUP §5 L5 + R-4 and the runbook incident row.
3. Any FAIL → opens a failing case against the owning agent (payload → 04; Pine behaviour → 03; relay
   configuration → 01's PLATFORM_FACTS) and the Golden Loop returns there.
4. All PASS → A-07 line in Appendix A, ledger 07 ✅, hand-off to Agent 08 (go-live ladder step 2 ✅).
