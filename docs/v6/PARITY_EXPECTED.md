## Expected classifications (pre-filled by Agent 06 before OA-2, from GOLDEN_SIGNALS.md §1 + A-03/A-03v)

These are the outcomes the Pine port is *expected* to produce on the 8 oracle rows **before** any
TradingView export exists. They come from the known, documented differences between the Java SIM and the
Pine strategy — not from tuning (AP-8). When `tools/run_parity.sh` runs, Agent 06 fills the "actual"
column from the tool output above and resolves every row to exactly one root cause:
`MATCH` · `ENTRY_MODE` · `ENTRY-CAP` · `OPEN-POSITION` · `DATA` · `PORT_APPROX` · `BUG`.
`BUG` = reject the owning Opus PR with a failing case (06.3). Nothing else is a failure.

| # | Java ts (ET / CT) | side | entry / stop / T1 | size | expected | why | actual (Agent 06 fills) |
|---|---|---|---|---|---|---|---|
| 1 | 2026-09-23 04:04 / 03:04 | BUY | 31008.00 / 31000.00 / 31038.75 | 5 | **MATCH** (entry may be ENTRY_MODE) | first entry of the CME day (18:00 ET roll); stop/T1 are band-derived → ±2 ticks | **DATA** — no TV trade anywhere on 09-23 (TV-only trades #1 09-15, #2 09-18 show the 5m fallback does trade). TV had NO 1m intrabars before 2026-09-25 12:35 ET (Essential intrabar-history limit; funnel: first 1m candle 09-25 12:35 ET, 2,707 5m bars empty), so 09-21→09-25 noon ran on the 5m fallback: 1m OB PD array (O9), 1m dealing-range closes (B1), 1m sweep closes (L3) all approximated. Not shown to be BUG; undecidable until a run whose 1m intrabars cover 09-23 |
| 2 | 2026-09-23 05:04 / 04:04 | BUY | 31015.50 / 31005.75 / 31038.75 | 5 | MATCH **or OPEN-POSITION** | TV holds row 1 to stop 31000.00 / T1 31038.75 while the Java SIM exited at 04:38 (X5). If TV is still long at 05:04, Pine refuses (`I1 not flat long enough`, pyramiding 0) | **DATA** — same (no 1m intrabars on 09-23) |
| 3 | 2026-09-23 05:16 / 04:16 | BUY | 31014.00 / 31005.75 / 31038.75 | 5 | MATCH **or OPEN-POSITION** | same mechanism vs row 2 (Java SIM exit 05:10) | **DATA** — same |
| 4 | 2026-09-23 06:37 / 05:37 | BUY | 31019.00 / 31005.75 / 31038.75 | 5 | **ENTRY-CAP** (Java-only) | 4th entry of the day; I5 `maxEntriesPerSession = 3` (sacred) refuses it — pinned by `test_entry_cap_mismatch_is_exactly_the_09_23_fourth_signal`. **Exception:** if rows 2/3 were OPEN-POSITION-refused, the day count is < 3 and Pine MAY take this one (then it is a MATCH, and rows 2/3 carry the explanation) | **DATA** — same; the ENTRY-CAP expectation could not be exercised (0 entries counted that day) |
| 5 | 2026-09-25 11:33 / 10:33 | SELL | 30847.50 / 30889.75 / 30805.25 | 2 | **MATCH** (G2) | RR(T1) is 1.00 at the Java reference; Pine evaluates RR vs `sigEntryRef` (not the fill) so WHETHER to trade agrees; price delta → ENTRY_MODE at most | **DATA** (5m-fallback timing) — TV #3 took the SAME setup at 11:55 ET, +5 bars: stop 30890.00 (+1t), T1 30805.25 (+0t), ref 30847.75 (+1t), size 2 (exact), fill 30857.00 (+38t better, ENTRY_MODE). 11:55 ET is still before the first 1m intrabar (12:35 ET), so the alarm was found on 5m bars instead of the 11:33 1m candle. WHETHER + levels agree; only WHEN differs. Tool: `Same setup, different time` row; G2 FAIL (no match) by the ±1-bar rule |
| 6 | 2026-09-27 20:40 / 19:40 | BUY | 30813.00 / 30801.75 / 30840.00 | 5 | **MATCH** | Sunday 20:40 ET = ASIA, first entry after the weekend reopen (18:00 ET); check TV loads the Sunday session bars (DATA if the 1m intrabars are missing) | **PORT_APPROX (provisional — the one UNRESOLVED row)** — 1m intrabars WERE present (after 09-25 12:35 ET) and TV took nothing between 09-25 12:00 and 09-28 12:30 ET. Candidates: POST_SWEEP branch (O6, PORT_APPROX), dealing-range carry across the Fri 17:00 → Sun 18:00 reopen (B1 `carryAcrossReopen`), raid score 6 → 5 under L5 (still ≥ floor 5), Sunday-session feed differences. Needs OA-3 bars (drift) + Java replayed on TV's own bars (see verdict). Not shown to be BUG; cannot be MATCH |
| 7 | 2026-09-28 12:32 / 11:32 | SELL | 30645.00 / 30723.00 / 30558.00 | 1 | **MATCH, ENTRY_MODE** | Java limit 30645.00 vs the 12:30–12:34 5m close 30655.00 = +40 ticks (better for the short) — far outside ±4, documented in GOLDEN_SIGNALS §1; stop/T1 unchanged | **MATCH, ENTRY_MODE** — TV #4 on the 12:30 ET bar (+0 bars): stop +0t, T1 +0t, size 1/1, entry −38t (TV fill 30654.50 vs tape 5m close 30655.00 = 2t of feed drift, OA-3 quantifies) |
| 8 | 2026-09-28 14:53 / 13:53 | SELL | 30636.00 / 30674.00 / 30558.00 | 3 | MATCH (G1) **or OPEN-POSITION** | row 7's TV short (stop 30723, T1 30558) is still open at 14:53 unless T1 printed first (it did not: G1 enters at 30636 > 30558) → Pine most likely refuses G1 as not flat. Then the tool prints `G1: FAIL (no TradingView trade matched)` and Agent 06 records **G1 = OPEN-POSITION, thesis carried by TV trade #(row 7)**: that trade MUST still exit at T1 30558.00 (same T1 as G1) for the thesis to count. Whether that satisfies Section 5 "G1 within tolerance" is a Fable judgement to write down here, never a tool change | **OPEN-POSITION** exactly as predicted — TV #4 (row 7) was still short at 14:53 ET (exit 15:35 ET at T1 30558.00 = G1's T1 → thesis carried). TV then re-armed the SAME band after the 18:00 roll: #5 18:10 ET, stop 30674.00 (+0t), T1 30558.00 (+0t), ref 30620.75 (OB inside [30605.5;30673.0]), size 1 — outside the oracle tape (ends 16:22 ET) so not a mismatch. G1 = OPEN-POSITION / thesis PASS (levels within tolerance, entry not comparable under I1) |

