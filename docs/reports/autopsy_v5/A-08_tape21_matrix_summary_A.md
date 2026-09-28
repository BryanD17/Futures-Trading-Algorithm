# FUNNEL AUTOPSY — C-A bootRun-equivalent (no -D flags: legacy target model, NY killzones only)

tape=C:\Users\Owner\topstep-trading\tape\tape21 symbol=MNQ bars=20094 smtBars=19207 first=2026-09-07 19:48 ET last=2026-09-28 19:47 ET

riskLimits: DLL=1000.0 MLL=2000.0 maxContracts=5 maxTotal=10 riskPerTrade=250.0 riskEngineRR=[1.0,5.0] validatorRR=[1.0,5.0]

cold start: first non-NEUTRAL bias at bar 22 (2026-09-07 20:15 ET); first decisive 3-of-4 vote at bar 22
episodes (BIAS_SET arrivals)=2 stateArrivals={BIAS_SET=2, DISPLACED=50, INVALIDATED=191, IN_TRADE=20, MANIP_DONE=148, MSS_CONFIRMED=56, OTE_ARMED=43, SWEEP_DONE=200}

## Per-session counts

| session | bars | kzOpen | biasNonNeutral | signals | riskDenied | ordersSent | fills | closed | wins | losses |
|---|---|---|---|---|---|---|---|---|---|---|
| ASIA | 6038 | 6038 | 6016 | 7 | 0 | 7 | 6 | 6 | 4 | 2 |
| LONDON | 5222 | 5222 | 5222 | 3 | 0 | 3 | 2 | 2 | 1 | 1 |
| PRE_NY | 1340 | 1340 | 1340 | 1 | 0 | 1 | 1 | 1 | 0 | 1 |
| NY_AM | 2250 | 2250 | 2250 | 2 | 0 | 2 | 2 | 2 | 1 | 1 |
| NY_LUNCH | 1346 | 1346 | 1346 | 3 | 0 | 3 | 3 | 2 | 2 | 0 |
| NY_PM | 1975 | 1975 | 1975 | 3 | 0 | 3 | 3 | 3 | 2 | 1 |
| PRE_ASIA | 849 | 849 | 849 | 1 | 0 | 1 | 1 | 1 | 0 | 1 |
| NO_ENTRY | 1074 | 0 | 1074 | 0 | 0 | 0 | 0 | 1 | 0 | 1 |
| WEEKEND | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |

## Gate HOLDING the machine, bars per session (each row sums to that session's bars)

| session | GATE-ALARM | GATE-M2b | GATE-M7 | GATE-NO_ENTRY | INVALIDATED-await-rearm | INVALIDATED-await-rearm-kzClosed | IN_TRADE | M2-bias-NEUTRAL | M4-no-sweep | M5-displacement-wrong-direction | M5-fvg-link-pending | M5-no-fvg-for-displacement | M5-no-recent-displacement | M6-no-MSS | M7-awaiting-band-touch | MANIP-no-leg | total |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ASIA | 9 | 96 | 0 | 0 | 368 | 0 | 74 | 22 | 619 | 428 | 335 | 37 | 1654 | 855 | 1539 | 2 | 6038 |
| LONDON | 9 | 213 | 71 | 0 | 301 | 0 | 30 | 0 | 453 | 472 | 234 | 10 | 1364 | 593 | 1472 | 0 | 5222 |
| PRE_NY | 32 | 134 | 0 | 0 | 55 | 0 | 7 | 0 | 59 | 114 | 116 | 10 | 234 | 197 | 379 | 3 | 1340 |
| NY_AM | 43 | 79 | 60 | 0 | 190 | 0 | 54 | 0 | 370 | 177 | 135 | 35 | 680 | 112 | 315 | 0 | 2250 |
| NY_LUNCH | 39 | 10 | 0 | 0 | 76 | 0 | 88 | 0 | 152 | 53 | 101 | 0 | 396 | 167 | 264 | 0 | 1346 |
| NY_PM | 17 | 92 | 0 | 0 | 114 | 0 | 158 | 0 | 135 | 113 | 129 | 1 | 710 | 182 | 324 | 0 | 1975 |
| PRE_ASIA | 0 | 5 | 4 | 0 | 80 | 0 | 7 | 0 | 294 | 70 | 9 | 0 | 271 | 11 | 98 | 0 | 849 |
| NO_ENTRY | 0 | 0 | 0 | 185 | 0 | 335 | 62 | 0 | 16 | 74 | 57 | 0 | 35 | 46 | 264 | 0 | 1074 |
| WEEKEND | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |

