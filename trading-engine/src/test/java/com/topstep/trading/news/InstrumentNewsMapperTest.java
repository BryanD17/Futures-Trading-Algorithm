package com.topstep.trading.news;

import com.topstep.trading.news.impact.InstrumentNewsMapper;
import com.topstep.trading.news.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for InstrumentNewsMapper - verifies all instrument/event mappings.
 */
class InstrumentNewsMapperTest {

    private InstrumentNewsMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new InstrumentNewsMapper();
    }

    @Nested
    @DisplayName("ES/NQ (Equity Indices) Relevance Tests")
    class EquityIndexTests {

        @Test
        @DisplayName("Fed decision has highest relevance for ES")
        void fedDecisionHighRelevanceForEs() {
            EconomicEvent event = createEvent("FOMC Rate Decision", Currency.USD, EventImpact.HIGH, EventCategory.CENTRAL_BANK);
            assertThat(mapper.getRelevance(event, InstrumentNewsMapper.ES)).isEqualTo(1.0);
            assertThat(mapper.getRelevance(event, InstrumentNewsMapper.NQ)).isEqualTo(1.0);
        }

        @Test
        @DisplayName("US CPI has very high relevance for ES")
        void cpiHighRelevanceForEs() {
            EconomicEvent event = createEvent("US CPI MoM", Currency.USD, EventImpact.HIGH, EventCategory.INFLATION);
            assertThat(mapper.getRelevance(event, InstrumentNewsMapper.ES)).isEqualTo(0.95);
        }

        @Test
        @DisplayName("NFP has very high relevance for ES")
        void nfpHighRelevanceForEs() {
            EconomicEvent event = createEvent("Nonfarm Payrolls", Currency.USD, EventImpact.HIGH, EventCategory.EMPLOYMENT);
            assertThat(mapper.getRelevance(event, InstrumentNewsMapper.ES)).isEqualTo(0.9);
        }

        @Test
        @DisplayName("EUR events have minimal relevance for ES")
        void eurEventsMinimalForEs() {
            EconomicEvent event = createEvent("ECB Rate Decision", Currency.EUR, EventImpact.HIGH, EventCategory.CENTRAL_BANK);
            assertThat(mapper.getRelevance(event, InstrumentNewsMapper.ES)).isEqualTo(0.2);
        }

        @Test
        @DisplayName("Low impact EUR events have zero relevance for ES")
        void lowImpactEurZeroForEs() {
            EconomicEvent event = createEvent("German Factory Orders", Currency.EUR, EventImpact.LOW, EventCategory.OTHER);
            assertThat(mapper.getRelevance(event, InstrumentNewsMapper.ES)).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("GC (Gold) Relevance Tests")
    class GoldTests {

        @Test
        @DisplayName("Fed decision has highest relevance for Gold")
        void fedDecisionHighRelevanceForGold() {
            EconomicEvent event = createEvent("FOMC Rate Decision", Currency.USD, EventImpact.HIGH, EventCategory.CENTRAL_BANK);
            assertThat(mapper.getRelevance(event, InstrumentNewsMapper.GC)).isEqualTo(1.0);
        }

        @Test
        @DisplayName("US CPI has very high relevance for Gold")
        void cpiHighRelevanceForGold() {
            EconomicEvent event = createEvent("US CPI MoM", Currency.USD, EventImpact.HIGH, EventCategory.INFLATION);
            assertThat(mapper.getRelevance(event, InstrumentNewsMapper.GC)).isEqualTo(0.95);
        }

        @Test
        @DisplayName("High impact non-USD events have some relevance for Gold (safe haven)")
        void nonUsdEventsHaveSomeRelevance() {
            EconomicEvent event = createEvent("ECB Rate Decision", Currency.EUR, EventImpact.HIGH, EventCategory.CENTRAL_BANK);
            assertThat(mapper.getRelevance(event, InstrumentNewsMapper.GC)).isEqualTo(0.2);
        }
    }

    @Nested
    @DisplayName("Directional Sign Tests")
    class DirectionalSignTests {

        @Test
        @DisplayName("Hot inflation is bearish for equities")
        void hotInflationBearishForEquities() {
            EconomicEvent event = createEvent("US CPI MoM", Currency.USD, EventImpact.HIGH, EventCategory.INFLATION);
            assertThat(mapper.getDirectionalSign(event, InstrumentNewsMapper.ES)).isEqualTo(-1);
        }

        @Test
        @DisplayName("Hawkish Fed is bearish for gold")
        void hawkishFedBearishForGold() {
            EconomicEvent event = createEvent("FOMC Rate Decision", Currency.USD, EventImpact.HIGH, EventCategory.CENTRAL_BANK);
            assertThat(mapper.getDirectionalSign(event, InstrumentNewsMapper.GC)).isEqualTo(-1);
        }

    }

    @Nested
    @DisplayName("Affected Instruments Tests")
    class AffectedInstrumentsTests {

        @Test
        @DisplayName("Fed decision affects all instruments")
        void fedDecisionAffectsAll() {
            EconomicEvent event = createEvent("FOMC Rate Decision", Currency.USD, EventImpact.HIGH, EventCategory.CENTRAL_BANK);
            List<String> affected = mapper.getAffectedInstruments(event);
            assertThat(affected).contains(
                    InstrumentNewsMapper.ES, InstrumentNewsMapper.NQ,
                    InstrumentNewsMapper.GC);
        }
    }

    private EconomicEvent createEvent(String name, Currency currency, EventImpact impact, EventCategory category) {
        return EconomicEvent.builder()
                .id("test-" + System.nanoTime())
                .name(name)
                .currency(currency)
                .impact(impact)
                .category(category)
                .scheduledTime(Instant.now())
                .build();
    }
}
