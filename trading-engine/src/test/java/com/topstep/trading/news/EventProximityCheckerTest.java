package com.topstep.trading.news;

import com.topstep.trading.news.calendar.MockCalendarProvider;
import com.topstep.trading.news.gating.EventProximityChecker;
import com.topstep.trading.news.impact.InstrumentNewsMapper;
import com.topstep.trading.news.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for EventProximityChecker - verifies gating logic at various time offsets.
 */
class EventProximityCheckerTest {

    private MockCalendarProvider calendarProvider;
    private InstrumentNewsMapper mapper;
    private MacroNewsConfig config;
    private EventProximityChecker checker;

    @BeforeEach
    void setUp() {
        calendarProvider = new MockCalendarProvider();
        mapper = new InstrumentNewsMapper();
        config = MacroNewsConfig.defaults();
        checker = new EventProximityChecker(calendarProvider, mapper, config);
    }

    @Nested
    @DisplayName("HIGH Impact Event Gating")
    class HighImpactTests {

        @Test
        @DisplayName("Should BLOCK trades 5 minutes before HIGH impact event")
        void blocksBeforeHighImpactEvent() {
            Instant now = Instant.now();
            Instant eventTime = now.plus(Duration.ofMinutes(3)); // 3 minutes away

            EconomicEvent event = createHighImpactEvent("FOMC Rate Decision", Currency.USD, eventTime);
            calendarProvider.addEvent(event);

            TradeGatingDecision decision = checker.checkGating(InstrumentNewsMapper.ES, now);

            assertThat(decision.getAction()).isEqualTo(GatingAction.BLOCK);
            assertThat(decision.getReason()).contains("HIGH impact event");
        }

        @Test
        @DisplayName("Should REDUCE_SIZE 15 minutes before HIGH impact event")
        void reduceSizeBeforeHighImpactEvent() {
            Instant now = Instant.now();
            Instant eventTime = now.plus(Duration.ofMinutes(12)); // 12 minutes away

            EconomicEvent event = createHighImpactEvent("FOMC Rate Decision", Currency.USD, eventTime);
            calendarProvider.addEvent(event);

            TradeGatingDecision decision = checker.checkGating(InstrumentNewsMapper.ES, now);

            assertThat(decision.getAction()).isEqualTo(GatingAction.REDUCE_SIZE);
            assertThat(decision.getSizeMultiplier()).isLessThan(1.0);
        }

        @Test
        @DisplayName("Should BLOCK trades immediately after HIGH impact event (until processed)")
        void blocksAfterHighImpactEventUntilProcessed() {
            Instant now = Instant.now();
            Instant eventTime = now.minus(Duration.ofMinutes(3)); // 3 minutes ago

            EconomicEvent event = createHighImpactEvent("FOMC Rate Decision", Currency.USD, eventTime);
            calendarProvider.addEvent(event);

            TradeGatingDecision decision = checker.checkGating(InstrumentNewsMapper.ES, now);

            assertThat(decision.getAction()).isEqualTo(GatingAction.BLOCK);
            assertThat(decision.getReason()).contains("Waiting for");
        }

        @Test
        @DisplayName("Should REDUCE_SIZE after HIGH impact event is processed")
        void reduceSizeAfterProcessed() {
            Instant now = Instant.now();
            Instant eventTime = now.minus(Duration.ofMinutes(3)); // 3 minutes ago

            EconomicEvent event = createHighImpactEvent("FOMC Rate Decision", Currency.USD, eventTime);
            calendarProvider.addEvent(event);

            // Mark as processed
            checker.markReleaseProcessed(event.getId());

            TradeGatingDecision decision = checker.checkGating(InstrumentNewsMapper.ES, now);

            assertThat(decision.getAction()).isEqualTo(GatingAction.REDUCE_SIZE);
            assertThat(decision.getReason()).contains("Post-event volatility");
        }

        @Test
        @DisplayName("Should ALLOW trades far from HIGH impact event")
        void allowsFarFromHighImpactEvent() {
            Instant now = Instant.now();
            Instant eventTime = now.plus(Duration.ofMinutes(30)); // 30 minutes away

            EconomicEvent event = createHighImpactEvent("FOMC Rate Decision", Currency.USD, eventTime);
            calendarProvider.addEvent(event);

            TradeGatingDecision decision = checker.checkGating(InstrumentNewsMapper.ES, now);

            assertThat(decision.getAction()).isEqualTo(GatingAction.ALLOW);
        }
    }

    @Nested
    @DisplayName("MEDIUM Impact Event Gating")
    class MediumImpactTests {

        @Test
        @DisplayName("Should BLOCK trades 2 minutes before MEDIUM impact event")
        void blocksBeforeMediumImpactEvent() {
            Instant now = Instant.now();
            Instant eventTime = now.plus(Duration.ofMinutes(1)); // 1 minute away

            EconomicEvent event = createMediumImpactEvent("ISM Manufacturing PMI", Currency.USD, eventTime);
            calendarProvider.addEvent(event);

            TradeGatingDecision decision = checker.checkGating(InstrumentNewsMapper.ES, now);

            assertThat(decision.getAction()).isEqualTo(GatingAction.BLOCK);
            assertThat(decision.getReason()).contains("MEDIUM impact event");
        }

        @Test
        @DisplayName("Should REDUCE_SIZE 5 minutes before MEDIUM impact event")
        void reduceSizeBeforeMediumImpactEvent() {
            Instant now = Instant.now();
            Instant eventTime = now.plus(Duration.ofMinutes(4)); // 4 minutes away

            EconomicEvent event = createMediumImpactEvent("ISM Manufacturing PMI", Currency.USD, eventTime);
            calendarProvider.addEvent(event);

            TradeGatingDecision decision = checker.checkGating(InstrumentNewsMapper.ES, now);

            assertThat(decision.getAction()).isEqualTo(GatingAction.REDUCE_SIZE);
            assertThat(decision.getSizeMultiplier()).isEqualTo(0.7);
        }

