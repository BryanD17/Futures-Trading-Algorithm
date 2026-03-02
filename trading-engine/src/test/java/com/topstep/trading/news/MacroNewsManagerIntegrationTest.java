package com.topstep.trading.news;

import com.topstep.trading.news.calendar.MockCalendarProvider;
import com.topstep.trading.news.model.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for MacroNewsManager - tests full flow.
 */
class MacroNewsManagerIntegrationTest {

    private MockCalendarProvider calendarProvider;
    private MacroNewsConfig config;
    private MacroNewsManager newsManager;

    @BeforeEach
    void setUp() {
        calendarProvider = new MockCalendarProvider();
        config = MacroNewsConfig.forBacktest();
        newsManager = new MacroNewsManager(calendarProvider, config, null);
        newsManager.start();
    }

    @AfterEach
    void tearDown() {
        newsManager.stop();
    }

    @Nested
    @DisplayName("Full Flow Tests")
    class FullFlowTests {

        @Test
        @DisplayName("Complete flow: event -> gating -> release -> bias")
        void completeFlow() {
            Instant now = Instant.now();
            calendarProvider.setSimulatedTime(now);

            // 1. Add upcoming event
            EconomicEvent event = EconomicEvent.builder()
                    .id("cpi-test")
                    .name("US CPI MoM")
                    .currency(Currency.USD)
                    .impact(EventImpact.HIGH)
                    .category(EventCategory.INFLATION)
                    .scheduledTime(now.plus(Duration.ofMinutes(3)))
                    .forecast(0.3)
                    .build();

            calendarProvider.addEvent(event);

            // 2. Check gating - should be blocked
            TradeGatingDecision gating = newsManager.checkTradeGating("ES", now);
            assertThat(gating.getAction()).isEqualTo(GatingAction.BLOCK);

            // 3. Move time past the event
            Instant afterEvent = now.plus(Duration.ofMinutes(5));
            calendarProvider.setSimulatedTime(afterEvent);

            // 4. Process release
            EconomicRelease release = EconomicRelease.builder()
                    .event(event)
                    .actual(0.6) // Hot inflation
                    .releaseTime(afterEvent)
                    .build();

            Optional<NewsImpulse> impulse = newsManager.processRelease(release);
            assertThat(impulse).isPresent();

            // 5. Check bias is now non-zero
            double bias = newsManager.getNewsBiasModifier("ES", afterEvent);
            assertThat(bias).isNotEqualTo(0.0);

            // 6. Check gating after processing - should now allow (with reduced size)
            TradeGatingDecision gatingAfter = newsManager.checkTradeGating("ES", afterEvent);
            assertThat(gatingAfter.getAction()).isIn(GatingAction.ALLOW, GatingAction.REDUCE_SIZE);
        }

