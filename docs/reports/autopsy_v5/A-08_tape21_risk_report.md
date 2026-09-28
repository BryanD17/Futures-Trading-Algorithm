## ALL-SESSIONS TRADE MATRIX
| session | bars | days | geSWEEP | geMSS | geOTE | signals | fills | closed | W/L | sumR | avgR | pnl$ |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| ASIA | 6038 | 19 | 60 | 18 | 7 | 7 | 6 | 6 | 4/2 | 1.60 | 0.27 | 160.50 |
| LONDON | 5222 | 15 | 51 | 18 | 8 | 3 | 2 | 2 | 1/1 | -0.09 | -0.04 | 66.00 |
| PRE_NY | 1340 | 15 | 9 | 2 | 6 | 1 | 1 | 1 | 0/1 | -1.00 | -1.00 | -82.50 |
| NY_AM | 2250 | 15 | 26 | 6 | 8 | 2 | 2 | 2 | 1/1 | -0.35 | -0.17 | -78.00 |
| NY_LUNCH | 1346 | 15 | 14 | 3 | 5 | 3 | 3 | 2 | 2/0 | 2.58 | 1.29 | 481.00 |
| NY_PM | 1975 | 15 | 19 | 4 | 4 | 3 | 3 | 3 | 2/1 | 1.66 | 0.55 | 451.50 |
| PRE_ASIA | 849 | 15 | 18 | 1 | 1 | 1 | 1 | 1 | 0/1 | -1.00 | -1.00 | -132.50 |
| NO_ENTRY | 1074 | 15 | 3 | 4 | 4 | 0 | 0 | 1 | 0/1 | -1.00 | -1.00 | -240.00 |
| WEEKEND | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0/0 | 0.00 | 0.00 | 0.00 |

## RISK ENVELOPE
- worst daily P&L: -215.00 vs DLL -1000 -> OK
- peak-to-trough drawdown: 424.50 vs MLL 2000 -> OK
- max order quantity: 5 vs maxContracts 5 -> OK; max simultaneous positions 1 (total cap 10)
- bars with an open position inside NO_ENTRY/WEEKEND: 7 ['2026-09-10 15:45', '2026-09-10 15:46', '2026-09-10 15:47']
- entries inside NO_ENTRY/WEEKEND: 0 -> OK
- risk-engine denials: 0; orphan cancels: 2
- net P&L 626.00, sum R 2.40, closed 18, W 10 / L 8, daily P&L {'2026-09-08': 66.0, '2026-09-09': -29.0, '2026-09-10': 85.0, '2026-09-13': 110.5, '2026-09-15': -80.0, '2026-09-16': -215.0, '2026-09-22': 70.0, '2026-09-23': 155.0, '2026-09-25': 136.0, '2026-09-27': -132.5, '2026-09-28': 460.0}

## STARVATION CHECKS
- S1 sessions (>=3 days, 0 fills): none -> false
- S2 single gate > 50% of post-sweep deaths: top = SWEEP_DONE:expired: SWEEP_DONE→DISPLACE 72/186 (39%) -> false
- S3 signals allowed 20 vs fills 18 vs cancels 2 (gap must be explained) -> false
- S6 trades/day: 18 closed over 19 days = 0.95 -> TRUE
- S4 / S5: see the trade audit and golden-case sections
