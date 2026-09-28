# FUNNEL AUTOPSY — C-A bootRun-equivalent (no -D flags: legacy target model, NY killzones only)

tape=C:\Users\Owner\wt-agent-05\trading-engine\src\test\resources\tape symbol=MNQ bars=7830 smtBars=7830 first=2026-09-21 00:00 ET last=2026-09-28 16:22 ET

riskLimits: DLL=1000.0 MLL=2000.0 maxContracts=5 maxTotal=10 riskPerTrade=250.0 riskEngineRR=[1.0,5.0] validatorRR=[1.0,5.0]

cold start: first non-NEUTRAL bias at bar 15 (2026-09-21 00:15 ET); first decisive 3-of-4 vote at bar 15
episodes (BIAS_SET arrivals)=0 stateArrivals={DISPLACED=10, INVALIDATED=108, MANIP_DONE=79, MSS_CONFIRMED=6, OTE_ARMED=1, SWEEP_DONE=99}

## Per-session counts

| session | bars | kzOpen | biasNonNeutral | signals | riskDenied | ordersSent | fills | closed | wins | losses |
|---|---|---|---|---|---|---|---|---|---|---|
| ASIA | 2179 | 2179 | 2164 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| LONDON | 2160 | 2160 | 2160 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| PRE_NY | 540 | 540 | 540 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| NY_AM | 900 | 900 | 900 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| NY_LUNCH | 540 | 540 | 540 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| NY_PM | 810 | 810 | 810 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| PRE_ASIA | 290 | 290 | 290 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| NO_ENTRY | 410 | 0 | 410 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| WEEKEND | 1 | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |

## Gate HOLDING the machine, bars per session (each row sums to that session's bars)

| session | GATE-M2b | INVALIDATED-await-rearm | INVALIDATED-await-rearm-kzClosed | M2-bias-NEUTRAL | M4-no-sweep | M5-displacement-already-consumed | M5-displacement-wrong-direction | M5-no-fvg-for-displacement | M5-no-recent-displacement | M6-no-MSS | M7-no-reaction-at-band | total |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ASIA | 0 | 207 | 0 | 15 | 194 | 16 | 253 | 107 | 1179 | 134 | 74 | 2179 |
| LONDON | 0 | 190 | 0 | 0 | 198 | 15 | 205 | 212 | 1297 | 43 | 0 | 2160 |
| PRE_NY | 39 | 52 | 0 | 0 | 52 | 0 | 37 | 37 | 265 | 48 | 10 | 540 |
| NY_AM | 0 | 76 | 0 | 0 | 103 | 0 | 104 | 40 | 433 | 61 | 83 | 900 |
| NY_LUNCH | 0 | 39 | 0 | 0 | 110 | 0 | 23 | 45 | 323 | 0 | 0 | 540 |
| NY_PM | 0 | 55 | 0 | 0 | 135 | 0 | 69 | 71 | 437 | 43 | 0 | 810 |
| PRE_ASIA | 0 | 14 | 0 | 0 | 102 | 0 | 34 | 0 | 102 | 33 | 5 | 290 |
| NO_ENTRY | 0 | 0 | 293 | 0 | 0 | 0 | 26 | 16 | 20 | 55 | 0 | 410 |
| WEEKEND | 0 | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 1 |

## Top-3 holding gates per session

- ASIA (2179 bars): M5-no-recent-displacement 1179 (54.1%); M5-displacement-wrong-direction 253 (11.6%); INVALIDATED-await-rearm 207 (9.5%)
- LONDON (2160 bars): M5-no-recent-displacement 1297 (60.0%); M5-no-fvg-for-displacement 212 (9.8%); M5-displacement-wrong-direction 205 (9.5%)
- PRE_NY (540 bars): M5-no-recent-displacement 265 (49.1%); INVALIDATED-await-rearm 52 (9.6%); M4-no-sweep 52 (9.6%)
- NY_AM (900 bars): M5-no-recent-displacement 433 (48.1%); M5-displacement-wrong-direction 104 (11.6%); M4-no-sweep 103 (11.4%)
- NY_LUNCH (540 bars): M5-no-recent-displacement 323 (59.8%); M4-no-sweep 110 (20.4%); M5-no-fvg-for-displacement 45 (8.3%)
- NY_PM (810 bars): M5-no-recent-displacement 437 (54.0%); M4-no-sweep 135 (16.7%); M5-no-fvg-for-displacement 71 (8.8%)
- PRE_ASIA (290 bars): M4-no-sweep 102 (35.2%); M5-no-recent-displacement 102 (35.2%); M5-displacement-wrong-direction 34 (11.7%)
- NO_ENTRY (410 bars): INVALIDATED-await-rearm-kzClosed 293 (71.5%); M6-no-MSS 55 (13.4%); M5-displacement-wrong-direction 26 (6.3%)
- WEEKEND (1 bars): INVALIDATED-await-rearm-kzClosed 1 (100.0%)

