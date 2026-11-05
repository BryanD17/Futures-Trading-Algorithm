# DRILL_REPORT.md — Agent 07 evidence (template; the owner fills the evidence, Fable stamps PASS/FAIL)

Status: ⛔ **AWAITING OA-5** (owner drills per `docs/v6/DRILL_SCRIPT.md`). Template written 2026-11-05.
Times in **ET (CT)**. Screenshots go in `docs/v6/drills/` as `S<n>_<drill>.png` (key column cropped; no
webhook URL; no unmasked payload — R5). Alert History rows may be pasted as text with `key=` removed.

## Preparation (DRILL_SCRIPT §1)

| item | value |
|---|---|
| Date of drills | ____-__-__ |
| Tradovate practice account name | `<PRACTICE_ACCOUNT>` (never paste a Lucid account name — use `<LUCID_1>`) |
| Pine BUILD_ID (dist line 2, 16 hex) at drill time | `________________` |
| `payload_check.py --live` on the 4 drill files | PASS / FAIL (paste output; the tool never prints the key) |
| Account Manager (practice) settings screenshot | S-P4 |
| TradingView alert | name `LucidICT_v6 <BUILD_ID>`, Open-ended, DRY RUN (tradingEnabled OFF) — screenshot S-P9 |

## Results

| drill | what | owner time ET (CT) | evidence | Fable verdict |
|---|---|---|---|---|
| D1 | single entry → −1 at broker, qty 1 | __:__ (__:__) | S1, S2 | ⬜ |
| D2 | OSO Stop + Limit present ≤ 10 s, GTC, payload prices; BRACKET message rejected | __:__ (__:__) | S3, S4 | ⬜ |
| D3 | manual long + stale ENTRY → withheld + flattened, never short | __:__ (__:__) | S5, S6 | ⬜ |
| D4 | FLATTEN → flat AND both legs cancelled | __:__ (__:__) | S7, S8 | ⬜ |
| D4 optional | 16:40 ET (15:40 CT) FLATTEN fill alert seen in DRY RUN / 16:42 ET (15:42 CT) auto-flatten backstop | __:__ (__:__) | — | ⬜ |
| D5 | Block Signals → blocked row; alert paused → no webhook; Tradovate Flatten = 3rd switch | __:__ (__:__) | S9, S10 | ⬜ |
| D6 | exactly one alert; opposite ENTRY while long → withheld + flattened; SELF-TEST PASS label | __:__ (__:__) | S11, S12, S13 (+ OA-2 self-test shot) | ⬜ |
| D7 | duplicate ENTRY → final 1 or 0 (never 2); qty 1000 → yellow rejection, flat | __:__ (__:__) | S14, S15, S16 | ⬜ |
| D7 ordering observed | **(a)** flatten_first first → re-placed once · **(b)** sync gate first → withhold + flatten  → circle one: a / b | | | → RELAY_SETUP L5/R-4 updated: ⬜ |
| D8 | OLD `________________` → NEW `________________`; one alert; payload carries NEW | __:__ (__:__) | S17, S18 | ⬜ |

### Lucid eval (DRILL_SCRIPT §10) — only after D1–D8 are all ✅

| drill | what | owner time ET (CT) | evidence | Fable verdict |
|---|---|---|---|---|
| pre | OA-4 reply / OA-7 reply pasted in LUCID_RULES.md (or owner waiver, dated) | | | ⬜ |
| pre | Account Manager (Lucid) settings per RELAY_SETUP §7 | | S-L0 | ⬜ |
| D1-L | +1 **micro** on `<LUCID_1>` only | __:__ (__:__) | S19 | ⬜ |
| D2-L | OSO legs ≤ 10 s, GTC, payload prices | __:__ (__:__) | S20 | ⬜ |
| D4-L | FLATTEN → flat, Orders empty | __:__ (__:__) | S21, S22 | ⬜ |
| post | Block Signals ON for `<LUCID_1>` until go-live day | __:__ (__:__) | | ⬜ |

## Anything unexpected (free text, with times)

_(e.g. a warning on a FLATTEN row, a leg that took > 10 s to appear, a Tradovate message)_

## Fable sign-off (A-07)

- D1..D8: __ PASS / __ FAIL · Lucid D1/D2/D4: __ · D7 ordering: (a)/(b)
- FAILs routed to: _(agent + failing case)_
- Hand-off to Agent 08 (go-live ladder step 2): ⬜
