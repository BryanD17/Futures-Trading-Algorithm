# PORT_MAP — V5 Java live decision path → Pine v6 (V6 Agent 00, Fable)

Date: 2026-11-05. Repo @ Main `f1f5df7` (worktree branch `feat/agent-00-v6-port-map`).
Master document: `TRADINGVIEW_MIGRATION_MASTER_PROMPT_V6.txt` Agent 00, tasks 00.1–00.2.
Companions: `docs/v6/PINE_CONSTRAINTS.md` (00.3), `docs/v6/GOLDEN_SIGNALS.md` (00.4–00.6),
`parity/java_signals.csv` (the golden oracle), `docs/RUNBOOK_V5.md` (the proven config).

All paths are relative to `trading-engine/src/main/java/com/topstep/trading/`. Line numbers
were read on 2026-11-05 at `f1f5df7`. Times are ET unless marked CT (owner's chart, ET − 1 h).

## 0. Reading guide

| column | meaning |
|---|---|
| BLOCKING | the gate alone can stop a trade in the default config |
| SCORING | contributes to tier / size / score but never blocks |
| INPUT | a computation other gates read (levels, aggregation) |
| PORT_EXACT | Pine can reproduce the same event on the same bar with the same parameters |
| PORT_APPROX | Pine reproduces the intent; a documented difference is expected (parity tolerance in V6 App. E) |
| RELAY | not in Pine; the relay (CrossTrade) or the broker bracket does it |
| DROP | not ported; stays in Java (lab) or is deleted from the live path |

Pine input naming (for Agents 02/03): every PORT_* row's `params` column names the EngineConfig
key; the Pine input tooltip must name that key (V6 task 02.2).

## 1. The REAL live decision path (task 00.1)

Effective configuration = classpath `trading-engine/src/main/resources/engine-defaults.properties`
with NO `-D` flags (the V5 "proven" config; `RUNBOOK_V5.md` §3). Verified by the export boot line
`[GOLDEN] riskLimits: DLL=1000.0 MLL=2000.0 maxContracts=5 maxTotal=10 riskPerTrade=250.0 rr=[1.0,5.0]
RISK PATH: STATIC (proven)`.

```
1m candle (TopstepConnector → EventBus, LIVE)                     LiveEngineRunner.java:334-342 builds
  → StdvOteMultiInstrumentEngine (engine.multiInstrument=true)      StdvOteMultiInstrumentEngine (legacy
      MNQ runner + MES as SMT-only feed; MGC runner                  MultiInstrumentEngine NOT built → multiEngine=null)
  → StdvOteRunnerStrategy.onCandle                                  StdvOteRunnerStrategy.java:787
      aggregate 1m → 5m (detector TF) / 15m / 30m / H1 / H4 / D1    BarAggregationManager.java:601,709
      levels, raids, structure, AMD, dealing range, SMT             §3–§4 below
      bias on each completed 15m/30m bar (VOTE + RANGE)             :985-1018 → StdvOteStrategy.recordHtfBias :487
      state machine IDLE→BIAS_SET→MANIP_DONE→SWEEP_DONE→DISPLACED   StdvOteStrategy.java (hooks) + OteSetupDriver.java
        →MSS_CONFIRMED→OTE_ARMED→(alarm)→tryEmit                   StdvOteStrategy.tryEmit :743
      MandatoryConfluenceValidator.validateStdvOteStrict M1..M9     validation/MandatoryConfluenceValidator.java:457
      → StrategySignalEvent (side, entry, stop, target, qty, tier, partials)   StdvOteStrategy.java:914
  → LiveEngineRunner.handleStrategySignal                           LiveEngineRunner.java:823-1035
      warmup guard + stale (>300 s) check                           :828-854
      14:45–17:00 CT no-entry block (RiskConfig.inNoEntryBlock)     :858-865, RiskConfig.java:122-126
      paused / kill switch / flattening                             :867-873
      duplicate / open-position check (one position per symbol)    :879-933
      TradingRiskManager correlation block — SKIPPED (multiEngine==null)   :936-956
      LiveRiskPath.evaluate → PropFirmRiskEngine.evaluate           :965-976, risk/PropFirmRiskEngine.java:61-217
      Order = LIMIT @ tick-rounded signal entry                     PropFirmRiskEngine.java:195-202
      connector.submitEntryOrder                                    :1008
  → broker FILLED → submitProtectiveOrders → BracketOrderManager    :1121-1164, :1201
      stop FIRST (flatten if it fails), then TP; partials ladder    BracketOrderManager.java:1243-1274, :1300-1324
      breakeven after first partial (entry ± 2 ticks)              BracketOrderManager.java:252, :1364-1382
  → flatten safety net 14:45 CT (earlier of 15:10 and 14:45)       LiveEngineRunner.java:1438-1471, RiskConfig.java:50-52
```

Dead / off-path in the default config (confirmed, not ported): news gating (no caller on the live
path; `news.blockWithoutCalendar=false`), `TradingRiskManager` (only with the legacy
`MultiInstrumentEngine`), `TrailingStopManager` (zero callers), `IctHighConfluenceStrategy`
(legacy strategy; `strategy.legacyFallback=false`), Monte Carlo / optimisation / journal (never gate
a signal), counter-trend scalp and LTF machine (`entry.counterTrendScalp=false`, `range.ltf.enabled=false`),
phase-aware risk (`risk.phaseAware=false`), scalp mode (`scalpMode.enabled` unset = false).

## 2. Time, sessions, bars (INPUT + M3)

| # | Java class:method (file:line) | what it decides | mode | params (key = default) | Pine plan | reason |
|---|---|---|---|---|---|---|
| T1 | `BarAggregationManager.getPeriodStart` (chart/BarAggregationManager.java:709) | 1m → 5m/15m/30m/H1 buckets by ET clock modulo; H4 anchored 18/22/02/06/10/14 ET; D1 = session day from 18:00 ET (17–18 ET excluded) | INPUT | detector TF `stdvote.detectorTimeframe=5` | PORT_EXACT | Chart = MNQ1! 5m; 1m, 15m, 30m, H1 via `request.security(..., lookahead_off)` on `[1]`. CME MNQ session is 18:00–17:00 ET so TradingView's daily bar = Java D1; H4 anchoring must be checked against TradingView's 240 bars (CME 4h bars start 18:00 ET — same anchors). |
| T2 | `SessionClassifier.classify` (strategy/session/SessionClassifier.java:113, constants :57-68) | ASIA 19:00–02:00, LONDON 02–08, PRE_NY 08–09:30, NY_AM 09:30–12, NY_LUNCH 12–13:30, NY_PM 13:30–15:45, NO_ENTRY 15:45–18:00, PRE_ASIA 18–19, WEEKEND Fri 17:00 → Sun 18:00 | INPUT | fixed | PORT_EXACT | `time(timeframe.period, "1900-0200:1234567", "America/New_York")` etc. CT shown in docs: NO_ENTRY = 14:45–17:00 CT. |
| T3 | `MandatoryConfluenceValidator` M3 (validation/MandatoryConfluenceValidator.java:597) | NO_ENTRY and WEEKEND always block; every other window passes | BLOCKING only for NO_ENTRY/WEEKEND | `session.gateMode=SCORING`, `session.allSessions=true` | PORT_EXACT | **V6 I3 tightens this**: no entries ≥ 16:30 ET (15:30 CT), flatten 16:40 ET (15:40 CT). Java's 15:45 ET no-entry start is EARLIER and stays (Java is stricter). Pine: `noEntryAfter` input default 15:45 ET to match Java on parity; owner may relax toward 16:30 only after parity. |
| T4 | prime killzones (SessionClassifier.java:73-83): London 02–05, NY AM 09:45–11:00, NY PM 13:45–15:45 | sets `primeKillzone` → tier O1 | SCORING | fixed | PORT_EXACT | Tier only; no size boost outside scalp mode. |
| T5 | `RaidQualityScorer` killzone bonuses (:110): legacy NY KZ 09:45–12:30 / 13:45–16:00 +2; Silver Bullet 03–04 / 10–11 / 14–15 +1 | raid score components | SCORING | fixed | PORT_EXACT | Same ET windows. |
| T6 | `LiveEngineRunner.handleStrategySignal` 14:45–17:00 CT block (:858-865; RiskConfig.java:50-52, :122-126) + `checkFlattenByTime` (:1438-1471) flatten at 14:45 CT | no entry 15:45–18:00 ET; flatten 15:45 ET | BLOCKING | fixed | PORT_EXACT + RELAY | Pine `strategy.close_all` at `flattenAt` (I3 default 16:40 ET; parity run uses Java's 15:45 ET). Relay: also configure its daily flatten (L2). |
| T7 | `LiveEngineRunner` stale-order cancel: 90 min or killzone CLOSING (:104, :1602-1615); SIM `order.ttlBars=80` 1m bars (RiskConfig.java:110-115) | unfilled entry limit expires | BLOCKING (order lifecycle) | `order.ttlBars` (derived 80) | DROP | Not applicable under MARKET_ON_CONFIRM (no resting limit). Recorded for BROKER_LIMIT in V7. |

## 3. Bias (M2 input)

| # | Java class:method | what it decides | mode | params | Pine plan | reason |
|---|---|---|---|---|---|---|
| B1 | `DealingRangeTracker.onCandle/snapshot` (strategy/stdvote/DealingRangeTracker.java:227, :371) | the governing dealing range: session-day range (rolls 18:00 ET; 17–18 prints ignored), decisive at 0.08 % span, direction = more recent extreme; a 1m close beyond the far extreme flips; pullback ≥ 0.5 of leg re-anchors; AUTO: from 09:30 ET the RTH impulse leg governs once ≥ 400 ticks; carries across 18:00 reopen until a new ≥ 400-tick leg | INPUT (sets bias + M2b + OTE anchors) | `bias.range.window=AUTO`, `bias.range.minLegTicks=400`, `bias.range.minRangePct=0.08`, `bias.range.reanchorFraction=0.5`, `bias.range.carryAcrossReopen=true` | PORT_EXACT | Pure 1m OHLC arithmetic with ET session anchors; implement on the 1m series (`request.security` 1m or on-chart 5m with 1m via `request.security_lower_tf` — see PINE_CONSTRAINTS C-05). This is THE bias in `bias.source=RANGE`: a decisive range overrides the vote. |
| B2 | `BiasVoteEngine.evaluate` (strategy/stdvote/BiasVoteEngine.java:389, :360) | with `bias.source=RANGE` the vote decides only when the range is NOT decisive (cold start) | INPUT | `bias.vote.mode=VOTE`, `bias.voteRule=ADAPTIVE`, `bias.source=RANGE` | PORT_APPROX | Port B1 exactly; the vote only matters before the first decisive range. Pine: treat NEUTRAL until the range is decisive (cold-start bars differ, never a live trade difference after warmup). |
| B3 | `BiasVoteEngine.voteV1` (:218) ← `HtfTrendAnalyzer` (strategy/HtfTrendAnalyzer.java:114,179): 15m/30m BOS structure + swing history + momentum fade | vote V1 | SCORING (vote) | `bias.v1.includeH4=false` | DROP | Only consulted when the range is not decisive (B2). Not worth 15m structure detection in Pine; parity impact confined to cold start. |
| B4 | `BiasVoteEngine.voteV2` (:233) ← `DailyAmdCycleTracker` phase | vote V2 | SCORING (vote) | — | DROP | Same as B3. |
| B5 | `BiasVoteEngine.voteV3` (:251) price vs MIDNIGHT_OPEN ± 2 ticks | vote V3 | SCORING (vote) | `pd.eqBandTicks=2` | DROP | Same as B3 (trivial to add later if cold-start parity matters). |
| B6 | `BiasVoteEngine.voteV4` (:274) PDH/PDL draw on liquidity | vote V4 | SCORING (vote) | `pd.eqBandTicks=2` | DROP | Same as B3. |
| B7 | `BiasVoteEngine.aggregate` ADAPTIVE (:316): 4 voters → 3; 3 → 2; 2 → both; < 2 → NEUTRAL | vote rule | SCORING (vote) | `bias.voteRule=ADAPTIVE` | DROP | Falls with B3–B6. |
| B8 | `StdvOteStrategy.recordHtfBias` (strategy/stdvote/StdvOteStrategy.java:487) | opposite flip kills the setup at once; NEUTRAL survives ≤ 3 consecutive 15m/30m evaluations | BLOCKING (kill) | `bias.hysteresis=true`, `bias.neutralGraceBars=3` | PORT_EXACT | Evaluate bias on each confirmed 15m/30m bar (`request.security` 15m `[1]`); counter of NEUTRAL reads; invalidate on flip. |
| B9 | `StdvOteStrategy.tryEmit` bias invariant (:764) | no emission unless the CURRENT bias is non-NEUTRAL and equals the setup's bias | BLOCKING | — | PORT_EXACT | Entry condition `bias == setupBias and bias != NEUTRAL`. |
| B10 | `BiasVoteEngine` seam: bias refreshed only on completed 15m/30m bars (runner :985-1018) | cadence | INPUT | — | PORT_EXACT | Use `ta.change(time("15"))`-style new-bar detection on the 15m request; never per 1m tick. |

## 4. Levels, manipulation, sweep / raid (M4)

| # | Java class:method | what it decides | mode | params | Pine plan | reason |
|---|---|---|---|---|---|---|
| L1 | `LevelEngine.processCandle` (chartstate/LevelEngine.java) | PDH/PDL (day rolls 18:00 ET, days < 60 bars ignored), PWH/PWL, PMH/PML, ASIA 20:00–00:00, LONDON 04:00–06:00, NY_AM 09:30–12:00, NY_PM 13:30–16:00, NY 09:30–16:00, SESSION H/L, equal H/L clusters, MIDNIGHT_OPEN + other OPENs (never raided); a raided level re-arms after price leaves it by 100 ticks | INPUT | `levels.<asia|london|nyam|nypm|ny>.<start|end>`, `levels.minBarsPerDay=60`, `levels.rearmDistanceTicks=100` | PORT_EXACT | Session H/L via `time(...)` windows in ET; PDH/PDL via `request.security("D")[1]` with `lookahead_off` (CME daily = 18:00 ET roll, identical to Java). PWH/PWL via "W"[1]. Equal H/L = cluster of swing points within tick tolerance. |
| L2 | `ManipulationLegDetector.detect` / `SessionLegLocator.locate` (strategy/stdvote/ManipulationLegDetector.java:47, SessionLegLocator.java:72) | BIAS_SET → MANIP_DONE: JUDAS excursion + reclaim, else LEVEL_SWEEP, else the last 1m swing pair (always exists) | effectively NON-BLOCKING (fallback always exists); seeds the STDV ladder that the anchored target path does not use | `manip.legMode=SESSION`, min leg 8 ticks, snap 3 ticks | DROP | Produces no gate and no number used by entry/stop/target in the default (DEALING_RANGE anchor) config. Pine goes BIAS → sweep directly. Parity impact: none on emitted trades (state-arrival timing only). |
| L3 | `StdvOteRunnerStrategy.tryRecordSweep/resolveSweep` (strategy/stdvote/StdvOteRunnerStrategy.java:1967, :2062) | MANIP_DONE → SWEEP_DONE: best of (a) a `RaidDetector` level raid within 3 bars, (b) a `LiquidityDetector(30)` swing sweep (low ≤ 10-bar low and close back above, mirrored); bullish bias needs a LOW sweep; unscoreable = floor − 1 | BLOCKING | `raid.sweepMode=PIPELINE`, `raid.starvedScore=-1` | PORT_EXACT | Both detectors are OHLC-only. In the IMPULSE_LEG model (O1) the sweep that arms the trade is the in-band sweep; parity oracle shows 6/8 signals with `raid_level_type=OTE_BAND` (swing sweep), 2/8 `LONDON_HIGH` (level raid). |
| L4 | `RaidDetector.checkLevelForRaid` (chartstate/RaidDetector.java:153) | raid of a high: `high > level + minPen` AND `low ≤ level + tol` AND rejection (close ≤ level OR upper wick ≥ 40 % of range); mirrored for lows | INPUT | MNQ: tol 4 / minPen 4 / strong 8 ticks, threshold 4 (`InstrumentRaidConfig`, micros map to NQ) | PORT_EXACT | Tick math only (`syminfo.mintick`). |
| L5 | `RaidQualityScorer.calculateScore` (chartstate/RaidQualityScorer.java:110) | V5 weights: base +1; HTF aligned +3 strong / +2 weak; HTF opposing −4; legacy NY KZ +2; Silver Bullet +1; PDH/PDL/PWH/PWL +2; session extreme +2; equal cluster ≥ 3 +1; rejection strong +2 / moderate +1; premium/discount vs range eq +1; SMT +2; 5m displacement+FVG+MSS +2; touches ≥ 3 +3 / = 2 +1; clamp 1..10; timing penalty skipped in SCORING | SCORING → feeds M4 | `raid.weights=V5` | PORT_APPROX | Port the weights verbatim; the "HTF aligned strong/weak" term reads `HtfTrendAnalyzer` strength (B3, DROP) → use range-aligned = +2 (weak) in Pine. Expected impact: scores 1 lower on strong-trend raids; the M4 floor (5) still clears on the oracle's 6–10 scores (min observed 6). Record as a known PORT_APPROX in PARITY_REPORT. |
| L6 | `StdvOteStrategy.recordSweep` floor (:593) + validator M4 (:622) | `raidScore ≥ 5` (MNQ/MES), 6 (MGC) | BLOCKING | `TradeableInstrument` MNQ 5 | PORT_EXACT | Input `minRaidScore` default 5. |
| L7 | `tryRefreshSweep` (:1999) | in SWEEP_DONE before displacement, a newer level raid with score ≥ floor and ≥ current replaces the sweep | BLOCKING (changes which sweep) | — | PORT_EXACT | Keep "latest best sweep" while waiting. Under IMPULSE_LEG the arming sweep is the one inside the band anyway. |
| L8 | `CorrelationTracker.hasSMTDivergence(MNQ, MES, 20)` (strategy/CorrelationTracker.java:121; runner :878) | on 1m closes: the last close makes a new 20-bar high/low on one symbol but not the other | SCORING (+2 raid score, tier O2, TIER_4) | fixed 20 | PORT_EXACT | `request.security("CME_MINI:MES1!", "1", ...)` closes, `lookahead_off`, data only — NEVER traded (I1). Only 1/8 oracle signals had `smt=DIVERGENT`. |

## 5. Displacement, FVG, MSS, OTE (M5–M7)

| # | Java class:method | what it decides | mode | params | Pine plan | reason |
|---|---|---|---|---|---|---|
| O1 | `OteSetupDriver.tryImpulseLegEntry` (strategy/stdvote/OteSetupDriver.java:250) | IMPULSE_LEG model: requires DEALING_RANGE anchor, sweep direction = bias, swept level inside [f79, f62] of the range, retrace extreme reaching the 0.705, no close beyond the 1.0; the range's impulse leg (LL→HH / HH→LL on 5m) must contain a displacement + same-direction FVG (M5) and an MSS at/after it (M6); success → OTE_ARMED on this candle; failure → POST_SWEEP path (O6) | BLOCKING | `ote.entryModel=IMPULSE_LEG`, `ote.impulseLeg.minSweepFib=0.705`, `ote.anchorMode=DEALING_RANGE` | PORT_EXACT | This is the model that produced 6/8 oracle signals (incl. G1, G2). All inputs are 5m OHLC + the range (B1). |
| O2 | `DisplacementDetector.detectDisplacement` (strategy/DisplacementDetector.java:216) | 5m bar range ≥ 1.2 × mean true range of the 14 PRIOR bars (current excluded) AND body/range ≥ 0.50; runs of 2–3 same-direction strong bars also qualify if they cover ≥ 1.2×ATR | BLOCKING (M5) | `displacement.atrMult=1.2`, `displacement.bodyPct=0.50`, ATR 14, lookback 20 | PORT_EXACT | `ta.atr(14)[1]` (prior bars) vs `high-low`; body ratio. Note "true range" mean, not range mean. |
| O3 | `PdArrayLocator.gapAt / linkedFvg` (strategy/stdvote/PdArrayLocator.java:88; driver :184) | 3-bar FVG whose middle candle is the displacement bar or up to 3 bars after; else the gap ending on it; IMPULSE_LEG: largest same-direction gap inside the leg if none linked | BLOCKING (M5) | `fvg.linkBars=3` | PORT_EXACT | `low[2] > high[0]` style scan over the leg bars. |
| O4 | `MarketStructureShiftDetector.forStdvOte / detectCloseBeyondRecentSwing` (strategy/MarketStructureShiftDetector.java:123) | swing = fractal with 2 bars each side (ties allowed); MSS = 5m close beyond the most recent opposite swing that is ≥ 2 bars old; each swing breakable once | BLOCKING (M6) | lookback 50, swing 2 | PORT_EXACT | `ta.pivothigh/low(2,2)` + close-through with a "used" flag. |
| O5 | POST_SWEEP `tryRecordMss` freshness (driver :493, :541) | MSS ≤ 30 detector bars old; counter-bias MSS through the sweep extreme invalidates | BLOCKING | `mss.freshBars=30` | PORT_EXACT | Only in the POST_SWEEP fallback (O6). |
| O6 | POST_SWEEP path: `tryRecordDisplacement` (:184) → `tryRecordMss` (:493) → `tryArmOte/plan` (:527, :557) | fallback when the sweep is NOT inside the band: displacement within 12 detector bars at/after the sweep, MSS, then OTE zone on the range if the post-sweep extreme is in the correct half, else on the sweep-extreme→furthest-price leg; ARM on first band touch | BLOCKING | `displacement.recentBars=12` | PORT_APPROX | 2/8 oracle signals (09-23 04:04, 09-27 20:40) came from POST_SWEEP. Port the range-anchored branch exactly; the impulse-leg-anchored branch (origin = sweep extreme) is PORT_APPROX (band moves as the terminus extends — Java recomputes per bar; Pine must do the same with `var` state). |
| O7 | `OteEntryCalculator.buildZone` (strategy/stdvote/OteEntryCalculator.java:48) | fibs from the terminus: eq 0.5, 0.618, 0.705, 0.786, 1.0 = origin; tick-rounded; rejected if 0.618/0.705/0.786 collapse to one tick | INPUT | `ote.fib62=0.618`, `ote.fib705=0.705`, `ote.fib79=0.786` | PORT_EXACT | `math.round_to_mintick`. |
| O8 | OTE window (runner :1108) | OTE_ARMED longer than 8 × 5 = 40 feed bars → invalidated | BLOCKING | `ote.windowBars=8` | PORT_EXACT | `bar_index - armedBar > 8` on the 5m chart. |
| O9 | `OteSetupDriver.alarm` (:617) + candidates (:409) + `bestInBand` + `impulseReaction` (:466) | PD array at the sweep: ICT_OB = newest opposite-close **1m** bar among the 5 before the sweep bar overlapping the band; also the 5m sweep-bar OB, WICK (close back past level, wick ≥ 50 %), FVG/IFVG/BREAKER reaching the level; pick = overlaps band, entry nearest 0.705, tie OB > FVG > IFVG > BREAKER. Reaction = bar touches the band AND closes back beyond the swept level in the trade direction. Hard block 15:45–18:00 ET + weekend | BLOCKING (M7) | `ote.pdArraySource=ICT_OB`, `ote.obLookbackBars=5` | PORT_APPROX | Needs the last five 1m bars before the sweep → `request.security_lower_tf(syminfo.tickerid, "1", ...)` arrays (C-05). If the 1m intrabar array is unavailable (plan/data), fall back to the 5m sweep-bar OB → entry drifts ≤ a few ticks (within App. E ± 4). Oracle PD kinds: OB 5, BREAKER 2, IFVG 1. |
| O10 | `MandatoryConfluenceValidator` M7 (:719) | zone exists; PD level not NaN; entry inside [f79, f62]; IMPULSE sweep inside the band; RR(T1) ≥ 1.0; RR(final) ≤ 5.0 | BLOCKING | `risk.rrFloor=1.0`, `risk.rrCeiling=5.0` | PORT_EXACT | Inputs `rrMin`, `rrMax`. |
| O11 | M7b `Ote30mConfluenceGate.gateCheck` (strategy/stdvote/Ote30mConfluenceGate.java) | 30m ChartEngine zone REACTED in the trade direction → tier +1 (O5); never blocks | SCORING | `ote30m.mode=SCORING`, `ote30m.acceptArmed=false` | DROP | Tier-only; tier never blocks and does not change size in the static risk path (see S4). |
| O12 | M2b `PremiumDiscountEvaluator.gateCheck/classify` (strategy/stdvote/PremiumDiscountEvaluator.java:232, :293) | proposed entry vs equilibrium of the governing range (RD decisive range → D1 fractal swings → PDH/PDL → developing day → ABSTAIN passes); longs need DISCOUNT, shorts PREMIUM; ± 2 ticks of eq blocks both | BLOCKING | `pd.gate.mode=BLOCK`, `pd.eqBandTicks=2`, `pd.minRangeTicks=80`, `pd.d1MinBars=10` | PORT_APPROX | Port the RD branch exactly (range from B1). The D1-fractal / PDH-PDL fallbacks only fire when the range is not decisive → PORT_APPROX, cold-start only. G3 evidence: M2b was the gate that correctly refused the 09-28 London arms (82 bars `GATE-M2b`). |
| O13 | M1 (:465) symbol ∈ {MNQ, MES, MGC}; M9 (:809) `lastGateFailed == null` per attempt | housekeeping | BLOCKING | — | DROP | Pine runs on one root (I1); M9 is a Java state-machine artefact. |

## 6. Entry, stop, targets, size (M8 + risk)

| # | Java class:method | what it decides | mode | params | Pine plan | reason |
|---|---|---|---|---|---|---|
| S1 | `PdArrayLocator.entryLevel` (:288) + `OteEntryCalculator.chooseEntry` (:102) | entry = OB midpoint, or FVG/IFVG/BREAKER/WICK near edge, clamped into the band; fallback 0.705. Java order = **LIMIT** at this price (`PropFirmRiskEngine.java:195-202`), placed on the alarm bar | INPUT | — | PORT_APPROX (**ENTRY_MODE = MARKET_ON_CONFIRM**) | See `GOLDEN_SIGNALS.md` §1: a TradingView order-fill alert fires only after TV's emulator fills, so a TV limit reaches the broker late as a market. V6 default enters at market on the confirmed alarm bar close; keep `javaEntryRef` plotted for parity. Expected drift = alarm-bar close − Java limit (oracle: G1 alarm bar 14:53 closes ≈ 30636 vs limit 30636.00 → 0 ticks; worst case the band width). App. E tolerance ± 4 ticks. |
| S2 | `StdvOteStrategy.anchoredStop` (:944) | short: `max(f79, PD-array far edge, sweep/episode high) + 4 ticks`; long mirrored | INPUT | `stdvOte.stopBufferTicks=4`, `ote.stopMode=BAND` | PORT_EXACT | Pure arithmetic; `strategy.exit(stop=...)` AND relay native bracket (I2). |
| S3 | `OteEntryCalculator.targetLadder` (:191) | T1 = 0.5 (eq), T2 = 0.382, T3 = 0.0 (range extreme) of the dealing range; signal target = furthest rung with RR ≤ 5.0; partials `[[RR_T1, 0.5], [RR_final, 0.5]]` (or `[[RR_T1, 1.0]]`) (StdvOteStrategy.java:811-812) | INPUT | `risk.rrCeiling=5.0` | PORT_EXACT | Two `strategy.exit` legs (50 % at T1, 50 % at final) or ONE bracket at T1 with the relay — Agent 03 decides; the parity key compares T1 only (± 2 ticks). |
| S4 | `VariantSelector.resolveStdvOteTier` (strategy/VariantSelector.java:97; runner :2720) | TIER_4 raid ≥ 8 + ≥ 4 confluences + SMT; TIER_3 raid ≥ 7 + ≥ 3; TIER_2 raid ≥ 6 + ≥ 2; else TIER_1 — total function | SCORING | — | DROP | Never blocks; static risk path ignores tier for size. Oracle: 7× TIER_2, 1× TIER_3. Keep as a debug label only. |
| S5 | `StdvOteRunnerStrategy.riskDerivedSize` (:2665) → `StdvOteSizer.riskDerived` | `budget = min($250, DLL room, MLL room)`; `size = floor(budget / (stopTicks × $0.50))`; cap `min(size.maxMicros 20, spec 20, RiskLimits.maxContracts 5)` = **5**; < 1 → deny | BLOCKING (M8 band [1, 20]; effective cap 5) | `size.minMicros=1`, `size.maxMicros=20`, `RiskLimits.topstep50k().maxContracts=5`, riskPerTrade $250 | PORT_EXACT | Pine: `contracts = math.floor(riskPerTrade / (stopTicks * tickValue))`, then `min(maxContracts, scalingCap)` from the AccountProfile (I4). Oracle sizes 5,5,5,5,2,5,1,3 = exactly this formula at $250 (e.g. G1 $76/micro → 3). **The Topstep cap of 5 is replaced by the Lucid profile cap** (Agent 03 reads `config/lucid_profiles.yaml`). |
| S6 | `PropFirmRiskEngine.evaluate` (risk/PropFirmRiskEngine.java:61-217) | deny paths: DLL −$1,000 breached; MLL $2,000 (highest-EOD − equity); no DLL room; max trades/day (0 = off); max consecutive losses (0 = off); invalid stop; size below `minMicros`; requested > budget; requested > hard cap 5; total contracts > 10; RR(final) < 1.0 | BLOCKING | `RiskLimits.topstep50k()` (domain/RiskLimits.java:112-126) | RELAY + PORT_APPROX | Soft daily loss in Pine (`strategy.risk.max_intraday_loss`, AccountProfile.softDailyLoss$) + RELAY account controls (max qty, daily loss) + Lucid MLL as L4. RR floor is in Pine (O10). Topstep DLL/MLL numbers are obsolete (PF-07). |
| S7 | `LiveRiskPath` profit-target halt (risk/LiveRiskPath.java:203-211; `risk.haltOnProfitTarget` LIVE=true) | no new entries once tracked realized P&L ≥ $3,000 | BLOCKING | `RiskLimits.profitTarget=3000` | DROP → RELAY/owner | Prop-firm eval target is the owner's decision (pass the eval → funded). Document in the runbook; optional `haltAtProfit$` input in the AccountProfile group. |
| S8 | `LiveEngineRunner.handleStrategySignal` duplicate / open-position check (:879-933) + `StdvOteRunnerStrategy` one-position latch (:2557-2580, :1626) | one position per symbol; a pending order is replaced only by a higher-tier signal | BLOCKING | — | PORT_EXACT | `strategy.position_size == 0` and `strategy.opentrades == 0` before any entry; `pyramiding=0` (I1/AP-10). |
| S9 | Warmup guard (`WarmupGuard`, LiveEngineRunner.java:828-854) + stale signal > 300 s | drop signals before feeds are warm | BLOCKING (LIVE only) | `warmup.timeoutSeconds=120` | DROP | TradingView has full history at alert creation; no warmup concept. Note in RUNBOOK: create the alert only after the chart has loaded ≥ 7 days of 1m data (B1 needs the prior session). |

## 7. Exits, order lifecycle, post-fill management

| # | Java class:method | what it decides | mode | params | Pine plan | reason |
|---|---|---|---|---|---|---|
| X1 | `BracketOrderManager.createBracket[WithPartials]` (execution/BracketOrderManager.java:1243-1274, :1300-1324) | after FILL: stop first (flatten if it fails), then TP(s) per the signal's partial ladder | BLOCKING (safety) | — | RELAY | I2: the relay places a native Tradovate bracket (stop_loss / take_profit) with the entry. Pine ALSO keeps `strategy.exit` so the TV position mirrors the broker. Lifecycle table = Agent 04 (04.4). |
| X2 | Breakeven after first partial, entry ± 2 ticks (BracketOrderManager.java:252, :1364-1382; single-contract price trigger :1876-1897) | stop to BE | post-fill | `BREAKEVEN_BUFFER_TICKS=2` | PORT_APPROX | Pine: when T1 leg fills (`strategy.opentrades.size` drops), re-issue `strategy.exit` with `stop = entry ± 2 ticks` → the alert payload updates the relay stop (if the relay supports stop modification; else RELAY ATM). Decide in Agent 03/04 with PLATFORM_FACTS. |
| X3 | `TrailingStopManager` (execution/TrailingStopManager.java) | — | OFF (zero callers) | — | DROP | Not on the live path. Do NOT add `trail_*` to `strategy.exit` in V6 (would create a parity difference that Java never had). SIM-only trailing in `ExecutionEngine.updateTrailingStops` (:534-562) affects the harness P&L columns only, never entries. |
| X4 | Adopted / foreign positions: `BracketOrderManager.reconcileOnStartup/reconcileWithBroker/adoptPosition` (:1611, :1628, :1750) | a broker position the engine does not track gets a real-distance stop (or observe-only if > maxContracts) | safety | `ADOPTED_MIN_STOP_TICKS=8` | RELAY | Relay strategy sync (`sync_strategy=true`, `out_of_sync=flatten`) replaces adoption: TV position ≠ broker position → flatten. The owner must not trade bot accounts manually (OA-6). |
| X5 | `ExecutionEngine` SIM fill model (execution/ExecutionEngine.java:434-446, :602-628) | limit fills when touched at the limit price; stop checked before target on the same bar | lab only | — | DROP (Java lab) | Relevant only to the oracle's diagnostic `fill_*`/`exit_*` columns. TradingView's emulator differs (C-07); the parity key is the SIGNAL (ts, side, entry, stop, T1), not the fill. |
| X6 | Re-arm: `processLegacyRearm/canRearm` (runner :1496, :1612) | after INVALIDATED or a closed trade: wait 5 feed bars, then re-arm unless NO_ENTRY/WEEKEND or a position is open | BLOCKING (cadence) | `setup.rearmAfterClose=true`, `setup.rearmCooldownBars=5`, `stdvOte.rearmOnInvalidated=true` | PORT_EXACT | 5 × 1m bars = 1 detector bar on the 5m chart: no new entry on the bar a position closed (I1 "flat ≥ 1 confirmed bar"), consistent with 03.7. |
| X7 | Expiry budgets (SessionConfig.java:117; StdvOteStrategy.java:405-455) | SWEEP→DISP 60 min, DISP→MSS 60, MSS→OTE 240, pre-sweep 480 min from BIAS_SET, IN_TRADE net 200 feed bars | BLOCKING (kill) | `setup.expiry.sweepToDisplacement=60`, `displacementToMss=60`, `mssToOte=240`, `setup.preSweepExpiryMinutes=480` | PORT_EXACT | Minutes → 5m bars (12 / 12 / 48 / 96). Under IMPULSE_LEG (O1) the machine jumps SWEEP→OTE_ARMED on one bar so only the pre-sweep and OTE-window budgets bite. |

## 8. Mandatory classifications (task 00.2) — resolved

| topic | classification | decision |
|---|---|---|
| News gating (`news/*`, `MacroNewsManager`, `EventProximityChecker`) | **DROP** | Not on the V5 live path (no caller; `news.blockWithoutCalendar=false`). LucidFlex allows news trading (Agent 01, `docs/LUCID_RULES.md`). Pine keeps an optional MANUAL blackout-window input (`[Time] manualBlackout`, default empty, ET). |
| Monte Carlo / optimisation / journal (`montecarlo/*`, `optimization/*`, `journal/*`, `backtest/*`) | **DROP from Pine, stay Java** | Never gate a signal. They are the V6 "lab". |
| Adopted-position logic (X4) | **RELAY** | Strategy sync with `out_of_sync=flatten` replaces adoption; the drill D3 proves it. |
| `TrailingStopManager` (X3) | **DROP** (chosen over PORT_APPROX trail_* and over relay ATM trailing) | Zero callers on the live path today, so any trailing in V6 would be NEW behaviour with no Java oracle. Keep V6 = Java: fixed stop → BE after T1 (X2). Revisit trailing as a V7 feature with its own parity run. |
| `TradingRiskManager` correlation block (risk/TradingRiskManager.java:71-90: NQ↔ES, GC↔SI↔MGC, GC↔DX inverse) | **replaced by I1 by design** | Skipped even in Java's default wiring (`multiEngine == null`). Pine: exactly ONE strategy instance on ONE root; MES read-only for SMT; `pine_lint.py` asserts no order call on a `request.security` symbol. |
| Scalp mode, counter-trend scalp, LTF machine, phase-aware risk | **DROP** | All opt-in and OFF in the proven config; not part of the oracle. |
| Tier system (S4) + M7b (O11) | **DROP** (debug label only) | Never blocks; never changes size in the static path. |
| Topstep envelope (DLL/MLL/caps/15:10 CT flatten, PF-07) | **RELAY + AccountProfile** | Replaced by I3/I4 + Lucid profile numbers (Agent 01 yaml → Agent 03 presets). |

## 9. Row count and coverage

Rows: T1–T7 (7) + B1–B10 (10) + L1–L8 (8) + O1–O13 (13) + S1–S9 (9) + X1–X7 (7) = **54 rows**, plus 8
mandatory-classification rows (§8) = 62 entries. Every BLOCKING gate on the live path (M1–M9, bias
kill, raid floor, OTE window, expiry, no-entry/flatten, one-position, risk deny paths, warmup) has a row.

Plan totals: PORT_EXACT 33 · PORT_APPROX 8 · RELAY 4 (+2 shared) · DROP 15.

## 10. Things that surprised me (for Agents 02/03/05)

1. **Effective size cap is 5 micros, not 20** — `RiskLimits.topstep50k().maxContracts=5` wins inside
   `riskDerivedSize`. Oracle sizes confirm (never > 5). In V6 the cap comes from the Lucid profile.
2. **Killzone size boost never applies** in the default path (`sizeForTier` passes boost 1.0; the boost
   lives in `scalpSize`, scalp mode only). Do not port a boost.
3. **`stdvOte.reactionWickTicks` (2) is dead** — the IMPULSE_LEG reaction is "close back beyond the
   swept level in the trade direction", no tick threshold. Port the close rule, not the wick rule.
4. **`FvgDetector`, `ImpulseLegTracker`, `StdvOteSizer.decide/tierCap`, `AdaptiveStopCalculator`,
   `MIN_RR_FLOOR_STDV_OTE=2.0`** are computed/declared but unused on the default path. Ignore them.
5. **Java's entry is a LIMIT at the PD-array level placed on the alarm bar**, and the SIM fills it
   when touched; LIVE waits for the broker fill before placing the stop. V6 enters at MARKET on the
   confirmed alarm bar with the bracket attached at submission (I2) — strictly safer, slightly
   different price (`GOLDEN_SIGNALS.md` §1).
6. **Java's no-entry starts 15:45 ET (14:45 CT)** — earlier than V6 I3's 16:30 ET. Keep 15:45 for
   the parity run; I3's 16:30/16:40 are the outer safety limits, not the parity default.
7. **MES is consumed as 1m closes only** (SMT, 20-bar extremes). One `request.security` call.
8. **The dealing range (B1) is the whole bias** once decisive; the 4-voter engine is cold-start only.
