package com.pantropi.vms.application.identity;

import com.pantropi.vms.application.identity.port.AuditTrail;
import com.pantropi.vms.application.identity.port.LoginAttemptStore;
import com.pantropi.vms.application.identity.port.PasswordHasher;
import com.pantropi.vms.application.identity.port.UserDirectory;
import com.pantropi.vms.application.identity.usecase.AuthenticateUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link AuthenticateUser} lockout (US-02.3.1) — pure, fake ports.
 *
 * <p>The point of this suite is AC-5: a locked account, a wrong password and an account that does
 * not exist must be indistinguishable. Each of those is asserted not only on the outcome but on the
 * <em>observable work done</em> — same exception, one hash computation each, and a counter that
 * advances identically — because an attacker measures behaviour, not intent.
 */
class AuthenticateUserTest {

    private static final int THRESHOLD = 3;
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final UUID ALICE = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");

    private final FakeDirectory users = new FakeDirectory();
    private final CountingHasher hasher = new CountingHasher();
    private final FakeAttempts attempts = new FakeAttempts();
    private final RecordingAudit audit = new RecordingAudit();
    private MutableClock clock = new MutableClock(T0);

    private AuthenticateUser authenticator() {
        return new AuthenticateUser(users, hasher, attempts, audit, clock, THRESHOLD, WINDOW);
    }

    @Test
    @DisplayName("valid credentials authenticate and clear any accumulated failures")
    void successClearsCounter() {
        attempts.failures.put("alice", 2);

        AuthenticateUser.AuthenticatedUser user = authenticator().login("alice", "right".toCharArray());

        assertThat(user.id()).isEqualTo(ALICE);
        assertThat(user.roleCode()).isEqualTo("MASTER_ADMIN");
        assertThat(user.mustChangePassword()).isFalse();
        assertThat(attempts.failures).containsEntry("alice", 0);
    }

    @Test
    @DisplayName("AC-5: the account locks on the configured failure count and stays shut afterwards")
    void locksAtThreshold() {
        for (int i = 0; i < THRESHOLD; i++) {
            assertThatThrownBy(() -> authenticator().login("alice", "wrong".toCharArray()))
                    .isInstanceOf(AuthenticateUser.InvalidCredentials.class);
        }
        assertThat(attempts.isLocked("alice", clock.instant())).isTrue();

        // Now even the correct password is refused, and refused the same way.
        assertThatThrownBy(() -> authenticator().login("alice", "right".toCharArray()))
                .isInstanceOf(AuthenticateUser.InvalidCredentials.class);
    }

    @Test
    @DisplayName("AC-5: the lock expires on its own, and the correct password then works")
    void lockExpires() {
        for (int i = 0; i < THRESHOLD; i++) {
            assertThatThrownBy(() -> authenticator().login("alice", "wrong".toCharArray()))
                    .isInstanceOf(AuthenticateUser.InvalidCredentials.class);
        }
        clock = new MutableClock(T0.plus(WINDOW).plusSeconds(1));

        assertThat(authenticator().login("alice", "right".toCharArray()).username()).isEqualTo("alice");
    }

    @Test
    @DisplayName("AC-5: probing a locked account does not extend the lock")
    void lockIsNotExtendedByFurtherAttempts() {
        for (int i = 0; i < THRESHOLD; i++) {
            assertThatThrownBy(() -> authenticator().login("alice", "wrong".toCharArray()))
                    .isInstanceOf(AuthenticateUser.InvalidCredentials.class);
        }
        Instant lockedUntil = attempts.lockedUntil.get("alice");

        clock = new MutableClock(T0.plusSeconds(60));
        assertThatThrownBy(() -> authenticator().login("alice", "wrong".toCharArray()))
                .isInstanceOf(AuthenticateUser.InvalidCredentials.class);

        assertThat(attempts.lockedUntil.get("alice")).isEqualTo(lockedUntil);
    }

    @Test
    @DisplayName("AC-5: an unknown username locks exactly like a real one, so probing tells nothing")
    void unknownUsernameLocksIdentically() {
        for (int i = 0; i < THRESHOLD; i++) {
            assertThatThrownBy(() -> authenticator().login("ghost", "wrong".toCharArray()))
                    .isInstanceOf(AuthenticateUser.InvalidCredentials.class);
        }
        assertThat(attempts.isLocked("ghost", clock.instant())).isTrue();
    }

    @Test
    @DisplayName("AC-5: wrong password, unknown account and locked account each cost one hash")
    void everyFailurePathHashesExactlyOnce() {
        // The hash dominates the response time, so an equal number of hashes is what makes the
        // three cases indistinguishable by a stopwatch.
        assertHashesOnce(() -> authenticator().login("alice", "wrong".toCharArray()));
        assertHashesOnce(() -> authenticator().login("ghost", "wrong".toCharArray()));

        attempts.lockedUntil.put("alice", clock.instant().plus(WINDOW));
        assertHashesOnce(() -> authenticator().login("alice", "right".toCharArray()));
    }

