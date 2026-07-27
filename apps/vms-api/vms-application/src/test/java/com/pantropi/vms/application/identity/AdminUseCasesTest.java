package com.pantropi.vms.application.identity;

import com.pantropi.vms.application.identity.port.AdminDirectory;
import com.pantropi.vms.application.identity.port.PasswordHasher;
import com.pantropi.vms.application.identity.usecase.BootstrapAdministrator;
import com.pantropi.vms.application.identity.usecase.MasterAdminPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the US-02.4.1 use cases — pure, no Spring, no DB. Ports are hand-stubbed so
 * each acceptance rule is exercised in isolation.
 */
class AdminUseCasesTest {

    // ---- BootstrapAdministrator (AC-5) ----

    @Test
    @DisplayName("AC-5: bootstrap creates the admin only when no user exists")
    void bootstrapCreatesOnEmpty() {
        var dir = new FakeDirectory(0, 0, true);
        var boot = new BootstrapAdministrator(dir, passthroughHasher());
        UUID id = boot.createInitialAdmin("sysadmin", "a-strong-password".toCharArray());
        assertThat(id).isNotNull();
        assertThat(dir.inserted).isTrue();
        assertThat(dir.insertedRole).isEqualTo(dir.systemAdminRoleId);
    }

    @Test
    @DisplayName("AC-5: bootstrap refuses once any user exists")
    void bootstrapRefusesWhenUsersExist() {
        var boot = new BootstrapAdministrator(new FakeDirectory(1, 0, true), passthroughHasher());
        assertThatThrownBy(() -> boot.createInitialAdmin("x", "a-strong-password".toCharArray()))
                .isInstanceOf(BootstrapAdministrator.AlreadyBootstrapped.class);
    }

    @Test
    @DisplayName("AC-5: a short password is refused (interim policy)")
    void bootstrapRejectsWeakPassword() {
        var boot = new BootstrapAdministrator(new FakeDirectory(0, 0, true), passthroughHasher());
        assertThatThrownBy(() -> boot.createInitialAdmin("x", "short".toCharArray()))
                .isInstanceOf(BootstrapAdministrator.WeakPassword.class);
    }

    @Test
    @DisplayName("bootstrap fails clearly if the SYSTEM_ADMIN role is not seeded")
    void bootstrapNeedsRole() {
        var dir = new FakeDirectory(0, 0, false); // role absent
        var boot = new BootstrapAdministrator(dir, passthroughHasher());
        assertThatThrownBy(() -> boot.createInitialAdmin("x", "a-strong-password".toCharArray()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SYSTEM_ADMIN");
    }

    // ---- MasterAdminPolicy (AC-3, AC-4) ----

    @Test
    @DisplayName("AC-3: assigning a non-central reception to a Master Admin is refused")
    void receptionMustBeCentral() {
        var central = new FakeDirectory(0, 0, true);
        central.centralReception = UUID.randomUUID();
        var policy = new MasterAdminPolicy(central);
        policy.assertReceptionAssignable(central.centralReception); // ok
        assertThatThrownBy(() -> policy.assertReceptionAssignable(UUID.randomUUID()))
                .isInstanceOf(MasterAdminPolicy.ReceptionNotCentral.class);
        assertThatThrownBy(() -> policy.assertReceptionAssignable(null))
                .isInstanceOf(MasterAdminPolicy.ReceptionNotCentral.class);
    }

    @Test
    @DisplayName("AC-4: releasing the last active Master Admin is refused; a second one is allowed")
    void lastMasterAdminProtected() {
        assertThatThrownBy(() -> new MasterAdminPolicy(new FakeDirectory(1, 1, true))
                .assertCanReleaseMasterAdmin())
                .isInstanceOf(MasterAdminPolicy.LastMasterAdmin.class);
        // two active → releasing one is fine
        new MasterAdminPolicy(new FakeDirectory(2, 2, true)).assertCanReleaseMasterAdmin();
    }

    // ---- fakes ----

    private static PasswordHasher passthroughHasher() {
        return new PasswordHasher() {
            public String hash(char[] p) { return "hash:" + new String(p); }
            public boolean matches(char[] p, String stored) { return stored.equals("hash:" + new String(p)); }
        };
    }

    private static final class FakeDirectory implements AdminDirectory {
        final long users;
        final long masterAdmins;
        final boolean hasSystemAdminRole;
        final UUID systemAdminRoleId = UUID.randomUUID();
        UUID centralReception;
        boolean inserted;
        UUID insertedRole;

        FakeDirectory(long users, long masterAdmins, boolean hasSystemAdminRole) {
            this.users = users;
            this.masterAdmins = masterAdmins;
            this.hasSystemAdminRole = hasSystemAdminRole;
        }

        public long countUsers() { return users; }
        public long countActiveUsersWithRole(String roleCode) { return masterAdmins; }
        public Optional<UUID> roleIdByCode(String roleCode) {
            return hasSystemAdminRole ? Optional.of(systemAdminRoleId) : Optional.empty();
        }
        public boolean receptionIsCentral(UUID receptionId) {
            return receptionId != null && receptionId.equals(centralReception);
        }
        public void insertUser(UUID id, String u, String h, UUID roleId, UUID rec, boolean active) {
            inserted = true; insertedRole = roleId;
        }
    }
}
