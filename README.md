# Topstep Futures Trading Algorithm

A fully automated futures trading system designed for Topstep evaluation and funded accounts. Built with event-driven architecture using Java for the trading engine, Spring Boot for the API, and React + TypeScript for the dashboard.

## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
- [Tech Stack](#tech-stack)
- [Project Structure](#project-structure)
- [Getting Started](#getting-started)
- [Development Roadmap](#development-roadmap)
- [Key Features](#key-features)
- [Safety and Compliance](#safety-and-compliance)
- [License](#license)

## Overview

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

1. **Market Data & Connectivity**: Receives and normalizes market data from Topstep
2. **Strategy Engine**: Implements ICT/SMC trading logic and generates signals
3. **Risk Engine**: Enforces Topstep rules and risk limits before every trade
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
├── docs/                    # Documentation
│   └── architecture/        # Architecture diagrams and specs
│
├── config/                  # Configuration files
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

## Development Roadmap

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

### Topstep Rules Compliance
✅ All trading originates from local device
✅ Daily loss limits enforced
✅ Trailing drawdown monitored
✅ Contract limits respected
✅ Flatten-by-time automated

### Security
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

**Topstep Compliance**: Ensure you read and understand all Topstep rules and terms of service. This software is designed to help comply with those rules, but YOU are responsible for ensuring compliance with all applicable rules and regulations.

---

**Status**: 🚧 Under Development - Week 1 Complete

For questions or issues, please open a GitHub issue.
