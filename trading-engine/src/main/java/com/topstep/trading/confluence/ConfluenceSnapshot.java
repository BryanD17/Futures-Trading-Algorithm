package com.topstep.trading.confluence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One aggregated read of every confluence fact for a symbol and direction
 * (V4 Agent 07). Immutable; computes nothing beyond the arithmetic over
 * answers it was handed.
 *
 * <h2>The arithmetic, and why it is shaped this way</h2>
 * <pre>
 *   score    = Σ weight(field) for fields that are TRUE
 *   maxScore = Σ weight(field) for fields that are NOT UNKNOWN
 * </pre>
 * UNKNOWN is excluded from BOTH sides. A stack whose sources are still cold
 * therefore reads as a small fraction of a small maximum — visibly cold —
 * instead of as a confident "almost nothing is true" (Appendix E6). Reporting
 * both numbers is the point: {@code 2/4} and {@code 2/16} are completely
 * different situations and a single ratio hides that.
 */
public record ConfluenceSnapshot(
        String symbol,
        boolean bullish,
        Instant at,
        Map<ConfluenceField, Tri> values,
        Map<ConfluenceField, String> details,
        double score,
        double maxScore
) {

    public ConfluenceSnapshot {
        values = Map.copyOf(values);
        details = Map.copyOf(details);
    }

    public String direction() {
        return bullish ? "LONG" : "SHORT";
    }

    /** Weighted ratio in [0,1]; 0 when nothing is known yet (never NaN). */
    public double ratio() {
        return maxScore <= 0 ? 0.0 : score / maxScore;
    }

    /** Number of fields answered TRUE. */
    public int trueCount() {
        return (int) values.values().stream().filter(Tri::isTrue).count();
    }

    /** Number of fields whose source could answer at all. */
    public int knownCount() {
        return (int) values.values().stream().filter(Tri::isKnown).count();
    }

    /** The heaviest TRUE fields, best-first — the "top:" part of the log line. */
    public List<String> topContributors(int n) {
        List<ConfluenceField> trues = new ArrayList<>();
        for (Map.Entry<ConfluenceField, Tri> e : values.entrySet()) {
            if (e.getValue().isTrue()) trues.add(e.getKey());
        }
        trues.sort((a, b) -> {
            int byWeight = Double.compare(b.weight(), a.weight());
            return byWeight != 0 ? byWeight : a.key().compareTo(b.key());
        });
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(n, trues.size()); i++) out.add(trues.get(i).key());
        return out;
    }

    /** Compact per-direction token for the {@code [CONFLUENCE]} line. */
    public String token() {
        return String.format("%s %d/%dw=%.2f",
                bullish ? "long" : "short", trueCount(), knownCount(), ratio());
    }

    /** JSON-friendly map for {@code /api/confluence/{symbol}}. */
    public Map<String, Object> toApiMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("symbol", symbol);
        m.put("direction", direction());
        m.put("at", at == null ? null : at.toString());
        m.put("score", score);
        m.put("maxScore", maxScore);
        m.put("ratio", ratio());
        m.put("trueCount", trueCount());
        m.put("knownCount", knownCount());
        m.put("fieldCount", ConfluenceField.values().length);
        m.put("top", topContributors(4));

        List<Map<String, Object>> fields = new ArrayList<>();
        for (ConfluenceField f : ConfluenceField.values()) {
            Map<String, Object> fm = new LinkedHashMap<>();
            Tri v = values.getOrDefault(f, Tri.UNKNOWN);
            fm.put("key", f.key());
            fm.put("value", v.name());
            fm.put("glyph", v.glyph());
            fm.put("weight", f.weight());
            fm.put("owner", f.owner());
            fm.put("detail", details.get(f));
            fields.add(fm);
        }
        m.put("fields", fields);
        return m;
    }
}
