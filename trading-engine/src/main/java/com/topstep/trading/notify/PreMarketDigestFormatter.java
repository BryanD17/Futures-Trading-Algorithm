package com.topstep.trading.notify;

import com.topstep.trading.chartstate.KnownLevel;
import com.topstep.trading.chartstate.LevelType;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renders the daily pre-market levels digest as one Discord embed per symbol.
 *
 * <p>Same two constraints as {@link OteAlertFormatter}: the disclaimer footer is
 * mandatory because this posts into a paid trading channel, and it is read on a
 * phone before the open, so the levels are the headline.
 *
 * <p>One rule specific to this payload: <b>a cold or empty symbol still gets an
 * embed.</b> A scheduled post is a promise, and silently skipping the day is
 * worse than saying "no levels yet" — members notice a missing routine faster
 * than a missing signal, and a skipped post is indistinguishable from a dead
 * bot.
 */
public final class PreMarketDigestFormatter {

    /** Brass, matching the alert identity. */
    private static final int COLOR_DIGEST = 0xC69E4E;
    /** Muted grey: a cold symbol must not look like a live opportunity. */
    private static final int COLOR_COLD = 0x4A5058;

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE d MMMM");

    /** Grouped in the order a trader reads them before the open. */
    private static final Set<LevelType> PREV_DAY = EnumSet.of(LevelType.PDH, LevelType.PDL);
    private static final Set<LevelType> PREV_WEEK = EnumSet.of(LevelType.PWH, LevelType.PWL);
    private static final Set<LevelType> SESSIONS = EnumSet.of(
            LevelType.ASIA_HIGH, LevelType.ASIA_LOW,
            LevelType.LONDON_HIGH, LevelType.LONDON_LOW);
    private static final Set<LevelType> OPENS = EnumSet.of(
            LevelType.MIDNIGHT_OPEN, LevelType.DAILY_OPEN, LevelType.WEEKLY_OPEN);

    private final String footerText;

    public PreMarketDigestFormatter() {
        this("Educational only. Not financial advice.");
    }

    public PreMarketDigestFormatter(String footerText) {
        if (footerText == null || footerText.isBlank()) {
            throw new IllegalArgumentException("footer is mandatory in a paid trading channel");
        }
        this.footerText = footerText;
    }

    /**
     * One message containing one embed per symbol, in the order supplied.
     *
     * @param sections per-symbol content; never empty in practice, but an empty
     *                 list still yields a valid (embed-less) payload rather than
     *                 throwing on the scheduler thread
     */
    public String format(List<SymbolSection> sections, LocalDate tradingDay, Instant now) {
        List<String> embeds = new ArrayList<>();
        for (SymbolSection s : sections) {
            embeds.add(embed(s, tradingDay, now));
        }
        return new Json.Obj().raw("embeds", Json.array(embeds)).build();
    }

    private String embed(SymbolSection s, LocalDate tradingDay, Instant now) {
        List<String> fields = new ArrayList<>();

        if (!s.warm() || s.levels().isEmpty()) {
            // The honest path. Say what is missing and why, rather than posting
            // an empty shell or nothing at all.
            fields.add(field("Status", s.warm()
                    ? "Chart is warm but no reference levels have formed yet."
                    : "Chart is COLD — not enough history ingested to publish levels.", false));
            fields.add(field("What this means",
                    "No levels are being published for this instrument today. "
                            + "This is a data state, not a market call.", false));
        } else {
            addGroup(fields, "Previous day", s.levels(), PREV_DAY, s.decimals());
            addGroup(fields, "Previous week", s.levels(), PREV_WEEK, s.decimals());
            addGroup(fields, "Sessions", s.levels(), SESSIONS, s.decimals());
            addGroup(fields, "Opens", s.levels(), OPENS, s.decimals());

            if (s.watching() != null) {
                fields.add(field("Watching", s.watching(), false));
            }
            fields.add(field("Untouched levels",
                    String.valueOf(s.unraidedCount()), true));
        }

        return new Json.Obj()
                .str("title", s.symbol() + "  ·  Pre-market levels")
                .str("description", DAY.format(tradingDay)
                        + (s.warm() ? "" : "  ·  data incomplete"))
                .num("color", (s.warm() && !s.levels().isEmpty()) ? COLOR_DIGEST : COLOR_COLD)
                .raw("fields", Json.array(fields))
                .raw("footer", new Json.Obj().str("text", footerText).build())
                .str("timestamp", ISO.format(now))
                .build();
    }

    /** Emit a field only when at least one level in the group is present. */
    private static void addGroup(List<String> fields, String name,
                                 Map<LevelType, Double> levels,
                                 Set<LevelType> group, int decimals) {
        StringBuilder sb = new StringBuilder();
        for (LevelType t : group) {
            Double price = levels.get(t);
            if (price == null) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(label(t)).append("  `").append(Prices.px(price, decimals)).append('`');
        }
        if (sb.length() > 0) {
            fields.add(field(name, sb.toString(), true));
        }
    }

    /** Human labels; the enum names are trader shorthand but not all obvious. */
    private static String label(LevelType t) {
        return switch (t) {
            case PDH -> "Prev day high";
            case PDL -> "Prev day low";
            case PWH -> "Prev week high";
            case PWL -> "Prev week low";
            case ASIA_HIGH -> "Asia high";
            case ASIA_LOW -> "Asia low";
            case LONDON_HIGH -> "London high";
            case LONDON_LOW -> "London low";
            case MIDNIGHT_OPEN -> "Midnight open";
            case DAILY_OPEN -> "Daily open";
            case WEEKLY_OPEN -> "Weekly open";
            default -> t.name();
        };
    }

    private static String field(String name, String value, boolean inline) {
        return new Json.Obj()
                .str("name", name)
                .str("value", value)
                .bool("inline", inline)
                .build();
    }

    /**
     * One instrument's digest content, already flattened away from engine types
     * so the formatter stays testable without chart state.
     *
     * @param symbol        instrument
     * @param warm          whether the chart had enough history to be trusted
     * @param levels        reference levels by type; may be empty
     * @param unraidedCount how many tracked levels are still untouched
     * @param watching      free text for any live zone, or null
     * @param decimals      price decimals for this instrument
     */
    public record SymbolSection(
            String symbol,
            boolean warm,
            Map<LevelType, Double> levels,
            int unraidedCount,
            String watching,
            int decimals
    ) {
        public SymbolSection {
            if (symbol == null || symbol.isBlank()) {
                throw new IllegalArgumentException("symbol required");
            }
            levels = (levels == null) ? Map.of() : new LinkedHashMap<>(levels);
        }
    }
}
