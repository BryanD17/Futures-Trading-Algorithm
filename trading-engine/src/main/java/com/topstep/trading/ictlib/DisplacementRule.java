package com.topstep.trading.ictlib;

import com.topstep.trading.domain.Candle;

/**
 * §S1 — DISPLACEMENT CANDLE, as a pure predicate over a {@link TimeframeSeries}.
 *
 * <pre>
 *   meanBody[i]   = SMA(body, meanLen) evaluated at i
 *   smallWicks[i] = wickTop[i] &lt; body[i]*wickRatioMax
 *               AND wickBot[i] &lt; body[i]*wickRatioMax
 *   displacementUp[i]   = body[i] &gt; meanBody[i] AND smallWicks[i] AND c[i] &gt; o[i]
 *   displacementDown[i] = body[i] &gt; meanBody[i] AND smallWicks[i] AND c[i] &lt; o[i]
 * </pre>
 *
 * <p>It lives apart from {@link DisplacementScanner} because §S2 needs the
 * predicate at bar i-1 without caring whether a DISPLACEMENT detection was
 * registered — one definition, two consumers (V4 B13).
 *
 * <p>SPEC DECISION (§S1 was ambiguous, Appendix W1 disambiguates): the SMA is
 * taken over the meanLen bars PRECEDING i and EXCLUDES i itself. W1 lists
 * "prior five bodies: 8, 10, 9, 7, 11 -&gt; meanBody = 9.0" and then compares
 * candle X's body of 15 against it. Including i would make the test
 * self-referential — a large body would inflate the very average it must beat,
 * which is not what "exceeds recent average bodies" means.
 *
 * <p>ABSTAIN: with fewer than meanLen prior bars the mean is undefined, and the
 * predicate answers false — it never blocks, it just does not fire (Rollout
 * Doctrine; V4 anti-pattern C6).
 */
public final class DisplacementRule {

    private DisplacementRule() {}

    /** Mean body over the {@code meanLen} bars strictly before {@code back}. */
    public static double meanBody(TimeframeSeries s, int back, int meanLen) {
        if (meanLen < 1) return Double.NaN;
        double sum = 0.0;
        for (int k = 1; k <= meanLen; k++) {
            Candle c = s.at(back + k);
            if (c == null) return Double.NaN;
            sum += TimeframeSeries.body(c);
        }
        return sum / meanLen;
    }

    public static boolean isDisplacementUp(TimeframeSeries s, int back,
                                           int meanLen, double wickRatioMax) {
        Candle c = s.at(back);
        return c != null && c.getClose() > c.getOpen()
                && qualifies(s, back, meanLen, wickRatioMax);
    }

    public static boolean isDisplacementDown(TimeframeSeries s, int back,
                                             int meanLen, double wickRatioMax) {
        Candle c = s.at(back);
        return c != null && c.getClose() < c.getOpen()
                && qualifies(s, back, meanLen, wickRatioMax);
    }

    /** Body dominance + small wicks, independent of direction. */
    private static boolean qualifies(TimeframeSeries s, int back,
                                     int meanLen, double wickRatioMax) {
        Candle c = s.at(back);
        if (c == null) return false;
        double mean = meanBody(s, back, meanLen);
        if (Double.isNaN(mean)) return false;
        double body = TimeframeSeries.body(c);
        if (!(body > mean)) return false;
        double limit = body * wickRatioMax;
        return TimeframeSeries.wickTop(c) < limit && TimeframeSeries.wickBot(c) < limit;
    }
}
