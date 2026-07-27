package com.pantropi.vms.application.identity;

import com.pantropi.vms.application.identity.usecase.PasswordPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link PasswordPolicy} (US-02.3.1, T-02.3.1.1) — pure, no I/O. */
class PasswordPolicyTest {

    private final PasswordPolicy policy = PasswordPolicy.standard();

    @Test
    @DisplayName("AC-1: a long passphrase is accepted with no composition requirements")
    void passphraseAccepted() {
        // No upper case, no digit, no symbol — and that is deliberately fine.
        assertThatCode(() -> policy.validate("correct horse battery staple".toCharArray()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("AC-1: too short is refused, and the message states the requirement")
    void tooShortRefused() {
        assertThatThrownBy(() -> policy.validate("short1234".toCharArray()))
                .isInstanceOf(PasswordPolicy.WeakPassword.class)
                .hasMessageContaining("at least 12");
    }

    @Test
    @DisplayName("an unbounded password is refused, so hashing cannot be turned into a DoS")
    void tooLongRefused() {
        assertThatThrownBy(() -> policy.validate("a".repeat(201).toCharArray()))
                .isInstanceOf(PasswordPolicy.WeakPassword.class)
                .hasMessageContaining("at most 200");
    }

    @ParameterizedTest
    @ValueSource(strings = {"password1234", "PASSWORD1234", "Passw0rd1234", "administrator",
                            "qwerty123456"})
    @DisplayName("AC-1: denylisted passwords are refused regardless of case or trailing digits")
    void denylistedRefused(String candidate) {
        assertThat(candidate.length()).isGreaterThanOrEqualTo(12);   // length is not what fails it
        assertThatThrownBy(() -> policy.validate(candidate.toCharArray()))
                .isInstanceOf(PasswordPolicy.WeakPassword.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"aaaaaaaaaaaa", "abcdefghijkl", "9876543210987654", "198703121985"})
    @DisplayName("AC-1: repeated, sequential and all-digit passwords are refused despite the length")
    void predictableRefused(String candidate) {
        assertThatThrownBy(() -> policy.validate(candidate.toCharArray()))
                .isInstanceOf(PasswordPolicy.WeakPassword.class);
    }

    @Test
    @DisplayName("AC-1: the rejection never echoes the candidate nor names the rule that matched")
    void guidanceLeaksNothing() {
        // The strongest form of the requirement: whatever the reason, the message must not be a
        // channel back to the attacker. Assert on the message of every rejection path.
        for (String weak : new String[]{"short", "password1234", "aaaaaaaaaaaa", "abcdefghijkl"}) {
            String message = catchMessage(weak);
            assertThat(message).doesNotContain(weak);
            assertThat(message.toLowerCase()).doesNotContain("denylist").doesNotContain("blocklist");
        }
    }

    @Test
    @DisplayName("a null password is refused rather than accepted or thrown as NPE")
    void nullRefused() {
        assertThatThrownBy(() -> policy.validate(null))
                .isInstanceOf(PasswordPolicy.WeakPassword.class);
    }

    private String catchMessage(String candidate) {
        try {
            policy.validate(candidate.toCharArray());
            throw new AssertionError("expected " + candidate + " to be refused");
        } catch (PasswordPolicy.WeakPassword e) {
            return e.getMessage();
        }
    }
}
