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

    // §S4 volume imbalance
    public final int retainVolumeImbalance;
    public final int viProjectBars;

    // §S5 opening gaps
    public final int retainGapWeekly;
    public final int retainGapDaily;

    // §S6 liquidity pools
    public final int poolSwingLen;
    public final double poolToleranceDiv;
    public final int poolMinCluster;
    public final int poolScanDepth;
    public final int retainPoolPerSide;
    public final int poolAtrPeriod;

    /** §S2 mode flag: plain gaps, or the inverted ("implied") overlap variant. */
    public enum GapMode { FVG, IFVG }

    private IctLibConfig(boolean enabled, int displacementMeanLen,
                         double displacementWickRatioMax, int retainDisplacement,
                         GapMode gapMode, int retainFvgPerSide, int retainBprPerSide,
                         int retainVolumeImbalance, int viProjectBars,
                         int retainGapWeekly, int retainGapDaily,
                         int poolSwingLen, double poolToleranceDiv, int poolMinCluster,
                         int poolScanDepth, int retainPoolPerSide, int poolAtrPeriod) {
        this.enabled = enabled;
        this.displacementMeanLen = displacementMeanLen;
        this.displacementWickRatioMax = displacementWickRatioMax;
        this.retainDisplacement = retainDisplacement;
        this.gapMode = gapMode;
        this.retainFvgPerSide = retainFvgPerSide;
        this.retainBprPerSide = retainBprPerSide;
        this.retainVolumeImbalance = retainVolumeImbalance;
        this.viProjectBars = viProjectBars;
        this.retainGapWeekly = retainGapWeekly;
        this.retainGapDaily = retainGapDaily;
        this.poolSwingLen = poolSwingLen;
        this.poolToleranceDiv = poolToleranceDiv;
        this.poolMinCluster = poolMinCluster;
        this.poolScanDepth = poolScanDepth;
        this.retainPoolPerSide = retainPoolPerSide;
        this.poolAtrPeriod = poolAtrPeriod;
    }

    /** Every field, so copy-with helpers cannot silently drop a new parameter. */
    private IctLibConfig copy(GapMode mode, int fvgCap, int poolCluster, double tolDiv) {
        return new IctLibConfig(enabled, displacementMeanLen, displacementWickRatioMax,
                retainDisplacement, mode, fvgCap, retainBprPerSide,
                retainVolumeImbalance, viProjectBars, retainGapWeekly, retainGapDaily,
                poolSwingLen, tolDiv, poolCluster, poolScanDepth, retainPoolPerSide,
                poolAtrPeriod);
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
                intProp("ictlib.retain.bpr", 5),
                intProp("ictlib.retain.volumeImbalance", 6),
                intProp("ictlib.vi.projectBars", 3),
                intProp("ictlib.retain.gapWeekly", 3),
                intProp("ictlib.retain.gapDaily", 2),
                intProp("ictlib.pool.swingLen", 5),
                doubleProp("ictlib.pool.toleranceDiv", 2.5),
                intProp("ictlib.pool.minCluster", 3),
                intProp("ictlib.pool.scanDepth", 50),
                intProp("ictlib.retain.pool", 4),
                intProp("ictlib.pool.atrPeriod", 10));
    }

    /** Spec defaults with no property lookups — the tests' baseline. */
    public static IctLibConfig defaults() {
        return new IctLibConfig(true, 5, 0.36, 50, GapMode.FVG, 10, 5,
                6, 3, 3, 2, 5, 2.5, 3, 50, 4, 10);
    }

    /** Copy with a different §S2 mode (used by the IFVG test and by tuning). */
    public IctLibConfig withGapMode(GapMode mode) {
        return copy(mode, retainFvgPerSide, poolMinCluster, poolToleranceDiv);
    }

    /** Copy with a different FVG retention cap (used by the cap test). */
    public IctLibConfig withRetainFvgPerSide(int cap) {
        return copy(gapMode, cap, poolMinCluster, poolToleranceDiv);
    }

    /** Copy with a different §S6 minimum cluster size. */
    public IctLibConfig withPoolMinCluster(int minCluster) {
        return copy(gapMode, retainFvgPerSide, minCluster, poolToleranceDiv);
    }

    /** Copy with a different §S6 tolerance divisor. */
    public IctLibConfig withPoolToleranceDiv(double div) {
        return copy(gapMode, retainFvgPerSide, poolMinCluster, div);
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
        m.put(DetectionType.VOLUME_IMBALANCE,
                new DetectionRegistry.Retention(retainVolumeImbalance, false));
        m.put(DetectionType.OPENING_GAP_WEEKLY,
                new DetectionRegistry.Retention(retainGapWeekly, false));
        m.put(DetectionType.OPENING_GAP_DAILY,
                new DetectionRegistry.Retention(retainGapDaily, false));
        m.put(DetectionType.LIQUIDITY_POOL,
                new DetectionRegistry.Retention(retainPoolPerSide, true));
        return m;
    }

    /** One-line resolved-config record for the startup log (V4 Agent 09 audit). */
    public String describe() {
        return "ictlib enabled=" + enabled
                + " displacement(meanLen=" + displacementMeanLen
                + ",wickRatioMax=" + displacementWickRatioMax
                + ",retain=" + retainDisplacement + ")"
                + " gaps(mode=" + gapMode + ",retainPerSide=" + retainFvgPerSide + ")"
                + " bpr(retainPerSide=" + retainBprPerSide + ")"
                + " vi(retain=" + retainVolumeImbalance + ")"
                + " gaps(weekly=" + retainGapWeekly + ",daily=" + retainGapDaily + ")"
                + " pools(swingLen=" + poolSwingLen
                + ",toleranceDiv=" + poolToleranceDiv
                + ",minCluster=" + poolMinCluster
                + ",atr=" + poolAtrPeriod
                + ",retainPerSide=" + retainPoolPerSide + ")";
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
