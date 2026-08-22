# LIVE OBSERVATION 02 — FULL SESSION, AND A CORRECTION TO LIVE_OBS_01

**Date:** 2026-08-18  **Engine:** `Main` @ 2c43df9 + PR #151
**Account:** `PRAC-V2-304501-28946455` (id 25855253, `simulated=true`)
**Window:** 2026-08-17 22:14 → 2026-08-18 17:10 CT — **18h56m, continuous**
**Engine uptime:** 38h unbroken (since 02:55 CT 2026-08-17)
**Source:** 228 five-minute snapshots (`logs/census-snapshots.jsonl`), no gaps
**Defaults:** `STRICT`, `FRACTAL_LEG`, hysteresis off, detector 5m — untouched.

---

## ⚠️ CORRECTION TO LIVE_OBS_01

`LIVE_OBS_01` reported **"172 displacements detected today"** and concluded
*"displacement is not rare."* **That was wrong, and it inverted the
conclusion.**

Those counts were taken from the whole engine log, which begins with the
**backfill replay of 7 days of history** through the same listener path the live
feed uses. Splitting the log at the end of that replay:

| | during backfill replay | on live tape |
|---|---:|---:|
| MNQ | 72 | 22 |
| MGC | 77 | 30 |

**~75% of those displacements were replayed history, not live detection.** The
live rate is roughly **5–7% of 5m bars** — displacement *is* rare on real tape,
which is the opposite of what LIVE_OBS_01 said, and it is what actually explains
the stall distribution below.

---

## THE CENSUS — ONE SESSION, RECONSTRUCTED ACROSS THE ROLLOVER

`[FUNNEL]` counters are session-scoped; these are peaks held before the 17:00 CT
Globex rollover, recovered from the snapshots.

```
MNQ  BIAS_SET=3 MANIP_DONE=2 SWEEP_DONE=3 DISPLACED=0 MSS_CONFIRMED=0
     OTE_ARMED=0 IN_TRADE=0 | invalidated: expired=2, HTF bias flip=1

MGC  BIAS_SET=1 MANIP_DONE=6 SWEEP_DONE=6 DISPLACED=0 MSS_CONFIRMED=0
     OTE_ARMED=0 IN_TRADE=0 | invalidated: HTF bias flip=4,
                              HTF bias became NEUTRAL=1, expired=1
```

**`DISPLACED=0` on both symbols across a full 19-hour session including all of
NY.** The funnel reached `SWEEP_DONE` **nine times** (MNQ 3, MGC 6) and never
once advanced. Yesterday's 32-minute window at least saw `MSS_CONFIRMED=1` on
MNQ; a full session saw none.

Stage counters are not strictly nested (MNQ `SWEEP_DONE` 3 > `MANIP_DONE` 2;
MGC `MANIP_DONE` 6 > `BIAS_SET` 1). Consistent with re-arm after invalidation,
but it means the counters should not be read as a strict funnel.

### Stall distribution

| Stage | Reason | MNQ | | MGC | |
|---|---|---:|---:|---:|---:|
| `SWEEP_DONE` | `no-recent-displacement` | 295 | **83%** | 456 | **69%** |
| `SWEEP_DONE` | `no-fvg-for-displacement` | 30 | 8% | 149 | 23% |
| `SWEEP_DONE` | `displacement-wrong-direction` | 31 | 9% | 60 | 9% |

Duration-weighted (a sample-per-5min measure of how long the machine sat on each
reason), not event counts.

---

## WHY IT DID NOT TRADE — THE ARITHMETIC

`tryRecordDisplacement()` requires, inside **5 detector bars = 25 minutes** of a
sweep, a displacement that is **in the HTF-bias direction** and **carries an
FVG** (or has a live same-direction unfilled FVG to fall back on).

Measured on live tape this session:

| | displacement rate | FVG rate (live) | FVG rate (backfill) |
|---|---:|---:|---:|
| MNQ | ~5% of 5m bars | 7/22 = **32%** | 13/72 = 18% |
| MGC | ~7% of 5m bars | 5/30 = **17%** | 13/77 = 17% |

