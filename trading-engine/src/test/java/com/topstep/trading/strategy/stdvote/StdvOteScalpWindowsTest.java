package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.domain.Candle;
import com.topstep.trading.event.Event;
import com.topstep.trading.event.EventBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SA4 time-window tests. Scalp mode replaces the legacy
 * (killzone ∪ Silver Bullet) union with full KillzoneClock killzones:
 * NY AM 9:45–12:30 ET, NY PM 13:45–16:00 ET, and (MGC only) the London
 * PRIME window 3:00–5:00 ET (config-driven — KillzoneClock has no London
 * phase API). SilverBulletClock stops being a hard gate in scalp mode but
 * remains a raid-scoring input. Legacy windows are byte-for-byte unchanged.
 */
@DisplayName("StdvOteScalpWindowsTest (SA4 killzone windows)")
class StdvOteScalpWindowsTest {

    private static final ZoneId ET = ZoneId.of("America/New_York");
    /** A Tuesday. */
    private static final LocalDate DAY = LocalDate.of(2026, 6, 16);

    static final class NullBus extends EventBus {
        @Override public void publish(Event event) { /* drop */ }
    }

    @AfterEach
    void cleanup() {
        System.clearProperty(ScalpConfig.ENABLED_PROPERTY);
        System.clearProperty(ScalpConfig.ALL_SESSIONS_PROPERTY);
        System.clearProperty(ScalpConfig.LONDON_PRIME_START_ET_PROPERTY);
        System.clearProperty(ScalpConfig.LONDON_PRIME_END_ET_PROPERTY);
        StdvOteRegistry.unregister("MNQ");
        StdvOteRegistry.unregister("MGC");
    }

    private static Instant et(int hour, int minute) {
        return DAY.atTime(LocalTime.of(hour, minute)).atZone(ET).toInstant();
    }

    /**
     * Feed one candle at the given instant and report the M3 input the
     * validator would see ({@code ctx.killzoneOpen}).
     */
    private static boolean killzoneOpenAt(String symbol, boolean scalp, Instant ts) {
        if (scalp) {
            System.setProperty(ScalpConfig.ENABLED_PROPERTY, "true");
        } else {
            System.clearProperty(ScalpConfig.ENABLED_PROPERTY);
        }
        // This suite documents the PRIME KILLZONE boundaries. scalp.allSessions
        // (default true since 2026-07-08) widens the M3 window to all market
        // hours; pin it OFF here so the killzone-boundary assertions keep
        // testing the killzone logic. ScalpAllSessionsTest covers the widened
        // window.
        System.setProperty(ScalpConfig.ALL_SESSIONS_PROPERTY, "false");
        try {
            StdvOteRunnerStrategy s = new StdvOteRunnerStrategy(symbol, null, new NullBus());
            s.onCandle(new Candle(symbol, ts, 21000, 21001, 20999, 21000.5, 100), null);
            return s.getSetupContext().killzoneOpen;
        } finally {
            StdvOteRegistry.unregister(symbol);
        }
    }

    @Test
    @DisplayName("scalp MNQ: full NY AM and NY PM killzones are open")
    void scalpNyKillzonesOpen() {
        assertThat(killzoneOpenAt("MNQ", true, et(9, 45))).isTrue();   // NY AM open
        assertThat(killzoneOpenAt("MNQ", true, et(11, 45))).isTrue();  // mid NY AM (outside SB)
        assertThat(killzoneOpenAt("MNQ", true, et(12, 29))).isTrue();  // NY AM last minute
        assertThat(killzoneOpenAt("MNQ", true, et(13, 45))).isTrue();  // NY PM open
        assertThat(killzoneOpenAt("MNQ", true, et(15, 59))).isTrue();  // NY PM last minute
    }

