# Macro News Integration System

## Overview

The Macro News Integration System provides economic calendar awareness and news-based bias adjustments for the futures trading algorithm. It gates trades around high-impact events and adjusts confluence scores based on macro-economic releases.

## Architecture

```
com.topstep.trading.news/
├── model/                        # Data models
│   ├── EconomicEvent.java        # Scheduled event with forecast
│   ├── EconomicRelease.java      # Actual release with outcome
│   ├── NewsImpulse.java          # Computed impulse from release
│   ├── TradeGatingDecision.java  # Gating decision object
│   ├── EventImpact.java          # HIGH, MEDIUM, LOW enum
│   ├── EventCategory.java        # Event category enum
│   ├── Currency.java             # Currency enum
│   ├── SurpriseDirection.java    # Direction enum
│   ├── GatingAction.java         # ALLOW, BLOCK, REDUCE_SIZE enum
│   └── MacroAlignment.java       # ALIGNED, NEUTRAL, OPPOSING enum
├── calendar/                     # Calendar providers
│   ├── EconomicCalendarProvider.java    # Interface
│   ├── TradingEconomicsProvider.java    # Live API implementation
│   └── MockCalendarProvider.java        # For backtesting
├── impact/                       # Impact analysis
│   ├── InstrumentNewsMapper.java  # Maps events to instruments
│   ├── SurpriseCalculator.java    # Calculates surprise metrics
│   ├── NewsImpactModel.java       # Converts releases to impulses
│   └── CurrencyStrengthIndex.java # Per-currency strength tracker
├── gating/                       # Trade gating
│   └── EventProximityChecker.java # Checks proximity to events
├── bias/                         # Bias modification
│   ├── NewsBiasModifier.java      # Tilts existing HTF bias
│   ├── MacroAlignmentScorer.java  # Score for confluence system
│   ├── NewsBiasBreakdown.java     # Detailed breakdown
│   └── ImpulseContribution.java   # Individual contribution
├── events/                       # EventBus events
│   ├── UpcomingEventWarning.java  # Warning about upcoming events
│   ├── EventReleaseEvent.java     # Release processed event
│   └── MacroBiasUpdateEvent.java  # Bias change notification
├── MacroNewsManager.java         # Main facade
└── MacroNewsConfig.java          # Configuration
```

## Quick Start

### Basic Usage

```java
// 1. Create the calendar provider
EconomicCalendarProvider provider = new TradingEconomicsProvider(apiKey);
// Or for backtesting:
// MockCalendarProvider provider = new MockCalendarProvider();
// provider.loadFromCsv(Path.of("economic_calendar.csv"));

// 2. Create the config
MacroNewsConfig config = MacroNewsConfig.defaults();

// 3. Create and start the manager
MacroNewsManager newsManager = new MacroNewsManager(provider, config, eventBus);
newsManager.start();

// 4. Integrate with strategy
strategy.setMacroNewsManager(newsManager);

// 5. In strategy's entry evaluation:
TradeGatingDecision gating = newsManager.checkTradeGating("ES");
if (gating.isBlocked()) {
    return Optional.empty(); // Don't trade
}

MacroAlignment alignment = newsManager.getMacroAlignment("ES", isBullish);
int confluenceAdjustment = newsManager.getConfluenceAdjustment("ES", isBullish);
confluenceScore += confluenceAdjustment;
```

## Configuration

### Default Configuration

```java
MacroNewsConfig config = MacroNewsConfig.defaults();
```

| Setting | Default | Description |
|---------|---------|-------------|
| `highImpactPreBuffer` | 5 min | Block trades this long before HIGH impact events |
| `highImpactPostBuffer` | 10 min | Reduce size this long after HIGH impact events |
| `mediumImpactPreBuffer` | 2 min | Block trades this long before MEDIUM impact events |
| `mediumImpactPostBuffer` | 5 min | Reduce size this long after MEDIUM impact events |
| `minRelevanceForGating` | 0.5 | Minimum relevance score to trigger gating |
| `highImpactDecay` | 4 hours | How long HIGH impact impulses last |
| `mediumImpactDecay` | 2 hours | How long MEDIUM impact impulses last |
| `lowImpactDecay` | 30 min | How long LOW impact impulses last |
| `alignmentThreshold` | 0.3 | Bias level to count as ALIGNED |
| `strongOppositionThreshold` | 0.5 | Bias level for OPPOSING |
| `blockingOppositionThreshold` | 0.6 | Bias level to block trades |