Chaining those, a sweep converts to an armed setup on the order of **a few
percent**. Against **nine sweeps**, the expected number of armed setups this
session is well under one.

**Zero is the expected result, not an anomaly.** Nothing here is evidence of a
defect. It is evidence that at these settings the setup is *rare* — on the order
of one armed setup every several days, so a trade requires many more sessions.
Whether that frequency is acceptable is an owner decision, and it is a
**strategy** decision, not a bug to fix.

The independence assumption above is deliberately conservative in the wrong
direction: the strategy's whole premise is that a sweep makes displacement *more*
likely than the base rate, so true conversion is probably higher than a naive
chain implies. The measured reality is still **0 for 9**.

---

## NY AM WAS NOT DIFFERENT — THE HYPOTHESIS LIVE_OBS_01 RAISED IS DEAD

`LIVE_OBS_01` suggested the thin overnight tape might be why nothing developed,
and that NY AM would differ. It did not. Displacements per hour (CT), live tape:

| session | hours | MNQ | MGC |
|---|---|---:|---:|
| Globex overnight | 22:00–02:00 | 4 | 3 |
| London | 02:00–07:00 | 4 | 6 |
| **NY AM killzone** | **08:00–10:00** | **0** | **2** |
| NY balance | 10:00–16:00 | 3 | 3 |

**The NY AM killzone produced the fewest displacements of any session.** The
distribution is essentially flat at 0–2 per hour everywhere. Session is not the
explanatory variable; the base rate is simply low all day.

---

## THE 3-CANDLE FVG HYPOTHESIS — STILL OPEN, NOW BETTER POSED

`LIVE_OBS_01` flagged that most displacements are single-candle while
`checkForFvgCreation()` tests the standard 3-candle gap across the last three
bars. That held on live tape: **49 of 69** live displacements were 1-candle,
16 were 2-candle, 4 were 3-candle.

But the FVG rate does **not** obviously indict it — MNQ ran 32% on live tape
against 18% on replayed history, i.e. *better* live. And MGC's 17% is identical
across both. If the 3-candle window were systematically mismeasuring 1-candle
moves, a stable ~17–20% across two very different tapes is not what that would
look like.

**Do not change the FVG window on this evidence.** The next honest step is
`DisplacementRateHarness`, which prints the whole threshold surface — measure
it, do not reason about it.

---

## HYGIENE

- Trades **0**, orders **0**, risk denials **0**, positions **0** — across all
  228 samples. `OTE_ARMED=0`, so nothing ever reached the risk engine.
- `running=true` on 228/228 samples; kill switch never armed; zero engine errors.
- Chart stayed warm throughout (MNQ 7708+, MGC 7643+ 1m bars).
- MGC saw **4 HTF bias flips + 1 NEUTRAL** this session — bias was noticeably
  less stable than the 32-minute window in LIVE_OBS_01 suggested. Not yet a
  concern, but it is the thing to watch if MGC keeps invalidating pre-sweep.
- A snapshot-tooling bug was found and fixed mid-session: `@($null).Count` is 1
  in PowerShell, so a failed `/api/trades` fetch was recorded as one trade. The
  counter now reads the raw JSON body and returns -1 on transport failure so a
  fetch error is visibly distinct from a genuine zero.

---

## NEXT

1. **Stop treating single sessions as evidence.** Two sessions, nine sweeps,
   zero conversions. The measurement that matters is sweeps-to-arm conversion
   over ~30+ sweeps, which is a week or more of tape, not a day.
2. **Run `DisplacementRateHarness` against live-captured bars** before touching
   any displacement threshold or the FVG window.
3. **Do not flip `trade.profile`.** `MINIMAL=0` for a second session. Profiles
   act after arming; nothing armed.
4. **Owner decision, stated plainly:** if ~1 armed setup every several days is
   too infrequent to be useful, that is a strategy-design conversation about the
   displacement + FVG conjunction — not a licence to widen the 25-minute window
   or drop the FVG requirement, either of which would trade quality for activity.
5. Risk calibration still wrong for this account ($50k model, $165,243.42
   balance, `remainingDrawdown` ~117k). Must be fixed before any funded account.
