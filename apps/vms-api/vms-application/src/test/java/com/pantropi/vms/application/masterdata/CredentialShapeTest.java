package com.pantropi.vms.application.masterdata;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CredentialShape} (US-04.8.2, T-04.8.2.2, AC-4) — pure, no I/O.
 *
 * <p>Every string below is fabricated and authenticates nothing. Two of them nonetheless match
 * gitleaks' vendor patterns, which is unavoidable: a test proving a credential detector works has to
 * contain things that look like credentials. Those two carry an inline {@code gitleaks:allow} rather
 * than a path entry in {@code .gitleaks.toml} — the suppression then sits on the line it excuses,
 * where a reviewer sees it, instead of quietly exempting the whole file from scanning.
 */
class CredentialShapeTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "-----BEGIN RSA PRIVATE KEY-----",
            "Bearer abcdefghijklmnop",
            "AKIAIOSFODNN7EXAMPLE",
            "ghp_16CharsAndThenSomeMore00",
            "sk_live_abcdefghijklmnopqrst",   // gitleaks:allow — fabricated, matches a vendor pattern
            "xoxb-1234-5678-abcdefg",
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.sig",
            "postgres://user:hunter2@db.internal:5432/vms",
            "password=hunter2",
            "api_key=abcdef123456",   // gitleaks:allow — fabricated, matches a vendor pattern
            "aVeryLongOpaqueToken1234567890abcdef",   // long, mixed letters and digits
            "c29tZSBiYXNlNjQgY29udGVudA=="            // base64 padding
    })
    @DisplayName("AC-4: credential-shaped values are recognised")
    void credentialShapesDetected(String candidate) {
        assertThat(CredentialShape.looksLikeCredential(candidate)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "12", "true", "false", "5",
            "Westgate Tower",
            "Asia/Dhaka",
            "reception@westgate.example",
            "A short note about this setting",
            "every visitor pass expires at midnight"   // long, but has spaces — prose, not a key
    })
    @DisplayName("ordinary configuration values are not mistaken for credentials")
    void ordinaryValuesAllowed(String candidate) {
        assertThat(CredentialShape.looksLikeCredential(candidate)).isFalse();
    }

    @Test
    @DisplayName("null and blank are not credentials — those are other errors' business")
    void nullAndBlankAreNotCredentials() {
        assertThat(CredentialShape.looksLikeCredential(null)).isFalse();
        assertThat(CredentialShape.looksLikeCredential("   ")).isFalse();
    }

    @Test
    @DisplayName("the refusal names the key and points at the secrets mechanism, not just 'no'")
    void refusalIsActionable() {
        String message = CredentialShape.refusalMessage("integration.api.key");

        assertThat(message).contains("integration.api.key").contains("F-05.2");
        // It must explain *why* settings are the wrong place, or an operator will just try again.
        assertThat(message).contains("masterdata.view").contains("audit");
    }

    @Test
    @DisplayName("the check is case-insensitive — a pasted key is not laundered by capitalisation")
    void detectionIsCaseInsensitive() {
        assertThat(CredentialShape.looksLikeCredential("BEARER sometokenvalue")).isTrue();
        assertThat(CredentialShape.looksLikeCredential("-----Begin Private Key-----")).isTrue();
    }
}
