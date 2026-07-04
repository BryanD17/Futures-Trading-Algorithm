[MODEL CHECK] fable-5 ✓

# SA3 — Scalp Target & Risk Model (1R-capped targets, topstep50kScalp profile, single-TP exits)

Date: 2026-07-03 · Branch: `feature/sa5-wiring-scalp-mode` · Base: 36f07c2
Scope: converts the exit/target model and risk profile to a 1R-capped scalp system behind `-DscalpMode.enabled=true` (DEFAULT **OFF**). Legacy behaviour is proven byte-for-byte identical by a golden-file regression test captured at HEAD before any change.

---

## 1. Master switch

| Property | Default | Meaning |
|---|---|---|
| `scalpMode.enabled` | **false** | Master switch. Mirrors the `stdvOte.enabled` pattern exactly (`ScalpConfig`, read via `System.getProperty`, runtime `-D` toggle, no code change to flip). |
| `scalp.breakevenAtHalfR` | true | Move the stop to entry (+2-tick buffer) once price reaches +0.5R. |
| `scalp.minTargetClearanceTicks` | 2 | A target must clear entry by at least this many ticks. |
| `scalp.candidateWindowR` | 1.5 | Candidates farther than this many R are invalid → exactly-1R fallback. |

Selection flows through `StdvOteFactory.build(...)` → `StdvOteRunnerStrategy` constructor, which reads `ScalpConfig` once: it injects a `ScalpTargetCalculator` into the core (`StdvOteStrategy.enableScalpMode`) and the active `RiskLimits` signal band into the validator. The core itself reads no system properties (stays pure/unit-testable).

The 1R cap itself is **deliberately not a property** (`ScalpTargetCalculator.TARGET_CAP_R = 1.0`): it is the definition of the model and the number SA5's Monte Carlo validates.

## 2. Target algorithm (`ScalpTargetCalculator` — pure, no detector imports)

Inputs: `entry`, `stop`, `bullish`, `tickSize`, `opposingLiquidity` (nullable), `fvgOrigin` (nullable).

1. `risk = |entry − stop|` (long: entry − stop). `risk ≤ 0` → **reject** "stop distance zero/negative".
2. **Candidate A** — nearest opposing liquidity level in the trade direction. Sourced by the runner each candle: `LiquidityTargetIdentifier.findAllTargets(close, bias)` nearest-first (unraided, significance ≥ 3), falling back to `LevelEngine.getNearestUnraidedLevelAbove/Below(close)` (per SA1 §6.4). Passed into the core via the narrow setter `StdvOteStrategy.setNearestOpposingLiquidity(Double)`.
3. **Candidate B** — FVG origin: the far edge of the displacement FVG already recorded via `recordDisplacement` (long → `fvg.getTop()`, short → `fvg.getBottom()`).
4. A candidate is **valid** iff: on the profit side of entry (wrong side → *excluded*, not a rejection), clears entry by ≥ 2 ticks, and lies within `1.5R` of entry.
5. Target = **closer** valid candidate (exact tie → liquidity), then **hard-capped at 1R**.
6. No valid candidate → target = **exactly 1R**.
7. Distance floors to the tick grid (never rounds past the cap). If the final distance is < 2 ticks (e.g. degenerate sub-2-tick risk) → **reject** "target at/inside entry".

