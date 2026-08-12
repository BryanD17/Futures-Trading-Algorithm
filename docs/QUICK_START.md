# Quick Start — Topstep Futures Trading Algorithm

This guide assumes you have cloned the repo and want to build, run, and
work with the new STDV+OTE refactor (v2.0).

## Prerequisites

- JDK 21 (Temurin verified)
- Node 18+
- Git

## Build

From the repo root:

```bash
# Backend (trading-engine + api-backend)
./gradlew clean build --no-daemon

# Frontend
cd dashboard-frontend
npm install
npm run build
```

The backend has 6 known pre-existing test failures in the news subsystem
(documented in `docs/REFRACTOR_BASELINE.md`); these are NOT caused by the
refactor and were already present at the baseline commit.

## Run

### BACKTEST mode (default)

```bash
./gradlew :trading-engine:run --args="BACKTEST"
```

This runs `BacktestExample`, which currently uses the **legacy**
`IctHighConfluenceStrategy`. The new `StdvOteStrategy` is not yet wired
as the default — see `docs/BACKTEST_COMPARISON.md` §2.3 for the work
that has to land first.

### SIM mode (MockConnector)

```bash
./gradlew :trading-engine:run --args="SIM"
```

Boots `SimEngineRunner` with the MockConnector — safe for testing.

### LIVE mode

```bash
./gradlew :trading-engine:run --args="LIVE"
```

Credentials resolve in PRIORITY ORDER — higher sources silently win:

1. Java system properties (`-Dtopstep.*`)
2. **`~/.topstep/credentials.properties`** (keys: `apiUrl`, `username`,
   `apiKey`, `accountId`)
3. Environment variables:
   - `TOPSTEP_API_URL`
   - `TOPSTEP_USERNAME`
   - `TOPSTEP_API_KEY`
   - `TOPSTEP_ACCOUNT_ID`

**GOTCHA (bitten in production 2026-07-08):** if the credentials file
exists, setting `$env:TOPSTEP_ACCOUNT_ID` does NOTHING — the file's
`accountId` wins. When Topstep rotates/replaces an account (e.g. a new
PRAC account), update the FILE. The startup log prints which source was
used and which account was resolved; the engine refuses to start unless
the configured account exact-matches an active account, and refuses
non-simulated accounts without `-Dtopstep.allowNonSimulated=true`.
Always confirm the `Resolved trading account: ...` line before leaving
the engine running.

**This connects to real markets and risks real money. Do not enable
LIVE until you have run a SIM session and watched the new Setup panel
render a complete state-machine lifecycle.**

## Dashboard

```bash
cd dashboard-frontend
npm run dev
```

Open the printed URL (Vite chooses one, typically `http://localhost:5173`).
The API backend must be running for the dashboard to populate:

```bash
./gradlew :api-backend:bootRun
```

## The new Setup tab

The dashboard now has a **Setup** tab between Overview and Positions.
It visualises the STDV+OTE setup state per instrument (MNQ / MES / MGC):

- **State machine stepper** — current position in the IDLE → IN_TRADE
  sequence; INVALIDATED renders with the last failed gate id.
- **Bias / killzone / SMT / tier pills.**
- **Mandatory gates M1..M9** — the failing gate is highlighted.
- **STDV ladder** — the 5-level exit ladder (-0.27 / -1 / -2 / -2.5 / -4)
  with liquidity-backed badges and a realism tag on -2.0.
- **OTE zone** — all five canonical levels (0.5 / 0.62 / 0.705 / 0.79 / 1.0)
  and the PD-array-in-zone edge.
- **Plan block** — entry / stop / RR / size / tier (appears on IN_TRADE).

The panel polls `/api/setup/{symbol}` at 1Hz; WebSocket push is a
follow-up.

## Configuration knobs you will actually touch

In `application.yml` (or as Spring properties), under the `stdvOte.*`
root. Defaults match `docs/architecture/STDV_OTE_MODEL.md`:

