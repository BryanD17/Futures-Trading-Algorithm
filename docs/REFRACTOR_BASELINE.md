# Refactor Baseline (SA0)

This document captures the **pre-refactor** state of the Topstep Futures
Trading Algorithm repository as the starting point for the STDV+OTE refactor
(branch `refactor/stdv-ote-core`, target merge into `Main`).

Date captured: 2026-05-28
Captured on: Windows 11, PowerShell, OpenJDK Temurin 21.0.10, Node v24.14.0,
Git 2.54.0.

The baseline exists so we can measure the refactor's impact against a known,
recorded "before" state and so that any pre-existing breakage is documented
rather than silently inherited.

---

## 1. Repository structure

Root layout (Gradle multi-module + standalone Node frontend):

- `trading-engine/` — Core trading logic (Gradle subproject).
- `api-backend/`    — Spring Boot REST/WebSocket API (Gradle subproject).
- `dashboard-frontend/` — React + TypeScript + Vite UI (standalone npm).
- `docs/`, `gradle/`, plus root `build.gradle`, `settings.gradle`, etc.

Gradle settings declares two subprojects: `trading-engine`, `api-backend`.
The dashboard is built/tested separately via `npm`.

Top-level packages inside `trading-engine` (`com.topstep.trading.*`):
`backtest, chartstate, connector, domain, engine, event, execution, journal,
lifecycle, montecarlo, news, optimization, risk, strategy, validation`.

---

## 2. Toolchain check

| Tool | Required | Found | Status |
|------|----------|-------|--------|
| Java | 21+      | OpenJDK Temurin 21.0.10 | OK |
| Node | 18+      | v24.14.0 | OK |
| Git  | any      | 2.54.0.windows.1 | OK |

---

## 3. Build status

All commands were run from the repo root unless otherwise noted.

### 3.1 `./gradlew clean build --no-daemon`

- **Compilation:** both `trading-engine` and `api-backend` compiled.
  - One deprecation note: `TopstepConnector.java` uses or overrides a
    deprecated API (informational, not an error).
- **Packaging:** `api-backend:bootJar`, `trading-engine:distTar/distZip` all
  produced.
- **Tests (engine):** `:trading-engine:test` ran 189 tests, **6 failed**
  (see Section 4 for the failure list).
- **Tests (api-backend):** NO-SOURCE — no test sources currently exist in
  `api-backend`.
- **Overall:** `BUILD FAILED` only because `:trading-engine:test` had the
  6 pre-existing test failures. Compilation, packaging, and the api-backend
  module are all green.

### 3.2 Frontend: `npm install` + `npm run build`

- `npm install` succeeded (277 packages audited, 16 vulnerabilities reported
  by `npm audit` — surfaced but not addressed at the baseline; out of scope
  for SA0).
- `npm run build` (script: `tsc && vite build`) succeeded — exit code 0.
  - 900 modules transformed, `dist/index.html` + assets produced.
  - Bundle warning: main JS chunk is 613.68 kB (> Vite's 500 kB warning
    threshold). Informational, not an error.
- TypeScript pass is included in `npm run build`; no `tsc` errors reported.

---

## 4. Test results — engine

Total run: **189 tests, 6 failed.**

All 6 failures are in the **macro-news / event subsystem** (under
`com.topstep.trading.news.*`); none touch the strategy, risk, execution,
chart-state, instrument, or connector paths that this refactor will modify:

| # | Test class | Test name |
|---|------------|-----------|
| 1 | `EventProximityCheckerTest` | Utility Method Tests > getUpcomingEventsForInstrument returns relevant events |
| 2 | `EventProximityCheckerTest` | HIGH Impact Event Gating > Should REDUCE_SIZE 15 minutes before HIGH impact event |
| 3 | `NewsBiasModifierTest` | Alignment Tests > Returns ALIGNED when news supports bullish technical bias |
| 4 | `SurpriseCalculatorTest` | Direction Determination > Lower unemployment is BETTER (inverse indicator) |
| 5 | `SurpriseCalculatorTest` | Direction Determination > Lower jobless claims is BETTER (inverse indicator) |
| 6 | `SurpriseCalculatorTest` | Basic Surprise Calculations > Calculates negative surprise correctly |

