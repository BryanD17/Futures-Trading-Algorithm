# BACKTEST_COMPARISON.md — STDV_OTE vs LEGACY

This document is the SA9 honesty ledger: what was tested, what was NOT
tested, and exactly what has to land before the comparison numbers can
be trusted to make a go/no-go decision on the new strategy.

The short version: the **unit and integration coverage of the new model
is solid (303 green tests, 6 pre-existing news-subsystem failures
unchanged from the baseline).** The **backtest comparison and SIM smoke
test are deferred** because the production wiring that lets the engine
loop actually drive `StdvOteStrategy.onCandle` to advance through the
state machine is left as a follow-up — see the SA4 + SA5 commit notes.

---

## 1. What is green right now

### 1.1 Unit + integration tests

| Module | Test count | New failures | Pre-existing | Notes |
|--------|------------|--------------|--------------|-------|
| trading-engine | 297 | 0 | 6 (news subsystem) | All new STDV+OTE tests green |
| api-backend | 6 | 0 | 0 | SA6 added these; previously NO-SOURCE |
| dashboard-frontend | n/a | n/a | n/a | `tsc && vite build` clean, 904 modules |

### 1.2 Test inventory by sub-agent

| Sub-agent | Test class | Cases | Covers |
|-----------|------------|-------|--------|
| SA2 | `StdvProjectionEngineTest` | 19 | sigma math (MNQ/MES/MGC), monotonicity, snap-to-liquidity within tolerance + refused on reorder, realism tag, degenerate/inverted/neutral/short-leg empty returns |
| SA3 | `OteEntryCalculatorTest` | 24 | canonical 62/705/79 for bullish + bearish, level ordering, contains() inside/outside, default 0.705 entry, PD-array snap, off-grid round-to-tick, bullish/bearish stop math, RR helper, FVG-in-zone preference rules |
| SA4 | `StdvOteValidatorTest` | 19 | happy path; one test per M1..M9 gate failure; short-circuit ordering (an M2 failure does not surface as M3) |
| SA4 | `StdvOteStrategyTest` | 17 | full happy MNQ-bullish sequence IDLE→IN_TRADE emits exactly one LONG_ENTRY with the correct entry / stop / target / RR / tier / size; sweep-before-bias ignored; counter-bias sweep ignored; displacement-before-sweep ignored; missing reaction stays at MSS_CONFIRMED; M8 size floor blocks emit; bias-flip invalidates; session-end invalidates; reset returns to IDLE; constructor rejects nulls; onCandle safe when IDLE |
| SA5 | `StdvOteSizerTest` | 21 | Appendix W.2 worked examples (Cases A..E) for MNQ/MES/MGC; tier caps 8/12/16/20; Topstep cap takes precedence when lower; news multiplier 0.5x halves / 0.3x sub-floor SKIP; null tier, no-room, degenerate geometry, null-request; property test: across 240 input grid points the output is always 0 or in [5, 20] |
| SA5 | `TradeableInstrumentTest` | 10 | only MNQ/MES/MGC tradeable; full-size NQ / ES / GC and null/empty rejected; case-insensitive resolution; per-instrument specs; MGC stricter raid floor; pointValue == tickValue/tickSize identity asserted; all specs within [5, 20]; roundToTick lands on grid |
| SA6 | `SetupControllerTest` | 6 | `/api/setup` lists strategy + active symbols; `/api/setup/instruments` returns MNQ/MES/MGC specs; IDLE snapshot for an unregistered symbol; 404 for full-size NQ; projections array populates after MANIP_DONE; rich snapshot fields serialise |

**Total new tests added by the refactor: 122 across two modules.**

### 1.3 Pre-existing failures (NOT introduced by the refactor)

All in the news subsystem, documented in `REFRACTOR_BASELINE.md`:

- `EventProximityCheckerTest.getUpcomingEventsForInstrument`
- `EventProximityCheckerTest.shouldReduceSizeBeforeHighImpact`
- `NewsBiasModifierTest.returnsAlignedWhenNewsSupportsBullish`
- `SurpriseCalculatorTest.lowerUnemploymentIsBetter`
- `SurpriseCalculatorTest.lowerJoblessClaimsIsBetter`
- `SurpriseCalculatorTest.negativeSurpriseCorrect`

The count was 6 at the baseline and remains 6 after every commit. The
refactor never made the count grow — that was an explicit invariant.

---

## 2. What is NOT tested yet (deferred)

### 2.1 BACKTEST_COMPARISON RUNS (R1..R6 from Appendix K of the master spec)

| Run | Description | Status |
|-----|-------------|--------|
| R1 | LEGACY (`IctHighConfluenceStrategy`) in-sample with costs | NOT RUN |
| R2 | STDV_OTE in-sample with costs | NOT RUN |
| R3 | STDV_OTE out-of-sample with costs | NOT RUN |
| R4 | STDV_OTE frictionless reference | NOT RUN |
| R5 | STDV_OTE per-instrument (MNQ / MES / MGC) | NOT RUN |
| R6 | Monte Carlo on STDV_OTE trade sequence | NOT RUN |

### 2.2 SIM smoke test

The acceptance criterion in Appendix K.6 — boot `bootRun` + `npm run dev`,
watch the Setup panel render a full lifecycle from IDLE to DONE under the
MockConnector — was NOT performed.

### 2.3 Why deferred

The blockers are explicit in the SA4 and SA5 commit messages, repeated
here for the record:

1. **`StdvOteStrategy.onCandle` is a stub.** It implements the time-based
   housekeeping (bar counting + expiry + session-end invalidation) but
   the actual poll of `RaidDetector` / `DisplacementDetector` /
   `FvgDetector` / `MarketStructureShiftDetector` / `Power3Detector` /
   `DailyAmdCycleTracker` / `HtfTrendAnalyzer` / `CorrelationTracker` /
   `KillzoneClock` is not wired. The state machine is driven by
   package-private `record*` hooks that production code never calls.
