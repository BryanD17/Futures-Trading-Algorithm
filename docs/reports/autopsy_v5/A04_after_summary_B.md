# FUNNEL AUTOPSY — C-B harness-equivalent (scalpMode.enabled=true scalp.minRaidScore=5 bias.hysteresis.enabled=true)

tape=C:\Users\Owner\wt-agent-04\trading-engine\src\test\resources\tape symbol=MNQ bars=7830 smtBars=7830 first=2026-09-21 00:00 ET last=2026-09-28 16:22 ET

riskLimits: DLL=1000.0 MLL=2000.0 maxContracts=20 maxTotal=20 riskPerTrade=150.0 riskEngineRR=[0.8,1.5] validatorRR=[0.8,1.5]

cold start: first non-NEUTRAL bias at bar 120 (2026-09-21 02:00 ET); first decisive 3-of-4 vote at bar 1730
episodes (BIAS_SET arrivals)=13 stateArrivals={BIAS_SET=13, DISPLACED=23, IDLE=7, INVALIDATED=55, MANIP_DONE=38, MSS_CONFIRMED=28, OTE_ARMED=12, SWEEP_DONE=50}

## Per-session counts

| session | bars | kzOpen | biasNonNeutral | signals | riskDenied | ordersSent | fills | closed | wins | losses |
|---|---|---|---|---|---|---|---|---|---|---|
| ASIA | 2179 | 2166 | 2051 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| LONDON | 2160 | 2145 | 2152 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| PRE_NY | 540 | 534 | 471 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| NY_AM | 900 | 893 | 892 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| NY_LUNCH | 540 | 536 | 540 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| NY_PM | 810 | 805 | 780 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| PRE_ASIA | 290 | 285 | 290 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| NO_ENTRY | 410 | 90 | 410 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| WEEKEND | 1 | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |

## Gate HOLDING the machine, bars per session (each row sums to that session's bars)

| session | GATE-M7 | INVALIDATED-await-rearm | INVALIDATED-await-rearm-kzClosed | M2-bias-NEUTRAL | M4-no-sweep | M5-displacement-wrong-direction | M5-fvg-link-pending | M5-no-fvg-for-displacement | M5-no-recent-displacement | M5-waiting | M6-no-MSS | M7-awaiting-band-touch | M7-no-anchor-leg | MANIP-no-leg | OTE_ARMED-sizer-standdown-or-tier | total |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ASIA | 0 | 90 | 0 | 128 | 123 | 100 | 146 | 9 | 359 | 15 | 115 | 755 | 0 | 195 | 144 | 2179 |
| LONDON | 18 | 103 | 0 | 8 | 77 | 135 | 106 | 0 | 453 | 0 | 395 | 451 | 0 | 235 | 179 | 2160 |
| PRE_NY | 0 | 38 | 0 | 69 | 15 | 50 | 25 | 5 | 99 | 25 | 55 | 13 | 0 | 146 | 0 | 540 |
| NY_AM | 0 | 49 | 0 | 8 | 90 | 190 | 55 | 45 | 108 | 25 | 45 | 101 | 0 | 143 | 41 | 900 |
| NY_LUNCH | 0 | 31 | 0 | 0 | 40 | 55 | 65 | 0 | 65 | 0 | 95 | 189 | 0 | 0 | 0 | 540 |
| NY_PM | 0 | 29 | 0 | 30 | 58 | 15 | 70 | 20 | 62 | 0 | 270 | 117 | 15 | 83 | 41 | 810 |
| PRE_ASIA | 0 | 13 | 0 | 0 | 77 | 27 | 5 | 0 | 82 | 0 | 10 | 10 | 0 | 66 | 0 | 290 |
| NO_ENTRY | 0 | 2 | 95 | 0 | 14 | 0 | 5 | 0 | 57 | 0 | 135 | 87 | 0 | 15 | 0 | 410 |
| WEEKEND | 0 | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 1 |

## Top-3 holding gates per session

- ASIA (2179 bars): M7-awaiting-band-touch 755 (34.6%); M5-no-recent-displacement 359 (16.5%); MANIP-no-leg 195 (8.9%)
- LONDON (2160 bars): M5-no-recent-displacement 453 (21.0%); M7-awaiting-band-touch 451 (20.9%); M6-no-MSS 395 (18.3%)
- PRE_NY (540 bars): MANIP-no-leg 146 (27.0%); M5-no-recent-displacement 99 (18.3%); M2-bias-NEUTRAL 69 (12.8%)
- NY_AM (900 bars): M5-displacement-wrong-direction 190 (21.1%); MANIP-no-leg 143 (15.9%); M5-no-recent-displacement 108 (12.0%)
- NY_LUNCH (540 bars): M7-awaiting-band-touch 189 (35.0%); M6-no-MSS 95 (17.6%); M5-fvg-link-pending 65 (12.0%)
- NY_PM (810 bars): M6-no-MSS 270 (33.3%); M7-awaiting-band-touch 117 (14.4%); MANIP-no-leg 83 (10.2%)
- PRE_ASIA (290 bars): M5-no-recent-displacement 82 (28.3%); M4-no-sweep 77 (26.6%); MANIP-no-leg 66 (22.8%)
- NO_ENTRY (410 bars): M6-no-MSS 135 (32.9%); INVALIDATED-await-rearm-kzClosed 95 (23.2%); M7-awaiting-band-touch 87 (21.2%)
- WEEKEND (1 bars): INVALIDATED-await-rearm-kzClosed 1 (100.0%)