    @Test
    @DisplayName("locking is audited; an unknown username produces no user id to attribute it to")
    void lockingIsAudited() {
        for (int i = 0; i < THRESHOLD; i++) {
            assertThatThrownBy(() -> authenticator().login("ghost", "wrong".toCharArray()))
                    .isInstanceOf(AuthenticateUser.InvalidCredentials.class);
        }
        assertThat(audit.entries).hasSize(1);
        Object[] entry = audit.entries.get(0);
        assertThat(entry[0]).isNull();                    // no actor — the account does not exist
        assertThat(entry[1]).isEqualTo("account.locked");
    }

    @Test
    @DisplayName("an inactive account fails like any other, and is never authenticated")
    void inactiveAccountRefused() {
        assertThatThrownBy(() -> authenticator().login("bob", "right".toCharArray()))
                .isInstanceOf(AuthenticateUser.InvalidCredentials.class);
    }

    @Test
    @DisplayName("a null or blank username is refused without touching the directory")
    void blankUsernameRefused() {
        assertThatThrownBy(() -> authenticator().login(null, "x".toCharArray()))
                .isInstanceOf(AuthenticateUser.InvalidCredentials.class);
        assertThatThrownBy(() -> authenticator().login("   ", "x".toCharArray()))
                .isInstanceOf(AuthenticateUser.InvalidCredentials.class);
        assertThat(users.lookups).isZero();
    }

    @Test
    @DisplayName("a forced password change is reported to the caller")
    void mustChangePasswordSurfaces() {
        users.mustChange = true;
        assertThat(authenticator().login("alice", "right".toCharArray()).mustChangePassword()).isTrue();
    }

    @Test
    @DisplayName("a threshold of one is refused at construction rather than locking everyone out")
    void unusableThresholdRefused() {
        assertThatThrownBy(() ->
                new AuthenticateUser(users, hasher, attempts, audit, clock, 1, WINDOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void assertHashesOnce(Runnable attempt) {
        int before = hasher.matchCalls;
        assertThatThrownBy(attempt::run).isInstanceOf(AuthenticateUser.InvalidCredentials.class);
        assertThat(hasher.matchCalls - before).isEqualTo(1);
    }

    // ---- fakes ----

    private static final class FakeDirectory implements UserDirectory {
        int lookups;
        boolean mustChange;

        public Optional<AuthUser> findActiveByUsername(String username) {
            lookups++;
            // "bob" exists but is inactive, so the directory does not return him at all.
            return "alice".equals(username)
                    ? Optional.of(new AuthUser(ALICE, "alice", "hash:right", "MASTER_ADMIN", true,
                            mustChange))
                    : Optional.empty();
        }
    }

    /** Matches only the literal "right"; counts calls so timing-equivalence can be asserted. */
    private static final class CountingHasher implements PasswordHasher {
        int matchCalls;

        public String hash(char[] p) { return "hash:" + new String(p); }

        public boolean matches(char[] p, String stored) {
            matchCalls++;
            return stored.equals("hash:" + new String(p));
        }
    }

    /** In-memory lockout honouring the same threshold/window semantics as the JDBC adapter. */
    private static final class FakeAttempts implements LoginAttemptStore {
        final Map<String, Integer> failures = new HashMap<>();
        final Map<String, Instant> lockedUntil = new HashMap<>();

        public boolean isLocked(String username, Instant now) {
            Instant until = lockedUntil.get(username);
            return until != null && until.isAfter(now);
        }

        public boolean recordFailure(String username, Instant now, int threshold, Duration window) {
            int next = failures.getOrDefault(username, 0) + 1;
            if (next >= threshold) {
                failures.put(username, 0);
                lockedUntil.put(username, now.plus(window));
                return true;
            }
            failures.put(username, next);
            return false;
        }

        public void recordSuccess(String username) {
            failures.put(username, 0);
            lockedUntil.remove(username);
        }

        public void unlock(String username) { recordSuccess(username); }
    }

    private static final class RecordingAudit implements AuditTrail {
        final List<Object[]> entries = new ArrayList<>();

        public void record(UUID actorId, String action, String type, String id, String detail) {
            entries.add(new Object[]{actorId, action, type, id, detail});
        }
        public void recordChange(UUID a, String ac, String t, String i, String b, String af) {}
        public void recordSecurityDenial(UUID a, String ac, String p, String r, String m, String o,
                                         String ip) {}
    }

    private static final class MutableClock extends Clock {
        private final Instant now;
        MutableClock(Instant now) { this.now = now; }
        public Instant instant() { return now; }
        public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId z) { return this; }
    }
}
