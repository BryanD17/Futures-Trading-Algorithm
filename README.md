# Futures Trading Algorithm — LucidICT V6 (TradingView → CrossTrade → Lucid/Tradovate)

An ICT/SMC futures strategy, proven on a real MNQ tape by the Java engine in this repo, now **executed by a
Pine Script v6 strategy on TradingView** and routed to **Lucid Trading** accounts on Tradovate through a
webhook relay (CrossTrade) with broker-side brackets. The Java engine is the **research lab and parity
oracle**; it no longer trades.

> **V6 (2026-11-05).** Topstep access was permanently revoked on 2026-11-05; the TopstepX/ProjectX live path
> was removed (LIVE mode fails loud, `POST /api/control/start?mode=LIVE` → 410). Everything below that
> mentions Topstep is **historical** and kept for context. Start here:
> - **Owner runbook (go live without asking Claude):** `docs/RUNBOOK_TRADINGVIEW.md`
> - **Venue rules with sources:** `docs/LUCID_RULES.md`, `docs/PLATFORM_FACTS.md`
> - **Relay payloads + failure lifecycle:** `docs/v6/RELAY_SETUP.md` · **drills:** `docs/v6/DRILL_SCRIPT.md`
> - **Port map + parity oracle:** `docs/v6/PORT_MAP.md`, `docs/v6/GOLDEN_SIGNALS.md`, `PARITY_REPORT.md`
> - **The master prompt that built it (ledger, owner actions):** `TRADINGVIEW_MIGRATION_MASTER_PROMPT_V6.txt`

## Table of Contents