| Key | Default | What it does |
|-----|---------|--------------|
| `stdvOte.enabled` | `false` | Switch the default strategy to STDV_OTE. Leave `false` until the SIM smoke test passes. |
| `stdvOte.size.riskFraction` | `0.12` | Fraction of available MLL room risked per trade. |
| `stdvOte.size.safetyCushion` | `300` | Dollars kept off the MLL floor. |
| `stdvOte.rr.floor` | `2.0` | Minimum reward-to-risk at the -2.0 STDV target. M7 rejects setups below this. |
| `stdvOte.killzone.nyAmStartEt` | `09:45` | NY AM killzone open (ET). |
| `stdvOte.killzone.nyAmEndEt` | `11:00` | NY AM killzone close (ET). |
| `stdvOte.killzone.silverBulletStartEt` | `10:00` | Silver Bullet open (ET). |
| `stdvOte.killzone.silverBulletEndEt` | `11:00` | Silver Bullet close (ET). |
| `stdvOte.raid.minQuality.MNQ` | `5` | Minimum raid quality for MNQ. |
| `stdvOte.raid.minQuality.MES` | `5` | Minimum raid quality for MES. |
| `stdvOte.raid.minQuality.MGC` | `6` | Stricter floor for MGC. |
| `stdvOte.risk.mllTrail` | `INTRADAY` | INTRADAY (conservative) or EOD. **You must set this to match your actual Topstep account/platform.** |
| `stdvOte.risk.flattenByEt` | `15:10` | Topstep cutoff (CT). Verify against current Topstep rules. |

## Before going LIVE — checklist

The refactor leaves LIVE manual on purpose. Before you flip
`stdvOte.enabled = true` AND switch the runner default, verify:

1. **MLL trail model** — INTRADAY vs EOD for your specific Combine /
   Express / Funded account on your specific platform (TopstepX,
   NinjaTrader, Tradovate, Quantower, TradingView). Sources disagree.
2. **DLL status** — TopstepX removed the platform DLL in Aug 2024; the
   other platforms still enforce it. The sizer codes defensively
   regardless; confirm which model applies to you.
3. **Contract specs** for MNQ / MES / MGC — the registry hardcodes
   tick size, tick value, and point value. Verify against the broker
   spec sheet before risking live money.
4. **Session flatten cutoff** — historically ~15:10 CT. Confirm
   current Topstep rules.
5. **The SIM smoke test** — boot SIM, watch the Setup panel render a
   full state-machine lifecycle for at least one instrument, confirm
   flatten-by-time fires, confirm `stdvOte.size.max = 20` is respected.

## STARTUP & WARMTH (chart-in-memory + historical backfill)

On every start, `TopstepConnector.startMarketDataPolling` now replays
**~3 days of 1-minute history per instrument** through the same listener
path the live feed uses, BEFORE live polling begins. This warms HTF bias,
LevelEngine PDH/PDL, the raid pipeline, and the in-memory 30m ChartEngine
in ~30 seconds — instead of the 5–24 hours of blindness a cold start used
to cost.

- **Depth**: `-Dbackfill.days=N` (default `3`, clamped to `[1,7]`). The
  chosen value is logged at startup.
- **The log line to look for** (one per instrument):
  `[Backfill] MNQ: delivered <N> historical 1m bars (3 days). Chart memory is warm.`
- **The API tripwire**: `GET /api/chart/<symbol>` must report
  `"warm": true` and `"barsIngested1m" >= ~1500` before you should expect
  trades. `warm=false` means the backfill did not run and the bot is
  trading blind — the exact condition behind the two-days-no-trades
  incident.
- **Restarts are cheap now**: a restart costs ~30 seconds of replay, not
  hours of NEUTRAL bias. Restart freely.
- A warmup guard in `LiveEngineRunner.handleStrategySignal` guarantees
  replayed history can NEVER fire a real order (look for `[Warmup]
  Suppressing signal ...` lines during startup).

**SIM warm boot (V2):** SIM now boots warm exactly like LIVE does — the
MockConnector replays **N days of SYNTHETIC, seeded 1m history** through
the same listener path before the first live-sim tick, so `/api/chart`
reports `warm=true` within seconds of a SIM start (and after every SIM
restart). The synthetic path is structured (session-scaled volatility,
one multi-hour leg + retraces per day) so the ChartEngine forms real OTE
zones during replay.

- `-Dbackfill.days=N` — same depth + clamp `[1,7]` as LIVE (default 3).
- `-Dsim.backfill.seed=42` — RNG seed; same seed = identical SIM history
  (reproducible sessions). Default 42.
- `-Dsim.warmBoot=false` — restore the old cold boot if you need it.
- The log line: `[SimWarmBoot] MNQ: delivered 4320 SYNTHETIC 1m bars
  (3 days, seed=42). Chart memory is warm.` followed by `✓ SIM warmup
  complete`. SIM mirrors the LIVE warmup guard (layers 1+2), so no signal
  created during the replay can ever place a SIM order.

## READING THE GATES (why is it not trading?)

Every completed 15m bar, each instrument prints one line:

```
[GATES MNQ] state=IDLE bias=NEUTRAL lastGateFailed=M2 kzActive=false chart30mOte=FORMING/BULL
```

