package com.topstep.trading.strategy.stdvote;

import com.topstep.trading.strategy.TradeTier;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * All-sessions trading (scalp.allSessions) and the killzone size boost
 * (scalp.killzoneSizeBoost), added 2026-07-08 by owner directive.
 *
 * <p>Invariants under test:
 * <ul>
 *   <li>the widened entry window NEVER admits the 14:45–17:00 CT daily
 *       block (flatten guarantee + Globex halt) or the weekend gap;</li>
 *   <li>the killzone boost rides the sizer's multiplier slot, so tier caps,
 *       topstepMicroMax, and the [5, 20] band all still bind.</li>
 * </ul>
 */
class ScalpAllSessionsTest {

    private static final ZoneId CT = ZoneId.of("America/Chicago");
    // Known weekdays: 2026-07-07 = Tuesday, 2026-07-10 = Friday,
    // 2026-07-11 = Saturday, 2026-07-12 = Sunday.
    private static final LocalDate TUE = LocalDate.of(2026, 7, 7);
    private static final LocalDate FRI = LocalDate.of(2026, 7, 10);
    private static final LocalDate SAT = LocalDate.of(2026, 7, 11);
    private static final LocalDate SUN = LocalDate.of(2026, 7, 12);

    private static ZonedDateTime at(LocalDate d, int h, int m) {
        return ZonedDateTime.of(d, LocalTime.of(h, m), CT);
    }

    @Test
    void weekdaySessionsAreOpenOutsideTheDailyBlock() {
        assertTrue(StdvOteRunnerStrategy.allSessionEntryWindow(at(TUE, 3, 0)),
                "overnight/London hours trade");
        assertTrue(StdvOteRunnerStrategy.allSessionEntryWindow(at(TUE, 10, 0)),
                "NY morning trades");
        assertTrue(StdvOteRunnerStrategy.allSessionEntryWindow(at(TUE, 14, 44)),
                "last minute before the block trades");
        assertTrue(StdvOteRunnerStrategy.allSessionEntryWindow(at(TUE, 17, 0)),
                "the 17:00 CT reopen trades");
        assertTrue(StdvOteRunnerStrategy.allSessionEntryWindow(at(TUE, 20, 30)),
                "the Asia session trades");
    }

    @Test
    void dailyNoEntryBlockIsNeverAdmitted() {
        // 14:45 CT (pre-flatten) through 16:59 CT (Globex halt) — no entries.
        assertFalse(StdvOteRunnerStrategy.allSessionEntryWindow(at(TUE, 14, 45)));
        assertFalse(StdvOteRunnerStrategy.allSessionEntryWindow(at(TUE, 15, 10)),
                "the flatten minute itself must be blocked");
        assertFalse(StdvOteRunnerStrategy.allSessionEntryWindow(at(TUE, 16, 30)),
                "the Globex halt must be blocked");
        assertFalse(StdvOteRunnerStrategy.allSessionEntryWindow(at(TUE, 16, 59)));
    }

    @Test
    void weekendGapIsNeverAdmitted() {
        assertFalse(StdvOteRunnerStrategy.allSessionEntryWindow(at(FRI, 14, 45)),
                "Friday close of entries at 14:45 CT");
        assertFalse(StdvOteRunnerStrategy.allSessionEntryWindow(at(FRI, 20, 0)),
                "no Friday-evening session exists");
        assertFalse(StdvOteRunnerStrategy.allSessionEntryWindow(at(SAT, 10, 0)));
        assertFalse(StdvOteRunnerStrategy.allSessionEntryWindow(at(SUN, 16, 59)),
                "Sunday before the reopen is closed");
        assertTrue(StdvOteRunnerStrategy.allSessionEntryWindow(at(SUN, 17, 0)),
                "Sunday 17:00 CT reopen trades");
        assertTrue(StdvOteRunnerStrategy.allSessionEntryWindow(at(FRI, 10, 0)),
                "Friday morning trades normally");
    }

    @Test
    void killzoneBoostCannotExceedAnyCap() {
        StdvOteSizer sizer = new StdvOteSizer();
        TradeableInstrument.Spec mnq = TradeableInstrument.of(TradeableInstrument.Symbol.MNQ);

        // Deep risk budget so the raw size is far above every cap: the boost
        // must still be clamped to the TIER_1 cap of 8.
        StdvOteSizer.SizingDecision boosted = sizer.decide(
                new StdvOteSizer.SizeRequest(20000.0, 19990.0, mnq, TradeTier.TIER_1),
                new StdvOteSizer.SizeContext(50_000, 46_000, 200, 0.12,
                        /* killzone boost */ 1.5, 20));
        assertEquals(8, boosted.contracts(),
                "boost must clamp to the tier cap, never exceed it");

        // Modest budget where the base is 6: 6 * 1.5 = 9, under the TIER_2
        // cap of 12 -> the boost takes effect exactly.
        // per-contract risk = 10 pts * $2 = $20; budget $132 -> raw 6.
        StdvOteSizer.SizingDecision base = sizer.decide(
                new StdvOteSizer.SizeRequest(20000.0, 19990.0, mnq, TradeTier.TIER_2),
                new StdvOteSizer.SizeContext(47_300, 46_000, 200, 0.12,
                        1.0, 20));
        StdvOteSizer.SizingDecision inKz = sizer.decide(
                new StdvOteSizer.SizeRequest(20000.0, 19990.0, mnq, TradeTier.TIER_2),
                new StdvOteSizer.SizeContext(47_300, 46_000, 200, 0.12,
                        1.5, 20));
        assertEquals(6, base.contracts());
        assertEquals(9, inKz.contracts(),
                "in-killzone size is base * 1.5 when no cap binds");

        // The [5, 20] band still binds the boosted result.
        assertTrue(inKz.contracts() >= 5 && inKz.contracts() <= 20);
    }

    @Test
    void configClampsTheBoostToSaneRange() {
        String prev = System.getProperty(ScalpConfig.KILLZONE_SIZE_BOOST_PROPERTY);
        try {
            System.setProperty(ScalpConfig.KILLZONE_SIZE_BOOST_PROPERTY, "5.0");
            assertEquals(2.0, ScalpConfig.killzoneSizeBoost(), 1e-9,
                    "boost is capped at 2.0");
            System.setProperty(ScalpConfig.KILLZONE_SIZE_BOOST_PROPERTY, "0.2");
            assertEquals(1.0, ScalpConfig.killzoneSizeBoost(), 1e-9,
                    "boost never shrinks size below normal");
            System.clearProperty(ScalpConfig.KILLZONE_SIZE_BOOST_PROPERTY);
            assertEquals(1.5, ScalpConfig.killzoneSizeBoost(), 1e-9,
                    "default boost is 1.5");
        } finally {
            if (prev != null) {
                System.setProperty(ScalpConfig.KILLZONE_SIZE_BOOST_PROPERTY, prev);
            } else {
                System.clearProperty(ScalpConfig.KILLZONE_SIZE_BOOST_PROPERTY);
            }
        }
    }
}
