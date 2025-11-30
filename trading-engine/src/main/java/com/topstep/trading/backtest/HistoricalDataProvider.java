package com.topstep.trading.backtest;

import com.topstep.trading.domain.Candle;
import com.topstep.trading.domain.TradingSession;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads historical futures data from CSV files for backtesting.
 *
 * Expected CSV format:
 * timestamp,open,high,low,close,volume
 * 2024-01-01T09:30:00,4500.00,4505.00,4498.00,4502.00,10000
 */
public class HistoricalDataProvider {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final ZoneId UTC = ZoneId.of("UTC");

    /**
     * Load candles from a CSV file.
     *
     * @param filePath Path to the CSV file
     * @param symbol Symbol name (e.g., "ES", "NQ")
     * @return List of candles
     */
    public List<Candle> loadFromCsv(String filePath, String symbol) throws IOException {
        List<Candle> candles = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            boolean isHeader = true;

            while ((line = reader.readLine()) != null) {
                // Skip header line
                if (isHeader) {
                    isHeader = false;
                    continue;
                }

                // Parse CSV line
                String[] parts = line.split(",");
                if (parts.length < 6) {
                    System.err.println("Invalid CSV line: " + line);
                    continue;
                }

                try {
                    String timestampStr = parts[0].trim();
                    double open = Double.parseDouble(parts[1].trim());
                    double high = Double.parseDouble(parts[2].trim());
                    double low = Double.parseDouble(parts[3].trim());
                    double close = Double.parseDouble(parts[4].trim());
                    long volume = Long.parseLong(parts[5].trim());

                    // Parse timestamp
                    LocalDateTime ldt = LocalDateTime.parse(timestampStr, FORMATTER);
                    Instant timestamp = ldt.atZone(UTC).toInstant();

                    // Create candle
                    Candle candle = new Candle(symbol, timestamp, open, high, low, close, volume, TradingSession.REGULAR);
                    candles.add(candle);

                } catch (Exception e) {
                    System.err.println("Error parsing line: " + line + " - " + e.getMessage());
                }
            }
        }

        System.out.println("Loaded " + candles.size() + " candles for " + symbol + " from " + filePath);
        return candles;
    }

    /**
     * Create sample synthetic data for testing (when no CSV is available).
     *
     * @param symbol Symbol name
     * @param count Number of candles to generate
     * @return List of synthetic candles
     */
    public List<Candle> generateSyntheticData(String symbol, int count) {
        List<Candle> candles = new ArrayList<>();
        Instant timestamp = Instant.now().minusSeconds(count * 300L); // 5-minute candles

        double basePrice = 4500.0;
        double price = basePrice;

        for (int i = 0; i < count; i++) {
            // Random walk with slight upward bias
            double change = (Math.random() - 0.48) * 10.0;
            price += change;

            double open = price;
            double high = price + Math.random() * 5.0;
            double low = price - Math.random() * 5.0;
            double close = low + Math.random() * (high - low);

            long volume = (long) (5000 + Math.random() * 5000);

            Candle candle = new Candle(symbol, timestamp, open, high, low, close, volume, TradingSession.REGULAR);
            candles.add(candle);

            timestamp = timestamp.plusSeconds(300); // Next 5-minute candle
        }

        System.out.println("Generated " + candles.size() + " synthetic candles for " + symbol);
        return candles;
    }
}