## Top-3 holding gates per session

- ASIA (6038 bars): M5-no-recent-displacement 1654 (27.4%); M7-awaiting-band-touch 1539 (25.5%); M6-no-MSS 855 (14.2%)
- LONDON (5222 bars): M7-awaiting-band-touch 1472 (28.2%); M5-no-recent-displacement 1364 (26.1%); M6-no-MSS 593 (11.4%)
- PRE_NY (1340 bars): M7-awaiting-band-touch 379 (28.3%); M5-no-recent-displacement 234 (17.5%); M6-no-MSS 197 (14.7%)
- NY_AM (2250 bars): M5-no-recent-displacement 680 (30.2%); M4-no-sweep 370 (16.4%); M7-awaiting-band-touch 315 (14.0%)
- NY_LUNCH (1346 bars): M5-no-recent-displacement 396 (29.4%); M7-awaiting-band-touch 264 (19.6%); M6-no-MSS 167 (12.4%)
- NY_PM (1975 bars): M5-no-recent-displacement 710 (35.9%); M7-awaiting-band-touch 324 (16.4%); M6-no-MSS 182 (9.2%)
- PRE_ASIA (849 bars): M4-no-sweep 294 (34.6%); M5-no-recent-displacement 271 (31.9%); M7-awaiting-band-touch 98 (11.5%)
- NO_ENTRY (1074 bars): INVALIDATED-await-rearm-kzClosed 335 (31.2%); M7-awaiting-band-touch 264 (24.6%); GATE-NO_ENTRY 185 (17.2%)

## Setup deaths (INVALIDATED transitions) by reason per session

