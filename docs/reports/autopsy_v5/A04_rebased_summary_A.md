# FUNNEL AUTOPSY — C-A bootRun-equivalent (no -D flags: legacy target model, NY killzones only)

tape=C:\Users\Owner\wt-agent-04\trading-engine\src\test\resources\tape symbol=MNQ bars=7830 smtBars=7830 first=2026-09-21 00:00 ET last=2026-09-28 16:22 ET

riskLimits: DLL=1000.0 MLL=2000.0 maxContracts=5 maxTotal=10 riskPerTrade=250.0 riskEngineRR=[3.0,6.0] validatorRR=[2.0,Infinity]

cold start: first non-NEUTRAL bias at bar 15 (2026-09-21 00:15 ET); first decisive 3-of-4 vote at bar 15
episodes (BIAS_SET arrivals)=0 stateArrivals={DISPLACED=26, INVALIDATED=104, IN_TRADE=2, MANIP_DONE=75, MSS_CONFIRMED=28, OTE_ARMED=10, SWEEP_DONE=104}

## Per-session counts

| session | bars | kzOpen | biasNonNeutral | signals | riskDenied | ordersSent | fills | closed | wins | losses |
|---|---|---|---|---|---|---|---|---|---|---|
| ASIA | 2179 | 2179 | 2164 | 1 | 1 | 0 | 0 | 0 | 0 | 0 |
| LONDON | 2160 | 2160 | 2160 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| PRE_NY | 540 | 540 | 540 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| NY_AM | 900 | 900 | 900 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| NY_LUNCH | 540 | 540 | 540 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| NY_PM | 810 | 810 | 810 | 1 | 0 | 1 | 0 | 0 | 0 | 0 |
| PRE_ASIA | 290 | 290 | 290 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| NO_ENTRY | 410 | 0 | 410 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| WEEKEND | 1 | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |

## Gate HOLDING the machine, bars per session (each row sums to that session's bars)

| session | GATE-M2b | GATE-M7 | INVALIDATED-await-rearm | INVALIDATED-await-rearm-kzClosed | IN_TRADE | M2-bias-NEUTRAL | M4-no-sweep | M5-displacement-wrong-direction | M5-fvg-link-pending | M5-no-fvg-for-displacement | M5-no-recent-displacement | M6-no-MSS | M7-awaiting-band-touch | OTE_ARMED-sizer-standdown-or-tier | total |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ASIA | 47 | 42 | 209 | 0 | 34 | 15 | 190 | 220 | 236 | 39 | 805 | 124 | 215 | 3 | 2179 |
| LONDON | 46 | 0 | 177 | 0 | 124 | 0 | 205 | 309 | 116 | 0 | 819 | 112 | 250 | 2 | 2160 |
| PRE_NY | 0 | 0 | 47 | 0 | 0 | 0 | 44 | 104 | 47 | 0 | 128 | 170 | 0 | 0 | 540 |
| NY_AM | 0 | 0 | 74 | 0 | 0 | 0 | 100 | 201 | 82 | 12 | 240 | 113 | 78 | 0 | 900 |
| NY_LUNCH | 0 | 0 | 40 | 0 | 0 | 0 | 134 | 17 | 85 | 0 | 131 | 67 | 61 | 5 | 540 |
| NY_PM | 0 | 0 | 55 | 0 | 19 | 0 | 135 | 29 | 116 | 35 | 309 | 45 | 67 | 0 | 810 |
| PRE_ASIA | 0 | 0 | 14 | 0 | 0 | 0 | 102 | 0 | 10 | 0 | 144 | 10 | 10 | 0 | 290 |
| NO_ENTRY | 0 | 0 | 0 | 231 | 39 | 0 | 0 | 10 | 30 | 0 | 67 | 22 | 0 | 11 | 410 |
| WEEKEND | 0 | 0 | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 1 |

## Top-3 holding gates per session

- ASIA (2179 bars): M5-no-recent-displacement 805 (36.9%); M5-fvg-link-pending 236 (10.8%); M5-displacement-wrong-direction 220 (10.1%)
- LONDON (2160 bars): M5-no-recent-displacement 819 (37.9%); M5-displacement-wrong-direction 309 (14.3%); M7-awaiting-band-touch 250 (11.6%)
- PRE_NY (540 bars): M6-no-MSS 170 (31.5%); M5-no-recent-displacement 128 (23.7%); M5-displacement-wrong-direction 104 (19.3%)
- NY_AM (900 bars): M5-no-recent-displacement 240 (26.7%); M5-displacement-wrong-direction 201 (22.3%); M6-no-MSS 113 (12.6%)
- NY_LUNCH (540 bars): M4-no-sweep 134 (24.8%); M5-no-recent-displacement 131 (24.3%); M5-fvg-link-pending 85 (15.7%)
- NY_PM (810 bars): M5-no-recent-displacement 309 (38.1%); M4-no-sweep 135 (16.7%); M5-fvg-link-pending 116 (14.3%)
- PRE_ASIA (290 bars): M5-no-recent-displacement 144 (49.7%); M4-no-sweep 102 (35.2%); INVALIDATED-await-rearm 14 (4.8%)
- NO_ENTRY (410 bars): INVALIDATED-await-rearm-kzClosed 231 (56.3%); M5-no-recent-displacement 67 (16.3%); IN_TRADE 39 (9.5%)
- WEEKEND (1 bars): INVALIDATED-await-rearm-kzClosed 1 (100.0%)

