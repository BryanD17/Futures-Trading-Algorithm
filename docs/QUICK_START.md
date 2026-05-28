# Quick Start — Topstep Futures Trading Algorithm

This guide assumes you have cloned the repo and want to build, run, and
work with the new STDV+OTE refactor (v2.0).

## Prerequisites

- JDK 21 (Temurin verified)
- Node 18+
- Git

## Build

From the repo root:

```bash
# Backend (trading-engine + api-backend)
./gradlew clean build --no-daemon

# Frontend
cd dashboard-frontend
npm install
npm run build
```

The backend has 6 known pre-existing test failures in the news subsystem
(documented in `docs/REFRACTOR_BASELINE.md`); these are NOT caused by the
refactor and were already present at the baseline commit.

## Run

### BACKTEST mode (default)

```bash
./gradlew :trading-engine:run --args="BACKTEST"
```

This runs `BacktestExample`, which currently uses the **legacy**
`IctHighConfluenceStrategy`. The new `StdvOteStrategy` is not yet wired
as the default — see `docs/BACKTEST_COMPARISON.md` §2.3 for the work
that has to land first.

### SIM mode (MockConnector)

```bash
./gradlew :trading-engine:run --args="SIM"
```

Boots `SimEngineRunner` with the MockConnector — safe for testing.

### LIVE mode

```bash
./gradlew :trading-engine:run --args="LIVE"
```

Requires the following environment variables to be set (the runner
will refuse to start without them):

- `TOPSTEP_API_URL`
- `TOPSTEP_USERNAME`
- `TOPSTEP_API_KEY`
- `TOPSTEP_ACCOUNT_ID`

**This connects to real markets and risks real money. Do not enable
LIVE until you have run a SIM session and watched the new Setup panel
render a complete state-machine lifecycle.**

## Dashboard

```bash
cd dashboard-frontend
npm run dev
```

Open the printed URL (Vite chooses one, typically `http://localhost:5173`).
The API backend must be running for the dashboard to populate:

```bash
./gradlew :api-backend:bootRun
```

## The new Setup tab

The dashboard now has a **Setup** tab between Overview and Positions.
It visualises the STDV+OTE setup state per instrument (MNQ / MES / MGC):

- **State machine stepper** — current position in the IDLE → IN_TRADE
  sequence; INVALIDATED renders with the last failed gate id.
- **Bias / killzone / SMT / tier pills.**
- **Mandatory gates M1..M9** — the failing gate is highlighted.
- **STDV ladder** — the 5-level exit ladder (-0.27 / -1 / -2 / -2.5 / -4)
  with liquidity-backed badges and a realism tag on -2.0.
- **OTE zone** — all five canonical levels (0.5 / 0.62 / 0.705 / 0.79 / 1.0)
  and the PD-array-in-zone edge.
- **Plan block** — entry / stop / RR / size / tier (appears on IN_TRADE).

The panel polls `/api/setup/{symbol}` at 1Hz; WebSocket push is a
follow-up.

## Configuration knobs you will actually touch

In `application.yml` (or as Spring properties), under the `stdvOte.*`
root. Defaults match `docs/architecture/STDV_OTE_MODEL.md`:

| Key | Default | What it does |
|-----|---------|--------------|
| `stdvOte.enabled` | `false` | Switch the default strategy to STDV_OTE. Leave `false` until the SIM smoke test passes. |
| `stdvOte.size.riskFraction` | `0.12` | Fraction of available MLL room risked per trade. |
| `stdvOte.size.safetyCushion` | `300` | Dollars kept off the MLL floor. |
| `stdvOte.rr.floor` | `2.0` | Minimum reward-to-risk at the -2.0 STDV target. M7 rejects setups below this. |
| `stdvOte.killzone.nyAmStartEt` | `09:45` | NY AM killzone open (ET). |
| `stdvOte.killzone.nyAmEndEt` | `11:00` | NY AM killzone close (ET). |
| `stdvOte.killzone.silverBulletStartEt` | `10:00` | Silver Bullet open (ET). |
| `stdvOte.killzone.silverBulletEndEt` | `11:00` | Silver Bullet close (ET). |
| `stdvOte.raid.minQuality.MNQ` | `5` | Minimum raid quality for MNQ. |
| `stdvOte.raid.minQuality.MES` | `5` | Minimum raid quality for MES. |
| `stdvOte.raid.minQuality.MGC` | `6` | Stricter floor for MGC. |
| `stdvOte.risk.mllTrail` | `INTRADAY` | INTRADAY (conservative) or EOD. **You must set this to match your actual Topstep account/platform.** |
| `stdvOte.risk.flattenByEt` | `15:10` | Topstep cutoff (CT). Verify against current Topstep rules. |

## Before going LIVE — checklist

The refactor leaves LIVE manual on purpose. Before you flip
`stdvOte.enabled = true` AND switch the runner default, verify:

1. **MLL trail model** — INTRADAY vs EOD for your specific Combine /
   Express / Funded account on your specific platform (TopstepX,
   NinjaTrader, Tradovate, Quantower, TradingView). Sources disagree.
2. **DLL status** — TopstepX removed the platform DLL in Aug 2024; the
   other platforms still enforce it. The sizer codes defensively
   regardless; confirm which model applies to you.
3. **Contract specs** for MNQ / MES / MGC — the registry hardcodes
   tick size, tick value, and point value. Verify against the broker
   spec sheet before risking live money.
4. **Session flatten cutoff** — historically ~15:10 CT. Confirm
   current Topstep rules.
5. **The SIM smoke test** — boot SIM, watch the Setup panel render a
   full state-machine lifecycle for at least one instrument, confirm
   flatten-by-time fires, confirm `stdvOte.size.max = 20` is respected.

## Troubleshooting

| Symptom | Likely cause |
|---------|--------------|
| Frontend builds but Setup panel shows "Backend disconnected" | api-backend bootRun is not running, or proxy is not pointing to `localhost:8080` |
| `npm run build` fails with TS errors | A new dependency was added without `npm install` running; rerun install |
| `./gradlew clean build` test failures in the news subsystem | These 6 failures are pre-existing; see `REFRACTOR_BASELINE.md` |
| Setup panel says state is IDLE forever | Expected until the detector poll inside `StdvOteStrategy.onCandle` is wired (see `BACKTEST_COMPARISON.md` §2.3) |

## Where the code lives

- New strategy code: `trading-engine/src/main/java/com/topstep/trading/strategy/stdvote/`
- New API controller: `api-backend/src/main/java/com/topstep/api/controller/SetupController.java`
- New frontend: `dashboard-frontend/src/components/SetupPanel.tsx` + `.css`,
  `dashboard-frontend/src/types/setup.ts`,
  `dashboard-frontend/src/services/setupApi.ts`
- Design / architecture: `docs/architecture/STDV_OTE_MODEL.md`
