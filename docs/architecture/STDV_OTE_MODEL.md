# STDV + OTE Model — Architecture Spec

This document is the single design source of truth for the `StdvOteStrategy`
refactor. It supersedes the additive-scoring behavior of
`IctHighConfluenceStrategy` for the default trade path. The legacy strategy is
retained behind a flag for A/B backtest comparison only.

The model has a one-line summary:

> **OTE gets you IN. STDV gets you OUT. Bias + sweep + displacement + MSS +
> killzone decide IF. Risk decides HOW BIG. Everything is measured, nothing
> is promised.**

---

## 1. Allowed instruments

Only three symbols are ever routed:

| Symbol | Tick size | Tick value | Point value | Notes |
|--------|-----------|------------|-------------|-------|
| MNQ | 0.25 | $0.50 | $2.00 | Micro Nasdaq. SMT pair: MES. raidMinQuality 5. |
| MES | 0.25 | $1.25 | $5.00 | Micro S&P. SMT pair: MNQ. raidMinQuality 5. |
| MGC | 0.10 | $1.00 | $10.00 | Micro Gold. London-biased. raidMinQuality 6. |

Hard size cap: every emitted order is in `[5, 20]` micros. If buffer-based risk
cannot fund 5, the trade is skipped — never partial (no 1-4 contracts).

Routing of `NQ`, `ES`, or full-size `GC` at startup is rejected with a WARN.

`pointValue == tickValue / tickSize` is asserted at construction; the registry
is the only source of truth for instrument facts.

## 2. Higher-timeframe phase (15m primary, 1H/4H context)

Goal: establish directional bias and project STDV exit levels from a
manipulation leg.

### H1 — HTF bias (3-of-4 rule)

Combine, each casting one vote:

- `HtfTrendAnalyzer` trend state (HH/HL = bullish, LH/LL = bearish).
- `DailyAmdCycleTracker` distribution-leg direction.
- Price vs true day open (discount → bullish vote, premium → bearish).
- Daily draw on liquidity (toward PDH = bullish, toward PDL = bearish).

≥ 3 same-direction votes ⇒ `htfBias = BULLISH` or `BEARISH`. Otherwise
`NEUTRAL` and the engine stands down.

### H2 — Manipulation leg + dealing range

The manipulation leg is the most recent decisive swing that swept an obvious
high/low and began to reverse. Use `IctStructureDetector` + `LiquidityDetector`
to bracket the leg as `(legLow, legHigh)`. STDV is drawn **body-to-body** on
this leg.

### H3 — STDV projection (exits)

Project from `anchorEnd` of the manipulation leg in the expansion direction
(`+1` bullish, `-1` bearish), using these sigma multiples of `legSize =
legHigh - legLow`:

| σ | Role |
|----|------|
| -0.27 | Early internal checkpoint |
| -1.0  | First partial / break-even trigger |
| -2.0  | Primary reaction zone low (main scale) |
| -2.5  | Primary reaction zone high |
| -4.0  | MAX expansion / terminus (runner) |

Each projected price is **snapped** to the nearest real liquidity from
`ChartStateQueryAPI` / `LevelEngine` (PDH/PDL/EQH/EQL/session H-L/weekly) if
within the instrument's tick tolerance — without reordering the level set.
`ImpulseExtensionAnalyzer` tags the -2.0 projection as
`REALISTIC | AGGRESSIVE | UNREALISTIC` for sizing decisions; it never moves
the price.

## 3. Lower-timeframe phase (1m primary, 5m FVG/context)

### L1 — Liquidity sweep (mandatory precondition)

On 1m/5m, price must sweep a bias-appropriate level (SSL for longs,
BSL for shorts) and reject. Use `RaidDetector` + `LiquidityRaid`. Require
`RaidQualityScorer.score >= instrument.raidMinQuality`. No sweep = no trade.

### L2 — Displacement + FVG (mandatory)

Immediately after the sweep, a displacement candle in the bias direction:
`body_pct > 0.60`, `RVOL > 1.8`, breaks local structure, leaves an FVG.
`DisplacementDetector` + `FvgDetector` provide this.

### L3 — Market Structure Shift / CHoCH (mandatory)

Confirm an MSS/CHoCH on 1m (or 3m) in the bias direction.
`MarketStructureShiftDetector`. CISD is satisfied by this same gate.

### L4 — OTE entry (canonical, mandatory)

Draw Fib on the LTF impulse leg that caused the MSS:

- Bullish: low → high. Retrace down.
- Bearish: high → low. Retrace up.

`OteZone = { 0.50 (eq), 0.62, 0.705 (precise), 0.79, 1.0 (invalidation) }`.

Entry requires **all**:

1. Price retraces into `[0.62, 0.79]`.
2. A PD array (FVG / OB / IFVG / breaker) sits **inside** the zone.
3. A reaction at the zone (rejection wick or lower-TF CHoCH).
4. Entry price = the PD-array edge inside the band, defaulting to 0.705.

