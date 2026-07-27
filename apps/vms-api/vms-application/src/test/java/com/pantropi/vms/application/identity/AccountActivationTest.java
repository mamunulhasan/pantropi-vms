package com.pantropi.vms.application.identity;

import com.pantropi.vms.application.identity.port.ActivationStore;
import com.pantropi.vms.application.identity.port.PasswordHasher;
import com.pantropi.vms.application.identity.usecase.AccountActivation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link AccountActivation} (US-02.2.2, T-02.2.2.3) — pure, fake store. */
class AccountActivationTest {

    private final FakeStore store = new FakeStore();
    private final AccountActivation activation =
            new AccountActivation(store, recordingHasher(), Clock.systemUTC(), Duration.ofHours(72));

    @Test
    @DisplayName("a token is stored hashed and redeemed exactly once to set a password")
    void activateOnce() {
        UUID user = UUID.randomUUID();
        String token = activation.issueToken(user);
        assertThat(store.stored).doesNotContainKey(token); // the key is a hash, not the raw token
        assertThat(token).contains(".");

        activation.activate(token, "a-strong-password".toCharArray());
        assertThat(store.passwordSetFor).isEqualTo(user);

        // second use is rejected — single-use
        assertThatThrownBy(() -> activation.activate(token, "a-strong-password".toCharArray()))
                .isInstanceOf(AccountActivation.InvalidToken.class);
    }

    @Test
    @DisplayName("an unknown or malformed token is rejected")
    void unknownTokenRejected() {
        assertThatThrownBy(() -> activation.activate("nope.nope", "a-strong-password".toCharArray()))
                .isInstanceOf(AccountActivation.InvalidToken.class);
    }

    @Test
    @DisplayName("a weak password is refused before any redemption")
    void weakPasswordRejected() {
        String token = activation.issueToken(UUID.randomUUID());
        assertThatThrownBy(() -> activation.activate(token, "short".toCharArray()))
                .isInstanceOf(AccountActivation.WeakPassword.class);
        assertThat(store.redeemed).isZero(); // policy checked before the store is touched
    }

    private static PasswordHasher recordingHasher() {
        return new PasswordHasher() {
            public String hash(char[] p) { return "hash:" + new String(p); }
            public boolean matches(char[] p, String s) { return false; }
        };
    }

    /** In-memory activation store honouring single-use + expiry semantics. */
    private static final class FakeStore implements ActivationStore {
        final Map<String, UUID> stored = new HashMap<>();  // tokenHash -> user
        final Map<String, Boolean> used = new HashMap<>();
        UUID passwordSetFor;
        int redeemed;

        public void createToken(UUID userId, String tokenHash, Instant exp) {
            stored.put(tokenHash, userId);
            used.put(tokenHash, false);
        }
        public Optional<UUID> redeem(String tokenHash, Instant now) {
            if (!stored.containsKey(tokenHash) || used.get(tokenHash)) return Optional.empty();
            used.put(tokenHash, true);
            redeemed++;
            return Optional.of(stored.get(tokenHash));
        }
        public void setPassword(UUID userId, String hash) { passwordSetFor = userId; }
    }
}
