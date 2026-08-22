package com.topstep.trading.notify;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal JSON writer for the fixed-shape Discord webhook payload.
 *
 * <p>WHY not Jackson: the engine already depends on Jackson, so using it would
 * have been free. This class exists so the notify package has <em>zero</em>
 * external dependencies and can therefore be compiled and unit-tested in
 * isolation from the rest of the engine. The payload shape is small, fixed,
 * and fully covered by tests; the escaping below is the complete set required
 * by RFC 8259 for the characters a price alert can contain.
 *
 * <p>This is a writer only. Nothing here parses untrusted input.
 */
final class Json {

    private Json() {}

    /**
     * Escape a string for embedding in a JSON string literal.
     *
     * <p>Handles the two mandatory escapes (quote, backslash), the five
     * short-form control escapes, and falls back to {@code \}{@code uXXXX} for
     * every other character below 0x20. Also escapes U+2028 and U+2029, which
     * are legal in JSON but break some JavaScript consumers.
     */
    static String escape(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(raw.length() + 16);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || c == '\u2028' || c == '\u2029') {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /** A JSON object built from ordered key/value pairs. Values are pre-encoded. */
    static final class Obj {
        private final List<String> parts = new ArrayList<>();

        Obj str(String key, String value) {
            if (value != null) parts.add("\"" + escape(key) + "\":\"" + escape(value) + "\"");
            return this;
        }

        Obj num(String key, long value) {
            parts.add("\"" + escape(key) + "\":" + value);
            return this;
        }

        Obj bool(String key, boolean value) {
            parts.add("\"" + escape(key) + "\":" + value);
            return this;
        }

        /** Nested object or array; {@code encoded} must already be valid JSON. */
        Obj raw(String key, String encoded) {
            if (encoded != null) parts.add("\"" + escape(key) + "\":" + encoded);
            return this;
        }

        String build() {
            return "{" + String.join(",", parts) + "}";
        }
    }

    /** Join pre-encoded elements into a JSON array. */
    static String array(List<String> encodedElements) {
        return "[" + String.join(",", encodedElements) + "]";
    }
}
