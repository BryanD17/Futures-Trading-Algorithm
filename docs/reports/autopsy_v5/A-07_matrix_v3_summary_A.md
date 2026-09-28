# FUNNEL AUTOPSY — C-A bootRun-equivalent (no -D flags: legacy target model, NY killzones only)

tape=C:\Users\Owner\Futures-Trading-Algorithm\..\wt-verify-fix_agent-05.3-v5-rearm-after-close\trading-engine\src\test\resources\tape symbol=MNQ bars=7830 smtBars=7830 first=2026-09-21 00:00 ET last=2026-09-28 16:22 ET

riskLimits: DLL=1000.0 MLL=2000.0 maxContracts=5 maxTotal=10 riskPerTrade=250.0 riskEngineRR=[1.0,5.0] validatorRR=[1.0,5.0]

cold start: first non-NEUTRAL bias at bar 15 (2026-09-21 00:15 ET); first decisive 3-of-4 vote at bar 15
episodes (BIAS_SET arrivals)=0 stateArrivals={DISPLACED=17, INVALIDATED=69, IN_TRADE=7, MANIP_DONE=50, MSS_CONFIRMED=26, OTE_ARMED=17, SWEEP_DONE=73}

## Per-session counts

| session | bars | kzOpen | biasNonNeutral | signals | riskDenied | ordersSent | fills | closed | wins | losses |
|---|---|---|---|---|---|---|---|---|---|---|
| ASIA | 2179 | 2179 | 2164 | 3 | 0 | 3 | 3 | 3 | 2 | 1 |
| LONDON | 2160 | 2160 | 2160 | 1 | 0 | 1 | 1 | 1 | 0 | 1 |
| PRE_NY | 540 | 540 | 540 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| NY_AM | 900 | 900 | 900 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| NY_LUNCH | 540 | 540 | 540 | 1 | 0 | 1 | 1 | 1 | 1 | 0 |
| NY_PM | 810 | 810 | 810 | 2 | 0 | 2 | 1 | 1 | 1 | 0 |
| PRE_ASIA | 290 | 290 | 290 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| NO_ENTRY | 410 | 0 | 410 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| WEEKEND | 1 | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |

## Gate HOLDING the machine, bars per session (each row sums to that session's bars)

| session | GATE-M2b | GATE-M7 | INVALIDATED-await-rearm | INVALIDATED-await-rearm-kzClosed | IN_TRADE | M2-bias-NEUTRAL | M4-no-sweep | M5-displacement-wrong-direction | M5-fvg-link-pending | M5-no-fvg-for-displacement | M5-no-recent-displacement | M6-no-MSS | M7-awaiting-band-touch | OTE_ARMED-sizer-standdown-or-tier | total |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ASIA | 123 | 79 | 140 | 0 | 45 | 15 | 95 | 136 | 133 | 31 | 477 | 171 | 730 | 4 | 2179 |
| LONDON | 82 | 40 | 126 | 0 | 17 | 0 | 179 | 181 | 90 | 14 | 490 | 131 | 804 | 6 | 2160 |
| PRE_NY | 41 | 0 | 29 | 0 | 0 | 0 | 10 | 49 | 47 | 0 | 66 | 197 | 101 | 0 | 540 |
| NY_AM | 0 | 14 | 59 | 0 | 0 | 0 | 205 | 111 | 57 | 12 | 183 | 72 | 146 | 41 | 900 |
| NY_LUNCH | 0 | 0 | 13 | 0 | 42 | 0 | 7 | 12 | 60 | 0 | 93 | 20 | 285 | 8 | 540 |
| NY_PM | 0 | 0 | 45 | 0 | 115 | 0 | 64 | 18 | 26 | 0 | 224 | 25 | 293 | 0 | 810 |
| PRE_ASIA | 5 | 0 | 21 | 0 | 0 | 0 | 100 | 0 | 10 | 0 | 134 | 10 | 10 | 0 | 290 |
| NO_ENTRY | 0 | 0 | 0 | 103 | 0 | 0 | 1 | 10 | 59 | 0 | 76 | 41 | 79 | 41 | 410 |
| WEEKEND | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 1 | 0 | 1 |

