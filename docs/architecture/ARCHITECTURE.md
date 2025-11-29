# System Architecture

## Overview

The Topstep Futures Trading Algorithm uses an event-driven, microservices-inspired architecture with three main components:

1. **Trading Engine** (Java): Core trading logic
2. **API Backend** (Spring Boot): REST + WebSocket API
3. **Dashboard** (React): Real-time monitoring UI

## Design Principles

### 1. Event-Driven Architecture

All components communicate through events via the central EventBus:

```
Market Data → CandleEvent → Strategy Engine → StrategySignalEvent → Risk Engine → OrderIntent → Execution Engine
```

**Benefits**:
- Loose coupling between components
- Easy to add new strategies or risk rules
- Natural audit trail
- Testability via event replay

### 2. Separation of Concerns

Each component has a single responsibility:

- **Market Data**: Only receives and normalizes data
- **Strategy**: Only generates signals based on market data
- **Risk**: Only validates signals against limits
- **Execution**: Only manages order lifecycle

### 3. Immutability Where Possible

Domain objects prefer immutability:
- `Candle`: Immutable
- `Trade`: Immutable
- `RiskLimits`: Immutable
- `Order`: Mutable (status changes)
- `Position`: Mutable (fills update)

## Component Details

### Trading Engine

#### Event Bus
- **Implementation**: Concurrent queue + thread pool
- **Threading**: Dedicated processor thread + worker pool
- **Delivery**: Async, fire-and-forget
- **Ordering**: FIFO within event type

```java
EventBus bus = new EventBus(4); // 4 worker threads
bus.subscribe(EventType.CANDLE, this::handleCandle);
bus.publish(new CandleEvent(candle));
```

#### Market Data Flow
```
Connector.onCandle()
    ↓
CandleEvent published
    ↓
Strategy subscribes to CANDLE
    ↓
Strategy.handleCandle()
    ↓
StrategySignalEvent published
```

#### Strategy Engine
- **Input**: Market data events (candles, ticks)
- **Output**: Strategy signal events
- **State**: Maintains internal state (swing highs/lows, FVGs, etc.)
- **Configuration**: Strategy parameters loaded from config

Example strategy signal:
```java
new StrategySignalEvent(
    SignalType.LONG_ENTRY,
    "ES",
    OrderSide.BUY,
    5000.0,  // entry
    4990.0,  // stop
    5020.0,  // target
    "FVG at discount + order block"
);
```

#### Risk Engine
- **Input**: Strategy signals
- **Output**: Approved/rejected order intents
- **Validation**:
  1. Check daily loss limit
  2. Check trailing drawdown
  3. Check contract limits
  4. Verify R:R ratio
  5. Check flatten-by-time
  6. Calculate position size

Hard kill switch:
```java
if (dailyLoss >= limits.getMaxDailyLoss()) {
    bus.publish(new RiskBreachEvent(...));
    return RiskDecision.REJECT;
}
```

#### Execution Engine
- **Input**: Risk-approved order intents
- **Output**: Orders to connector + order events
- **Responsibilities**:
  1. Submit orders to venue
  2. Track order lifecycle
  3. Process fills
  4. Update positions
  5. Calculate PnL

Fill processing:
```java
void onOrderFilled(Order order, int qty, double price) {
    order.recordFill(qty, price);
    accountState.updatePosition(order.getSymbol(), qty, price);
    bus.publish(new OrderEvent(EventType.ORDER_FILLED, order));
}
```

### API Backend

#### REST Endpoints

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/api/status` | GET | System status and mode |
| `/api/positions` | GET | Current open positions |
| `/api/trades` | GET | Trade history (paginated) |
| `/api/risk` | GET | Risk metrics and limits |
| `/api/metrics/daily` | GET | Daily performance metrics |
| `/api/control/pause` | POST | Pause trading engine |
| `/api/control/resume` | POST | Resume trading engine |

#### WebSocket Topics

| Topic | Purpose |
|-------|---------|
| `/topic/account` | Real-time account updates |
| `/topic/positions` | Position changes |
| `/topic/trades` | New trades |
| `/topic/risk` | Risk metric updates |

### Dashboard

#### Component Hierarchy
```
App
├── Header (status badge)
├── Tabs (navigation)
└── Content
    ├── Overview (metrics grid)
    ├── Positions (table)
    ├── Trades (table)
    └── Risk (gauges/bars)
