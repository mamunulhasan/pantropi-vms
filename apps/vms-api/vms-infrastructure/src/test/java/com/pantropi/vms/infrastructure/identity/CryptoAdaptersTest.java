package com.pantropi.vms.infrastructure.identity;

import com.pantropi.vms.application.identity.port.AccessTokenIssuer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fast unit tests for the JDK-based crypto adapters (US-02.1.1). No Spring, no I/O.
 */
class CryptoAdaptersTest {

    private final Pbkdf2PasswordHasher hasher = new Pbkdf2PasswordHasher();

    @Test
    @DisplayName("PBKDF2: a password verifies against its own hash and nothing else")
    void passwordRoundTrip() {
        String stored = hasher.hash("s3cret-password".toCharArray());
        assertThat(stored).startsWith("pbkdf2_sha256$210000$");
        assertThat(hasher.matches("s3cret-password".toCharArray(), stored)).isTrue();
        assertThat(hasher.matches("wrong".toCharArray(), stored)).isFalse();
    }

    @Test
    @DisplayName("PBKDF2: salts are random, so equal passwords hash differently")
    void saltsDiffer() {
        assertThat(hasher.hash("same".toCharArray()))
                .isNotEqualTo(hasher.hash("same".toCharArray()));
    }

    @Test
    @DisplayName("PBKDF2: a malformed stored hash is a non-match, never an exception")
    void malformedHashIsFalse() {
        assertThat(hasher.matches("x".toCharArray(), "not-a-valid-hash")).isFalse();
        assertThat(hasher.matches("x".toCharArray(), "")).isFalse();
    }

    @Test
    @DisplayName("JWT: an issued token verifies; sub/username/role survive the round trip")
    void jwtRoundTrip() {
        var issuer = new HmacJwtIssuer("this-secret-is-certainly-32-bytes-long!!", Duration.ofMinutes(10),
                Clock.systemUTC());
        UUID id = UUID.randomUUID();
        UUID sid = UUID.randomUUID();
        AccessTokenIssuer.IssuedToken t = issuer.issue(id, "alice", "MASTER_ADMIN", sid);

        Optional<AccessTokenIssuer.VerifiedToken> v = issuer.verify(t.token());
        assertThat(v).isPresent();
        assertThat(v.get().userId()).isEqualTo(id);
        assertThat(v.get().username()).isEqualTo("alice");
        assertThat(v.get().roleCode()).isEqualTo("MASTER_ADMIN");
        assertThat(v.get().sessionId()).isEqualTo(sid);
    }

    @Test
    @DisplayName("JWT: a token signed with a different secret is rejected (no signature confusion)")
    void jwtRejectsForeignSignature() {
        var issuerA = new HmacJwtIssuer("secret-A-secret-A-secret-A-secret-A!", Duration.ofMinutes(10),
                Clock.systemUTC());
        var issuerB = new HmacJwtIssuer("secret-B-secret-B-secret-B-secret-B!", Duration.ofMinutes(10),
                Clock.systemUTC());
        String tokenFromA = issuerA.issue(UUID.randomUUID(), "alice", "TENANT", UUID.randomUUID()).token();
        assertThat(issuerB.verify(tokenFromA)).isEmpty();
    }

    @Test
    @DisplayName("JWT: an expired token is rejected")
    void jwtRejectsExpired() {
        Clock past = Clock.fixed(Instant.now().minus(Duration.ofHours(1)), ZoneOffset.UTC);
        var issuer = new HmacJwtIssuer("secret-secret-secret-secret-secret!!", Duration.ofMinutes(5), past);
        String expired = issuer.issue(UUID.randomUUID(), "alice", "TENANT", UUID.randomUUID()).token();
        // verify with a now-clock: the token expired 55 minutes ago
        var nowIssuer = new HmacJwtIssuer("secret-secret-secret-secret-secret!!", Duration.ofMinutes(5),
                Clock.systemUTC());
        assertThat(nowIssuer.verify(expired)).isEmpty();
    }

    @Test
    @DisplayName("JWT: a secret shorter than 32 bytes is refused at construction")
    void jwtRejectsShortSecret() {
        assertThatThrownBy(() -> new HmacJwtIssuer("too-short", Duration.ofMinutes(5), Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
