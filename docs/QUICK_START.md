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

## OWNER'S ROLLOUT RUNBOOK (POST-V4)

**R1. WEEK ONE — LOOK, DON'T TOUCH.** Run at defaults (`STRICT`,
`FRACTAL_LEG`). Each day open the Bot Chart next to TopstepX with the reference
indicators loaded and confirm the same FVGs, order blocks, pools and OTE band
appear in the same places. Any structural mismatch is a fixup before any flip.

**R2. READ THE THREE NUMBERS DAILY.** `[PROFILE]` would-trade counts,
`STRICT`'s ranked blocking gates, and `[ICTLIB-DIFF]`. What they mean together:

- `MINIMAL = 0` → **upstream problem** (warmth, sweeps, funnel progression),
  not strictness. No flag fixes it. **This is what the first report found.**
- `STANDARD >> STRICT = 0` with sane blocking gates → strictness is the cost;
  a flip is on the table.
- `STANDARD ≈ 0` too → the tape genuinely is not offering the choreography. No
  flag fixes that honestly either.

**R3. THE FLIP (only when R2 supports it).** Review the last 10 `STANDARD`
would-trade events against the Bot Chart. Would you have taken them by hand? If
most yes: `-Dtrade.profile=STANDARD`. One switch, one week. `MINIMAL` is a
diagnostic floor — trading it needs a written reason in the V4 LEDGER NOTES
first.

**R4. WHAT NEVER CHANGES.** DLL/MLL, max contracts, flatten-by, sizing bounds,
warmup guards, the kill switch and killzone-only entries hold in **every**
profile. If any would-trade event ever violates one of those, it is a bug —
revert to `STRICT` and file it. Re-verify Topstep's current written automation
policy for the account type before any live flip.

**R5. ANCHOR MODE — a separate decision, on its own week.** Run
`-Dchart.anchorCompare=true` for a few sessions. If `TREND_SHIFT` zones
consistently match what you would have drawn and `FRACTAL_LEG` ones do not,
flip `-Dchart.anchorMode=TREND_SHIFT` — alone, per the one-switch rule.

### Where V4 stands today

The first profile report (`docs/reports/PROFILE_SIM_01.md`) found **zero
would-trade events in every profile across three sessions**, with the funnel
never reaching an emission attempt. Per R2 that is an upstream verdict: **do not
flip anything yet.** Run LIVE observation at defaults; the next work is setup
expiry and bias stability.

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

## RUNNING SIM SO IT ACTUALLY TRADES (V4 follow-up)

```bash
java -Dserver.port=8080 -Dsim.backfill.seed=42 \
     -Dmock.virtualClock=true -Dmock.candleIntervalMs=20 \
     -Dstdvote.detectorTimeframe=1 \
     -jar api-backend/build/libs/api-backend-1.0.0-SNAPSHOT.jar
# then POST /api/control/start?mode=SIM
```

| Flag | Why |
|------|-----|
| `mock.virtualClock=true` | Without it the live SIM stream advances **5 real seconds per candle**, so a ten-minute run produces ten minutes of tape and nothing can develop. |
| `mock.candleIntervalMs=20` | One virtual minute per 20 ms — a full day of tape in ~30 s. |
| `stdvote.detectorTimeframe=1` | The scripted act is a **1-minute** fixture. On the 5m default its expansion is averaged away and no FVG survives aggregation. LIVE keeps the 5m default. |
| `sim.backfill.seed=<n>` | Deterministic tape — the same seed replays the same session. |

Verified on three seeded sessions (42 / 7 / 1337): **14 trades each**, 28 fills,
8 signals still denied by the risk engine on R:R. See
`docs/reports/PROFILE_SIM_02.md`.

**This proves the pipeline, not an edge.** The SIM tape is a scripted fixture
designed to contain the setup.

### The SIM tape

`sim.tape=CHOREOGRAPHY` (default) replays the hand-verified 23-candle act from
`SyntheticScalpSessionGenerator` inside each killzone — accumulation, an
equal-lows cluster, sweep + raid, displacement + MSS, an OTE retrace with a
rejection — and drifts quietly upward between acts so the HTF bias stays stable.
The warm boot uses the same tape, so history and live ticks are one market.

`sim.tape=RANDOM` restores the old memoryless walk (for tests that want
unstructured noise). Note that on that tape the funnel cannot assemble a setup:
it was the reason SIM never traded.

### `[FUNNEL]` — where the funnel dies, as event counts

