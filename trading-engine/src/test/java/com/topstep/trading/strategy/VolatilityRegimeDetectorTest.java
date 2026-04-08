package com.topstep.trading.strategy;

import com.topstep.trading.domain.Candle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.time.Instant;

import static org.assertj.core.api.Assertions.*;

@DisplayName("VolatilityRegimeDetector Tests")
class VolatilityRegimeDetectorTest {

    private VolatilityRegimeDetector detector;

    @BeforeEach
    void setUp() {
        detector = new VolatilityRegimeDetector("NQ", 10, 50);
    }

    private Candle makeCandle(double range) {
        double mid = 20000.0;
        return new Candle("NQ", Instant.now(), mid, mid + range / 2, mid - range / 2, mid, 1000);
    }

    @Test
    @DisplayName("Returns STABLE when not initialized")
    void stableWhenNotInitialized() {
        assertThat(detector.isInitialized()).isFalse();
        assertThat(detector.getRegime()).isEqualTo(VolatilityRegimeDetector.VolatilityRegime.STABLE);
        assertThat(detector.getRegimeRatio()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Stable regime with consistent candle ranges")
    void stableRegimeConsistentRanges() {
        // Feed 50 identical candles → short and long σ should be equal → ratio ≈ 1.0
        for (int i = 0; i < 50; i++) {
            detector.update(makeCandle(10.0));
        }

        assertThat(detector.isInitialized()).isTrue();
        assertThat(detector.getRegime()).isEqualTo(VolatilityRegimeDetector.VolatilityRegime.STABLE);
        assertThat(detector.getRegimeRatio()).isCloseTo(1.0, within(0.3));
        assertThat(detector.getZoneWidthMultiplier()).isEqualTo(1.0);
        assertThat(detector.isModelUnreliable()).isFalse();
    }

    @Test
    @DisplayName("Expanding regime when recent candles have highly variable ranges")
    void expandingRegime() {
        // 40 candles with very consistent ranges (low σ)
        for (int i = 0; i < 40; i++) {
            detector.update(makeCandle(10.0));  // constant range
        }
        // 10 candles with wildly varying ranges (high σ) — volatility expanding
        for (int i = 0; i < 10; i++) {
            detector.update(makeCandle(i % 2 == 0 ? 2.0 : 50.0));  // alternating 2 and 50
        }

        assertThat(detector.isInitialized()).isTrue();
        double ratio = detector.getRegimeRatio();
        // Short window σ should be much larger than long window σ
        assertThat(ratio).isGreaterThan(1.0);
    }

    @Test
    @DisplayName("Contracting regime when recent candles have much smaller ranges")
    void contractingRegime() {
        // 40 candles with large range, then 10 candles with small range
        for (int i = 0; i < 40; i++) {
            detector.update(makeCandle(30.0));
        }
        for (int i = 0; i < 10; i++) {
            detector.update(makeCandle(3.0));
        }

        assertThat(detector.isInitialized()).isTrue();
        double ratio = detector.getRegimeRatio();
        assertThat(ratio).isLessThan(1.0);
    }

    @Test
    @DisplayName("Zone width multiplier for stable regime is 1.0")
    void zoneWidthStable() {
        for (int i = 0; i < 50; i++) {
            detector.update(makeCandle(10.0));
        }
        assertThat(detector.getZoneWidthMultiplier()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("isModelUnreliable returns false for stable regime")
    void modelReliableWhenStable() {
        for (int i = 0; i < 50; i++) {
            detector.update(makeCandle(10.0));
        }
        assertThat(detector.isModelUnreliable()).isFalse();
    }

    @Test
    @DisplayName("Regime ratio increases when recent candles become more variable")
    void ratioIncreasesWithRecentVariability() {
        // Fill long window with very consistent data
        for (int i = 0; i < 50; i++) {
            detector.update(makeCandle(10.0));
        }
        assertThat(detector.isInitialized()).isTrue();
        double stableRatio = detector.getRegimeRatio();

        // Now add wildly alternating candles — only short window changes
        for (int i = 0; i < 10; i++) {
            detector.update(makeCandle(i % 2 == 0 ? 1.0 : 60.0));
        }
        double newRatio = detector.getRegimeRatio();

        // Ratio should increase when short-term volatility spikes
        assertThat(newRatio).isGreaterThan(stableRatio);
    }

    @Test
    @DisplayName("Regime enum has correct descriptions")
    void regimeDescriptions() {
        assertThat(VolatilityRegimeDetector.VolatilityRegime.STABLE.getDescription())
                .contains("reliable");
        assertThat(VolatilityRegimeDetector.VolatilityRegime.EXPANDING.getDescription())
                .contains("widen");
        assertThat(VolatilityRegimeDetector.VolatilityRegime.CONTRACTING.getDescription())
                .contains("tighten");
        assertThat(VolatilityRegimeDetector.VolatilityRegime.TRANSITIONING.getDescription())
                .contains("unreliable");
    }
}