### How the ≥ 90 % target is read (Section 5 / target end-state (b))

8 oracle rows → a single unexplained miss is 87.5 %. The target is therefore evaluated on the
**adjusted denominator**: `matched / (8 − rows classified ENTRY-CAP or OPEN-POSITION)`, with every
excluded row listed above and its mechanism named. The exclusions are design consequences of sacred
invariants (I1, I5), not tolerance. Anything classified `BUG`, and any entry in the "Hard fails" section
above, fails the gate regardless of the rate.

### Hard-fail checklist (06.4 — automated by `pine_parity.py` + Agent 06 (Opus) manual pass over all 22 TV trades, 09-15 → 10-29)

- [x] opposite-direction disagreement on a matched setup (`OPPOSITE:`) — none (0 on the 8 oracle rows; every TV trade's side agrees with its own `ref`/stop/T1 geometry)
- [x] a trade without a stop (`NO STOP:`, `--require-stop` on) — none: 22/22 carry `sl=` and `tp=`
- [x] an entry in [16:30, 18:00) ET / [15:30, 17:00) CT (`LATE ENTRY:`) — none. Tool fix in this run: the window now ends at the 18:00 ET roll (it previously flagged every overnight entry, e.g. #5 18:10 ET, #8 20:50 ET, #10 18:40 ET, #13 23:05 ET — those are the next CME day and legal in both Java (M3 15:45–18:00) and Pine (`hardNoEntry` [16:30,18:00)). Also none in Java's wider 15:45–18:00 ET window
- [x] a size above the cap (`SIZE ABOVE CAP:`; cap 5) — none; max 5 (6 trades at 5)
- [x] a decisive stop/T1 delta > ±2 ticks on a matched row (`LEVELS:`) — none (row 7: +0/+0; same-setup rows 5/8: ≤ 1t)
- [x] opposite-direction overlap / reversal without a flat bar — none: 22 trades strictly sequential (each entry ≥ 60 min after the previous exit except #15→#16 at 25 min, same side); no overnight hold past the next flatten
- [x] hold ≤ 30 s — none: minimum hold = 1 × 5m bar (8 trades; 7 of them stopped on the bar after entry — the MARKET_ON_CONFIRM "chase" signature, see §Funnel)

**I4 reproduction (Agent 06 Opus, all 22 TV trades; pinned by `test_pine_strategy.RealTvSizingTest`):** every TV size equals
`min(5, floor(min(250, 600 + dayPnl, (equity − 48,000) − 200) / (max(|ref − stop|, |fill − stop|) × $0.50)))` — 22/22 exact
(e.g. #4 $156/micro → 1; #12 budget $154.50 after −$235.50 the same day → 1; #19 cum −$1,767.50 → MLL room $232.50 − $200 = $32.50 → 1 at $16.50/micro;
#21 budget $62.50 at $26.50/micro → 2). The sizing port (80_strategy `sizeContracts`) is PORT_EXACT on real TV fills.

### Hold-time (I6) — actual

| bucket | ≤5s | ≤30s | ≤1m | ≤5m | ≤15m | ≤60m | >60m |
|---|---|---|---|---|---|---|---|
| trades | 0 | 0 | 0 | 8 | 3 | 7 | 4 |

Closed trades 22 · share of gross profit from trades held ≤ 5 s **0.0 %** (limit 50 %) · min hold 5 min · I6 clean.

### Strategy-layer funnel (owner's TV run, 10,764 5m bars 09-13 → 11-05): emitted 80 → `strategy.entry` 22 — root causes

| reject | count | root cause | verdict |
|---|---|---|---|
| `SIZE 0` | 33 | `budget = min(riskPerTrade$ 250, softDailyLoss 600 + dayPnl, MLL room − drawdownBuffer$ 200)`. The TV run is ONE account lifetime from 09-13: cum P&L reached −$1,540 (10-08) / −$1,767.50 (10-12), so MLL room (equity − 48,000) − 200 fell to $260 → $32.50 → $24.50 and only setups ≤ $32/micro could size ≥ 1 (#19–#21 did; #22 at $113/micro sized 1 on a $127.50 budget). Second (ENTRY_MODE) component: Pine sizes on `max(|ref − stop|, |fill − stop|)` and the 5m-close fill was WORSE than `ref` on 17/22 real trades (e.g. #8 14.75 → 44 pts, #22 23 → 56.5 pts), so a chased fill can drop the size to 0 where Java (limit at `ref`) would size ≥ 1. The 33 individual budgets/distances are not in the export (labels only), so the split between the two components is inferred, not counted; the 16-day gap 10-12 → 10-28 with budgets $32.50/$24.50 shows the MLL component dominates late in the run. Java has the same `budget = min($250, DLL room, MLL room)` and denies size < 1 (PORT_MAP S5/S6); the $200 buffer is V6 policy (A-03v), the only delta. Oracle window unaffected (budget $250 for rows 1–7, cum ≤ −$598) | **DESIGN** (Lucid MLL emulation working as specified; no AP-8 tuning). Owner note: on this tape the Flex 50K sits $225 above its MLL by 10-12 — L4 territory; a halt when `MLL room − buffer < minimum useful budget` is a V7 policy question, not parity |
| `BRACKET wrong side of fill (short)` / `(long)` | 9 / 9 | Pine detects the alarm on a 1m intrabar (`sigTs1m`) but fills at the 5m close (`MARKET_ON_CONFIRM`, `process_orders_on_close`). By that close price has either run through T1 (continuation) or back through the stop (failed reaction), so `bracketReason` refuses — correctly: a market fill there would have the bracket on the wrong side. Java would instead rest a LIMIT at the PD array (S1) that fills on a later retrace or expires (T7, 90 min) — a trade Pine never takes. Checked on the tape for the 8 oracle rows: the 5m close containing each alarm lies strictly inside (stop, T1) for all 8 (`bracketOK` ✓), so no oracle row is lost to this | **DESIGN consequence of ENTRY_MODE** (GOLDEN_SIGNALS §1). V7 fix = `BROKER_LIMIT`: rest the limit at `sigEntryRef` at the broker via the relay REST path with the bracket attached, cancel on invalidation / 90-min TTL. Harness ask to Opus (03): print `fill / stop / T1` in the NO TRADE label + funnel so the split continuation-vs-failed-reaction is visible |
| `I5 maxEntriesPerSession` | 5 | 4th+ emission in a CME day | DESIGN (sacred I5) |
| `I3 no-entry` | 1 | emission at/after 15:45 ET | DESIGN (sacred I3) |
| invalidation `signal not executed (released)` | 68 | IN_TRADE latch release when no position is seen on the bar after the emit bar (70_bias_smt:955-963). The emit-bar fill happens AFTER that bar's script run (`process_orders_on_close`), so `positionSeen` is first checked on the next bar; a trade stopped/targeted inside that next bar (8 of the 22 real trades held exactly one 5m bar) is labelled "released". Arithmetic: 57 listed rejects + 8 one-bar trades = 65 of 68; 80 emitted − 22 entered − 57 listed rejects = 1 emission with no listed reason; ≈ 2–3 releases stay unattributed without the per-bar label log (OA-3-independent; a debug export would settle it). Both paths impose `i_rearmCooldown` (70:422-426), so trading behaviour is identical | **diagnostic nit** (label only, not a BUG): Opus 02/03 may tag via `strategy.closedtrades` change. No failing case written |

Pine reproduces Java's WHETHER on every decidable row; the three mechanisms above are the documented MARKET_ON_CONFIRM and account-envelope consequences. Nothing in the 22 trades or the funnel points to a port BUG. Nothing was tuned to the tape (AP-8).

### C-14 (strategy.risk session reset) — from OA-2 step "C-14 check"

Owner's note: no SOFT_STOP / built-in halt fired (worst day 10-01: −$377.50 < $600); C-14 untested on this tape (not a blocker — the Pine soft stop is the first layer, the relay the second).

### Agent 06 verdict — OPUS-VERIFIED (Fable re-check pending) (2026-11-05, OA-2 run, dist `f78cfef464511f7c`, TradingView Essential)

Run on Opus with owner approval (Fable credits exhausted). Opus wrote the code under test, so every claim below was re-derived from the raw files: sizes 22/22 recomputed, the 8 oracle 5m closes checked against the bracket on `parity/bars_mnq_1m.csv` (all inside (stop, T1)), the release path read in 70_bias_smt.pine:955-963 and the reject order in 80_strategy.pine:175-199 (bracket is checked BEFORE size, so SIZE 0 counts only bracket-valid emissions).

- Data drift (06.1): **⛔ OA-3 pending** (no `parity/tv_bars_*.csv`). Indirect: TV fill 30654.50 vs tape 5m close 30655.00 on the one matched row = 2 ticks.
- Match rate: raw **1/8 = 12.5 %**; adjusted per the pre-agreed rule (− OPEN-POSITION row 8) **1/7 = 14.3 %**; on the rows TradingView could actually decide (1m intrabars present → rows 6, 7) **1/2 = 50 %**. Rows 1–5 are **DATA** (Essential had no 1m intrabars before 09-25 12:35 ET; rows 5/7/8 prove the levels/size/side agree to ≤ 1 tick where the setup was seen at all). **Target (b) NOT met — inconclusive, not failing.**
- Opposite: **0** · Hard fails: **0** (tool) + **0** (manual pass over all 22 trades, 09-15 → 10-29) · I6: 0 / 0 buckets, 0.0 % fast profit.
- G1 / G2 / G3: **OPEN-POSITION (thesis PASS: TV #4 exited at G1's T1 30558.00; the same band re-armed as #5 with stop/T1 +0t)** / **DATA (same setup +5 bars, stop +1t T1 +0t size exact)** / **PASS**.
- BUG rows → PR rejected: **none**. Funnel: SIZE 0 ×33 and BRACKET ×18 are DESIGN (table above); "released" ×68 is a diagnostic label nit.
- Ledger 06: **🔄** (not ✅: (b) unmet on a 2-row decidable denominator; row 6 unresolved). Exact next step, in order:
  1. **OA-3 as issued** (1m MNQ1!/MES1! export 09-21 → 09-28) → `tools/run_parity.sh` → drift verdict.
  2. **OA-3 extended (recommended, no plan upgrade):** the same "Export chart data" click for 1m MNQ1! + MES1! **2026-09-25 → 2026-10-29** (the window where TV HAD 1m intrabars and took 20 trades). Agent 06 converts to the tape JSON, reruns `GoldenSignalExport` (JDK 21) on TradingView's own bars, and diffs the Java oracle against TV trades #3–#22 — ~20 rows instead of 2, on identical data. If Java signals on 09-27 20:40-type setups and Pine does not on the same bars, THAT is the BUG case (routed to Opus 02/03 with the bar range); if Java agrees, row 6 closes as DATA.
  3. TradingView **Premium is NOT required**: intrabar depth is a plan history limit, and Premium's longer 1m history MAY reach 09-23 but is not guaranteed; step 2 removes the dependency entirely. Deep-backtesting a custom range does not add intrabars.
- OPUS-VERIFIED (Fable re-check pending) dist BUILD_ID handed to Agent 07: `f78cfef464511f7c` — **safety-verified** (I2/I3/I4/I5/I6 hold on 22 real TV trades, zero hard fails; drills may proceed per the Agent 06 FAILURE clause); **parity 🔄** until step 2.
