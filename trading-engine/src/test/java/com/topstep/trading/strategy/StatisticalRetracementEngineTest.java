package com.topstep.trading.strategy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.assertj.core.api.Assertions.*;

@DisplayName("StatisticalRetracementEngine Tests")
class StatisticalRetracementEngineTest {

    private StatisticalRetracementEngine engine;

    @BeforeEach
    void setUp() {
        engine = new StatisticalRetracementEngine("NQ", 40);
    }

    @Test
    @DisplayName("Falls back to Fibonacci OTE when not initialized")
    void fallbackToFibWhenUninitialized() {
        assertThat(engine.isInitialized()).isFalse();
        assertThat(engine.getConfidence()).isEqualTo(StatisticalRetracementEngine.EntryConfidence.UNINITIALIZED);

        // Bullish entry zone should use Fib 0.62-0.705
        double swingLevel = 19800.0;
        double impulseRange = 100.0;
        double[] zone = engine.getEntryZone(swingLevel, impulseRange, true);

        // entryZoneLow = 19800 + 100 * 0.62 = 19862
        assertThat(zone[0]).isCloseTo(19862.0, within(0.01));
        // entryZoneHigh = 19800 + 100 * 0.705 = 19870.5
        assertThat(zone[2]).isCloseTo(19870.5, within(0.01));
        // optimalEntry = midpoint
        assertThat(zone[1]).isCloseTo((19862.0 + 19870.5) / 2.0, within(0.01));
    }

    @Test
    @DisplayName("Not initialized with fewer than 15 pullbacks")
    void notInitializedBelow15() {
        for (int i = 0; i < 14; i++) {
            engine.recordPullback(100.0, 65.0, 5, true);
        }
        assertThat(engine.isInitialized()).isFalse();
    }

    @Test
    @DisplayName("Initialized after 15 continued pullbacks")
    void initializedAfter15Continued() {
        for (int i = 0; i < 15; i++) {
            engine.recordPullback(100.0, 65.0, 5, true);
        }
        assertThat(engine.isInitialized()).isTrue();
    }

    @Test
    @DisplayName("Non-continued pullbacks don't count toward initialization")
    void nonContinuedDontCount() {
        for (int i = 0; i < 20; i++) {
            engine.recordPullback(100.0, 65.0, 5, false);
        }
        assertThat(engine.isInitialized()).isFalse();
    }

    @Test
    @DisplayName("Mean and stdDev match hand-computed values")
    void meanAndStdDevComputation() {
        // Feed 20 pullbacks: 10 with depth 0.60, 10 with depth 0.70
        // impulseRange=100, so pullbackRange=60 gives depth=0.60; pullbackRange=70 gives depth=0.70
        for (int i = 0; i < 10; i++) {
            engine.recordPullback(100.0, 60.0, 5, true);
        }
        for (int i = 0; i < 10; i++) {
            engine.recordPullback(100.0, 70.0, 5, true);
        }

        assertThat(engine.isInitialized()).isTrue();
        assertThat(engine.getMeanDepth()).isCloseTo(0.65, within(0.001));
        assertThat(engine.getStdDevDepth()).isCloseTo(0.05, within(0.001));
    }

    @Test
    @DisplayName("Entry zone uses statistical values when initialized")
    void entryZoneUsesStatistics() {
        // mean=0.65, stddev=0.05
        for (int i = 0; i < 10; i++) {
            engine.recordPullback(100.0, 60.0, 5, true);
        }
        for (int i = 0; i < 10; i++) {
            engine.recordPullback(100.0, 70.0, 5, true);
        }

        double swingLevel = 19800.0;
        double impulseRange = 100.0;
        double[] zone = engine.getEntryZone(swingLevel, impulseRange, true);

        // zoneLowPct = 0.65 - 0.5*0.05 = 0.625
        // zoneHighPct = 0.65 + 0.5*0.05 = 0.675
        double expectedLow = 19800.0 + 100.0 * 0.625;
        double expectedHigh = 19800.0 + 100.0 * 0.675;
        double expectedMid = (expectedLow + expectedHigh) / 2.0;

        assertThat(zone[0]).isCloseTo(expectedLow, within(0.1));
        assertThat(zone[2]).isCloseTo(expectedHigh, within(0.1));
        assertThat(zone[1]).isCloseTo(expectedMid, within(0.1));
    }

    @Test
    @DisplayName("Bearish entry zone is computed correctly")
    void bearishEntryZone() {
        for (int i = 0; i < 10; i++) {
            engine.recordPullback(100.0, 60.0, 5, true);
        }
        for (int i = 0; i < 10; i++) {
            engine.recordPullback(100.0, 70.0, 5, true);
        }

        double swingLevel = 20100.0;
        double impulseRange = 100.0;
        double[] zone = engine.getEntryZone(swingLevel, impulseRange, false);

        // For bearish: entryZoneHigh = swing - impulse * zoneLowPct
        // zoneLowPct = 0.625, zoneHighPct = 0.675
        double expectedHigh = 20100.0 - 100.0 * 0.625;
        double expectedLow = 20100.0 - 100.0 * 0.675;

        assertThat(zone[2]).isCloseTo(expectedHigh, within(0.1));
        assertThat(zone[0]).isCloseTo(expectedLow, within(0.1));
    }

