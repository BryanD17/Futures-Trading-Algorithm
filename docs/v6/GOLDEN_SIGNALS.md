# GOLDEN_SIGNALS — the Java oracle, the entry-type decision, and G1–G3 (Agent 00, tasks 00.4–00.6)

Date: 2026-11-05. Repo @ Main `f1f5df7`. Harness:
`trading-engine/src/test/java/com/topstep/trading/strategy/stdvote/GoldenSignalExport.java`.

```
./gradlew :trading-engine:test --tests '*GoldenSignalExport*'      # JDK 21 (temurin-21); JDK 23 breaks Gradle 8.5
# → parity/java_signals.csv, parity/bars_mnq_1m.csv, parity/bars_mes_1m.csv   (repo root; GOLDEN_OUT overrides)
```

The harness replays `src/test/resources/tape/real_MNQ_1m.json` (7,830 bars, 2026-09-21 00:00 →
2026-09-28 16:22 ET) with `real_MES_1m.json` as the SMT feed through the REAL runner path
(`StdvOteRunnerStrategy` → `StrategySignalEvent` on a started `EventBus` → `LiveEngineRunner.newLiveRiskPath()`
/ `PropFirmRiskEngine` with `ScalpConfig.activeRiskLimits()` → `ExecutionEngine` fills) under the
EFFECTIVE config (classpath `engine-defaults.properties`, `engine.props=NONE`, no `-D`). It is the
same wiring `FunnelAutopsyHarness` proved in V5, reduced to the signal rows. The test FAILS on 0
signals (V6 Agent 00 SUCCESS criterion).

## 0. Determinism + run evidence (Appendix A-00)

| run | java_signals.csv sha256 | bars_mnq_1m.csv | bars_mes_1m.csv |
|---|---|---|---|
| 1 (`cleanTest`) | `1926014584d89a54c90d4df03a1f8eb186283687242fa77c5781388516bdbd7d` | `b7a166833474a2bc67aad0f6bda1913db313c8827c4c7ef8e1a3cd760e795ba9` | `c66dcc30d083f3c56eaa5a6de36373d39dacba344bbce751a35474e978b0fbaf` |
| 2 (`cleanTest`) | `1926014584d89a54c90d4df03a1f8eb186283687242fa77c5781388516bdbd7d` | `b7a166833474a2bc67aad0f6bda1913db313c8827c4c7ef8e1a3cd760e795ba9` | `c66dcc30d083f3c56eaa5a6de36373d39dacba344bbce751a35474e978b0fbaf` |

Byte-identical. Signals: **8** (RUNBOOK_V5 §8 reported 7 at Main `55994e6`; the later V5 05.x PRs
merged after it changed the 09-23 London sequence to four entries — not a regression: `FunnelAutopsyHarness`
cfg A on the same tape today also reports 8 signals / 8 fills / 0 risk denials / 0 in NO_ENTRY/WEEKEND).

Boot line: `riskLimits: DLL=1000.0 MLL=2000.0 maxContracts=5 maxTotal=10 riskPerTrade=250.0 rr=[1.0,5.0]
RISK PATH: STATIC (proven) — effective per-trade budget $250.00 ... (risk.phaseAware=false) | haltOnProfitTarget=true`.

### CSV schema (`parity/java_signals.csv`)

Parity key (first 12 columns, V6 task 00.5): `ts_et, side, entry, stop, t1, t2, size_micros,
raid_level_type, raid_score, killzone, bias, gate_proof` — `ts_et` = the 1m candle (ET, `yyyy-MM-dd HH:mm`)
on which the signal was emitted; `killzone` = the SessionClassifier window; `gate_proof` = pipe-joined
facts (session, prime KZ, bias + vote, dealing range + eq, sweep + extreme, raid score, SMT, displacement,
FVG, M5 link kind, MSS, OTE band + invalidation, armed/alarm times, PD array kind@price, entry model,
impulse-leg verdict, anchor, tier, RR(T1), signal reason, risk decision).
Diagnostic columns after it: `ts_utc, session, t3, final_target, rr_t1, tier, size_requested, risk_decision,
pd_array_kind, pd_array_price, entry_model, ote_low, ote_high, range_low, range_high, fill_price, fill_ts_et,
exit_price, exit_ts_et, realized_pnl, r_multiple` (fill/exit columns come from the SIM `ExecutionEngine`,
which is NOT the live exit model — see PORT_MAP X5; they are not part of the parity key).

`raid_level_type`: the best active raid's `KnownLevel` type when it is the swept level (± 1 tick), else
the nearest KnownLevel within 2 ticks, else `OTE_BAND` (the IMPULSE_LEG in-band swing sweep).