### L5 — Stop

Stop sits just beyond the 1.0 (the swept extreme that originated the impulse),
plus an instrument-specific buffer in ticks. Hit = the sweep failed.

### L6 — Targets (from STDV, not guessed)

Map exits to the H3 projection ladder:

| Sigma | Action |
|-------|--------|
| -1.0  | Scale out 1/3 to 1/2; move stop to break-even. |
| -2.0 to -2.5 | Main target band (primary scale). |
| -4.0 | Runner with trailing stop. |

If the -2.0 target is tagged `AGGRESSIVE`, front-load size at -1.0.

**Geometry gate (M7):** `(target_-2.0 - entry) / (entry - stop) >= 2.0R`
or skip the trade — never widen targets to force RR.

## 4. Mandatory gates (M1–M9, sequential, blocking)

The validator short-circuits on the first failure. Optional confluences only
adjust tier/size.

| Gate | Check |
|------|-------|
| M1 | Instrument is MNQ, MES, or MGC. |
| M2 | Trade direction == HTF bias direction. |
| M3 | Inside a killzone (NY AM / Silver Bullet; London for MGC). |
| M4 | Liquidity sweep + `raidScore >= instrument.raidMinQuality`. |
| M5 | Displacement candle + FVG present. |
| M6 | MSS/CHoCH confirmed in bias direction. |
| M7 | Price in OTE band + PD array in OTE + reaction; RR >= 2.0 at -2.0 target. |
| M8 | Sized order in `[5, 20]` micros after MLL-buffer sizing. |
| M9 | Risk guardrails pass (MLL cushion, DLL, consistency, time, contract cap). |

## 5. Optional confluences (tier / size only)

| Code | Confluence | Role |
|------|------------|------|
| O1 | Killzone window | Required (M3) but timing-quality contributes |
| O2 | SMT divergence (MNQ↔MES) | Confirm-only; required for Tier 4 on index pairs |
| O3 | Swept level is PDH/PDL/Weekly | Tier boost |
| O4 | FVG exactly inside OTE | Strongest single tier boost |
| O5 | AMD distribution-aligned | Tier boost |
| O6 | RVOL spike on displacement | Tier boost |
| O7 | StatisticalRetracementEngine band overlaps OTE | Bonus only (demoted from entry) |
| O8 | Multi-TF nesting (5m FVG / 1m OB / 15m IFVG) | Tier boost |

## 6. Tiers and sizing

`TradeTier` re-mapped (existing enum retained):

| Tier | Raid score | Optional count | Size band |
|------|------------|----------------|-----------|
| TIER_1 | ≥ 5 | 0–1 | 5–8 |
| TIER_2 | ≥ 6 | 2–3 | 8–12 |
| TIER_3 | ≥ 7 | 4+ inc. SMT or PDH/PDL | 12–16 |
| TIER_4 | ≥ 8 | SMT + killzone + FVG-in-OTE + level + AMD | 16–20 |

Buffer sizing (SA5):

```
available_room   = equity - mll_floor - safety_cushion
risk_per_trade$  = available_room * riskFraction         # default 0.12
stop_points      = |entry - stop|
per_contract_risk = stop_points * pointValue
raw_contracts    = floor(risk_per_trade$ / per_contract_risk)
contracts        = clamp(min(raw_contracts, tierCap), 5, 20)
contracts        = floor(contracts * newsMultiplier)
if contracts < 5  -> SKIP
```

## 7. Risk guardrails (Topstep, non-negotiable)

- **MLL trail** — `INTRADAY` (default, conservative) or `EOD`. Floor never
  trails down. Verify the user's account/platform.
- **DLL** — block new entries if a worst-case loss would breach.
- **Consistency** — best-day share > 0.40 (configurable) triggers downsize or
  skip.
- **Flatten-by-time** — close all positions before Topstep cutoff (~15:10 CT).
- **Safety cushion** — never size or stop right at the floor.
- **Kill switch** — on breach, flatten + halt + require manual reset.

Pre-trade order: MLL cushion → DLL → contract cap → time guard →
consistency → tier/size floor.

## 8. Setup state machine

```
IDLE → BIAS_SET → MANIP_DONE → SWEEP_DONE → DISPLACED
     → MSS_CONFIRMED → OTE_ARMED → IN_TRADE → MANAGING → DONE
                  (any state → INVALIDATED on bias flip / expiry)
```

Properties:

- No `Order` is emitted before `OTE_ARMED`.
- Never counter-bias.
- One active setup per instrument.
- Same-bar advancement is allowed if multiple gates pass on one candle, but
  each gate is re-validated before advancing.
- Setup expiry: `setup.expiryBars` LTF bars without progress → `INVALIDATED`.