The same fields are served by `GET /api/setup/{symbol}` (`state`,
`lastGateFailed`), and the bot's internal 30m chart + OTE overlay by
`GET /api/chart/{symbol}?lookback=100`.

One-line meaning of each mandatory gate (from
`MandatoryConfluenceValidator`):

| Gate | Meaning |
|------|---------|
| M1 | Instrument is MNQ/MES/MGC. |
| M2 | HTF bias is not NEUTRAL AND the trade direction matches it. |
| M2b | Premium/Discount (entry vs equilibrium) — see the section below. |
| M3 | Inside a killzone. |
| M4 | Liquidity sweep present AND raid score >= instrument minimum. |
| M5 | Displacement candle AND a FairValueGap present. |
| M6 | Market Structure Shift / CHoCH confirmed. |
| M7 | OTE zone built, PD-array edge inside the band, RR >= floor at the -2.0 target. |
| M8 | Size request >= instrument minimum (5 micros). |
| M9 | Clean diagnostics — no unresolved failure after the risk-engine pre-flight. |

### THE TIMEFRAME LADDER (V3 — H4 + D1 with CME session boundaries)

The aggregation ladder now reaches Daily: `{1m, 3m, 5m, 15m, 30m, 1h, 4h,
1d}`. The intraday frames are clock-aligned exactly as before; H4 and D1
are SESSION-aware:

- The CME trading day runs 18:00 ET → 17:00 ET next day. The daily bar for
  "Tuesday" OPENS Monday 18:00 ET; Sunday 18:00 ET opens Monday's session.
- 17:00–18:00 ET is the maintenance break — no bars, and a stray bar there
  belongs to NO daily/H4 bucket.
- H4 anchors to the session open: 18:00, 22:00, 02:00, 06:00, 10:00,
  14:00 ET wall-clock (DST-safe: the zone does the bucketing, never a
  fixed UTC offset).

TWO-TIER BACKFILL:

- Tier 1 (unchanged): `backfill.days` of 1m bars through the listener path.
- Tier 2 (new): `-Dhtf.backfill.days=N` (default **30**, clamped [7, 90])
  of H1 bars fetched DIRECTLY from the API and delivered ONLY to the
  aggregation layer's seeding API — never through the 1m listener, never
  into the ChartEngine. On success you'll see:
  `[HTF-Backfill MNQ] seeded 330 H1 bars -> 120 H4, 21 D1 (30 days, 0 refused)`
  A failed seed is NON-FATAL: the engine runs exactly as it did pre-V3 and
  D1-dependent logic ABSTAINS.

D1 signals are only as deep as the seed — with `htf.backfill.days=7` you
get ~5 daily bars, below the `pd.d1MinBars` threshold Agent 05 uses.

The Bot Chart API accepts `GET /api/chart/{symbol}?tf=30m|4h|1d`
(default 30m, unchanged).

### M2b — PREMIUM/DISCOUNT GATE (V3 — config-gated, DEFAULT LOG)

The top-down rule: longs are only taken at a DISCOUNT (proposed ENTRY price
below the equilibrium/midpoint of the governing range), shorts only at a
PREMIUM (above it). The judged price is the resting OTE limit, never the
current tick — price momentarily in premium while the limit rests in
discount is the normal geometry of the setup.

Governing range resolution (each verdict logs which range governed):

| Step | Range | When |
|------|-------|------|
| R0 | D1 dealing range (last ESTABLISHED daily swing high/low, fractal strength 2) | once the D1 series is >= `pd.d1MinBars` (default 10) deep with confirmed swings |
| R1 | Yesterday's PDH/PDL | price inside [PDL, PDH] |
| R2 | Today's developing range | breakout day, once the range spans >= `pd.minRangeTicks` |
| R3 | ABSTAIN | no usable range — gate PASSES, logs, counts |

Every `[PD]` verdict names the governing range (`R0-D1`, `R1`, `R2`); a
thin or failed HTF seed simply never activates R0 — the R1/R2/R3 chain is
byte-identical to pre-R0 behavior. Related V3-Agent-05 knobs:
`-Dbias.v1.includeH4=true` (DEFAULT false — measure first) lets the V1
structure vote ABSTAIN when H4 fractal structure contradicts the 15m/30m
read; V4 logs PWH/PWL tapped state in its detail string (context only,
never a vote).

Switch: `-Dpd.gate.mode=OFF|LOG|BLOCK` (DEFAULT **LOG**).

- `OFF` — evaluator never runs (pre-V3 behavior, byte-identical).
- `LOG` — verdicts are computed and counted; an unfavorable verdict prints
  `[PD MNQ] WOULD-BLOCK LONG entry=... eq=... range=R1 hi=.../lo=...` but
  the gate passes. The `[GATES]` line carries `pd=DISCOUNT(R1)` /
  `pd=WOULD-BLOCK-LONG`; `/api/setup/{symbol}` serves the counters in `pd`.
