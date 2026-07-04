[MODEL CHECK] fable-5 ✓

# SA2 — Detector Wiring Notes (STDV+OTE hooks → live detector feeds)

Date: 2026-07-03 · Branch: `feature/sa5-wiring-scalp-mode`
Scope: closes the wiring gaps identified in `SA1_detector_contract_map.md`. All wiring lives in `StdvOteRunnerStrategy`; `StdvOteStrategy` core remains detector-free and pure (one targeted core bug fix, see §Core bugs).

---

## Per-hook wiring

### `recordHtfBias(MarketBias)`
Fed from a `BarAggregationManager(symbol, 500)` + `HtfTrendAnalyzer(symbol, barManager)` constructed in the runner. Every 1m candle goes through `barManager.processCandle(...)`; the trend analyzer is updated **only when the returned completed-bar map contains an M15 or M30 bar**, and the hook fires **only on a mapped-bias change at those closes**. Mapping: STRONG/WEAK_BULLISH → BULLISH, STRONG/WEAK_BEARISH → BEARISH, RANGING → NEUTRAL. The previous feed (`IctStructureDetector.getBias()` on the raw 1m stream) is gone — 1m noise can no longer thrash the hook, which invalidates on bias flips. On startup the analyzer is RANGING → NEUTRAL, `lastBias` is NEUTRAL, no call is made, and the machine stays IDLE (covered by `neutralHtfBiasStaysIdle`).

### `recordManipulationLeg(legLow, legHigh, tickSize, snapTolTicks)`
Fed from the new pure `ManipulationLegDetector` (static `detect(...)`, ~60 lines): given the candles buffered since the killzone open, it returns the Judas-swing leg — the extreme excursion **against** the HTF bias beyond the killzone open price, paired with the opposing extreme from open→excursion, terminated by a reclaim bar that closes back through the open price. The runner buffers killzone candles (cleared on each killzone-open transition, capped at 600 bars for the London window). **Precedence:** while a killzone is open the Judas detector is authoritative — no fallback fires (a coarse swing pair mid-killzone would anchor the STDV ladder to unrelated structure). Outside any killzone the detector can never have an anchor, so the legacy most-recent-swing-pair input (`IctStructureDetector.getLastSwingHigh/Low`) applies unchanged. Snap tolerance stays 3 ticks; minimum leg = `StdvProjectionEngine.DEFAULT_MIN_LEG_TICKS` (8).

### `recordSweep(LiquiditySweep, raidScore)`
The raid pipeline is now actually fed: `raidDetector.processCandle(candle, RaidDetectionContext.fullWithCascade(hasSmt, htfBullish, htfTrendStrong, 0, displacementEntry, 0))` runs every primary candle (SA1 GAP 1 closed — previously `getActiveRaids()` was permanently empty). The sweep still comes from `LiquidityDetector.getLastSweep()` gated by `hasRecentSweep(3)` and bias-direction match. The score passed to the hook is the **real 1–10 `qualityScore`** of the direction-matched active raid (`getActiveRaidByDirection(LOW_SWEEP/HIGH_SWEEP)`) when the pipeline has one; when no tracked raid exists (e.g. no known levels yet), it falls back to the instrument base (`spec.raidMinQuality()`), matching pre-wiring behaviour so M4 remains satisfiable exactly at the floor. The old "+2 if any raid exists" heuristic is removed in favour of the real score. Idempotency: the consumed sweep's timestamp is recorded and the same sweep event is never consumed twice.

### `recordDisplacement(FairValueGap)`
Now uses the displacement↔FVG linkage that already existed inside `DisplacementDetector`: when `getLastDisplacement().createdFvg()`, the hook receives a `FairValueGap` built from `getDisplacementFvgZone()` — the exact 3-candle gap the displacement itself created — instead of the newest same-direction unfilled FVG from `FvgDetector` (which may be unrelated). The `FairValueGap` is constructed directly from the zone rather than matched against `FvgDetector.getUnfilledFvgs()` because `FvgDetector` marks every FVG filled on its creation bar (`candle.low <= fvg.top` is true by construction for the c3 candle), so the unfilled list is empty in practice; constructing from the detector's own zone is both more correct and avoids touching the shared `FvgDetector` (which the legacy rollback strategy also uses). The newest-unfilled-FVG pick remains only as a fallback when the displacement created no FVG. Idempotency: displacement-event timestamp is tracked; the same displacement is never consumed twice.

