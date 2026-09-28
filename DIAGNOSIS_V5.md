# DIAGNOSIS_V5 — Funnel Autopsy (Agent 00, Fable)

Date: 2026-09-28. Repo @ Main `2c43df9`. Branch `fix/agent-00-v5-funnel-autopsy`.
Master document: `TRADE_FLOW_UNBLOCK_MASTER_PROMPT_V5.txt` (Section 3, Agent 00).

**Headline.** The engine takes ZERO trades on the owner's real tape in every
configuration that exists today. This is reproduced offline, bar by bar, with
the REAL runner path (StdvOteRunnerStrategy → MandatoryConfluenceValidator →
PropFirmRiskEngine → ExecutionEngine). Five configurations were replayed over
7,830 real 1-minute MNQ bars (2026-09-21 00:00 ET → 2026-09-28 16:22 ET, MES
as the SMT feed): 0 signals, 0 orders, 0 fills in all five. The machine reached
OTE_ARMED 1–2 times in 7 days and never emitted. The killers are not one gate;
they are a chain of eleven independent defects, each of which alone is enough
to produce zero trades. Every one is named below with the number that proves it
and the Opus agent that owns the fix.

---

## 0. Tape, harness, and how to re-run

| item | value |
|---|---|
| Tape | `~/topstep-trading/tape/real_MNQ_1m.json` + `real_MES_1m.json` (7,830 bars each; NQ / ES front-month 1m from a public feed, price-identical to MNQ/MES) |
| Coverage (ET) | 2026-09-21 00:00 → 2026-09-28 16:22; 5 full RTH days + Sun 09-27 evening; 11 gaps > 1 min (weekend + halts) |
| Golden-case sanity | tape NY-AM 09-28 high **30759.25** / low **30356.75** = owner's fib anchors 30759.25 / 30356.50 (1 tick); 09-25 NY-AM high 30926.50 vs owner's ~30930.75 |
| Harness | `trading-engine/src/test/java/com/topstep/trading/strategy/stdvote/FunnelAutopsyHarness.java` |
| Run | `AUTOPSY_DIR=~/topstep-trading/tape AUTOPSY_CONFIG=A AUTOPSY_OUT=~/topstep-trading/autopsy/A AUTOPSY_TRANSCRIPT="2026-09-28T13:30,2026-09-28T15:45" ./gradlew :trading-engine:cleanTest :trading-engine:test --tests '*FunnelAutopsyHarness*'` |
| Outputs | `candles.csv` (one row per 1m bar: state before/after, holding gate, bias + vote, kzOpen, sweep, raidScore, disp/fvg/mss, OTE band, entry/stop/rr/size, lastGateFailed, stall, death, signal, risk decision, orders, positions, closed trades), `summary.md`, `transcript.txt` |
| Persisted copies | `~/topstep-trading/autopsy/{A,B,C,D,E}/` (the `build/` copies are wiped by `gradle clean`) |

**Topstep history API is BLOCKED**: `~/.topstep/credentials.properties` authenticates
with `errorCode=3` (rejected) as of 2026-09-28 13:31 ET; the engine's own logs
show the same key authenticating successfully on 2026-09-20..22. ⛔ Owner action:
refresh the TopstepX API key in `~/.topstep/credentials.properties` (the LIVE
runner cannot connect until then either). The public NQ/ES 1m feed was used
instead; it matches the owner's chart to the tick (table above).

### The five configurations

| cfg | meaning | flags |
|---|---|---|
| **A** | bootRun-equivalent — what `java -jar api-backend.jar` / `./gradlew bootRun` runs with no flags | none (legacy target model, NY killzones ∪ Silver Bullet only) |
| **B** | the FunnelReplayHarness / PR #139-#150 "it works" flags | `scalpMode.enabled=true scalp.minRaidScore=5 bias.hysteresis.enabled=true` |
| **C** | the "intended all-sessions" config | `scalpMode.enabled=true scalp.allSessions=true bias.hysteresis.enabled=true bias.vote.mode=VOTE` |
| **D** | scalp mode with its own defaults (raid floor 6) | `scalpMode.enabled=true` |
| **E** | the owner's ACTUAL running JVM (pid 14220, up since 2026-09-22, `java -jar api-backend-1.0.0-SNAPSHOT.jar`) | `backfill.days=7 stdvote.displacement.recentBars=12 stdvote.displacement.atrMult=1.2 stdvote.displacement.bodyPct=0.55` |

Note on **E**: `stdvote.displacement.recentBars` does not exist on Main — it is
introduced by the still-OPEN PR #151 (`fix/contract-resolution-targeted-search`,
27 files, +3,836). The owner's running jar was built from that branch, not from
Main. Main ignores the flag (window hard-coded to 5 detector bars).

---

## 1. PF-01..PF-12 re-verification on Main @ 2c43df9