- ASIA: {HTF bias flip=18, MSS stale before price reached the OTE band (# detector bars)=7, OTE invalidated: close # beyond range extreme #=1, OTE window expired (# bars)=2, expired=1, expired: DISPLACED→MSS # > # min=7, expired: SWEEP_DONE→DISPLACEMENT # > # min=21}
- LONDON: {HTF bias flip=5, MSS stale before price reached the OTE band (# detector bars)=9, OTE window expired (# bars)=7, expired=1, expired: DISPLACED→MSS # > # min=7, expired: SWEEP_DONE→DISPLACEMENT # > # min=18}
- PRE_NY: {OTE window expired (# bars)=2, expired: DISPLACED→MSS # > # min=2, expired: SWEEP_DONE→DISPLACEMENT # > # min=5}
- NY_AM: {HTF bias flip=11, OTE invalidated: close # beyond range extreme #=1, OTE window expired (# bars)=4, expired: DISPLACED→MSS # > # min=2, expired: SWEEP_DONE→DISPLACEMENT # > # min=9}
- NY_LUNCH: {HTF bias flip=3, MSS stale before price reached the OTE band (# detector bars)=1, expired: DISPLACED→MSS # > # min=2, expired: SWEEP_DONE→DISPLACEMENT # > # min=6}
- NY_PM: {HTF bias flip=2, MSS stale before price reached the OTE band (# detector bars)=2, OTE window expired (# bars)=2, expired: DISPLACED→MSS # > # min=2, expired: SWEEP_DONE→DISPLACEMENT # > # min=10}
- PRE_ASIA: {HTF bias flip=11}
- NO_ENTRY: {MSS stale before price reached the OTE band (# detector bars)=1, OTE window expired (# bars)=5, expired: DISPLACED→MSS # > # min=1, expired: SWEEP_DONE→DISPLACEMENT # > # min=3}

## Deepest state reached at death per session

- ASIA: {IN_TRADE=52, MSS_CONFIRMED=2, OTE_ARMED=2, SWEEP_DONE=1}
- LONDON: {IN_TRADE=46, OTE_ARMED=1}
- PRE_NY: {IN_TRADE=8, SWEEP_DONE=1}
- NY_AM: {IN_TRADE=24, OTE_ARMED=1, SWEEP_DONE=2}
- NY_LUNCH: {DISPLACED=1, IN_TRADE=11}
- NY_PM: {DISPLACED=1, IN_TRADE=17}
- PRE_ASIA: {IN_TRADE=11}
- NO_ENTRY: {IN_TRADE=9, OTE_ARMED=1}

## Stall reasons (FunnelTelemetry) per session

- ASIA: {MSS_CONFIRMED:awaiting-band-touch=1539, OTE_ARMED:impulse-awaiting-rejection=1, OTE_ARMED:no-pd-array-overlapping-band=6, OTE_ARMED:no-reaction-at-band=2, SWEEP_DONE:displacement-wrong-direction=428, SWEEP_DONE:fvg-link-pending=335, SWEEP_DONE:no-fvg-for-displacement=37, SWEEP_DONE:no-recent-displacement=1654}
- LONDON: {MSS_CONFIRMED:awaiting-band-touch=1472, OTE_ARMED:no-reaction-at-band=9, SWEEP_DONE:displacement-wrong-direction=472, SWEEP_DONE:fvg-link-pending=234, SWEEP_DONE:no-fvg-for-displacement=10, SWEEP_DONE:no-recent-displacement=1364}
- PRE_NY: {MSS_CONFIRMED:awaiting-band-touch=379, OTE_ARMED:no-pd-array-overlapping-band=29, OTE_ARMED:no-reaction-at-band=3, SWEEP_DONE:displacement-wrong-direction=114, SWEEP_DONE:fvg-link-pending=116, SWEEP_DONE:no-fvg-for-displacement=10, SWEEP_DONE:no-recent-displacement=234}
- NY_AM: {MSS_CONFIRMED:awaiting-band-touch=315, OTE_ARMED:impulse-awaiting-rejection=30, OTE_ARMED:no-pd-array-overlapping-band=5, OTE_ARMED:no-reaction-at-band=8, SWEEP_DONE:displacement-wrong-direction=177, SWEEP_DONE:fvg-link-pending=135, SWEEP_DONE:no-fvg-for-displacement=35, SWEEP_DONE:no-recent-displacement=680}
- NY_LUNCH: {MSS_CONFIRMED:awaiting-band-touch=264, OTE_ARMED:impulse-awaiting-rejection=38, OTE_ARMED:no-reaction-at-band=1, SWEEP_DONE:displacement-wrong-direction=53, SWEEP_DONE:fvg-link-pending=101, SWEEP_DONE:no-recent-displacement=396}
- NY_PM: {MSS_CONFIRMED:awaiting-band-touch=324, OTE_ARMED:impulse-awaiting-rejection=17, SWEEP_DONE:displacement-wrong-direction=113, SWEEP_DONE:fvg-link-pending=129, SWEEP_DONE:no-fvg-for-displacement=1, SWEEP_DONE:no-recent-displacement=710}
- PRE_ASIA: {MSS_CONFIRMED:awaiting-band-touch=98, SWEEP_DONE:displacement-wrong-direction=70, SWEEP_DONE:fvg-link-pending=9, SWEEP_DONE:no-recent-displacement=271}
- NO_ENTRY: {MSS_CONFIRMED:awaiting-band-touch=264, OTE_ARMED:no-entry-block-14:45-17:00CT=185, SWEEP_DONE:displacement-wrong-direction=74, SWEEP_DONE:fvg-link-pending=57, SWEEP_DONE:no-recent-displacement=35}

## Risk-engine denials: {}

## Signal / order / trade log

- 2026-09-08 00:55 ET ASIA | ALARM ALARM: no-pd-array-overlapping-band (band [30033.5,30039.25])
- 2026-09-08 09:00 ET PRE_NY | ALARM ALARM: no-pd-array-overlapping-band (band [29951.25,29999.0])
- 2026-09-08 09:35 ET NY_AM | ALARM ALARM: no-reaction-at-band (band [29951.25,29999.0])
- 2026-09-08 11:16 ET NY_AM | ALARM ALARM: impulse-awaiting-rejection (need a close back beyond swept 29900.0 in the trade direction, band [29883.5,29927.75])
- 2026-09-08 11:21 ET NY_AM | SHORT_ENTRY e=29908.0 s=29934.75 t=29821.5 rr=3.23 q=4 | ALLOW qty=4 Approved: 4 contracts (honoured requested 4 (risk-derived max 4)), $214.00 risk ($53.50/micro), budget $250.00 (base $250.00), R:R 3.23:1, DLL room: $1000.00
- 2026-09-08 11:42 ET NY_AM | CLOSED SELL q=4 in=29908.0 out=29934.75 pnl=-214.00 R=-1.00 Stop hit
- 2026-09-08 13:01 ET NY_LUNCH | ALARM ALARM: impulse-awaiting-rejection (need a close back beyond swept 29900.0 in the trade direction, band [29883.5,29927.75])
- 2026-09-08 13:44 ET NY_PM | SHORT_ENTRY e=29904.0 s=29939.0 t=29821.5 rr=2.36 q=3 | ALLOW qty=3 Approved: 3 contracts (honoured requested 3 (risk-derived max 3)), $210.00 risk ($70.00/micro), budget $250.00 (base $250.00), R:R 2.36:1, DLL room: $786.00
- 2026-09-08 14:48 ET NY_PM | CLOSED SELL q=3 in=29904.0 out=29857.33 pnl=280.00 R=1.33 2 exits: 2@29869.00 Partial profit at 1.0R; 1@29834.00 Partial profit at 2.0R
- 2026-09-09 02:55 ET LONDON | ALARM ALARM: no-reaction-at-band (band [29865.75,29879.5])
- 2026-09-09 03:01 ET LONDON | LONG_ENTRY e=29872.0 s=29864.75 t=29898.75 rr=3.69 q=5 | ALLOW qty=5 Approved: 5 contracts (honoured requested 5 (risk-derived max 5)), $72.50 risk ($14.50/micro), budget $250.00 (base $250.00), R:R 3.69:1, DLL room: $1066.00
- 2026-09-09 03:02 ET LONDON | CLOSED BUY q=5 in=29872.0 out=29864.75 pnl=-72.50 R=-1.00 Stop hit
- 2026-09-09 03:49 ET LONDON | LONG_ENTRY e=29872.0 s=29856.75 t=29930.0 rr=3.80 q=5 | ALLOW qty=5 Approved: 5 contracts (honoured requested 5 (risk-derived max 5)), $152.50 risk ($30.50/micro), budget $250.00 (base $250.00), R:R 3.80:1, DLL room: $993.50
- 2026-09-09 04:03 ET LONDON | CLOSED BUY q=5 in=29872.0 out=29885.85 pnl=138.50 R=0.91 2 exits: 3@29887.25 Partial profit at 1.0R; 2@29883.75 Breakeven stop hit
- 2026-09-09 15:45 ET NO_ENTRY | NO_ENTRY NO_ENTRY: 14:45-17:00 CT no-entry block (armed, band [29750.5,29757.5])
- 2026-09-09 19:54 ET ASIA | SHORT_ENTRY e=29751.25 s=29760.75 t=29711.75 rr=4.16 q=5 | ALLOW qty=5 Approved: 5 contracts (honoured requested 5 (risk-derived max 5)), $95.00 risk ($19.00/micro), budget $250.00 (base $250.00), R:R 4.16:1, DLL room: $1132.00
- 2026-09-09 19:55 ET ASIA | CLOSED SELL q=5 in=29751.25 out=29760.75 pnl=-95.00 R=-1.00 Stop hit
- 2026-09-10 06:40 ET LONDON | ALARM ALARM: no-reaction-at-band (band [29690.5,29699.5])
- 2026-09-10 12:34 ET NY_LUNCH | ALARM ALARM: no-reaction-at-band (band [29420.0,29451.25])
- 2026-09-10 12:35 ET NY_LUNCH | LONG_ENTRY e=29435.75 s=29410.0 t=29495.25 rr=2.31 q=4 | ALLOW qty=4 Approved: 4 contracts (honoured requested 4 (risk-derived max 4)), $206.00 risk ($51.50/micro), budget $250.00 (base $250.00), R:R 2.31:1, DLL room: $1037.00
- 2026-09-10 12:44 ET NY_LUNCH | CLOSED BUY q=4 in=29435.75 out=29476.38 pnl=325.00 R=1.58 3 exits: 2@29461.50 Partial profit at 1.0R; 1@29487.25 Partial profit at 2.0R; 1@29495.25 Final target hit
- 2026-09-10 15:08 ET NY_PM | ALARM ALARM: impulse-awaiting-rejection (need a close back beyond swept 29440.25 in the trade direction, band [29420.0,29451.25])
- 2026-09-10 15:09 ET NY_PM | LONG_ENTRY e=29443.0 s=29419.0 t=29495.25 rr=2.18 q=5 | ALLOW qty=5 Approved: 5 contracts (honoured requested 5 (risk-derived max 5)), $240.00 risk ($48.00/micro), budget $250.00 (base $250.00), R:R 2.18:1, DLL room: $1362.00
- 2026-09-10 15:52 ET NO_ENTRY | CLOSED BUY q=5 in=29443.0 out=29419.00 pnl=-240.00 R=-1.00 Stop hit
- 2026-09-11 03:33 ET LONDON | ALARM ALARM: no-reaction-at-band (band [29518.25,29527.25])
- 2026-09-13 20:06 ET ASIA | ALARM ALARM: no-reaction-at-band (band [29348.75,29368.75])
- 2026-09-13 20:07 ET ASIA | SHORT_ENTRY e=29353.5 s=29369.75 t=29274.5 rr=4.86 q=5 | ALLOW qty=5 Approved: 5 contracts (honoured requested 5 (risk-derived max 5)), $162.50 risk ($32.50/micro), budget $250.00 (base $250.00), R:R 4.86:1, DLL room: $1122.00
- 2026-09-13 20:15 ET ASIA | CLOSED SELL q=5 in=29353.5 out=29342.45 pnl=110.50 R=0.68 2 exits: 3@29337.25 Partial profit at 1.0R; 2@29350.25 Breakeven stop hit
- 2026-09-14 09:11 ET PRE_NY | ALARM ALARM: no-reaction-at-band (band [29153.0,29162.5])
- 2026-09-15 05:51 ET LONDON | SHORT_ENTRY e=29354.25 s=29370.5 t=29297.25 rr=3.51 q=5 | ALLOW qty=5 Approved: 5 contracts (honoured requested 5 (risk-derived max 5)), $162.50 risk ($32.50/micro), budget $250.00 (base $250.00), R:R 3.51:1, DLL room: $1232.50
- 2026-09-15 05:58 ET LONDON | ORDER ORDER: cancelled — setup expired (200 bars without progress)
- 2026-09-15 15:55 ET NO_ENTRY | NO_ENTRY NO_ENTRY: 14:45-17:00 CT no-entry block (armed, band [29239.75,29248.0])
- 2026-09-15 22:26 ET ASIA | SHORT_ENTRY e=29271.25 s=29279.25 t=29250.25 rr=2.63 q=5 | ALLOW qty=5 Approved: 5 contracts (honoured requested 5 (risk-derived max 5)), $80.00 risk ($16.00/micro), budget $250.00 (base $250.00), R:R 2.63:1, DLL room: $1232.50
- 2026-09-15 22:30 ET ASIA | CLOSED SELL q=5 in=29271.25 out=29279.25 pnl=-80.00 R=-1.00 Stop hit
- 2026-09-16 08:37 ET PRE_NY | ALARM ALARM: no-reaction-at-band (band [29379.5,29392.25])
- 2026-09-16 08:38 ET PRE_NY | LONG_ENTRY e=29385.25 s=29377.0 t=29410.0 rr=3.00 q=5 | ALLOW qty=5 Approved: 5 contracts (honoured requested 5 (risk-derived max 5)), $82.50 risk ($16.50/micro), budget $250.00 (base $250.00), R:R 3.00:1, DLL room: $1152.50
- 2026-09-16 08:39 ET PRE_NY | CLOSED BUY q=5 in=29385.25 out=29377.00 pnl=-82.50 R=-1.00 Stop hit
- 2026-09-16 13:02 ET NY_LUNCH | ALARM ALARM: impulse-awaiting-rejection (need a close back beyond swept 29446.75 in the trade direction, band [29446.75,29469.5])
- 2026-09-16 13:04 ET NY_LUNCH | LONG_ENTRY e=29451.75 s=29438.5 t=29501.0 rr=3.72 q=5 | ALLOW qty=5 Approved: 5 contracts (honoured requested 5 (risk-derived max 5)), $132.50 risk ($26.50/micro), budget $250.00 (base $250.00), R:R 3.72:1, DLL room: $1070.00
- 2026-09-16 13:40 ET NY_PM | CLOSED BUY q=5 in=29451.75 out=29438.50 pnl=-132.50 R=-1.00 Stop hit
- 2026-09-17 09:37 ET NY_AM | ALARM ALARM: no-reaction-at-band (band [29597.5,29630.75])
- 2026-09-17 10:13 ET NY_AM | ALARM ALARM: impulse-awaiting-rejection (need a close back beyond swept 29694.75 in the trade direction, band [29687.25,29709.5])
- 2026-09-17 15:59 ET NO_ENTRY | NO_ENTRY NO_ENTRY: 14:45-17:00 CT no-entry block (armed, band [29724.0,29738.75])
- 2026-09-17 22:20 ET ASIA | SHORT_ENTRY e=29692.5 s=29699.5 t=29672.25 rr=2.89 q=5 | ALLOW qty=5 Approved: 5 contracts (honoured requested 5 (risk-derived max 5)), $70.00 risk ($14.00/micro), budget $250.00 (base $250.00), R:R 2.89:1, DLL room: $937.50
- 2026-09-17 22:21 ET ASIA | ORDER ORDER: cancelled — setup expired (200 bars without progress)
- 2026-09-21 15:50 ET NO_ENTRY | NO_ENTRY NO_ENTRY: 14:45-17:00 CT no-entry block (armed, band [30791.75,30807.0])
- 2026-09-22 00:20 ET ASIA | LONG_ENTRY e=30836.5 s=30827.5 t=30874.25 rr=4.19 q=5 | ALLOW qty=5 Approved: 5 contracts (honoured requested 5 (risk-derived max 5)), $90.00 risk ($18.00/micro), budget $250.00 (base $250.00), R:R 4.19:1, DLL room: $937.50
- 2026-09-22 00:29 ET ASIA | CLOSED BUY q=5 in=30836.5 out=30843.50 pnl=70.00 R=0.78 2 exits: 3@30845.50 Partial profit at 1.0R; 2@30840.50 Breakeven stop hit
- 2026-09-23 01:25 ET ASIA | ALARM ALARM: no-reaction-at-band (band [31030.0,31042.75])
- 2026-09-23 01:26 ET ASIA | SHORT_ENTRY e=31036.5 s=31043.75 t=31012.25 rr=3.34 q=5 | ALLOW qty=5 Approved: 5 contracts (honoured requested 5 (risk-derived max 5)), $72.50 risk ($14.50/micro), budget $250.00 (base $250.00), R:R 3.34:1, DLL room: $1007.50
- 2026-09-23 01:34 ET ASIA | CLOSED SELL q=5 in=31036.5 out=31026.35 pnl=101.50 R=1.40 2 exits: 3@31029.25 Partial profit at 1.0R; 2@31022.00 Partial profit at 2.0R
- 2026-09-23 01:41 ET ASIA | ALARM ALARM: impulse-awaiting-rejection (need a close back beyond swept 31039.5 in the trade direction, band [31030.0,31042.75])
- 2026-09-23 01:42 ET ASIA | SHORT_ENTRY e=31036.5 s=31043.75 t=31012.25 rr=3.34 q=5 | ALLOW qty=5 Approved: 5 contracts (honoured requested 5 (risk-derived max 5)), $72.50 risk ($14.50/micro), budget $250.00 (base $250.00), R:R 3.34:1, DLL room: $1109.00
- 2026-09-23 01:49 ET ASIA | CLOSED SELL q=5 in=31036.5 out=31031.15 pnl=53.50 R=0.74 2 exits: 3@31029.25 Partial profit at 1.0R; 2@31034.00 Breakeven stop hit
- 2026-09-23 05:29 ET LONDON | ALARM ALARM: no-reaction-at-band (band [31007.0,31025.75])
- 2026-09-24 16:15 ET NO_ENTRY | NO_ENTRY NO_ENTRY: 14:45-17:00 CT no-entry block (armed, band [30713.0,30729.5])
- 2026-09-25 08:16 ET PRE_NY | ALARM ALARM: no-reaction-at-band (band [30921.0,30937.75])
- 2026-09-25 11:32 ET NY_AM | ALARM ALARM: impulse-awaiting-rejection (need a close back beyond swept 30862.25 in the trade direction, band [30833.75,30874.5])
- 2026-09-25 11:33 ET NY_AM | SHORT_ENTRY e=30854.5 s=30875.5 t=30776.5 rr=3.71 q=5 | ALLOW qty=5 Approved: 5 contracts (honoured requested 5 (risk-derived max 5)), $210.00 risk ($42.00/micro), budget $250.00 (base $250.00), R:R 3.71:1, DLL room: $1162.50
- 2026-09-25 11:59 ET NY_AM | CLOSED SELL q=5 in=30854.5 out=30840.90 pnl=136.00 R=0.65 2 exits: 3@30833.50 Partial profit at 1.0R; 2@30852.00 Breakeven stop hit
- 2026-09-27 18:01 ET PRE_ASIA | LONG_ENTRY e=30861.75 s=30848.5 t=30920.75 rr=4.45 q=5 | ALLOW qty=5 Approved: 5 contracts (honoured requested 5 (risk-derived max 5)), $132.50 risk ($26.50/micro), budget $250.00 (base $250.00), R:R 4.45:1, DLL room: $1298.50
- 2026-09-27 18:02 ET PRE_ASIA | CLOSED BUY q=5 in=30861.75 out=30848.50 pnl=-132.50 R=-1.00 Stop hit
- 2026-09-28 12:24 ET NY_LUNCH | ALARM ALARM: impulse-awaiting-rejection (need a close back beyond swept 30640.0 in the trade direction, band [30605.5,30673.0])
- 2026-09-28 12:32 ET NY_LUNCH | SHORT_ENTRY e=30645.0 s=30723.0 t=30356.5 rr=3.70 q=1 | ALLOW qty=1 Approved: 1 contracts (honoured requested 1 (risk-derived max 1)), $156.00 risk ($156.00/micro), budget $250.00 (base $250.00), R:R 3.70:1, DLL room: $1166.00
- 2026-09-28 13:08 ET NY_LUNCH | CLOSED SELL q=1 in=30645.0 out=30567.00 pnl=156.00 R=1.00 Partial profit at 1.0R
- 2026-09-28 14:53 ET NY_PM | ALARM ALARM: impulse-awaiting-rejection (need a close back beyond swept 30645.75 in the trade direction, band [30605.5,30673.0])
- 2026-09-28 14:57 ET NY_PM | SHORT_ENTRY e=30636.0 s=30674.0 t=30510.25 rr=3.31 q=3 | ALLOW qty=3 Approved: 3 contracts (honoured requested 3 (risk-derived max 3)), $228.00 risk ($76.00/micro), budget $250.00 (base $250.00), R:R 3.31:1, DLL room: $1322.00
- 2026-09-28 15:38 ET NY_PM | CLOSED SELL q=3 in=30636.0 out=30585.33 pnl=304.00 R=1.33 2 exits: 2@30598.00 Partial profit at 1.0R; 1@30560.00 Partial profit at 2.0R

## FunnelTelemetry (current session): [FUNNEL MNQ] BIAS_SET=0 MANIP_DONE=1 SWEEP_DONE=1 DISPLACED=0 MSS_CONFIRMED=1 OTE_ARMED=2 IN_TRADE=0 | invalidated: HTF bias flip=1 | stalls: SWEEP_DONE:no-recent-displacement=53, MSS_CONFIRMED:awaiting-band-touch=10, SWEEP_DONE:fvg-link-pending=5
