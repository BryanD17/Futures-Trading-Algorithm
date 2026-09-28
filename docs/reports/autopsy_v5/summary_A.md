# FUNNEL AUTOPSY — C-A bootRun-equivalent (no -D flags: legacy target model, NY killzones only)

tape=C:\Users\Owner\topstep-trading\tape symbol=MNQ bars=7830 smtBars=7830 first=2026-09-21 00:00 ET last=2026-09-28 16:22 ET

riskLimits: DLL=1000.0 MLL=2000.0 maxContracts=5 maxTotal=10 riskPerTrade=250.0 riskEngineRR=[3.0,6.0] validatorRR=[2.0,Infinity]

cold start: first non-NEUTRAL bias at bar 120 (2026-09-21 02:00 ET); first decisive 3-of-4 vote at bar 1730
episodes (BIAS_SET arrivals)=12 stateArrivals={BIAS_SET=12, DISPLACED=4, IDLE=8, INVALIDATED=23, MANIP_DONE=16, MSS_CONFIRMED=4, OTE_ARMED=1, SWEEP_DONE=20}

## Per-session counts

| session | bars | kzOpen | biasNonNeutral | signals | riskDenied | ordersSent | fills | closed | wins | losses |
|---|---|---|---|---|---|---|---|---|---|---|
| ASIA | 2179 | 0 | 1994 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| LONDON | 2160 | 354 | 2077 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| PRE_NY | 540 | 0 | 405 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| NY_AM | 900 | 798 | 823 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| NY_LUNCH | 540 | 179 | 540 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| NY_PM | 810 | 712 | 756 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| PRE_ASIA | 290 | 0 | 290 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| NO_ENTRY | 410 | 90 | 383 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| WEEKEND | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |

## Gate HOLDING the machine, bars per session (each row sums to that session's bars)

| session | GATE-M3 | INVALIDATED-await-rearm | INVALIDATED-await-rearm-kzClosed | M2-bias-NEUTRAL | M4-no-sweep | M5-displacement-already-consumed | M5-displacement-wrong-direction | M5-no-fvg-for-displacement | M5-no-recent-displacement | M6-no-MSS | M7-no-reaction-at-band | M7-ote-not-armed-after-reaction | MANIP-no-leg | total |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ASIA | 0 | 0 | 2059 | 120 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 2179 |
| LONDON | 0 | 7 | 1182 | 8 | 62 | 0 | 70 | 149 | 550 | 126 | 0 | 0 | 6 | 2160 |
| PRE_NY | 0 | 0 | 540 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 540 |
| NY_AM | 0 | 38 | 94 | 77 | 110 | 10 | 0 | 48 | 249 | 216 | 56 | 0 | 2 | 900 |
| NY_LUNCH | 0 | 7 | 155 | 0 | 0 | 0 | 25 | 38 | 161 | 73 | 81 | 0 | 0 | 540 |
| NY_PM | 0 | 21 | 75 | 54 | 111 | 0 | 43 | 20 | 411 | 0 | 60 | 0 | 15 | 810 |
| PRE_ASIA | 0 | 0 | 290 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 290 |
| NO_ENTRY | 41 | 0 | 164 | 0 | 0 | 0 | 30 | 60 | 54 | 0 | 59 | 2 | 0 | 410 |
| WEEKEND | 0 | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 1 |

## Top-3 holding gates per session