```
[FUNNEL MNQ] BIAS_SET=4 MANIP_DONE=5 SWEEP_DONE=6 DISPLACED=0 MSS_CONFIRMED=0
             OTE_ARMED=0 IN_TRADE=6 | invalidated: expired=9
             | stalls: SWEEP_DONE:displacement-wrong-direction=38
```

Emitted on the same 15m tick as `[GATES]`. Stage counts say **where** the funnel
stops; `stalls` say **what it is stopping on**.

Read this instead of counting `lastGateFailed`, which is **sticky** — it holds
the last failure until something overwrites it, so counting it measures how long
a setup sat dead, not how often anything happened. That distinction matters: one
read of these logs showed "HTF bias became NEUTRAL" 274 times when the real
number of such invalidations was **one**.

### Re-arm after a dead setup

`stdvOte.rearmOnInvalidated` (default **true**) lets a LEGACY-mode setup that
died *without trading* arm again, under the same gates the scalp path enforces
(cooldown, killzone open, no open position, risk frequency limits). `IN_TRADE`
stays terminal in legacy mode — that is the one-trade discipline and it is
unchanged. Set the flag false to restore the old one-attempt-per-process
behaviour.

---

## PROFILES & THE SIMULATOR — V4 Agent 08

A **profile** is a named required-confluence set for entries.

| Profile | Requires |
|---------|----------|
| `STRICT` **(default)** | today's full M1–M9 chain, byte-identical |
| `STANDARD` | killzone + bias (legacy **or** vote) + scored sweep + a PD array (ictlib FVG **or** order block) + structure (gate MSS **or** ictlib MSS/BOS) + OTE band touch (machine leg **or** chart zone) + M2b/M8/M9 unchanged |
| `MINIMAL` | killzone + sweep + structure + OTE band touch + M8/M9 unchanged |

```bash
-Dtrade.profile=STANDARD      # default STRICT
```

### RISK IS PROFILE-INDEPENDENT

DLL/MLL, max contracts, flatten-by, sizing bounds, warmup guards, the kill
switch and **killzone-only entry** hold identically in every profile. That is
not a convention — `PropFirmRiskEngine` is never passed a profile and has no way
to obtain one, and `ProfileRiskIndependenceTest` pins it with 16 tests
parameterised across all three (including a structural test that fails the day
someone threads a profile into the risk layer).

### The seam

**One** place in any gating path reads `TradeProfile.active()`:
`MandatoryConfluenceValidator.validateStdvOte`. The chain runs first and exactly
once in every profile — which matters beyond tidiness, because M2b and M7b keep
LOG-mode counters and evaluating them twice would corrupt evidence the owner
reads for a different decision.

### The simulator runs whether or not you flip anything

At every emission evaluation **and** on each 15m tick, all three profiles are
judged. Running in `STRICT` — the default — every session still answers "what
would `STANDARD` have done?", so a flip is never a guess.

```
[PROFILE MNQ] active=STRICT wouldTrade: STANDARD=3 MINIMAL=7 (session)
```

A profile that stays satisfied for twenty samples counts **once**: the counts
measure opportunities, not how long a condition persisted.

Would-trade events carry the timestamp, direction, entry/stop/target and the
active profile's **full** blocking-gate list, and persist to JSONL
(`-Dprofile.sim.file`, default `data/profile_sim.jsonl`) so they survive
restarts. `GET /api/confluence/{symbol}` serves the counters, the ranked
blocking gates and the last 5 events per profile.

### ADOPTION PLAYBOOK — in order, no shortcuts

1. **Observe.** Run at defaults (`STRICT`) in LIVE for 3–5 sessions. The
   simulator is already recording.
2. **Read the three numbers daily** (runbook R2): would-trade counts, STRICT's
   ranked blocking gates, `[ICTLIB-DIFF]`.
3. **If `MINIMAL` = 0** → the problem is upstream (warmth, sweeps, funnel
   progression), *not* strictness. No flag fixes that. Fix upstream first.
   **This is what the first report found — see `docs/reports/PROFILE_SIM_01.md`.**
4. **If `STANDARD` >> `STRICT` = 0** with sane blocking gates → review the last
   10 would-trade events against the Bot Chart. Would you have taken them by
   hand? If most yes, flip to `STANDARD`.
5. **One switch, one week.** Never move `trade.profile` and `chart.anchorMode`
   in the same week — neither result would mean anything.
6. **Never flip straight to `MINIMAL`.** It is a diagnostic floor, not a trading
   mode. Trading it requires a written reason in the V4 LEDGER NOTES first.

---

## THE CONFLUENCE STACK — V4 Agent 07

