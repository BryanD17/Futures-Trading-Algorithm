[MODEL CHECK] fable-5 ✓

# SA4 — Frequency & Gate Restructure (full killzones, binary raid gate, re-arm with cooldown)

Date: 2026-07-03 · Branch: `feature/sa5-wiring-scalp-mode` · Base: c266734
Scope: makes multiple trades per session structurally possible in scalp mode (`-DscalpMode.enabled=true`) WITHOUT loosening the setup sequence. Legacy (`scalpMode.enabled=false`) is byte-for-byte unchanged — `StdvOteLegacyGoldenTest` and every pre-existing test pass unmodified.

---

## 1. Gate table — before/after for EVERY gate

| Gate | Legacy behavior | Scalp behavior | Config key |
|---|---|---|---|
| M1 instrument ∈ {MNQ, MES, MGC} | blocking | **unchanged** | — |
| M2 HTF bias non-neutral + direction match | blocking; bias flip / NEUTRAL invalidates | **unchanged** | — |
| M3 time gate (`ctx.killzoneOpen` ← `isInstrumentKillzone`) | NY killzones (9:45–12:30, 13:45–16:00 ET) ∪ Silver Bullet windows (3–4, 10–11, 14–15 ET); MGC additionally full London session 3:00–12:00 ET | **Full KillzoneClock killzones only**: NY AM 9:45–12:30 ET ∪ NY PM 13:45–16:00 ET; MGC additionally London restricted to its PRIME window (default 3:00–5:00 ET — a NARROWING of legacy's 3:00–12:00). The SB-only 3:00–4:00 ET window no longer opens indices | `scalp.londonPrimeStartEt` (03:00), `scalp.londonPrimeEndEt` (05:00) |
| Silver Bullet hard gate | part of the M3 union (grants entry windows) | **not a hard gate** — remains a scoring input only: `RaidDetector.processCandle` stamps the SB window on the scoring context (RaidDetector L276–278) and `RaidQualityScorer` awards **+1** inside it (L109–112) — verified functioning | — |
| M4 sweep + raid score ≥ instrument minimum (MNQ/MES 5, MGC 6) | blocking | **unchanged** (floor NOT lowered) | — |
| **NEW: binary raid-quality gate** | none | **blocking, at sweep-record time, STRICT (SA5)**: a sweep whose raid score is `< scalp.minRaidScore` (default 6) is rejected — EVERY score is subject to the floor (pipeline-differentiated, starved-pipeline base fallback, and exact-base alike). The machine stays `MANIP_DONE` so a later, better sweep can still arm inside the window. See §2 | `scalp.minRaidScore` (6) |
| M5 displacement + FVG in bias direction | blocking | **unchanged** | — |
| M6 MSS / CHoCH in bias direction | blocking; counter-bias MSS invalidates | **unchanged** | — |
| M7 OTE geometry + RR band | blocking; band [2.0, +∞) via `topstep50k()` signal band | **unchanged from SA3**: band [0.8, 1.5] via `topstep50kScalp()`; geometry checks identical | (SA3) `scalpMode.enabled` |
| Tier ladder (runner `computeTier`) | `tier == null` (raid score below instrument base) → `invalidate("no qualifying tier")` — **blocks emission** | **never blocks emission**: `tier == null` → `TIER_1` fallback; tier is still computed and informs sizing only (sizer tier caps 8/12/16/20) | — |
| M8 size ∈ [5, 20] micros | blocking | **unchanged** | — |
| M9 diagnostics clean (`lastGateFailed == null`) | blocking | **unchanged** (SA2 self-written-diagnostic clearing honoured) | — |
| Risk engine DLL/MLL/drawdown/flatten | blocking (`PropFirmRiskEngine`) | **unchanged** — no Topstep rail touched | — |
| Risk engine RR band / maxContracts / riskPerTrade | 3.0–6.0 / 5 / $250 | **unchanged from SA3**: 0.8–1.5 / 20 / $150 via `topstep50kScalp()` | (SA3) |
| Risk engine frequency gates (`maxTradesPerDay` 6, `maxConsecutiveLosses` 3) | disabled (0) on legacy profiles | **unchanged from SA3** (blocking in `PropFirmRiskEngine.evaluate` §3b) — and now **mirrored at re-arm**: the runner refuses to arm a setup those gates would block (reads `AccountState.getTradesToday()` / `getConsecutiveLosses()` from the `StrategyContext`) | — |
| One-move discipline | `IN_TRADE` / `INVALIDATED` terminal — max ONE emission per instrument per JVM run; `resetForNextWindow()` never called in production | **re-arm (SA4)**: after the position closes (via `PositionClosedEvent`) or the setup invalidates, `resetForNextWindow()` fires once (a) the cooldown elapsed, (b) a killzone is open, (c) no position is open on the symbol, (d) the frequency-gate mirror passes. Sweep/displacement consumption markers are KEPT across re-arm (old events can never be re-consumed); MSS/impulse state is cleared (a fresh MSS is required); HTF bias is re-seeded from the runner's live bias | `scalp.rearmCooldownBars` (5) |
| No-overlap rule | implicit (single-shot per JVM) | **explicit and double-enforced**: (1) re-arm requires `!positionOpen` (event-tracked) AND `!context.hasPosition(symbol)`; (2) the emission path itself skips `tryEmit` while a position is open on the symbol. NOTE: the risk engine alone would NOT prevent overlap for scalp sizes (`maxTotalContracts` 20 admits e.g. 6+6), hence the runner-level rule | — |
| Sizing | fixed tier table {T1:6, T2:10, T3:14, T4:18} clamped [5, 20]; `StdvOteSizer` unwired | **`StdvOteSizer.decide` wired** (§4): equity + MLL floor from the `StrategyContext` account; risk budget = min(12% of available room, `riskPerTrade` $150); caps = min(tier cap, `maxContracts` 20, instrument 20); floor 5 or stand-down (never 1–4). Falls back to the legacy tier table when no account state is available | `scalp.sizerSafetyCushion` (200) |

Setup sequence (bias → leg → sweep → displacement+FVG → MSS → OTE) fully intact in both modes: SA4 touched only the time window, the tier ladder's blocking role, and post-terminal re-arm.

---

## 2. Binary raid gate — placement, STRICT semantics (SA5 fix)

Implemented in `StdvOteStrategy.recordSweep(sweep, score)` (scalp mode only): any score `< scalp.minRaidScore` is rejected **at sweep time** — deliberately earlier than emission, so a low-quality sweep does not burn the 40-bar window (a later ≥floor sweep can still arm; pinned by `betterSweepAfterRejectionStillArms`).

**STRICT (SA5):** the floor applies to EVERY sweep score, with no provenance distinction. The SA4 first cut carried a bypass (a starved-pipeline fallback score, or a tracked raid scoring exactly the instrument base, skipped the check); SA5 removed it because it violated the hard success criterion "no trade emits with raid quality score < 6 in scalp mode". The conservative rule now in force: **a score that cannot be shown ≥ `scalp.minRaidScore` does not trade in scalp mode.** Concretely:

1. **Starved pipeline** (no tracked raid): the runner's fallback score is the instrument base (`spec.raidMinQuality()` — 5 for MNQ/MES, 6 for MGC). For the index instruments that is `< 6` and the sweep is REJECTED (the window stays alive per the original SA4 design; a later sweep that produces a real ≥6 raid can still arm). For MGC the base equals the default floor, so a starved-pipeline sweep still passes at exactly 6.
2. **A tracked raid scoring below the floor** (including exactly the base): REJECTED — same rule, no exceptions.

The branch's scalp fixtures were reworked to reach the floor legitimately (`StdvOteScalpFixture`): an EQUAL_LOW cluster of 3 fractal swing lows (21014.02 / 21014.00 / 21013.98, within the EqualLevelDetector clustering tolerance, strictly descending so MSS bearish structure survives) is raided by the sweep candle after the HTF bias flip, scoring HTF aligned +2, NY AM killzone +2, Silver Bullet window +1, strong equal level (cluster ≥ 3) +1 = **6**. `scalp.minRaidScore` was NOT lowered anywhere; M4's instrument floors are untouched and legacy has no floor at all. Pinned by `StdvOteScalpRaidGateTest.fallbackScoreIsRejectedStrictly` (fallback 5 rejected, later 6 arms) and the reworked scalp integration tests.

---

## 3. Re-arm + PositionClosedEvent mechanics

- **`PositionClosedEvent`** (new, `event` package, reuses the pre-existing `EventType.POSITION_CLOSED` slot and the `EventBus.mapClassToEventType` mapping that already referenced the class name). Carries symbol, pnl, win flag, and the market exit timestamp (deterministic in backtests; wall clock at the live bracket funnel).
- **Published at the SAME funnels that count trades** (`AccountState.recordTradeCompleted`, per SA3):
  - `ExecutionEngine.closePosition` (sim/backtest) — engine gained an optional `setEventBus(...)`; wired in `SimEngineRunner`, `BacktestRunner`, `LiveEngineRunner`;
  - `LiveEngineRunner`'s bracket `onStopLossFilled` / `onTakeProfitFilled` handlers (live closes bypass `ExecutionEngine.closePosition`).
- **Consumption**: `StdvOteRunnerStrategy` subscribes (scalp mode only). The async handler only flips an `AtomicBoolean` — all state mutation happens flag-and-apply on the candle thread (`SetupContext` is thread-confined per SA1 §6.1).
- **Cooldown**: detection bar (close observed or `INVALIDATED` transition) starts `scalp.rearmCooldownBars` (default 5); full bars are counted; on expiry the re-arm gates (killzone open, no-overlap, frequency-gate mirror) are re-checked every bar until they pass. Invalidation-triggered re-arm is detected internally from the state transition — no event needed.
- **What re-arm resets / keeps**: resets core to `IDLE` (`resetForNextWindow`), clears MSS/impulse/OTE trackers and post-sweep extremes, re-seeds HTF bias from the runner's live bias (reset wipes it to NEUTRAL and the bias hook only fires on change at 15m/30m closes). KEEPS the sweep/displacement consumption timestamps (old events can never arm the new setup) and the killzone candle buffer (same-killzone Judas anchor).
- Legacy path: none of this executes — no production `resetForNextWindow()` call on the legacy path; one-move discipline intact.

---

## 4. Sizer decision

`StdvOteSizer.decide(SizeRequest, SizeContext)` **is now wired** for scalp-mode emissions (runner `scalpSize(...)`):

- `equity` = `AccountState.getEquity()` from the `StrategyContext` (the runners all pass `DefaultStrategyContext(accountState)`);
- `mllFloor` = `getHighestEndOfDayBalance() − activeRiskLimits.getMaxLossLimit()`;
- `safetyCushion` = `scalp.sizerSafetyCushion` (default $200);
- `riskFraction` = `min(0.12, riskPerTrade / availableRoom)` — the risk budget is therefore `min(12% of room, $150)`, honouring both the sizer's canonical formula and the scalp profile's `riskPerTrade`;
- `topstepMicroMax` = `activeRiskLimits.getMaxContracts()` (20) — runner sizing can never exceed the risk engine's cap;
- the sizer clamps to the instrument band [5, 20] and returns 0 (stand down, no emission this bar) rather than 1–4 micros.

Worked (both fixture emissions, pinned by test): $50k account → room = 50,000 − 48,000 − 200 = $1,800; budget = min(216, 150) = $150; MNQ 12-pt stop × $2/pt = $24/contract → 6 micros (≤ TIER_1 cap 8).

FLAGS: `newsMultiplier` is fixed at 1.0 (the runner has no news context — pre-existing gap); when no account state is available (null context, e.g. some tests), sizing falls back to the bounded tier table — unchanged [5, 20] clamp. `PropFirmRiskEngine` still re-derives the final quantity from `riskPerTrade` and `maxContracts`, so the engine remains the last word.

---

## 5. Flatten-time verification (task 1 check)

Actual semantics in code: `LiveEngineRunner.checkFlattenByTime` runs on a 60-second scheduler; when `now(CT) > riskLimits.getFlattenByTime()` (**15:10 CT** on every Topstep profile, scalp included) and before 16:00 CT, it calls `flattenAllPositions(...)` (cancels brackets, market-closes, clears account state). `handleStrategySignal` drops signals while flattening is in progress. `PropFirmRiskEngine` itself has no time gate.

Ordering: the scalp NY PM window closes at **16:00 ET = 15:00 CT**, i.e. the M3 gate stops all scalp entries **10 minutes BEFORE** the 15:10 CT (= 16:10 ET) flatten deadline. There is no window between the flatten and the 16:00 ET close for an entry to slip through — the close comes first, and the flatten still dominates any position left open. Pinned by `StdvOteScalpWindowsTest.noWindowBetweenCloseAndFlatten` (16:00 / 16:05 / 16:09 / 16:10 ET all closed). Legacy is equally safe: its M3 union also ends at 16:00 ET (last SB window ends 15:00 ET; MGC London ends 12:00 ET).

---

## 6. New config, code, tests

Config (all `scalp.*`, read-once at runner construction, invalid values fall back):
`scalp.minRaidScore`=6 · `scalp.rearmCooldownBars`=5 · `scalp.londonPrimeStartEt`=03:00 · `scalp.londonPrimeEndEt`=05:00 · `scalp.sizerSafetyCushion`=200

| Change | File |
|---|---|
| NEW `PositionClosedEvent` | `event/PositionClosedEvent.java` |
| Publish at close funnels | `execution/ExecutionEngine` (`setEventBus` + `closePosition`), `LiveEngineRunner` (2 bracket handlers + `setEventBus`), `SimEngineRunner`, `backtest/BacktestRunner` |
| Binary raid gate (core, scalp only) | `StdvOteStrategy` (`recordSweep` 3-arg + `enableScalpMode(calculator, floor)`) |
| Scalp windows, re-arm engine, no-overlap, sizer wiring, tier non-blocking | `StdvOteRunnerStrategy` |
| New tunables | `ScalpConfig` |

Tests (19 new, all green; 419 total across both modules, 0 failed, 0 skipped/disabled):
- `StdvOteScalpFrequencyIntegrationTest` (3) — (a) TWO complete trades in one NY AM killzone off one deterministic fixture, closing trade 1 through the REAL `ExecutionEngine` funnel (fill → target → `closePosition` → event → cooldown → re-arm → second full sequence); (c) no re-arm while the position is open; (d) no re-arm inside the cooldown (same feed, cooldown 50 → exactly one emission).
- `StdvOteScalpRaidGateTest` (5) — (b) raidScore-5 REJECTED / raidScore-6 PASSES; rejected sweep keeps the window alive; starved-pipeline fallback REJECTED (strict, SA5); legacy has no floor.
- `StdvOteScalpWindowsTest` (6) — full killzones, SB no longer a hard gate (but legacy keeps it), MGC London prime + configurability + MGC-only, flatten-gap check.
- `PositionClosedEventFunnelTest` (2) — the funnel publishes exactly once, with market exit time; no-bus legacy path unaffected.
- `ScalpFrequencyConfigTest` (3) — defaults / overrides / invalid-value fallbacks.
- Fixture: `StdvOteScalpFrequencyFixture` extends `StdvOteScalpFixture` (SA5: the golden fixture plus a legitimate equal-lows cluster so both acts' raids really score 6 — see §2) with a round-trip candle, cooldown bridge, and a legitimate second act (fresh sweep/displacement/MSS/OTE). `StdvOteGoldenFixture` and the golden expectations are untouched.

## 7. Flags / deferrals

1. ~~Base-score bypass on the binary gate~~ — **RESOLVED by SA5**: the gate is now strict (§2); the scalp fixtures were reworked to reach raid score ≥ 6 legitimately.
2. **Emitted-but-never-filled signals**: `positionOpen` is set at emission and cleared only by `PositionClosedEvent`. If the risk engine denies the order (e.g. trade #7), no position ever opens and no close event fires — re-arm stays blocked for that run. Safe direction (fewer trades); a future `ORDER_REJECTED` subscription could release it.
3. Re-arm may also fire in a LATER killzone (e.g. invalidated at NY AM close → re-arms when NY PM opens) — intentional, addresses SA1 blocker #7 (session-end dead-start); the frequency gates still cap the day.
4. The frequency-gate mirror at re-arm needs the `StrategyContext` account; without it the mirror is skipped and `PropFirmRiskEngine` remains the (only, still blocking) enforcement.
5. Live-path `PositionClosedEvent.closedAt` uses wall clock (broker fills carry no candle timestamp at that funnel).
