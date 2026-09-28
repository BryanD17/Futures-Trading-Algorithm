# FUNNEL AUTOPSY — C-B harness-equivalent (scalpMode.enabled=true scalp.minRaidScore=5 bias.hysteresis.enabled=true)

tape=C:\Users\Owner\wt-agent-05\trading-engine\src\test\resources\tape symbol=MNQ bars=7830 smtBars=7830 first=2026-09-21 00:00 ET last=2026-09-28 16:22 ET

riskLimits: DLL=1000.0 MLL=2000.0 maxContracts=20 maxTotal=20 riskPerTrade=150.0 riskEngineRR=[0.8,5.0] validatorRR=[0.8,5.0]

cold start: first non-NEUTRAL bias at bar 120 (2026-09-21 02:00 ET); first decisive 3-of-4 vote at bar 1730
episodes (BIAS_SET arrivals)=14 stateArrivals={BIAS_SET=14, DISPLACED=6, IDLE=6, INVALIDATED=56, IN_TRADE=1, MANIP_DONE=40, MSS_CONFIRMED=13, OTE_ARMED=1, SWEEP_DONE=44}

## Per-session counts

| session | bars | kzOpen | biasNonNeutral | signals | riskDenied | ordersSent | fills | closed | wins | losses |
|---|---|---|---|---|---|---|---|---|---|---|
| ASIA | 2179 | 2165 | 2051 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| LONDON | 2160 | 2146 | 2160 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| PRE_NY | 540 | 533 | 471 | 1 | 0 | 1 | 1 | 1 | 0 | 1 |
| NY_AM | 900 | 893 | 892 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| NY_LUNCH | 540 | 535 | 540 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| NY_PM | 810 | 805 | 802 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| PRE_ASIA | 290 | 285 | 290 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| NO_ENTRY | 410 | 90 | 410 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| WEEKEND | 1 | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |

## Gate HOLDING the machine, bars per session (each row sums to that session's bars)

| session | GATE-M3 | INVALIDATED-await-rearm | INVALIDATED-await-rearm-kzClosed | IN_TRADE | M2-bias-NEUTRAL | M4-no-sweep | M5-displacement-already-consumed | M5-displacement-wrong-direction | M5-no-fvg-for-displacement | M5-no-recent-displacement | M5-waiting | M6-no-MSS | M7-no-reaction-at-band | M7-ote-not-armed-after-reaction | MANIP-no-leg | total |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ASIA | 0 | 94 | 0 | 0 | 128 | 151 | 0 | 136 | 93 | 1019 | 0 | 100 | 250 | 13 | 195 | 2179 |
| LONDON | 0 | 93 | 0 | 0 | 0 | 69 | 0 | 116 | 239 | 1070 | 0 | 129 | 212 | 0 | 232 | 2160 |
| PRE_NY | 0 | 38 | 0 | 24 | 69 | 17 | 0 | 0 | 0 | 198 | 25 | 0 | 23 | 0 | 146 | 540 |
| NY_AM | 0 | 49 | 0 | 0 | 8 | 84 | 10 | 35 | 70 | 297 | 0 | 148 | 56 | 0 | 143 | 900 |
| NY_LUNCH | 0 | 38 | 0 | 0 | 0 | 68 | 0 | 45 | 35 | 183 | 0 | 82 | 89 | 0 | 0 | 540 |
| NY_PM | 0 | 29 | 0 | 0 | 8 | 58 | 0 | 110 | 50 | 412 | 0 | 0 | 60 | 0 | 83 | 810 |
| PRE_ASIA | 0 | 12 | 0 | 0 | 0 | 77 | 0 | 27 | 0 | 74 | 0 | 28 | 5 | 0 | 67 | 290 |
| NO_ENTRY | 41 | 2 | 93 | 0 | 0 | 14 | 0 | 40 | 29 | 100 | 0 | 0 | 76 | 0 | 15 | 410 |
| WEEKEND | 0 | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 1 |

## Top-3 holding gates per session

- ASIA (2179 bars): M5-no-recent-displacement 1019 (46.8%); M7-no-reaction-at-band 250 (11.5%); MANIP-no-leg 195 (8.9%)
- LONDON (2160 bars): M5-no-recent-displacement 1070 (49.5%); M5-no-fvg-for-displacement 239 (11.1%); MANIP-no-leg 232 (10.7%)
- PRE_NY (540 bars): M5-no-recent-displacement 198 (36.7%); MANIP-no-leg 146 (27.0%); M2-bias-NEUTRAL 69 (12.8%)
- NY_AM (900 bars): M5-no-recent-displacement 297 (33.0%); M6-no-MSS 148 (16.4%); MANIP-no-leg 143 (15.9%)
- NY_LUNCH (540 bars): M5-no-recent-displacement 183 (33.9%); M7-no-reaction-at-band 89 (16.5%); M6-no-MSS 82 (15.2%)
- NY_PM (810 bars): M5-no-recent-displacement 412 (50.9%); M5-displacement-wrong-direction 110 (13.6%); MANIP-no-leg 83 (10.2%)
- PRE_ASIA (290 bars): M4-no-sweep 77 (26.6%); M5-no-recent-displacement 74 (25.5%); MANIP-no-leg 67 (23.1%)
- NO_ENTRY (410 bars): M5-no-recent-displacement 100 (24.4%); INVALIDATED-await-rearm-kzClosed 93 (22.7%); M7-no-reaction-at-band 76 (18.5%)
- WEEKEND (1 bars): INVALIDATED-await-rearm-kzClosed 1 (100.0%)