2. **`MultiInstrumentEngine` still uses the legacy
   `IctHighConfluenceStrategy` and the `InstrumentCharacteristics` NQ/ES/GC
   profile map.** Switching defaults without first wiring the detector
   poll in (1) would just produce silent no-trade behaviour — worse than
   the current state, because the legacy strategy at least fires.
3. **The Topstep MLL trail mode (INTRADAY vs EOD) has not been confirmed
   by the user for their actual account.** Sizing math depends on this;
   the sizer is correct under either model but the runner has to pick.

A backtest run today would either run the LEGACY strategy and tell us
nothing new, or run STDV_OTE in its stub form and produce zero trades.

### 2.4 What has to land before R1..R6 are meaningful

1. Implement the detector poll inside `StdvOteStrategy.onCandle` —
   call the existing detector instances on each LTF candle and translate
   their outputs into the `record*` hook calls. (Approx. 200–400 LOC,
   needs careful reading of each detector's API.)
2. Wire the sizer (`StdvOteSizer`) into a runner-side risk pre-check
   that consults `PropFirmRiskEngine` for MLL cushion + DLL + consistency
   throttle, then sets `ctx.sizeRequest` before `tryEmit`. (Approx.
   100–150 LOC.)
3. Make `MultiInstrumentEngine` (or a new dedicated `StdvOteEngine`)
   instantiate one `StdvOteStrategy` per `TradeableInstrument` and route
   `CandleEvent`s to it.
4. Build a 90-day MNQ / MES / MGC historical dataset under
   `historical-data/` (already in `.gitignore`).
5. Run `BacktestExample` with the LEGACY strategy and the new
   STDV_OTE strategy across the same dataset, capture
   `EnhancedBacktestReport` metrics.
6. Run `MonteCarloSimulator` over the STDV_OTE trade list.
7. Fill the placeholder tables in §3 below with the real numbers.

---

## 3. Results tables (PLACEHOLDERS — fill after runs)

The structure below mirrors Appendix K.4 of the master spec. Numbers are
left blank intentionally: this file should never carry imaginary
backtest results.

### 3.1 In-sample with costs (R1 vs R2)

| Metric | LEGACY | STDV_OTE |
|--------|--------|----------|
| Trades | _tbd_ | _tbd_ |
| Win % | _tbd_ | _tbd_ |
| Avg R | _tbd_ | _tbd_ |
| Expectancy (R/trade) | _tbd_ | _tbd_ |
| Profit factor | _tbd_ | _tbd_ |
| Max drawdown ($ / %) | _tbd_ | _tbd_ |
| Recovery factor | _tbd_ | _tbd_ |
| Longest losing streak | _tbd_ | _tbd_ |
| MLL breaches (must be 0) | _tbd_ | _tbd_ |
| Worst-day vs total (consistency) | _tbd_ | _tbd_ |
| Avg trade duration | _tbd_ | _tbd_ |
| % trades by tier (1/2/3/4) | _tbd_ | _tbd_ |

### 3.2 Out-of-sample STDV_OTE (R3)

Same table, run on the reserved 30% OOS slice. Expectancy MUST be in
the same ballpark as R2 — significant degradation means over-fit.

### 3.3 Per-instrument (R5)

| Instrument | Trades | Win % | Avg R | Max DD | MLL breaches |
|------------|--------|-------|-------|--------|--------------|
| MNQ | _tbd_ | _tbd_ | _tbd_ | _tbd_ | _tbd_ |
| MES | _tbd_ | _tbd_ | _tbd_ | _tbd_ | _tbd_ |
| MGC | _tbd_ | _tbd_ | _tbd_ | _tbd_ | _tbd_ |

### 3.4 Monte Carlo on STDV_OTE (R6)

- 5th / 50th / 95th percentile drawdown: _tbd_
- Pass probability under the user's MLL configuration: _tbd_
- Risk of ruin: _tbd_

---

## 4. Acceptance gates (from Appendix H)

The refactor should be declared "better" only if **all** of these hold,
once the runs above are filled in:

- [ ] STDV_OTE produces **0 MLL breaches** in R2 + R5 under default
      sizing — this is the hard gate. Any breach in backtest is a stop.
- [ ] STDV_OTE OOS (R3) expectancy is non-negative and within roughly
      one standard deviation of R2.
- [ ] STDV_OTE max drawdown (R2, with costs) is ≤ LEGACY max drawdown.
- [ ] Trade count drops materially relative to LEGACY (fewer trades is
      EXPECTED — the refactor's whole point is a stricter gate).
- [ ] Per-instrument metrics in R5 are roughly consistent across MNQ /
      MES / MGC; if MGC underperforms badly the raid floor of 6 might
      need tuning.

If any of these fail, **do not** loosen mandatory gates to manufacture
trades. Report the gap and propose tuning (raid threshold, RR floor, OTE
entry at 0.705 vs 0.79, tier bands, killzone window). The user decides.

---

## 5. Disclaimers

- Backtest results, when they exist, are NOT a promise of live
  performance. ICT marketing win-rates (60–75%) rarely survive realistic
  fills, commissions, slippage, and the Topstep trailing MLL.
- LIVE mode is NOT enabled by this refactor and MUST stay manual. The
  user is the only person who can flip it.
- The Topstep MLL trail model (INTRADAY vs EOD) has been left
  configurable but must be confirmed by the user for their actual
  Combine / Express / Funded account before any of the size numbers in
  this document can be trusted in live trading.
- The frontend Setup panel reads from `/api/setup`; it does not (and
  should not) be able to trigger a trade.
