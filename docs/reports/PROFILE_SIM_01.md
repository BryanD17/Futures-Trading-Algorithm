# PROFILE SIMULATOR — FIRST REPORT (V4 Agent 08)

**Date:** 2026-08-12  **Engine:** Main @ V4 Agent 08  **Active profile:** `STRICT` (default)
**Sessions:** 3 warm SIM boots, seeds `42`, `7`, `1337` (`-Dsim.backfill.seed`)

---

## ⚠️ READ THIS FIRST — WHAT THIS REPORT IS AND IS NOT

This report validates the **PIPELINE**, not the strategy. Every number below comes
from the SIM's *synthetic* tape, which is generated to exercise the engine — it is
not market data and it carries no edge. **Nothing here justifies flipping a
profile.** The adoption path is 3–5 **LIVE-observation** sessions first
(`QUICK_START` → "PROFILES & THE SIMULATOR"), reviewing would-trade events against
the Bot Chart, and only then a flip.

---

## THE HEADLINE

**Across all three sessions, every profile — including `MINIMAL` — would have taken
ZERO trades.**

| Session (seed) | Symbol | Evaluations | STRICT satisfied | STANDARD would-trade | MINIMAL would-trade |
|---|---|---|---|---|---|
| 42   | MNQ | 37 | 0 | 0 | 0 |
| 42   | MGC | 37 | 0 | 0 | 0 |
| 42   | MES | 0¹ | – | – | – |
| 7    | MNQ | 37 | 0 | 0 | 0 |
| 7    | MGC | 37 | 0 | 0 | 0 |
| 7    | MES | 0¹ | – | – | – |
| 1337 | MNQ | 37 | 0 | 0 | 0 |
| 1337 | MGC | 38 | 0 | 0 | 0 |
| 1337 | MES | 0¹ | – | – | – |

¹ MES runs as an SMT-only feed in the multi-instrument engine — it has no setup
funnel of its own, so having no evaluations is correct, not a gap.

`emissionEvaluations = 0` in every session: the funnel **never reached an emission
attempt at all**. Every evaluation above is the 15m periodic sample.

## RANKED BLOCKING GATES OF THE ACTIVE PROFILE (STRICT)

| Session | Symbol | Blocking reason | Count | Share |
|---|---|---|---|---|
| 42   | MNQ | `expired (200 bars without progress)` | 37 | 100% |
| 42   | MGC | `HTF bias flip BULLISH -> BEARISH` | 37 | 100% |
| 7    | MNQ | `HTF bias became NEUTRAL` | 37 | 100% |
| 7    | MGC | `HTF bias became NEUTRAL` | 37 | 100% |
| 1337 | MNQ | `expired (200 bars without progress)` | 37 | 100% |
| 1337 | MGC | `HTF bias became NEUTRAL` | 38 | 100% |

Note what these are **not**: not `M4`, not `M7`, not any mandatory gate. They are
**funnel-progress failures** recorded in `ctx.lastGateFailed` — the setup died on
its way to the gates, so the gates were never consulted.

## THREE CONSERVATIVE OBSERVATIONS

**1. This is an upstream problem, not a strictness problem — and that is a
falsifiable claim, not an opinion.** `MINIMAL` requires only killzone + sweep +
structure + OTE band + sizing. It scored 0 in all three sessions. Appendix E5
predicted exactly this reading: *"MINIMAL = 0 would-trades proves the problem is
upstream (warmth, data, sweep detection), not gate strictness."* Loosening a
profile cannot fix a setup that never assembles. **Flipping `trade.profile` on this
evidence would change nothing.**

**2. It is not a warmth problem, which narrows it usefully.** The chart is warm
(`warm=true`, 4,330 1m bars), the ICT library is populated, and the confluence
stack reads `long 9/16 w=0.60` — a majority of facts are known and true. What fails
is *setup assembly*: MNQ dies on `expired (200 bars without progress)`, MGC on HTF
bias flipping or going NEUTRAL before the funnel completes. So the next
investigation is the funnel's progression and the bias stability that drives it,
**not** backfill, and **not** the gates.

**3. The bias is the common thread across seeds.** Four of the six symbol-sessions
died on a bias transition (`became NEUTRAL` or a flip), and the two "expired" cases
show `bias=NEUTRAL`/`BEARISH` at expiry. The V3 bias-vote work is already the
instrument for this — the vote ran `NEUTRAL` throughout these sessions. That points
at bias stability as the first thing to measure in LIVE, ahead of anything in this
document.

## WHAT THE SIMULATOR PROVED ABOUT ITSELF

The measurement machinery works end to end even though the answer is zero:

- All three profiles are evaluated on **every** sample regardless of which is
  active — running in `STRICT`, the engine still reported what `STANDARD` and
  `MINIMAL` would have done.
- Blocking reasons are captured, counted and ranked per session.
- Would-trade events persist to JSONL (`-Dprofile.sim.file`, default
  `data/profile_sim.jsonl`) and survive restarts — round-tripped by test.
- A profile that stays satisfied counts **once**, not once per sample (rising-edge
  latch), so counts measure opportunities rather than duration.
- `[PROFILE MNQ] active=STRICT wouldTrade: STANDARD=0 MINIMAL=0 (session)` emits on
  the same 15m tick as `[GATES]` and `[CONFLUENCE]`.

## THE FLIP COMMAND (FOR WHEN EVIDENCE SUPPORTS IT — IT DOES NOT YET)

```bash
-Dtrade.profile=STANDARD      # default STRICT
```

**One switch, one week.** Never flip two things at once — if `chart.anchorMode`
and `trade.profile` move together, neither result means anything. `MINIMAL` is a
**diagnostic floor, not a trading mode**; trading it requires a written reason in
the V4 LEDGER NOTES first.

**Risk is unaffected either way.** DLL/MLL, max contracts, flatten-by, sizing
bounds, warmup guards, the kill switch and killzone-only entry hold identically in
all three profiles — enforced by code the profile is never passed to, and pinned by
`ProfileRiskIndependenceTest` (16 tests, parameterised across all three).

## NEXT STEP

Do **not** flip anything. Run LIVE-observation sessions at defaults and re-read
these three numbers daily (`QUICK_START` → OWNER'S ROLLOUT RUNBOOK R2). If LIVE
also shows `MINIMAL = 0`, the work is in the funnel — specifically setup expiry and
bias stability — and this report has already named where to look.
