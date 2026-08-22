package com.topstep.trading.strategy;

import com.topstep.trading.domain.Candle;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The recency window that {@code stdvote.displacement.recentBars} widens.
 *
 * <p>Context (LIVE_OBS_02, 2026-08-18): displacement fires on only ~5-7% of live
 * 5m bars, so the default 5-bar (25-minute) window after a sweep is empty about
 * three quarters of the time. {@code no-recent-displacement} was 83% (MNQ) /
 * 69% (MGC) of all stalls across nine sweeps that produced zero armed setups.
 *
 * <p>These tests pin the window's behaviour so a widened setting demonstrably
 * accepts older displacements and the default demonstrably still rejects them.
 */
class DisplacementRecencyWindowTest {

    private static final Instant T0 = Instant.parse("2026-08-18T14:00:00Z");

    private static Candle bar(int i, double o, double h, double l, double c) {
        return new Candle("MNQ", T0.plus(i, ChronoUnit.MINUTES), o, h, l, c, 100);
    }

    /**
     * Feed quiet bars to establish a small average range, then one large
     * body-dominated up-candle that must register as a bullish displacement.
     */
    private static DisplacementDetector detectorWithOneBullishDisplacement() {
        DisplacementDetector d = new DisplacementDetector(20, 1.5, 0.65, "TEST");
        double px = 20000;
        for (int i = 0; i < 20; i++) {
            d.update(bar(i, px, px + 1, px - 1, px));
        }
        // Range 40 vs ~2 average, body 100% — unambiguous displacement.
        d.update(bar(20, px, px + 40, px, px + 40));
        return d;
    }

    @Test
    void freshDisplacementIsSeenByBothTheDefaultAndAWiderWindow() {
        DisplacementDetector d = detectorWithOneBullishDisplacement();
        assertTrue(d.hasRecentDisplacement(5, true), "default window should see it immediately");
        assertTrue(d.hasRecentDisplacement(12, true), "wider window should see it too");
    }

    @Test
    void theDefaultWindowRejectsADisplacementThatHasAgedOut() {
        DisplacementDetector d = detectorWithOneBullishDisplacement();
        double px = 20040;
        // Eight quiet bars — beyond the 5-bar default, inside a 12-bar window.
        for (int i = 21; i < 29; i++) {
            d.update(bar(i, px, px + 1, px - 1, px));
        }
        assertFalse(d.hasRecentDisplacement(5, true),
                "5 bars later the default window must have expired — this is the "
                        + "no-recent-displacement stall that dominated LIVE_OBS_02");
        assertTrue(d.hasRecentDisplacement(12, true),
                "a widened window is exactly what recovers that displacement");
    }

    @Test
    void directionStillBindsRegardlessOfWindowWidth() {
        // Widening recency must NOT smuggle in counter-bias displacements —
        // bias alignment is the strategy's premise, not a strictness dial.
        DisplacementDetector d = detectorWithOneBullishDisplacement();
        assertTrue(d.hasRecentDisplacement(12, true));
        assertFalse(d.hasRecentDisplacement(12, false),
                "a bullish displacement must never satisfy a bearish requirement");
    }

    @Test
    void aWindowOfOneStillSeesTheDisplacementOnTheBarItFired() {
        DisplacementDetector d = detectorWithOneBullishDisplacement();
        assertTrue(d.hasRecentDisplacement(1, true));
    }
}