- `BLOCK` — DISCOUNT required for longs, PREMIUM for shorts; the
  EQUILIBRIUM band (±`pd.eqBandTicks`, default 2 ticks around the midpoint)
  blocks BOTH directions; ABSTAIN always passes.

Tuning: `pd.eqBandTicks` (default 2), `pd.minRangeTicks` (default 2x the
symbol's `chart.minLegTicks`, per-symbol override `pd.minRangeTicks.MNQ`).

ROLLOUT PLAYBOOK: run LOG for 3–5 sessions. For every WOULD-BLOCK line,
check what that signal actually did — WOULD-BLOCK on losers is the gate
earning its keep; WOULD-BLOCK on winners means tighten `pd.eqBandTicks` or
revisit the range source before flipping. Then flip `-Dpd.gate.mode=BLOCK`
and verify M2b blocked counts roughly match the LOG-mode WOULD-BLOCK rate
(a large mismatch is a bug — revert to LOG and file it).

### THE BIAS VOTE (V3 — 3-of-4 per STDV_OTE_MODEL.md §H1, DEFAULT LOG)

The model doc defines HTF bias as FOUR votes, each BULL / BEAR / ABSTAIN:

| Vote | Source | Plain language |
|------|--------|----------------|
| V1 | HtfTrendAnalyzer state | 15m/30m structure: HH/HL = BULL, LH/LL = BEAR, ranging = ABSTAIN |
| V2 | DailyAmdCycleTracker | The daily AMD cycle's distribution leg direction; no distribution leg yet = ABSTAIN |
| V3 | Price vs true day open | Below the midnight-ET open = discount = BULL; above = BEAR; within ±`pd.eqBandTicks` of the open = ABSTAIN |
| V4 | Daily draw on liquidity | Untapped PDH pulls price up (BULL), untapped PDL pulls down (BEAR); both untapped = the nearer one; both tapped / equidistant = ABSTAIN |

Aggregation: >= 3 same-direction votes ⇒ BULLISH/BEARISH, otherwise NEUTRAL
(2+ abstentions make 3-of-4 impossible — NEUTRAL by arithmetic, which the
hysteresis machinery treats as uncertainty, not contradiction).

Switch: `-Dbias.vote.mode=LEGACY|LOG|VOTE` (DEFAULT **LOG**).

- `LEGACY` — the vote engine never runs (pre-V3, byte-identical).
- `LOG` — every 15m bias evaluation prints one line and counts agreement,
  while the legacy single-vote bias still decides:
  `[VOTE MNQ] V1=BULL(weak-bullish) V2=ABSTAIN(no-dist-leg) V3=BEAR(above-open) V4=BULL(PDH-untapped) -> vote=NEUTRAL legacy=BULLISH AGREE=false`
- `VOTE` — the vote's finalBias replaces the legacy value at the single
  seam feeding `core.recordHtfBias`.

The `[GATES]` line carries `vote=<bias>(<bull>/<bear>/<abstains>)
agree=<bool>`; `/api/setup/{symbol}` serves the four votes + counters in
`vote`; the Setup panel renders four V1–V4 pills (gray = ABSTAIN).

ADOPTION PLAYBOOK: run LOG for 3–5 sessions and read the counters. High
agreement = low risk AND low reward in flipping. The interesting rows:
`voteDirectional_legacyNeutral` (setups the richer bias would have
enabled — investigate every one) and disagreements (V2–V4 overruling
structure — spot-check 5+ against the Bot Chart and TopstepX: whose bias
read the tape better?). Flip `-Dbias.vote.mode=VOTE` only after that
review, and keep hysteresis settings unchanged that week (one switch at a
time).

### BIAS HYSTERESIS (V2 — config-gated, DEFAULT OFF)

Field evidence 2026-07-09: a session passed M1–M4 and was destroyed by
"HTF bias became NEUTRAL" before M5. NEUTRAL is *uncertainty*; an
OPPOSITE bias is *contradiction* — hysteresis makes the machine treat
them differently for **in-flight setups only**:

- `-Dbias.hysteresis.enabled` (default **false**) — when true, an
  in-flight setup survives brief NEUTRAL wobbles; an OPPOSITE flip still
  kills it instantly.
- `-Dbias.neutralGraceBars` (default 2, clamped [1,4]) — consecutive
  NEUTRAL 15m evaluations the setup survives before "HTF bias NEUTRAL
  beyond grace".

**The counterfactual line** (hysteresis OFF — the default): every
`[BIAS] NEUTRAL flip invalidated setup (hysteresis OFF — grace would
have held it N more bar(s))` is a setup that died to a wobble and WOULD
have been held.

**Adoption path — decide from data, not frustration:** run 3–5 SIM
sessions with the default OFF; count counterfactual lines vs setups that
reached BIAS SET; enable with `graceBars=2` only if NEUTRAL flips are
demonstrably killing multi-gate setups (like 2026-07-09). **Hard
invariant, tested:** entries NEVER fire while the live bias evaluation is
NEUTRAL — grace preserves progress, not entry permission. If you ever
see an entry during NEUTRAL, kill the engine and file it.

### M7b — 30M OTE VERDICT PIPELINE (V3 — config-gated, DEFAULT LOG)

The 30m ChartEngine OTE comparison is no longer a permanent log line: its
agreement stats PERSIST across restarts (`data/ote_agreement_stats.jsonl`,
checkpointed every 30 minutes + at session end; `/api/chart` `oteStats`
now carries `{session, lifetime}`), and a confluence gate is one flag away.

Switch: `-Dote30m.confluence=OFF|LOG|GATE` (DEFAULT **LOG**).

- `OFF` — the check never runs (byte-identical).
- `LOG` — the V2 comparison formalized through counters; always passes.
- `GATE` — M7b requires the chart's active zone for the signal direction
  to be REACTED (or ARMED with `-Dote30m.acceptArmed=true`, default
  false). NO zone tracked = ABSTAIN = pass + count (a thin chart must
  never silently disable trading).

PROMOTE / DELETE CRITERIA (verbatim — these are the decision rules):

> Evaluate after >= 15 sessions of lifetime data:
> - PROMOTE to GATE if agreement precision
>   agreed / (agreed + disagreed) >= 0.70 AND the machine emitted
>   >= 20 signals in the window (sample floor);
> - DELETE the comparison (keep ChartEngine for the dashboard) if
>   precision <= 0.45 with the same sample floor — at that point the
>   30m zone is anti-signal or noise for this machine;
> - IN BETWEEN: keep LOG mode, re-evaluate every 10 sessions, and
>   record each evaluation as a dated LEDGER NOTE.

The stats loader logs a reminder at boot once the threshold is met:
`[OTE-VERDICT] 17 sessions collected — evaluation due.` — that line is
your cue to run the numbers above.

### OWNER'S ROLLOUT RUNBOOK (V3 — the one-switch-at-a-time rule)

Everything V3 added is in LOG mode, counting quietly. NEVER flip two
switches in the same week — attribution dies the moment two behavior
changes overlap. Order of adoption (matches expected impact and risk):

1. `-Dpd.gate.mode=BLOCK` — after 3–5 LIVE LOG sessions (playbook in the
   M2b section above).
2. `-Dbias.vote.mode=VOTE` — after reviewing the agreement counters
   (playbook in THE BIAS VOTE section). Keep hysteresis settings
   unchanged that week.
3. `-Dote30m.confluence=GATE` — ONLY when the M7b promote criteria pass
   (that section's blockquote is the decision rule; DELETE is the other
   arm).

First evidence tables: `docs/reports/TOPDOWN_MEASUREMENT_01.md`
(synthetic-tape pipeline validation — live observation precedes any flip).

Standing safety checks (unchanged from V2, still true): `warm=true`
before expecting trades; Bot Chart matches TopstepX; `lastGateFailed`
should name market reasons, not plumbing; DLL/MLL/flatten-by verified;
Topstep's current written automation policy re-checked for the account
type; kill switch tested weekly.

### OTE AGREEMENT COUNTERS — the adoption playbook (V2)

`/api/chart/{symbol}` now carries an `oteStats` object and the `[GATES]`
line ends with `oteAgree=X oteDisagree=Y chartOnly=Z` (session-scoped,
not persisted; counting only — NOTHING gates on these numbers):

- `machineEmitted_chartAgreed` — the machine entered while the 30m chart
  showed REACTED in the same direction.
- `machineEmitted_chartDisagreed` — the machine entered without the
  chart pattern.
- `chartReacted_machineSilent` — the 30m chart found the screenshot
  pattern while the machine had no armed setup (counted once per zone).

Run several SIM/LIVE-observation sessions and read the counts:
agreement dominating → gating on `hasReactedOte` would add little;
`chartReacted_machineSilent` dominating while setups keep dying to
invalidations → first fix upstream fragility (enable bias hysteresis per
the section above), THEN revisit whether `hasReactedOte` should become a
gate — that change is a separate, owner-approved plan with its own
backtest and PR. Neither switch happens automatically.

### FUNNEL CALIBRATION — windows scale with the detector timeframe (fix 2026-07-27)

Root cause of the "never trades" LIVE session (12h, 0 emissions): the
2026-07-09 field fix moved the entry anatomy to 5m bars but left the setup
windows calibrated in 1m FEED bars — the whole funnel had 40 minutes on a
5x slower clock. Evidence: 144/173 live invalidations were "expired (40
bars without progress)"; an offline replay of the same real tape (the
`FunnelReplayHarness` test, run with `-Dfunnel.data.dir=<dir of real 1m
JSON>`) reproduced 0 emissions — and produced real emissions after the fix.

