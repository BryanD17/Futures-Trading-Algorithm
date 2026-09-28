# FUNNEL AUTOPSY — C-A bootRun-equivalent (no -D flags: legacy target model, NY killzones only)

tape=C:\Users\Owner\wt-agent-04\trading-engine\src\test\resources\tape symbol=MNQ bars=7830 smtBars=7830 first=2026-09-21 00:00 ET last=2026-09-28 16:22 ET

riskLimits: DLL=1000.0 MLL=2000.0 maxContracts=5 maxTotal=10 riskPerTrade=250.0 riskEngineRR=[3.0,6.0] validatorRR=[2.0,Infinity]

cold start: first non-NEUTRAL bias at bar 120 (2026-09-21 02:00 ET); first decisive 3-of-4 vote at bar 1730
episodes (BIAS_SET arrivals)=12 stateArrivals={BIAS_SET=12, DISPLACED=12, IDLE=8, INVALIDATED=23, MANIP_DONE=17, MSS_CONFIRMED=16, OTE_ARMED=7, SWEEP_DONE=24}

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

| session | GATE-M3 | GATE-M4 | GATE-M7 | INVALIDATED-await-rearm | INVALIDATED-await-rearm-kzClosed | M2-bias-NEUTRAL | M4-no-sweep | M5-displacement-wrong-direction | M5-fvg-link-pending | M5-no-fvg-for-displacement | M5-no-recent-displacement | M6-no-MSS | M7-awaiting-band-touch | MANIP-no-leg | OTE_ARMED-sizer-standdown-or-tier | total |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ASIA | 0 | 0 | 0 | 0 | 2059 | 120 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 2179 |
| LONDON | 58 | 0 | 0 | 7 | 1219 | 8 | 62 | 165 | 90 | 0 | 127 | 60 | 357 | 6 | 1 | 2160 |
| PRE_NY | 0 | 0 | 0 | 0 | 540 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 540 |
| NY_AM | 0 | 39 | 16 | 38 | 94 | 77 | 116 | 140 | 60 | 45 | 91 | 65 | 115 | 2 | 2 | 900 |
| NY_LUNCH | 0 | 0 | 0 | 7 | 155 | 0 | 0 | 50 | 55 | 0 | 13 | 85 | 175 | 0 | 0 | 540 |
| NY_PM | 0 | 0 | 33 | 21 | 75 | 54 | 111 | 10 | 85 | 0 | 159 | 195 | 52 | 15 | 0 | 810 |
| PRE_ASIA | 0 | 0 | 0 | 0 | 284 | 0 | 0 | 0 | 0 | 0 | 0 | 5 | 1 | 0 | 0 | 290 |
| NO_ENTRY | 0 | 0 | 0 | 0 | 146 | 0 | 0 | 0 | 5 | 0 | 15 | 110 | 103 | 0 | 31 | 410 |
| WEEKEND | 0 | 0 | 0 | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 1 |

## Top-3 holding gates per session

- ASIA (2179 bars): INVALIDATED-await-rearm-kzClosed 2059 (94.5%); M2-bias-NEUTRAL 120 (5.5%)
- LONDON (2160 bars): INVALIDATED-await-rearm-kzClosed 1219 (56.4%); M7-awaiting-band-touch 357 (16.5%); M5-displacement-wrong-direction 165 (7.6%)
- PRE_NY (540 bars): INVALIDATED-await-rearm-kzClosed 540 (100.0%)
- NY_AM (900 bars): M5-displacement-wrong-direction 140 (15.6%); M4-no-sweep 116 (12.9%); M7-awaiting-band-touch 115 (12.8%)
- NY_LUNCH (540 bars): M7-awaiting-band-touch 175 (32.4%); INVALIDATED-await-rearm-kzClosed 155 (28.7%); M6-no-MSS 85 (15.7%)
- NY_PM (810 bars): M6-no-MSS 195 (24.1%); M5-no-recent-displacement 159 (19.6%); M4-no-sweep 111 (13.7%)
- PRE_ASIA (290 bars): INVALIDATED-await-rearm-kzClosed 284 (97.9%); M6-no-MSS 5 (1.7%); M7-awaiting-band-touch 1 (0.3%)
- NO_ENTRY (410 bars): INVALIDATED-await-rearm-kzClosed 146 (35.6%); M6-no-MSS 110 (26.8%); M7-awaiting-band-touch 103 (25.1%)
- WEEKEND (1 bars): INVALIDATED-await-rearm-kzClosed 1 (100.0%)

