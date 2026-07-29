package com.pantropi.vms.application.shared;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US-07.4.2 — the audit payload is {@code jsonb} and the rejection reason is text a person typed.
 *
 * <p>The case that motivated this class is the newline: a reason entered in a textarea contains one
 * routinely, and a raw newline inside a JSON string is invalid, so PostgreSQL refuses the cast and an
 * ordinary rejection fails. Escaping only quotes and backslashes — which is what the per-entity
 * helpers elsewhere in this module do — would not have caught it.
 */
class JsonTextTest {

    @Test
    @DisplayName("quotes and backslashes are escaped")
    void quotesAndBackslashes() {
        assertThat(JsonText.quoted("he said \"no\"")).isEqualTo("\"he said \\\"no\\\"\"");
        assertThat(JsonText.quoted("C:\\path")).isEqualTo("\"C:\\\\path\"");
    }

    @Test
    @DisplayName("newlines, carriage returns and tabs become their escape sequences")
    void whitespaceControls() {
        assertThat(JsonText.quoted("line one\nline two")).isEqualTo("\"line one\\nline two\"");
        assertThat(JsonText.quoted("a\r\nb")).isEqualTo("\"a\\r\\nb\"");
        assertThat(JsonText.quoted("a\tb")).isEqualTo("\"a\\tb\"");
    }

    @Test
    @DisplayName("any other control character is escaped numerically rather than dropped")
    void otherControls() {
        // Kept rather than stripped: the audit record should say what was actually written.
        assertThat(JsonText.quoted("a\u0001b")).isEqualTo("\"a\\u0001b\"");
        assertThat(JsonText.quoted("\u0000")).isEqualTo("\"\\u0000\"");
    }

    @Test
    @DisplayName("markup passes through untouched — this escapes for JSON, it does not sanitise")
    void markupIsNotSanitised() {
        assertThat(JsonText.quoted("<script>alert(1)</script>"))
                .isEqualTo("\"<script>alert(1)</script>\"");
        assertThat(JsonText.quoted("5 < 10 & rising")).isEqualTo("\"5 < 10 & rising\"");
    }

    @Test
    @DisplayName("non-ASCII text is left alone")
    void unicodePassesThrough() {
        assertThat(JsonText.quoted("café — 日本語")).isEqualTo("\"café — 日本語\"");
    }

    @Test
    @DisplayName("an absent value is the JSON literal null, not the string \"null\"")
    void nullIsALiteral() {
        assertThat(JsonText.quotedOrNull(null)).isEqualTo("null");
        assertThat(JsonText.quotedOrNull("null")).isEqualTo("\"null\"");
        assertThat(JsonText.quoted("")).isEqualTo("\"\"");
    }
}