```

#### State Management
- **Local State**: Component-specific (useState)
- **API Data**: Fetched via services (useEffect)
- **Real-time**: WebSocket updates (future)

#### Data Flow
```
Component.useEffect()
    ↓
API Service (axios)
    ↓
Backend REST endpoint
    ↓
Backend Service
    ↓
Trading Engine (in-memory or IPC)
```

## Data Models

### Core Domain

```java
// Market Data
Candle(symbol, timestamp, OHLCV, session)

// Orders
Order(id, symbol, side, type, quantity, prices, status)
OrderStatus: PENDING, SUBMITTED, FILLED, CANCELED, REJECTED

// Positions
Position(symbol, quantity, avgEntryPrice, unrealizedPnL)

// Trades
Trade(id, symbol, side, entry/exit, pnl, rMultiple, notes)

// Account
AccountState(balance, equity, positions, pnl)

// Risk
RiskLimits(maxDailyLoss, drawdown, maxContracts, riskPerTrade)
```

### Event Types

```java
EventType:
- CANDLE, TICK, QUOTE              // Market data
- STRATEGY_SIGNAL                   // Strategy
- ORDER_SUBMITTED, ORDER_FILLED     // Orders
- POSITION_OPENED, POSITION_CLOSED  // Positions
- RISK_BREACH                       // Risk
- ENGINE_STARTED, ENGINE_PAUSED     // System
```

## Threading Model

### Trading Engine
- **Main Thread**: Application initialization
- **EventBus Processor**: Single thread, processes event queue
- **EventBus Workers**: Thread pool for handlers (default: 4)
- **Connector Threads**: WebSocket/market data (varies by connector)

### Thread Safety
- EventBus: Thread-safe via concurrent collections
- AccountState: ConcurrentHashMap for positions
- Order: Synchronized mutations
- Immutable objects: Thread-safe by design

## Error Handling

### Strategy
- Catch all exceptions in event handlers
- Log error + context
- Continue processing (don't crash engine)

### Risk
- Reject on any validation error
- Emit RiskBreachEvent
- Log detailed reason

### Execution
- Retry transient errors (network)
- Reject permanent errors (invalid order)
- Emit OrderEvent(REJECTED)

### Recovery
- Persist critical state
- Restore on restart
- Reconcile positions with venue

## Security

### Authentication
- Dashboard: Simple auth (future: JWT)
- API: CORS restricted to localhost

### Secrets
- API keys in environment variables
- Never commit credentials
- Load from secure config at runtime

### Risk Controls
- Hard daily loss limit
- Kill switch on breach
- Manual override required to resume

## Performance Considerations

### Event Bus
- Bounded queue (prevent memory issues)
- Async processing (non-blocking)
- Worker pool sized to hardware

### Database
- SQLite for simplicity (local file)
- Consider PostgreSQL for production
- Index on timestamp, symbol

### Dashboard
- Polling interval: 3-10 seconds
- WebSocket for real-time (future)
- Pagination for large trade lists

## Deployment

### Development
- 3 processes: engine, API, dashboard
- All run locally
- SQLite database

### Production (Future)
- Systemd services for engine + API
- Nginx reverse proxy for dashboard
- PostgreSQL database
- Log aggregation
- Monitoring/alerting

## Testing Strategy

### Unit Tests
- Domain models
- Event bus
- Risk calculations
- Strategy logic

### Integration Tests
- Connector → EventBus → Strategy
- Strategy → Risk → Execution
- End-to-end event flow

### Backtesting
- Historical replay
- Strategy validation
- Risk rule verification

## Future Enhancements

1. **Multi-Strategy Support**: Run multiple strategies concurrently
2. **Hot Reload**: Update strategy params without restart
3. **Cloud Monitoring**: Push metrics to cloud dashboard
4. **Advanced Analytics**: ML-based signal filtering
5. **Multi-Account**: Manage multiple Topstep accounts

## References

- [Event-Driven Architecture](https://martinfowler.com/articles/201701-event-driven.html)
- [Domain-Driven Design](https://martinfowler.com/tags/domain%20driven%20design.html)
- [Clean Architecture](https://blog.cleancoder.com/uncle-bob/2012/08/13/the-clean-architecture.html)