## Setup deaths (INVALIDATED transitions) by reason per session

- ASIA: {HTF bias NEUTRAL beyond grace=1, HTF bias flip=7, expired=4, impulse origin violated before OTE entry=2}
- LONDON: {HTF bias flip=6, counter-bias MSS observed=1, expired=8}
- PRE_NY: {HTF bias NEUTRAL beyond grace=3, HTF bias flip=1, expired=1}
- NY_AM: {HTF bias NEUTRAL beyond grace=1, HTF bias flip=5, impulse origin violated before OTE entry=1}
- NY_LUNCH: {HTF bias flip=3, expired=3, impulse origin violated before OTE entry=1}
- NY_PM: {HTF bias NEUTRAL beyond grace=1, HTF bias flip=2}
- PRE_ASIA: {expired=1}
- NO_ENTRY: {HTF bias flip=1, OTE window expired (# bars)=1, expired=1}
- WEEKEND: {HTF bias NEUTRAL beyond grace=1}

## Deepest state reached at death per session

- ASIA: {BIAS_SET=1, MSS_CONFIRMED=8, SWEEP_DONE=5}
- LONDON: {BIAS_SET=2, MANIP_DONE=1, MSS_CONFIRMED=6, SWEEP_DONE=6}
- PRE_NY: {BIAS_SET=1, MSS_CONFIRMED=3, SWEEP_DONE=1}
- NY_AM: {BIAS_SET=2, IN_TRADE=1, MSS_CONFIRMED=1, SWEEP_DONE=3}
- NY_LUNCH: {DISPLACED=1, MSS_CONFIRMED=3, SWEEP_DONE=3}
- NY_PM: {DISPLACED=1, MSS_CONFIRMED=1, SWEEP_DONE=1}
- PRE_ASIA: {SWEEP_DONE=1}
- NO_ENTRY: {MSS_CONFIRMED=1, OTE_ARMED=1, SWEEP_DONE=1}
- WEEKEND: {SWEEP_DONE=1}

## Stall reasons (FunnelTelemetry) per session

- ASIA: {MSS_CONFIRMED:no-reaction-at-band=250, MSS_CONFIRMED:ote-not-armed-after-reaction=13, SWEEP_DONE:displacement-wrong-direction=136, SWEEP_DONE:no-fvg-for-displacement=93, SWEEP_DONE:no-recent-displacement=1019}
- LONDON: {MSS_CONFIRMED:no-reaction-at-band=212, SWEEP_DONE:displacement-wrong-direction=116, SWEEP_DONE:no-fvg-for-displacement=239, SWEEP_DONE:no-recent-displacement=1070}
- PRE_NY: {MSS_CONFIRMED:no-reaction-at-band=23, SWEEP_DONE:no-recent-displacement=198}
- NY_AM: {MSS_CONFIRMED:no-reaction-at-band=56, SWEEP_DONE:displacement-already-consumed=10, SWEEP_DONE:displacement-wrong-direction=35, SWEEP_DONE:no-fvg-for-displacement=70, SWEEP_DONE:no-recent-displacement=297}
- NY_LUNCH: {MSS_CONFIRMED:no-reaction-at-band=89, SWEEP_DONE:displacement-wrong-direction=45, SWEEP_DONE:no-fvg-for-displacement=35, SWEEP_DONE:no-recent-displacement=183}
- NY_PM: {MSS_CONFIRMED:no-reaction-at-band=60, SWEEP_DONE:displacement-wrong-direction=110, SWEEP_DONE:no-fvg-for-displacement=50, SWEEP_DONE:no-recent-displacement=412}
- PRE_ASIA: {MSS_CONFIRMED:no-reaction-at-band=5, SWEEP_DONE:displacement-wrong-direction=27, SWEEP_DONE:no-recent-displacement=74}
- NO_ENTRY: {MSS_CONFIRMED:no-reaction-at-band=76, SWEEP_DONE:displacement-wrong-direction=40, SWEEP_DONE:no-fvg-for-displacement=29, SWEEP_DONE:no-recent-displacement=100}

## Risk-engine denials: {}

## Signal / order / trade log

- 2026-09-24 08:45 ET PRE_NY | SHORT_ENTRY e=30482.5 s=30510.5 t=30454.5 rr=1.00 q=2 | ALLOW qty=2 Approved: 2 contracts (honoured requested 2 (risk-derived max 2)), $112.00 risk ($56.00/micro), budget $150.00 (base $150.00), R:R 1.00:1, DLL room: $1000.00
- 2026-09-24 09:02 ET PRE_NY | CLOSED SELL q=2 in=30482.5 out=30510.5 pnl=-112.00 R=0.00 Stop hit

## FunnelTelemetry (current session): [FUNNEL MNQ] BIAS_SET=1 MANIP_DONE=10 SWEEP_DONE=10 DISPLACED=2 MSS_CONFIRMED=3 OTE_ARMED=0 IN_TRADE=0 | invalidated: HTF bias flip=4, impulse origin violated before OTE entry=3, expired=3, HTF bias NEUTRAL beyond grace=2 | stalls: SWEEP_DONE:no-recent-displacement=852, SWEEP_DONE:no-fvg-for-displacement=93, SWEEP_DONE:displacement-wrong-direction=82, MSS_CONFIRMED:no-reaction-at-band=52
