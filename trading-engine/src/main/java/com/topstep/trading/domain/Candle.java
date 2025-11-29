package com.topstep.trading.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Represents a candlestick/OHLCV bar for futures market data.
 * Immutable value object.
 */
public final class Candle {
    private final String symbol;
    private final Instant timestamp;
    private final double open;
    private final double high;
    private final double low;
    private final double close;
    private final long volume;
    private final TradingSession session;

    public Candle(String symbol, Instant timestamp, double open, double high,
                  double low, double close, long volume, TradingSession session) {
        this.symbol = Objects.requireNonNull(symbol, "symbol cannot be null");
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp cannot be null");
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.volume = volume;
        this.session = session;
    }

    public String getSymbol() { return symbol; }
    public Instant getTimestamp() { return timestamp; }
    public double getOpen() { return open; }
    public double getHigh() { return high; }
    public double getLow() { return low; }
    public double getClose() { return close; }
    public long getVolume() { return volume; }
    public TradingSession getSession() { return session; }

    public boolean isBullish() {
        return close > open;
    }

    public boolean isBearish() {
        return close < open;
    }

    public double getRange() {
        return high - low;
    }

    public double getBody() {
        return Math.abs(close - open);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Candle candle = (Candle) o;
        return Double.compare(candle.open, open) == 0 &&
               Double.compare(candle.high, high) == 0 &&
               Double.compare(candle.low, low) == 0 &&
               Double.compare(candle.close, close) == 0 &&
               volume == candle.volume &&
               Objects.equals(symbol, candle.symbol) &&
               Objects.equals(timestamp, candle.timestamp);
    }

    @Override
    public int hashCode() {
        return Objects.hash(symbol, timestamp, open, high, low, close, volume);
    }

    @Override
    public String toString() {
        return String.format("Candle{symbol='%s', time=%s, O=%.2f, H=%.2f, L=%.2f, C=%.2f, V=%d, session=%s}",
                symbol, timestamp, open, high, low, close, volume, session);
    }
}
