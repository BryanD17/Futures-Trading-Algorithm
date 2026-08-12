package com.topstep.trading.ictlib;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolved configuration for the ICT library. Every Appendix S parameter is a
 * system property with the spec's default, so a mis-tuned instrument is a flag
 * away from being fixed without a rebuild.
 *
 * <p>ictlib is OBSERVATION-GRADE by construction: it feeds the Bot Chart, the
 * confluence snapshot and the profile simulator. It is not wired into any
 * entry gate, so having it on by default does not change live trading
 * behaviour — the Rollout Doctrine's switches (trade.profile, chart.anchorMode)
 * remain the only things that can.
 */
public final class IctLibConfig {

    /** Master switch — {@code ictlib.enabled}, default true (observation only). */
    public final boolean enabled;

    // §S1 displacement
    public final int displacementMeanLen;
    public final double displacementWickRatioMax;
    public final int retainDisplacement;

    // §S2 FVG / IFVG
    public final GapMode gapMode;
    public final int retainFvgPerSide;

    // §S3 BPR
    public final int retainBprPerSide;

    /** §S2 mode flag: plain gaps, or the inverted ("implied") overlap variant. */
    public enum GapMode { FVG, IFVG }

    private IctLibConfig(boolean enabled, int displacementMeanLen,
                         double displacementWickRatioMax, int retainDisplacement,
                         GapMode gapMode, int retainFvgPerSide, int retainBprPerSide) {
        this.enabled = enabled;
        this.displacementMeanLen = displacementMeanLen;
        this.displacementWickRatioMax = displacementWickRatioMax;
        this.retainDisplacement = retainDisplacement;
        this.gapMode = gapMode;
        this.retainFvgPerSide = retainFvgPerSide;
        this.retainBprPerSide = retainBprPerSide;
    }

    /** Appendix S defaults, with system-property overrides applied. */
    public static IctLibConfig fromSystemProperties() {
        return new IctLibConfig(
                !"false".equalsIgnoreCase(System.getProperty("ictlib.enabled", "true")),
                intProp("ictlib.displacement.meanLen", 5),
                doubleProp("ictlib.displacement.wickRatioMax", 0.36),
                intProp("ictlib.retain.displacement", 50),
                gapModeProp(),
                intProp("ictlib.retain.fvg", 10),
                intProp("ictlib.retain.bpr", 5));
    }

    /** Spec defaults with no property lookups — the tests' baseline. */
    public static IctLibConfig defaults() {
        return new IctLibConfig(true, 5, 0.36, 50, GapMode.FVG, 10, 5);
    }

    /** Copy with a different §S2 mode (used by the IFVG test and by tuning). */
    public IctLibConfig withGapMode(GapMode mode) {
        return new IctLibConfig(enabled, displacementMeanLen, displacementWickRatioMax,
                retainDisplacement, mode, retainFvgPerSide, retainBprPerSide);
    }

    /** Copy with a different FVG retention cap (used by the cap test). */
    public IctLibConfig withRetainFvgPerSide(int cap) {
        return new IctLibConfig(enabled, displacementMeanLen, displacementWickRatioMax,
                retainDisplacement, gapMode, cap, retainBprPerSide);
    }

    /** Retention policy map handed to every {@link DetectionRegistry}. */
    public Map<DetectionType, DetectionRegistry.Retention> retentions() {
        Map<DetectionType, DetectionRegistry.Retention> m = new LinkedHashMap<>();
        m.put(DetectionType.DISPLACEMENT,
                new DetectionRegistry.Retention(retainDisplacement, false));
        m.put(DetectionType.FVG,
                new DetectionRegistry.Retention(retainFvgPerSide, true));
        m.put(DetectionType.BPR,
                new DetectionRegistry.Retention(retainBprPerSide, true));
        return m;
    }

    /** One-line resolved-config record for the startup log (V4 Agent 09 audit). */
    public String describe() {
        return "ictlib enabled=" + enabled
                + " displacement(meanLen=" + displacementMeanLen
                + ",wickRatioMax=" + displacementWickRatioMax
                + ",retain=" + retainDisplacement + ")"
                + " gaps(mode=" + gapMode + ",retainPerSide=" + retainFvgPerSide + ")"
                + " bpr(retainPerSide=" + retainBprPerSide + ")";
    }

    private static int intProp(String key, int def) {
        try {
            String v = System.getProperty(key);
            return (v == null) ? def : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static double doubleProp(String key, double def) {
        try {
            String v = System.getProperty(key);
            return (v == null) ? def : Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static GapMode gapModeProp() {
        String v = System.getProperty("ictlib.fvg.mode", "FVG");
        return "IFVG".equalsIgnoreCase(v.trim()) ? GapMode.IFVG : GapMode.FVG;
    }
}
