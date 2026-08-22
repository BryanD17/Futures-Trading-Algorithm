# LIVE OBSERVATION 01 — FIRST REAL-TAPE FUNNEL CENSUS

**Date:** 2026-08-17  **Engine:** `Main` @ 2c43df9 + PR #151
**Account:** `PRAC-V2-304501-28946455` (id 25855253, `simulated=true`, `canTrade=true`)
**Window:** 02:55–03:27 CT Monday — **~32 minutes**, Globex overnight / London
**Defaults:** `trade.profile=STRICT`, `chart.anchorMode=FRACTAL_LEG`,
`bias.hysteresis.enabled=false`, `stdvote.detectorTimeframe=5` — none touched.
Only `-Dbackfill.days=7` was raised, at the command line, for warmth.

---

## ⚠️ WHAT THIS IS AND IS NOT

This is **one 32-minute overnight window**, not a session. The NY AM killzone
(09:45–11:00 ET) had not opened. `PROFILE_SIM_01` → R2 asks for **3–5 LIVE
sessions** before any reading is acted on; this is the first fragment of the
first one. Nothing here justifies flipping anything, and the stall counts below
are too small to rank with confidence.

What it *does* establish is that the census now runs on **real tape against the
right contracts**, which was not true before today.

---

## THE SESSION ONLY EXISTS BECAUSE OF THREE FIXES (PR #151)

The first two LIVE starts produced invalid data. In order of discovery:

1. **MGC was bound to an expired contract.** Bulk contract discovery searches
   `/Contract/search` with an empty `searchText`; the gateway returns a
   truncated page of 20 roots (`BP6 … M6E`). MNQ, MGC and MES all sort after
   that cut-off, so every traded symbol missed the cache and fell through to a
   calendar-based *guesser*. It was right by coincidence for the index micros
   and put gold on `CON.F.US.MGC.Q26` — August, already rolled off — against an
   active `Z26`. Symptom: a **5-bar** backfill and a funnel reading a dead
   contract's prices.
2. **A 7-day backfill trips the gateway's rate limit.** Every MES chunk returned
   HTTP 429, and a throttled chunk was indistinguishable from a market-closed
   one, so the boot reported a successful backfill of **0 bars**.
3. **`[Backfill] … Chart memory is warm.` printed unconditionally** — it claimed
   warmth for the 5-bar MGC fill while `/api/chart` read `warm=false`. Same
   class of misleading telemetry as sticky `lastGateFailed`.

### Warmth after the fixes

| Symbol | Contract | 1m bars | `warm` |
|---|---|---|---|
| MNQ | `CON.F.US.MNQ.U26` | 6933 | ✅ |
| MGC | `CON.F.US.MGC.Z26` | 6869 | ✅ |
| MES | `CON.F.US.MES.U26` | 6933 | ✅ |

Note for the runbook: **`backfill.days=3` cannot warm a Monday-morning boot.**
Three calendar days ending Monday 02:47 CT contain only ~1381 market minutes —
below the 1500-bar threshold — because the weekend is closed. Use 5–7 on Mondays.

---

## THE CENSUS — `[FUNNEL]`, EVENT COUNTS

Read these, not `lastGateFailed`. Stage counters are cumulative session events.

```
[FUNNEL MNQ] BIAS_SET=1 MANIP_DONE=1 SWEEP_DONE=1 DISPLACED=0 MSS_CONFIRMED=1
             OTE_ARMED=0 IN_TRADE=0 | invalidated: expired=1
             | stalls: SWEEP_DONE:no-recent-displacement=48,
                       SWEEP_DONE:displacement-wrong-direction=30,
                       MSS_CONFIRMED:no-reaction-at-band=23

[FUNNEL MGC] BIAS_SET=1 MANIP_DONE=1 SWEEP_DONE=1 DISPLACED=0 MSS_CONFIRMED=0
             OTE_ARMED=0 IN_TRADE=0 | invalidated: expired=1
             | stalls: SWEEP_DONE:no-recent-displacement=95,
                       SWEEP_DONE:no-fvg-for-displacement=75
```

MES has **no funnel** — it is the SMT-only feed, exactly as `PROFILE_SIM_01`
footnote 1 describes. Not a gap.

### Stall distribution — the one number this session was run to get