The windows now scale by `stdvote.detectorTimeframe` (defaults restore the
ORIGINAL design durations; on a 1m detector they equal the historical
constants exactly):

| Window | Default (detector bars) | Effective on 5m | Override |
|--------|------------------------:|----------------:|----------|
| Setup expiry | 40 | 200 feed bars (~3.3h) | `stdvOte.setupExpiryBars` |
| OTE_ARMED window | 8 | 40 feed bars | `stdvOte.oteWindowBars` |
| MSS freshness | 30 | 150 feed bars | `stdvOte.mssFreshBars` |

The startup line `funnel windows (feed bars): expiry=… oteWindow=…
mssFresh=…` prints the resolved values per symbol — verify it after any
detector-timeframe change.

### DETECTOR TIMEFRAME — the 5m entry anatomy (field fix 2026-07-09)

Displacement, FVG, and MSS/CHoCH are now measured on **5-minute candles**
by default (`-Dstdvote.detectorTimeframe=1|3|5|15`). On raw 1m, MNQ never
registered the displacement a human sees on the 5m chart (five ordinary
1m candles ≠ one strong 1m candle), so M5 never passed and every setup
died at SWEEP_DONE. Structure, sweeps, and levels remain 1m. The gates
themselves are unchanged — displacement+FVG (M5) and MSS (M6) are still
mandatory, measured on the model's real timeframe. The `[DISPLACEMENT
<sym>]` log line is now symbol-tagged. The Bot Chart also now includes
the FORMING 30m bar (marked `"partial": true`) so its right edge matches
the broker chart instead of lagging up to 30 minutes.

### TUNING THE BOT CHART (V2 — per-instrument leg thresholds)

The ChartEngine's leg-significance floor is now per-instrument (defaults
identical to before: minLegTicks=40, swingStrength=2, expiryBars=32):

- `-Dchart.minLegTicks.<SYM>` (e.g. `-Dchart.minLegTicks.MGC=80`)
- `-Dchart.swingStrength.<SYM>` (optional)
- `-Dchart.zoneExpiryBars.<SYM>` (optional)

Two log lines drive the tuning: the startup config
`[CHART CFG MGC] minLegTicks=40 swingStrength=2 expiryBars=32`, and the
per-leg telemetry `[CHART MNQ] leg ACCEPTED origin=... size=200t -> OTE
drawn (...)` / `[CHART MNQ] leg REJECTED size=32t < minLegTicks=40`.
The method: watch a few sessions of `[CHART]` lines per instrument (next
to the Bot Chart tab), then set `minLegTicks` so obvious structural legs
are ACCEPTED and noise legs are REJECTED. Frequent REJECTED on legs you
consider obvious → lower it; zones drawn on every wiggle → raise it.

## SCALP FLOOR — known deadlock while the raid pipeline is starved

If `scalpMode.enabled=true`, the default floor `scalp.minRaidScore=6`
combined with the MNQ/MES fallback raid score of **5** rejects every
sweep whenever the raid pipeline has no tracked level (the fallback score
can never reach the floor — by design). After the backfill this mostly
self-heals because PDH/PDL exist from minute one, but **until you have
verified real raid scores in the logs**, the recommended launch flag is:

```
-Dscalp.minRaidScore=5
```

The conservative default in code is intentional and unchanged — the fix
is warmth plus an informed owner, not a weaker floor.

## ALL-SESSIONS TRADING + KILLZONE SIZE BOOST (owner directive 2026-07-08)

Scalp mode now takes entries in **any open session** by default, not just
the prime killzones:

- `-Dscalp.allSessions` (default `true`) — the M3 time gate becomes
  "market open" MINUS a hard no-new-entries block **14:45–17:00 CT** every
  day (protects the 15:10 CT flatten and spans the Globex halt) and the
  weekend gap (Fri 14:45 CT → Sun 17:00 CT). Set `false` to restore
  killzone-only entries. The prime killzone windows themselves are
  unchanged and OR-ed in.
- `-Dscalp.killzoneSizeBoost` (default `1.5`, clamped `[1.0, 2.0]`) —
  inside the prime killzones (NY AM/PM, MGC London prime) the sizer's
  output is multiplied by this factor. Every existing cap still binds:
  tier cap, `topstepMicroMax`, the `[5, 20]` micro band, and the
  PropFirmRiskEngine's evaluation of the final signal. Look for the
  `KILLZONE SIZE BOOST x1.5` log line. NOTE: a boosted trade risks up to
  1.5× the per-trade dollar budget — that is the point, and it is why the
  boost only requests more size and can never bypass a cap.
- Tier scoring is NOT inflated by the wider hours: the O1 "killzone open"
  confluence point still counts only the prime killzones.
- Expect `kzActive=true` in the `[GATES]` lines for most of the day now —
  the boost, not the entry permission, is what distinguishes killzones.

## TOPSTEP AUTOMATION POLICY

Before pointing the engine at a **FUNDED** (non-eval) account, re-verify
Topstep's current *written* policy on automated trading — policies change
and enforcement is account-type-specific. Run SIM against the warm chart
first, and verify `GET /api/chart/<symbol>` matches the TopstepX chart
candle-for-candle before trusting any signal.

## Troubleshooting

| Symptom | Likely cause |
|---------|--------------|
| Frontend builds but Setup panel shows "Backend disconnected" | api-backend bootRun is not running, or proxy is not pointing to `localhost:8080` |
| `npm run build` fails with TS errors | A new dependency was added without `npm install` running; rerun install |
| `./gradlew clean build` test failures in the news subsystem | These 6 failures are pre-existing; see `REFRACTOR_BASELINE.md` |
| Setup panel says state is IDLE forever | Expected until the detector poll inside `StdvOteStrategy.onCandle` is wired (see `BACKTEST_COMPARISON.md` §2.3) |

## Where the code lives

- New strategy code: `trading-engine/src/main/java/com/topstep/trading/strategy/stdvote/`
- New API controller: `api-backend/src/main/java/com/topstep/api/controller/SetupController.java`
- New frontend: `dashboard-frontend/src/components/SetupPanel.tsx` + `.css`,
  `dashboard-frontend/src/types/setup.ts`,
  `dashboard-frontend/src/services/setupApi.ts`
- Design / architecture: `docs/architecture/STDV_OTE_MODEL.md`

## OWNER'S OPERATING RUNBOOK (POST-V2)

### F1. Start of day (SIM or LIVE)
1. Start the stack (`api-backend bootRun`; dashboard `npm run dev`).
2. Dashboard -> Setup tab: the WARM PILL must be green ("CHART WARM
   (>=1500 bars)"). If red/COLD — LIVE: grep the log for `[Backfill]`
   (missing = backfill never ran, restart; failing = the line names the
   chunk+error). SIM: should never be cold post-V2; if it is, the warm
   boot regressed — file it.
3. Bot Chart tab: sanity-check against TopstepX on 30m — same candles,
   same most-recent swing. Divergence means the bot trades a different
   market than you are looking at: stop and investigate first.

### F2. Reading the telemetry during a session
- `[GATES <sym>]` every 15m: state, bias, lastGateFailed, kzActive,
  chart30mOte, oteAgree/oteDisagree/chartOnly — the single answer to
  "why is it not trading right now."
- `[CHART <sym>] leg ACCEPTED/REJECTED`: is the 30m brain seeing the
  structure you see? Obvious legs REJECTED -> lower
  `chart.minLegTicks.<sym>`; zones on noise -> raise it.
- `[BIAS]` counterfactual (hysteresis OFF): each line is a setup that
  died to a NEUTRAL wobble and WOULD have been held.

### F3. The hysteresis decision (from data, not frustration)
1. Run 3-5 SIM sessions with `bias.hysteresis.enabled=false` (default).
2. Count `[BIAS]` counterfactual lines vs setups reaching BIAS SET.
3. If wobbles kill a meaningful share of multi-gate setups (like
   2026-07-09), enable `-Dbias.hysteresis.enabled=true
   -Dbias.neutralGraceBars=2` and re-observe. Setups surviving to M5+
   should rise; entries during NEUTRAL must remain ZERO (impossible by
   tested invariant — if you ever see one, kill the engine and file it).

### F4. The OTE-gate decision (a later, separate decision)
Read `oteStats` after several sessions: agreement dominating -> gating
adds little. `chartReacted_machineSilent` dominating while the machine
dies upstream -> fix upstream fragility first (F3), then REVISIT whether
`hasReactedOte` becomes a gate — that is its own plan, backtest, and PR.

### F5. Before going LIVE, every time
- [ ] warm=true on /api/chart for every traded symbol
- [ ] Bot Chart matches TopstepX on 30m
- [ ] [GATES] lines flowing; lastGateFailed is a market reason, not plumbing
- [ ] Risk panel: DLL/MLL/flatten-by unchanged and correct for the account
- [ ] Topstep's CURRENT written automation policy re-checked (eval vs funded)
- [ ] Kill switch reachable and tested this week

---

## THE ICT LIBRARY (`ictlib`) — V4

`trading-engine/src/main/java/com/topstep/trading/ictlib/` is the
**chart/confluence-grade** detection library added by V4. It implements
Appendix S of `ICT_STACK_MASTER_PROMPT_V4.txt` from spec, and it runs
**beside** the gate detectors rather than replacing them.

Read that sentence twice, because it is the whole design: the detectors that
feed the M1–M9 entry gates are tuned to the 2022-model state machine and are
**untouched**. `ictlib` exists so the Bot Chart can draw what a fully-loaded
ICT chart draws, so the confluence stack has one queryable source per fact,
and so the owner can later unify the two truths **from measured evidence**.

### Where it plugs in

One ingest seam. `ChartEngine.setCandleTap(...)` — `ictlib` reads the exact
same closed 1m candles the Bot Chart draws, on both the backfill and the live
path, so a detection can never disagree with the candles beneath it:

```
LiveEngineRunner / SimEngineRunner
   └─ chartEngine.onCandle(candle)          (backfill + live, one path)
        └─ candleTap  ──►  IctLibEngine.onCandle(candle)
                              ├─ 1m  series ─┐
                              └─ 15m series ─┴─► family detectors ─► DetectionRegistry
