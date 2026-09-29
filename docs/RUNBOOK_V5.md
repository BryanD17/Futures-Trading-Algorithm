# RUNBOOK V5 — running the engine in the configuration the tape proved

Written by Fable Agent 08 (TRADE_FLOW_UNBLOCK_MASTER_PROMPT_V5), 2026-09-28.
Companion documents: `DIAGNOSIS_V5.md` (why it did not trade), `docs/reports/autopsy_v5/` (every agent's evidence).

## 1. What "proven" means

Every number in this runbook was produced by replaying **7,830 real 1-minute MNQ bars (2026-09-21 → 2026-09-28, MES as SMT)** through the real engine path (`StdvOteRunnerStrategy → MandatoryConfluenceValidator → PropFirmRiskEngine → ExecutionEngine`) with `FunnelAutopsyHarness`, in the **DEFAULT** configuration — no `-D` flags. `./gradlew bootRun`, `java -jar api-backend-1.0.0-SNAPSHOT.jar` and the test JVM load the same `engine-defaults.properties`, so what the harness proved is what the engine runs.

## 2. Start commands

```bash
# build (never run :api-backend:clean while a jar from api-backend/build/libs is running)
./gradlew :trading-engine:clean :trading-engine:build :api-backend:build

# SIM via the API (the way the dashboard drives it)
cd api-backend && ./gradlew bootRun                     # or:
java -Duser.home=%USERPROFILE% -Dserver.port=8080 -jar api-backend/build/libs/api-backend-1.0.0-SNAPSHOT.jar
curl -X POST "http://localhost:8080/api/control/start?mode=SIM"
curl http://localhost:8080/api/status        # effectiveConfig + telemetry.gateCounts
curl http://localhost:8080/api/setup         # last 200 gate decisions with numbers
curl http://localhost:8080/api/setup/MNQ     # current setup: state, gate, band, entry/stop

# LIVE (manual, human-initiated, never automatic)
curl -X POST "http://localhost:8080/api/control/start?mode=LIVE"

# Dashboard
cd dashboard-frontend && npm install && npm run dev   # http://localhost:3000 → Setup / "Why no trade"
```

Optional overrides, in precedence order: `-Dkey=value` > `ENGINE_KEY_UPPER` env > `%USERPROFILE%\topstep-trading\engine.properties` > classpath `engine-defaults.properties` > code default. `./gradlew bootRun -Pengine.props=<path>` points at a different properties file.

## 3. The proven engine.properties (verbatim = the classpath defaults)

You do not need a `~/topstep-trading/engine.properties` file: the defaults below ARE the proven configuration. Create the file only to override something.

```properties
session.allSessions=true
session.gateMode=SCORING
backfill.days=7
bias.hysteresis=true
bias.vote.mode=VOTE
bias.voteRule=ADAPTIVE
bias.source=RANGE
bias.neutralGraceBars=3
pd.gate.mode=BLOCK
bias.range.window=AUTO
bias.range.minLegTicks=400
displacement.atrMult=1.2
displacement.bodyPct=0.50
displacement.recentBars=12
fvg.linkBars=3
ote.anchorMode=DEALING_RANGE
ote.fib62=0.618
ote.fib705=0.705
ote.fib79=0.786
ote.windowBars=8
mss.freshBars=30
ote30m.mode=SCORING
risk.rrFloor=1.0
risk.rrFloor.scalp=0.8
risk.rrCeiling=5.0
ote.entryModel=IMPULSE_LEG
ote.impulseLeg.minSweepFib=0.705
ote.pdArraySource=ICT_OB
ote.obLookbackBars=5
strategy.legacyFallback=false
warmup.timeoutSeconds=120
size.minMicros=1
size.preferredMicros=5
size.maxMicros=20
news.blockWithoutCalendar=false
setup.expiry.sweepToDisplacement=60
setup.expiry.displacementToMss=60
setup.expiry.mssToOte=240
setup.rearmAfterClose=true
entry.counterTrendScalp=false
entry.counterTrend.sessions=ASIA,LONDON,PRE_NY
entry.counterTrend.minRangeTicks=400
entry.counterTrend.maxRiskFraction=0.5
entry.counterTrend.maxPerDay=2
range.ltf.enabled=false
range.ltf.minLegTicks=120
range.ltf.window=INTRADAY_SWINGS
range.ltf.gating=INDEPENDENT
range.ltf.maxPerDay=4
range.ltf.riskFraction=1.0
range.ltf.sessions=ASIA,LONDON,PRE_NY,NY_AM,NY_LUNCH,NY_PM,PRE_ASIA
```

`scalp.enabled` stays at its code default `false` (legacy target model: T1 = 0.5 of the dealing range, T2 = 0.382, T3 = range extreme). The Topstep envelope (DLL $1,000, MLL $2,000, max 5 contracts / 10 total on the 50K legacy profile, 14:45–17:00 CT no-entry + 15:10 CT flatten) is not configurable.

## 4. What each flag does (one line each)

| key | meaning |
|---|---|
| session.gateMode | SCORING: M3 passes in every session except NO_ENTRY (15:45–18:00 ET) and WEEKEND; prime killzones add tier/size. BLOCKING: pre-V5 NY-killzone-only gate (A/B). |
| session.allSessions | all-sessions entry decoupled from scalp mode |
| backfill.days | 1m history replayed at start (LIVE real, SIM synthetic) so bias/levels are warm |
| bias.vote.mode / voteRule / source | VOTE + ADAPTIVE (3-of-4, 2-of-3, warm pair) on the dealing RANGE direction; LEGACY/STRICT_3OF4/STRUCTURE for A/B |
| bias.hysteresis / neutralGraceBars | a NEUTRAL 15m read is a wobble for up to 3 reads, not a kill |
| pd.gate.mode | M2b premium/discount vs the dealing-range equilibrium is BLOCKING (LOG for A/B) |
| displacement.atrMult / bodyPct / recentBars | 5m displacement = range ≥ 1.2×ATR14 and body ≥ 50 % (17.9 % of real 5m bars, 85 % of real impulses) |
| fvg.linkBars | the FVG may form within 3 bars of the displacement, either detector family |
| ote.anchorMode / fib* | OTE band = 0.618–0.786 of the day's dealing range (chart parity: G1 = [30605.50, 30673.00]) |
| bias.range.window / minLegTicks | AUTO: from 09:30 ET the dealing range is the RTH impulse leg once it spans ≥ 400 ticks (100 MNQ pts), else the session-day range (G1 and G2 parity); SESSION_DAY / RTH_FIRST for A/B |
| ote.pdArraySource / obLookbackBars | ICT_OB: the M7 PD array at the in-band sweep is the last opposite-close bar within 5 bars before the sweep (ICT order block); SWEEP_BAR = 05.2 behaviour |
| ote.entryModel | IMPULSE_LEG: the range's impulse leg satisfies M5/M6; the setup arms on a sweep inside the band reaching 0.705 and alarms on the rejection. POST_SWEEP = pre-V5 sequence (A/B and fallback) |
| ote.windowBars / mss.freshBars | OTE_ARMED → emit window; MSS freshness |
| ote30m.mode | M7b 30m-chart confluence is SCORING (GATE re-blocks) |
| risk.rrFloor / rrFloor.scalp / rrCeiling | the ONE RR band read by the validator AND the risk engine (floor vs T1, ceiling vs final target) |
| size.minMicros / preferredMicros / maxMicros | size = floor(risk$ / (stopTicks × tickValue)) clamped [1, 20]; never above the $ budget; the killzone boost never exceeds the budget |
| warmup.timeoutSeconds | warmup completes when every required feed has ticked or after 120 s with a WARN |
| setup.expiry.* | phase budgets after the sweep (60 / 60 / 240 min); `setup.expiryAnchor=SWEEP_DONE_TOTAL` restores the old single 60-min budget |
| setup.rearmAfterClose | re-arm after the position closes in every target model (false = one trade per window) |
| news.blockWithoutCalendar | no calendar ≠ blackout |
| entry.counterTrendScalp (+ entry.counterTrend.*) | OPT-IN, default false (= byte-identical engine). true: short a HIGH sweep inside the PREMIUM OTE band (0.618–0.786 from the low) of a BULLISH dealing range back to equilibrium (T1) / the top of the discount band (final), mirror for longs; IMPULSE_LEG trigger (PD array at the sweep + rejection close); only in `sessions` (NO_ENTRY/WEEKEND never), range ≥ `minRangeTicks`, $ risk = budget × `maxRiskFraction`, ≤ `maxPerDay`, one position per symbol (a with-trend setup may arm while it is open, emits once it is flat). Signals carry `STDV_OTE_CT:`; decisions are GateDecisionEvent gate `CT`. Tape: A-05.8 |
| range.ltf.enabled (+ range.ltf.*) | OPT-IN, default false (= byte-identical engine). true: a SECOND full setup machine per symbol on the LOWER-TIMEFRAME dealing range = the most recent confirmed 5m fractal swing leg >= `minLegTicks` (120 = 30 MNQ pt; `.MES` / `.MGC` overrides), rebuilt on every qualifying swing, extended on new extremes, flipped only by an opposite leg >= minLegTicks (or a 1m close beyond its origin); own bias (= its direction), own equilibrium / premium-discount (M2b) / OTE band / sweep + ICT OB + rejection, T1 = LTF equilibrium, T2 = LTF far edge; the same M1..M9 chain. `gating=HTF_ALIGNED` (comparison) also requires LTF direction = HTF bias and the entry on the HTF discount/premium side. One position per symbol (first machine to emit holds it), `maxPerDay` 4, `riskFraction` 1.0 of the budget, `sessions` = every open window. Signals carry `STDV_OTE_LTF:`; `SetupContext.machine` = HTF / LTF. Tape: A-05.9 |
| strategy.legacyFallback | a non-{MNQ,MES,MGC} symbol fails fast instead of silently running the legacy strategy |

## 5. Reading the boot table and the gate histogram

- Boot prints `EFFECTIVE ENGINE CONFIG` (key | value | source) then one line per consequence: `STRATEGY: StdvOteRunnerStrategy …`, `SESSION GATE: SCORING …`, the expiry anchor, the entry model. If a line says BLOCKING/LEGACY/POST_SWEEP you are not in the proven configuration.
- `GET /api/status → telemetry.gateCounts` counts gate decisions since boot; `GET /api/setup → gateDecisions` lists the last 200 with `session, state, gate, reason, numberA, numberB` — "which gate killed it at 15:05 ET" is the row whose candleTime is 15:05 ET.
- Offline: `AUTOPSY_DIR=<abs>/trading-engine/src/test/resources/tape AUTOPSY_CONFIG=A AUTOPSY_OUT=<abs> ./gradlew :trading-engine:cleanTest :trading-engine:test --tests '*FunnelAutopsyHarness*'` writes `summary.md` (per-session counts, the holding-gate histogram whose rows sum to the session's bars, deaths, the trade log) and `candles.csv` (one row per bar). `AUTOPSY_PROPS="key=v;key=v"` overrides; `AUTOPSY_TRANSCRIPT="2026-09-28T13:30,2026-09-28T15:45"` prints a bar-by-bar transcript.

## 6. Pre-LIVE checklist

1. `~/.topstep/credentials.properties` authenticates (`errorCode 3` on 2026-09-28 = key rejected → refresh it in TopstepX). LIVE cannot connect until then.
2. Boot table shows the proven values (Section 3) and `SESSION GATE: SCORING`.
3. Warmup line: every required feed (MNQ, MES SMT, MGC) ticked, or the WARN names the missing one; `telemetry.warmupDroppedSignals` stays 0 after warmup.
4. Flatten: 14:45 CT no-entry (session gate) and the execution-path safety net + 15:10 CT flatten are both active (boot line + `ExecutionPathSafetyTest`).
5. Kill switch: `POST /api/control/killswitch`; flatten: `POST /api/control/flatten`.
6. Run SIM first through the API and watch `/api/setup` for at least one full session.

## 7. A/B commands (comparison backtests)

```bash
AUTOPSY_PROPS="session.gateMode=BLOCKING"                       # pre-V5 NY-killzone-only time gate
AUTOPSY_PROPS="bias.voteRule=STRICT_3OF4"                        # strict vote
AUTOPSY_PROPS="bias.vote.mode=LEGACY"                            # 15m/30m structure bias
AUTOPSY_PROPS="ote.entryModel=POST_SWEEP"                        # pre-05.2 sequence
AUTOPSY_PROPS="setup.expiryAnchor=SWEEP_DONE_TOTAL"              # single 60-min budget
AUTOPSY_PROPS="setup.rearmAfterClose=false"                      # one trade per window
AUTOPSY_PROPS="bias.range.window=SESSION_DAY"                   # pre-05.6 range window
AUTOPSY_PROPS="ote.pdArraySource=SWEEP_BAR"                      # pre-05.5 PD-array rule
AUTOPSY_PROPS="scalp.enabled=true"                               # scalp target model (1R cap)
AUTOPSY_PROPS="stdvOte.enabled=false"                            # legacy IctHighConfluence strategy
AUTOPSY_PROPS="entry.counterTrendScalp=true"                     # opt-in counter-trend scalp (A-05.8)
AUTOPSY_PROPS="range.ltf.enabled=true"                           # opt-in LTF dealing-range machine (A-05.9)
AUTOPSY_PROPS="range.ltf.enabled=true;range.ltf.gating=HTF_ALIGNED"  # LTF machine, HTF-aligned comparison
```

## 8. Results on the proven tape (final matrix v5, Main 55994e6)

| session | days | signals | fills | closed | W/L | sum R | P&L |
|---|---|---|---|---|---|---|---|
| ASIA | 7 | 3 | 3 | 3 | 2/1 | +0.54 | +$50.00 |
| LONDON | 6 | 1 | 1 | 1 | 1/0 | +0.81 | +$87.50 |
| PRE_NY | 6 | 0 | 0 | 0 | — | — | no in-band sweep on the tape |
| NY_AM | 6 | 1 | 1 | 1 | 1/0 | +1.50 | +$253.50 (G2) |
| NY_LUNCH | 6 | 1 | 1 | 1 | 1/0 | +1.00 | +$156.00 |
| NY_PM | 6 | 1 | 1 | 1 | 1/0 | +1.33 | +$304.00 (G1) |
| PRE_ASIA | 5 | 0 | 0 | 0 | — | — | no in-band sweep on the tape |
| NO_ENTRY / WEEKEND | — | 0 | 0 | 0 | — | — | must be 0 |

Total: 7 signals, 7 fills, 7 closed, 6W/1L, +5.18R, +$851.00; risk denials 0; worst day +$137.50 vs DLL $1,000; max drawdown $67.50 vs MLL $2,000; max order 5 vs cap 5; zero positions inside 15:45–18:00 ET; zero entries in NO_ENTRY/WEEKEND. Baseline before V5: 0 signals in every configuration. Seven days is a thin sample — see the honest assessment in `docs/reports/autopsy_v5/A-08.md`.
