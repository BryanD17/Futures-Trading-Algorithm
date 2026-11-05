## Expected classifications (pre-filled by Fable Agent 06 before OA-2, from GOLDEN_SIGNALS.md §1 + A-03/A-03v)

These are the outcomes the Pine port is *expected* to produce on the 8 oracle rows **before** any
TradingView export exists. They come from the known, documented differences between the Java SIM and the
Pine strategy — not from tuning (AP-8). When `tools/run_parity.sh` runs, Agent 06 fills the "actual"
column from the tool output above and resolves every row to exactly one root cause:
`MATCH` · `ENTRY_MODE` · `ENTRY-CAP` · `OPEN-POSITION` · `DATA` · `PORT_APPROX` · `BUG`.
`BUG` = reject the owning Opus PR with a failing case (06.3). Nothing else is a failure.

| # | Java ts (ET / CT) | side | entry / stop / T1 | size | expected | why | actual (Agent 06 fills) |
|---|---|---|---|---|---|---|---|
| 1 | 2026-09-23 04:04 / 03:04 | BUY | 31008.00 / 31000.00 / 31038.75 | 5 | **MATCH** (entry may be ENTRY_MODE) | first entry of the CME day (18:00 ET roll); stop/T1 are band-derived → ±2 ticks | |
| 2 | 2026-09-23 05:04 / 04:04 | BUY | 31015.50 / 31005.75 / 31038.75 | 5 | MATCH **or OPEN-POSITION** | TV holds row 1 to stop 31000.00 / T1 31038.75 while the Java SIM exited at 04:38 (X5). If TV is still long at 05:04, Pine refuses (`I1 not flat long enough`, pyramiding 0) | |
| 3 | 2026-09-23 05:16 / 04:16 | BUY | 31014.00 / 31005.75 / 31038.75 | 5 | MATCH **or OPEN-POSITION** | same mechanism vs row 2 (Java SIM exit 05:10) | |
| 4 | 2026-09-23 06:37 / 05:37 | BUY | 31019.00 / 31005.75 / 31038.75 | 5 | **ENTRY-CAP** (Java-only) | 4th entry of the day; I5 `maxEntriesPerSession = 3` (sacred) refuses it — pinned by `test_entry_cap_mismatch_is_exactly_the_09_23_fourth_signal`. **Exception:** if rows 2/3 were OPEN-POSITION-refused, the day count is < 3 and Pine MAY take this one (then it is a MATCH, and rows 2/3 carry the explanation) | |
| 5 | 2026-09-25 11:33 / 10:33 | SELL | 30847.50 / 30889.75 / 30805.25 | 2 | **MATCH** (G2) | RR(T1) is 1.00 at the Java reference; Pine evaluates RR vs `sigEntryRef` (not the fill) so WHETHER to trade agrees; price delta → ENTRY_MODE at most | |
| 6 | 2026-09-27 20:40 / 19:40 | BUY | 30813.00 / 30801.75 / 30840.00 | 5 | **MATCH** | Sunday 20:40 ET = ASIA, first entry after the weekend reopen (18:00 ET); check TV loads the Sunday session bars (DATA if the 1m intrabars are missing) | |
| 7 | 2026-09-28 12:32 / 11:32 | SELL | 30645.00 / 30723.00 / 30558.00 | 1 | **MATCH, ENTRY_MODE** | Java limit 30645.00 vs the 12:30–12:34 5m close 30655.00 = +40 ticks (better for the short) — far outside ±4, documented in GOLDEN_SIGNALS §1; stop/T1 unchanged | |
| 8 | 2026-09-28 14:53 / 13:53 | SELL | 30636.00 / 30674.00 / 30558.00 | 3 | MATCH (G1) **or OPEN-POSITION** | row 7's TV short (stop 30723, T1 30558) is still open at 14:53 unless T1 printed first (it did not: G1 enters at 30636 > 30558) → Pine most likely refuses G1 as not flat. Then the tool prints `G1: FAIL (no TradingView trade matched)` and Agent 06 records **G1 = OPEN-POSITION, thesis carried by TV trade #(row 7)**: that trade MUST still exit at T1 30558.00 (same T1 as G1) for the thesis to count. Whether that satisfies Section 5 "G1 within tolerance" is a Fable judgement to write down here, never a tool change | |

### How the ≥ 90 % target is read (Section 5 / target end-state (b))

8 oracle rows → a single unexplained miss is 87.5 %. The target is therefore evaluated on the
**adjusted denominator**: `matched / (8 − rows classified ENTRY-CAP or OPEN-POSITION)`, with every
excluded row listed above and its mechanism named. The exclusions are design consequences of sacred
invariants (I1, I5), not tolerance. Anything classified `BUG`, and any entry in the "Hard fails" section
above, fails the gate regardless of the rate.

### Hard-fail checklist (06.4 — all automated by `pine_parity.py`, all must read "none")

- [ ] opposite-direction disagreement on a matched setup (`OPPOSITE:`)
- [ ] a trade without a stop (`NO STOP:`; run with `--require-stop`, as run_parity.sh does)
- [ ] an entry at/after 16:30 ET / 15:30 CT (`LATE ENTRY:`)
- [ ] a size above the cap (`SIZE ABOVE CAP:`; cap 5 micros = `max micros per trade`)
- [ ] a decisive stop/T1 delta > ±2 ticks on a matched row (`LEVELS:`)

### Hold-time (I6) expectations

Every oracle trade is a 5m-bar-close entry with a bracket ≥ 32 ticks away, so the `<=5s` and `<=30s`
buckets must be 0 and the ≤ 5 s profit share 0.0 %. Anything else is a `BUG` in the exit path.

### C-14 (strategy.risk session reset) — from OA-2 step "C-14 check"

Owner's note on whether trading resumed after any SOFT_STOP / built-in halt: _(paste)_

### Agent 06 verdict

- Data drift (06.1): _(from the sections above; OA-3)_
- Adjusted match rate: __ / __ = __ %  ·  opposite: __  ·  hard fails: __
- G1 / G2 / G3: __ / __ / __
- BUG rows → PR rejected: _(none | list)_
- FABLE-VERIFIED dist BUILD_ID handed to Agent 07: `________________`
