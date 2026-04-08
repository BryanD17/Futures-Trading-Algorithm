package com.topstep.trading.strategy;

import com.topstep.trading.domain.Candle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.time.Instant;

import static org.assertj.core.api.Assertions.*;

@DisplayName("AdaptiveStopCalculator Tests")
class AdaptiveStopCalculatorTest {

    private AdaptiveStopCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new AdaptiveStopCalculator("NQ", 20);
    }

    private Candle makeCandle(double open, double high, double low, double close) {
        return new Candle("NQ", Instant.now(), open, high, low, close, 1000);
    }

    @Test
    @DisplayName("Not initialized with fewer than lookback candles")
    void notInitializedUntilEnoughCandles() {
        for (int i = 0; i < 19; i++) {
            calculator.update(makeCandle(100, 105, 95, 102));
        }
        assertThat(calculator.isInitialized()).isFalse();
        assertThat(calculator.getNoiseBuffer()).isEqualTo(0);
    }

    @Test
    @DisplayName("Initialized after lookback candles received")
    void initializedAfterLookbackCandles() {
        for (int i = 0; i < 20; i++) {
            calculator.update(makeCandle(100, 110, 90, 105));
        }
        assertThat(calculator.isInitialized()).isTrue();
    }

    @Test
    @DisplayName("Noise buffer equals 1.5 * stdDev of candle ranges")
    void noiseBufferCalculation() {
        // Feed 20 candles with known ranges: all range = 10.0 points
        for (int i = 0; i < 20; i++) {
            calculator.update(makeCandle(100, 105, 95, 102)); // range = 10
        }

        assertThat(calculator.isInitialized()).isTrue();
        assertThat(calculator.getMeanRange()).isCloseTo(10.0, within(0.01));
        // All ranges identical → stdDev = 0
        assertThat(calculator.getStdDevRange()).isCloseTo(0.0, within(0.01));
        assertThat(calculator.getNoiseBuffer()).isCloseTo(0.0, within(0.01));
    }

    @Test
    @DisplayName("Noise buffer with varied candle ranges")
    void noiseBufferWithVariedRanges() {
        // Feed 10 candles with range=8, then 10 with range=12 → mean=10, stddev should be 2
        for (int i = 0; i < 10; i++) {
            calculator.update(makeCandle(100, 104, 96, 102)); // range = 8
        }
        for (int i = 0; i < 10; i++) {
            calculator.update(makeCandle(100, 106, 94, 102)); // range = 12
        }

        assertThat(calculator.isInitialized()).isTrue();
        assertThat(calculator.getMeanRange()).isCloseTo(10.0, within(0.01));
        assertThat(calculator.getStdDevRange()).isCloseTo(2.0, within(0.01));
        // Noise buffer = 1.5 * 2.0 = 3.0
        assertThat(calculator.getNoiseBuffer()).isCloseTo(3.0, within(0.01));
    }

    @Test
    @DisplayName("Stop tightens in low volatility")
    void stopTightensInLowVolatility() {
        // Small candle ranges with some variation → tight but non-zero stop buffer
        for (int i = 0; i < 10; i++) {
            calculator.update(makeCandle(100, 101, 99, 100.5)); // range = 2
        }
        for (int i = 0; i < 10; i++) {
            calculator.update(makeCandle(100, 102, 98, 100.5)); // range = 4
        }

        double zoneEdge = 19900.0;
        double stop = calculator.calculateStop(zoneEdge, true);

        // Bullish: stop should be below zone edge, but very close (low vol)
        assertThat(stop).isLessThan(zoneEdge);
        assertThat(zoneEdge - stop).isLessThan(5.0); // Tight stop
    }

    @Test
    @DisplayName("Stop widens in high volatility")
    void stopWidensInHighVolatility() {
        // Large candle ranges → wide stop
        for (int i = 0; i < 10; i++) {
            calculator.update(makeCandle(100, 120, 80, 110)); // range = 40
        }
        for (int i = 0; i < 10; i++) {
            calculator.update(makeCandle(100, 130, 70, 110)); // range = 60
        }

        double zoneEdge = 19900.0;
        double stop = calculator.calculateStop(zoneEdge, true);

        // Bullish: stop should be further below zone edge (high vol)
        assertThat(stop).isLessThan(zoneEdge);
        assertThat(zoneEdge - stop).isGreaterThan(10.0); // Wide stop
    }

    @Test
    @DisplayName("Bearish stop is above zone edge")
    void bearishStopAboveZoneEdge() {
        // Use varied ranges so stdDev > 0
        for (int i = 0; i < 10; i++) {
            calculator.update(makeCandle(100, 105, 95, 98)); // range = 10
        }
        for (int i = 0; i < 10; i++) {
            calculator.update(makeCandle(100, 107, 93, 98)); // range = 14
        }

        double zoneEdge = 20100.0;
        double stop = calculator.calculateStop(zoneEdge, false);

        assertThat(stop).isGreaterThan(zoneEdge);
    }

    @Test
    @DisplayName("Validates stop rejects too tight stops")
    void rejectsTooTightStop() {
        for (int i = 0; i < 10; i++) {
            calculator.update(makeCandle(100, 104, 96, 102)); // range = 8
        }
        for (int i = 0; i < 10; i++) {
            calculator.update(makeCandle(100, 106, 94, 102)); // range = 12
        }

        // stdDev = 2.0, so 1.0σ = 2.0 pts minimum
        AdaptiveStopCalculator.StopValidation validation = calculator.validateStop(1.0); // < 1σ
        assertThat(validation.isValid()).isFalse();
        assertThat(validation.getSuggestedDistance()).isCloseTo(2.0, within(0.01));
    }

    @Test
    @DisplayName("Validates stop rejects too wide stops")
    void rejectsTooWideStop() {
        for (int i = 0; i < 10; i++) {
            calculator.update(makeCandle(100, 104, 96, 102)); // range = 8
        }
        for (int i = 0; i < 10; i++) {
            calculator.update(makeCandle(100, 106, 94, 102)); // range = 12
        }

        // stdDev = 2.0, so 3.0σ = 6.0 pts maximum
        AdaptiveStopCalculator.StopValidation validation = calculator.validateStop(7.0); // > 3σ
        assertThat(validation.isValid()).isFalse();
        assertThat(validation.getSuggestedDistance()).isCloseTo(6.0, within(0.01));
    }

    @Test
    @DisplayName("Validates stop accepts valid stops")
    void acceptsValidStop() {
        for (int i = 0; i < 10; i++) {
            calculator.update(makeCandle(100, 104, 96, 102)); // range = 8
        }
        for (int i = 0; i < 10; i++) {
            calculator.update(makeCandle(100, 106, 94, 102)); // range = 12
        }

        // stdDev = 2.0, so 1.5σ = 3.0 pts → valid (between 1σ and 3σ)
        AdaptiveStopCalculator.StopValidation validation = calculator.validateStop(3.0);
        assertThat(validation.isValid()).isTrue();
    }

    @Test
    @DisplayName("Fallback when uninitialized returns zone edge")
    void fallbackWhenUninitialized() {
        double zoneEdge = 19900.0;
        double stop = calculator.calculateStop(zoneEdge, true);

        // When not initialized, returns zone edge (caller applies ATR formula)
        assertThat(stop).isEqualTo(zoneEdge);
    }

    @Test
    @DisplayName("Validation accepts any stop when uninitialized")
    void validationAcceptsWhenUninitialized() {
        AdaptiveStopCalculator.StopValidation validation = calculator.validateStop(100.0);
        assertThat(validation.isValid()).isTrue();
    }
}