## Setup deaths (INVALIDATED transitions) by reason per session

- ASIA: {HTF bias NEUTRAL beyond grace=1, HTF bias flip=7, MSS stale before price reached the OTE band (# detector bars)=2, OTE window expired (# bars)=2, expired=1}
- LONDON: {HTF bias flip=6, MSS stale before price reached the OTE band (# detector bars)=1, OTE window expired (# bars)=4, expired=5}
- PRE_NY: {HTF bias NEUTRAL beyond grace=3, HTF bias flip=1, expired=1}
- NY_AM: {HTF bias NEUTRAL beyond grace=1, HTF bias flip=5, OTE window expired (# bars)=1}
- NY_LUNCH: {HTF bias flip=3, expired=3}
- NY_PM: {HTF bias flip=2, OTE window expired (# bars)=1}
- PRE_ASIA: {expired=1}
- NO_ENTRY: {MSS stale before price reached the OTE band (# detector bars)=1, expired=2}
- WEEKEND: {HTF bias NEUTRAL beyond grace=1}

## Deepest state reached at death per session

- ASIA: {BIAS_SET=1, MSS_CONFIRMED=2, OTE_ARMED=7, SWEEP_DONE=3}
- LONDON: {BIAS_SET=2, MSS_CONFIRMED=5, OTE_ARMED=8, SWEEP_DONE=1}
- PRE_NY: {BIAS_SET=1, MSS_CONFIRMED=3, OTE_ARMED=1}
- NY_AM: {BIAS_SET=2, DISPLACED=1, OTE_ARMED=1, SWEEP_DONE=3}
- NY_LUNCH: {DISPLACED=1, MSS_CONFIRMED=3, OTE_ARMED=1, SWEEP_DONE=1}
- NY_PM: {MSS_CONFIRMED=2, OTE_ARMED=1}
- PRE_ASIA: {SWEEP_DONE=1}
- NO_ENTRY: {MSS_CONFIRMED=3}
- WEEKEND: {OTE_ARMED=1}

## Stall reasons (FunnelTelemetry) per session

- ASIA: {MSS_CONFIRMED:awaiting-band-touch=755, OTE_ARMED:no-reaction-at-band=2, SWEEP_DONE:displacement-wrong-direction=100, SWEEP_DONE:fvg-link-pending=146, SWEEP_DONE:no-fvg-for-displacement=9, SWEEP_DONE:no-recent-displacement=359}
- LONDON: {MSS_CONFIRMED:awaiting-band-touch=451, OTE_ARMED:no-reaction-at-band=1, SWEEP_DONE:displacement-wrong-direction=135, SWEEP_DONE:fvg-link-pending=106, SWEEP_DONE:no-recent-displacement=453}
- PRE_NY: {MSS_CONFIRMED:awaiting-band-touch=13, SWEEP_DONE:displacement-wrong-direction=50, SWEEP_DONE:fvg-link-pending=25, SWEEP_DONE:no-fvg-for-displacement=5, SWEEP_DONE:no-recent-displacement=99}
- NY_AM: {MSS_CONFIRMED:awaiting-band-touch=101, OTE_ARMED:no-reaction-at-band=2, SWEEP_DONE:displacement-wrong-direction=190, SWEEP_DONE:fvg-link-pending=55, SWEEP_DONE:no-fvg-for-displacement=45, SWEEP_DONE:no-recent-displacement=108}
- NY_LUNCH: {MSS_CONFIRMED:awaiting-band-touch=189, SWEEP_DONE:displacement-wrong-direction=55, SWEEP_DONE:fvg-link-pending=65, SWEEP_DONE:no-recent-displacement=65}
- NY_PM: {MSS_CONFIRMED:awaiting-band-touch=117, MSS_CONFIRMED:no-anchor-leg=15, SWEEP_DONE:displacement-wrong-direction=15, SWEEP_DONE:fvg-link-pending=70, SWEEP_DONE:no-fvg-for-displacement=20, SWEEP_DONE:no-recent-displacement=62}
- PRE_ASIA: {MSS_CONFIRMED:awaiting-band-touch=10, SWEEP_DONE:displacement-wrong-direction=27, SWEEP_DONE:fvg-link-pending=5, SWEEP_DONE:no-recent-displacement=82}
- NO_ENTRY: {MSS_CONFIRMED:awaiting-band-touch=87, SWEEP_DONE:fvg-link-pending=5, SWEEP_DONE:no-recent-displacement=57}

## Risk-engine denials: {}

## Signal / order / trade log


## FunnelTelemetry (current session): [FUNNEL MNQ] BIAS_SET=1 MANIP_DONE=8 SWEEP_DONE=10 DISPLACED=5 MSS_CONFIRMED=6 OTE_ARMED=3 IN_TRADE=0 | invalidated: HTF bias flip=4, OTE window expired (40 bars)=2, HTF bias NEUTRAL beyond grace=2, MSS stale before price reached the OTE band (30 detector bars)=1 | stalls: MSS_CONFIRMED:awaiting-band-touch=276, SWEEP_DONE:no-recent-displacement=208, SWEEP_DONE:displacement-wrong-direction=120, SWEEP_DONE:fvg-link-pending=105
