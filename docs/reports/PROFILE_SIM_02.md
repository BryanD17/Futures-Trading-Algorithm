# SIM NOW TRADES — FUNNEL PROGRESSION & BIAS STABILITY (V4 follow-up)

**Date:** 2026-08-12  **Branch:** `fix/funnel-progression`
**Result:** three seeded warm SIM sessions, **14 trades each**, 0 open at the end.

---

## ⚠️ WHAT THIS PROVES, AND WHAT IT DOES NOT

It proves the **PIPELINE**: a sweep can become a displacement, an FVG, an MSS,
an armed OTE zone, an approved order, a fill and a closed trade — end to end,
deterministically, three times.

It proves **nothing about edge**. The SIM tape is a scripted fixture designed to
contain the setup. Of course it produces winners. **Do not read the P&L below as
performance**; it is the arithmetic of a fixture playing out as written.

---

## THE THREE SESSIONS

Command (per session — seeds 42 / 7 / 1337):

```bash
java -Dserver.port=<port> -Dsim.backfill.seed=<seed> \
     -Dmock.virtualClock=true -Dmock.candleIntervalMs=20 \
     -Dstdvote.detectorTimeframe=1 \
     -jar api-backend/build/libs/api-backend-1.0.0-SNAPSHOT.jar
# then: POST /api/control/start?mode=SIM
```

| Seed | Trades | Fills | Signals approved | Denied by risk | Net P&L | End balance | Open |
|------|--------|-------|------------------|----------------|---------|-------------|------|
| 42   | 14 | 28 | 14 | 8 | +50.92 | 50,281.32 | 0 |
| 7    | 14 | 28 | 14 | 8 | +51.25 | 50,281.65 | 0 |
| 1337 | 14 | 28 | 14 | 8 | +50.82 | 50,281.22 | 0 |

Both instruments traded (MNQ 8, MGC 6 per session). **The risk engine kept
denying 8 signals per session** for `R:R too low: 2.33 < 3.0` — exactly what it
is supposed to do, and useful confirmation that "trades happen" was not achieved
by weakening risk.

Sample trade record, verbatim:

```json
{ "symbol": "MNQ", "side": "BUY", "quantity": 2,
  "entryPrice": 20004.25, "exitPrice": 20008.245703006764,
  "realizedPnL": 3.995703006763506, "notes": "Breakeven stop hit" }
```

---

## WHAT WAS ACTUALLY WRONG

The V4 close-out said the constraint was upstream of the gates. It was — in
**four** distinct places, three of them real engine or fixture defects.

### 1. `INVALIDATED` was terminal for the life of the process (engine defect)

In LEGACY mode the re-arm engine never ran, so one dead setup per symbol ended
the session. Measured: **5 state transitions in an entire SIM run**, then 265
consecutive samples parked in `INVALIDATED`.

"One-move discipline" is meant to bound **trades**, not **attempts** — a setup
that died without trading consumed no risk and used no trade allowance.
`stdvOte.rearmOnInvalidated` (default **true**) now re-arms it under the *same*
gates the scalp path already enforced: cooldown, killzone open, no open
position, risk frequency limits. `IN_TRADE` stays terminal in legacy mode; that
part *is* the discipline.

### 2. Single-candle displacement was ~2.3x stricter than documented (engine defect)

`detectDisplacement` compared a single candle's **body** against a threshold
derived from average **range** (`avgRange * 1.5`), which subsumed the expansion
test already done. Effective bar: `range >= ~2.3x` the 14-bar average, against a
documented intent of 1.5x ATR.

Measured pass rate at shipped defaults: **0.63% of 5m bars** — once or twice per
session. Fixed so the distance test applies to the multi-candle branch only;
`DisplacementRateHarness` now prints the whole threshold surface, and
`DisplacementConfirmationTest` pins both the fix and the two tests that must
still bite.

### 3. The execution engine never saw a candle in multi-instrument SIM (wiring defect)

In multi-instrument mode the engine subscribes symbols itself, so
`onMarketData` never runs — and `ExecutionEngine.onNewCandle`, which drives
**fills, stops and targets**, was only called from there. Signals were approved
and orders submitted, and then nothing ever happened: `0` fills, `0` trades,
balance frozen at 50,000.

The candle tap is the one place every candle passes through in that mode, so the
execution engine now hangs off it. This alone took the sessions from *0 trades*
to *14*.

### 4. The SIM tape could not pose the question (fixture defect)

The live SIM stream was a memoryless uniform random walk
(`close = open + (rand-0.5)*10`). Nothing in it correlates a sweep with the
displacement that should follow, so the funnel sat at `SWEEP_DONE` for hundreds
of candles reporting `no-recent-displacement`. **A SIM whose tape cannot
assemble a setup validates nothing.**

`SimChoreographyTape` (SIM-only, `sim.tape=CHOREOGRAPHY`, default) now replays
the hand-verified 23-candle act from `SyntheticScalpSessionGenerator` inside
each killzone, and the warm boot uses the *same* tape so history and live ticks
are one market. Between acts it drifts quietly upward — which is what made the
HTF bias stable.

---

## BIAS STABILITY

Before: `BULLISH 130 / BEARISH 134 / NEUTRAL 24`, with `HTF bias flip` and
`HTF bias became NEUTRAL` shredding setups mid-funnel.

After: **`BULLISH 307 / NEUTRAL 30 / BEARISH 0`**, and bias-flip invalidations
disappeared from the funnel census entirely.

The cause was never hysteresis tuning — `bias.hysteresis.enabled` is still
**off** by default and was not touched. A memoryless tape simply cannot hold a
bias; a tape with persistent drift does.

---

## THE NEW TELEMETRY THAT FOUND ALL OF THIS

`ctx.lastGateFailed` is **sticky**, so counting it across 15m samples measures
how *long* a setup sat dead, not how often anything happened. The first read of
these logs showed "HTF bias became NEUTRAL" 274 times and suggested a bias
crisis; the real number of NEUTRAL invalidations in that window was **one**.

`[FUNNEL]` counts EVENTS instead:

```
[FUNNEL MNQ] BIAS_SET=4 MANIP_DONE=5 SWEEP_DONE=6 DISPLACED=0 MSS_CONFIRMED=0
             OTE_ARMED=0 IN_TRADE=6 | invalidated: expired=9
             | stalls: SWEEP_DONE:displacement-wrong-direction=38,
                       SWEEP_DONE:no-recent-displacement=22
```

Stage arrivals say *where* it stops; `stalls` say *what it is stopping on*. Every
fix above was found by reading one of those stall reasons and nothing else.

---

## WHAT HAS NOT CHANGED

- `trade.profile` is still `STRICT`. No profile was flipped.
- `chart.anchorMode` is still `FRACTAL_LEG`.
- `bias.hysteresis.enabled` is still `false`.
- **Risk is untouched.** `PropFirmRiskEngine`, DLL/MLL, sizing bounds,
  flatten-by and the kill switch are byte-identical, and the sessions above show
  the engine still denying 8 of 22 signals on R:R.
- The LIVE path is untouched by the fixture work: `SimChoreographyTape` is
  referenced only by `MockConnector`.

## NEXT

These sessions validate the pipeline on a scripted tape. The honest next step is
**LIVE observation at defaults** with the `[FUNNEL]` census running, to see which
stall reasons dominate on real tape — that census is now the fastest way to
answer the question, and it is the same instrument that solved this one.
