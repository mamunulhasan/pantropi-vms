package com.pantropi.vms.application.identity;

import com.pantropi.vms.application.identity.port.ActivationStore;
import com.pantropi.vms.application.identity.port.AuditTrail;
import com.pantropi.vms.application.identity.port.CredentialStore;
import com.pantropi.vms.application.identity.port.LoginAttemptStore;
import com.pantropi.vms.application.identity.port.PasswordHasher;
import com.pantropi.vms.application.identity.port.SessionStore;
import com.pantropi.vms.application.identity.usecase.AccountActivation;
import com.pantropi.vms.application.identity.usecase.AccountRecovery;
import com.pantropi.vms.application.identity.usecase.AuthenticateUser;
import com.pantropi.vms.application.identity.usecase.ChangePassword;
import com.pantropi.vms.application.identity.usecase.PasswordPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ChangePassword} and {@link AccountRecovery} (US-02.3.1) — pure, fake ports.
 *
 * <p>Two properties carry most of the security value here and are asserted directly: changing a
 * password revokes every session, and an administrator has no way to <em>choose</em> a password —
 * recovery only ever issues a token.
 */
class PasswordChangeTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID ADMIN = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

    private final FakeCredentials credentials = new FakeCredentials();
    private final PlainHasher hasher = new PlainHasher();
    private final FakeSessions sessions = new FakeSessions();
    private final FakeAttempts attempts = new FakeAttempts();
    private final RecordingAudit audit = new RecordingAudit();
    private final FakeActivationStore activationStore = new FakeActivationStore();

    private final ChangePassword changePassword = new ChangePassword(
            credentials, hasher, PasswordPolicy.standard(), sessions, attempts, audit,
            Clock.fixed(NOW, java.time.ZoneOffset.UTC));

    private final AccountRecovery recovery = new AccountRecovery(
            credentials, attempts, sessions,
            new AccountActivation(activationStore, hasher,
                    Clock.fixed(NOW, java.time.ZoneOffset.UTC), Duration.ofHours(72)),
            audit);

    // ---- self-service change ----

    @Test
    @DisplayName("a correct current password and an acceptable new one replaces the hash")
    void changeSucceeds() {
        changePassword.change(ALICE, "old-password-here".toCharArray(),
                "a brand new passphrase".toCharArray());

        assertThat(credentials.hash).isEqualTo("hash:a brand new passphrase");
        assertThat(credentials.changedAt).isEqualTo(NOW);
        assertThat(credentials.mustChange).isFalse();
    }

    @Test
    @DisplayName("changing the password revokes every session, including the one that asked")
    void changeRevokesAllSessions() {
        changePassword.change(ALICE, "old-password-here".toCharArray(),
                "a brand new passphrase".toCharArray());

        assertThat(sessions.revokedFor).containsEntry(ALICE, "password_changed");
    }

    @Test
    @DisplayName("changing the password clears an outstanding lockout")
    void changeClearsLockout() {
        attempts.locked.add("alice");
        changePassword.change(ALICE, "old-password-here".toCharArray(),
                "a brand new passphrase".toCharArray());
        assertThat(attempts.locked).isEmpty();
    }

    @Test
    @DisplayName("a wrong current password is refused, audited, and changes nothing")
    void wrongCurrentPasswordRefused() {
        assertThatThrownBy(() -> changePassword.change(ALICE, "not-the-password".toCharArray(),
                "a brand new passphrase".toCharArray()))
                .isInstanceOf(AuthenticateUser.InvalidCredentials.class);

        assertThat(credentials.hash).isEqualTo("hash:old-password-here");   // untouched
        assertThat(sessions.revokedFor).isEmpty();
        assertThat(audit.actions()).containsExactly("password.change_denied");
    }

    @Test
    @DisplayName("a new password failing policy is refused before anything is written")
    void weakNewPasswordRefused() {
        assertThatThrownBy(() -> changePassword.change(ALICE, "old-password-here".toCharArray(),
                "short".toCharArray()))
                .isInstanceOf(PasswordPolicy.WeakPassword.class);

        assertThat(credentials.hash).isEqualTo("hash:old-password-here");
        assertThat(sessions.revokedFor).isEmpty();
    }

    @Test
    @DisplayName("re-setting the same password is refused, so the rotation stamp stays honest")
    void reusingCurrentPasswordRefused() {
        assertThatThrownBy(() -> changePassword.change(ALICE, "old-password-here".toCharArray(),
                "old-password-here".toCharArray()))
                .isInstanceOf(ChangePassword.PasswordUnchanged.class);

        assertThat(sessions.revokedFor).isEmpty();
    }

    @Test
    @DisplayName("the audit records that a change happened and never the password or its hash")
    void auditCarriesNoSecret() {
        changePassword.change(ALICE, "old-password-here".toCharArray(),
                "a brand new passphrase".toCharArray());

        assertThat(audit.actions()).containsExactly("password.changed");
        String detail = String.valueOf(audit.entries.get(0)[4]);
        assertThat(detail).doesNotContain("passphrase").doesNotContain("hash:");
    }

    @Test
    @DisplayName("password arrays are wiped after use, so they do not linger in the heap")
    void passwordArraysAreWiped() {
        char[] current = "old-password-here".toCharArray();
        char[] proposed = "a brand new passphrase".toCharArray();

        changePassword.change(ALICE, current, proposed);

        assertThat(new String(current)).isEqualTo("\0".repeat(current.length));
        assertThat(new String(proposed)).isEqualTo("\0".repeat(proposed.length));
    }

    @Test
    @DisplayName("a deactivated user cannot change a password even holding a live token")
    void deactivatedUserRefused() {
        credentials.exists = false;
        assertThatThrownBy(() -> changePassword.change(ALICE, "old-password-here".toCharArray(),
                "a brand new passphrase".toCharArray()))
                .isInstanceOf(AuthenticateUser.InvalidCredentials.class);
    }

    // ---- administrator recovery ----

    @Test
    @DisplayName("an administrator unlock clears the lockout and is attributed to the administrator")
    void adminUnlock() {
        attempts.locked.add("alice");

        recovery.unlock(ADMIN, ALICE);

        assertThat(attempts.locked).isEmpty();
        assertThat(audit.actions()).containsExactly("account.unlocked");
        assertThat(audit.entries.get(0)[0]).isEqualTo(ADMIN);
    }

    @Test
    @DisplayName("an administrator reset issues a token and never sets a password value")
    void adminResetIssuesTokenOnly() {
        String token = recovery.reset(ADMIN, ALICE);

        assertThat(token).isNotBlank();
        assertThat(activationStore.tokensIssued).isEqualTo(1);
        // The decisive assertion: the stored hash is exactly what it was.
        assertThat(credentials.hash).isEqualTo("hash:old-password-here");
        assertThat(credentials.mustChange).isTrue();
        assertThat(sessions.revokedFor).containsEntry(ALICE, "password_reset");
    }

    @Test
    @DisplayName("the reset audit does not contain the token that was issued")
    void resetAuditOmitsToken() {
        String token = recovery.reset(ADMIN, ALICE);

        assertThat(audit.actions()).containsExactly("password.reset_requested");
        for (Object field : audit.entries.get(0)) {
            assertThat(String.valueOf(field)).doesNotContain(token);
        }
    }

    @Test
    @DisplayName("recovering an unknown or deactivated account is refused")
    void recoveryOfUnknownAccountRefused() {
        credentials.exists = false;
        assertThatThrownBy(() -> recovery.unlock(ADMIN, ALICE))
                .isInstanceOf(AccountRecovery.UnknownAccount.class);
        assertThatThrownBy(() -> recovery.reset(ADMIN, ALICE))
                .isInstanceOf(AccountRecovery.UnknownAccount.class);
        assertThat(activationStore.tokensIssued).isZero();
    }

    // ---- fakes ----

    private static final class FakeCredentials implements CredentialStore {
        boolean exists = true;
        String hash = "hash:old-password-here";
        Instant changedAt;
        boolean mustChange;

        public Optional<Credential> findById(UUID userId) {
            return exists && ALICE.equals(userId)
                    ? Optional.of(new Credential(ALICE, "alice", hash)) : Optional.empty();
        }
        public void updatePassword(UUID userId, String newHash, Instant when) {
            hash = newHash; changedAt = when; mustChange = false;
        }
        public void requirePasswordChange(UUID userId) { mustChange = true; }
    }

    private static final class PlainHasher implements PasswordHasher {
        public String hash(char[] p) { return "hash:" + new String(p); }
        public boolean matches(char[] p, String stored) {
            return stored.equals("hash:" + new String(p));
        }
    }

    private static final class FakeSessions implements SessionStore {
        final Map<UUID, String> revokedFor = new HashMap<>();

        public void create(UUID s, UUID u, String un, String r, String h, Instant e, boolean m) {}
        public Optional<ActiveSession> findActive(UUID s) { return Optional.empty(); }
        public RotationOutcome rotate(UUID s, String p, String n, Instant e) {
            return RotationOutcome.INVALID;
        }
        public void revoke(UUID s, String reason) {}
        public int revokeAllForUser(UUID userId, String reason) {
            revokedFor.put(userId, reason);
            return 2;
        }
    }

    private static final class FakeAttempts implements LoginAttemptStore {
        final Set<String> locked = new HashSet<>();

        public boolean isLocked(String username, Instant now) { return locked.contains(username); }
        public boolean recordFailure(String u, Instant n, int t, Duration w) { return false; }
        public void recordSuccess(String username) { locked.remove(username); }
        public void unlock(String username) { locked.remove(username); }
    }

    private static final class FakeActivationStore implements ActivationStore {
        int tokensIssued;
        public void createToken(UUID userId, String tokenHash, Instant expiresAt) { tokensIssued++; }
        public Optional<UUID> redeem(String tokenHash, Instant now) { return Optional.empty(); }
        public void setPassword(UUID userId, String passwordHash) {
            throw new AssertionError("recovery must never set a password value");
        }
    }

    private static final class RecordingAudit implements AuditTrail {
        final List<Object[]> entries = new ArrayList<>();

        public void record(UUID actorId, String action, String type, String id, String detail) {
            entries.add(new Object[]{actorId, action, type, id, detail});
        }
        public void recordChange(UUID a, String ac, String t, String i, String b, String af) {}
        public void recordSecurityDenial(UUID a, String ac, String p, String r, String m, String o,
                                         String ip) {}

        List<String> actions() {
            return entries.stream().map(e -> (String) e[1]).toList();
        }
    }
}