`GET /api/confluence/{symbol}` returns **one snapshot per direction**: every
confluence fact the engine can answer, with its owner, its weight and a
human-readable detail. The same object drives the `[CONFLUENCE]` log line and
the panel on the Setup tab.

It **aggregates only**. Bias, sweeps, zones and lifecycles are computed by the
components that own them; this service records their answers and does the
arithmetic. Nothing here gates a trade.

### The fields and who owns each one

| Field | Owner | Default weight |
|-------|-------|----------------|
| `inTradingKillzone` | `KillzoneClock` | 3 |
| `htfBiasAligned` | `HtfTrendAnalyzer` (legacy bias) | 2 |
| `voteBiasAligned` | `BiasVoteEngine` (V3 3-of-4) | 2 |
| `pdVerdict` | `PremiumDiscountEvaluator` (M2b) | 1 |
| `recentSweep` | `RaidDetector` / raid pipeline | 3 |
| `raidScore` | `RaidQualityScorer` (≥ floor) | 2 |
| `machineOteState` | StdvOte state machine | 2 |
| `activeFvgInDirection` | ictlib §S2 registry | 2 |
| `priceInsideFvg` | ictlib §S2 registry | 2 |
| `nearestObZone` | ictlib §S7 registry | 2 |
| `bprPresent` | ictlib §S3 registry | 1 |
| `viNearby` | ictlib §S4 registry | 1 |
| `openingGapMagnet` | ictlib §S5 registry | 1 |
| `poolSweptRecently` | ictlib §S6 registry | 2 |
| `structureState` | ictlib §S8 `StructureEngine` | 2 |
| `chartOteState` | `ChartEngine` 30m zone | 2 |

Override any weight with `-Dconfluence.weight.<field>=N` (must be ≥ 0; a weight
of 0 keeps the field visible but stops it moving the score).

### Tri-state: why `—` is not a small `✗`

Each field is `TRUE` / `FALSE` / `UNKNOWN`. **UNKNOWN means the source could
not answer** — cold, not warmed, not running — and is excluded from *both* the
score and the maximum:

```
score    = Σ weight(field) where field is TRUE
maxScore = Σ weight(field) where field is NOT UNKNOWN
```

A cold engine therefore reads `long 0/0w=0.00` rather than `0/16`, which is the
difference between "nothing is known yet" and "nothing is true". Reporting a
cold source as `false` is exactly how the starved-input failure class comes
back (Appendix E6).

### The telemetry line

Emitted on the same 15m tick as `[GATES]`, so the two always describe the same
instant:

```
[CONFLUENCE MNQ] long 8/16w=0.53 short 8/16w=0.53 top: inTradingKillzone,recentSweep,activeFvgInDirection,nearestObZone
```

`top:` lists the heaviest TRUE fields of whichever direction scores higher.

### Other knobs

| Property | Default | Meaning |
|----------|---------|---------|
| `confluence.nearTicks` | `40` | How close a zone must be to count as "at" price. |
| `confluence.raidScoreFloor` | `5` | Raid score at or above which `raidScore` reads TRUE. |
| `confluence.recentMinutes` | `120` | How recent a pool sweep / structure event still counts. |

---

## THE BOT CHART OVERLAY — V4 Agent 06

The Bot Chart now draws what a fully-loaded ICT chart draws. Hold it next to
TopstepX: the same structures should appear in the same places.

### Layers

| Chip | Family | Default | Drawn as |
|------|--------|---------|----------|
| FVG | `fvg` | on | box |
| Order blocks | `orderBlock` | on | box |
| Liquidity pools | `liquidityPool` | on | box |
| Volume imbalance | `volumeImbalance` | on | box |
| Daily gap (NDOG) | `openingGapDaily` | on | box + midline |
| Weekly gap (NWOG) | `openingGapWeekly` | on | box + midline |
| MSS | `mss` | on | marker |
| BOS | `bos` | on | marker |
| Displacement | `displacement` | **off** | marker |
| BPR | `bpr` | **off** | box |

Displacement and BPR start off: both are derived from things already on the
chart, so they are the first to clutter it. Every chip shows its live count and
toggles independently.

### Lifecycle → appearance (one mapping, every family)

| State | Appearance |
|-------|-----------|
| `ACTIVE`, `POINT` | solid border, extends to the right edge |
| `TOUCHED`, `TESTED`, `PARTIAL` | dashed border — price has interacted with it |
| `BREAKER` | dashed, **not** muted — polarity flipped but still a live level |
| `FILLED`, `BROKEN`, `SWEPT`, `REMOVED` | muted, and the **right edge freezes** at the bar that ended it |

### Session shading — two kinds, deliberately distinct

