package com.pantropi.vms.application.identity;

import com.pantropi.vms.application.identity.port.AccessTokenIssuer;
import com.pantropi.vms.application.identity.port.AuditTrail;
import com.pantropi.vms.application.identity.port.SessionStore;
import com.pantropi.vms.application.identity.usecase.SessionManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SessionManager} (US-02.1.2) — pure, fake ports.
 */
class SessionManagerTest {

    private final FakeStore store = new FakeStore();
    private final FakeAudit audit = new FakeAudit();
    private final SessionManager mgr = new SessionManager(
            store, fixedIssuer(), audit, Clock.systemUTC(), Duration.ofMinutes(60));

    @Test
    @DisplayName("openSession stores a session and returns an access+refresh pair")
    void openSession() {
        var t = mgr.openSession(UUID.randomUUID(), "alice", "TENANT");
        assertThat(t.accessToken()).isNotBlank();
        assertThat(t.refreshToken()).contains(".");
        assertThat(store.created).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-1: refresh rotates — new pair issued, old refresh no longer valid")
    void refreshRotates() {
        var opened = mgr.openSession(UUID.randomUUID(), "alice", "TENANT");
        store.rotationOutcome = SessionStore.RotationOutcome.ROTATED;
        var rotated = mgr.refresh(opened.refreshToken());
        assertThat(rotated.refreshToken()).isNotEqualTo(opened.refreshToken());
        assertThat(store.rotateCalls).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-5: a replayed refresh token revokes the family, audits, and 401s")
    void replayRevokesFamily() {
        var opened = mgr.openSession(UUID.randomUUID(), "alice", "TENANT");
        store.rotationOutcome = SessionStore.RotationOutcome.REPLAY_DETECTED;
        assertThatThrownBy(() -> mgr.refresh(opened.refreshToken()))
                .isInstanceOf(SessionManager.InvalidRefreshToken.class);
        assertThat(store.revoked).containsKey(store.lastSessionId);
        assertThat(audit.actions).contains("session.replay_detected");
    }

    @Test
    @DisplayName("refresh of an unknown session is rejected without touching rotate")
    void refreshUnknownSession() {
        store.active = false;
        assertThatThrownBy(() -> mgr.refresh(UUID.randomUUID() + ".secret"))
                .isInstanceOf(SessionManager.InvalidRefreshToken.class);
        assertThat(store.rotateCalls).isZero();
    }

    @Test
    @DisplayName("AC-2: logout revokes the session and writes an audit event")
    void logout() {
        UUID sid = UUID.randomUUID();
        mgr.logout(sid, UUID.randomUUID());
        assertThat(store.revoked).containsKey(sid);
        assertThat(audit.actions).contains("session.logout");
    }

    // ---- fakes ----
    private static AccessTokenIssuer fixedIssuer() {
        return new AccessTokenIssuer() {
            public IssuedToken issue(UUID u, String n, String r, UUID sid) {
                return new IssuedToken("access-" + sid, Instant.now().plusSeconds(900));
            }
            public Optional<VerifiedToken> verify(String t) { return Optional.empty(); }
        };
    }

    private static final class FakeStore implements SessionStore {
        int created; int rotateCalls;
        boolean active = true;
        RotationOutcome rotationOutcome = RotationOutcome.ROTATED;
        UUID lastSessionId;
        boolean createdWithMustChangePassword;
        boolean sessionOwesPasswordChange;
        final java.util.Map<UUID, String> revoked = new java.util.HashMap<>();

        public void create(UUID sid, UUID u, String un, String r, String h, Instant e, boolean mcp) {
            created++; lastSessionId = sid; createdWithMustChangePassword = mcp;
        }
        public Optional<ActiveSession> findActive(UUID sid) {
            lastSessionId = sid;
            return active ? Optional.of(new ActiveSession(sid, UUID.randomUUID(), "alice", "TENANT",
                    Instant.now().plusSeconds(3600), sessionOwesPasswordChange)) : Optional.empty();
        }
        public RotationOutcome rotate(UUID sid, String p, String n, Instant e) {
            rotateCalls++; return rotationOutcome;
        }
        public void revoke(UUID sid, String reason) { revoked.put(sid, reason); }
        public int revokeAllForUser(UUID u, String reason) { return 0; }
    }

    private static final class FakeAudit implements AuditTrail {
        final List<String> actions = new ArrayList<>();
        public void record(UUID u, String action, String et, String eid, String detail) {
            actions.add(action);
        }
        public void recordChange(UUID u, String action, String et, String eid, String b, String af) {
            actions.add(action);
        }
        public void recordSecurityDenial(UUID a, String action, String p, String r,
                                         String m, String o, String ip) { actions.add(action); }
    }
}
