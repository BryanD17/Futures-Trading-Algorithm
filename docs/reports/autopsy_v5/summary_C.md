# FUNNEL AUTOPSY — C-C intended all-sessions (scalpMode.enabled=true scalp.allSessions=true bias.hysteresis.enabled=true bias.vote.mode=VOTE)

tape=C:\Users\Owner\topstep-trading\tape symbol=MNQ bars=7830 smtBars=7830 first=2026-09-21 00:00 ET last=2026-09-28 16:22 ET

riskLimits: DLL=1000.0 MLL=2000.0 maxContracts=20 maxTotal=20 riskPerTrade=150.0 riskEngineRR=[0.8,1.5] validatorRR=[0.8,1.5]

cold start: first non-NEUTRAL bias at bar 1730 (2026-09-22 06:00 ET); first decisive 3-of-4 vote at bar 1730
episodes (BIAS_SET arrivals)=7 stateArrivals={BIAS_SET=7, IDLE=14, INVALIDATED=14, MANIP_DONE=10}

## Per-session counts

| session | bars | kzOpen | biasNonNeutral | signals | riskDenied | ordersSent | fills | closed | wins | losses |
|---|---|---|---|---|---|---|---|---|---|---|
| ASIA | 2179 | 2179 | 231 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| LONDON | 2160 | 2156 | 388 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| PRE_NY | 540 | 538 | 89 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| NY_AM | 900 | 897 | 321 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| NY_LUNCH | 540 | 539 | 97 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| NY_PM | 810 | 809 | 217 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| PRE_ASIA | 290 | 287 | 79 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| NO_ENTRY | 410 | 90 | 161 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| WEEKEND | 1 | 0 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |

## Gate HOLDING the machine, bars per session (each row sums to that session's bars)

| session | INVALIDATED-await-rearm | INVALIDATED-await-rearm-kzClosed | M2-bias-NEUTRAL | M4-no-sweep | MANIP-no-leg | total |
|---|---|---|---|---|---|---|
| ASIA | 0 | 0 | 1948 | 231 | 0 | 2179 |
| LONDON | 28 | 0 | 1772 | 105 | 255 | 2160 |
| PRE_NY | 14 | 0 | 451 | 1 | 74 | 540 |
| NY_AM | 21 | 0 | 579 | 180 | 120 | 900 |
| NY_LUNCH | 7 | 0 | 443 | 90 | 0 | 540 |
| NY_PM | 7 | 0 | 593 | 120 | 90 | 810 |
| PRE_ASIA | 20 | 0 | 210 | 60 | 0 | 290 |
| NO_ENTRY | 0 | 0 | 249 | 146 | 15 | 410 |
| WEEKEND | 0 | 1 | 0 | 0 | 0 | 1 |

## Top-3 holding gates per session

- ASIA (2179 bars): M2-bias-NEUTRAL 1948 (89.4%); M4-no-sweep 231 (10.6%)
- LONDON (2160 bars): M2-bias-NEUTRAL 1772 (82.0%); MANIP-no-leg 255 (11.8%); M4-no-sweep 105 (4.9%)
- PRE_NY (540 bars): M2-bias-NEUTRAL 451 (83.5%); MANIP-no-leg 74 (13.7%); INVALIDATED-await-rearm 14 (2.6%)
- NY_AM (900 bars): M2-bias-NEUTRAL 579 (64.3%); M4-no-sweep 180 (20.0%); MANIP-no-leg 120 (13.3%)
- NY_LUNCH (540 bars): M2-bias-NEUTRAL 443 (82.0%); M4-no-sweep 90 (16.7%); INVALIDATED-await-rearm 7 (1.3%)
- NY_PM (810 bars): M2-bias-NEUTRAL 593 (73.2%); M4-no-sweep 120 (14.8%); MANIP-no-leg 90 (11.1%)
- PRE_ASIA (290 bars): M2-bias-NEUTRAL 210 (72.4%); M4-no-sweep 60 (20.7%); INVALIDATED-await-rearm 20 (6.9%)
- NO_ENTRY (410 bars): M2-bias-NEUTRAL 249 (60.7%); M4-no-sweep 146 (35.6%); MANIP-no-leg 15 (3.7%)
- WEEKEND (1 bars): INVALIDATED-await-rearm-kzClosed 1 (100.0%)

## Setup deaths (INVALIDATED transitions) by reason per session

- LONDON: {HTF bias NEUTRAL beyond grace=4}
- PRE_NY: {HTF bias NEUTRAL beyond grace=2}
- NY_AM: {HTF bias NEUTRAL beyond grace=3}
- NY_LUNCH: {HTF bias NEUTRAL beyond grace=1}
- NY_PM: {HTF bias NEUTRAL beyond grace=1}
- PRE_ASIA: {HTF bias NEUTRAL beyond grace=2}
- WEEKEND: {HTF bias NEUTRAL beyond grace=1}

## Deepest state reached at death per session

- LONDON: {BIAS_SET=1, MANIP_DONE=3}
- PRE_NY: {BIAS_SET=2}
- NY_AM: {BIAS_SET=1, MANIP_DONE=2}
- NY_LUNCH: {MANIP_DONE=1}
- NY_PM: {MANIP_DONE=1}
- PRE_ASIA: {MANIP_DONE=2}
- WEEKEND: {MANIP_DONE=1}

## Stall reasons (FunnelTelemetry) per session


## Risk-engine denials: {}

## Signal / order / trade log


## FunnelTelemetry (current session): [FUNNEL MNQ] BIAS_SET=0 MANIP_DONE=2 SWEEP_DONE=0 DISPLACED=0 MSS_CONFIRMED=0 OTE_ARMED=0 IN_TRADE=0 | invalidated: HTF bias NEUTRAL beyond grace=2