### The 8 oracle signals

| ts_et | side | entry | stop | T1 | T2 | size | level | score | window | bias | PD array | model |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 2026-09-23 04:04 | BUY | 31008.00 | 31000.00 | 31038.75 | 31052.00 | 5 | OTE_BAND | 6 | LONDON | BULLISH | BREAKER | POST_SWEEP |
| 2026-09-23 05:04 | BUY | 31015.50 | 31005.75 | 31038.75 | 31052.00 | 5 | OTE_BAND | 6 | LONDON | BULLISH | OB | IMPULSE_LEG |
| 2026-09-23 05:16 | BUY | 31014.00 | 31005.75 | 31038.75 | 31052.00 | 5 | OTE_BAND | 6 | LONDON | BULLISH | OB | IMPULSE_LEG |
| 2026-09-23 06:37 | BUY | 31019.00 | 31005.75 | 31038.75 | 31052.00 | 5 | OTE_BAND | 6 | LONDON | BULLISH | OB | IMPULSE_LEG |
| 2026-09-25 11:33 | SELL | 30847.50 | 30889.75 | 30805.25 | 30776.75 | 2 | OTE_BAND | 7 | NY_AM | BEARISH | BREAKER | IMPULSE_LEG |
| 2026-09-27 20:40 | BUY | 30813.00 | 30801.75 | 30840.00 | 30853.75 | 5 | OTE_BAND | 6 | ASIA | BULLISH | OB | POST_SWEEP |
| 2026-09-28 12:32 | SELL | 30645.00 | 30723.00 | 30558.00 | 30510.50 | 1 | LONDON_HIGH | 10 | NY_LUNCH | BEARISH | IFVG | IMPULSE_LEG |
| 2026-09-28 14:53 | SELL | 30636.00 | 30674.00 | 30558.00 | 30510.50 | 3 | LONDON_HIGH | 10 | NY_PM | BEARISH | OB | IMPULSE_LEG |

Sizes are exactly `floor($250 / (stopTicks × $0.50))` capped at 5 (PORT_MAP S5): e.g. 14:53 → 38 pt
= 152 ticks = $76/micro → 3; 12:32 → 78 pt = $156/micro → 1.

## 1. Entry type (task 00.4)

**Java today**: the signal's entry is a PD-array level inside the OTE band (OB midpoint, or the near
edge of an FVG/IFVG/BREAKER/WICK; fallback the 0.705) and the order is a **LIMIT at that price**
(`PropFirmRiskEngine.java:195-202`, `OrderType.LIMIT`, tick-rounded), submitted on the alarm bar.
LIVE places the stop only after the broker reports the fill (`LiveEngineRunner.java:1121-1164`,
`BracketOrderManager.java:1243-1274`); SIM fills the limit when the bar touches it
(`ExecutionEngine.java:434-446`). Because the alarm rule is "bar touches the band AND closes back
beyond the swept level in the trade direction" (`OteSetupDriver.impulseReaction` :466), price has
already traded through the entry level on the alarm bar; the limit is usually touched on the next bar
or not at all (Java TTL 90 min live / 80 1m bars SIM — PORT_MAP T7).

**Why V6 cannot use a TradingView limit**: a strategy order-fill alert fires only when TradingView's
broker emulator fills the order (PINE_CONSTRAINTS C-08), and only in realtime. A Pine `strategy.entry(limit=...)`
would therefore alert when TV *thinks* the limit filled; the relay then sends the broker an order at that
moment — a market order at the then-current price, i.e. a late fill with no price guarantee and a stop
whose distance no longer matches the sizing. The Pine limit would also rest only in TV, not at Tradovate.

**V6 default = `MARKET_ON_CONFIRM`** (`ENTRY_MODE` input, Agent 03 task 03.1): on the confirmed alarm
bar (`barstate.isconfirmed`, `process_orders_on_close=true`, C-07) `strategy.entry(market)` with
`strategy.exit(stop, limit)` on the same bar and the relay payload carrying `stop_loss`/`take_profit`
so the bracket is at the broker at submission (I2). `BROKER_LIMIT` (resting OTE limit at Tradovate via
the relay REST API + cancel on invalidation) is deferred to V7 (Section 9).

**Expected parity impact (recorded for Agent 06)**:

