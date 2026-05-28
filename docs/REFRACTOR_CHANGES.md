# STDV+OTE Refactor — Change Ledger (v2.0)

This is the cumulative change list for the
`refactor/stdv-ote-core` → `Main` merge. It is meant to be read end-to-end
before the merge; every claim here has a commit it can be traced to.

## Summary

The refactor introduces a strict, sequential **STDV (Standard Deviation
Projection) + canonical OTE (Optimal Trade Entry)** strategy as a parallel
trading path alongside the legacy `IctHighConfluenceStrategy`. STDV defines
exits (targets), OTE defines entries, and a mandatory M1..M9 gate sequence
decides whether a trade is allowed at all — the previous additive-scoring
("advisory, not a gate") behaviour is replaced by hard gates.

The new code is **fully tested in isolation** (122 new green unit /
integration tests across the trading-engine and api-backend modules) but is
**not yet wired into the production runners**. The legacy strategy remains
the default for `BacktestExample`, `SimEngineRunner`, and
`LiveEngineRunner`. Switching defaults is an explicit follow-up gated on
the SA9 SIM smoke test that the spec calls for — see
`BACKTEST_COMPARISON.md` for the exact list of work that has to land first.

The merge is therefore **additive and reversible**: any path that was
producing trades before still produces them after the merge, and the new
code can be deleted in one commit if the user wants to walk it back.

## New classes (com.topstep.trading.strategy.stdvote)

| Class | Role | Sub-agent |
|-------|------|-----------|
| `TradeableInstrument` | Strict registry of the only routable symbols (MNQ / MES / MGC). Asserts `pointValue == tickValue / tickSize` and the hard `[5, 20]` micro band at construction. | SA1 / SA5 |
| `StdvProjection` (record) | One STDV ladder level: sigma, rawPrice, snappedPrice, snappedLevelType, isLiquidityBacked, realismTag. | SA1 |
| `StdvProjectionEngine` | Canonical { -0.27, -1, -2, -2.5, -4 } projection from a manipulation leg, with optional snap-to-liquidity (monotonicity-preserving) and `ImpulseExtensionAnalyzer` realism tag on the -2.0 level. | SA2 |
| `OteZone` (record) | The canonical 0.50 / 0.62 / 0.705 / 0.79 / 1.0 levels of an LTF impulse leg. Implements `contains(price)` for the band. | SA1 |
| `OteEntryCalculator` | Builds the zone, chooses the entry (0.705 default; PD-array edge inside the band overrides), computes the stop (just beyond 1.0 + buffer), reward-to-risk helper, and `bestFvgEdgeInZone` for PD-array-in-OTE detection. | SA3 |
| `SetupState` (enum) | IDLE → BIAS_SET → MANIP_DONE → SWEEP_DONE → DISPLACED → MSS_CONFIRMED → OTE_ARMED → IN_TRADE → MANAGING → DONE; INVALIDATED is reachable from any non-terminal state. | SA1 |
| `SetupContext` | Mutable per-instrument state carried through the machine. All the fields the validator reads and the API serialises. | SA1 |
| `StdvOteStrategy` | The strategy. Implements `TradingStrategy`. Registers itself in the process-wide `StdvOteRegistry`. The state machine is fully implemented and exercised by the unit test; the detector orchestration inside `onCandle` is left as a TODO for the follow-up. | SA4 |
| `StdvOteSizer` | Buffer-based position sizer with the canonical formula from Appendix W.1. NEVER returns 1..4 — either 0 (with a `SkipReason`) or `[5, 20]`. | SA5 |
| `StdvOteRegistry` | Process-wide static registry of active `StdvOteStrategy` instances by symbol. Used by the API layer to read the live `SetupContext` snapshot without bean wiring. | SA6 |

## Modified classes

| Class | Change | Reason |
|-------|--------|--------|
| `validation/MandatoryConfluenceValidator` | New `validateStdvOte(SetupContext)` method implementing M1..M9 sequentially with first-failure short-circuit. | SA4 — the strict gate evaluator. |
| `strategy/SilverBulletStrategy` | `@Deprecated(forRemoval = true, since = "v2.0-stdv-ote")`. | SA8 — confirmed zero production references; queued for deletion after SIM verification. |
| `strategy/StatisticalRetracementEngine` | `@Deprecated(since = "v2.0-stdv-ote")`. | SA8 — entry-source role superseded by canonical OTE; kept as an optional O-tier confluence signal. |
| `api-backend SetupController` | NEW Spring `@RestController` under `/api/setup`. | SA6 — exposes the live setup state. |

## New documentation

| File | Purpose |
|------|---------|
| `docs/REFRACTOR_BASELINE.md` | Pre-refactor build status, test counts, default strategy, instrument set, pre-existing breakage. The "before" snapshot. |
| `docs/architecture/STDV_OTE_MODEL.md` | One-source-of-truth design doc: instruments, HTF/LTF phases, mandatory M1..M9 gates, optional confluences, tiers + sizing, risk guardrails, state machine, data-flow diagram, and a step-by-class map naming the owning sub-agent. |
| `docs/REMOVED.md` | SA8 ledger of what was kept and why, plus the items queued for post-SIM cleanup. |
| `docs/BACKTEST_COMPARISON.md` | SA9 honesty ledger. Test inventory, what was tested, what was NOT tested (R1..R6 + SIM smoke), why each was deferred, acceptance gates as unchecked checkboxes, and disclaimers. |
| `docs/REFRACTOR_CHANGES.md` | This file. |

