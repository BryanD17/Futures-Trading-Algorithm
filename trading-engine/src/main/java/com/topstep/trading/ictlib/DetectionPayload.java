package com.topstep.trading.ictlib;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the BOUNDED {@code detections} object served by
 * {@code GET /api/chart/{symbol}} (V4 Agent 06).
 *
 * <p>Bounding is the whole job. The registry already caps each family, but the
 * chart must not simply dump everything: nine families across two timeframes
 * is a payload that grows with nothing to stop it and a chart nobody can read
 * (risk G-R5). So per family this returns
 * <ul>
 *   <li>every LIVE (non-terminal) detection — those are the ones that still
 *       mean something, and</li>
 *   <li>the most recent {@code recentTerminal} finished ones — enough for the
 *       eye to see that a gap filled or a pool was swept, without keeping the
 *       chart's whole history on screen.</li>
 * </ul>
 * A hard {@code cap} on the total then guarantees the response size regardless
 * of how the caps are tuned, and {@code truncated} says plainly when it bit —
 * a silently shortened list would read as "nothing else was there".
 *
 * <p>The timeframe filter defaults to the 15m instances: on a 30m chart, 1m
 * zones are visual noise rather than information.
 */
public final class DetectionPayload {

    /** Default timeframe rendered on the 30m Bot Chart. */
    public static final String DEFAULT_TIMEFRAME = IctLibEngine.TF_15M;
    /** Default hard cap on returned detections. */
    public static final int DEFAULT_CAP = 300;
    /** Default number of finished detections kept per family. */
    public static final int DEFAULT_RECENT_TERMINAL = 3;

    private DetectionPayload() {}

    public static Map<String, Object> build(DetectionRegistry registry, String timeframe) {
        return build(registry, timeframe, DEFAULT_CAP, DEFAULT_RECENT_TERMINAL);
    }

    /**
     * @param registry        the symbol's store (never null; may be empty)
     * @param timeframe       {@code "1m"}, {@code "15m"}, or {@code "all"}
     * @param cap             hard ceiling on total returned detections
     * @param recentTerminal  finished detections kept per family
     */
    public static Map<String, Object> build(DetectionRegistry registry, String timeframe,
                                            int cap, int recentTerminal) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> families = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();

        int returned = 0;
        boolean truncated = false;
        boolean all = timeframe == null || "all".equalsIgnoreCase(timeframe);

        for (DetectionType type : DetectionType.values()) {
            List<Detection> live = new ArrayList<>();
            List<Detection> finished = new ArrayList<>();
            for (Detection d : registry.byType(type)) {
                if (!all && !d.timeframe().equalsIgnoreCase(timeframe)) continue;
                (d.terminal() ? finished : live).add(d);
            }
            // byType is oldest-first, so the tail is the most recent.
            int from = Math.max(0, finished.size() - Math.max(0, recentTerminal));
            List<Detection> keep = new ArrayList<>(live);
            keep.addAll(finished.subList(from, finished.size()));
            if (keep.isEmpty()) continue;

            List<Map<String, Object>> serialised = new ArrayList<>(keep.size());
            for (Detection d : keep) {
                if (returned >= cap) {
                    truncated = true;
                    break;
                }
                serialised.add(d.toApiMap());
                returned++;
            }
            if (!serialised.isEmpty()) {
                families.put(type.jsonKey(), serialised);
                counts.put(type.jsonKey(), serialised.size());
            }
            if (truncated) break;
        }

        out.put("timeframe", all ? "all" : timeframe);
        out.put("cap", cap);
        out.put("returned", returned);
        out.put("truncated", truncated);
        out.put("counts", counts);
        out.put("families", families);
        return out;
    }
}
