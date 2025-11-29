# Quick Start Guide

This guide will help you get the Topstep Futures Trading Algorithm up and running quickly.

## Prerequisites

Before you begin, ensure you have the following installed:

- **Java 21**: [Download OpenJDK 21](https://adoptium.net/)
- **Node.js 18+**: [Download Node.js](https://nodejs.org/)
- **Git**: [Download Git](https://git-scm.com/)

Verify installations:
```bash
java -version    # Should show Java 21
node -version    # Should show v18 or higher
npm -version     # Should show npm 9 or higher
git --version    # Should show git 2.x
```

## Installation Steps

### 1. Clone the Repository

```bash
git clone <your-repo-url>
cd Futures-Trading-Algorithm
```

### 2. Build Trading Engine

```bash
cd trading-engine
./gradlew build
cd ..
```

On Windows:
```bash
cd trading-engine
gradlew.bat build
cd ..
```

### 3. Build API Backend

```bash
cd api-backend
./gradlew build
cd ..
```

### 4. Install Dashboard Dependencies

```bash
cd dashboard-frontend
npm install
cd ..
```

## Running the System

You'll need **3 terminal windows** to run all components:

### Terminal 1: Trading Engine

```bash
cd trading-engine
./gradlew run
```

Expected output:
```
INFO  EventBus started
INFO  MockConnector connected
INFO  Trading engine initialized
```

### Terminal 2: API Backend

```bash
cd api-backend
./gradlew bootRun
```

Expected output:
```
Started ApiBackendApplication in X.XXX seconds
Tomcat started on port(s): 8080
```

### Terminal 3: Dashboard Frontend

```bash
cd dashboard-frontend
npm run dev
```

Expected output:
```
  VITE v5.x.x  ready in XXX ms

  ➜  Local:   http://localhost:3000/
  ➜  Network: use --host to expose
```

## Access the Dashboard

Open your browser and navigate to:
```
http://localhost:3000
```

You should see the trading dashboard with:
- **Overview**: Account metrics and P&L
- **Positions**: Open positions (empty initially)
- **Trades**: Recent trade history (empty initially)
- **Risk**: Risk limits and usage

## Verify Everything Works

1. **Check API Health**:
   ```bash
   curl http://localhost:8080/api/status/health
   ```
   Should return: `{"status":"UP"}`

2. **Check Status**:
   ```bash
   curl http://localhost:8080/api/status
   ```
   Should return JSON with status information

3. **Check Dashboard**:
   - Dashboard should show "SIMULATION" mode in the header
   - All tabs should be accessible
   - No errors in browser console

## Next Steps

### Configure Risk Limits

Edit the risk configuration in your trading engine:
```java
RiskLimits limits = RiskLimits.topstep50k(); // For 50K account
// or
RiskLimits limits = RiskLimits.topstep100k(); // For 100K account
```

### Paper Trading

The system starts in simulation mode with the MockConnector:
- Generates simulated market data
- Simulates order fills
- Safe for testing strategies

### Topstep Integration

⚠️ **NOT YET IMPLEMENTED**

To connect to real Topstep:
1. Obtain Topstep API credentials
2. Implement TopstepConnector (currently a placeholder)
3. Update configuration to use TopstepConnector
4. Test thoroughly in paper mode first

## Troubleshooting

### Port Already in Use

If port 8080 or 3000 is already in use:

**API Backend**: Edit `api-backend/src/main/resources/application.yml`
```yaml
server:
  port: 8081  # Change to different port
```

**Frontend**: Edit `dashboard-frontend/vite.config.ts`
```typescript
server: {
  port: 3001  # Change to different port
}
```

### Gradle Build Fails

Clear Gradle cache:
```bash
./gradlew clean build --refresh-dependencies
```

### npm Install Fails

Clear npm cache:
```bash
cd dashboard-frontend
rm -rf node_modules package-lock.json
npm install
```

### Database Errors

The SQLite database is created automatically. If you encounter issues:
```bash
rm ~/topstep-trading/data.db  # Remove database and restart
```

## Stopping the System

Press `Ctrl+C` in each terminal window to stop:
1. Dashboard frontend
2. API backend
3. Trading engine

## Development Mode

For active development:

- **Auto-reload Backend**: Spring Boot DevTools (add to dependencies)
- **Auto-reload Frontend**: Already enabled with Vite HMR
- **Watch Tests**: `./gradlew test --continuous`

## Configuration

Key configuration files:
- `api-backend/src/main/resources/application.yml`: API settings
- `dashboard-frontend/vite.config.ts`: Frontend build settings
- `trading-engine/src/main/resources/logback.xml`: Logging (TODO)

## Getting Help

- Check the main [README.md](../README.md) for architecture details
- Review code comments and JavaDoc
- Open an issue on GitHub

## Safety Reminders

⚠️ **IMPORTANT**:
- This system is in development (Week 1 complete)
- Strategy logic not yet implemented
- Risk engine not yet connected
- DO NOT connect to real Topstep accounts yet
- Always test in simulation first

---

**Next**: Continue with [Week 2 Development](../README.md#week-2---strategy--backtesting)