The pale bands are §S10 **display sessions** (NY, London open, London close,
Asia, each in its own timezone). The green bands are the engine's real
**TRADING killzones** from `KillzoneClock` — 09:45–12:30 and 13:45–16:00 ET.

The legend spells this out because confusing the two is how someone ends up
"explaining" a trade with a window no gate ever consulted. **Only the
killzones are read by anything that trades.**

### Detection timeframe

The `15m` / `1m` / `all` selector chooses which ictlib series the overlay
requests. **15m is the default**: on a 30m chart the 1m instances are noise.
Switch to `1m` for the close side-by-side verification pass.

### Payload

Bounded server-side: every live detection per family plus the 3 most recent
finished ones, hard-capped at 300 total. If the cap bites, the UI says so
rather than silently showing a shortened list. Measured on a warm SIM session:
**~24 KB total, ~8 KB of detections** at 15m — comfortably pollable at 15s.
Dev builds log the exact size once per fetch.

Tune with `?detections=<1m|15m|all>` and `?detectionCap=<n>`, or lower the
per-family `ictlib.retain.*` caps.

---

## TUNING THE BOT CHART — OTE ANCHORING (V4 §S9)

The Bot Chart can draw its OTE fibs two ways. **The default is unchanged from
pre-V4** and nothing flips without the owner flipping it.

| Mode | How the leg is chosen |
|------|----------------------|
| `FRACTAL_LEG` **(default)** | The most recent significant 2-bar fractal leg — whichever of the last confirmed swing high / low came later sets the direction. |
| `TREND_SHIFT` | §S9: the swing **low that started the trend** to the confirmed higher high that **shifted structure**, with the high anchor **extending** as the trend prints new confirmed highs. What a human actually draws. |

```
-Dchart.anchorMode=TREND_SHIFT        # global    (default FRACTAL_LEG)
-Dchart.anchorMode.MNQ=TREND_SHIFT    # one symbol
-Dchart.oteBand=0.618,0.786           # default   0.62,0.79 (sweet = midpoint)
-Dchart.anchorCompare=true            # default   false
```

The resolved values are printed once per instrument at startup and served on
every `/api/chart/{symbol}` response:

```json
{ "anchorMode": "FRACTAL_LEG", "oteBand": "0.62,0.79", "anchorCompare": false }
```

Each zone also carries its own `anchorMode` and band inside the `ote` object,
so a zone always reports the levels it was *actually* armed on — a config
change can never retroactively rewrite the fibs of a zone that already exists.

### The post-ARM rule (the part worth understanding)

While a zone is still `FORMING`, a new higher confirmed pivot simply
**re-stretches** the fibs — no fact has been recorded against the old levels
yet, so nothing is lost.

Once a zone is `ARMED` or `REACTED`, it is a **historical fact about prices
that were actually traded**. Extending it would let the band chase price and
make `REACTED` unfalsifiable. So a post-ARM extension **invalidates** the old
zone and forms a fresh `FORMING` one on the new anchors. The invalidated zone
stays queryable so the sequence remains auditable against the chart.

A zone is also invalidated by a close through its origin (the pre-existing
rule) or by an opposite trend shift.

### `chart.anchorCompare` — deciding from evidence

With the flag on, BOTH modes run. The active mode answers every query; the
other is a shadow, exposed as `shadowOte` and logged only when the two
verdicts diverge:

```
[CHART-ANCHOR MNQ] TREND_SHIFT=FORMING/BULL@21000.0-21200.0 FRACTAL_LEG=FORMING/BEAR@21200.0-21050.0
```

They frequently **agree** on clean trending tape and diverge when a shallow
pullback creates a fractal micro-leg that the trend read ignores. Run it for a
few sessions and flip only if the `TREND_SHIFT` zones consistently match what
you would have drawn by hand — on its own week, per the one-switch rule.

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

### Families (Agent 03)

| § | Family | Lifecycle | Retention |
|---|--------|-----------|-----------|
| S4 | `VOLUME_IMBALANCE` | `ACTIVE → FILLED` (range fully covers the zone) | 6 |
| S5 | `OPENING_GAP_WEEKLY` | `ACTIVE → TOUCHED` (**never terminal**) | 3 |
| S5 | `OPENING_GAP_DAILY` | `ACTIVE → TOUCHED` (**never terminal**) | 2 |
| S6 | `LIQUIDITY_POOL` | `ACTIVE → PARTIAL → SWEPT` | 4 per side |

Opening gaps deliberately have **no fill-terminal state**: they act as
persistent magnets, so they are evicted by retention count and nothing else.