| quantity | Java (oracle) | Pine MARKET_ON_CONFIRM | expected delta |
|---|---|---|---|
| entry timestamp | alarm 1m candle (`ts_et`) | the 5m bar containing it (close) | 0 or +1 execution bar (App. E window ± 1) |
| entry price | PD-array level (limit) | alarm bar close (`process_orders_on_close`) or next-bar open | Tape numbers (`parity/bars_mnq_1m.csv`): G1 Java limit 30636.00; 1m alarm bar 14:53 close 30633.75 (−9 ticks for the short); 5m bar 14:50–14:54 close 30638.25 (+9 ticks); next 5m open 14:55 = 30638.00 (+8). The 12:32 row: limit 30645.00 vs 1m close 30628.00 (−68 ticks) vs 5m 12:30–12:34 close 30655.00 (+40). **App. E ± 4 ticks on entry will NOT hold for every trade** → Agent 06 must classify these as `ENTRY_MODE` mismatches, not bugs, and treat **stop and T1** (range/band-derived, independent of the fill) at ± 2 ticks as the decisive key. Agent 03 should evaluate on the 5m chart so the Pine fill is the 5m close containing the alarm (G1: 30638.25). |
| stop / T1 / T2 | band + PD array + 4 ticks / 0.5 / 0.382 of the range | identical formulas | 0 ± 2 ticks (tick rounding) |
| size | `floor(250 / (|entry − stop| × 2))` capped 5 | same formula with the Pine entry price and the Lucid cap | may differ by 1 micro when the entry drifts ≥ 1/size of the stop distance; exact after caps otherwise |
| RR(T1) gate | computed at the Java entry | computed at the Pine entry | a borderline RR(T1) ≈ 1.0 (oracle 09-25 11:33 has RR(T1) = 1.00 exactly) can flip → one expected unmatched signal; Agent 06 lists it as ENTRY_MODE |
| fills | SIM: limit touched → filled at limit | TV: filled at bar close (process_orders_on_close) | not compared (not part of the key) |

**Known expected ENTRY-CAP mismatch (Fable A-03v, for Agent 06).** The oracle emits **four** entries on the
2026-09-23 trading day (04:04, 05:04, 05:16, 06:37 ET; Java `maxTradesPerDay = 0` = off), while the V6
sacred invariant I5 fixes `maxEntriesPerSession = 3` per CME trading day (18:00 ET roll). Pine therefore
refuses the **4th signal, 2026-09-23 06:37 BUY 31019.00 / stop 31005.75 / T1 31038.75**, with the reject
reason `I5 maxEntriesPerSession`. Agent 06 must classify that one Java-only row as `ENTRY-CAP`, not as a
parity bug, and must NOT raise the default to make it match (AP-8; the default is sacred, Section 2 I5).
`tools/tests/test_pine_strategy.py::test_entry_cap_mismatch_is_exactly_the_09_23_fourth_signal` pins
that it is exactly this one row. Separately, a later signal can also be refused with `I1 not flat long
enough` when the earlier TV position is still open (TV holds to stop/T1; the Java SIM exits earlier, X5):
candidates 05:04 → 05:16 and 09-28 12:32 → 14:53 (G1). Agent 06 classifies those as `OPEN-POSITION`.

Recommendation to Agent 03: compute the Java entry reference (`javaEntryRef` = PD-array level) and
plot it in debug mode so OA-2's List of Trades can be diffed against both the Java limit and the
Pine market fill; evaluate the RR(T1) gate against `javaEntryRef` (as Java does), not the market fill,
so the two engines agree on WHETHER to trade; the market fill then only changes the PRICE.

## 2. Golden cases G1–G3 (task 00.6; V5 Appendix E → V6 Appendix E)

### G1 — 2026-09-28 NY PM short from the 30,640 order block — **MATCH**

