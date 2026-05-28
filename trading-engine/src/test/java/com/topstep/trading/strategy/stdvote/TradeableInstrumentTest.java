package com.topstep.trading.strategy.stdvote;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SA5 tests for {@link TradeableInstrument} registry. The registry is the
 * single source of truth for which symbols may be routed. These tests pin
 * the constraints that prevent the engine from silently routing full-size
 * NQ/ES/GC under any code path.
 */
@DisplayName("TradeableInstrument registry")
class TradeableInstrumentTest {

    @Test
    @DisplayName("the only tradeable symbols are MNQ, MES, MGC")
    void onlyThreeSymbols() {
        assertThat(TradeableInstrument.Symbol.values())
                .containsExactly(
                        TradeableInstrument.Symbol.MNQ,
                        TradeableInstrument.Symbol.MES,
                        TradeableInstrument.Symbol.MGC);
        assertThat(TradeableInstrument.all()).hasSize(3);
    }

    @Test
    @DisplayName("full-size NQ is rejected by resolve()")
    void rejectsFullSizeNq() {
        Optional<TradeableInstrument.Symbol> r = TradeableInstrument.resolve("NQ");
        assertThat(r).isEmpty();
        assertThat(TradeableInstrument.isTradeable("NQ")).isFalse();
    }

    @Test
    @DisplayName("full-size ES, GC are rejected")
    void rejectsFullSizeEsGc() {
        assertThat(TradeableInstrument.isTradeable("ES")).isFalse();
        assertThat(TradeableInstrument.isTradeable("GC")).isFalse();
    }

    @Test
    @DisplayName("null and empty inputs are rejected")
    void rejectsNullAndEmpty() {
        assertThat(TradeableInstrument.isTradeable(null)).isFalse();
        assertThat(TradeableInstrument.isTradeable("")).isFalse();
        assertThat(TradeableInstrument.isTradeable("   ")).isFalse();
    }

    @Test
    @DisplayName("case-insensitive resolution for the three allowed symbols")
    void caseInsensitive() {
        assertThat(TradeableInstrument.resolve("mnq"))
                .contains(TradeableInstrument.Symbol.MNQ);
        assertThat(TradeableInstrument.resolve("MeS"))
                .contains(TradeableInstrument.Symbol.MES);
        assertThat(TradeableInstrument.resolve(" mgc "))
                .contains(TradeableInstrument.Symbol.MGC);
    }

    @Test
    @DisplayName("MNQ spec: tick 0.25, value $0.50, point $2.00, raid min 5, SMT MES")
    void mnqSpec() {
        TradeableInstrument.Spec s = TradeableInstrument.of(TradeableInstrument.Symbol.MNQ);
        assertThat(s.tickSize()).isEqualTo(0.25);
        assertThat(s.tickValue()).isEqualTo(0.50);
        assertThat(s.pointValue()).isEqualTo(2.00);
        assertThat(s.minMicros()).isEqualTo(5);
        assertThat(s.maxMicros()).isEqualTo(20);
        assertThat(s.raidMinQuality()).isEqualTo(5);
        assertThat(s.correlate()).isEqualTo("MES");
    }

    @Test
    @DisplayName("MES spec: tick 0.25, value $1.25, point $5.00, raid min 5, SMT MNQ")
    void mesSpec() {
        TradeableInstrument.Spec s = TradeableInstrument.of(TradeableInstrument.Symbol.MES);
        assertThat(s.tickSize()).isEqualTo(0.25);
        assertThat(s.tickValue()).isEqualTo(1.25);
        assertThat(s.pointValue()).isEqualTo(5.00);
        assertThat(s.raidMinQuality()).isEqualTo(5);
        assertThat(s.correlate()).isEqualTo("MNQ");
    }

    @Test
    @DisplayName("MGC spec: tick 0.10, value $1.00, point $10.00, raid min 6 (stricter)")
    void mgcSpec() {
        TradeableInstrument.Spec s = TradeableInstrument.of(TradeableInstrument.Symbol.MGC);
        assertThat(s.tickSize()).isEqualTo(0.10);
        assertThat(s.tickValue()).isEqualTo(1.00);
        assertThat(s.pointValue()).isEqualTo(10.00);
        assertThat(s.raidMinQuality())
                .as("MGC has a stricter raid floor than MNQ/MES")
                .isEqualTo(6);
    }

    @Test
    @DisplayName("every spec satisfies pointValue == tickValue / tickSize (asserted in ctor)")
    void pointValueIdentity() {
        for (TradeableInstrument.Spec s : TradeableInstrument.all()) {
            double expected = s.tickValue() / s.tickSize();
            assertThat(Math.abs(s.pointValue() - expected))
                    .as("instrument %s violates the identity", s.symbol())
                    .isLessThan(1e-9);
        }
    }

    @Test
    @DisplayName("every spec sits within the hard [5, 20] micro band")
    void specsWithinHardBand() {
        for (TradeableInstrument.Spec s : TradeableInstrument.all()) {
            assertThat(s.minMicros()).isGreaterThanOrEqualTo(5);
            assertThat(s.maxMicros()).isLessThanOrEqualTo(20);
            assertThat(s.minMicros()).isLessThanOrEqualTo(s.maxMicros());
        }
    }

    @Test
    @DisplayName("roundToTick lands on the grid for MNQ (0.25) and MGC (0.10)")
    void roundsToTick() {
        TradeableInstrument.Spec mnq = TradeableInstrument.of(TradeableInstrument.Symbol.MNQ);
        assertThat(mnq.roundToTick(20100.13)).isEqualTo(20100.25);
        assertThat(mnq.roundToTick(20100.12)).isEqualTo(20100.00);

        TradeableInstrument.Spec mgc = TradeableInstrument.of(TradeableInstrument.Symbol.MGC);
        assertThat(mgc.roundToTick(2400.07)).isEqualTo(2400.10);
        assertThat(mgc.roundToTick(2400.04)).isEqualTo(2400.00);
    }
}