        @Test
        @DisplayName("Should REDUCE_SIZE 5 minutes after MEDIUM impact event")
        void reduceSizeAfterMediumImpactEvent() {
            Instant now = Instant.now();
            Instant eventTime = now.minus(Duration.ofMinutes(3)); // 3 minutes ago

            EconomicEvent event = createMediumImpactEvent("ISM Manufacturing PMI", Currency.USD, eventTime);
            calendarProvider.addEvent(event);

            TradeGatingDecision decision = checker.checkGating(InstrumentNewsMapper.ES, now);

            assertThat(decision.getAction()).isEqualTo(GatingAction.REDUCE_SIZE);
        }
    }

    @Nested
    @DisplayName("LOW Impact Event Gating")
    class LowImpactTests {

        @Test
        @DisplayName("Should ALLOW trades near LOW impact event")
        void allowsNearLowImpactEvent() {
            Instant now = Instant.now();
            Instant eventTime = now.plus(Duration.ofMinutes(1));

            EconomicEvent event = createLowImpactEvent("Building Permits", Currency.USD, eventTime);
            calendarProvider.addEvent(event);

            TradeGatingDecision decision = checker.checkGating(InstrumentNewsMapper.ES, now);

            assertThat(decision.getAction()).isEqualTo(GatingAction.ALLOW);
        }
    }

    @Nested
    @DisplayName("Relevance-Based Gating")
    class RelevanceTests {

        @Test
        @DisplayName("Should not gate irrelevant events for instrument")
        void doesNotGateIrrelevantEvents() {
            Instant now = Instant.now();
            Instant eventTime = now.plus(Duration.ofMinutes(3));

            // Create a JPY event - minimal relevance for ES
            EconomicEvent event = EconomicEvent.builder()
                    .id("test-event")
                    .name("Japan GDP")
                    .currency(Currency.JPY)
                    .impact(EventImpact.HIGH)
                    .category(EventCategory.GDP_GROWTH)
                    .scheduledTime(eventTime)
                    .build();

            calendarProvider.addEvent(event);

            TradeGatingDecision decision = checker.checkGating(InstrumentNewsMapper.ES, now);

            // JPY GDP doesn't have enough relevance for ES
            assertThat(decision.getAction()).isEqualTo(GatingAction.ALLOW);
        }

        @Test
        @DisplayName("Should gate relevant events for instrument")
        void gatesRelevantEvents() {
            Instant now = Instant.now();
            Instant eventTime = now.plus(Duration.ofMinutes(3));

            // USD CPI is highly relevant for ES
            EconomicEvent event = createHighImpactEvent("US CPI MoM", Currency.USD, eventTime);
            calendarProvider.addEvent(event);

            TradeGatingDecision decision = checker.checkGating(InstrumentNewsMapper.ES, now);

            assertThat(decision.getAction()).isEqualTo(GatingAction.BLOCK);
        }
    }

    @Nested
    @DisplayName("Utility Method Tests")
    class UtilityTests {

        @Test
        @DisplayName("getUpcomingEventsForInstrument returns relevant events")
        void getUpcomingEventsReturnsRelevant() {
            Instant now = Instant.now();
            calendarProvider.setSimulatedTime(now);

            calendarProvider.addEvent(createHighImpactEvent("FOMC", Currency.USD, now.plus(Duration.ofHours(2))));
            calendarProvider.addEvent(createHighImpactEvent("ECB", Currency.EUR, now.plus(Duration.ofHours(3))));
            calendarProvider.addEvent(createHighImpactEvent("BOJ", Currency.JPY, now.plus(Duration.ofHours(4))));

            List<EconomicEvent> esEvents = checker.getUpcomingEventsForInstrument(InstrumentNewsMapper.ES, 24);

            // ES should see FOMC and ECB (high impact foreign events have some relevance)
            assertThat(esEvents).hasSize(2);
        }

        @Test
        @DisplayName("isReleaseProcessed tracks processed releases")
        void tracksProcessedReleases() {
            String eventId = "test-event-123";

            assertThat(checker.isReleaseProcessed(eventId)).isFalse();

            checker.markReleaseProcessed(eventId);

            assertThat(checker.isReleaseProcessed(eventId)).isTrue();
        }
    }

    private EconomicEvent createHighImpactEvent(String name, Currency currency, Instant scheduledTime) {
        return EconomicEvent.builder()
                .id("test-" + System.nanoTime())
                .name(name)
                .currency(currency)
                .impact(EventImpact.HIGH)
                .category(EventCategory.CENTRAL_BANK)
                .scheduledTime(scheduledTime)
                .build();
    }

    private EconomicEvent createMediumImpactEvent(String name, Currency currency, Instant scheduledTime) {
        return EconomicEvent.builder()
                .id("test-" + System.nanoTime())
                .name(name)
                .currency(currency)
                .impact(EventImpact.MEDIUM)
                .category(EventCategory.PMI_SURVEYS)
                .scheduledTime(scheduledTime)
                .build();
    }

    private EconomicEvent createLowImpactEvent(String name, Currency currency, Instant scheduledTime) {
        return EconomicEvent.builder()
                .id("test-" + System.nanoTime())
                .name(name)
                .currency(currency)
                .impact(EventImpact.LOW)
                .category(EventCategory.HOUSING)
                .scheduledTime(scheduledTime)
                .build();
    }
}
