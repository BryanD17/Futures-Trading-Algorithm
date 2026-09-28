# FUNNEL AUTOPSY — C-A bootRun-equivalent (no -D flags: legacy target model, NY killzones only)

tape=<repo>\trading-engine\src\test\resources\tape symbol=MNQ bars=7830 smtBars=7830 first=2026-09-21 00:00 ET last=2026-09-28 16:22 ET

riskLimits: DLL=1000.0 MLL=2000.0 maxContracts=5 maxTotal=10 riskPerTrade=250.0 riskEngineRR=[3.0,6.0] validatorRR=[2.0,Infinity]

cold start: first non-NEUTRAL bias at bar 120 (2026-09-21 02:00 ET); first decisive 3-of-4 vote at bar 1730
episodes (BIAS_SET arrivals)=17 stateArrivals={BIAS_SET=17, DISPLACED=12, IDLE=15, INVALIDATED=103, IN_TRADE=1, MANIP_DONE=74, MSS_CONFIRMED=8, SWEEP_DONE=88}

## Per-session counts

| session | bars | kzOpen | biasNonNeutral | signals | riskDenied | ordersSent | fills | closed | wins | losses |
|---|---|---|---|---|---|---|---|---|---|---|
| ASIA | 2179 | 2179 | 2015 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| LONDON | 2160 | 2160 | 2098 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| PRE_NY | 540 | 540 | 412 | 1 | 0 | 1 | 1 | 1 | 0 | 1 |
| NY_AM | 900 | 900 | 816 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| NY_LUNCH | 540 | 540 | 540 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| NY_PM | 810 | 810 | 749 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| PRE_ASIA | 290 | 290 | 290 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| NO_ENTRY | 410 | 0 | 368 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| WEEKEND | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |

## Gate HOLDING the machine, bars per session (each row sums to that session's bars)

| session | INVALIDATED-await-rearm | INVALIDATED-await-rearm-kzClosed | IN_TRADE | M2-bias-NEUTRAL | M4-no-sweep | M5-displacement-already-consumed | M5-displacement-wrong-direction | M5-no-fvg-for-displacement | M5-no-recent-displacement | M6-no-MSS | M7-no-reaction-at-band | M7-ote-not-armed-after-reaction | MANIP-no-leg | total |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ASIA | 204 | 0 | 0 | 164 | 223 | 0 | 150 | 96 | 1056 | 134 | 79 | 3 | 70 | 2179 |
| LONDON | 167 | 0 | 0 | 62 | 183 | 12 | 104 | 105 | 986 | 99 | 35 | 0 | 407 | 2160 |
| PRE_NY | 38 | 0 | 45 | 113 | 20 | 0 | 24 | 15 | 257 | 0 | 26 | 0 | 2 | 540 |
| NY_AM | 71 | 0 | 103 | 65 | 159 | 10 | 30 | 62 | 275 | 119 | 4 | 0 | 2 | 900 |
| NY_LUNCH | 58 | 0 | 0 | 0 | 79 | 0 | 45 | 43 | 305 | 9 | 1 | 0 | 0 | 540 |
| NY_PM | 74 | 0 | 0 | 55 | 106 | 0 | 59 | 52 | 419 | 42 | 0 | 0 | 3 | 810 |
| PRE_ASIA | 0 | 0 | 0 | 0 | 95 | 0 | 30 | 0 | 62 | 28 | 5 | 0 | 70 | 290 |
| NO_ENTRY | 0 | 174 | 0 | 0 | 0 | 0 | 60 | 70 | 71 | 35 | 0 | 0 | 0 | 410 |
| WEEKEND | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 1 |

## Top-3 holding gates per session

- ASIA (2179 bars): M5-no-recent-displacement 1056 (48.5%); M4-no-sweep 223 (10.2%); INVALIDATED-await-rearm 204 (9.4%)
- LONDON (2160 bars): M5-no-recent-displacement 986 (45.6%); MANIP-no-leg 407 (18.8%); M4-no-sweep 183 (8.5%)
- PRE_NY (540 bars): M5-no-recent-displacement 257 (47.6%); M2-bias-NEUTRAL 113 (20.9%); IN_TRADE 45 (8.3%)
- NY_AM (900 bars): M5-no-recent-displacement 275 (30.6%); M4-no-sweep 159 (17.7%); M6-no-MSS 119 (13.2%)
- NY_LUNCH (540 bars): M5-no-recent-displacement 305 (56.5%); M4-no-sweep 79 (14.6%); INVALIDATED-await-rearm 58 (10.7%)
- NY_PM (810 bars): M5-no-recent-displacement 419 (51.7%); M4-no-sweep 106 (13.1%); INVALIDATED-await-rearm 74 (9.1%)
- PRE_ASIA (290 bars): M4-no-sweep 95 (32.8%); MANIP-no-leg 70 (24.1%); M5-no-recent-displacement 62 (21.4%)
- NO_ENTRY (410 bars): INVALIDATED-await-rearm-kzClosed 174 (42.4%); M5-no-recent-displacement 71 (17.3%); M5-no-fvg-for-displacement 70 (17.1%)
- WEEKEND (1 bars): INVALIDATED-await-rearm-kzClosed 1 (100.0%)

