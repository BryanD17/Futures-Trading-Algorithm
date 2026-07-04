# SCALP MODE — STDV+OTE 1R Scalp System

Branch: `feature/sa5-wiring-scalp-mode` · Instruments: **MNQ / MES / MGC only** · Default: **OFF** (`scalpMode.enabled=false`)

## What changed at a glance

| Area | Legacy (default) | Scalp mode (`-DscalpMode.enabled=true`) |
|---|---|---|
| Target | −2σ STDV extension | min(nearest opposing liquidity, FVG origin), **hard-capped at 1R**; exactly 1R when no valid candidate |
| RR band (validator M7 + risk engine) | [2.0, ∞) validator / [3.0, 6.0] engine | **[0.8, 1.5]** both |
| Risk profile | `topstep50k()` — $250/trade, 5 contracts | `topstep50kScalp()` — **$150/trade**, 20 micros, **6 trades/day max**, **stop after 3 consecutive losses** |
| Raid quality | M4 instrument floor (5/5/6), no binary gate | **STRICT binary gate: every sweep score must be ≥ `scalp.minRaidScore` (6)** — including the starved-pipeline fallback; sub-floor sweeps rejected at sweep time (window stays alive) |
| Time windows | NY killzones ∪ Silver Bullet windows; MGC full London 3:00–12:00 ET | **Full killzones only** (NY AM 9:45–12:30, NY PM 13:45–16:00 ET); MGC London narrowed to the prime window (03:00–05:00 ET); SB is a scoring input (+1), not a gate |
| Frequency | ONE emission per instrument per run (`IN_TRADE` terminal) | **re-arm after position close / invalidation** (cooldown, killzone-open, no-overlap, frequency-gate mirror); a second setup can arm in the same session |
| Exits | tiered partial ladder (1R/2R/3R…) | **single take-profit** bracket; breakeven trigger at **+0.5R** |
| Sizing | fixed tier table {6,10,14,18} clamped [5,20] | `StdvOteSizer.decide` (equity/MLL-buffer based, ≤ $150 budget, stand-down instead of 1–4 micros); tier informs caps only, never blocks emission |
| Topstep rails (DLL/MLL/flatten) | unchanged | **unchanged — never weakened** |

Legacy behavior is proven byte-for-byte by `StdvOteLegacyGoldenTest` (zero-tolerance price assertions on the SA2 fixture).

## Every configuration flag

All are JVM system properties (`-Dname=value`), read once at construction. Invalid values fall back to the default with a warning.

| Property | Default | Meaning |
|---|---|---|
| `stdvOte.enabled` | `true` | Strategy selector (`StdvOteFactory`). `true` → STDV+OTE runner for MNQ/MES/MGC (anything else falls back to `IctHighConfluenceStrategy` with a warning). `false` → legacy `IctHighConfluenceStrategy` for every symbol. |
| `stdvOte.stopBufferTicks` | `4` | Stop buffer beyond the OTE 1.0 level, in ticks (both modes). |
| `stdvOte.reactionWickTicks` | `2` | Minimum OTE rejection-wick length, in ticks, for `reactionConfirmed`. |
| `scalpMode.enabled` | `false` | **Master scalp switch.** Selects the 1R target model, `topstep50kScalp()` risk limits, strict raid gate, scalp windows, re-arm engine, single-TP exits. |
| `scalp.minRaidScore` | `6` | STRICT binary raid-quality floor at sweep-record time. Applies to EVERY score (pipeline, base fallback, exact base). A sweep that cannot be shown ≥ this floor never arms a scalp setup. |
| `scalp.rearmCooldownBars` | `5` | Feed bars (1m) that must elapse after a position close / setup invalidation before a new setup may arm. |
| `scalp.breakevenAtHalfR` | `true` | Move the stop to entry (+2-tick buffer) once price reaches +0.5R (live bracket path). |
| `scalp.minTargetClearanceTicks` | `2` | A scalp target must clear entry by at least this many ticks. |
| `scalp.candidateWindowR` | `1.5` | Target candidates farther than this many R from entry are invalid → exactly-1R fallback. |
| `scalp.londonPrimeStartEt` | `03:00` | MGC-only London prime window start (ET, HH:mm). |
| `scalp.londonPrimeEndEt` | `05:00` | MGC-only London prime window end (ET, HH:mm). |
| `scalp.sizerSafetyCushion` | `200` | Dollars held back from available room in the `StdvOteSizer` wiring. |
| `backtest.commissionPerSide` | `1.55` | A/B backtest cost model: commission per side per contract (dollars). |
| `backtest.slippageTicks` | `1` | A/B backtest cost model: adverse slippage per side per contract, in ticks. |