        @Test
        @DisplayName("Multiple instruments affected by single event")
        void multipleInstrumentsAffected() {
            Instant now = Instant.now();

            // Fed decision affects multiple instruments
            EconomicEvent event = EconomicEvent.builder()
                    .id("fed-test")
                    .name("FOMC Rate Decision")
                    .currency(Currency.USD)
                    .impact(EventImpact.HIGH)
                    .category(EventCategory.CENTRAL_BANK)
                    .scheduledTime(now.minus(Duration.ofMinutes(5)))
                    .forecast(5.25)
                    .build();

            calendarProvider.addEvent(event);

            EconomicRelease release = EconomicRelease.builder()
                    .event(event)
                    .actual(5.5) // Hawkish surprise
                    .releaseTime(now)
                    .build();

            newsManager.processRelease(release);

            // All USD-sensitive instruments should have bias
            assertThat(newsManager.getNewsBiasModifier("ES")).isNotEqualTo(0.0);
            assertThat(newsManager.getNewsBiasModifier("NQ")).isNotEqualTo(0.0);
            assertThat(newsManager.getNewsBiasModifier("GC")).isNotEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("Gating API Tests")
    class GatingApiTests {

        @Test
        @DisplayName("checkTradeGating returns allow when no events")
        void allowsWhenNoEvents() {
            TradeGatingDecision decision = newsManager.checkTradeGating("ES");
            assertThat(decision.getAction()).isEqualTo(GatingAction.ALLOW);
        }

        @Test
        @DisplayName("getNextBlockingEvent returns upcoming event")
        void getNextBlockingEvent() {
            Instant now = Instant.now();
            calendarProvider.setSimulatedTime(now);

            EconomicEvent event = EconomicEvent.builder()
                    .id("nfp-test")
                    .name("US NFP")
                    .currency(Currency.USD)
                    .impact(EventImpact.HIGH)
                    .category(EventCategory.EMPLOYMENT)
                    .scheduledTime(now.plus(Duration.ofMinutes(10)))
                    .build();

            calendarProvider.addEvent(event);

            Optional<EconomicEvent> blocking = newsManager.getNextBlockingEvent("ES");
            assertThat(blocking).isPresent();
            assertThat(blocking.get().getName()).isEqualTo("US NFP");
        }
    }

    @Nested
    @DisplayName("Alignment API Tests")
    class AlignmentApiTests {

        @Test
        @DisplayName("getMacroAlignment returns neutral with no impulses")
        void neutralWithNoImpulses() {
            MacroAlignment alignment = newsManager.getMacroAlignment("ES", true);
            assertThat(alignment).isEqualTo(MacroAlignment.NEUTRAL);
        }

        @Test
        @DisplayName("getConfluenceAdjustment returns zero with no impulses")
        void zeroAdjustmentWithNoImpulses() {
            int adjustment = newsManager.getConfluenceAdjustment("ES", true);
            assertThat(adjustment).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Upcoming Events API Tests")
    class UpcomingEventsApiTests {

        @Test
        @DisplayName("getUpcomingEvents returns events for instrument")
        void getUpcomingEventsForInstrument() {
            Instant now = Instant.now();
            calendarProvider.setSimulatedTime(now);

            calendarProvider.addEvent(createEvent("US CPI", Currency.USD, EventImpact.HIGH, now.plus(Duration.ofHours(2))));
            calendarProvider.addEvent(createEvent("EUR GDP", Currency.EUR, EventImpact.HIGH, now.plus(Duration.ofHours(4))));

            List<EconomicEvent> esEvents = newsManager.getUpcomingEvents("ES", 24);

            assertThat(esEvents).isNotEmpty();
            assertThat(esEvents.stream().anyMatch(e -> e.getName().equals("US CPI"))).isTrue();
        }

        @Test
        @DisplayName("getAllUpcomingEvents returns all events")
        void getAllUpcomingEvents() {
            Instant now = Instant.now();
            calendarProvider.setSimulatedTime(now);

            calendarProvider.addEvent(createEvent("Event1", Currency.USD, EventImpact.HIGH, now.plus(Duration.ofHours(1))));
            calendarProvider.addEvent(createEvent("Event2", Currency.EUR, EventImpact.HIGH, now.plus(Duration.ofHours(2))));

            List<EconomicEvent> allEvents = newsManager.getAllUpcomingEvents(24);

            assertThat(allEvents).hasSize(2);
        }
    }

    @Nested
    @DisplayName("Health and Lifecycle Tests")
    class HealthTests {

        @Test
        @DisplayName("isHealthy returns true when running")
        void healthyWhenRunning() {
            assertThat(newsManager.isHealthy()).isTrue();
        }

        @Test
        @DisplayName("isRunning returns correct state")
        void runningState() {
            assertThat(newsManager.isRunning()).isTrue();

            newsManager.stop();

            assertThat(newsManager.isRunning()).isFalse();
        }

        @Test
        @DisplayName("Gracefully handles operations when stopped")
        void gracefulWhenStopped() {
            newsManager.stop();

            // Should return safe defaults
            assertThat(newsManager.checkTradeGating("ES").getAction()).isEqualTo(GatingAction.ALLOW);
            assertThat(newsManager.getMacroAlignment("ES", true)).isEqualTo(MacroAlignment.NEUTRAL);
            assertThat(newsManager.getNewsBiasModifier("ES")).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("Clear and Reset Tests")
    class ResetTests {

        @Test
        @DisplayName("clearImpulses removes all active impulses")
        void clearImpulses() {
            Instant now = Instant.now();

            // Process a release
            EconomicEvent event = createEvent("CPI", Currency.USD, EventImpact.HIGH, now);
            EconomicRelease release = EconomicRelease.builder()
                    .event(event)
                    .actual(0.6)
                    .releaseTime(now)
                    .build();

            newsManager.processRelease(release);
            assertThat(newsManager.getNewsBiasModifier("ES")).isNotEqualTo(0.0);

            // Clear impulses
            newsManager.clearImpulses();
            assertThat(newsManager.getNewsBiasModifier("ES")).isEqualTo(0.0);
        }
    }

    private EconomicEvent createEvent(String name, Currency currency, EventImpact impact, Instant scheduledTime) {
        return EconomicEvent.builder()
                .id("test-" + System.nanoTime())
                .name(name)
                .currency(currency)
                .impact(impact)
                .category(EventCategory.CENTRAL_BANK)
                .scheduledTime(scheduledTime)
                .forecast(1.0)
                .build();
    }
}
