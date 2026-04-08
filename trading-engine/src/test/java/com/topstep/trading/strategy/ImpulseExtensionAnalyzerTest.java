package com.topstep.trading.strategy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.assertj.core.api.Assertions.*;

@DisplayName("ImpulseExtensionAnalyzer Tests")
class ImpulseExtensionAnalyzerTest {

    private ImpulseExtensionAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new ImpulseExtensionAnalyzer("NQ", 30);
    }

    @Test
    @DisplayName("Not initialized with fewer than 15 impulses")
    void notInitializedUntilMinSamples() {
        for (int i = 0; i < 14; i++) {
            analyzer.recordImpulse(20.0);
        }
        assertThat(analyzer.isInitialized()).isFalse();
    }

    @Test
    @DisplayName("Initialized after 15 impulses")
    void initializedAfter15Impulses() {
        for (int i = 0; i < 15; i++) {
            analyzer.recordImpulse(20.0);
        }
        assertThat(analyzer.isInitialized()).isTrue();
    }

    @Test
    @DisplayName("Mean and stdDev computed correctly for uniform impulses")
    void meanAndStdDevUniform() {
        for (int i = 0; i < 20; i++) {
            analyzer.recordImpulse(25.0);
        }

        assertThat(analyzer.getMeanImpulse()).isCloseTo(25.0, within(0.01));
        assertThat(analyzer.getStdDevImpulse()).isCloseTo(0.0, within(0.01));
    }

    @Test
    @DisplayName("Mean and stdDev computed correctly for varied impulses")
    void meanAndStdDevVaried() {
        // 10 impulses of 20, 10 impulses of 30 → mean=25, stddev=5
        for (int i = 0; i < 10; i++) {
            analyzer.recordImpulse(20.0);
        }
        for (int i = 0; i < 10; i++) {
            analyzer.recordImpulse(30.0);
        }

        assertThat(analyzer.getMeanImpulse()).isCloseTo(25.0, within(0.01));
        assertThat(analyzer.getStdDevImpulse()).isCloseTo(5.0, within(0.01));
    }

    @Test
    @DisplayName("REALISTIC classification for target within 1σ")
    void realisticClassification() {
        // mean=25, stddev=5 → realistic threshold = 25 + 5 = 30
        for (int i = 0; i < 10; i++) {
            analyzer.recordImpulse(20.0);
        }
        for (int i = 0; i < 10; i++) {
            analyzer.recordImpulse(30.0);
        }

        ImpulseExtensionAnalyzer.TargetAssessment assessment = analyzer.assessTarget(28.0);
        assertThat(assessment.getRealism()).isEqualTo(ImpulseExtensionAnalyzer.TargetRealism.REALISTIC);
    }

    @Test
    @DisplayName("AGGRESSIVE classification for target between 1σ and 2σ")
    void aggressiveClassification() {
        // mean=25, stddev=5 → aggressive threshold = 25 + 10 = 35
        for (int i = 0; i < 10; i++) {
            analyzer.recordImpulse(20.0);
        }
        for (int i = 0; i < 10; i++) {
            analyzer.recordImpulse(30.0);
        }

        ImpulseExtensionAnalyzer.TargetAssessment assessment = analyzer.assessTarget(33.0);
        assertThat(assessment.getRealism()).isEqualTo(ImpulseExtensionAnalyzer.TargetRealism.AGGRESSIVE);
    }

    @Test
    @DisplayName("UNREALISTIC classification for target above 2.5σ")
    void unrealisticClassification() {
        // mean=25, stddev=5 → unrealistic threshold = 25 + 12.5 = 37.5
        for (int i = 0; i < 10; i++) {
            analyzer.recordImpulse(20.0);
        }
        for (int i = 0; i < 10; i++) {
            analyzer.recordImpulse(30.0);
        }

        ImpulseExtensionAnalyzer.TargetAssessment assessment = analyzer.assessTarget(40.0);
        assertThat(assessment.getRealism()).isEqualTo(ImpulseExtensionAnalyzer.TargetRealism.UNREALISTIC);
    }

    @Test
    @DisplayName("UNINITIALIZED assessment when not enough data")
    void uninitializedAssessment() {
        analyzer.recordImpulse(20.0);
        ImpulseExtensionAnalyzer.TargetAssessment assessment = analyzer.assessTarget(30.0);
        assertThat(assessment.getRealism()).isEqualTo(ImpulseExtensionAnalyzer.TargetRealism.UNINITIALIZED);
    }

    @Test
    @DisplayName("Empirical probability: higher distance = lower probability")
    void probabilityDecreasesWithDistance() {
        // Create varied impulse sizes: 10, 15, 20, 25, 30 (3 each)
        for (int i = 0; i < 3; i++) {
            analyzer.recordImpulse(10.0);
            analyzer.recordImpulse(15.0);
            analyzer.recordImpulse(20.0);
            analyzer.recordImpulse(25.0);
            analyzer.recordImpulse(30.0);
        }

        double probSmall = analyzer.estimateTargetProbability(12.0);
        double probMedium = analyzer.estimateTargetProbability(22.0);
        double probLarge = analyzer.estimateTargetProbability(28.0);

        assertThat(probSmall).isGreaterThan(probMedium);
        assertThat(probMedium).isGreaterThan(probLarge);
    }

    @Test
    @DisplayName("Sigma target calculation bullish")
    void sigmaTargetBullish() {
        for (int i = 0; i < 10; i++) {
            analyzer.recordImpulse(20.0);
        }
        for (int i = 0; i < 10; i++) {
            analyzer.recordImpulse(30.0);
        }

        // mean=25, stddev=5
        double entry = 20000.0;
        // 1σ target = entry + (25 + 5) = entry + 30
        double target1Sigma = analyzer.getSigmaTarget(entry, true, 1.0);
        assertThat(target1Sigma).isCloseTo(20030.0, within(0.01));

        // 2σ target = entry + (25 + 10) = entry + 35
        double target2Sigma = analyzer.getSigmaTarget(entry, true, 2.0);
        assertThat(target2Sigma).isCloseTo(20035.0, within(0.01));
    }

    @Test
    @DisplayName("Sigma target calculation bearish")
    void sigmaTargetBearish() {
        for (int i = 0; i < 10; i++) {
            analyzer.recordImpulse(20.0);
        }
        for (int i = 0; i < 10; i++) {
            analyzer.recordImpulse(30.0);
        }

        double entry = 20000.0;
        double target1Sigma = analyzer.getSigmaTarget(entry, false, 1.0);
        assertThat(target1Sigma).isCloseTo(19970.0, within(0.01));
    }

    @Test
    @DisplayName("Ignores zero and negative impulse sizes")
    void ignoresInvalidImpulses() {
        analyzer.recordImpulse(0);
        analyzer.recordImpulse(-10.0);
        assertThat(analyzer.getSampleCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Rolling window evicts old samples")
    void rollingWindowEviction() {
        // Fill window of 30
        for (int i = 0; i < 30; i++) {
            analyzer.recordImpulse(20.0);
        }
        assertThat(analyzer.getSampleCount()).isEqualTo(30);

        // Add 5 more — oldest should be evicted
        for (int i = 0; i < 5; i++) {
            analyzer.recordImpulse(40.0);
        }
        assertThat(analyzer.getSampleCount()).isEqualTo(30);

        // Mean should shift toward 40
        assertThat(analyzer.getMeanImpulse()).isGreaterThan(20.0);
    }

    @Test
    @DisplayName("Assessment includes suggested targets")
    void assessmentHasSuggestedTargets() {
        for (int i = 0; i < 10; i++) {
            analyzer.recordImpulse(20.0);
        }
        for (int i = 0; i < 10; i++) {
            analyzer.recordImpulse(30.0);
        }

        ImpulseExtensionAnalyzer.TargetAssessment assessment = analyzer.assessTarget(50.0);
        // Conservative = mean + 1σ = 25 + 5 = 30
        assertThat(assessment.getSuggestedConservative()).isCloseTo(30.0, within(0.01));
        // Aggressive = mean + 2σ = 25 + 10 = 35
        assertThat(assessment.getSuggestedAggressive()).isCloseTo(35.0, within(0.01));
    }
}