## Top-3 holding gates per session

- ASIA (2179 bars): M7-awaiting-band-touch 730 (33.5%); M5-no-recent-displacement 477 (21.9%); M6-no-MSS 171 (7.8%)
- LONDON (2160 bars): M7-awaiting-band-touch 804 (37.2%); M5-no-recent-displacement 490 (22.7%); M5-displacement-wrong-direction 181 (8.4%)
- PRE_NY (540 bars): M6-no-MSS 197 (36.5%); M7-awaiting-band-touch 101 (18.7%); M5-no-recent-displacement 66 (12.2%)
- NY_AM (900 bars): M4-no-sweep 205 (22.8%); M5-no-recent-displacement 183 (20.3%); M7-awaiting-band-touch 146 (16.2%)
- NY_LUNCH (540 bars): M7-awaiting-band-touch 285 (52.8%); M5-no-recent-displacement 93 (17.2%); M5-fvg-link-pending 60 (11.1%)
- NY_PM (810 bars): M7-awaiting-band-touch 293 (36.2%); M5-no-recent-displacement 224 (27.7%); IN_TRADE 115 (14.2%)
- PRE_ASIA (290 bars): M5-no-recent-displacement 134 (46.2%); M4-no-sweep 100 (34.5%); INVALIDATED-await-rearm 21 (7.2%)
- NO_ENTRY (410 bars): INVALIDATED-await-rearm-kzClosed 103 (25.1%); M7-awaiting-band-touch 79 (19.3%); M5-no-recent-displacement 76 (18.5%)
- WEEKEND (1 bars): M7-awaiting-band-touch 1 (100.0%)

## Setup deaths (INVALIDATED transitions) by reason per session

