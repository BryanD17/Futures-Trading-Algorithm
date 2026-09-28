# FUNNEL AUTOPSY — custom (D)

tape=C:\Users\Owner\topstep-trading\tape symbol=MNQ bars=7830 smtBars=7830 first=2026-09-21 00:00 ET last=2026-09-28 16:22 ET

riskLimits: DLL=1000.0 MLL=2000.0 maxContracts=20 maxTotal=20 riskPerTrade=150.0 riskEngineRR=[0.8,1.5] validatorRR=[0.8,1.5]

cold start: first non-NEUTRAL bias at bar 120 (2026-09-21 02:00 ET); first decisive 3-of-4 vote at bar 1730
episodes (BIAS_SET arrivals)=16 stateArrivals={BIAS_SET=16, IDLE=16, INVALIDATED=52, MANIP_DONE=47, SWEEP_DONE=2}

## Per-session counts

| session | bars | kzOpen | biasNonNeutral | signals | riskDenied | ordersSent | fills | closed | wins | losses |
|---|---|---|---|---|---|---|---|---|---|---|
| ASIA | 2179 | 2167 | 2015 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| LONDON | 2160 | 2144 | 2098 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| PRE_NY | 540 | 535 | 419 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| NY_AM | 900 | 895 | 823 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| NY_LUNCH | 540 | 534 | 540 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| NY_PM | 810 | 807 | 756 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| PRE_ASIA | 290 | 285 | 290 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| NO_ENTRY | 410 | 90 | 383 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| WEEKEND | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |

## Gate HOLDING the machine, bars per session (each row sums to that session's bars)

| session | INVALIDATED-await-rearm | INVALIDATED-await-rearm-kzClosed | M2-bias-NEUTRAL | M4-no-sweep | M5-displacement-wrong-direction | M5-no-recent-displacement | MANIP-no-leg | total |
|---|---|---|---|---|---|---|---|---|
| ASIA | 80 | 0 | 164 | 1748 | 0 | 0 | 187 | 2179 |
| LONDON | 107 | 0 | 62 | 1779 | 0 | 0 | 212 | 2160 |
| PRE_NY | 31 | 0 | 121 | 242 | 0 | 0 | 146 | 540 |
| NY_AM | 35 | 0 | 77 | 670 | 10 | 3 | 105 | 900 |
| NY_LUNCH | 39 | 0 | 0 | 498 | 3 | 0 | 0 | 540 |
| NY_PM | 21 | 0 | 54 | 645 | 0 | 0 | 90 | 810 |
| PRE_ASIA | 0 | 0 | 0 | 220 | 0 | 0 | 70 | 290 |
| NO_ENTRY | 2 | 173 | 0 | 220 | 0 | 0 | 15 | 410 |
| WEEKEND | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 1 |

## Top-3 holding gates per session

- ASIA (2179 bars): M4-no-sweep 1748 (80.2%); MANIP-no-leg 187 (8.6%); M2-bias-NEUTRAL 164 (7.5%)
- LONDON (2160 bars): M4-no-sweep 1779 (82.4%); MANIP-no-leg 212 (9.8%); INVALIDATED-await-rearm 107 (5.0%)
- PRE_NY (540 bars): M4-no-sweep 242 (44.8%); MANIP-no-leg 146 (27.0%); M2-bias-NEUTRAL 121 (22.4%)
- NY_AM (900 bars): M4-no-sweep 670 (74.4%); MANIP-no-leg 105 (11.7%); M2-bias-NEUTRAL 77 (8.6%)
- NY_LUNCH (540 bars): M4-no-sweep 498 (92.2%); INVALIDATED-await-rearm 39 (7.2%); M5-displacement-wrong-direction 3 (0.6%)
- NY_PM (810 bars): M4-no-sweep 645 (79.6%); MANIP-no-leg 90 (11.1%); M2-bias-NEUTRAL 54 (6.7%)
- PRE_ASIA (290 bars): M4-no-sweep 220 (75.9%); MANIP-no-leg 70 (24.1%)
- NO_ENTRY (410 bars): M4-no-sweep 220 (53.7%); INVALIDATED-await-rearm-kzClosed 173 (42.2%); MANIP-no-leg 15 (3.7%)
- WEEKEND (1 bars): INVALIDATED-await-rearm-kzClosed 1 (100.0%)

## Setup deaths (INVALIDATED transitions) by reason per session

- ASIA: {HTF bias became NEUTRAL=3, HTF bias flip=5, expired=4}
- LONDON: {HTF bias became NEUTRAL=4, HTF bias flip=5, expired=8}
- PRE_NY: {HTF bias became NEUTRAL=2, HTF bias flip=1, expired=1}
- NY_AM: {HTF bias became NEUTRAL=4, HTF bias flip=1}
- NY_LUNCH: {HTF bias flip=3, expired=3}
- NY_PM: {HTF bias became NEUTRAL=3}
- NO_ENTRY: {HTF bias became NEUTRAL=3, expired=2}

## Deepest state reached at death per session

- ASIA: {BIAS_SET=1, MANIP_DONE=11}
- LONDON: {BIAS_SET=3, MANIP_DONE=14}
- PRE_NY: {MANIP_DONE=4}
- NY_AM: {BIAS_SET=2, MANIP_DONE=2, SWEEP_DONE=1}
- NY_LUNCH: {MANIP_DONE=4, SWEEP_DONE=2}
- NY_PM: {MANIP_DONE=2, SWEEP_DONE=1}
- NO_ENTRY: {MANIP_DONE=4, SWEEP_DONE=1}

## Stall reasons (FunnelTelemetry) per session

- NY_AM: {SWEEP_DONE:displacement-wrong-direction=10, SWEEP_DONE:no-recent-displacement=3}
- NY_LUNCH: {SWEEP_DONE:displacement-wrong-direction=3}

## Risk-engine denials: {}

## Signal / order / trade log


## FunnelTelemetry (current session): [FUNNEL MNQ] BIAS_SET=2 MANIP_DONE=10 SWEEP_DONE=1 DISPLACED=0 MSS_CONFIRMED=0 OTE_ARMED=0 IN_TRADE=0 | invalidated: HTF bias flip=3, expired=3, HTF bias became NEUTRAL=3 | stalls: SWEEP_DONE:displacement-wrong-direction=3
