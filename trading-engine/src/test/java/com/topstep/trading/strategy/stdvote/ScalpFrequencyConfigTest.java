package com.topstep.trading.strategy.stdvote;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SA4 config surface: the new {@code scalp.*} frequency/gate tunables.
 */
@DisplayName("ScalpFrequencyConfigTest (SA4 scalp.* tunables)")
class ScalpFrequencyConfigTest {

    @AfterEach
    void cleanup() {
        System.clearProperty(ScalpConfig.MIN_RAID_SCORE_PROPERTY);
        System.clearProperty(ScalpConfig.REARM_COOLDOWN_BARS_PROPERTY);
        System.clearProperty(ScalpConfig.LONDON_PRIME_START_ET_PROPERTY);
        System.clearProperty(ScalpConfig.LONDON_PRIME_END_ET_PROPERTY);
        System.clearProperty(ScalpConfig.SIZER_SAFETY_CUSHION_PROPERTY);
    }

    @Test
    @DisplayName("defaults: minRaidScore 6, cooldown 5 bars, London prime 03:00-05:00 ET, cushion $200")
    void defaults() {
        assertThat(ScalpConfig.minRaidScore()).isEqualTo(6);
        assertThat(ScalpConfig.rearmCooldownBars()).isEqualTo(5);
        assertThat(ScalpConfig.londonPrimeStartEt()).isEqualTo(LocalTime.of(3, 0));
        assertThat(ScalpConfig.londonPrimeEndEt()).isEqualTo(LocalTime.of(5, 0));
        assertThat(ScalpConfig.sizerSafetyCushion()).isEqualTo(200.0);
    }

    @Test
    @DisplayName("every tunable is property-driven")
    void overrides() {
        System.setProperty(ScalpConfig.MIN_RAID_SCORE_PROPERTY, "7");
        System.setProperty(ScalpConfig.REARM_COOLDOWN_BARS_PROPERTY, "10");
        System.setProperty(ScalpConfig.LONDON_PRIME_START_ET_PROPERTY, "03:30");
        System.setProperty(ScalpConfig.LONDON_PRIME_END_ET_PROPERTY, "04:45");
        System.setProperty(ScalpConfig.SIZER_SAFETY_CUSHION_PROPERTY, "350.5");

        assertThat(ScalpConfig.minRaidScore()).isEqualTo(7);
        assertThat(ScalpConfig.rearmCooldownBars()).isEqualTo(10);
        assertThat(ScalpConfig.londonPrimeStartEt()).isEqualTo(LocalTime.of(3, 30));
        assertThat(ScalpConfig.londonPrimeEndEt()).isEqualTo(LocalTime.of(4, 45));
        assertThat(ScalpConfig.sizerSafetyCushion()).isEqualTo(350.5);
    }

    @Test
    @DisplayName("invalid values fall back to safe defaults (never crash the runner)")
    void invalidValuesFallBack() {
        System.setProperty(ScalpConfig.MIN_RAID_SCORE_PROPERTY, "not-a-number");
        System.setProperty(ScalpConfig.REARM_COOLDOWN_BARS_PROPERTY, "-3");
        System.setProperty(ScalpConfig.LONDON_PRIME_START_ET_PROPERTY, "3 o'clock");
        System.setProperty(ScalpConfig.SIZER_SAFETY_CUSHION_PROPERTY, "$$$");

        assertThat(ScalpConfig.minRaidScore()).isEqualTo(6);
        assertThat(ScalpConfig.rearmCooldownBars()).isZero(); // clamped at 0
        assertThat(ScalpConfig.londonPrimeStartEt()).isEqualTo(LocalTime.of(3, 0));
        assertThat(ScalpConfig.sizerSafetyCushion()).isEqualTo(200.0);
    }
}