Not configurable by design: the **1R target cap** (`ScalpTargetCalculator.TARGET_CAP_R = 1.0`) — it is the definition of the scalp model and the number the Monte Carlo validates.

## Toggle matrix — `stdvOte.enabled` × `scalpMode.enabled`

| `stdvOte.enabled` | `scalpMode.enabled` | Behavior |
|---|---|---|
| `true` (default) | `false` (default) | **STDV+OTE legacy extension mode** for MNQ/MES/MGC: −2σ targets, `topstep50k()` limits, tiered exits, one emission per run. Non-MNQ/MES/MGC symbols fall back to `IctHighConfluenceStrategy` (warn, never throws). |
| `true` | `true` | **STDV+OTE scalp mode**: everything in the "Scalp mode" column above. Same `IctHighConfluenceStrategy` fallback for non-MNQ/MES/MGC symbols. |
| `false` | `false` | Legacy `IctHighConfluenceStrategy` for all symbols — completely untouched code path, `topstep50k()` limits. |
| `false` | `true` | **Unsupported combination (fail-safe).** Strategy selection ignores the scalp flag → `IctHighConfluenceStrategy` runs, but `ScalpConfig.activeRiskLimits()` selects `topstep50kScalp()`, whose [0.8, 1.5] RR band rejects the legacy strategy's 2R+ signals — the account trades **nothing**. Risk-safe direction (no trades, rails intact), but do not run this combination deliberately. |

`IctHighConfluenceStrategy` itself was never modified on this branch.

## Run commands

```bash
# Build + full test suite (both modules; must be 0 failed / 0 skipped)
./gradlew clean build

# Trading-engine tests only
./gradlew :trading-engine:test

# A/B backtest: legacy vs scalp on the SAME candles, gross AND net PnL
# (uses data/MNQ_1min.csv if present; otherwise synthetic sessions + warning)
./gradlew :trading-engine:run --args="ABTEST"

# Monte Carlo: scalp ($150, 6/day, 3-loss stop) vs naive ($500, no stop)
./gradlew :trading-engine:run --args="MONTECARLO"

# Cost model overrides for the A/B harness
./gradlew :trading-engine:run --args="ABTEST" -Dbacktest.commissionPerSide=1.85 -Dbacktest.slippageTicks=2

# Enable scalp mode on any runner (SIM shown; never auto-LIVE)
./gradlew :trading-engine:run --args="SIM" -DscalpMode.enabled=true
```

On Windows use `.\gradlew.bat` in place of `./gradlew`.

## Where things live

- Core state machine: `trading-engine/src/main/java/com/topstep/trading/strategy/stdvote/StdvOteStrategy.java`
- Runner/wiring (detectors, gates, re-arm): `.../stdvote/StdvOteRunnerStrategy.java`
- Scalp config + target model: `.../stdvote/ScalpConfig.java`, `.../stdvote/ScalpTargetCalculator.java`
- Risk profiles: `.../domain/RiskLimits.java` (`topstep50k()` untouched; `topstep50kScalp()` new)
- A/B harness: `.../backtest/AbBacktestComparison.java` (+ `BacktestCosts`, `SyntheticScalpSessionGenerator`)
- Monte Carlo comparison: `.../montecarlo/MonteCarloScalpComparison.java`
- Audit trail: `docs/audit/SA1..SA5` documents.
