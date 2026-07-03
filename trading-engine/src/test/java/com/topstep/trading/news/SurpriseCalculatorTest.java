package com.topstep.trading.news;

import com.topstep.trading.news.impact.SurpriseCalculator;
import com.topstep.trading.news.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Tests for SurpriseCalculator - verifies surprise calculations and direction.
 */
class SurpriseCalculatorTest {

    private SurpriseCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new SurpriseCalculator(MacroNewsConfig.defaults());
    }

    @Nested
    @DisplayName("Basic Surprise Calculations")
    class BasicCalculations {

        @Test
        @DisplayName("Calculates positive surprise correctly")
        void positiveSurprise() {
            EconomicEvent event = createInflationEvent(0.3); // Forecast 0.3%
            EconomicRelease release = createRelease(event, 0.5); // Actual 0.5%

            calculator.calculateSurprise(release);

            assertThat(release.getSurprise()).isEqualTo(0.2); // 0.5 - 0.3 = 0.2
            assertThat(release.getSurpriseZScore()).isCloseTo(1.0, within(0.01)); // 0.2 / 0.2 = 1.0
        }

        @Test
        @DisplayName("Calculates negative surprise correctly")
        void negativeSurprise() {
            EconomicEvent event = createInflationEvent(0.3);
            EconomicRelease release = createRelease(event, 0.1);

            calculator.calculateSurprise(release);

            assertThat(release.getSurprise()).isCloseTo(-0.2, within(1e-9));
            assertThat(release.getSurpriseZScore()).isCloseTo(-1.0, within(0.01));
        }

        @Test
        @DisplayName("Calculates inline surprise for small differences")
        void inlineSurprise() {
            EconomicEvent event = createInflationEvent(0.3);
            EconomicRelease release = createRelease(event, 0.35); // Very close

            calculator.calculateSurprise(release);

            // Z-score = 0.05 / 0.2 = 0.25, below threshold of 0.5
            assertThat(release.getSurpriseDirection()).isEqualTo(SurpriseDirection.INLINE);
        }

        @Test
        @DisplayName("Handles missing forecast gracefully")
        void missingForecast() {
            EconomicEvent event = createInflationEvent(null);
            EconomicRelease release = createRelease(event, 0.5);

            calculator.calculateSurprise(release);

            assertThat(release.getSurpriseDirection()).isEqualTo(SurpriseDirection.INLINE);
            assertThat(release.getSurprise()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("Handles missing actual gracefully")
        void missingActual() {
            EconomicEvent event = createInflationEvent(0.3);
            EconomicRelease release = createRelease(event, null);

            calculator.calculateSurprise(release);

            assertThat(release.getSurpriseDirection()).isEqualTo(SurpriseDirection.INLINE);
            assertThat(release.getSurprise()).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("Direction Determination")
    class DirectionTests {

        @Test
        @DisplayName("Better than expected for positive inflation surprise")
        void betterThanExpectedInflation() {
            // Higher CPI is worse for economy but BETTER for the currency (higher rates)
            EconomicEvent event = createInflationEvent(0.3);
            EconomicRelease release = createRelease(event, 0.6);

            calculator.calculateSurprise(release);

            assertThat(release.getSurpriseDirection()).isEqualTo(SurpriseDirection.BETTER_THAN_EXPECTED);
        }

        @Test
        @DisplayName("Worse than expected for negative inflation surprise")
        void worseThanExpectedInflation() {
            EconomicEvent event = createInflationEvent(0.3);
            EconomicRelease release = createRelease(event, 0.0);

            calculator.calculateSurprise(release);

            assertThat(release.getSurpriseDirection()).isEqualTo(SurpriseDirection.WORSE_THAN_EXPECTED);
        }

        @Test
        @DisplayName("Lower unemployment is BETTER (inverse indicator)")
        void lowerUnemploymentIsBetter() {
            EconomicEvent event = EconomicEvent.builder()
                    .id("test-event")
                    .name("US Unemployment Rate")
                    .currency(Currency.USD)
                    .impact(EventImpact.HIGH)
                    .category(EventCategory.EMPLOYMENT)
                    .scheduledTime(Instant.now())
                    .forecast(4.0)
                    .build();

            EconomicRelease release = createRelease(event, 3.5);

            calculator.calculateSurprise(release);

            // Lower unemployment is BETTER
            assertThat(release.getSurpriseDirection()).isEqualTo(SurpriseDirection.BETTER_THAN_EXPECTED);
        }

        @Test
        @DisplayName("Lower jobless claims is BETTER (inverse indicator)")
        void lowerJoblessClaimsIsBetter() {
            EconomicEvent event = EconomicEvent.builder()
                    .id("test-event")
                    .name("Initial Jobless Claims")
                    .currency(Currency.USD)
                    .impact(EventImpact.MEDIUM)
                    .category(EventCategory.EMPLOYMENT)
                    .scheduledTime(Instant.now())
                    .forecast(200.0)
                    .build();

            EconomicRelease release = createRelease(event, 180.0);

            calculator.calculateSurprise(release);

            assertThat(release.getSurpriseDirection()).isEqualTo(SurpriseDirection.BETTER_THAN_EXPECTED);
        }

    }

    @Nested
    @DisplayName("Category-Specific Z-Score Tests")
    class CategoryZScoreTests {

        @Test
        @DisplayName("Employment uses 50K standard deviation")
        void employmentStdev() {
            EconomicEvent event = EconomicEvent.builder()
                    .id("test-event")
                    .name("Nonfarm Payrolls")
                    .currency(Currency.USD)
                    .impact(EventImpact.HIGH)
                    .category(EventCategory.EMPLOYMENT)
                    .scheduledTime(Instant.now())
                    .forecast(200.0)
                    .build();

            // 50K surprise should be ~1 z-score for NFP
            EconomicRelease release = createRelease(event, 250.0);

            calculator.calculateSurprise(release);

            assertThat(release.getSurpriseZScore()).isCloseTo(1.0, within(0.01));
        }

        @Test
        @DisplayName("PMI uses 2-point standard deviation")
        void pmiStdev() {
            EconomicEvent event = EconomicEvent.builder()
                    .id("test-event")
                    .name("ISM Manufacturing PMI")
                    .currency(Currency.USD)
                    .impact(EventImpact.MEDIUM)
                    .category(EventCategory.PMI_SURVEYS)
                    .scheduledTime(Instant.now())
                    .forecast(50.0)
                    .build();

            // 2-point surprise should be ~1 z-score for PMI
            EconomicRelease release = createRelease(event, 52.0);

            calculator.calculateSurprise(release);

            assertThat(release.getSurpriseZScore()).isCloseTo(1.0, within(0.01));
        }
    }

    @Nested
    @DisplayName("Utility Method Tests")
    class UtilityTests {

        @Test
        @DisplayName("isLowerBetter correctly identifies inverse indicators")
        void isLowerBetterTest() {
            assertThat(calculator.isLowerBetter("US Unemployment Rate")).isTrue();
            assertThat(calculator.isLowerBetter("Initial Jobless Claims")).isTrue();
            assertThat(calculator.isLowerBetter("US CPI MoM")).isFalse();
            assertThat(calculator.isLowerBetter("Nonfarm Payrolls")).isFalse();
        }

        @Test
        @DisplayName("getCategoryStdev returns correct values")
        void categoryStdevTest() {
            assertThat(calculator.getCategoryStdev(EventCategory.INFLATION)).isEqualTo(0.2);
            assertThat(calculator.getCategoryStdev(EventCategory.EMPLOYMENT)).isEqualTo(50.0);
            assertThat(calculator.getCategoryStdev(EventCategory.PMI_SURVEYS)).isEqualTo(2.0);
        }
    }

    private EconomicEvent createInflationEvent(Double forecast) {
        return EconomicEvent.builder()
                .id("test-event")
                .name("US CPI MoM")
                .currency(Currency.USD)
                .impact(EventImpact.HIGH)
                .category(EventCategory.INFLATION)
                .scheduledTime(Instant.now())
                .forecast(forecast)
                .build();
    }

    private EconomicRelease createRelease(EconomicEvent event, Double actual) {
        return EconomicRelease.builder()
                .event(event)
                .actual(actual)
                .releaseTime(Instant.now())
                .build();
    }
}