### 4.1 Disposition

These are **pre-existing failures on `Main`**. They are documented here rather
than fixed in SA0 because:

- They are not on any code path the refactor changes.
- Fixing them would expand SA0 scope beyond "establish baseline."
- The macro-news subsystem is explicitly listed in Section 0.1 of the
  refactor spec as something to **keep** (it remains a useful gating filter
  for the new `StdvOteStrategy`).

The refactor must not make these tests worse. After SA9, the count of
news-subsystem failures should be equal to or less than 6. If a sub-agent
needs to touch any of the news classes referenced above, the corresponding
test must be re-verified before that sub-agent's commit.

---

## 5. Current default strategy

The current default `TradingStrategy` selected by each runner:

| Runner / entry point | Default strategy | Source |
|----------------------|------------------|--------|
| `BacktestExample.main` | `new IctHighConfluenceStrategy("NQ", "ES", eventBus)` | `backtest/BacktestExample.java:121` |
| `SimEngineRunner.run` | `new IctHighConfluenceStrategy(DEFAULT_SYMBOL, "NQ", eventBus)` | `SimEngineRunner.java:77` |
| `LiveEngineRunner.run` | `new IctHighConfluenceStrategy(DEFAULT_SYMBOL, SMT_SYMBOL, eventBus)` | `LiveEngineRunner.java:277` |
| `MultiInstrumentEngine` (per-instrument) | `InstrumentSpecificStrategy` (one per profile) | `engine/MultiInstrumentEngine.java:521` |

`IctHighConfluenceStrategy.java` is currently **1,890 lines** (the spec
estimated ~2,190; the actual count is within that ballpark). It is the
"additive-scoring" monolith the refactor demotes to LEGACY behind a flag.

Symbols passed into the default strategy today are the **full-size minis**
(`NQ`, `ES`), not the `MNQ`/`MES`/`MGC` micros that the refactor targets.

---

## 6. Current instrument set

The engine's instrument registry is `InstrumentCharacteristics.getAllProfiles()`,
which today returns: **NQ, ES, GC** (full-size E-mini Nasdaq, E-mini S&P,
and Gold). Each profile already carries optional "micro" fields:

| Symbol | Micro symbol | Micro tick value | "Always use micro" |
|--------|--------------|------------------|--------------------|
| NQ     | MNQ          | $0.50            | (not set)          |
| ES     | MES          | $1.25            | (not set)          |
| GC     | MGC          | $1.00            | **true** (Topstep Feb 2026 restriction noted in source) |

`InstrumentCharacteristics.getProfile(symbol)` already aliases `MNQ → NQ`,
`MES → ES`, `MGC → GC`. The `GC` profile has `microMaxContracts(5)` for a
$50K account.

This baseline is the input to **SA5**, which replaces this informal
"profile-with-micro-fields" model with a strict
`TradeableInstrument { MNQ, MES, MGC }` registry, locks all instrument
enumeration through it, and rejects `NQ`/`ES`/full-size `GC` at startup.

---

## 7. Strategy package inventory (51 files)

For reference — the strategy package already contains the detectors the
refactor will **reuse** rather than reimplement (Appendix V wiring map):