    @Test
    @DisplayName("scalp MNQ: closed outside killzones — lunch gap, pre-open, and the SB-only 3–4 AM window")
    void scalpClosedOutsideKillzones() {
        assertThat(killzoneOpenAt("MNQ", true, et(9, 44))).isFalse();   // pre killzone
        assertThat(killzoneOpenAt("MNQ", true, et(12, 30))).isFalse();  // NY AM closed
        assertThat(killzoneOpenAt("MNQ", true, et(13, 0))).isFalse();   // lunch gap
        // Silver Bullet London-open window (3:00–4:00 ET) is NO LONGER a
        // hard gate in scalp mode (it stays a raid-scoring input)...
        assertThat(killzoneOpenAt("MNQ", true, et(3, 30))).isFalse();
        // ...but legacy keeps it — byte-for-byte unchanged.
        assertThat(killzoneOpenAt("MNQ", false, et(3, 30))).isTrue();
    }

    @Test
    @DisplayName("scalp: no entry window between the 16:00 ET killzone close and the 15:10 CT (16:10 ET) flatten")
    void noWindowBetweenCloseAndFlatten() {
        // Flatten-time verification (documented in SA4_frequency_gates.md):
        // the NY PM scalp window closes at 16:00 ET = 15:00 CT, which is
        // BEFORE the Topstep flatten deadline 15:10 CT = 16:10 ET. The M3
        // gate therefore stops entries 10 minutes before the flatten — no
        // scalp entry can slip in between.
        assertThat(killzoneOpenAt("MNQ", true, et(16, 0))).isFalse();
        assertThat(killzoneOpenAt("MNQ", true, et(16, 5))).isFalse();
        assertThat(killzoneOpenAt("MNQ", true, et(16, 9))).isFalse();
        assertThat(killzoneOpenAt("MNQ", true, et(16, 10))).isFalse();
    }

    @Test
    @DisplayName("scalp MGC: London restricted to the PRIME window 3:00–5:00 ET (legacy keeps 3:00–12:00)")
    void mgcLondonPrimeOnly() {
        assertThat(killzoneOpenAt("MGC", true, et(3, 0))).isTrue();    // prime open
        assertThat(killzoneOpenAt("MGC", true, et(4, 30))).isTrue();   // inside prime
        assertThat(killzoneOpenAt("MGC", true, et(5, 0))).isFalse();   // prime closed
        assertThat(killzoneOpenAt("MGC", true, et(8, 0))).isFalse();   // London non-prime
        assertThat(killzoneOpenAt("MGC", true, et(2, 59))).isFalse();  // pre London
        assertThat(killzoneOpenAt("MGC", true, et(10, 0))).isTrue();   // NY AM still applies
        // Legacy MGC keeps the full London session — unchanged.
        assertThat(killzoneOpenAt("MGC", false, et(8, 0))).isTrue();
        assertThat(killzoneOpenAt("MGC", false, et(4, 30))).isTrue();
    }

    @Test
    @DisplayName("scalp MGC: London prime window is config-driven (scalp.londonPrimeStartEt/EndEt)")
    void mgcLondonPrimeConfigurable() {
        System.setProperty(ScalpConfig.LONDON_PRIME_START_ET_PROPERTY, "03:30");
        System.setProperty(ScalpConfig.LONDON_PRIME_END_ET_PROPERTY, "04:30");
        try {
            assertThat(killzoneOpenAt("MGC", true, et(3, 15))).isFalse(); // before custom start
            assertThat(killzoneOpenAt("MGC", true, et(4, 0))).isTrue();   // inside custom prime
            assertThat(killzoneOpenAt("MGC", true, et(4, 45))).isFalse(); // after custom end
        } finally {
            System.clearProperty(ScalpConfig.LONDON_PRIME_START_ET_PROPERTY);
            System.clearProperty(ScalpConfig.LONDON_PRIME_END_ET_PROPERTY);
        }
    }

    @Test
    @DisplayName("scalp MNQ: London prime does NOT open indices (MGC-only window)")
    void londonPrimeIsMgcOnly() {
        assertThat(killzoneOpenAt("MNQ", true, et(3, 30))).isFalse();
        assertThat(killzoneOpenAt("MNQ", true, et(4, 30))).isFalse();
    }
}
