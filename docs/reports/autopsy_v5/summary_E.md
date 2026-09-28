# FUNNEL AUTOPSY — custom (E)

tape=C:\Users\Owner\topstep-trading\tape symbol=MNQ bars=7830 smtBars=7830 first=2026-09-21 00:00 ET last=2026-09-28 16:22 ET

riskLimits: DLL=1000.0 MLL=2000.0 maxContracts=5 maxTotal=10 riskPerTrade=250.0 riskEngineRR=[3.0,6.0] validatorRR=[2.0,Infinity]

cold start: first non-NEUTRAL bias at bar 120 (2026-09-21 02:00 ET); first decisive 3-of-4 vote at bar 1730
episodes (BIAS_SET arrivals)=12 stateArrivals={BIAS_SET=12, DISPLACED=5, IDLE=8, INVALIDATED=23, MANIP_DONE=16, MSS_CONFIRMED=5, OTE_ARMED=1, SWEEP_DONE=19}

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
| LONDON | 0 | 7 | 1182 | 8 | 62 | 0 | 205 | 315 | 234 | 141 | 0 | 0 | 6 | 2160 |
| PRE_NY | 0 | 0 | 540 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 540 |
| NY_AM | 0 | 38 | 94 | 77 | 110 | 10 | 15 | 122 | 160 | 216 | 56 | 0 | 2 | 900 |
| NY_LUNCH | 0 | 7 | 155 | 0 | 0 | 0 | 55 | 85 | 16 | 81 | 140 | 1 | 0 | 540 |
| NY_PM | 0 | 21 | 75 | 54 | 111 | 0 | 63 | 135 | 186 | 0 | 146 | 4 | 15 | 810 |
| PRE_ASIA | 0 | 0 | 290 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 290 |
| NO_ENTRY | 41 | 0 | 164 | 0 | 0 | 0 | 54 | 80 | 10 | 0 | 59 | 2 | 0 | 410 |
| WEEKEND | 0 | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 1 |

## Top-3 holding gates per session

- ASIA (2179 bars): INVALIDATED-await-rearm-kzClosed 2059 (94.5%); M2-bias-NEUTRAL 120 (5.5%)
- LONDON (2160 bars): INVALIDATED-await-rearm-kzClosed 1182 (54.7%); M5-no-fvg-for-displacement 315 (14.6%); M5-no-recent-displacement 234 (10.8%)
- PRE_NY (540 bars): INVALIDATED-await-rearm-kzClosed 540 (100.0%)
- NY_AM (900 bars): M6-no-MSS 216 (24.0%); M5-no-recent-displacement 160 (17.8%); M5-no-fvg-for-displacement 122 (13.6%)
- NY_LUNCH (540 bars): INVALIDATED-await-rearm-kzClosed 155 (28.7%); M7-no-reaction-at-band 140 (25.9%); M5-no-fvg-for-displacement 85 (15.7%)
- NY_PM (810 bars): M5-no-recent-displacement 186 (23.0%); M7-no-reaction-at-band 146 (18.0%); M5-no-fvg-for-displacement 135 (16.7%)
- PRE_ASIA (290 bars): INVALIDATED-await-rearm-kzClosed 290 (100.0%)
- NO_ENTRY (410 bars): INVALIDATED-await-rearm-kzClosed 164 (40.0%); M5-no-fvg-for-displacement 80 (19.5%); M7-no-reaction-at-band 59 (14.4%)
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
- NY_PM: {MSS_CONFIRMED=1, SWEEP_DONE=2}
- NO_ENTRY: {MSS_CONFIRMED=2, OTE_ARMED=1, SWEEP_DONE=2}

## Stall reasons (FunnelTelemetry) per session

- LONDON: {SWEEP_DONE:displacement-wrong-direction=205, SWEEP_DONE:no-fvg-for-displacement=315, SWEEP_DONE:no-recent-displacement=234}
- NY_AM: {MSS_CONFIRMED:no-reaction-at-band=56, SWEEP_DONE:displacement-already-consumed=10, SWEEP_DONE:displacement-wrong-direction=15, SWEEP_DONE:no-fvg-for-displacement=122, SWEEP_DONE:no-recent-displacement=160}
- NY_LUNCH: {MSS_CONFIRMED:no-reaction-at-band=140, MSS_CONFIRMED:ote-not-armed-after-reaction=1, SWEEP_DONE:displacement-wrong-direction=55, SWEEP_DONE:no-fvg-for-displacement=85, SWEEP_DONE:no-recent-displacement=16}
- NY_PM: {MSS_CONFIRMED:no-reaction-at-band=146, MSS_CONFIRMED:ote-not-armed-after-reaction=4, SWEEP_DONE:displacement-wrong-direction=63, SWEEP_DONE:no-fvg-for-displacement=135, SWEEP_DONE:no-recent-displacement=186}
- NO_ENTRY: {MSS_CONFIRMED:no-reaction-at-band=59, MSS_CONFIRMED:ote-not-armed-after-reaction=2, SWEEP_DONE:displacement-wrong-direction=54, SWEEP_DONE:no-fvg-for-displacement=80, SWEEP_DONE:no-recent-displacement=10}

## Risk-engine denials: {}

## Signal / order / trade log


## FunnelTelemetry (current session): [FUNNEL MNQ] BIAS_SET=2 MANIP_DONE=4 SWEEP_DONE=4 DISPLACED=0 MSS_CONFIRMED=1 OTE_ARMED=0 IN_TRADE=0 | invalidated: expired=1, impulse origin violated before OTE entry=1, HTF bias flip=1, HTF bias became NEUTRAL=1 | stalls: SWEEP_DONE:no-fvg-for-displacement=147, SWEEP_DONE:no-recent-displacement=129, SWEEP_DONE:displacement-wrong-direction=104, SWEEP_DONE:displacement-already-consumed=10