## Setup deaths (INVALIDATED transitions) by reason per session

- LONDON: {HTF bias became NEUTRAL=1, HTF bias flip=2, OTE window expired (# bars)=1, expired=3}
- NY_AM: {HTF bias became NEUTRAL=1, OTE window expired (# bars)=1}
- NY_LUNCH: {HTF bias flip=3, expired=3}
- NY_PM: {HTF bias became NEUTRAL=3}
- PRE_ASIA: {expired=1}
- NO_ENTRY: {HTF bias became NEUTRAL=3, HTF bias flip=1}

## Deepest state reached at death per session

- LONDON: {DISPLACED=1, MSS_CONFIRMED=3, OTE_ARMED=2, SWEEP_DONE=1}
- NY_AM: {OTE_ARMED=2}
- NY_LUNCH: {MSS_CONFIRMED=3, OTE_ARMED=3}
- NY_PM: {OTE_ARMED=1, SWEEP_DONE=2}
- PRE_ASIA: {MSS_CONFIRMED=1}
- NO_ENTRY: {MSS_CONFIRMED=1, OTE_ARMED=3}

## Stall reasons (FunnelTelemetry) per session

- LONDON: {MSS_CONFIRMED:awaiting-band-touch=357, OTE_ARMED:no-reaction-at-band=1, SWEEP_DONE:displacement-wrong-direction=165, SWEEP_DONE:fvg-link-pending=90, SWEEP_DONE:no-recent-displacement=127}
- NY_AM: {MSS_CONFIRMED:awaiting-band-touch=115, OTE_ARMED:no-reaction-at-band=2, SWEEP_DONE:displacement-wrong-direction=140, SWEEP_DONE:fvg-link-pending=60, SWEEP_DONE:no-fvg-for-displacement=45, SWEEP_DONE:no-recent-displacement=91}
- NY_LUNCH: {MSS_CONFIRMED:awaiting-band-touch=175, SWEEP_DONE:displacement-wrong-direction=50, SWEEP_DONE:fvg-link-pending=55, SWEEP_DONE:no-recent-displacement=13}
- NY_PM: {MSS_CONFIRMED:awaiting-band-touch=52, SWEEP_DONE:displacement-wrong-direction=10, SWEEP_DONE:fvg-link-pending=85, SWEEP_DONE:no-recent-displacement=159}
- PRE_ASIA: {MSS_CONFIRMED:awaiting-band-touch=1}
- NO_ENTRY: {MSS_CONFIRMED:awaiting-band-touch=103, OTE_ARMED:no-entry-block-14:45-17:00CT=31, SWEEP_DONE:fvg-link-pending=5, SWEEP_DONE:no-recent-displacement=15}

## Risk-engine denials: {}

## Signal / order / trade log


## FunnelTelemetry (current session): [FUNNEL MNQ] BIAS_SET=2 MANIP_DONE=4 SWEEP_DONE=4 DISPLACED=1 MSS_CONFIRMED=2 OTE_ARMED=0 IN_TRADE=0 | invalidated: expired=1, HTF bias flip=1, HTF bias became NEUTRAL=1 | stalls: MSS_CONFIRMED:awaiting-band-touch=135, SWEEP_DONE:no-recent-displacement=103, SWEEP_DONE:displacement-wrong-direction=90, SWEEP_DONE:fvg-link-pending=45