## Setup deaths (INVALIDATED transitions) by reason per session

- ASIA: {HTF bias flip=8, expired=26}
- LONDON: {HTF bias flip=3, counter-bias MSS observed=1, expired=27}
- PRE_NY: {expired=8}
- NY_AM: {HTF bias flip=2, counter-bias MSS observed=1, expired=9}
- NY_LUNCH: {expired=7}
- NY_PM: {expired=9}
- PRE_ASIA: {HTF bias flip=2}
- NO_ENTRY: {counter-bias MSS observed=2, expired=3}

## Deepest state reached at death per session

- ASIA: {MSS_CONFIRMED=18, OTE_ARMED=14, SWEEP_DONE=2}
- LONDON: {MSS_CONFIRMED=15, OTE_ARMED=10, SWEEP_DONE=6}
- PRE_NY: {MSS_CONFIRMED=4, OTE_ARMED=3, SWEEP_DONE=1}
- NY_AM: {MSS_CONFIRMED=6, OTE_ARMED=6}
- NY_LUNCH: {MSS_CONFIRMED=3, OTE_ARMED=4}
- NY_PM: {MSS_CONFIRMED=5, OTE_ARMED=4}
- PRE_ASIA: {MSS_CONFIRMED=1, OTE_ARMED=1}
- NO_ENTRY: {MSS_CONFIRMED=3, OTE_ARMED=2}

## Stall reasons (FunnelTelemetry) per session

- ASIA: {MSS_CONFIRMED:no-reaction-at-band=74, SWEEP_DONE:displacement-already-consumed=16, SWEEP_DONE:displacement-wrong-direction=253, SWEEP_DONE:no-fvg-for-displacement=107, SWEEP_DONE:no-recent-displacement=1179}
- LONDON: {SWEEP_DONE:displacement-already-consumed=15, SWEEP_DONE:displacement-wrong-direction=205, SWEEP_DONE:no-fvg-for-displacement=212, SWEEP_DONE:no-recent-displacement=1297}
- PRE_NY: {MSS_CONFIRMED:no-reaction-at-band=10, SWEEP_DONE:displacement-wrong-direction=37, SWEEP_DONE:no-fvg-for-displacement=37, SWEEP_DONE:no-recent-displacement=265}
- NY_AM: {MSS_CONFIRMED:no-reaction-at-band=83, SWEEP_DONE:displacement-wrong-direction=104, SWEEP_DONE:no-fvg-for-displacement=40, SWEEP_DONE:no-recent-displacement=433}
- NY_LUNCH: {SWEEP_DONE:displacement-wrong-direction=23, SWEEP_DONE:no-fvg-for-displacement=45, SWEEP_DONE:no-recent-displacement=323}
- NY_PM: {SWEEP_DONE:displacement-wrong-direction=69, SWEEP_DONE:no-fvg-for-displacement=71, SWEEP_DONE:no-recent-displacement=437}
- PRE_ASIA: {MSS_CONFIRMED:no-reaction-at-band=5, SWEEP_DONE:displacement-wrong-direction=34, SWEEP_DONE:no-recent-displacement=102}
- NO_ENTRY: {SWEEP_DONE:displacement-wrong-direction=26, SWEEP_DONE:no-fvg-for-displacement=16, SWEEP_DONE:no-recent-displacement=20}

## Risk-engine denials: {}

## Signal / order / trade log


## FunnelTelemetry (current session): [FUNNEL MNQ] BIAS_SET=0 MANIP_DONE=14 SWEEP_DONE=18 DISPLACED=1 MSS_CONFIRMED=2 OTE_ARMED=0 IN_TRADE=0 | invalidated: expired=16, HTF bias flip=3 | stalls: SWEEP_DONE:no-recent-displacement=743, SWEEP_DONE:displacement-wrong-direction=109, SWEEP_DONE:no-fvg-for-displacement=84, MSS_CONFIRMED:no-reaction-at-band=63
