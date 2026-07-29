package com.pantropi.vms.application.shared;

/**
 * Renders a string as a JSON literal (US-07.4.2, AC-6).
 *
 * <p>The audit trail and the outbox both store {@code jsonb}, and this project builds those payloads
 * by concatenation rather than through a serialiser — a deliberate choice, since the shapes are tiny
 * and fixed. That is safe for identifiers and enum values. It stops being safe the moment
 * operator-authored free text goes in, which is what a rejection reason is.
 *
 * <h2>Why the obvious two replacements are not enough</h2>
 * Escaping only {@code \} and {@code "} leaves control characters raw, and JSON forbids them inside
 * a string. A reason typed into a textarea will contain a newline sooner or later, and the resulting
 * payload is not valid JSON — so PostgreSQL refuses the {@code jsonb} cast and an ordinary rejection
 * fails with a 500. The bound is the format's, not a guess about what users type.
 *
 * <p>This does not sanitise: the text is stored exactly as written, HTML and all. Stripping tags
 * here would corrupt a legitimate reason ({@code "declined 5 < 10 people"}) while doing nothing for
 * the actual risk, which is at render time — the tenant's browser is where a stored script would
 * run, so that is where output encoding belongs (OWASP A03).
 *
 * <p>No framework imports — this is application code (US-01.2.2).
 */
public final class JsonText {

    private JsonText() {
    }

    /**
     * @return the value as a quoted JSON string, or the literal {@code null} when it is absent
     */
    public static String quotedOrNull(String value) {
        return value == null ? "null" : quoted(value);
    }

    /** @return the value as a quoted, escaped JSON string; never null */
    public static String quoted(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20) {
                        // Everything else below the space is escaped numerically rather than
                        // dropped: the audit record should say what was actually written.
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