### `recordMss()`
Unchanged source (`MarketStructureShiftDetector.update(...)` event return, freshness ≤ 30 bars, counter-bias MSS invalidates). New: on successful consumption the runner arms the `ImpulseLegTracker` with the true post-MSS leg — origin = lowest low since the sweep (tracked from the consumed sweep's level/bar onward), terminus = the MSS candle extreme.

### `recordOteImpulse(impulseLow, impulseHigh, tickSize, reactionConfirmed)`
Fed from the new pure `ImpulseLegTracker` instead of the pre-MSS swing accessors (SA1 GAP: the swing pair could predate the MSS entirely). The tracker's origin is fixed at the post-sweep extreme; the terminus extends bar-by-bar in the impulse direction; trading through the origin marks the leg violated and the runner invalidates the setup ("impulse origin violated before OTE entry"). `reactionConfirmed` is **derived from observable price action** — `ImpulseLegTracker.isRejectionReaction(candle, tickSize, minWickTicks)`: the candle traded into the prospective 0.62–0.79 band, closed back in the impulse direction, and left a rejection wick ≥ `stdvOte.reactionWickTicks` (default 2). The literal `true` is gone. (`IctStructureDetector` has no CHoCH accessor per SA1, so the rejection-wick test is the sanctioned proxy.)

### `tryEmit(tickSize, stopBufferTicks, tier, sizeRequest)`
Unchanged inputs except `stopBufferTicks` (below). Tier evaluation and the fixed size table are untouched.

---

## Decisions

- **`stopBufferTicks`** — now read once at construction from system property **`stdvOte.stopBufferTicks`**, following the `stdvOte.enabled` pattern; **default 4**, preserving legacy behaviour byte-for-byte (the master plan's suggested default of 2 was deliberately NOT adopted; scalp mode picks its own value in SA3). Invalid values log a warning and fall back to 4. Covered by `stopBufferTicksIsConfigurable`.
- **`stdvOte.reactionWickTicks`** — new tunable (default 2) for the OTE rejection-wick minimum; same config pattern.
- **Sizer deferral** — `StdvOteSizer` remains **unwired by design**: it needs equity + MLL-floor context from the runner and wiring it would change live sizing; SA3's risk-profile work owns that. The tier-driven fixed table {T4:18, T3:14, T2:10, T1:6}, clamped to `[5, 20]`, is unchanged.
- **`initialize()`** — implemented: resets every collaborator that supports reset (structure/liquidity/FVG/displacement/MSS detectors, raid detector, candle series, correlation tracker, bar manager), rebuilds `HtfTrendAnalyzer` (it has no reset), clears all runner transient state and timestamps, and returns the core to IDLE via `resetForNextWindow()`. `onSessionEnd()` still fires the core's mid-setup invalidation first, then clears per-session wiring state (sweep/displacement consumption markers, impulse tracker, killzone buffer); the HTF aggregation and level engine intentionally survive session boundaries (cross-session context). LevelEngine/EqualLevelDetector expose no reset; `initialize()` is documented as pre-stream/idempotent rather than a mid-day wipe.
- **Idempotency guards** — primary and SMT feeds each enforce strict timestamp monotonicity (duplicates and out-of-order candles are dropped before any detector sees them); sweep and displacement events are consumed at most once (timestamp identity). All covered in `StdvOteWiringIntegrationTest`.
- **Determinism** — no clock seam was needed: `KillzoneClock`, `SilverBulletClock`, `BarAggregationManager` boundaries and `RaidDetector`'s internal clocks are all pure functions of the candle `Instant`; nothing in the decision path reads wall clock (`HtfTrendAnalyzer` uses `Instant.now()` only for a cosmetic last-state-change stamp).

---

## Core bugs found + fixed (with regression tests)

1. **M9 poisoned-retry loop** (`StdvOteStrategy.tryEmit`): any failed emission attempt wrote `ctx.lastGateFailed` (e.g. "M8"), and the next attempt then failed the M9 gate (which checks `lastGateFailed == null`) — permanently, since the M9 failure re-wrote the field. Same for the transient "M7: no PD array" hint written by `recordOteImpulse` while the leg is still extending. Fix: the core tracks whether it wrote the diagnostic itself (`gateDiagnosticSelfWritten`) and clears only self-written diagnostics at the top of `tryEmit`; an externally written pre-flight rejection (the real M9 contract, SA3+) is still honoured. Regression tests: `StdvOteEmissionRetryTest` (3 tests, including the external-pre-flight case).
2. **OTE-window double counting** (runner, flagged by SA1 §3): `tryEmitOrder()` incremented `barsInOte` a second time on emission failure, effectively halving the 8-bar OTE window under retries. The extra increment is removed; only the per-candle counter remains.

Also noted (not fixed, out of scope): `FvgDetector` marks every FVG filled on its own creation bar, so `getUnfilledFvgs()` is effectively always empty — the displacement-zone construction above routes around it; a detector-level fix would change legacy-strategy behaviour and belongs in its own change.

---

## New components + tests

| Component | Purpose | Tests |
|---|---|---|
| `ImpulseLegTracker` (main) | Post-MSS impulse extremes + OTE rejection-reaction test | `ImpulseLegTrackerTest` — 19 tests |
| `ManipulationLegDetector` (main) | Judas-swing leg from killzone open (pure static function) | `ManipulationLegDetectorTest` — 10 tests |
| `StdvOteWiringIntegrationTest` | Deterministic 128-candle end-to-end fixture: warmup 15m structure → bias at 14:00Z close → Judas dip/reclaim → sweep 21012 → displacement FVG [21020, 21023] → MSS > 21035 → OTE retrace → LONG_ENTRY @ 21023, stop 21011 (sweep − 4 ticks), target −2σ = 21058, RR ≈ 2.92 (M7 floor ≥ 2.0 passed with no gate weakened) | 5 tests (happy path, stop-buffer property, dupe/out-of-order drops, session-end invalidation, NEUTRAL-bias IDLE) |
| `StdvOteEmissionRetryTest` | M9 poisoning regression | 3 tests |

All pre-existing stdvote tests pass unmodified.