Sessions come from `TradingSessionCalendar` — the same calendar the V3 D1
ladder uses — and nowhere else. 18:00 ET opens the next session; 17:00–18:00 ET
is the maintenance break and belongs to no session. A new trading **week** is
detected by comparing the *session* dates' Mondays, so a holiday-shifted open
keys on the first session that actually trades, never on the calendar Monday.

### Families (Agent 04)

| § | Family | Lifecycle | Retention |
|---|--------|-----------|-----------|
| S7 | `ORDER_BLOCK` | `ACTIVE → TESTED → BREAKER → REMOVED` | 5 per side |
| S8 | `MSS` | `POINT` | 200 |
| S8 | `BOS` | `POINT` | 200 |

An order block is the **origin of the move that broke structure** — the lowest
(bullish) candle between the swing and the break — not "the last opposite
candle", which is only the same bar sometimes. `BREAKER` is *not* terminal: a
block whose far edge was body-closed through flips polarity and keeps working
as a level in the opposite direction until it is reclaimed (`REMOVED`).

`MSS` is the regime **flip**; `BOS` is continuation while the regime agrees,
deduplicated so only a genuinely *newer* swing level emits one.

### `[ICTLIB-DIFF] mss` — the second measurement

ictlib's structure engine is a pure zigzag regime. The 2022-model gate (M6)
uses `MarketStructureShiftDetector`, which additionally demands displacement
through the level and prior opposite structure. **The gate's detector is
untouched and remains the only thing entries read.** To keep two structure
truths honest, the `StructureEngine` runs a *shadow* instance of the real gate
detector over the same bars and reports:

```
[ICTLIB-DIFF MNQ] mss: ictlib=18 gate=35 agreeWindow=6
```

`agreeWindow` pairs each ictlib shift with a gate shift of the same direction
within ±`ictlib.structure.mssAgreeWindow` bars, greedily and at most once each.
Order does not matter — either may lead. That number answers the only question
worth asking: do the two see the same turn a few bars apart, or different
markets entirely?

### Liquidity pools publish into the ONE level universe

A confirmed §S6 pool does not stay locked inside ictlib. `IctLibLevelAdapter`
registers it in `LevelEngine` as an `EQUAL_HIGH` (buyside) or `EQUAL_LOW`
(sellside) level tagged `ICTLIB_CLUSTER`, so the raid pipeline can fire on the
same object the Bot Chart draws.

This is strictly **one-directional**: ictlib registers levels; it never reads
raid state and never marks a level raided. Raid detection stays the raid
pipeline's job. The pool's own `PARTIAL`/`SWEPT` lifecycle is ictlib's
independent read — keeping the two separately derived is what makes a
disagreement *visible* rather than self-confirming.

Not attaching a `LevelEngine` for a symbol is not an error: pools still form,
they simply are not published.

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
| `ictlib.retain.volumeImbalance` | `6` | §S4 retention. |
| `ictlib.vi.projectBars` | `3` | §S4 display-only marker projection. |
| `ictlib.retain.gapWeekly` | `3` | §S5 weekly opening-gap retention. |
| `ictlib.retain.gapDaily` | `2` | §S5 daily opening-gap retention. |
| `ictlib.pool.swingLen` | `5` | §S6 bars to the left of a pivot. |
| `ictlib.pool.toleranceDiv` | `2.5` | §S6 `tolerance = ATR(n) / div`. Raise it if pools never form on an instrument. |
| `ictlib.pool.minCluster` | `3` | §S6 swings needed to make a pool. |
| `ictlib.pool.scanDepth` | `50` | §S6 rolling swing history per side. |
| `ictlib.pool.atrPeriod` | `10` | §S6 ATR window (simple mean, for replay determinism). |
| `ictlib.retain.pool` | `4` | §S6 retention **per side**. |
| `ictlib.ob.swingLen` | `10` | §S7 bars to the left of a tracked swing. |
| `ictlib.ob.useBody` | `true` | §S7 zone from bodies (`true`) or full wicks (`false`). |
| `ictlib.retain.orderBlock` | `5` | §S7 retention **per side**. |
| `ictlib.structure.pivotLeft` | `5` | §S8 pivot left bars. |
| `ictlib.structure.pivotRight` | `1` | §S8 pivot confirmation bars. |
| `ictlib.structure.historyCap` | `200` | §S8 MSS/BOS history per family. |
| `ictlib.structure.mssAgreeWindow` | `5` | Bars of slack when pairing ictlib and gate shifts. |

The resolved config is printed once at start:
`[ICTLIB] ictlib enabled=true displacement(meanLen=5,...) gaps(mode=FVG,...)`.