- ASIA (2179 bars): INVALIDATED-await-rearm-kzClosed 2059 (94.5%); M2-bias-NEUTRAL 120 (5.5%)
- LONDON (2160 bars): INVALIDATED-await-rearm-kzClosed 1182 (54.7%); M5-no-recent-displacement 550 (25.5%); M5-no-fvg-for-displacement 149 (6.9%)
- PRE_NY (540 bars): INVALIDATED-await-rearm-kzClosed 540 (100.0%)
- NY_AM (900 bars): M5-no-recent-displacement 249 (27.7%); M6-no-MSS 216 (24.0%); M4-no-sweep 110 (12.2%)
- NY_LUNCH (540 bars): M5-no-recent-displacement 161 (29.8%); INVALIDATED-await-rearm-kzClosed 155 (28.7%); M7-no-reaction-at-band 81 (15.0%)
- NY_PM (810 bars): M5-no-recent-displacement 411 (50.7%); M4-no-sweep 111 (13.7%); INVALIDATED-await-rearm-kzClosed 75 (9.3%)
- PRE_ASIA (290 bars): INVALIDATED-await-rearm-kzClosed 290 (100.0%)
- NO_ENTRY (410 bars): INVALIDATED-await-rearm-kzClosed 164 (40.0%); M5-no-fvg-for-displacement 60 (14.6%); M7-no-reaction-at-band 59 (14.4%)
- WEEKEND (1 bars): INVALIDATED-await-rearm-kzClosed 1 (100.0%)

## Setup deaths (INVALIDATED transitions) by reason per session

- LONDON: {HTF bias became NEUTRAL=1, HTF bias flip=3, expired=3}
- NY_AM: {HTF bias became NEUTRAL=1, impulse origin violated before OTE entry=1}
- NY_LUNCH: {HTF bias flip=3, expired=3}
- NY_PM: {HTF bias became NEUTRAL=3}
- NO_ENTRY: {HTF bias became NEUTRAL=3, HTF bias flip=1, OTE window expired (# bars)=1}

## Deepest state reached at death per session

- LONDON: {DISPLACED=1, SWEEP_DONE=6}
- NY_AM: {DISPLACED=1, MSS_CONFIRMED=1}
- NY_LUNCH: {DISPLACED=2, MSS_CONFIRMED=2, SWEEP_DONE=2}
- NY_PM: {SWEEP_DONE=3}
- NO_ENTRY: {MSS_CONFIRMED=1, OTE_ARMED=1, SWEEP_DONE=3}

## Stall reasons (FunnelTelemetry) per session

- LONDON: {SWEEP_DONE:displacement-wrong-direction=70, SWEEP_DONE:no-fvg-for-displacement=149, SWEEP_DONE:no-recent-displacement=550}
- NY_AM: {MSS_CONFIRMED:no-reaction-at-band=56, SWEEP_DONE:displacement-already-consumed=10, SWEEP_DONE:no-fvg-for-displacement=48, SWEEP_DONE:no-recent-displacement=249}
- NY_LUNCH: {MSS_CONFIRMED:no-reaction-at-band=81, SWEEP_DONE:displacement-wrong-direction=25, SWEEP_DONE:no-fvg-for-displacement=38, SWEEP_DONE:no-recent-displacement=161}
- NY_PM: {MSS_CONFIRMED:no-reaction-at-band=60, SWEEP_DONE:displacement-wrong-direction=43, SWEEP_DONE:no-fvg-for-displacement=20, SWEEP_DONE:no-recent-displacement=411}
- NO_ENTRY: {MSS_CONFIRMED:no-reaction-at-band=59, MSS_CONFIRMED:ote-not-armed-after-reaction=2, SWEEP_DONE:displacement-wrong-direction=30, SWEEP_DONE:no-fvg-for-displacement=60, SWEEP_DONE:no-recent-displacement=54}

## Risk-engine denials: {}

## Signal / order / trade log


## FunnelTelemetry (current session): [FUNNEL MNQ] BIAS_SET=2 MANIP_DONE=4 SWEEP_DONE=4 DISPLACED=0 MSS_CONFIRMED=1 OTE_ARMED=0 IN_TRADE=0 | invalidated: expired=1, impulse origin violated before OTE entry=1, HTF bias flip=1, HTF bias became NEUTRAL=1 | stalls: SWEEP_DONE:no-recent-displacement=314, SWEEP_DONE:no-fvg-for-displacement=56, SWEEP_DONE:displacement-already-consumed=10, SWEEP_DONE:displacement-wrong-direction=10
