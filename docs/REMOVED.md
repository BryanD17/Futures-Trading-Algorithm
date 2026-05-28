# Refactor changes — kept / demoted / removed (SA8)

This is the SA8 ledger of what the STDV+OTE refactor touched in the legacy
strategy code. The conservative rule held: nothing on a live risk /
execution / lifecycle path is deleted in this branch, even when it appears
dead, until SA9's SIM smoke test proves the new strategy works end-to-end
on the actual engine wiring.

Every entry below either was annotated `@Deprecated` with `forRemoval=true`
(safe deletions queued for after SIM verification) or was kept with a
documented reason.

## Demoted via @Deprecated

| Class | Annotation | Reason |
|-------|------------|--------|
| `strategy/SilverBulletStrategy.java` | `@Deprecated(forRemoval = true, since = "v2.0-stdv-ote")` | Zero production references confirmed by repo-wide grep. SilverBullet mechanics (NY AM 10–11 ET killzone, sweep → MSS → FVG entry) are absorbed into `StdvOteStrategy` with the killzone gate as M3 and the canonical OTE math owning entries. Queue for deletion in the post-SIM cleanup. |
| `strategy/StatisticalRetracementEngine.java` | `@Deprecated(since = "v2.0-stdv-ote")` | The class was the previous OTE replacement (empirical pullback percentile band, falling back to Fib when undersampled). The refactor restores canonical OTE (0.62 / 0.705 / 0.79) as the only entry source. The engine is retained as an *optional* O-tier confluence signal: if its empirical band overlaps the canonical band it adds tier weight. It is no longer to be wired into entry selection. NOT marked `forRemoval` because the bonus-confluence role still has value. |

Grep evidence captured at SA8 commit time:

```
$ grep -rn "new SilverBulletStrategy" trading-engine api-backend dashboard-frontend
(no matches)

$ grep -rn "StatisticalRetracementEngine" trading-engine api-backend dashboard-frontend
trading-engine/src/main/java/com/topstep/trading/strategy/IctHighConfluenceStrategy.java:* uses StatisticalRetracementEngine for entry — to be demoted when the
trading-engine/src/main/java/com/topstep/trading/strategy/StatisticalRetracementEngine.java
trading-engine/src/test/java/com/topstep/trading/strategy/StatisticalRetracementEngineTest.java
trading-engine/src/main/java/com/topstep/trading/strategy/stdvote/OteEntryCalculator.java:* (see {@code StatisticalRetracementEngine}) is
```

The only remaining references are the class file itself, its (still-green)
unit test, the legacy `IctHighConfluenceStrategy` (which is queued for
default-off but not yet removed), and a doc reference in the new
`OteEntryCalculator` explaining the demotion.

## Kept (because they are still in the live code path)

| Class | Why kept |
|-------|----------|
| `strategy/IctHighConfluenceStrategy.java` | Still the production default in `BacktestExample`, `SimEngineRunner`, and `LiveEngineRunner`. The runners are NOT switched in this branch — SA5 explicitly deferred that until SIM verification with the new sizer + detector wiring. Removing the legacy strategy now would orphan the runners. |
| `strategy/InstrumentSpecificStrategy.java` | Used by `MultiInstrumentEngine` for per-instrument behaviour. The hybrid engine itself is 1700+ LOC and was deliberately not modified in this branch — see SA5 commit message. |
| `strategy/ImpulseExtensionAnalyzer.java` | Repurposed by the new `StdvProjectionEngine` as the realism tagger for the -2.0 target. Active use, not dead. |
| `strategy/InstrumentCharacteristics.java` | Still drives the existing `MultiInstrumentEngine` profile map (NQ/ES/GC with micro fields). The new `TradeableInstrument` registry is the planned successor, but until the engine is rewired to read from it the old characteristics class stays. |
| `strategy/InstrumentProfile.java`, `strategy/InstrumentConfig.java` | Same — referenced by the multi-instrument engine and the existing risk manager. Migration is gated on the SA5 follow-up. |
| News subsystem (`news.*`) | Six pre-existing test failures, but the gating logic is kept and explicitly listed as part of the M9 risk pre-flight chain. |

## Frontend

No frontend code was removed in SA7. Existing components (`Overview`,
`Positions`, `Trades`, `Risk`, `Journal`, `ChallengeEconomicsPanel`,
`EquityFanChart`, `PassProbabilityGauge`, `PhaseTracker`, `SimulatorView`,
`Controls`, `ErrorBoundary`) all build cleanly via `tsc && vite build` (baseline)
and were unchanged by the Setup panel addition.

## What still needs doing after SIM verification (deferred)

1. Switch `BacktestExample` / `SimEngineRunner` / `LiveEngineRunner` defaults
   from `IctHighConfluenceStrategy` to `StdvOteStrategy`.
2. Wire `MultiInstrumentEngine` to read instruments through
   `TradeableInstrument` (which rejects NQ/ES/full-size GC at startup).
3. Delete `SilverBulletStrategy` (already marked `forRemoval`).
4. Delete `StatisticalRetracementEngine` if the bonus-confluence hook turns
   out unused after the SA9 backtest comparison.
5. Remove `InstrumentCharacteristics.NQ` / `.ES` / `.GC` factory constants
   once the multi-instrument engine is on the new registry.

None of these are landed in this branch because each one needs the SA9 SIM
smoke test as proof the new strategy fires correctly under the real engine
loop. The conservative path is also the safer path: a refactor that leaves
the working runners untouched, then a follow-up that flips defaults after
the user has watched a SIM session and seen the new state machine advance
to IN_TRADE end-to-end.