| PF | verdict | evidence (file:line) |
|---|---|---|
| PF-01 strategy selection / silent legacy fallback | **CONFIRMED** | `StdvOteFactory.java:40-62` — non-{MNQ,MES,MGC} symbol → `IctHighConfluenceStrategy` with a println WARN; `isEnabled()` defaults true (:65-69) |
| PF-02 strict sequential AND-chain | **CONFIRMED** | `StdvOteStrategy.java` state hooks (recordManipulationLeg :332, recordSweep :359, recordDisplacement :379, recordMss :392, recordOteImpulse :404, tryEmit :456); `MandatoryConfluenceValidator.validateStdvOteStrict` :461-617 short-circuits M1→M2→M2b→M3→M4→M5→M6→M7→M7b→M8→M9 |
| PF-03 M3 NY-only in the default path | **CONFIRMED** | `StdvOteRunnerStrategy.isInstrumentKillzone` :1214-1225 (legacy = KillzoneClock NY AM/PM ∪ SilverBullet; MGC + London 03:00–12:00). Tape: cfg A `kzOpen` bars = ASIA 0 / PRE_NY 0 / PRE_ASIA 0 / LONDON 354 (only the 03:00–04:00 SB hour) |
| PF-04 flags never set at runtime | **CONFIRMED + WORSE** | `api-backend/build.gradle` has no bootRun block; `application.yml` sets nothing; 94 property keys exist (Section 2); the owner does not even use bootRun — pid 14220 runs `java -jar` with 4 flags, one of which (`recentBars`) only exists on an unmerged branch |
| PF-05 warmup guard wall-clock | **CONFIRMED** | `LiveEngineRunner.java:659-660` (`warmupCompletedAt = Instant.now()`), :815 `createdDuringWarmup(signal.getTimestamp() …)`, :826-827 `isStaleSignal(lastTs, Instant.now(), 300s)`; `BaseEvent.java:16` stamps `Instant.now()`; `WarmupGuard.isStaleSignal` returns true when `lastTs == null` (:26-28) so a subscribed symbol with no bar yet drops every signal. No timeout, no per-symbol readiness (`:625-660`) |
| PF-06 seven deny paths / size floor | **CONFIRMED + one correction** | `PropFirmRiskEngine.evaluate` :64-192: DLL, MLL, no room, maxTrades/day, maxConsecutiveLosses, invalid stop, invalid risk, "Risk too high per contract", max total contracts, "R:R too low", "R:R too high (unrealistic)". Correction: the risk engine IGNORES `signal.getQuantity()` and re-sizes from `riskPerTrade / $risk` (:127-158) — so the [5,20] strategy floor and the risk engine's 1..maxContracts sizing are two unrelated sizers |
| PF-07 two RR bands | **CONFIRMED with numbers** | legacy: validator M7 band `[2.0, +inf)` (`RiskLimits.java:244-245`) vs risk engine `[3.0, 6.0]` (`RiskLimits.topstep50k` :123-124) → every legacy signal with RR ∈ [2,3) passes M7 and is DENIED by risk. Scalp: `[0.8,1.5]` in both (:topstep50kScalp) but the legacy `-2σ` target is replaced by a 1R-capped target |
| PF-08 3-of-4 vote deadlock | **CONFIRMED + root cause found** | `BiasVoteEngine.java:275-277` (≥3 aligned else NEUTRAL). **V2 (AMD) can NEVER vote**: `DailyAmdCycleTracker.update` folds the candle into sessionHigh/Low (:107-108) before `processAccumulation` tests `close < lowRef` (:152) — impossible by construction → V2 always ABSTAIN → VOTE mode is 3-of-3. Tape cfg C: first non-NEUTRAL bias at bar **1,730** (2026-09-22 06:00 ET, 29 h after start); bias non-NEUTRAL on only 231/2179 ASIA bars |
| PF-09 KillzoneClock dual zone | **DRIFTED (benign today)** | `KillzoneClock.java:19-20` declares both zones but every method uses `newYorkZone`; `chicagoZone` is dead. The CT block in `StdvOteRunnerStrategy.allSessionEntryWindow` :1293 uses `America/Chicago` correctly. Still: three time systems (KillzoneClock ET LocalTime, SilverBulletClock, runner CT) with no single classifier and no DST table test |
| PF-10 re-arm / expiry loop | **CONFIRMED + two new facts** | (a) expiry counts from **BIAS_SET** (`StdvOteStrategy.java:323` sets `createdAtBar` only there; check :240-246) — 40 detector bars × 5 = 200 min for the WHOLE path, despite the "without progress" text; (b) `canRearm` :1059-1063 requires `inKillzone` in BOTH modes → in legacy the machine sits INVALIDATED from the last NY-PM invalidation until the next NY-AM open. Tape cfg A: `INVALIDATED-await-rearm-kzClosed` = **94.5 % of ASIA bars, 100 % of PRE_NY, 100 % of PRE_ASIA, 54.7 % of LONDON** |
| PF-11 telemetry not the owner's runtime | **CONFIRMED** | FunnelTelemetry / ProfileSimulator / ConfluenceService exist; the owner's api on :8080 (pid 14220) did not answer `/api/status` on 2026-09-28 13:50 ET (HTTP 000 / connection refused) |
| PF-12 G1 died inside a killzone | **CONFIRMED — exact gates named** | see Section 4: M2 direction (bias BULLISH 13:45–15:00 while the setup is a short), then M5 (displacement 15:00 bar body 53 % < 65 %; 14:35 bar range 1.495× < 1.5×), then M5 FVG linkage (15:35 displacement has no 3-bar gap: c1.low 30589.00 > c3.high 30593.50 is false) |

---

## 2. Config diff table (94 keys)

Grep: `grep -rn "System.getProperty\|Integer.getInteger\|Boolean.getBoolean\|Long.getLong" trading-engine/src/main api-backend/src/main` → 47 direct `System.getProperty` sites + constant-named keys + templated keys = **94 distinct keys**.
`api-backend/src/main` reads only `user.home`. **Forwarded by `./gradlew bootRun`: NONE** (no bootRun block, no `systemProperty`, no `jvmArgs`; `application.yml` sets none; no `gradle.properties`; no `engine.properties`).
Forwarded into the TEST JVM by `trading-engine/build.gradle:47-64`: 17 keys (marked Y).