| Stage | Reason | MNQ | MGC |
|---|---|---:|---:|
| `SWEEP_DONE` | `no-recent-displacement` | 48 | 95 |
| `SWEEP_DONE` | `displacement-wrong-direction` | 30 | – |
| `SWEEP_DONE` | `no-fvg-for-displacement` | – | 75 |
| `MSS_CONFIRMED` | `no-reaction-at-band` | 23 | – |

**Every stall on both symbols is at or after `SWEEP_DONE`, and displacement is
the dominant reason on both.** The funnel reliably gets a bias, a manipulation
leg and a sweep out of real tape, then dies waiting for a displacement that
either never comes, comes the wrong way, or carries no FVG.

`OTE_ARMED=0` on both symbols → the funnel **never reached an emission
attempt**, so no gate was ever consulted.

---

## PROFILE READING (R2)

```
[PROFILE MNQ] active=STRICT wouldTrade: STANDARD=0 MINIMAL=0 (session)
[PROFILE MGC] active=STRICT wouldTrade: STANDARD=0 MINIMAL=0 (session)
```

**`MINIMAL = 0` on real tape.** Per R2 that is the *upstream* verdict, the same
one `PROFILE_SIM_01` returned on synthetic tape: **do not flip `trade.profile`.**
A profile cannot loosen its way past a setup that never assembles. The
difference from the SIM report is that we now know *where* it fails to assemble
— displacement after the sweep — rather than at bias stability.

---

## SUPPORTING DISTRIBUTIONS (duration, not events — read with care)

Sampled `[GATES]` lines measure how *long* the machine sat somewhere, so they
rank duration. Included only to characterise the window.

| | MNQ | MGC |
|---|---|---|
| samples | 462 | 458 |
| bias BULLISH / BEARISH / NEUTRAL | 284 / 153 / 25 | 251 / 191 / 16 |
| killzone active | 120 (26%) | 237 (52%) |
| longest-held states | `INVALIDATED` 241, `SWEEP_DONE` 171 | `SWEEP_DONE` 203, `INVALIDATED` 193 |

Bias is **stable on real tape** — NEUTRAL is 5% (MNQ) and 3.5% (MGC), and no
bias-flip invalidation appears in either funnel census. The V3/V4 worry about
bias wobble shredding setups does not reproduce here. `bias.hysteresis.enabled`
should stay `false`; there is no counterfactual evidence for it in this window.

### `[ICTLIB-DIFF]`

```
MNQ  fvg: ictlib=123 existing=247 overlap=123     mss: ictlib=50 gate=146 agreeWindow=37
MGC  fvg: ictlib=145 existing=278 overlap=145     mss: ictlib=51 gate=129 agreeWindow=31
```

`overlap == ictlib` on both symbols: every FVG `ictlib` finds is also found by
the existing detector, which finds about **twice as many**. `ictlib` is a strict
subset — it is stricter, not different. Worth knowing before anything is
switched over to it.

---

## HYGIENE

- Trades: **0.** Orders: **0.** Risk denials: **0** (nothing reached the risk
  engine — `OTE_ARMED=0`). Positions at end: **0**.
- Errors in the whole session: **2**, both residual `429`s on MGC chunks that
  exhausted their retries. MGC still backfilled 6869 bars.
- Kill switch not armed; engine never paused.

---

## NEXT

1. **This window is not a session.** Repeat across NY AM (09:45–11:00 ET) before
   ranking anything. The overnight/London tape is the thinnest of the day and is
   the least likely to produce displacement — the dominant stall may simply be
   the session, not the detector.
2. **Then ask the displacement question properly.** `PROFILE_SIM_02` §2 found
   single-candle displacement was ~2.3× stricter than documented and fixed it;
   real tape still stalls there hardest. `DisplacementRateHarness` prints the
   whole threshold surface — measure the LIVE pass rate against it before
   touching a threshold, and change nothing on one window's evidence.
3. **Do not flip `trade.profile`.** `MINIMAL=0` again.
4. **Fix the risk calibration before this points at a Combine.** The panel is
   modelled on a $50k account (`maxLossLimit=2000`, `dailyLossLimit=1000`) while
   this PRAC account holds **$165,243.42**, so it reports
   `remainingDrawdown=117243` and the MLL guard is not binding. Untouched here
   by policy — but it must be right before any funded account is involved.
