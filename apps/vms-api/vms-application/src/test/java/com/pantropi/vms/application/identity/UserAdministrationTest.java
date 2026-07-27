package com.pantropi.vms.application.identity;

import com.pantropi.vms.application.identity.port.AuditTrail;
import com.pantropi.vms.application.identity.port.SessionStore;
import com.pantropi.vms.application.identity.port.UserAdministrationStore;
import com.pantropi.vms.application.identity.port.UserAdministrationStore.NewUser;
import com.pantropi.vms.application.identity.port.UserAdministrationStore.Page;
import com.pantropi.vms.application.identity.port.UserAdministrationStore.UserFilter;
import com.pantropi.vms.application.identity.port.UserAdministrationStore.UserView;
import com.pantropi.vms.application.identity.usecase.UserAdministration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link UserAdministration} (US-02.2.1) — pure, fake ports. */
class UserAdministrationTest {

    private final FakeStore store = new FakeStore();
    private final FakeSessions sessions = new FakeSessions();
    private final FakeAudit audit = new FakeAudit();
    private final UserAdministration users = new UserAdministration(store, sessions, audit);
    private final UUID actor = UUID.randomUUID();

    private NewUser valid() {
        return new NewUser("jsmith", "j@ex.com", "J Smith", "FLOOR_RECEPTIONIST", null, null);
    }

    @Test
    @DisplayName("AC-1: create writes a user with no password and audits the creation")
    void createOk() {
        UUID id = users.create(actor, valid());
        assertThat(id).isNotNull();
        assertThat(store.placeholder).isEqualTo(UserAdministration.ACTIVATION_PENDING);
        assertThat(audit.actions).contains("user.created");
    }

    @Test
    @DisplayName("AC-5: a duplicate username is rejected naming the field, with no insert")
    void duplicateUsername() {
        store.usernameTaken = true;
        assertThatThrownBy(() -> users.create(actor, valid()))
                .isInstanceOfSatisfying(UserAdministration.DuplicateField.class,
                        e -> assertThat(e.field).isEqualTo("username"));
        assertThat(store.inserted).isZero();
    }

    @Test
    @DisplayName("AC-5: a duplicate email is rejected naming the field")
    void duplicateEmail() {
        store.emailTaken = true;
        assertThatThrownBy(() -> users.create(actor, valid()))
                .isInstanceOfSatisfying(UserAdministration.DuplicateField.class,
                        e -> assertThat(e.field).isEqualTo("email"));
    }

    @Test
    @DisplayName("invalid username format and unknown role are rejected before any write")
    void validationFailures() {
        assertThatThrownBy(() -> users.create(actor,
                new NewUser("ab", "j@ex.com", "J", "FLOOR_RECEPTIONIST", null, null)))
                .isInstanceOf(UserAdministration.InvalidField.class);
        store.roleKnown = false;
        assertThatThrownBy(() -> users.create(actor, valid()))
                .isInstanceOf(UserAdministration.InvalidField.class);
        assertThat(store.inserted).isZero();
    }

    @Test
    @DisplayName("an inactive reception reference is rejected")
    void inactiveReception() {
        store.receptionOk = false;
        UUID rec = UUID.randomUUID();
        assertThatThrownBy(() -> users.create(actor,
                new NewUser("jsmith", "j@ex.com", "J", "FLOOR_RECEPTIONIST", rec, null)))
                .isInstanceOfSatisfying(UserAdministration.InvalidReference.class,
                        e -> assertThat(e.field).isEqualTo("reception_id"));
    }

    @Test
    @DisplayName("AC-3: deactivation revokes the user's sessions and audits")
    void deactivateRevokesSessions() {
        UUID id = UUID.randomUUID();
        store.existing = new UserView(id, "jsmith", "j@ex.com", "J", "TENANT", null, null, true);
        users.deactivate(actor, id);
        assertThat(store.activeSet).isFalse();
        assertThat(sessions.revokedUser).isEqualTo(id);
        assertThat(audit.actions).contains("user.deactivated", "user.sessions_revoked");
    }

    // ---- fakes ----
    private static final class FakeStore implements UserAdministrationStore {
        boolean usernameTaken, emailTaken; boolean roleKnown = true; boolean receptionOk = true;
        int inserted; String placeholder; UserView existing; Boolean activeSet;
        public boolean usernameExists(String u) { return usernameTaken; }
        public boolean emailExists(String e) { return emailTaken; }
        public Optional<UUID> roleIdByCode(String r) { return roleKnown ? Optional.of(UUID.randomUUID()) : Optional.empty(); }
        public boolean receptionActive(UUID r) { return receptionOk; }
        public boolean tenantActive(UUID t) { return true; }
        public UUID insert(NewUser u, String ph) { inserted++; placeholder = ph; return UUID.randomUUID(); }
        public Optional<UserView> findById(UUID id) { return Optional.ofNullable(existing); }
        public void updateAssignment(UUID id, UUID r, UUID rec, UUID t) {}
        public void setActive(UUID id, boolean active) { activeSet = active; }
        public Page list(UserFilter f) { return new Page(List.of(), 0, 0, f.size()); }
    }

    private static final class FakeSessions implements SessionStore {
        UUID revokedUser;
        public void create(UUID s, UUID u, String un, String r, String h, java.time.Instant e) {}
        public Optional<ActiveSession> findActive(UUID s) { return Optional.empty(); }
        public RotationOutcome rotate(UUID s, String p, String n, java.time.Instant e) { return RotationOutcome.INVALID; }
        public void revoke(UUID s, String reason) {}
        public int revokeAllForUser(UUID u, String reason) { revokedUser = u; return 2; }
    }

    private static final class FakeAudit implements AuditTrail {
        final List<String> actions = new ArrayList<>();
        public void record(UUID a, String action, String et, String eid, String d) { actions.add(action); }
        public void recordChange(UUID a, String action, String et, String eid, String b, String af) { actions.add(action); }
    }
}