- [V6 Architecture](#v6-architecture)
- [Overview (historical: Topstep era)](#overview)
- [Architecture (historical: Java live engine)](#architecture)
- [Tech Stack](#tech-stack)
- [Project Structure](#project-structure)
- [Getting Started](#getting-started)
- [Building the Pine file](#building-the-pine-file)
- [Development Roadmap](#development-roadmap)
- [Key Features](#key-features)
- [Safety and Compliance](#safety-and-compliance)
- [License](#license)

## V6 Architecture

```
        ┌──────────────────────── TradingView (cloud) ────────────────────────┐
        │  Pine v6 strategy LucidICT_v6 on MNQ1! 5m (1m intrabars via         │
        │  request.security_lower_tf; MES read-only for SMT)                  │
        │   sessions → levels → raid score → MSS → displacement → FVG/OTE     │
        │   → bias → sizing → AccountProfile caps (from config/lucid_profiles) │
        │   strategy.entry/exit/close_all(alert_message = relay payload)      │
        └──────────────────────────────┬──────────────────────────────────────┘
                                       │ ONE alert: {{strategy.order.alert_message}} → HTTPS webhook
                                       ▼
        ┌──────────────── Relay: CrossTrade (cloud) ──────────────────────────┐
        │  secret key → strategy sync (out_of_sync=flatten, exit block) →      │
        │  Account Manager (session loss, drawdown, window, auto-flatten       │
        │  16:42 ET / 15:42 CT, Block Signals) → Tradovate-native OSO bracket  │
        │  (stop + T1, GTC) → N linked Lucid accounts, same side, same time    │
        └──────────────────────────────┬──────────────────────────────────────┘
                                       ▼
                     Tradovate — Lucid LucidFlex accounts (eval → funded)

        Java (this repo, local, NOT in the live path):
          backtest · Monte Carlo · optimisation · GoldenSignalExport (parity/java_signals.csv)
          · tools/pine_parity.py + tools/run_parity.sh (Pine vs Java on the same bars)
          · dashboard = "Research mode — live trading on TradingView"
```

| piece | where | owner doc |
|---|---|---|
| Pine sources (modular) → one pasteable file | `pine/src/*.pine` → `pine/dist/LucidICT_v6.pine` | [Building the Pine file](#building-the-pine-file) |
| Plan numbers (data only, sourced) | `config/lucid_profiles.yaml` | `docs/LUCID_RULES.md` |
| Relay payload builders + checker | `pine/src/75_payload.pine`, `tools/payload_check.py` | `docs/v6/RELAY_SETUP.md` |
| Parity oracle + harness | `parity/*.csv`, `tools/pine_parity.py`, `tools/bars_drift.py`, `tools/run_parity.sh` | `docs/v6/GOLDEN_SIGNALS.md`, `PARITY_REPORT.md` |
| Java lab (backtests on the Lucid profile, parity export) | `trading-engine/`, `api-backend/`, `dashboard-frontend/` | `docs/QUICK_START.md`, `docs/RUNBOOK_TRADINGVIEW.md` §10 |
| Secret hygiene | `.githooks/pre-commit` (`git config core.hooksPath .githooks`) | `.githooks/README.md` |

Sacred invariants (master prompt Section 2): one strategy instance, one traded root, one alert → **no
opposing exposure across instruments or accounts**; every entry carries a **broker-side stop at submission**;
no entries ≥ 16:30 ET (15:30 CT), flat at 16:40 ET (15:40 CT); size ≤ plan caps; no HFT / microscalp
signature; account-agnostic core; no repaint; no secrets in git; no Topstep orders.

## Overview

> **Historical (Topstep era, V1–V5).** The Java engine below traded Topstep via TopstepX/ProjectX. In V6 it
> is the research lab; the strategy logic it proved is what `pine/` ports.

This trading algorithm implements ICT/Smart Money Concepts (SMC) with strict risk management to trade futures contracts while adhering to Topstep's rules:

- **Max Daily Loss Enforcement**: Hard limits on daily losses
- **Trailing Drawdown Protection**: Automatic position management
- **Contract Limits**: Maximum contracts per position and total
- **Flatten-by-Time**: Must be flat by specified session close
- **Local Execution**: All trading originates from your device (Topstep requirement)

### Goals

1. **Primary**: Pass Topstep evaluation and trade funded accounts profitably
2. **Secondary**: Build a hardened, productizable trading engine
3. **Long-term**: Scale to multiple evaluation accounts and strategies

## Architecture

> **Historical (Java live engine).** This is the V1–V5 picture. In V6 the connector layer's live path is
> gone (`TopstepConn.` moved to `trading-engine/legacy`, not compiled; `MockConnector` remains for SIM /
> backtests) and the dashboard is a research viewer. The live path is the [V6 Architecture](#v6-architecture).

The system follows an event-driven architecture with clear separation of concerns:

```
┌─────────────────────────────────────────────────────────────┐
│                     DASHBOARD (React)                        │
│  ┌──────────┬──────────┬──────────┬──────────────────────┐ │
│  │ Overview │ Positions│  Trades  │  Risk Management     │ │
│  └──────────┴──────────┴──────────┴──────────────────────┘ │
└───────────────────────────┬─────────────────────────────────┘
                            │ REST + WebSocket
                            ↓
┌─────────────────────────────────────────────────────────────┐
│                  API BACKEND (Spring Boot)                   │
│  ┌──────────────────────────────────────────────────────┐  │
│  │   StatusController │ PositionsController │ etc.      │  │
│  └──────────────────────────────────────────────────────┘  │
└───────────────────────────┬─────────────────────────────────┘
                            │ In-memory or IPC
                            ↓
┌─────────────────────────────────────────────────────────────┐
│                   TRADING ENGINE (Java)                      │
│  ┌───────────────────────────────────────────────────────┐ │
│  │                    Event Bus                          │ │
│  └───────────┬───────────────────────┬───────────────────┘ │
│              ↓                       ↓                      │
│  ┌──────────────────┐    ┌──────────────────────┐         │
│  │ Market Data      │    │ Strategy Engine      │         │
│  │ - Candles        │    │ - ICT/SMC Logic      │         │
│  │ - Ticks/Quotes   │    │ - Signal Generation  │         │
│  └──────────────────┘    └──────────────────────┘         │
│              ↓                       ↓                      │
│  ┌──────────────────┐    ┌──────────────────────┐         │
│  │ Risk Engine      │    │ Execution Engine     │         │
│  │ - Daily Loss     │    │ - Order Management   │         │
│  │ - Drawdown       │    │ - Position Tracking  │         │
│  │ - Contract Limits│    │ - Fill Processing    │         │
│  └──────────────────┘    └──────────────────────┘         │
│              ↓                       ↓                      │
│  ┌───────────────────────────────────────────────────────┐ │
│  │              Connector Layer                          │ │
│  │  ┌─────────────┐  ┌─────────────┐  ┌──────────────┐ │ │
│  │  │MockConnector│  │TopstepConn. │  │RithmicConn.  │ │ │
│  │  └─────────────┘  └─────────────┘  └──────────────┘ │ │
│  └───────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────┐
│                   PERSISTENCE (SQLite)                       │
│  ┌─────────┬─────────┬─────────┬──────────────────────┐   │
│  │ Trades  │ Orders  │ Metrics │ Config Snapshots     │   │
│  └─────────┴─────────┴─────────┴──────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

### Key Components

1. **Market Data & Connectivity**: Receives and normalizes market data (V6: tape replay / Mock only; Topstep feed removed)
2. **Strategy Engine**: Implements ICT/SMC trading logic and generates signals (V6: the parity oracle for `pine/`)
3. **Risk Engine**: Enforces the prop-firm rules and risk limits before every trade (V6: Lucid profiles from `config/lucid_profiles.yaml`, EOD-trailing MLL + lock)
4. **Execution Engine**: Manages order lifecycle and position tracking
5. **Event Bus**: Central pub/sub system for decoupled communication
6. **API Backend**: REST + WebSocket endpoints for dashboard
7. **Dashboard**: Real-time monitoring UI
8. **Persistence**: SQLite database for trades, orders, and metrics

## Tech Stack

### Trading Engine
- **Language**: Java 21
- **Build**: Gradle
- **HTTP**: OkHttp
- **WebSocket**: Jetty
- **JSON**: Jackson
- **Database**: SQLite + HikariCP
- **Logging**: SLF4J + Logback
- **Testing**: JUnit 5, AssertJ, Mockito

### API Backend
- **Framework**: Spring Boot 3.2
- **Database**: Spring Data JPA + SQLite
- **WebSocket**: Spring WebSocket + STOMP

### Dashboard Frontend
- **Framework**: React 18
- **Language**: TypeScript
- **Build**: Vite
- **HTTP**: Axios
- **Charts**: Recharts
- **WebSocket**: STOMP.js

## Project Structure

```
Futures-Trading-Algorithm/
├── trading-engine/          # Core trading logic (Java)
│   ├── src/main/java/com/topstep/trading/
│   │   ├── domain/          # Domain models (Candle, Order, Position, etc.)
│   │   ├── event/           # Event bus and event types
│   │   ├── connector/       # Trading venue connectors
│   │   ├── strategy/        # Trading strategies (ICT/SMC)
│   │   ├── risk/            # Risk management engine
│   │   ├── execution/       # Order execution engine
│   │   └── persistence/     # Database repositories
│   ├── build.gradle
│   └── settings.gradle
│
├── api-backend/             # REST + WebSocket API (Spring Boot)
│   ├── src/main/java/com/topstep/api/
│   │   ├── controller/      # REST controllers
│   │   ├── service/         # Business logic
│   │   ├── websocket/       # WebSocket handlers
│   │   └── config/          # Configuration
│   ├── src/main/resources/
│   │   └── application.yml
│   ├── build.gradle
│   └── settings.gradle
│
├── dashboard-frontend/      # React dashboard
│   ├── src/
│   │   ├── components/      # React components
│   │   ├── services/        # API client
│   │   ├── types/           # TypeScript types
│   │   └── hooks/           # Custom hooks
│   ├── package.json
│   ├── tsconfig.json
│   └── vite.config.ts
│
├── pine/                    # V6: Pine Script v6 strategy
│   ├── src/                 # modular sources 00_header … 95_debug (concatenated by tools/pine_build.py)
│   └── dist/LucidICT_v6.pine# the ONE generated file the owner pastes (never hand-edited)
│
├── tools/                   # V6: pine_build.py, pine_lint.py, payload_check.py,
│   │                        #     pine_parity.py, bars_drift.py, run_parity.sh, tests/
├── parity/                  # V6: Java oracle (java_signals.csv, bars_*.csv) + owner TradingView exports
├── .githooks/               # V6: pre-commit secret scan (git config core.hooksPath .githooks)
│
├── docs/                    # Documentation
│   ├── RUNBOOK_TRADINGVIEW.md   # V6 owner runbook (setup, daily, kill switch, incidents, go-live ladder)
│   ├── LUCID_RULES.md / PLATFORM_FACTS.md   # venue truth with vendor URLs + retrieval dates
│   ├── v6/                  # PORT_MAP, PINE_CONSTRAINTS, GOLDEN_SIGNALS, RELAY_SETUP, DRILL_SCRIPT/REPORT
│   ├── RUNBOOK_V5.md        # historical (Java engine on Topstep)
│   └── architecture/        # Architecture diagrams and specs
│
├── config/                  # lucid_profiles.yaml (plan numbers, sourced) + engine config
│
├── LICENSE                  # MIT License
└── README.md               # This file
```

## Getting Started

### Prerequisites

- **Java 21** (OpenJDK or Oracle JDK)
- **Node.js 18+** and npm
- **Git**

### Installation

1. **Clone the repository**:
   ```bash
   git clone <repository-url>
   cd Futures-Trading-Algorithm
   ```

2. **Build the trading engine**:
   ```bash
   cd trading-engine
   ./gradlew build
   cd ..
   ```

3. **Build the API backend**:
   ```bash
   cd api-backend
   ./gradlew build
   cd ..
   ```

4. **Install dashboard dependencies**:
   ```bash
   cd dashboard-frontend
   npm install
   cd ..
   ```

### Running Locally

> **V6: the Java engine is the lab.** `BACKTEST` and `SIM` modes work as before (defaults now use the
> `LUCID_FLEX_50K_EVAL` profile); `LIVE` refuses to start ("Topstep removed in V6 — live trading runs on
> TradingView (see RUNBOOK_TRADINGVIEW.md)"). Live trading is configured in `docs/RUNBOOK_TRADINGVIEW.md`.
> The parity oracle: `./gradlew :trading-engine:test --tests '*GoldenSignalExport*'` → `parity/java_signals.csv`;
> compare with a TradingView export via `tools/run_parity.sh`.

> **V5 (2026-09-28, historical): run it the proven way.** `docs/RUNBOOK_V5.md` has the exact start commands, the
> `engine-defaults.properties` the real-tape matrix was proven with, the A/B switches, the pre-LIVE
> checklist, and how to read `GET /api/setup` ("which gate killed it"). The engine boots in that
> configuration with **no flags** (`./gradlew bootRun` or `java -jar api-backend-1.0.0-SNAPSHOT.jar`);
> every effective flag and its source is printed at boot and served at `GET /api/status → effectiveConfig`.
> Why it did not trade before V5: `DIAGNOSIS_V5.md`.


**Engine configuration (V5)**: every flag resolves through `EngineConfig`
(`-D` > `ENGINE_<KEY>` env > `~/topstep-trading/engine.properties` >
classpath `engine-defaults.properties` > code). `java -jar
api-backend/build/libs/api-backend-1.0.0-SNAPSHOT.jar` and `./gradlew
:api-backend:bootRun` load the same file, print the same **EFFECTIVE ENGINE
CONFIG** table at boot, and expose it at `GET /api/status` (`effectiveConfig`).
See `docs/QUICK_START.md` -> "ENGINE CONFIGURATION".

**Terminal 1 - Trading Engine**:
```bash
cd trading-engine
./gradlew run
```

**Terminal 2 - API Backend**:
```bash
cd api-backend
./gradlew bootRun
```

**Terminal 3 - Dashboard Frontend**:
```bash
cd dashboard-frontend
npm run dev
```

Then open http://localhost:3000 in your browser.

### Running Tests

**Trading Engine**:
```bash
cd trading-engine
./gradlew test
```

**API Backend**:
```bash
cd api-backend
./gradlew test
```

## Building the Pine file

V6 runs the strategy on TradingView. The Pine source is modular (`pine/src/NN_name.pine`, concatenated
in numeric order because Pine has no `#include`); the owner pastes ONE generated file,
`pine/dist/LucidICT_v6.pine`. Never edit `pine/dist/` by hand (master prompt R8).

```bash
python3 tools/pine_build.py                          # -> pine/dist/LucidICT_v6.pine
python3 tools/pine_lint.py pine/dist/LucidICT_v6.pine # static safety checks (exit 1 on any FAIL)
python3 tools/pine_build.py --check                  # CI: fail if dist is stale vs pine/src
python3 -m unittest discover -s tools/tests -v       # build + lint unit tests
SOURCE_DATE_EPOCH=0 python3 tools/pine_build.py      # byte-reproducible build (fixed build time)
```

| file | owner | content |
|---|---|---|
| `00_header.pine` | Agent 02 | `//@version=6`, the single `strategy()` declaration, `BUILD_ID` marker |
| `10_inputs.pine` | 02 (+03 Account/Risk, 04 Relay) | input groups; every Strategy input = Java effective default, tooltip names the EngineConfig key |
| `20_time.pine` | 02 | ET clock (`America/New_York`), SessionClassifier windows, killzones, trading-day/week keys, I3 time helpers |
| `30_levels.pine` | 02 | PDH/PDL, PWH/PWL, session H/L, equal H/L (tick tolerance) |
| `40_raid.pine` | 02 | level raids, swing sweeps, V5 raid score 1–10 |
| `50_structure.pine` | 02 | 5m buffer, 2-bar swings, MSS, displacement (ATR multiple) |
| `60_fvg_ote.pine` | 02 | FVG / IFVG / BREAKER / OB / WICK, OTE zone, target ladder |
| `70_bias_smt.pine` | 02 | dealing range (bias), SMT vs MES (data only), setup state machine, 1m replay engine, EXPORTS |
| `80_strategy.pine` | Agent 03 | entries, sizing, AccountProfile envelope |
| `90_alerts.pine` | Agent 04 | relay payloads |
| `95_debug.pine` | 02 | optional overlay + gate table (off by default) |

The header stamp (`// BUILD: LucidICT_v6 | git <sha> | built <UTC> | src-sha256 <hex16>`) and the Pine
constant `BUILD_ID` (= src-sha256) identify exactly which sources a pasted script came from. `git <sha>` is
`HEAD` at build time (`+src-modified` when `pine/src` had uncommitted edits). The only non-deterministic
token is the build time; two builds of the same tree with `SOURCE_DATE_EPOCH` set (or `--no-time`) are
byte-identical.

Lint rules (`tools/pine_lint.py`, details in its docstring): L1 exactly one `strategy()`; L2 `//@version=6`;
L3 every `request.security` uses `lookahead = barmerge.lookahead_off` (or the documented `[1]` + `lookahead_on`
pair); L4 entries only in `80_strategy.pine`; L5 no order references the SMT symbol or any `request.*` data
(I1); L6 every `strategy.entry` inside `if barstate.isconfirmed` (I8); L7 every entry has a
`strategy.exit(..., stop = ...)` (I2); L8 no URLs / secrets; L9 no `calc_on_every_tick`, `pyramiding = 0`;
L10 every order call carries a non-empty `alert_message` (the relay payload).

Pine cannot be compiled or run here — TradingView is the only compiler. Compiling is owner action OA-1
(click-by-click steps in `TRADINGVIEW_MIGRATION_MASTER_PROMPT_V6.txt` Appendix D).

### Parity: does Pine take the Java engine's trades?

```bash
./gradlew :trading-engine:test --tests '*GoldenSignalExport*'   # JDK 21 → parity/java_signals.csv (8 signals)
tools/run_parity.sh --tv-tz America/Chicago                     # needs parity/tv_trades.csv (owner OA-2)
```
`run_parity.sh` stops with the exact owner steps when the TradingView export is missing; with it, it runs the
data-drift check (`tools/bars_drift.py`, if `parity/tv_bars_*.csv` from OA-3 exist), `tools/pine_parity.py`
with every hard fail on (opposite direction, no stop, entry ≥ 16:30 ET, size above cap, stop/T1 off by > 2 ticks)
and rewrites `PARITY_REPORT.md` with the pre-filled expected classifications (`docs/v6/PARITY_EXPECTED.md`).

## Development Roadmap

> **Historical.** The Week 1–4 plan below is the original 2026 scaffold. Delivered since: V1/V2 chart fixes,
> V3 top-down OTE, V4 ICT stack, V5 trade-flow unblock (Java engine proven on the real tape), **V6 TradingView
> migration** (this). Deferred to V7: multi-root trading behind an exposure gateway, broker-side resting limits,
> relay-fed live dashboard, package rename `com.topstep` → neutral (master prompt Section 9).

### ✅ Week 1 - Foundations (COMPLETED)
- [x] Project structure and build configuration
- [x] Core domain models
- [x] Event bus architecture
- [x] Connector interfaces with mock implementation
- [x] Basic API endpoints
- [x] Dashboard UI skeleton

### Week 2 - Strategy & Backtesting
- [ ] Integrate historical data provider
- [ ] Build backtester framework
- [ ] Implement ICT/SMC strategy logic
- [ ] Implement risk engine with Topstep rules
- [ ] Run initial backtests

### Week 3 - Live Integration & Dashboard
- [ ] Implement Topstep connector
- [ ] Live market data subscription
- [ ] Complete dashboard with charts
- [ ] WebSocket real-time updates
- [ ] End-to-end paper trading

### Week 4 - Robustness & Operations
- [ ] State persistence and recovery
- [ ] Error handling and reconnection logic
- [ ] Dashboard authentication
- [ ] Deployment scripts
- [ ] Operating documentation

## Key Features

### Trading Strategy (ICT/SMC)
- Session segmentation (London, New York, Asian)
- Liquidity pool identification
- Stop raid detection
- Fair Value Gaps (FVGs)
- Order blocks and key price zones
- Fibonacci retracements for entry zones

### Risk Management
- **Daily Loss Limit**: Enforced before every trade
- **Trailing Drawdown**: Continuous monitoring
- **Position Sizing**: R-based risk (1R per trade)
- **Max Contracts**: Per position and total limits
- **Flatten-by-Time**: Automatic position closure
- **Kill Switch**: Automatic halt on breach

### Monitoring
- Real-time PnL and equity curve
- Open positions with stops/targets
- Trade history with R multiples
- Risk usage gauges
- System status and health

## Safety and Compliance

### Lucid Trading rules (V6) — every rule sourced in `docs/LUCID_RULES.md`
- Bots and trade copiers permitted; **hedging, HFT and microscalping prohibited and auto-detected** → one strategy,
  one traded root (MNQ), same side on every account; ≤ 3 entries per CME day, ≥ 60 s between orders, no
  strategy exit < 30 s after a fill; broker-side stop on every trade (OSO at Tradovate)
- LucidFlex 50K: target $3,000 / Max Loss Limit $2,000 EOD-trailing with lock / 40 micros max; forced flat
  16:45 ET (15:45 CT) — the strategy is flat at 16:40 ET (15:40 CT), the relay backstop at 16:42 ET (15:42 CT)
- Two open questions to Lucid support (OA-4 hedging wording, OA-7 daily-loss-limit amount) are printed verbatim
  in `docs/LUCID_RULES.md`

### Topstep Rules Compliance (historical, V1–V5)
✅ All trading originates from local device
✅ Daily loss limits enforced
✅ Trailing drawdown monitored
✅ Contract limits respected
✅ Flatten-by-time automated

### Security
- Relay key, webhook URL and account names live only in TradingView's hidden inputs / CrossTrade — never in
  the repo (placeholders `<RELAY_SECRET>`, `<ACCOUNT_1>`); `.githooks/pre-commit` blocks key-shaped strings
- API keys stored in environment variables
- No credentials in source code
- Local-first architecture
- Simple authentication for dashboard

### Risk Protection
- Hard kill switch on risk breach
- Manual override capability
- Persistent state recovery
- Comprehensive logging

## Contributing

This is currently a personal project. Contributions, suggestions, and feedback are welcome via issues.

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Disclaimer

**RISK WARNING**: Trading futures involves substantial risk of loss and is not suitable for all investors. This software is provided "as is" without warranty of any kind. Past performance is not indicative of future results. Always test thoroughly in simulation before risking real capital.

**Prop-firm Compliance**: Ensure you read and understand all Lucid Trading rules and terms of service (and those of
TradingView, CrossTrade and Tradovate). This software is designed to help comply with those rules, but YOU are
responsible for ensuring compliance with all applicable rules and regulations. (Topstep wording: historical.)

---

**Status**: V6 — Agents 00–05 merged (Pine port, relay adapter, Java lab); Agents 06–08 waiting on owner
TradingView/relay actions (compile, Strategy Tester export, drills). See the ledger in
`TRADINGVIEW_MIGRATION_MASTER_PROMPT_V6.txt` Section 1 and the owner action list in its Appendix D.

For questions or issues, please open a GitHub issue.