## 9. Data-flow diagram

```
                        ┌──────────────┐
                        │ Candle event │  (HTF and LTF series)
                        └──────┬───────┘
                               │
                               ▼
                ┌──────────────────────────┐
                │   HtfTrendAnalyzer       │   DailyAmdCycleTracker
                │   Power3Detector         │   premium/discount (day open)
                │   draw-on-liquidity      │
                └─────────────┬────────────┘
                              │ 3-of-4 vote
                              ▼
                       htfBias  (or NEUTRAL → stand down)
                              │
                              ▼
        IctStructureDetector + LiquidityDetector
        → manipulation leg (legLow, legHigh)
                              │
                              ▼
              ┌──────────────────────────────┐
              │   StdvProjectionEngine        │  ──► STDV ladder
              │   (snap to LevelEngine)       │       (-0.27/-1/-2/-2.5/-4)
              └──────────────┬────────────────┘
                              ▼
   ┌──────────────────────────┴──────────────────────────┐
   │                  LTF state machine                  │
   │                                                     │
   │  RaidDetector ──► SWEEP_DONE (raidScore >= min)     │
   │  DisplacementDetector + FvgDetector ──► DISPLACED   │
   │  MarketStructureShiftDetector ──► MSS_CONFIRMED     │
   │  OteEntryCalculator + PD-array overlap ──► OTE_ARMED│
   │                                                     │
   │  reaction + MandatoryConfluenceValidator(M1..M9)    │
   │     ▼                                               │
   │  buffer-based sizing [5,20]                          │
   │     ▼                                               │
   │  emit StrategySignalEvent                           │
   └──────────────────────────┬──────────────────────────┘
                              ▼
                ExecutionEngine + BracketOrderManager
                + TrailingStopManager  (manage to STDV ladder)
                              │
                              ▼
                  ┌──────────────────────────┐
                  │  EngineFacade (read API)  │── /api/setup/{symbol}
                  │  + WebSocket push         │── /topic/setup
                  └──────────────────────────┘
```

## 10. Spec ↔ class map

Each numbered model step has a single owner. New code is in
`com.topstep.trading.strategy.stdvote`; reused detectors stay in
`com.topstep.trading.strategy.*`.

| Step | Owner (new ⊕ reused) |
|------|----------------------|
| H1 bias | `StdvOteStrategy.computeHtfBias()` (new) ⊕ `MultiTimeframeAnalyzer`, `HtfTrendAnalyzer`, `DailyAmdCycleTracker`, `Power3Detector` |
| H2 manipulation leg | `StdvOteStrategy.findManipulationLeg()` ⊕ `IctStructureDetector`, `LiquidityDetector` |
| H3 STDV projection | `StdvProjectionEngine` (new, SA2) ⊕ `ChartStateQueryAPI`, `LevelEngine`, `ImpulseExtensionAnalyzer` (realism tag) |
| L1 sweep | `StdvOteStrategy` LTF tick ⊕ `RaidDetector`, `LiquidityRaid`, `RaidQualityScorer` |
| L2 displacement+FVG | `StdvOteStrategy` ⊕ `DisplacementDetector`, `FvgDetector` |
| L3 MSS | `StdvOteStrategy` ⊕ `MarketStructureShiftDetector` |
| L4 OTE entry | `OteEntryCalculator` (new, SA3) ⊕ `FvgDetector`, `OrderBlockDetector`, `BreakerBlockDetector` |
| L5 stop | `OteEntryCalculator.stopPrice()` (new, SA3) |
| L6 targets | derived from `StdvProjection` (SA2) |
| Validator M1..M9 | `MandatoryConfluenceValidator.validateStdvOte()` (new method) |
| Tier + size | `StdvOteStrategy.computeTier()` (new) ⊕ existing `TradeTier`; sizing in SA5 |
| Risk | `PropFirmRiskEngine`, `TradingRiskManager`, `RiskProfile` (kept; SA5 wires sizing) |
| Execution | `ExecutionEngine`, `BracketOrderManager`, `TrailingStopManager` (kept) |
| API | `SetupController` (new, SA6) + WebSocket push |
| UI | `SetupPanel.tsx` (new, SA7) |

## 11. Configuration root

All tunables live under `stdvOte.*` in `application.yml`. The defaults match
Appendix O of the master prompt. Wiring is SA5's responsibility; SA1 only
documents the keys.

## 12. Out of scope for SA1

SA1 produces **interfaces and the design doc** only. No detector wiring, no
sizing math, no order emission. The build must remain green with stubs.
Behavior lands in SA2 (STDV math), SA3 (OTE math), SA4 (state machine +
validator body), SA5 (risk + sizing + registry routing), SA6 (API), SA7 (UI),
SA8 (cleanup), SA9 (tests + backtest comparison), SA10 (merge).