## Setup deaths (INVALIDATED transitions) by reason per session

- ASIA: {HTF bias flip=8, OTE window expired (# bars)=2, expired=24}
- LONDON: {HTF bias flip=3, expired=26}
- PRE_NY: {expired=7}
- NY_AM: {HTF bias flip=2, expired=10}
- NY_LUNCH: {OTE invalidated: close # beyond range extreme #=1, expired=6}
- NY_PM: {expired=9}
- PRE_ASIA: {HTF bias flip=2}
- NO_ENTRY: {expired=4}

## Deepest state reached at death per session

- ASIA: {IN_TRADE=20, MSS_CONFIRMED=6, OTE_ARMED=6, SWEEP_DONE=2}
- LONDON: {IN_TRADE=18, MSS_CONFIRMED=10, OTE_ARMED=1}
- PRE_NY: {IN_TRADE=5, MSS_CONFIRMED=1, OTE_ARMED=1}
- NY_AM: {IN_TRADE=8, MSS_CONFIRMED=2, OTE_ARMED=2}
- NY_LUNCH: {IN_TRADE=5, MSS_CONFIRMED=1, OTE_ARMED=1}
- NY_PM: {IN_TRADE=6, MSS_CONFIRMED=1, OTE_ARMED=2}
- PRE_ASIA: {IN_TRADE=2}
- NO_ENTRY: {IN_TRADE=2, MSS_CONFIRMED=1, OTE_ARMED=1}

## Stall reasons (FunnelTelemetry) per session

- ASIA: {MSS_CONFIRMED:awaiting-band-touch=215, OTE_ARMED:no-reaction-at-band=3, SWEEP_DONE:displacement-wrong-direction=220, SWEEP_DONE:fvg-link-pending=236, SWEEP_DONE:no-fvg-for-displacement=39, SWEEP_DONE:no-recent-displacement=805}
- LONDON: {MSS_CONFIRMED:awaiting-band-touch=250, OTE_ARMED:no-reaction-at-band=2, SWEEP_DONE:displacement-wrong-direction=309, SWEEP_DONE:fvg-link-pending=116, SWEEP_DONE:no-recent-displacement=819}
- PRE_NY: {SWEEP_DONE:displacement-wrong-direction=104, SWEEP_DONE:fvg-link-pending=47, SWEEP_DONE:no-recent-displacement=128}
- NY_AM: {MSS_CONFIRMED:awaiting-band-touch=78, SWEEP_DONE:displacement-wrong-direction=201, SWEEP_DONE:fvg-link-pending=82, SWEEP_DONE:no-fvg-for-displacement=12, SWEEP_DONE:no-recent-displacement=240}
- NY_LUNCH: {MSS_CONFIRMED:awaiting-band-touch=61, OTE_ARMED:no-pd-array-overlapping-band=5, SWEEP_DONE:displacement-wrong-direction=17, SWEEP_DONE:fvg-link-pending=85, SWEEP_DONE:no-recent-displacement=131}
- NY_PM: {MSS_CONFIRMED:awaiting-band-touch=67, SWEEP_DONE:displacement-wrong-direction=29, SWEEP_DONE:fvg-link-pending=116, SWEEP_DONE:no-fvg-for-displacement=35, SWEEP_DONE:no-recent-displacement=309}
- PRE_ASIA: {MSS_CONFIRMED:awaiting-band-touch=10, SWEEP_DONE:fvg-link-pending=10, SWEEP_DONE:no-recent-displacement=144}
- NO_ENTRY: {OTE_ARMED:no-entry-block-14:45-17:00CT=11, SWEEP_DONE:displacement-wrong-direction=10, SWEEP_DONE:fvg-link-pending=30, SWEEP_DONE:no-recent-displacement=67}

## Risk-engine denials: {R:R too low: # < #=1}

## Signal / order / trade log

- 2026-09-23 01:26 ET ASIA | SHORT_ENTRY e=31036.0 s=31044.25 t=31011.5 rr=2.97 q=6 | DENY R:R too low: 2.97 < 3.0
- 2026-09-28 15:26 ET NY_PM | SHORT_ENTRY e=30635.5 s=30674.0 t=30510.5 rr=3.25 q=18 | ALLOW qty=3 Approved: 3 contracts, $250.00 dynamic risk/trade (base $250.00), R:R 3.25:1, DLL room: $1000.00

## FunnelTelemetry (current session): [FUNNEL MNQ] BIAS_SET=0 MANIP_DONE=14 SWEEP_DONE=19 DISPLACED=7 MSS_CONFIRMED=7 OTE_ARMED=3 IN_TRADE=1 | invalidated: expired=15, HTF bias flip=3 | stalls: SWEEP_DONE:no-recent-displacement=371, MSS_CONFIRMED:awaiting-band-touch=193, SWEEP_DONE:displacement-wrong-direction=171, SWEEP_DONE:fvg-link-pending=107
