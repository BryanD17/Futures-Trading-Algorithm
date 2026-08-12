package com.topstep.trading.ictlib;

import com.topstep.trading.domain.Candle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §S8 STRUCTURE — Appendix W6 verbatim, including the whipsaw and the BOS
 * dedup rule, plus the [ICTLIB-DIFF] mss comparison against the gate detector.
 */
class IctLibStructureTest {

    private final IctLibConfig config = IctLibConfig.defaults();
    private DetectionRegistry registry;
    private TimeframeSeries series;
    private StructureEngine engine;

    @BeforeEach
    void setUp() {
        IctLibDiffStats.resetAll();
        registry = new DetectionRegistry(IctLibFixture.SYM, config.retentions());
        series = new TimeframeSeries(IctLibEngine.TF_1M);
        engine = new StructureEngine(config, IctLibDiffStats.forSymbol(IctLibFixture.SYM));
    }

    private void push(Candle c) {
        series.push(c);
        engine.onBar(series, registry);
    }

    /** W6's sequence, hand-built so every pivot confirms exactly where stated. */
    private void w6Sequence() {
        for (int i = 0; i < 5; i++) {
            push(IctLibFixture.c(i, 21015, 21020, 21010, 21016));
        }
        push(IctLibFixture.c(5, 21015, 21040, 21012, 21020));   // swing high 21040
        push(IctLibFixture.c(6, 21020, 21039, 21018, 21038));   // confirms it
        push(IctLibFixture.c(7, 21038, 21045, 21037, 21042));   // close 21042 → MSS_BULL
        push(IctLibFixture.c(8, 21042, 21055, 21040, 21050));   // new swing high 21055
        push(IctLibFixture.c(9, 21050, 21052, 21046, 21048));   // confirms it
        push(IctLibFixture.c(10, 21048, 21058, 21047, 21056));  // close 21056 → BOS_BULL
        push(IctLibFixture.c(11, 21056, 21060, 21054, 21058));  // above again → dedup
    }

    private List<Detection> mss() {
        return registry.byType(DetectionType.MSS);
    }

    private List<Detection> bos() {
        return registry.byType(DetectionType.BOS);
    }

    @Test
    @DisplayName("W6: MSS_BULL at 21040 flips dir to +1")
    void mssBull() {
        w6Sequence();
        assertThat(mss()).isNotEmpty();
        Detection m = mss().get(0);
        assertThat(m.direction()).isEqualTo(DetectionDirection.BULLISH);
        assertThat(m.meta()).containsEntry("level", 21040.0);
        assertThat(m.state()).isEqualTo(DetectionState.POINT);
        assertThat(engine.direction()).isEqualTo(1);
    }

    @Test
    @DisplayName("W6: BOS_BULL at 21055, and a second close above it emits NOTHING (dedup)")
    void bosThenDedup() {
        w6Sequence();
        assertThat(bos()).hasSize(1);
        assertThat(bos().get(0).meta()).containsEntry("level", 21055.0);
        assertThat(bos().get(0).direction()).isEqualTo(DetectionDirection.BULLISH);
    }

    @Test
    @DisplayName("W6 whipsaw: a close below a confirmed swing low flips the full regime to MSS_BEAR")
    void whipsawToBearish() {
        w6Sequence();
        push(IctLibFixture.c(12, 21058, 21059, 21035, 21040));  // swing low 21035
        push(IctLibFixture.c(13, 21040, 21044, 21037, 21042));  // confirms it
        push(IctLibFixture.c(14, 21042, 21043, 21030, 21033));  // close 21033 → MSS_BEAR

        List<Detection> shifts = mss();
        assertThat(shifts).hasSize(2);
        Detection bear = shifts.get(1);
        assertThat(bear.direction()).isEqualTo(DetectionDirection.BEARISH);
        assertThat(bear.meta()).containsEntry("level", 21035.0);
        assertThat(engine.direction()).isEqualTo(-1);
    }

    @Test
    @DisplayName("No MSS while the regime already agrees — that is a BOS, by definition")
    void secondBullishShiftIsNotAnMss() {
        w6Sequence();
        assertThat(mss()).hasSize(1);          // only the regime FLIP is a shift
        assertThat(bos()).hasSize(1);
    }

    @Test
    @DisplayName("Cold start ABSTAINS: no pivots confirmed, no structure, no exception")
    void coldStartEmitsNothing() {
        for (int i = 0; i < 4; i++) {
            push(IctLibFixture.c(i, 21000, 21010, 20990, 21005));
        }
        assertThat(mss()).isEmpty();
        assertThat(bos()).isEmpty();
        assertThat(engine.direction()).isZero();
    }

    @Test
    @DisplayName("[ICTLIB-DIFF] mss line carries the documented shape and real counts")
    void mssDiffLine() {
        w6Sequence();
        IctLibDiffStats stats = IctLibDiffStats.forSymbol(IctLibFixture.SYM);

        assertThat(stats.ictlibMss()).isEqualTo(1);
        assertThat(stats.mssRollup())
                .isEqualTo("mss: ictlib=" + stats.ictlibMss()
                        + " gate=" + stats.gateMss()
                        + " agreeWindow=" + stats.agreeMss());
        assertThat(stats.mssLogLine())
                .isEqualTo("[ICTLIB-DIFF MNQ] " + stats.mssRollup());
        // The agreement count can never exceed either side's event count.
        assertThat(stats.agreeMss()).isLessThanOrEqualTo(stats.ictlibMss());
        assertThat(stats.agreeMss()).isLessThanOrEqualTo(stats.gateMss());
    }

    @Test
    @DisplayName("Both structure reads run over the identical bars and both fire — gate=0 would be a lie")
    void bothReadsFireAndAgreementIsBounded() {
        for (Candle c : IctLibDeterminismTest.syntheticFeed(600)) push(c);

        IctLibDiffStats stats = IctLibDiffStats.forSymbol(IctLibFixture.SYM);
        assertThat(stats.ictlibMss())
                .as("ictlib's zigzag regime must produce shifts")
                .isGreaterThan(0);
        assertThat(stats.gateMss())
                .as("the SHADOWED gate detector must produce shifts too, or the diff is meaningless")
                .isGreaterThan(0);
        // The two reads are genuinely different constructs — that is the point
        // of measuring them rather than assuming one.
        assertThat(stats.ictlibMss()).isNotEqualTo(stats.gateMss());
        assertThat(stats.agreeMss()).isLessThanOrEqualTo(stats.ictlibMss());
        assertThat(stats.agreeMss()).isLessThanOrEqualTo(stats.gateMss());
    }

    @Test
    @DisplayName("Structure history stays bounded whatever the tape does")
    void historyIsBounded() {
        for (Candle c : IctLibDeterminismTest.syntheticFeed(600)) push(c);

        assertThat(registry.count(DetectionType.MSS))
                .isLessThanOrEqualTo(config.structureHistoryCap);
        assertThat(registry.count(DetectionType.BOS))
                .isLessThanOrEqualTo(config.structureHistoryCap);
    }
}