## Setup deaths (INVALIDATED transitions) by reason per session

- ASIA: {HTF bias became NEUTRAL=3, HTF bias flip=5, expired=24, impulse origin violated before OTE entry=1}
- LONDON: {HTF bias became NEUTRAL=4, HTF bias flip=3, counter-bias MSS observed=1, expired=18}
- PRE_NY: {HTF bias became NEUTRAL=1, HTF bias flip=1, expired=4}
- NY_AM: {HTF bias became NEUTRAL=3, HTF bias flip=1, expired=6, impulse origin violated before OTE entry=1}
- NY_LUNCH: {HTF bias flip=3, expired=5, impulse origin violated before OTE entry=1}
- NY_PM: {HTF bias became NEUTRAL=2, expired=10}
- NO_ENTRY: {HTF bias became NEUTRAL=2, HTF bias flip=1, counter-bias MSS observed=1, expired=2}

## Deepest state reached at death per session

- ASIA: {BIAS_SET=2, DISPLACED=7, MSS_CONFIRMED=4, SWEEP_DONE=20}
- LONDON: {BIAS_SET=2, DISPLACED=6, MSS_CONFIRMED=6, SWEEP_DONE=12}
- PRE_NY: {MSS_CONFIRMED=2, SWEEP_DONE=4}
- NY_AM: {DISPLACED=2, IN_TRADE=1, MSS_CONFIRMED=4, SWEEP_DONE=4}
- NY_LUNCH: {DISPLACED=1, IN_TRADE=1, MSS_CONFIRMED=5, SWEEP_DONE=2}
- NY_PM: {DISPLACED=1, IN_TRADE=2, MSS_CONFIRMED=6, SWEEP_DONE=3}
- NO_ENTRY: {DISPLACED=1, IN_TRADE=1, MSS_CONFIRMED=3, SWEEP_DONE=1}

## Stall reasons (FunnelTelemetry) per session

- ASIA: {MSS_CONFIRMED:no-reaction-at-band=79, MSS_CONFIRMED:ote-not-armed-after-reaction=3, SWEEP_DONE:displacement-wrong-direction=150, SWEEP_DONE:no-fvg-for-displacement=96, SWEEP_DONE:no-recent-displacement=1056}
- LONDON: {MSS_CONFIRMED:no-reaction-at-band=35, SWEEP_DONE:displacement-already-consumed=12, SWEEP_DONE:displacement-wrong-direction=104, SWEEP_DONE:no-fvg-for-displacement=105, SWEEP_DONE:no-recent-displacement=986}
- PRE_NY: {MSS_CONFIRMED:no-reaction-at-band=26, SWEEP_DONE:displacement-wrong-direction=24, SWEEP_DONE:no-fvg-for-displacement=15, SWEEP_DONE:no-recent-displacement=257}
- NY_AM: {MSS_CONFIRMED:no-reaction-at-band=4, SWEEP_DONE:displacement-already-consumed=10, SWEEP_DONE:displacement-wrong-direction=30, SWEEP_DONE:no-fvg-for-displacement=62, SWEEP_DONE:no-recent-displacement=275}
- NY_LUNCH: {MSS_CONFIRMED:no-reaction-at-band=1, SWEEP_DONE:displacement-wrong-direction=45, SWEEP_DONE:no-fvg-for-displacement=43, SWEEP_DONE:no-recent-displacement=305}
- NY_PM: {SWEEP_DONE:displacement-wrong-direction=59, SWEEP_DONE:no-fvg-for-displacement=52, SWEEP_DONE:no-recent-displacement=419}
- PRE_ASIA: {MSS_CONFIRMED:no-reaction-at-band=5, SWEEP_DONE:displacement-wrong-direction=30, SWEEP_DONE:no-recent-displacement=62}
- NO_ENTRY: {SWEEP_DONE:displacement-wrong-direction=60, SWEEP_DONE:no-fvg-for-displacement=70, SWEEP_DONE:no-recent-displacement=71}

## Risk-engine denials: {}

## Signal / order / trade log

- 2026-09-24 08:45 ET PRE_NY | SHORT_ENTRY e=30482.5 s=30510.5 t=30393.0 rr=3.20 q=6 | ALLOW qty=4 Approved: 4 contracts, $250.00 dynamic risk/trade (base $250.00), R:R 3.20:1, DLL room: $1000.00
- 2026-09-24 09:02 ET PRE_NY | CLOSED SELL q=4 in=30482.5 out=30510.5 pnl=-56.00 R=0.00 Stop hit

## FunnelTelemetry (current session): [FUNNEL MNQ] BIAS_SET=4 MANIP_DONE=15 SWEEP_DONE=17 DISPLACED=2 MSS_CONFIRMED=3 OTE_ARMED=0 IN_TRADE=0 | invalidated: expired=13, HTF bias flip=3, HTF bias became NEUTRAL=3, impulse origin violated before OTE entry=2 | stalls: SWEEP_DONE:no-recent-displacement=730, SWEEP_DONE:no-fvg-for-displacement=93, SWEEP_DONE:displacement-wrong-direction=45, MSS_CONFIRMED:no-reaction-at-band=33