| # | key | file:line | type | code default | controls | test fwd | bootRun |
|---|---|---|---|---|---|---|---|
| 1 | backtest.commissionPerSide | backtest/BacktestCosts.java:27 | double | 1.55 | backtest commission | N | N |
| 2 | backtest.slippageTicks | backtest/BacktestCosts.java:31 | int | 1 | backtest slippage | N | N |
| 3 | chart.minLegTicks.SYM | chart/ChartEngine.java:113; PremiumDiscountEvaluator.java:102 | int | ctor / 40 | chart min leg; PD min range base | N | N |
| 4 | chart.swingStrength.SYM | chart/ChartEngine.java:114 | int | ctor | chart fractal strength | N | N |
| 5 | chart.zoneExpiryBars.SYM | chart/ChartEngine.java:115 | int | ctor | chart zone expiry | N | N |
| 6 | chart.anchorCompare | chart/ChartEngine.java:158 | bool | false | log both anchor modes | Y | N |
| 7 | chart.anchorMode | chart/ChartEngine.java:165 | enum | FRACTAL_LEG | OTE leg anchoring | Y | N |
| 8 | chart.anchorMode.SYM | chart/ChartEngine.java:164 | enum | → #7 | per-symbol anchoring | N | N |
| 9 | chart.oteBand | chart/ChartEngine.java:173 | band | null | OTE band | Y | N |
| 10 | chart.oteBand.SYM | chart/ChartEngine.java:172 | band | → #9 | per-symbol band | N | N |
| 11 | confluence.weight.KEY (17 keys) | confluence/ConfluenceField.java:65 | double | 1.0/2.0/3.0 | confluence weights | N | N |
| 12 | confluence.nearTicks | confluence/ConfluenceService.java:39 | int | 40 | "near" distance | N | N |
| 13 | confluence.raidScoreFloor | confluence/ConfluenceService.java:41 | int | 5 | raid-score confluence floor | N | N |
| 14 | confluence.recentMinutes | confluence/ConfluenceService.java:43 | int | 120 | recency window | N | N |
| 15 | mock.candleIntervalMs | connector/MockConnector.java:30 | long | 5000 | SIM candle cadence | N | N |
| 16 | mock.virtualClock | connector/MockConnector.java:32 | bool | false | SIM virtual timeline | N | N |
| 17 | mock.virtualMinutes | connector/MockConnector.java:34 | long | 2000 | virtual timeline offset | N | N |
| 18 | sim.warmBoot | connector/MockConnector.java:43 | bool | true | SIM synthetic warm boot | N | N |
| 19 | sim.tape | connector/SimChoreographyTape.java:57 | enum | CHOREOGRAPHY | SIM tape vs RANDOM | N | N |
| 20 | sim.backfill.seed | connector/SimWarmBoot.java:36 | long | 42 | SIM RNG seed | N | N |
| 21 | backfill.days | connector/SimWarmBoot.java:40; TopstepConnector.java:154 | int | 3 [1,7] | 1m backfill depth | N | N (owner passes 7 by hand) |
| 22 | htf.backfill.days | connector/SimWarmBoot.java:163; TopstepConnector.java:117 | int | 30 [7,90] | HTF backfill depth | N | N |
| 23 | topstep.allowNonSimulated | connector/TopstepConnector.java:1704 | bool | false | allow real-money account | N | N |
| 24-27 | topstep.apiUrl / username / apiKey / accountId | connector/TopstepCredentials.java:52-55 | String | null → file → env | credentials | N | N |
| 28 | ictlib.enabled | ictlib/IctLibConfig.java:115 | bool | true | ictlib master | N | N |
| 29-51 | ictlib.displacement.meanLen / wickRatioMax, retain.*, fvg.mode, vi.projectBars, pool.*, ob.swingLen, ob.useBody, structure.pivotLeft/Right/historyCap/mssAgreeWindow | ictlib/IctLibConfig.java:116-138, :247 | mixed | 5 / 0.36 / 50 / FVG / 10 / 5 / 6 / 3 / 3 / 2 / 5 / 2.5 / 3 / 50 / 4 / 10 / 10 / true / 5 / 5 / 1 / 200 / 5 | ICT library tuning (observation only) | N | N |
| 52 | stdvote.symbol | LiveEngineRunner.java:72; SimEngineRunner.java:46 | String | MNQ | single-symbol primary | N | N |
| 53 | stdvote.smt | LiveEngineRunner.java:74 | String | MES | SMT pair | N | N |
| 54 | stdvote.multiInstrument | LiveEngineRunner.java:90; SimEngineRunner.java:58 | bool | true | multi-instrument engine (MNQ+MGC active, MES SMT) | N | N |
| 55 | notify.discord.enabled | LiveEngineRunner.java:1788 | bool | true | Discord alerts | N | N |
| 56 | bias.vote.mode | BiasVoteEngine.java:48 (:103) | LEGACY/LOG/VOTE | **LOG** | which bias feeds recordHtfBias | **N** | N |
| 57 | bias.v1.includeH4 | BiasVoteEngine.java:57 | bool | false | V1 H4 consult | N | N |
| 58 | ote30m.confluence | Ote30mConfluenceGate.java:42 | OFF/LOG/GATE | LOG | M7b | N | N |
| 59 | ote30m.acceptArmed | Ote30mConfluenceGate.java:44 | bool | false | M7b accepts ARMED | N | N |
| 60 | ote.stats.file | OteAgreementStatsStore.java:47 | path | data/ote_agreement_stats.jsonl | stats output | N | N |
| 61 | pd.gate.mode | PremiumDiscountEvaluator.java:62 | enum | LOG | M2b mode | N | N |
| 62 | pd.eqBandTicks | PremiumDiscountEvaluator.java:64; BiasVoteEngine.java:104 | int | 2 | eq band | N | N |
| 63 | pd.minRangeTicks | PremiumDiscountEvaluator.java:67 | int | 2×minLegTicks (80) | min dealing range | N | N |
| 64 | pd.minRangeTicks.SYM | PremiumDiscountEvaluator.java:103 | int | → #63 | per-symbol | N | N |
| 65 | pd.d1MinBars | PremiumDiscountEvaluator.java:69 | int | 10 | D1 depth before R0 governs | N | N |
| 66 | scalpMode.enabled | ScalpConfig.java:56 (:117) | bool | **false** | scalp master switch (risk profile, windows, re-arm, sizer, brackets) | Y | N |
| 67 | scalp.breakevenAtHalfR | ScalpConfig.java:59 | bool | true | breakeven at +0.5R | N | N |
| 68 | scalp.minTargetClearanceTicks | ScalpConfig.java:63 | int | 2 | scalp target clearance | N | N |
| 69 | scalp.candidateWindowR | ScalpConfig.java:67 | double | 1.5 | scalp target window | N | N |
| 70 | scalp.minRaidScore | ScalpConfig.java:74 | int | **6** | scalp raid floor (fallback score is 5 → every starved sweep rejected) | Y | N |
| 71 | scalp.rearmCooldownBars | ScalpConfig.java:78 | int | 5 | re-arm cooldown (both modes) | N | N |
| 72-73 | scalp.londonPrimeStartEt / EndEt | ScalpConfig.java:82-86 | HH:mm | 03:00 / 05:00 | MGC London prime | N | N |
| 74 | scalp.sizerSafetyCushion | ScalpConfig.java:90 | double | 200 | sizer cushion | N | N |
| 75 | scalp.allSessions | ScalpConfig.java:100 (:182) | bool | **true** | all-sessions entry — only consulted when scalpMode.enabled (runner :1214-1216, :1255) | **N** | N |
| 76 | scalp.killzoneSizeBoost | ScalpConfig.java:110 | double [1,2] | 1.5 | prime-KZ size multiplier | N | N |
| 77 | stdvOte.enabled | StdvOteFactory.java:27 | bool | true | StdvOte vs legacy | N | N |
| 78 | stdvote.symbols.active | StdvOteMultiInstrumentEngine.java:59 | CSV | MNQ,MGC | active symbols | N | N |
| 79 | stdvote.symbols.smt.SYM | StdvOteMultiInstrumentEngine.java:62 | String | MNQ→MES | SMT pair | N | N |
| 80 | stdvote.detectorTimeframe | StdvOteRunnerStrategy.java:262 | 1/3/5/15 | 5 | detector bar TF | Y | N |
| 81 | stdvOte.setupExpiryBars | StdvOteRunnerStrategy.java:287 | int | 40 (×5 min) | setup expiry from BIAS_SET | Y | N |
| 82 | stdvOte.oteWindowBars | StdvOteRunnerStrategy.java:291 | int | 8 (×5 min) | OTE window | Y | N |
| 83 | stdvOte.mssFreshBars | StdvOteRunnerStrategy.java:296 | int | 30 (×5 min) | MSS freshness | Y | N |
| 84 | stdvOte.entryTimeoutBars | StdvOteRunnerStrategy.java:307 | int | oteWindow×2 | unfilled-entry timeout (scalp only) | N | N |
| 85 | stdvOte.stopBufferTicks | StdvOteRunnerStrategy.java:130 | int | 4 | stop buffer | N | N |
| 86 | stdvOte.reactionWickTicks | StdvOteRunnerStrategy.java:135 | int | 2 | OTE reaction wick | N | N |
| 87 | stdvote.displacement.atrMult | StdvOteRunnerStrategy.java:431 | double | 1.5 | displacement range × ATR | Y | N (owner: 1.2) |
| 88 | stdvote.displacement.bodyPct | StdvOteRunnerStrategy.java:432 | double | 0.65 | displacement body % | Y | N (owner: 0.55) |
| 89 | stdvote.displacement.recentBars | (PR #151 only) | int | 5 hard-coded on Main | displacement recency | N | N (owner: 12 — ignored on Main) |
| 90 | stdvOte.rearmOnInvalidated | StdvOteRunnerStrategy.java:531 | bool | true | legacy re-arm | N | N |
| 91 | bias.hysteresis.enabled | StdvOteStrategy.java:194 | bool | **false** | NEUTRAL grace | Y | N |
| 92 | bias.neutralGraceBars | StdvOteStrategy.java:198 | int [1,4] | 2 | grace length | Y | N |
| 93 | trade.profile | trade/TradeProfile.java:38 | enum | STRICT | profile | Y | N |
| 94 | profile.sim.file | trade/ProfileSimulator.java:48 | path | data/profile_sim.jsonl | sim output | Y | N |

**Findings routed to Agent 01 from this table:** every row is a bootRun/harness
mismatch by construction (bootRun forwards nothing). The ones that change
trading behaviour: #56 (bias.vote.mode is not even forwardable to tests), #66,
#70, #75 (allSessions is dead unless scalp mode), #87-89 (the owner's live
flags), #91-92, #21. Two keys the owner relies on cannot reach the engine the
way they run it (#89 does not exist on Main; #75 is ignored in legacy mode).

---

## 3. Gate-death histograms (5 configs × sessions)

Definition: for every 1m candle the harness records the ONE gate that is
holding the machine on that bar (IDLE → `M2-bias-NEUTRAL`; BIAS_SET →
`MANIP-no-leg`; MANIP_DONE → `M4-no-sweep`; SWEEP_DONE → `M5-<stall>`;
DISPLACED → `M6-no-MSS`; MSS_CONFIRMED → `M7-<stall>`; OTE_ARMED → the
validator's failed gate or `sizer-standdown`; INVALIDATED → `await-rearm`
(+`-kzClosed` when the killzone gate also blocks the re-arm)). Each session row
sums to that session's bar count; the sum of all rows is 7,830 in every config.

Bars per session: ASIA 2179 · LONDON 2160 · PRE_NY 540 · NY_AM 900 · NY_LUNCH 540 · NY_PM 810 · PRE_ASIA 290 · NO_ENTRY 410 · WEEKEND 1.

### C-A — bootRun-equivalent (owner's reality). Signals 0 / orders 0 / fills 0

| session | M2 NEUTRAL | MANIP no-leg | M4 no-sweep | M5 no-recent-disp | M5 wrong-dir | M5 no-FVG | M5 consumed | M6 no-MSS | M7 no-reaction | M7 not-armed | GATE-M3 | INVALIDATED (kz open) | INVALIDATED (kz closed) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ASIA | 120 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | **2059** |
| LONDON | 8 | 6 | 62 | **550** | 70 | 149 | 0 | 126 | 0 | 0 | 0 | 7 | **1182** |
| PRE_NY | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | **540** |
| NY_AM | 77 | 2 | 110 | **249** | 0 | 48 | 10 | **216** | 56 | 0 | 0 | 38 | 94 |
| NY_LUNCH | 0 | 0 | 0 | 161 | 25 | 38 | 0 | 73 | 81 | 0 | 0 | 7 | 155 |
| NY_PM | 54 | 15 | 111 | **411** | 43 | 20 | 0 | 0 | 60 | 0 | 0 | 21 | 75 |
| PRE_ASIA | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | **290** |
| NO_ENTRY | 0 | 0 | 0 | 54 | 30 | 60 | 0 | 0 | 59 | 2 | 41 | 0 | 164 |

Top-3 per session: ASIA await-rearm-kzClosed 94.5 % / M2 5.5 % · LONDON await-rearm-kzClosed 54.7 % / M5-no-recent-displacement 25.5 % / M5-no-FVG 6.9 % · PRE_NY await-rearm-kzClosed 100 % · NY_AM M5-no-recent-displacement 27.7 % / M6-no-MSS 24.0 % / M4-no-sweep 12.2 % · NY_LUNCH M5 29.8 % / await-rearm-kzClosed 28.7 % / M7-no-reaction 15.0 % · NY_PM M5-no-recent-displacement 50.7 % / M4 13.7 % / await-rearm 9.3 % · PRE_ASIA await-rearm-kzClosed 100 %.
State arrivals over 7 days: BIAS_SET 12, MANIP_DONE 16, SWEEP_DONE 20, DISPLACED 4, MSS_CONFIRMED 4, OTE_ARMED **1**, IN_TRADE 0. Deaths: HTF bias flip 7, HTF bias became NEUTRAL 8, expired 6, impulse origin violated 1, OTE window expired 1.

### C-B — harness flags (scalp, raid floor 5, hysteresis). Signals 0

| session | M2 | MANIP | M4 | M5 no-recent | M5 wrong-dir | M5 no-FVG | M6 | M7 no-reaction | M7 not-armed | sizer stand-down | GATE-M7 | INVALIDATED |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ASIA | 128 | 195 | 151 | **1019** | 136 | 93 | 100 | 250 | 13 | 0 | 0 | 94 |
| LONDON | 0 | 232 | 69 | **1070** | 116 | 239 | 129 | 212 | 0 | 0 | 0 | 93 |
| PRE_NY | 69 | 146 | 15 | 179 | 0 | 0 | 0 | 23 | 0 | **41** | 0 | 42 |
| NY_AM | 8 | 147 | 84 | 297 | 35 | 70 | 148 | 56 | 0 | 0 | 0 | 45 |
| NY_LUNCH | 0 | 0 | 68 | 183 | 45 | 35 | 82 | 89 | 0 | 0 | 0 | 38 |
| NY_PM | 8 | 83 | 58 | **412** | 110 | 50 | 0 | 60 | 0 | 0 | 0 | 29 |
| PRE_ASIA | 0 | 67 | 77 | 74 | 27 | 0 | 28 | 5 | 0 | 0 | 0 | 12 |
| NO_ENTRY | 0 | 15 | 14 | 100 | 40 | 29 | 0 | 76 | 0 | 0 | 41 | 95 |

State arrivals: BIAS_SET 14, MANIP_DONE 39, SWEEP_DONE 43, DISPLACED 6, MSS_CONFIRMED 13, OTE_ARMED **2**, IN_TRADE 0. The two OTE_ARMED episodes: (1) 2026-09-21 16:01 ET (NO_ENTRY) — armed but `M7: no PD array in OTE band` for 41 bars then `OTE window expired`; (2) **2026-09-24 08:45 ET (PRE_NY) — armed with EVERY gate satisfied (`lastGateFailed == null`) for 41 bars and never emitted: `scalpSize()` returned 0 (sizer stand-down below the 5-micro floor), so `tryEmit` was never called; expired at 09:26.** This is D-07 on tape.

### C-C — intended all-sessions with bias.vote.mode=VOTE. Signals 0

| session | M2 NEUTRAL | MANIP | M4 | INVALIDATED |
|---|---|---|---|---|
| ASIA | **1948** | 0 | 231 | 0 |
| LONDON | **1772** | 255 | 105 | 28 |
| PRE_NY | 451 | 74 | 1 | 14 |
| NY_AM | 579 | 120 | 180 | 21 |
| NY_LUNCH | 443 | 0 | 90 | 7 |
| NY_PM | 593 | 90 | 120 | 7 |
| PRE_ASIA | 210 | 0 | 60 | 20 |
| NO_ENTRY | 249 | 15 | 146 | 0 |

The vote NEVER reaches SWEEP_DONE in 7 days (arrivals: BIAS_SET 7, MANIP_DONE 10). First non-NEUTRAL vote at bar 1,730 (29 h). All 14 deaths = "HTF bias NEUTRAL beyond grace".

### C-D — scalp mode with its own defaults (raid floor 6). Signals 0

M4-no-sweep holds **80.2 % ASIA / 82.4 % LONDON / 44.8 % PRE_NY / 74.4 % NY_AM / 92.2 % NY_LUNCH / 79.6 % NY_PM / 75.9 % PRE_ASIA**. `[MNQ] SCALP raid-score gate: sweep rejected (score 5 < floor 6)` printed **2,031 times**; SWEEP_DONE reached twice in 7 days.

### C-E — the owner's running flags (atrMult 1.2, bodyPct 0.55) on Main. Signals 0

Same shape as C-A (legacy path): ASIA 94.5 % await-rearm-kzClosed; NY_AM M6-no-MSS 24.0 % / M5-no-recent 17.8 % / M5-no-FVG 13.6 %; NY_PM M5-no-recent 23.0 % / M7-no-reaction 18.0 % / M5-no-FVG 16.7 %. Looser displacement moved bars from `no-recent-displacement` to `no-fvg-for-displacement` and `M7-no-reaction` — the next gate in the chain simply absorbs them. OTE_ARMED 1, IN_TRADE 0.

### Cross-config facts

| fact | number |
|---|---|
| Raid pipeline produced a scored raid | **0** bars with `bestRaid` in the G1 window; over the whole tape `raidScore` = 5 (the fallback base, `currentRaidScore` :1368-1372) on 6,922 sweep-bars and 4 on 315 → the score is never above the floor, so scalp floor 6 rejects everything and M4 "passes" legacy only because fallback == floor |
| 5m bars passing the displacement test (range ≥ 1.5×avg14 AND body ≥ 65 %) | **107 / 1,558 = 6.9 %** (body ≥ 1.5×avg: 4.4 %) |
| Cold start → first non-NEUTRAL legacy bias | bar 120 (2 h) |
| Cold start → first decisive 3-of-4 vote | bar 1,730 (29 h) |
| LevelEngine PDH/PDL on 2026-09-28 | **PDH 30921.75 / PDL 30889.25** — both equal the single 1-contract print at Fri 17:00 ET (o=h=30921.75, l=c=30889.25, v=1); true prior-day = 30999.50 / 30679.00 (Thu 18:00→Fri 17:00). `LevelEngine.getTradingDay` (:460-467) rolls at 17:00 so that lone bar became a whole "day" and overwrote PDH/PDL at Sunday 18:00 (`onDayChange` :150-154) |
| LevelEngine LONDON_HIGH on 09-28 | 30679.00 (engine window) vs owner's LuxAlgo London high **30640.00** (= the 04:00–06:00 ET window on the tape; 30640.0 prints at 04:15 and 07:50) |
| Risk-engine denials | 0 — because 0 signals reached it |

---

## 4. Golden-case transcripts

### G1 — 2026-09-28 NY PM short from the 30,640 OB (owner: SELL 8 @ 30635.75)

Tape facts (ET): NY-AM HH 30759.25 (09:45) → LL 30356.75 (11:00). Retrace: 14:40 bar 30585→30627 (+40, body 88 %); 14:50–14:59 the 1m highs print 30640.00 / 30650.00 / 30640.75 / 30644 / 30649.75 — **the 30640 London high is swept at 14:52–14:58**. Drop: 15:00 5m bar o 30639.25 h 30641.25 l 30597.25 c 30615.75; 15:05 c 30594.50; 15:10 c 30576.75; 15:35 bar 30587.75→30545.25.

Engine (cfg A, identical in E; B differs only by re-arming earlier), from `~/topstep-trading/autopsy/A/transcript.txt`:

```
13:30–13:44  INVALIDATED (lastGateFailed = "HTF bias flip BEARISH -> BULLISH", kzOpen=false)   ← waiting for NY PM KZ to re-arm
13:45  BIAS_SET     bias=BULLISH  vote=BULLISH(3/0/1)                                            ← M2 DIRECTION WRONG: the machine hunts a LONG on the day the owner shorts
13:49  MANIP_DONE   (legacy swing-pair fallback leg)
13:55  SWEEP_DONE   sweep=LOW@30611.25  raidScore=5 (fallback — no raid object)                   ← a LOW sweep for a long; the 30640 HIGH sweep at 14:52 is invisible to a bullish setup
13:55–14:44  M5 no-recent-displacement  (bullish displacement required; none)
14:45–14:59  M5 no-fvg-for-displacement (14:40 bar qualified as bullish displacement: range 45.5 = 1.81×avg, body 88 %; FVG check c1.high(14:30)=30631.75 < c3.low(14:40)=30581.5 FALSE → no FVG)
15:00  INVALIDATED  "HTF bias became NEUTRAL"   vote=NEUTRAL(2/0/2)                             ← hysteresis OFF (cfg A): one NEUTRAL 15m read kills it
15:07  IDLE  (re-armed on cooldown; bias NEUTRAL)
15:15  MANIP_DONE   bias=BEARISH (legacy)  vote=NEUTRAL(2/1/1)                                   ← in VOTE mode (cfg C) this would still be M2-dead
15:21  SWEEP_DONE   sweep=HIGH@30601.50 raidScore=5                                              ← the sweep the engine finally records is the 15:20 wick, not the 30640 London high
15:21–15:39  M5 no-recent-displacement: 15:00 bar range 44.0 = 1.66×avg BUT body 23.5/44.0 = 53 % < 65 %; 15:05 body 74 % but range 0.95×; 15:10 body 82 % but range 0.76×
15:40–15:45  M5 no-fvg-for-displacement: 15:35 bar IS a displacement (range 52.5 = 1.91×, body 81 %) but bearish FVG needs c1.low(15:25)=30589.00 > c3.high(15:35)=30593.50 → FALSE; fvgDetector fallback has no unfilled bearish 5m FVG
15:45  NO_ENTRY session begins (14:45 CT) — the setup is dead in time.
```

Rejecting gates, in order, with the two numbers: **M2** (bias BULLISH vs setup SHORT, 13:45–15:00); **M5-displacement** (15:00 bar body ratio 0.53 < 0.65; 14:35 bar range/avg 1.495 < 1.5); **M5-FVG linkage** (15:35: c1.low 30589.00 vs c3.high 30593.50 — need c1.low > c3.high). Even had M5 passed: the engine's OTE would be built from `impulseTracker` origin = highSinceSweep (≈30605.75) → terminus (≈30541) — a 65-pt post-MSS leg — NOT the owner's 402.75-pt dealing range 30759.25→30356.50, so the band would never be [30605.50, 30673.00]; and the stop (~39 pt) at $2/pt = $78/micro → legacy riskPerTrade $250 → 3 micros (below the strategy's [5,20] band; the risk engine would order 3), scalp riskPerTrade $150 → 1 micro → `scalpSize()` stand-down → no emission at all.

What the engine SHOULD have seen (numbers for Agents 03/04/05): bias BEARISH from the HH→LL impulse of the day (not from the 15m retrace structure, which reads BULLISH during the very retrace the model sells); sweep = 30640.00 raided at 14:52 (penetration 40 ticks at 14:53 high 30650); displacement = 15:00 bar (44 pt, 1.66×ATR) — with a 55 % body rule it passes; MSS = 15:00 close 30615.75 < prior 5m swing low 30600.75 (14:30 low) — no, 15:05 close 30594.50 < 30600.75 → **MSS at 15:05**; OTE band from 30759.25/30356.50 = 0.618 → **30605.42**, 0.786 → **30673.08**; entry at the OB edge ≈ 30635.75; T1 0.5 = 30557.88; RR(T1) = 77.9 / 39.3 ≈ 2.0.

### G2 — 2026-09-25 NY AM (owner's date "09-27" is a Sunday; 09-25 is the prior trading day)

Tape: 09-25 NY-AM high 30926.50 (owner ~30930.75) / low 30684.00 (owner's LL marker 30721.00 is an intraday swing, the session low is 30684). Engine cfg A on 09-25 09:30–12:00: deepest state SWEEP_DONE / DISPLACED; deaths "HTF bias flip" ×3 and "expired" ×3 in NY_LUNCH; no OTE_ARMED. Same three killers as G1 (bias direction from 15m structure, displacement threshold, FVG linkage). Exact per-bar rows: `~/topstep-trading/autopsy/A/candles.csv` rows 6,1xx (ts_ET 2026-09-25 09:30 … 12:00). Agent 04 records the engine's zone numbers after its fix in A-04.

### G3 — 2026-09-28 London 02:00–08:00 ET (all-sessions proof)

Tape: London range **30679.00 (02:xx) – 30531.00 (05:xx)**; the 30581.25 low the owner marks prints at 06:30; the owner's 30547.75 / 30535.00 are not exact prints (nearest 30549.0 06:xx low, 30531.0 05:xx low). Engine cfg A: 100 % of the 360 London bars that day are `INVALIDATED-await-rearm-kzClosed` (except the 03:00–04:00 SB hour) — gating says "no" for the wrong reason (time), not because it evaluated the setup. Cfg B (all-sessions): BIAS_SET/MANIP/SWEEP cycles, M5 no-recent-displacement dominant. Verdict for gating: **a killed-by-time window, not a correct "no setup"** → RC-02.

---

## 5. Sanity checks (Agent 00 task 8)

| check | result |
|---|---|
| EventBus subscription order (SimEngineRunner) | OK: handler subscribed in the constructor (:194) before `eventBus.start()` (:222) and before the first candle (`multiEngine.start()` :247). BUT `EventBus.publish` while `!running` **drops** the event with a WARN (`EventBus.java:146-148`), and the bus is 4 worker threads → handlers run concurrently/out of order |
| SMT correlate in bootRun | MES is subscribed as SMT-only (`StdvOteMultiInstrumentEngine.java:131-136`, :186-188) and routed to `MNQ.onSmtCandle`. Its absence does not NEUTRAL-ise the vote; SMT is only a +2 raid-score bonus and the TIER_4 requirement |
| H4/D1 from cold start | first H4 ≤ 240 bars, first D1 ≤ 1,380 bars; **PDH/PDL only at the first 17:00 ET rollover**; the TIER-2 HTF seed covers H1/H4/D1 only — never M15/M30, which is what the legacy bias reads (`HtfTrendAnalyzer.java:112-113`) |
| multiInstrument=true | two runners (MNQ+MES-SMT, MGC) on one bus, one account, one `riskLimits`; `getTotalContracts()` counts positions not pending orders; scalp `maxTotalContracts=20` = one full-size position blocks the other symbol. In multi mode `SimEngineRunner.onMarketData`/`checkRiskLimits` never run (:381, :457) — DLL/MLL breach never stops SIM; in LIVE multi mode `onMarketData` (:712-777) never runs → day rollover, `executionEngine.onNewCandle`, `checkPriceBreakevenTrigger` are skipped |
| candle time vs wall clock | decision-path `Instant.now()`: `BaseEvent.java:16` (every signal), `LiveEngineRunner.java:659,827`, `HtfTrendAnalyzer.java:235`, `DefaultStrategyContext.java:19`, `EqualLevelDetector.java:400`, `TradingRiskManager.java:344`, `BracketOrderManager.java:87`, `TrailingStopManager.java:70,82,195,339`, all of `news/*` (EventProximityChecker :88,229,246,274; MacroNewsManager :183-433) |
| PositionClosedEvent release in legacy mode | `SimEngineRunner.releaseUnexecutedSignal` (:396-401) publishes it, but the runner subscribes ONLY in scalp mode (`StdvOteRunnerStrategy.java:537-548`) → a legacy signal denied by risk leaves the machine IN_TRADE until `setupExpiryBars` (200 min) |
| ExecutionEngine order TTL | none — an untouched limit rests forever in SIM (`ExecutionEngine.java` has no expiry); scalp `entryTimeoutBars` invalidates the SETUP but never cancels the ORDER (orphan fill possible) |
| P&L units | `ExecutionEngine.java:491,516` compute `priceDiff * qty * tickValue` (points × per-TICK value) — MNQ P&L under-reported 4× (Agent 05 to verify) |

### Swallowed / silent failure points on the signal → order path

| file:line | what | effect |
|---|---|---|
| `connector/MockConnector.java:307` | catch Exception around the whole candle tap + strategy | candle lost, next tick continues |
| `MockConnector.java:136-145` warm-boot loop | no catch; throw → `SimEngineRunner:270` prints + `stop()` which no-ops because `running` is false | zombie engine |
| `StdvOteMultiInstrumentEngine.java:281-313` `dispatchCandle` | no try/catch | a throw in the tap stops the strategy for that candle; :249,:258 `catch (RuntimeException ignored) {}` (shutdown) |
| `connector/TopstepConnector.java:949` (`fetchBars`) | catch Exception, message only, skips `lastBarTimestamp` update | poison-pill: same bars re-delivered every poll |
| `TopstepConnector.java:1094 / 1089` | bar fetch/parse | bar skipped silently |
| `chart/HistoricalBackfill.java:78` | failed chunk | 0-bar backfill still "completes" warmup |
| `event/EventBus.java:146-148, 153-156, 158, 242, 266-267` | not-running drop, queue-full drop, handler exception | a throw inside `handleStrategySignal` skips `releaseUnexecutedSignal` → latch stuck |
| `TopstepConnector.java:350` + finally :352-354 | exception inside fill listener | **fill lost for good** (removed from `pendingOrders`) |
| `TopstepConnector.java:364, 378, 251, 387` | cancel/reject/poll | message-only |
| `LiveEngineRunner.java:1080-1137` | missing bracket manager / invalid prices → `return` | **position left unprotected** |
| `execution/BracketOrderManager.java:316, 384` | stop submit fails | position has no stop |
| `BracketOrderManager.java:401-404` | TP submit fails → cancels the working stop | neither stop nor target |
| `BracketOrderManager.java:516, 550` | breakeven / qty update fails after the old stop was cancelled (:493, :534) | no stop; comment "keep old stop" is wrong |
| `LiveEngineRunner.java:866, 985, 1242, 1576` | cancel-on-upgrade / entry submit / journal / stale cancel | System.err only |
| `connector/OrderListener.java` defaults | `onOrderFilled/onOrderRejected` are no-ops; LiveEngineRunner's listener implements only `onOrderUpdate` | MockConnector-driven fills vanish if ever wired |

---

## 6. Root-cause register

Category legend: CONFIG · TIME · BIAS · SWEEP · DISPLACEMENT-FVG · MSS · OTE · RR · SIZE · WARMUP · RISK · EXEC · WIRING. Fix class per rule R3: **correct** / **re-classify to SCORING** / **re-parameterise**. Δ = expected histogram delta on the 7,830-bar tape.

| RC | symptom (number) | mechanism (file:line) | cat | fix class | owner | Δ expected |
|---|---|---|---|---|---|---|
| **RC-01** | The engine the owner runs is not the engine anyone tested: 94 keys, bootRun forwards 0, owner's jar is from unmerged PR #151 with a flag Main ignores; `scalp.allSessions` is dead unless scalp mode | `api-backend/build.gradle` (no bootRun block); `ScalpConfig.java:182` + runner :1214-1216 | CONFIG | correct (single EngineConfig, boot table, parity) | 01 | prerequisite for every other Δ to be measurable at runtime |
| **RC-02** | Outside NY killzones the machine cannot even re-arm: 94.5 % ASIA, 100 % PRE_NY/PRE_ASIA, 54.7 % LONDON bars are `INVALIDATED-await-rearm-kzClosed` (cfg A); M3 blocks every emission there anyway | `canRearm` :1063 `if (!inKillzone) return false`; `isInstrumentKillzone` :1214-1225; validator M3 :488-492 | TIME | re-classify M3 to SCORING (only NO_ENTRY/WEEKEND block); re-arm must not need a killzone in SCORING mode | 02 | −4,071 kzClosed bars → those sessions enter the funnel; M3 deaths 0 outside NO_ENTRY/WEEKEND |
| **RC-03** | Setup expiry counts from BIAS_SET (200 min total budget), not from SWEEP_DONE; "expired" = 6–16 deaths/config; a manipulation leg that takes >200 min from bias can never arm | `StdvOteStrategy.java:323` (`createdAtBar` at BIAS_SET only), :240-246 | TIME | correct (measure from SWEEP_DONE; expose bars AND minutes) | 02 | "expired" deaths → ~0 before SWEEP_DONE |
| **RC-04** | One NEUTRAL 15m read invalidates a live setup (hysteresis default OFF): "HTF bias became NEUTRAL" 8 deaths + "HTF bias flip" 7 deaths in cfg A (15 of 23 deaths); G1 died this way at 15:00 | `StdvOteStrategy.java:194` default false; `recordHtfBias` :279-325 | BIAS | re-parameterise (hysteresis ON, grace 3) + biasEpoch idempotency | 03 | −8 NEUTRAL deaths on tape |
| **RC-05** | VOTE mode is structurally 3-of-3: V2 (AMD) can never vote; first decisive vote after 29 h; M2 NEUTRAL holds 60–89 % of every session in cfg C | `DailyAmdCycleTracker.java:107-108` vs :152,:164 (close < lowRef impossible after folding the candle in); `BiasVoteEngine.java:275-277` | BIAS | correct V2; ADAPTIVE vote rule (3-of-4 / 2-of-3 / warm pair) ; warm-boot M15/M30 (seed covers only H1/H4/D1) | 03 | cfg C M2-NEUTRAL 1948 → < 300 ASIA bars; bias reachable within 60 bars of start |
| **RC-06** | Bias DIRECTION is wrong for the OTE model: on G1 the 15m/30m structure reads BULLISH 13:45–15:00 (the retrace leg), the owner's model is BEARISH from the day's HH→LL impulse; the engine then hunts LOW sweeps and ignores the 30640 HIGH sweep | `HtfTrendAnalyzer.java:112-113,198-225` (15m BOS-based, sticky); runner :742-773 `mapTrendToBias`; `tryRecordSweep` :1340-1341 direction filter on `lastBias` | BIAS | correct (bias = dealing-range/impulse direction with premium/discount context; 15m structure demoted to a vote) | 03 | G1 becomes BEARISH by 11:00 ET; HIGH sweep at 14:52 recorded |
| **RC-07** | Raid pipeline never scores: `raidScore` is the fallback 5 on 6,922 sweep-bars, 4 on 315, never ≥6; scalp floor 6 rejected 2,031 sweeps (cfg D); the fallback equals the legacy floor so M4 "passes" by coincidence | `currentRaidScore` :1368-1372 (`orElse(spec.raidMinQuality())`); `RaidDetector.java:156-185` needs a KnownLevel within `toleranceTicks` — and the levels are wrong (RC-08); `RaidQualityScorer` +2 killzone/+2 SMT/+2 PDH make the floor unreachable without them (D-10) | SWEEP | correct (score every sweep; recalibrate weights so a textbook sweep of a session level clears 5 without SMT/PDH; -1 timing penalty not in SCORING mode) | 03 | raid scores spread 3–9; cfg D M4-no-sweep 80 % → < 20 % |
| **RC-08** | LevelEngine levels do not match the chart: PDH/PDL on 09-28 = 30921.75/30889.25 (a 1-lot 17:00 settlement print); true 30999.50/30679.00; LONDON_HIGH 30679 vs owner's 30640; ASIA window 20:00–00:00 with dead `hour<0` code; `inNY` starts 09:00 | `LevelEngine.java:460-467` (17:00 rollover makes the lone Fri 17:00 bar a "day"), :150-154, :211, :214-242 | SWEEP | correct (ignore phantom days < N bars / halt window; session windows configurable to the owner's LuxAlgo windows; tolerances in ticks) | 03 | PDH/PDL/LONDON/ASIA/NY levels within 1 pt of the chart on 09-28 |
| **RC-09** | Displacement threshold kills the textbook impulse: only 6.9 % of real 5m bars pass (range ≥ 1.5×avg14 AND body ≥ 65 %); G1 15:00 bar body 53 %, 14:35 bar 1.495×; `M5-no-recent-displacement` is the #1 holding gate in NY_PM (50.7 %) and ASIA/LONDON (47–50 %, cfg B) | `DisplacementDetector.java:128-142` (avg RANGE not ATR; 14-bar SMA); thresholds :56 | DISPLACEMENT-FVG | re-parameterise with tape evidence (owner already runs 1.2/0.55; target ≥ 30 % of real impulses) + one detector (delete/adapt ictlib DisplacementScanner) | 04 | M5-no-recent-displacement −60 % |
| **RC-10** | FVG linkage demands the 3-bar gap on the exact displacement bar; `no-fvg-for-displacement` 149–315 bars/session; G1 15:35 displacement: c1.low 30589.00 > c3.high 30593.50 false → no FVG → dead; fallback `pickFvgFor` finds no unfilled 5m FVG | `DisplacementDetector.checkForFvgCreation` :236-255; runner :1390-1406 | DISPLACEMENT-FVG | correct (FVG within N bars of the displacement, either family; OB/IFVG/breaker candidates) | 04 | M5-no-FVG → < 5 % |
| **RC-11** | OTE anchors ≠ chart: engine leg = post-MSS impulse (origin highSinceSweep → MSS terminus, ~65 pt on G1) vs owner's dealing range 30759.25→30356.50 (402.75 pt); band uses 0.62/0.79 not 0.618/0.786 (0.79 edge off by 1.6 pt on G1); band moves as the terminus extends; `M7: no PD array in OTE band` after a PD array was found (09-21 16:01) | `ImpulseLegTracker.arm` call site :1439-1456; `OteEntryCalculator.java:28-30,46-52`; `StdvOteStrategy.recordOteImpulse` :404-437 | OTE | correct (AnchorMode incl. dealing-range; fib constants; explicit ARM/ALARM/INVALIDATE rules; overlap not containment) | 04 | G1 zone = [30605.50, 30673.00] ± 1 pt; M7-no-reaction −50 % |
| **RC-12** | OTE reaction rule starves M7: `M7-no-reaction-at-band` 250 bars ASIA / 212 LONDON (cfg B); "reaction" = rejection wick ≥ 2 ticks at the band on a 1m bar, evaluated only while the moving band contains price | `impulseTracker.isRejectionReaction` (runner :1475-1477), `reactionWickTicks` default 2 | OTE | correct (limit-fill at 0.705/0.786 OR close-back-inside; PD-array-edge overlap) | 04 | OTE_ARMED arrivals 2 → ≥ 20 on tape |
| **RC-13** | Two RR ceilings, two floors: legacy validator [2.0,∞) vs risk engine [3.0,6.0] → RR ∈ [2,3) passes M7 and is denied; scalp [0.8,1.5] rejects any runner target; G1 RR(T1)=2.0 would be DENIED in legacy ("R:R too low: 2.0 < 3.0") | `RiskLimits.java:123-124, 244-245`; `PropFirmRiskEngine.java:171-178`; validator :564-576 | RR | correct (ONE band in RiskLimits: floor 1.0 legacy / 0.8 scalp, ceiling 5.0; validator floor vs T1, ceiling vs final target; remove "unrealistic") | 04 (band) + 05 (risk engine) | risk denials on RR → 0 for RR ∈ [1,5] |
| **RC-14** | Size floor above what risk allows: scalp `scalpSize()` returns 0 when floor(150/$risk) < 5 → G1 stop 39 pt = $78/micro → 1 micro → stand-down; **09-24 08:45 PRE_NY setup sat OTE_ARMED with every gate green for 41 bars and never emitted**; legacy sends 6–18 micros to a risk engine that re-sizes to floor(250/$78)=3 and caps at maxContracts 5 | `StdvOteSizer.java:29-30,119-135` (never 1..4); runner :1511-1516; `TradeableInstrument.java:55` (minMicros<5 throws); `PropFirmRiskEngine.java:127-158` | SIZE | correct (size = floor(risk$/(stopTicks×tickValue)) clamped [size.minMicros=1, 20]; ONE sizer; risk engine honours it; deny with numbers) | 05 | +1 emission on 09-24; every OTE_ARMED with green gates emits |
| **RC-15** | Warmup guard uses wall clock: signals stamped `Instant.now()`; `isStaleSignal(lastTs==null)` drops everything for a symbol that has not ticked; no timeout; multi-mode LIVE skips `onMarketData` (day rollover, execution onNewCandle, breakeven trigger) | `BaseEvent.java:16`; `WarmupGuard.java:26-28`; `LiveEngineRunner.java:659-660, 806-833, 712-777`; `StdvOteMultiInstrumentEngine.java:281-313` | WARMUP/WIRING | correct (candle-time warmup, per-symbol readiness + timeout, GateDecisionEvent "WARMUP") | 05 | LIVE-only; not measurable on tape — proven by unit test |
| **RC-16** | Legacy path cannot recover from a risk denial: `releaseUnexecutedSignal` publishes PositionClosedEvent but only scalp mode subscribes → IN_TRADE until expiry (200 min) | `SimEngineRunner.java:396-401`; `StdvOteRunnerStrategy.java:537-548` | EXEC/WIRING | correct (subscribe in both modes; M9 cleared per attempt) | 05 | measurable once signals exist |
| **RC-17** | Silent failure map (Section 5): 20+ catch-and-continue sites on candle→order→fill→bracket; three of them leave a LIVE position with no stop; no order TTL in SIM; P&L computed with tick value per point | table in Section 5 | EXEC | correct (log ERROR + telemetry counter + GateDecisionEvent; never silent; TTL; units) | 05 (execution/connector) + 01 (telemetry event) | LIVE safety; SIM fills correct |
| **RC-18** | M7b (30m OTE confluence) and M2b run in LOG mode and cannot be measured as gates; kill share unknown until arms exist | `Ote30mConfluenceGate.java:42-58`; `PremiumDiscountEvaluator.java:62` | OTE | re-classify M7b to SCORING by default after measurement | 04 | measured in Agent 06's after-histogram |

Deaths ≥ 5 % share per session are all covered: kzClosed (RC-02), M2 NEUTRAL (RC-04/05), MANIP-no-leg (RC-02/06: with all sessions the killzone buffer never falls back to the swing pair — Agent 03 task 4), M4-no-sweep (RC-07/08), M5 (RC-09/10), M6-no-MSS (Agent 04 task 2 — 216 bars NY_AM: two MSS detectors, 5m swing definition), M7 (RC-11/12), sizer (RC-14).

---

## 7. Routing table

| agent | RCs | minimum PFs |
|---|---|---|
| **01 Configuration & Wiring** | RC-01, RC-17 (GateDecisionEvent + telemetry counters) | PF-01, PF-04, PF-11 |
| **02 Time & Session** | RC-02, RC-03 | PF-03, PF-09, PF-10 |
| **03 Bias / Manip / Sweep / Raid** | RC-04, RC-05, RC-06, RC-07, RC-08 | PF-08 |
| **04 Displacement / FVG / MSS / OTE / RR** | RC-09, RC-10, RC-11, RC-12, RC-13 (band), RC-18 | PF-07 (validator half), PF-12 |
| **05 Post-signal** | RC-13 (risk engine), RC-14, RC-15, RC-16, RC-17 (execution/connector) | PF-05, PF-06, PF-07 (risk half) |

Merge order 01 → 02 → 03 → 04 → 05 (Agent 06). Every Opus agent re-runs
`FunnelAutopsyHarness` in cfg A (after Agent 01: the default config IS the
proven config) and pastes the before/after row for its gate in Appendix A.

---

## 8. Honest assessment

1. Zero trades is over-determined. Fixing any single agent's list will still
   yield zero: after RC-02 the machine enters the funnel in every session but
   dies at M5 (6.9 % pass rate); after RC-09/10 it reaches OTE and dies at RC-11/12
   or RC-14; after RC-14 the legacy risk engine denies RR 2.0 < 3.0 (RC-13).
   All five agents must land before the first trade can appear — which is why
   the doc's Agent 06 integration step is not optional.
2. The gates that measure the SETUP (sweep of a real level, displacement +
   FVG, MSS, OTE + PD array) are computing the wrong thing today, not too
   strictly the right thing. Level parity (RC-08), bias direction (RC-06),
   anchor selection (RC-11) and FVG linkage (RC-10) are correctness bugs; only
   RC-09's threshold is a calibration.
3. What must stay BLOCKING: M1, M2 (once reachable and correct), M4, M5, M6,
   M7, M8, RISK-DLL/MLL/max-contracts, FLATTEN. What becomes SCORING: M3
   (session), M7b, the killzone/timing bonus in the raid score.
4. The tape is 7 days. It contains 4 Asia nights, 5 London mornings, 5 NY days.
   Agent 07's S1 (≥ 3 trading days per session) is satisfiable; S6 needs the
   owner's TopstepX key refreshed so the 21-day fetch (`~/topstep-trading/tape/fetch_tape.py`) can run.