### Preset Configurations

```java
// For aggressive risk management (tighter gating)
MacroNewsConfig config = MacroNewsConfig.aggressive();

// For conservative trading (looser gating)
MacroNewsConfig config = MacroNewsConfig.conservative();

// For backtesting (no API refresh)
MacroNewsConfig config = MacroNewsConfig.forBacktest();
```

## Instrument Mappings

The `InstrumentNewsMapper` determines how events affect each instrument.

### Relevance Scores

| Event Type | ES/NQ | GC | CL | 6E | 6J |
|------------|-------|-----|-----|-----|-----|
| USD Fed | 1.0 | 1.0 | 0.5 | 0.95 | 0.95 |
| USD CPI | 0.95 | 0.95 | 0.4 | 0.8 | 0.8 |
| USD NFP | 0.9 | 0.7 | - | 0.75 | 0.75 |
| EUR ECB | 0.2 | 0.2 | - | 1.0 | - |
| JPY BOJ | 0.2 | 0.2 | - | - | 1.0 |
| EIA Crude | - | - | 1.0 | - | - |

### Directional Signs

| Surprise Type | ES | GC | CL | 6E | 6J |
|---------------|-----|-----|-----|-----|-----|
| Hot USD Inflation | -1 | -1 | - | -1 | -1 |
| Strong USD Jobs | -1 | -1 | - | -1 | -1 |
| Hawkish Fed | -1 | -1 | -1 | -1 | -1 |
| Strong EUR Data | - | - | - | +1 | - |
| Inventory Build | - | - | -1 | - | - |

## Trade Gating Logic

### HIGH Impact Events (Fed, CPI, NFP, ECB, BOJ)

- **5 min before**: BLOCK
- **15 min before**: REDUCE_SIZE (50%)
- **0-5 min after** (processed): REDUCE_SIZE (50%)
- **5-10 min after**: REDUCE_SIZE (if not processed: BLOCK)

### MEDIUM Impact Events (PMI, Retail Sales, Jobless Claims)

- **2 min before**: BLOCK
- **5 min before**: REDUCE_SIZE (70%)
- **5 min after**: REDUCE_SIZE (70%)

### LOW Impact Events

- No gating applied

## Bias Calculation

### Impulse Magnitude

Impulses are calculated using tanh normalization:

```
magnitude = tanh(z-score) × impact_multiplier
```

Where:
- Z-score = (actual - forecast) / category_stdev
- Impact multiplier: HIGH=1.0, MEDIUM=0.6, LOW=0.3

### Bias Aggregation

The total bias for an instrument is:

```
bias = Σ (relevance × directional_sign × decayed_magnitude)
```

Clamped to [-1.0, +1.0]

### Impulse Decay

Impulses decay exponentially:

```
M(t) = M₀ × e^(-2t/τ)
```

Where τ is the decay constant (4h for HIGH, 2h for MEDIUM, 30m for LOW).

## Alignment Scoring

### ALIGNED (+1 confluence)
- Technical bullish + news bias ≥ +0.3
- Technical bearish + news bias ≤ -0.3

### NEUTRAL (0 confluence)
- News bias between -0.3 and +0.3

### OPPOSING (-1 confluence)
- Technical bullish + news bias ≤ -0.5
- Technical bearish + news bias ≥ +0.5

### BLOCKING (no trade)
- Technical bullish + news bias ≤ -0.6
- Technical bearish + news bias ≥ +0.6

## EventBus Events

### UpcomingEventWarning
Published 15 minutes before HIGH impact events.