## New REST surface (api-backend)

| Method | Path | Returns |
|--------|------|---------|
| GET | `/api/setup` | `{ strategy: "STDV_OTE", activeSymbols: [...] }` |
| GET | `/api/setup/instruments` | array of MNQ/MES/MGC specs (tick, point, micros, raid floor, SMT pair) |
| GET | `/api/setup/{symbol}` | full `SetupSnapshotDto` for the instrument; 404 only on non-tradeable symbols; tradeable-but-unregistered returns an IDLE snapshot so the panel can render |
| GET | `/api/setup/{symbol}/projections` | just the STDV ladder |

WebSocket push is a follow-up; the frontend polls at 1s.

## New frontend surface

A new **Setup** tab on the dashboard renders:

- Instrument tabs (MNQ / MES / MGC only) and the active strategy tag.
- State-machine stepper with the current step lit; INVALIDATED renders
  inline with the last failed gate.
- Bias / killzone / SMT / tier pills.
- M1..M9 confluence checklist with the failing gate highlighted and the
  last-failed-gate id surfaced.
- STDV ladder with sigma / price / liquidity-backed badge / realism tag.
- OTE block with all five levels and the PD-array-in-zone edge.
- Plan block (entry / stop / RR / size / tier) when the state machine
  reaches IN_TRADE.

The existing dashboard components (Overview, Positions, Risk, Journal,
Trades, ChallengeEconomicsPanel, EquityFanChart, PassProbabilityGauge,
PhaseTracker, SimulatorView, Controls, ErrorBoundary) were unchanged.
The frontend builds with zero TS errors.

## Configuration

All STDV+OTE-specific tunables live under a single `stdvOte.*` root in
`application.yml` (per `STDV_OTE_MODEL.md` §11). The defaults match the
master spec's Appendix O. Wiring of these keys into the runtime is part
of the follow-up that switches default strategies; for this branch the
defaults are baked into the new classes' constants.

## What is still open after this merge

See `BACKTEST_COMPARISON.md` §2.4 for the canonical list and
`REMOVED.md` "What still needs doing after SIM verification" for the
cleanup queue. In brief:

1. Implement the detector poll inside `StdvOteStrategy.onCandle` so the
   state machine actually advances under the real engine loop.
2. Wire `StdvOteSizer` into a runner-side risk pre-check that consults
   `PropFirmRiskEngine`.
3. Switch `BacktestExample` / `SimEngineRunner` / `LiveEngineRunner`
   defaults from `IctHighConfluenceStrategy` to `StdvOteStrategy`,
   gated on a `stdvOte.enabled` flag.
4. Route `MultiInstrumentEngine` through the `TradeableInstrument`
   registry (currently still on `InstrumentCharacteristics.getAllProfiles()`).
5. Run R1..R6 backtests and fill in `BACKTEST_COMPARISON.md`.
6. Run a SIM smoke test (bootRun + `npm run dev`) and confirm the Setup
   panel renders a live lifecycle.
7. Confirm the Topstep MLL trail mode (INTRADAY vs EOD) for the user's
   actual account and bake it into the configuration default.

None of these are required for the merge — they are required before
LIVE.

## Risks and reversibility

- **No live runner uses the new code.** Without (1)–(3) above, this
  branch is purely additive: legacy trades remain legacy, the new path
  produces no orders.
- **The merge is one `git revert -m 1 <merge-sha>` away from gone.**
  The branch uses `--no-ff` so the merge commit is a single revert point.
- **Risk guarantees are not weakened.** `PropFirmRiskEngine`,
  `TradingRiskManager`, `RiskProfile`, `RiskLimits`, the flatten-by-time
  path, the kill switch — all unchanged. The new sizer is a layer
  above them that produces a number; it cannot bypass them.
- **No secrets committed.** `.gitignore` covers `*.log`, `.env`, all
  credential files. The two Topstep-credential paths
  (`topstep-config.json`, `api-keys.json`) were already in
  `.gitignore` and remain so.
- **LIVE mode is not enabled.** Confirmed by inspection of every commit:
  no runner default changed, no environment-variable-driven autostart
  added, no path exists by which the API or the dashboard could trigger
  a live trade.

## Tag

After merge, the branch will be tagged `v2.0-stdv-ote` for easy
identification and rollback.

## Commit log on the refactor branch

```
59b93ae test(docs): SA9 test inventory + backtest comparison scaffold
49d17c6 refactor(cleanup): demote SilverBulletStrategy + StatisticalRetracementEngine
de70fe1 feat(ui): add live STDV+OTE Setup panel
ff00e92 feat(api): /api/setup endpoints + StdvOteRegistry process registry
4ae9c90 feat(risk): buffer-based sizer + instrument registry tests
0f0fc94 feat(strategy): StdvOteStrategy state machine + M1..M9 validator
1908035 feat(strategy): canonical OTE entry calculator (entries) + tests
ff4659c feat(strategy): STDV projection engine (exits) + tests
1539ed2 feat(model): add STDV/OTE/setup interfaces and instrument registry types
e1e0919 chore(baseline): record clean build + test baseline
```
