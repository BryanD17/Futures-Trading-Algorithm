package com.topstep.trading.notify;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders an {@link OteAlert} as a Discord embed payload.
 *
 * <p>Two constraints shape the output. First, this posts into a paid channel of
 * a trading education server, so every embed carries the not-financial-advice
 * footer; that line is not optional and is asserted by the tests. Second, the
 * embed is read on a phone during a live session, so the levels are the
 * headline and everything else is secondary.
 *
 * <p>Colours follow the server's brass identity rather than the usual
 * green/red, with one exception: invalidation is muted grey, because a dead
 * setup should be visually quiet and not read as an opportunity.
 */
public final class OteAlertFormatter {

    /** Brass. Matches the server identity. */
    private static final int COLOR_ARMED = 0xC69E4E;
    /** Deeper brass for the follow-through event. */
    private static final int COLOR_REACTED = 0x8C6E33;
    /** Muted grey. A dead setup must not look like a live one. */
    private static final int COLOR_INVALIDATED = 0x4A5058;

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_INSTANT;

    private final String footerText;

    public OteAlertFormatter() {
        this("Educational only. Not financial advice.");
    }

    public OteAlertFormatter(String footerText) {
        if (footerText == null || footerText.isBlank()) {
            throw new IllegalArgumentException("footer is mandatory in a paid trading channel");
        }
        this.footerText = footerText;
    }

    /** Build the complete webhook payload for one alert. */
    public String format(OteAlert a) {
        List<String> fields = new ArrayList<>();

        fields.add(field("Zone",
                "`" + px(a.zoneNear(), a.decimals()) + "`  to  `"
                        + px(a.zoneFar(), a.decimals()) + "`", true));
        fields.add(field("Entry (0.705)",
                "`" + px(a.zoneSweet(), a.decimals()) + "`", true));
        fields.add(field("Invalidation",
                "`" + px(a.invalidation(), a.decimals()) + "`", true));

        if (a.kind() != OteAlert.Kind.INVALIDATED) {
            fields.add(field("First target",
                    "`" + px(a.target(), a.decimals()) + "`", true));
            double rr = a.riskReward();
            if (!Double.isNaN(rr)) {
                fields.add(field("R:R", String.format("%.1f to 1", rr), true));
            }
        }

        if (a.raidScore() != null) {
            String level = a.raidLevel() == null ? "level" : a.raidLevel();
            fields.add(field("Raid",
                    level + "  ·  quality " + a.raidScore() + "/10", true));
        }
        if (a.session() != null) {
            fields.add(field("Session", a.session(), true));
        }

        String embed = new Json.Obj()
                .str("title", title(a))
                .str("description", description(a))
                .num("color", color(a.kind()))
                .raw("fields", Json.array(fields))
                .raw("footer", new Json.Obj().str("text", footerText).build())
                .str("timestamp", ISO.format(a.occurredAt()))
                .build();

        return new Json.Obj()
                .raw("embeds", Json.array(List.of(embed)))
                .build();
    }

    private String title(OteAlert a) {
        String dir = a.bullish() ? "Bullish" : "Bearish";
        return switch (a.kind()) {
            case ARMED       -> a.symbol() + "  ·  " + dir + " OTE armed";
            case REACTED     -> a.symbol() + "  ·  " + dir + " OTE reacted";
            case INVALIDATED -> a.symbol() + "  ·  OTE invalidated";
        };
    }

    private String description(OteAlert a) {
        return switch (a.kind()) {
            case ARMED -> "Price has traded into the optimal entry band. "
                    + "Levels below are what the system is watching.";
            case REACTED -> "Price rejected out of the band and is moving toward the leg extreme.";
            case INVALIDATED -> "Price closed beyond the leg origin. This setup is dead. "
                    + "No further levels from this zone.";
        };
    }

    private int color(OteAlert.Kind kind) {
        return switch (kind) {
            case ARMED       -> COLOR_ARMED;
            case REACTED     -> COLOR_REACTED;
            case INVALIDATED -> COLOR_INVALIDATED;
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
     * Fixed-decimal price rendering.
     *
     * <p>BigDecimal with HALF_UP rather than String.format, because tick prices
     * such as NQ's 0.25 increments hit binary floating point representations
     * that {@code %.2f} rounds inconsistently across values.
     */
    private static String px(double value, int decimals) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return "n/a";
        return BigDecimal.valueOf(value)
                .setScale(decimals, RoundingMode.HALF_UP)
                .toPlainString();
    }
}