```

### Families (Agent 02)

| § | Family | Timeframes | Lifecycle | Retention |
|---|--------|-----------|-----------|-----------|
| S1 | `DISPLACEMENT` | 1m, 15m | `POINT` (instantaneous) | last 50 |
| S2 | `FVG` (or IFVG) | 1m, 15m | `ACTIVE → TOUCHED → FILLED` | 10 per side |
| S3 | `BPR` | 1m, 15m | `ACTIVE → TOUCHED → BROKEN` | 5 per side |

Transitions are **monotonic** — a filled gap never un-fills — and each family's
detector is the only writer of its state. The chart and the confluence stack
*read* these states; nothing recomputes them.

Every list is capped. Nothing in `ictlib` grows per candle without a bound.

### The one behavioural difference that matters

```
legacy FvgDetector : bullish gap  ⇔  l[i] > h[i-2]
ictlib §S2         : bullish gap  ⇔  l[i] > h[i-2]  AND  displacementUp[i-1]
```

**`ictlib` will report far fewer FVGs than the legacy detector. That is the
feature, not a bug.** A naive three-candle gap fires constantly on quiet tape;
requiring the middle candle to be a displacement keeps only the gaps an
energetic move actually left behind.

### `[ICTLIB-DIFF]` — the measurement

Once per session (and on demand via `IctLibEngine.logDiffLines()`):

```
[ICTLIB-DIFF MNQ] fvg: ictlib=7 existing=92 overlap=7
```

- `ictlib` — gaps the §S2 rule found this session (1m + 15m).
- `existing` — gaps the legacy rule would have found over the same bars.
- `overlap` — an **invariant check**: in FVG mode §S2 is the legacy rule *plus*
  a displacement requirement, so every ictlib gap must also be a legacy gap and
  `overlap` must equal `ictlib`. If it ever does not, either the mode is IFVG
  (where the gap comparison inverts) or something has drifted.

A big `existing`/small `ictlib` is the filter working. Verify a handful on the
Bot Chart before concluding anything else.

### Tuning knobs

| Property | Default | Meaning |
|----------|---------|---------|
| `ictlib.enabled` | `true` | Master switch. Observation-only: it gates nothing, so leaving it on changes no live trading behaviour. |
| `ictlib.displacement.meanLen` | `5` | §S1 body-SMA length (over the bars *preceding* the candle). |
| `ictlib.displacement.wickRatioMax` | `0.36` | §S1 max wick as a fraction of body. |
| `ictlib.retain.displacement` | `50` | §S1 retention. |
| `ictlib.fvg.mode` | `FVG` | `FVG` or `IFVG` (inverted/overlap read). |
| `ictlib.retain.fvg` | `10` | §S2 retention **per side**. |
| `ictlib.retain.bpr` | `5` | §S3 retention **per side**. |

The resolved config is printed once at start:
`[ICTLIB] ictlib enabled=true displacement(meanLen=5,...) gaps(mode=FVG,...)`.