    @Test
    @DisplayName("hasFibConfluence returns true when mean near 0.618")
    void fibConfluenceNear618() {
        // Feed pullbacks that average near 0.618
        // 15 pullbacks at depth 0.618 exactly
        for (int i = 0; i < 15; i++) {
            engine.recordPullback(100.0, 61.8, 5, true);
        }

        assertThat(engine.isInitialized()).isTrue();
        assertThat(engine.getMeanDepth()).isCloseTo(0.618, within(0.001));
        assertThat(engine.hasFibConfluence()).isTrue();
    }

    @Test
    @DisplayName("hasFibConfluence returns false when mean far from all Fib levels")
    void noFibConfluence() {
        // Mean depth around 0.40 — far from all Fib levels
        for (int i = 0; i < 15; i++) {
            engine.recordPullback(100.0, 40.0, 5, true);
        }

        assertThat(engine.isInitialized()).isTrue();
        assertThat(engine.getMeanDepth()).isCloseTo(0.40, within(0.001));
        assertThat(engine.hasFibConfluence()).isFalse();
    }

    @Test
    @DisplayName("Zone widens when stdDev increases")
    void zoneWidensWithHigherStdDev() {
        // Low stddev: all pullbacks at 0.65
        StatisticalRetracementEngine tightEngine = new StatisticalRetracementEngine("NQ", 40);
        for (int i = 0; i < 20; i++) {
            tightEngine.recordPullback(100.0, 65.0, 5, true);
        }

        // High stddev: pullbacks scattered between 0.40 and 0.90
        StatisticalRetracementEngine wideEngine = new StatisticalRetracementEngine("NQ", 40);
        for (int i = 0; i < 10; i++) {
            wideEngine.recordPullback(100.0, 40.0, 5, true);
        }
        for (int i = 0; i < 10; i++) {
            wideEngine.recordPullback(100.0, 90.0, 5, true);
        }

        double[] tightZone = tightEngine.getEntryZone(19800.0, 100.0, true);
        double[] wideZone = wideEngine.getEntryZone(19800.0, 100.0, true);

        double tightWidth = tightZone[2] - tightZone[0];
        double wideWidth = wideZone[2] - wideZone[0];

        assertThat(wideWidth).isGreaterThan(tightWidth);
    }

    @Test
    @DisplayName("HIGH confidence when stdDev < 0.08")
    void highConfidence() {
        // All pullbacks at exactly 0.65 → stdDev = 0
        for (int i = 0; i < 20; i++) {
            engine.recordPullback(100.0, 65.0, 5, true);
        }

        assertThat(engine.getConfidence()).isEqualTo(StatisticalRetracementEngine.EntryConfidence.HIGH);
    }

    @Test
    @DisplayName("LOW confidence when stdDev > 0.15")
    void lowConfidence() {
        // Scattered pullbacks: 0.30, 0.50, 0.70, 0.90 → high stdDev
        for (int i = 0; i < 5; i++) {
            engine.recordPullback(100.0, 30.0, 5, true);
            engine.recordPullback(100.0, 50.0, 5, true);
            engine.recordPullback(100.0, 70.0, 5, true);
            engine.recordPullback(100.0, 90.0, 5, true);
        }

        assertThat(engine.getStdDevDepth()).isGreaterThan(0.15);
        assertThat(engine.getConfidence()).isEqualTo(StatisticalRetracementEngine.EntryConfidence.LOW);
    }

    @Test
    @DisplayName("NORMAL confidence when stdDev between 0.08 and 0.15")
    void normalConfidence() {
        // Pullbacks at 0.55 and 0.75 → mean=0.65, stdDev=0.10
        for (int i = 0; i < 10; i++) {
            engine.recordPullback(100.0, 55.0, 5, true);
        }
        for (int i = 0; i < 10; i++) {
            engine.recordPullback(100.0, 75.0, 5, true);
        }

        assertThat(engine.getStdDevDepth()).isBetween(0.08, 0.15);
        assertThat(engine.getConfidence()).isEqualTo(StatisticalRetracementEngine.EntryConfidence.NORMAL);
    }

    @Test
    @DisplayName("SwingPointListener callback works")
    void swingPointListenerCallback() {
        for (int i = 0; i < 15; i++) {
            engine.onPullbackCompleted(100.0, 65.0, 5, true);
        }
        assertThat(engine.isInitialized()).isTrue();
        assertThat(engine.getMeanDepth()).isCloseTo(0.65, within(0.001));
    }

    @Test
    @DisplayName("Sample count only counts continued pullbacks")
    void sampleCountOnlyContinued() {
        for (int i = 0; i < 10; i++) {
            engine.recordPullback(100.0, 65.0, 5, true);
        }
        for (int i = 0; i < 5; i++) {
            engine.recordPullback(100.0, 65.0, 5, false);
        }

        assertThat(engine.getSampleCount()).isEqualTo(10);
    }

    @Test
    @DisplayName("Zero impulse range is rejected")
    void zeroImpulseRejected() {
        engine.recordPullback(0.0, 50.0, 5, true);
        assertThat(engine.getSampleCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Regime stability is low for consistent data")
    void regimeStabilityLow() {
        for (int i = 0; i < 20; i++) {
            engine.recordPullback(100.0, 65.0, 5, true);
        }

        double stability = engine.getRegimeStability();
        assertThat(stability).isLessThan(0.5);
    }
}