`AdaptiveStopCalculator, ATRCalculator, BarAggregationManager,
BidirectionalLiquidityModel, BreakerBlock, BreakerBlockDetector,
ConsolidationDetector, ContinuationPatternDetector, CorrelationTracker,
DailyAmdCycleTracker, DefaultStrategyContext, DisplacementDetector,
FairValueGap, FvgDetector, HtfConfirmationResult, HtfTrendAnalyzer,
IctHighConfluenceStrategy, IctStructureDetector, ImpulseExtensionAnalyzer,
InstrumentCharacteristics, InstrumentConfig, InstrumentProfile,
InstrumentSpecificStrategy, KillzoneClock, KillzonePhase,
LiquidityDetector, LiquiditySweep, LiquidityTargetIdentifier, MarketBias,
MarketStructureShiftDetector, MitigationBlock, MitigationBlockDetector,
MultiTimeframeAnalyzer, OrderBlock, OrderBlockDetector,
PartialProfitManager, Power3Detector, SessionManager, SilverBulletClock,
SilverBulletStrategy, SmtDivergenceResult, StatisticalRetracementEngine,
StrategyContext, SwingPointListener, TargetPlacement, TradeTier,
TradeTierVariant, TradingStrategy, TrendlineDetector, VariantSelector,
VolatilityRegimeDetector, VolumeProfileAnalyzer`.

Per the refactor spec:

- `StatisticalRetracementEngine` — demote to bonus-only (Section 1, Issue 2).
- `ImpulseExtensionAnalyzer` — repurpose as STDV realism tag (Section 1,
  Issue 3).
- `SilverBulletStrategy`, `InstrumentSpecificStrategy` — candidates for
  cleanup in SA8 only if proven unreferenced; not touched in SA0.
- All ICT detectors (FVG, OB, breaker, MSS, liquidity, sweep, displacement,
  killzone, AMD, Power-of-3, SMT, multi-TF) — keep and orchestrate from the
  new `StdvOteStrategy` state machine.

---

## 8. Git baseline

| Field | Value |
|-------|-------|
| Default branch | `Main` (capital M) |
| Working branch | `refactor/stdv-ote-core` (created off `Main`) |
| Remote `origin` | `https://github.com/BryanD17/Futures-Trading-Algorithm.git` |
| Base commit on `Main` | `dde9a39` "Merge branch 'fix/dashboard-blank-screen' into Main" |
| Status at baseline | clean working tree, up to date with `origin/Main` |

The refactor branch will accumulate the SA0–SA10 commits. The final merge
back into `Main` will use `--no-ff` per the spec's Git protocol (Section 6 /
Appendix S) and **only** after SA9's acceptance gate, with an explicit
human go/no-go before any `git push origin Main` or tag push.

---

## 9. Items the refactor must verify with the user before they bind

These are flagged here (and surfaced again in SA5/SA10) so they are not
silently guessed. The refactor spec is explicit that these need
confirmation from the user against the actual Topstep account/platform
state, not assumed from documentation:

1. **MLL trail model** — INTRADAY vs EOD. Default chosen by the refactor is
   `INTRADAY` (more conservative for sizing); must be set to match the
   user's actual Combine/Express/Funded account/platform.
2. **Session flatten cutoff** — currently codified as ~15:10 CT in the spec;
   verify against current Topstep rules at SA5.
3. **Contract specs** (tick size, tick value, point value) for MNQ/MES/MGC —
   the spec restates them, but the refactor will assert
   `pointValue == tickValue / tickSize` at construction; any broker-side
   change to these specs invalidates the assumption.
4. **Per-account contract caps** and current DLL status on the user's
   platform (TopstepX vs NinjaTrader/Tradovate/Quantower/TradingView).

---

## 10. SA0 exit status

| Item | Status |
|------|--------|
| Java 21+ available | OK |
| Node 18+ available | OK |
| Git available | OK |
| Repo cloned and on refactor branch | OK |
| Engine + api-backend compile | OK |
| Frontend `tsc && vite build` | OK |
| Engine tests run (189 total, 6 pre-existing news failures) | OK (documented) |
| Default strategy identified | `IctHighConfluenceStrategy` |
| Instrument set identified | `NQ, ES, GC` (with micro fields) |
| Baseline doc written | this file |

**SA0 is complete and ready to hand off to SA1 (design + interfaces).** No
sub-agent past SA0 has begun. No production behavior has been changed.