| item | expected (V5 App. E / V6 App. E) | oracle (`java_signals.csv` row 8) | verdict |
|---|---|---|---|
| bias | BEARISH | BEARISH (`vote=BEARISH(3/1/0)`, `range:BEARISH[30356.75-30759.25]`) | ✅ |
| dealing range | HH 30759.25 (09:45) → LL 30356.50 (tape 30356.75) | `range=[30356.75;30759.25] eq=30558.00` | ✅ (tape tick) |
| sweep | London high 30640.00 swept 14:52–14:58, high 30650 at 14:53, score ≥ 5 | `sweep=HIGH@30640.0 sweepExtreme=30650.00 raidScore=10`, `raid_level_type=LONDON_HIGH` | ✅ |
| displacement | 15:00 bar (V5 text) | `disp=true@10:00` — the range's impulse leg (IMPULSE_LEG model, leg 09:30–10:45 ET, range/ATR 1.56, body 0.84) | ✅ (model: the leg's displacement satisfies M5; see PORT_MAP O1) |
| MSS | 15:05 close 30594.50 < 30600.75 (V5 text) | `mss=true@10:05 close 30565.0 < swing 30583.0` (impulse leg) | ✅ (same reason) |
| OTE band | [30605.50, 30673.00] | `ote=[30605.5;30673.0] inv=30759.25` | ✅ exact |
| PD array | OB ~30638–30650 | `pdArray=OB@30636.00` (ICT_OB) | ✅ |
| entry | ≈ 30635.75 (± 4 ticks under MARKET_ON_CONFIRM) | **30636.00** (1 tick) | ✅ |
| stop | ≈ 30675–30725 | **30674.00** (4 ticks below the V5 band's low edge; = max(f79 30673.00, OB far edge, sweep high 30650) + 4 ticks) | ✅ (within 1 pt of the band) |
| T1 / T2 / T3 | 30558 ± 1 / 30510.25 / 30356.50 | **30558.00 / 30510.50 / 30356.75** | ✅ |
| RR(T1) | ≈ 2.0 with a 39-pt stop | 2.05 (38-pt stop) | ✅ |
| size | 3 micros at $250 ($78/micro) | **3** ($76/micro) | ✅ |
| emit time | NY PM, 14:53 ET | 14:53 ET (`oteArmed=14:53 oteAlarm=14:53`) | ✅ |

Note: the engine ALSO emitted the earlier 12:32 ET short (SELL 30645.00, stop 30723.00, 1 micro, IFVG) on
the same band after the 12:24 arm — a valid oracle row (NY_LUNCH) that the owner did not take. Pine
must reproduce it too; the owner's manual book is not the oracle.

### G2 — 2026-09-25 NY AM bearish sequence — **MATCH**

| item | expected | oracle (row 5) | verdict |
|---|---|---|---|
| range | HH ~30926.50 → LL 30684 (owner's 30721 marker is an intraday swing) | `range=[30684.00;30926.50] eq=30805.25` (AUTO window: RTH impulse leg ≥ 400 ticks) | ✅ |
| bias | BEARISH | BEARISH `vote=BEARISH(0/3/1)` | ✅ |
| OTE armed | OTE_ARMED with the same anchor rule | `ote=[30833.75;30874.5]`, armed 11:32, alarm 11:33 | ✅ |
| sweep / score | bearish sweep inside the band | `sweep=HIGH@30860.5 raidScore=7` | ✅ |
| entry / stop / T1 | recorded in A-04 (V5) = SELL 30847.50 / 30889.75 / 30805.25 | identical | ✅ |
| size | 2 | 2 ($84.50/micro) | ✅ |

### G3 — 2026-09-28 London 02:00–08:00 ET all-sessions proof — **PASS (evaluated, correct "no setup")**

Evidence: `FunnelAutopsyHarness` cfg A over the same resources tape, transcript window 02:00–08:00 ET
(360 bars): `kzOpen=true` on **360/360** bars (V5 baseline: 100 % `await-rearm-kzClosed`). Holding
gates: `M5-no-recent-displacement` 130, `GATE-M2b` 82, `M4-no-sweep` 52, `M7-awaiting-band-touch` 45,
`INVALIDATED-await-rearm` 26, `M5-displacement-wrong-direction` 20, `M5-fvg-link-pending` 5. The machine
reached OTE_ARMED twice (02:00 on `HIGH@30688.125`, 05:30 on `HIGH@30630.25`, bands [30665.25;30675.25]
and [30592.25;30609.0]) and was refused by **M2b** (premium/discount: a short proposed on the discount
side of the governing range) both times; SWEEP_DONE on `HIGH@30581.25` at 06:30 (the owner's marked sweep)
died in M5 (no displacement). Range: London high 30679.00 / low 30531.00 as in V5 App. E. Deaths in the
window: 2 × "OTE window expired", 2 × "expired: SWEEP_DONE→DISPLACEMENT". Verdict: the window was
EVALUATED and correctly produced no trade — a PASS for Pine parity (Pine must also take no trade here;
M2b must be PORT_EXACT on the RD branch, PORT_MAP O12).

## 3. Hand-off

- Agents 02/03: PORT_MAP rows + PINE_CONSTRAINTS; the oracle's `gate_proof` column gives the exact
  numbers every detector must reproduce on the 8 bars (and the two G3 refusals).
- Agent 05 (05.4 `pine_parity.py`): read the first 12 columns as the key; treat `ts_et` as the 1m
  emit candle and match within ± 1 five-minute bar; expect `ENTRY_MODE` deltas per §1.
- Agent 06: the bars CSVs are the data-drift reference (`ts_utc` column for TradingView's UTC export).
