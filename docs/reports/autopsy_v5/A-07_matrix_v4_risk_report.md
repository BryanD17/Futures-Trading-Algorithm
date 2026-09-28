## ALL-SESSIONS TRADE MATRIX
| session | bars | days | geSWEEP | geMSS | geOTE | signals | fills | closed | W/L | sumR | avgR | pnl$ |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ASIA | 2179 | 7 | 23 | 10 | 7 | 3 | 3 | 3 | 2/1 | 0.54 | 0.18 | 50.00 |
| LONDON | 2160 | 6 | 20 | 8 | 3 | 1 | 1 | 1 | 1/0 | 0.81 | 0.81 | 87.50 |
| PRE_NY | 540 | 6 | 4 | 0 | 1 | 0 | 0 | 0 | 0/0 | 0.00 | 0.00 | 0.00 |
| NY_AM | 900 | 6 | 10 | 1 | 2 | 1 | 1 | 1 | 0/1 | -1.00 | -1.00 | -196.50 |
| NY_LUNCH | 540 | 6 | 4 | 3 | 1 | 1 | 1 | 1 | 1/0 | 1.00 | 1.00 | 156.00 |
| NY_PM | 810 | 6 | 8 | 0 | 0 | 2 | 1 | 1 | 1/0 | 1.33 | 1.33 | 304.00 |
| PRE_ASIA | 290 | 5 | 6 | 1 | 1 | 0 | 0 | 0 | 0/0 | 0.00 | 0.00 | 0.00 |
| NO_ENTRY | 410 | 6 | 1 | 2 | 1 | 0 | 0 | 0 | 0/0 | 0.00 | 0.00 | 0.00 |
| WEEKEND | 1 | 1 | 0 | 0 | 0 | 0 | 0 | 0 | 0/0 | 0.00 | 0.00 | 0.00 |

## RISK ENVELOPE
- worst daily P&L: -196.50 vs DLL -1000 -> OK
- peak-to-trough drawdown: 196.50 vs MLL 2000 -> OK
- max order quantity: 5 vs maxContracts 5 -> OK; max simultaneous positions 1 (total cap 10)
- bars with an open position inside NO_ENTRY/WEEKEND: 0 []
- entries inside NO_ENTRY/WEEKEND: 0 -> OK
- risk-engine denials: 0; orphan cancels: 1
- net P&L 401.00, sum R 2.68, closed 7, W 5 / L 2, daily P&L {'2026-09-23': 137.5, '2026-09-25': -196.5, '2026-09-28': 460.0}

## STARVATION CHECKS
- S1 sessions (>=3 days, 0 fills): ['PRE_NY', 'PRE_ASIA'] -> TRUE
- S2 single gate > 50% of post-sweep deaths: top = SWEEP_DONE:expired: SWEEP_DONE→DISPLACE 26/70 (37%) -> false
- S3 signals allowed 8 vs fills 7 vs cancels 1 (gap must be explained) -> false
- S6 trades/day: 7 closed over 7 days = 1.00 -> false
- S4 / S5: see the trade audit and golden-case sections