Rejections write `ctx.lastGateFailed = "SCALP: <reason>"` using the same self-written-diagnostic mechanism as validator failures, so a rejected attempt cannot poison the M9 gate on retry (SA2's fix is honoured).

### Worked LONG (MNQ, tick 0.25)

Setup: sweep of 21012.00, OTE entry at the displacement-FVG top.

| Quantity | Value |
|---|---|
| Entry | **21023.00** |
| Stop (sweep − 4 ticks, `stdvOte.stopBufferTicks`) | **21011.00** |
| Risk (1R) | 12.00 = 48 ticks |
| Candidate A (nearest unraided opposing level) | 21033.00 → +10.00 = 0.833R, within [2 ticks, 1.5R=18.00] → **valid** |
| Candidate B (FVG [21020.00, 21023.00], far edge = top) | 21023.00 = entry → +0 ticks < 2 ticks → **excluded** |
| Closer valid candidate | A @ 21033.00 |
| Cap check | 0.833R ≤ 1R → uncapped |
| **Target** | **21033.00**, RR **0.83** → validator band [0.8, 1.5] ✓ |

Variants: A at 21038.00 (+1.25R, valid) → capped to **21035.00** (exactly 1R). No A, B excluded → fallback **21035.00** (exactly 1R) — this is precisely what the golden fixture produces end-to-end (`StdvOteScalpModeIntegrationTest.fixtureFallsBackToExactlyOneR`).

### Worked SHORT (MNQ, tick 0.25)

Setup: sweep of buyside at 21008.00, bearish displacement FVG [20987.50, 20990.50].

| Quantity | Value |
|---|---|
| Entry | **21000.00** |
| Stop (sweep + 4 ticks) | **21012.00** |
| Risk (1R) | 12.00 = 48 ticks |
| Candidate A (nearest unraided level below) | 20989.75 → −10.25 = 0.854R → **valid** |
| Candidate B (FVG far edge = bottom) | 20987.50 → −12.50 = 1.042R → **valid** (≤ 1.5R) |
| Closer valid candidate | A @ 20989.75 |
| Cap check | 0.854R ≤ 1R → uncapped |
| **Target** | **20989.75**, RR **0.85** → band ✓ |

Variant: A absent → B at 1.042R → capped to exactly 1R → target **20988.00**.

## 3. Validator RR band decision (the legacy trap and how it was avoided)

`MandatoryConfluenceValidator.validateStdvOte` M7 previously hardcoded `MIN_RR_FLOOR_STDV_OTE = 2.0`. But `topstep50k().minRiskRewardRatio` is **3.0** — wiring the validator naively to `minRiskRewardRatio` would have silently tightened legacy emission from 2.0 → 3.0 (killing every 2.0–3.0 RR setup that emits today).

Decision: `RiskLimits` gained an **explicit validator signal band** — `signalMinRr` / `signalMaxRr`:

| Profile | signalMinRr | signalMaxRr | minRiskRewardRatio (risk engine) | maxRiskRewardRatio |
|---|---|---|---|---|
| `topstep50k()` (builder defaults; factory source untouched) | 2.0 | +∞ | 3.0 | 6.0 |
| `topstep50kScalp()` | 0.8 | 1.5 | 0.8 | 1.5 |

- The **builder defaults** are `[2.0, +∞)` — exactly today's effective validator behaviour — so `topstep50k()`/`topstep100k()`/`topstep150k()` source stayed **byte-for-byte untouched** yet carries the band.
- The validator takes the band via `setActiveRiskLimits(RiskLimits)`; with nothing injected the historical constants apply (identical result either way). `MIN_RR_FLOOR_STDV_OTE` is still there and still 2.0.
- Proof: `StdvOteValidatorRrBandTest.legacyProfileDoesNotTightenTo3` passes a 2.5-RR context with `topstep50k()` injected. `StdvOteLegacyGoldenTest` proves the end-to-end numbers.

Note the intentional asymmetry: legacy keeps **no** validator ceiling (matching history) even though the risk engine has always had its own 6.0 max; scalp gets a real two-sided band because a "scalp" at 3R is not a scalp — it means the stop/target geometry escaped the model.

## 4. Profile selection wiring

The single selection point is **`ScalpConfig.activeRiskLimits()`**:
`scalpMode.enabled` → `RiskLimits.topstep50kScalp()`, else `RiskLimits.topstep50k()` exactly as today.

All four legacy `topstep50k()` call sites now route through it (SA1 §Scalp Blockers #3): `SimEngineRunner` (default ctor), `LiveEngineRunner` (default ctor), `BacktestExample.main`, `EngineFacade.getRiskLimits()` (uninitialised fallback). The runner also feeds the same object's signal band to the validator, so the risk engine and validator can never disagree about which mode is active.

## 5. Scalp risk profile — key numbers and why

- **riskPerTrade $150** (NOT 250–500): with a $1,000 DLL and up to 6 trades/day, $250 risk means 4 losses = DLL breach — a math-guaranteed bust on an ordinary bad day. $150 × 6 = $900 worst case keeps the day alive. SA5 will Monte-Carlo this.
- **maxContracts 20 micros**: instrument band is [5, 20]; the $150 cap dominates real sizing (e.g. 48-tick MNQ stop → $24/contract → 6 micros).
- **maxTradesPerDay 6** and **maxConsecutiveLosses 3**: NEW `RiskLimits` fields (builder default **0 = disabled**, so legacy profiles enforce neither). Enforced as **blocking gates** in `PropFirmRiskEngine.evaluate` (before sizing), semantics ported from the Monte Carlo `RiskProfile` (same meaning: trade #7 of the day denied; the trade after 3 straight losses denied; a win/scratch resets the streak; trades/day resets on day rollover; loss streak survives the day boundary like `AccountLifecycle`). The counters live in `AccountState` (`recordTradeCompleted`), incremented at the two real position-close funnels: `ExecutionEngine.closePosition` (sim/backtest) and the live bracket SL/TP close handlers in `LiveEngineRunner`.
- DLL/MLL/flatten-time/weekend fields are identical to `topstep50k()` — no Topstep rail was touched.

## 6. Exits — ONE take-profit

- Scalp signals carry the **real RR** (11-arg `StrategySignalEvent` constructor — SA1 blocker #9) and a single partial row `[[rr, 1.0]]` (100% at the actual target price for any ladder consumer).
- `LiveEngineRunner.submitProtectiveOrders`: when scalp mode is on it **always** uses the legacy single-TP `BracketOrderManager.createBracket()` (never `createBracketWithPartials` — its 2R/3R/5R rungs would rest beyond a 1R-capped target; SA1 blocker #10). `PartialProfitManager` is not referenced by any runner (dead path) — nothing to disable there.
- Breakeven at +0.5R (`scalp.breakevenAtHalfR`, default true): new `BracketOrderManager.armPriceBreakevenTrigger(symbol, price)` reuses the **existing** single-contract price-trigger mechanism (`checkPriceBreakevenTrigger` → `moveStopToBreakeven`, already polled every candle by `LiveEngineRunner`); no new listener machinery. `TrailingStopManager` is not wired to any runner, so nothing to bypass.
- Sim path (`SimEngineRunner`) already submits single stop/target via `executionEngine.submitOrder` — inherently single-TP.

## 7. Golden-file regression (legacy untouched — the proof)

Captured at HEAD (36f07c2) **before** any SA3 edit, from the `StdvOteWiringIntegrationTest` fixture:

```
LONG_ENTRY MNQ  TIER_1  qty=6
entry  = 21023.0
stop   = 21011.0
target = 21058.0          (−2σ projection off leg [21012, 21035])
rr     = 2.9166666666666665 (= 35/12; ctx.rr and getActualRR())
signal.riskRewardRatio = 2.0 (tier default)   partials = [[1.0,0.5],[2.0,0.5]]
```

`StdvOteLegacyGoldenTest` re-runs the identical fixture with `scalpMode.enabled=false` and asserts every value **exactly** (prices with zero tolerance). It passes after all SA3 changes. The same fixture under scalp mode emits entry/stop unchanged and target 21035.0 = exactly 1R (RR 1.00, band ✓) — `StdvOteScalpModeIntegrationTest`.

## 8. New/changed code + tests

| Change | File(s) |
|---|---|
| NEW `ScalpConfig`, `ScalpTargetCalculator` | `strategy/stdvote/` |
| Scalp branch in `tryEmit` + narrow setters (core stays pure) | `StdvOteStrategy` |
| Candidate-A sourcing + calculator/validator injection | `StdvOteRunnerStrategy` |
| Signal band + frequency fields + `topstep50kScalp()` | `domain/RiskLimits` |
| M7 band from active RiskLimits | `validation/MandatoryConfluenceValidator` |
| Frequency gates | `risk/PropFirmRiskEngine`, `domain/AccountState`, `execution/ExecutionEngine`, `LiveEngineRunner` |
| Single-TP scalp bracket + +0.5R BE trigger | `LiveEngineRunner`, `execution/BracketOrderManager` |
| Profile selection call sites | `SimEngineRunner`, `LiveEngineRunner`, `BacktestExample`, `EngineFacade`, (log only) `StdvOteFactory` |

Tests (39 new, all green; all pre-existing tests pass unmodified): `ScalpTargetCalculatorTest` (22 — includes a 5,000-case seeded randomized ≤1R sweep), `ScalpConfigTest` (6), `ScalpRiskProfileTest` (8 — trade #7 rejection, 3-loss rejection, win reset, day rollover, legacy-enforces-neither, scalp RR band), `StdvOteValidatorRrBandTest` (3), `StdvOteLegacyGoldenTest` (2), `StdvOteScalpModeIntegrationTest` (2), `BracketScalpBreakevenTest` (4). Shared fixture helper: `StdvOteGoldenFixture` (copy of the SA2 fixture so that test stays untouched).

## 9. Out of scope / flags

- Time-window gating, raid-score go/no-go, re-arm/one-move discipline: **SA4** (M-gates run exactly as today in scalp mode; one emission per JVM run still applies until SA4's re-arm).
- `StdvOteSizer` remains unwired (SA2 note stands); scalp sizing is bounded by riskPerTrade $150 + maxContracts 20 in the risk engine.
- Candidate-A quality depends on the LevelEngine having levels; when starved, the model degrades gracefully to the exactly-1R fallback (shippable minimum per the task's failure-handling clause) — observed on the synthetic fixture; real sessions with PDH/PDL/session extremes will produce liquidity-anchored targets.
- Live double-count guard: a completed trade is counted once per close path (`ExecutionEngine.closePosition` fires only when simulation is enabled; live counts in the bracket close handlers). If both ever fired, the error direction is over-counting → earlier blocking → risk-safe.