```java
eventBus.subscribe(EventType.UPCOMING_NEWS_EVENT, event -> {
    UpcomingEventWarning warning = (UpcomingEventWarning) event;
    log.warn(warning.getWarningMessage());
});
```

### EventReleaseEvent
Published when a release is processed.

```java
eventBus.subscribe(EventType.NEWS_RELEASE, event -> {
    EventReleaseEvent release = (EventReleaseEvent) event;
    log.info(release.getSummaryMessage());
});
```

### MacroBiasUpdateEvent
Published when bias changes for an instrument.

```java
eventBus.subscribe(EventType.MACRO_BIAS_UPDATE, event -> {
    MacroBiasUpdateEvent update = (MacroBiasUpdateEvent) event;
    log.info("{}: {} ({})", update.getInstrument(),
             update.getNewBias(), update.getBiasDirection());
});
```

## API Methods

### Primary Strategy API

```java
// Check trade gating (call BEFORE every entry)
TradeGatingDecision checkTradeGating(String instrument)

// Get macro alignment for confluence
MacroAlignment getMacroAlignment(String instrument, boolean technicalBullish)

// Get confluence score adjustment
int getConfluenceAdjustment(String instrument, boolean technicalBullish)

// Check if should block on strong opposition
boolean shouldBlockOnOpposition(String instrument, boolean technicalBullish)

// Get current news bias modifier
double getNewsBiasModifier(String instrument)
```

### Informational API

```java
// Get upcoming events for instrument
List<EconomicEvent> getUpcomingEvents(String instrument, int hoursAhead)

// Get all upcoming events
List<EconomicEvent> getAllUpcomingEvents(int hoursAhead)

// Get detailed bias breakdown
NewsBiasBreakdown getBiasBreakdown(String instrument)

// Get currency strength
double getCurrencyStrength(Currency currency)

// Get time until unblocked
Optional<Duration> getTimeUntilUnblocked(String instrument)
```

## Backtesting

### Using MockCalendarProvider

```java
MockCalendarProvider provider = new MockCalendarProvider();

// Load from CSV
provider.loadFromCsv(Path.of("economic_calendar_2024.csv"));

// Or add events programmatically
provider.addEvent(event, release);

// Set simulated time for backtest
provider.setSimulatedTime(backtestTime);

// Simulate release
provider.simulateRelease(eventId);
```

### CSV Format

```csv
date,time,name,currency,impact,category,forecast,actual,previous,unit
2024-01-05,08:30,US NFP,USD,HIGH,EMPLOYMENT,170,216,173,K
2024-01-11,08:30,US CPI MoM,USD,HIGH,INFLATION,0.2,0.3,0.1,%
```

## Testing

Run all tests:
```bash
./gradlew :trading-engine:test --tests "com.topstep.trading.news.*"
```

Test coverage targets:
- `InstrumentNewsMapperTest`: Verify all instrument/event mappings
- `EventProximityCheckerTest`: Test gating at various time offsets
- `SurpriseCalculatorTest`: Test surprise calculations
- `NewsBiasModifierTest`: Test bias calculations and alignment
- `MacroNewsManagerIntegrationTest`: Full flow tests

## Best Practices

1. **Always check gating before entries**: Call `checkTradeGating()` at the start of your entry evaluation.

2. **Apply size multiplier**: Even if not blocked, the size multiplier may be reduced.

3. **Use alignment for confluence**: Add `getConfluenceAdjustment()` to your total confluence score.

4. **Handle strong opposition**: Consider blocking trades when `shouldBlockOnOpposition()` returns true.

5. **Log bias breakdown**: Use `getBiasBreakdown()` for debugging and transparency.

6. **Subscribe to warnings**: Monitor `UPCOMING_NEWS_EVENT` for proactive alerts.

## Thread Safety

All public methods on `MacroNewsManager` are thread-safe. Internal data structures use:
- `ConcurrentHashMap` for impulse storage
- `CopyOnWriteArrayList` for callbacks
- `ReentrantReadWriteLock` for calendar cache

The manager can be safely accessed from multiple strategy threads simultaneously.