- ASIA: {HTF bias flip=8, MSS stale before price reached the OTE band (# detector bars)=2, OTE window expired (# bars)=4, expired: DISPLACED→MSS # > # min=1, expired: SWEEP_DONE→DISPLACEMENT # > # min=6}
- LONDON: {HTF bias flip=4, MSS stale before price reached the OTE band (# detector bars)=5, OTE window expired (# bars)=3, expired: DISPLACED→MSS # > # min=1, expired: SWEEP_DONE→DISPLACEMENT # > # min=6}
- PRE_NY: {OTE window expired (# bars)=1, expired: DISPLACED→MSS # > # min=2, expired: SWEEP_DONE→DISPLACEMENT # > # min=2}
- NY_AM: {HTF bias flip=2, OTE window expired (# bars)=1, expired: DISPLACED→MSS # > # min=2, expired: SWEEP_DONE→DISPLACEMENT # > # min=4}
- NY_LUNCH: {MSS stale before price reached the OTE band (# detector bars)=1, expired: SWEEP_DONE→DISPLACEMENT # > # min=1}
- NY_PM: {MSS stale before price reached the OTE band (# detector bars)=3, expired=1, expired: SWEEP_DONE→DISPLACEMENT # > # min=3}
- PRE_ASIA: {HTF bias flip=2, OTE window expired (# bars)=1}
- NO_ENTRY: {expired: DISPLACED→MSS # > # min=1, expired: SWEEP_DONE→DISPLACEMENT # > # min=2}

## Deepest state reached at death per session

- ASIA: {IN_TRADE=12, MSS_CONFIRMED=2, OTE_ARMED=5, SWEEP_DONE=2}
- LONDON: {IN_TRADE=14, MSS_CONFIRMED=3, OTE_ARMED=2}
- PRE_NY: {IN_TRADE=4, MSS_CONFIRMED=1}
- NY_AM: {IN_TRADE=6, MSS_CONFIRMED=1, OTE_ARMED=2}
- NY_LUNCH: {IN_TRADE=2}
- NY_PM: {IN_TRADE=5, MSS_CONFIRMED=1, OTE_ARMED=1}
- PRE_ASIA: {IN_TRADE=3}
- NO_ENTRY: {IN_TRADE=1, MSS_CONFIRMED=1, OTE_ARMED=1}

## Stall reasons (FunnelTelemetry) per session

- ASIA: {MSS_CONFIRMED:awaiting-band-touch=730, OTE_ARMED:impulse-awaiting-rejection=1, OTE_ARMED:no-reaction-at-band=3, SWEEP_DONE:displacement-wrong-direction=136, SWEEP_DONE:fvg-link-pending=133, SWEEP_DONE:no-fvg-for-displacement=31, SWEEP_DONE:no-recent-displacement=477}
- LONDON: {MSS_CONFIRMED:awaiting-band-touch=804, OTE_ARMED:impulse-awaiting-rejection=1, OTE_ARMED:impulse-no-pd-array-at-sweep=4, OTE_ARMED:no-reaction-at-band=1, SWEEP_DONE:displacement-wrong-direction=181, SWEEP_DONE:fvg-link-pending=90, SWEEP_DONE:no-fvg-for-displacement=14, SWEEP_DONE:no-recent-displacement=490}
- PRE_NY: {MSS_CONFIRMED:awaiting-band-touch=101, SWEEP_DONE:displacement-wrong-direction=49, SWEEP_DONE:fvg-link-pending=47, SWEEP_DONE:no-recent-displacement=66}
- NY_AM: {MSS_CONFIRMED:awaiting-band-touch=146, OTE_ARMED:impulse-no-pd-array-at-sweep=41, SWEEP_DONE:displacement-wrong-direction=111, SWEEP_DONE:fvg-link-pending=57, SWEEP_DONE:no-fvg-for-displacement=12, SWEEP_DONE:no-recent-displacement=183}
- NY_LUNCH: {MSS_CONFIRMED:awaiting-band-touch=285, OTE_ARMED:impulse-awaiting-rejection=8, SWEEP_DONE:displacement-wrong-direction=12, SWEEP_DONE:fvg-link-pending=60, SWEEP_DONE:no-recent-displacement=93}
- NY_PM: {MSS_CONFIRMED:awaiting-band-touch=293, SWEEP_DONE:displacement-wrong-direction=18, SWEEP_DONE:fvg-link-pending=26, SWEEP_DONE:no-recent-displacement=224}
- PRE_ASIA: {MSS_CONFIRMED:awaiting-band-touch=10, SWEEP_DONE:fvg-link-pending=10, SWEEP_DONE:no-recent-displacement=134}
- NO_ENTRY: {MSS_CONFIRMED:awaiting-band-touch=79, OTE_ARMED:no-entry-block-14:45-17:00CT=41, SWEEP_DONE:displacement-wrong-direction=10, SWEEP_DONE:fvg-link-pending=59, SWEEP_DONE:no-recent-displacement=76}
- WEEKEND: {MSS_CONFIRMED:awaiting-band-touch=1}

## Risk-engine denials: {}

## Signal / order / trade log

- 2026-09-23 00:09 ET ASIA | SHORT_ENTRY e=31036.25 s=31043.0 t=31011.5 rr=3.67 q=5 | ALLOW qty=5 Approved: 5 contracts (honoured requested 5 (risk-derived max 5)), $67.50 risk ($13.50/micro), budget $250.00 (base $250.00), R:R 3.67:1, DLL room: $1000.00
- 2026-09-23 00:21 ET ASIA | CLOSED SELL q=5 in=31036.25 out=31043.00 pnl=-67.50 R=-1.00 Stop hit
- 2026-09-23 01:26 ET ASIA | SHORT_ENTRY e=31036.0 s=31044.25 t=31011.5 rr=2.97 q=5 | ALLOW qty=5 Approved: 5 contracts (honoured requested 5 (risk-derived max 5)), $82.50 risk ($16.50/micro), budget $250.00 (base $250.00), R:R 2.97:1, DLL room: $932.50
- 2026-09-23 01:34 ET ASIA | CLOSED SELL q=5 in=31036.0 out=31029.35 pnl=66.50 R=0.81 2 exits: 3@31027.75 Partial profit at 1.0R; 2@31031.75 Breakeven stop hit
- 2026-09-23 01:42 ET ASIA | SHORT_ENTRY e=31036.0 s=31043.0 t=31011.5 rr=3.50 q=5 | ALLOW qty=5 Approved: 5 contracts (honoured requested 5 (risk-derived max 5)), $70.00 risk ($14.00/micro), budget $250.00 (base $250.00), R:R 3.50:1, DLL room: $999.00
- 2026-09-23 01:49 ET ASIA | CLOSED SELL q=5 in=31036.0 out=31030.90 pnl=51.00 R=0.73 2 exits: 3@31029.00 Partial profit at 1.0R; 2@31033.75 Breakeven stop hit
- 2026-09-23 06:51 ET LONDON | LONG_ENTRY e=31017.0 s=31005.75 t=31052.0 rr=3.11 q=5 | ALLOW qty=5 Approved: 5 contracts (honoured requested 5 (risk-derived max 5)), $112.50 risk ($22.50/micro), budget $250.00 (base $250.00), R:R 3.11:1, DLL room: $1050.00
- 2026-09-23 07:02 ET LONDON | CLOSED BUY q=5 in=31017.0 out=31005.75 pnl=-112.50 R=-1.00 Stop hit
- 2026-09-24 14:03 ET NY_PM | LONG_ENTRY e=30607.0 s=30567.75 t=30703.25 rr=2.45 q=3 | ALLOW qty=3 Approved: 3 contracts (honoured requested 3 (risk-derived max 3)), $235.50 risk ($78.50/micro), budget $250.00 (base $250.00), R:R 2.45:1, DLL room: $937.50
- 2026-09-24 15:07 ET NY_PM | ORDER ORDER: cancelled — setup expired (200 bars without progress)
- 2026-09-28 12:32 ET NY_LUNCH | SHORT_ENTRY e=30645.0 s=30723.0 t=30356.75 rr=3.70 q=1 | ALLOW qty=1 Approved: 1 contracts (honoured requested 1 (risk-derived max 1)), $156.00 risk ($156.00/micro), budget $250.00 (base $250.00), R:R 3.70:1, DLL room: $937.50
- 2026-09-28 13:08 ET NY_LUNCH | CLOSED SELL q=1 in=30645.0 out=30567.00 pnl=156.00 R=1.00 Partial profit at 1.0R
- 2026-09-28 14:53 ET NY_PM | SHORT_ENTRY e=30634.75 s=30674.0 t=30510.5 rr=3.17 q=3 | ALLOW qty=3 Approved: 3 contracts (honoured requested 3 (risk-derived max 3)), $235.50 risk ($78.50/micro), budget $250.00 (base $250.00), R:R 3.17:1, DLL room: $1093.50
- 2026-09-28 15:38 ET NY_PM | CLOSED SELL q=3 in=30634.75 out=30582.42 pnl=314.00 R=1.33 2 exits: 2@30595.50 Partial profit at 1.0R; 1@30556.25 Partial profit at 2.0R

## FunnelTelemetry (current session): [FUNNEL MNQ] BIAS_SET=0 MANIP_DONE=7 SWEEP_DONE=14 DISPLACED=2 MSS_CONFIRMED=6 OTE_ARMED=5 IN_TRADE=2 | invalidated: expired: SWEEP_DONE→DISPLACEMENT=5, HTF bias flip=3, MSS stale before price reached the OTE band (30 detector bars)=2, OTE window expired (40 bars)=2 | stalls: MSS_CONFIRMED:awaiting-band-touch=423, SWEEP_DONE:no-recent-displacement=294, SWEEP_DONE:displacement-wrong-direction=117, SWEEP_DONE:fvg-link-pending=72
