package com.pantropi.vms.domain.visitor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * US-07.1.2 · T-07.1.2.1 — the visitor detail value objects. Pure, no I/O.
 *
 * <p>Three properties the story asks for: normalisation, equality of differently-cased addresses,
 * and a {@code toString()} that cannot leak the value.
 */
class VisitorDetailsTest {

    // ---- AC-2: normalisation ----

    @Test
    @DisplayName("AC-2: an email is trimmed and lower-cased")
    void emailIsNormalised() {
        assertThat(EmailAddress.of("  Ada.Lovelace@Example.TEST  ").value())
                .isEqualTo("ada.lovelace@example.test");
    }

    @Test
    @DisplayName("AC-2: two spellings of the same address are the same value")
    void emailEqualityIgnoresCase() {
        // The column is citext, so the database already thinks these are one address. This is what
        // makes Java agree — otherwise a duplicate check here passes and the insert then fails.
        assertThat(EmailAddress.of("ADA@EXAMPLE.TEST"))
                .isEqualTo(EmailAddress.of("ada@example.test"))
                .hasSameHashCodeAs(EmailAddress.of("Ada@Example.Test"));
    }

    @Test
    @DisplayName("AC-2: lower-casing does not depend on the server's default locale")
    void emailNormalisationIsLocaleIndependent() {
        // In a Turkish locale String.toLowerCase() maps I to ı, so ADA@X.COM and ada@x.com would
        // stop being the same address depending on where the process happens to run.
        java.util.Locale original = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(new java.util.Locale("tr", "TR"));
            assertThat(EmailAddress.of("ADMIN@EXAMPLE.TEST").value())
                    .isEqualTo("admin@example.test");
        } finally {
            java.util.Locale.setDefault(original);
        }
    }

    @Test
    @DisplayName("AC-2: a phone number keeps its digits and drops its presentation")
    void phoneIsNormalised() {
        assertThat(PhoneNumber.of("  +880 1000-00000  ").value()).isEqualTo("+880100000000");
        assertThat(PhoneNumber.of("(65) 6123 4567").value()).isEqualTo("6561234567");
        assertThat(PhoneNumber.of("+880 1000 00000"))
                .isEqualTo(PhoneNumber.of("+8801000-00000"));
    }

    // ---- AC-4: malformed input ----

    @ParameterizedTest
    @ValueSource(strings = {"not-an-email", "@example.test", "ada@", "ada@example",
            "ada @example.test", "ada@exa mple.test", "ada@@example.test", "ada@.test"})
    @DisplayName("AC-4: a malformed email is refused, naming the field and not the value")
    void malformedEmailRefused(String bad) {
        assertThatThrownBy(() -> EmailAddress.of(bad))
                .isInstanceOfSatisfying(Visitor.InvalidVisitorDetail.class,
                        e -> assertThat(e.field()).isEqualTo("visitor email"))
                .hasMessageNotContaining(bad);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not a number", "12345", "+", "++880100000000", "0800-CALL-NOW",
            "1234567890123456789012"})
    @DisplayName("AC-4: a malformed phone number is refused, naming the field and not the value")
    void malformedPhoneRefused(String bad) {
        assertThatThrownBy(() -> PhoneNumber.of(bad))
                .isInstanceOfSatisfying(Visitor.InvalidVisitorDetail.class,
                        e -> assertThat(e.field()).isEqualTo("visitor phone"))
                .hasMessageNotContaining(bad);
    }

    @Test
    @DisplayName("AC-4: an over-long value is refused without being echoed")
    void overLongValuesRefused() {
        String longEmail = "a".repeat(320) + "@example.test";
        assertThatThrownBy(() -> EmailAddress.of(longEmail))
                .isInstanceOf(Visitor.InvalidVisitorDetail.class)
                .hasMessageNotContaining("aaa");

        assertThatThrownBy(() -> PhoneNumber.of("+" + "1".repeat(45)))
                .isInstanceOf(Visitor.InvalidVisitorDetail.class)
                .hasMessageNotContaining("111");
    }

    @Test
    @DisplayName("both are optional — absent and blank mean absent, not invalid")
    void bothAreOptional() {
        for (String blank : new String[]{null, "", "   ", "\t"}) {
            assertThat(EmailAddress.of(blank)).isNull();
            assertThat(PhoneNumber.of(blank)).isNull();
        }
    }

    @Test
    @DisplayName("a real-world spread of valid values is accepted")
    void validValuesAccepted() {
        // Singapore and Bangladesh both have to work: this is a Singapore building with a
        // Bangladeshi delivery team, and a stricter format check would lock one of them out.
        assertThat(EmailAddress.of("a.b+tag@sub.example.co.uk")).isNotNull();
        assertThat(EmailAddress.of("ada_l@example.test")).isNotNull();
        assertThat(PhoneNumber.of("+6561234567")).isNotNull();
        assertThat(PhoneNumber.of("+8801712345678")).isNotNull();
        assertThat(PhoneNumber.of("62345678")).isNotNull();
    }

    // ---- T-07.1.2.1: redaction ----

    @Test
    @DisplayName("toString on an email keeps the domain and loses the person")
    void emailToStringIsRedacted() {
        EmailAddress email = EmailAddress.of("ada.lovelace@example.test");

        assertThat(email.toString())
                .doesNotContain("ada").doesNotContain("lovelace")
                .contains("example.test");                       // still useful in a log
    }

    @Test
    @DisplayName("toString on a phone keeps two digits and loses the number")
    void phoneToStringIsRedacted() {
        PhoneNumber phone = PhoneNumber.of("+8801712345678");

        assertThat(phone.toString()).doesNotContain("880171234").endsWith("78");
    }

    @Test
    @DisplayName("a visitor interpolated into a string leaks neither email nor phone")
    void visitorFieldsDoNotLeakThroughInterpolation() {
        // The case this guards: someone writes log.info("rejected " + visitor.email()) two years
        // from now. With a String field that is a PII leak; with a value object it is redacted.
        Visitor visitor = Visitor.named("Ada Lovelace", "ada.lovelace@example.test",
                "+8801712345678", "Analytical Ltd", null);

        String interpolated = "visitor=" + visitor.email() + " phone=" + visitor.phone();

        assertThat(interpolated).doesNotContain("ada.lovelace").doesNotContain("8801712345");
    }

    // ---- AC-1: the visitor type ----

    @Test
    @DisplayName("AC-1: the visitor type id is carried on the visitor, and is optional")
    void visitorTypeIsCarried() {
        UUID type = UUID.randomUUID();

        assertThat(Visitor.named("Ada", null, null, null, type).visitorTypeId()).isEqualTo(type);
        assertThat(Visitor.named("Ada", null, null, null, null).visitorTypeId()).isNull();
    }

    @Test
    @DisplayName("the normalised values are what reach persistence")
    void persistenceSeesNormalisedValues() {
        Visitor visitor = Visitor.named("Ada", "  ADA@Example.TEST ", "+880 1712-345678", null,
                null);

        assertThat(visitor.emailValue()).isEqualTo("ada@example.test");
        assertThat(visitor.phoneValue()).isEqualTo("+8801712345678");
    }

    @Test
    @DisplayName("a stored value is rehydrated without re-validation")
    void storedValuesAreNotRevalidated() {
        // Data written before these rules existed must still load. Refusing to rehydrate it would
        // make a historical row unreadable, which is a worse outcome than a legacy oddity in memory.
        assertThat(EmailAddress.stored("Legacy Address").value()).isEqualTo("Legacy Address");
        assertThat(PhoneNumber.stored("ext. 4021").value()).isEqualTo("ext. 4021");
        assertThat(EmailAddress.stored(null)).isNull();
        assertThat(PhoneNumber.stored("  ")).isNull();
    }
}
