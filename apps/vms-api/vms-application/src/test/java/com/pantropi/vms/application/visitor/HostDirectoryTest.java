package com.pantropi.vms.application.visitor;

import com.pantropi.vms.application.identity.port.AuditTrail;
import com.pantropi.vms.application.shared.port.TransactionRunner;
import com.pantropi.vms.application.visitor.port.HostRepository;
import com.pantropi.vms.application.visitor.port.TenantDirectory;
import com.pantropi.vms.application.visitor.usecase.HostDirectory;
import com.pantropi.vms.domain.identity.ScopeFilter;
import com.pantropi.vms.domain.visitor.Host;
import com.pantropi.vms.domain.visitor.Visitor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * UNIT TESTS for {@link HostDirectory} (US-10.1.1, T-10.1.1.1).
 *
 * <p>The properties under test are the ones that make a host directory safe to expose: the tenant
 * is taken from the acting user and never from a caller, a host outside the scope is
 * indistinguishable from one that never existed, and nothing anywhere can delete.
 */
class HostDirectoryTest {

    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID TENANT = UUID.randomUUID();

    private final FakeHosts hosts = new FakeHosts();
    private final FakeTenants tenants = new FakeTenants();
    private final FakeAudit audit = new FakeAudit();
    private final DirectRunner tx = new DirectRunner();
    @SuppressWarnings("unused") private ScopeFilter lastFilter;

    private HostDirectory directory(ScopeFilter filter) {
        lastFilter = filter;
        return new HostDirectory(hosts, tenants, audit, tx);
    }

    @Test
    @DisplayName("AC-1: the tenant comes from the acting user's own record, and the host is active")
    void tenantComesFromTheUser() {
        tenants.tenant = TENANT;

        UUID id = directory(new ScopeFilter.OwnTenant(TENANT))
                .create(ACTOR, "Jia Tan", "j.tan@acme.test", "+8801712345678");

        assertThat(hosts.saved.tenantId()).isEqualTo(TENANT);
        assertThat(hosts.saved.active()).isTrue();
        assertThat(hosts.saved.id()).isEqualTo(id);
        // Normalised on the way in, once.
        assertThat(hosts.saved.emailValue()).isEqualTo("j.tan@acme.test");
    }

    @Test
    @DisplayName("AC-1: a user belonging to no tenant has no directory to add to")
    void userWithoutTenantIsRefused() {
        tenants.tenant = null;

        assertThatThrownBy(() -> directory(new ScopeFilter.Unrestricted())
                .create(ACTOR, "Jia Tan", null, null))
                .isInstanceOf(HostDirectory.NoTenantForUser.class);

        assertThat(hosts.saved).isNull();
        assertThat(audit.actions).isEmpty();
    }

    @Test
    @DisplayName("AC-4: a host the scope cannot see is reported exactly as one that never existed")
    void outOfScopeIsIndistinguishableFromUnknown() {
        hosts.visible = null;   // the repository's scoped read found nothing

        HostDirectory unit = directory(new ScopeFilter.OwnTenant(TENANT));
        UUID someoneElses = UUID.randomUUID();

        assertThatThrownBy(() -> unit.get(someoneElses))
                .isInstanceOf(HostDirectory.HostNotFound.class);
        assertThatThrownBy(() -> unit.update(ACTOR, someoneElses, "New Name", null, null))
                .isInstanceOf(HostDirectory.HostNotFound.class);
        assertThatThrownBy(() -> unit.deactivate(ACTOR, someoneElses))
                .isInstanceOf(HostDirectory.HostNotFound.class);
    }

    @Test
    @DisplayName("AC-3: deactivation keeps the record — the audit says so and nothing is removed")
    void deactivationKeepsTheRecord() {
        Host existing = Host.stored(UUID.randomUUID(), TENANT, "Jia Tan", "j@acme.test", null, true);
        hosts.visible = existing;

        directory(new ScopeFilter.OwnTenant(TENANT)).deactivate(ACTOR, existing.id());

        assertThat(hosts.updated.active()).isFalse();
        assertThat(hosts.updated.id()).isEqualTo(existing.id());     // same row, still there
        assertThat(hosts.updated.tenantId()).isEqualTo(TENANT);
        assertThat(audit.actions).contains("host.deactivated");
    }

    @Test
    @DisplayName("deactivating an already-inactive host writes nothing and audits nothing")
    void deactivationIsIdempotent() {
        hosts.visible = Host.stored(UUID.randomUUID(), TENANT, "Jia Tan", null, null, false);

        directory(new ScopeFilter.OwnTenant(TENANT)).deactivate(ACTOR, hosts.visible.id());

        assertThat(hosts.updated).isNull();
        assertThat(audit.actions).isEmpty();
    }

    @Test
    @DisplayName("AC-5: the port offers no way to delete a host")
    void thereIsNoDelete() {
        // Structural, not a rule someone has to remember: if a delete method existed, some call
        // site would eventually use it and orphan the visits the host received.
        assertThat(HostRepository.class.getMethods())
                .extracting(java.lang.reflect.Method::getName)
                .doesNotContain("delete", "remove", "purge");
    }

    @Test
    @DisplayName("the audit records that contact details exist, never what they are")
    void auditCarriesFlagsNotContacts() {
        tenants.tenant = TENANT;

        directory(new ScopeFilter.OwnTenant(TENANT))
                .create(ACTOR, "Jia Tan", "j.tan@acme.test", "+8801712345678");

        assertThat(audit.payloads).anySatisfy(p -> assertThat(p)
                .contains("\"hasEmail\":true").contains("\"hasPhone\":true")
                .doesNotContain("j.tan@acme.test").doesNotContain("8801712345678"));
    }

    @Test
    @DisplayName("a blank name is refused by the domain, naming the field and not the value")
    void blankNameIsRefused() {
        tenants.tenant = TENANT;

        assertThatThrownBy(() -> directory(new ScopeFilter.OwnTenant(TENANT))
                .create(ACTOR, "   ", null, null))
                .isInstanceOfSatisfying(Visitor.InvalidVisitorDetail.class,
                        e -> assertThat(e.field()).isEqualTo("host name"));
    }

    @Test
    @DisplayName("a correction keeps the host's identity, tenant and active state")
    void correctionKeepsIdentity() {
        Host existing = Host.stored(UUID.randomUUID(), TENANT, "Jia Tan", "old@acme.test", null, true);
        hosts.visible = existing;

        directory(new ScopeFilter.OwnTenant(TENANT))
                .update(ACTOR, existing.id(), "Jia Tan-Wong", "new@acme.test", null);

        assertThat(hosts.updated.id()).isEqualTo(existing.id());
        assertThat(hosts.updated.tenantId()).isEqualTo(TENANT);
        assertThat(hosts.updated.active()).isTrue();
        assertThat(hosts.updated.fullName()).isEqualTo("Jia Tan-Wong");
    }

    @Test
    @DisplayName("toString identifies a host without disclosing how to contact them")
    void toStringDoesNotLeakContacts() {
        String rendered = Host.stored(UUID.randomUUID(), TENANT, "Jia Tan", "j.tan@acme.test",
                "+8801712345678", true).toString();

        assertThat(rendered).contains("Jia Tan");
        assertThat(rendered).doesNotContain("j.tan@acme.test").doesNotContain("8801712345678");
    }

    // ---- fakes ----

    private static final class FakeHosts implements HostRepository {
        Host saved;
        Host updated;
        Host visible;

        @Override public void save(Host host) {
            saved = host;
        }

        @Override public Optional<Host> findById(UUID id) {
            return Optional.ofNullable(visible);
        }

        @Override public boolean update(Host host) {
            updated = host;
            return true;
        }

        @Override public Page list(Query query) {
            return new Page(List.of(), 0L, query.page(), query.size());
        }
    }

    private static final class FakeTenants implements TenantDirectory {
        UUID tenant;

        @Override public Optional<UUID> tenantOfUser(UUID userId) {
            return Optional.ofNullable(tenant);
        }

        @Override public boolean hostBelongsToTenant(UUID hostId, UUID tenantId) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FakeAudit implements AuditTrail {
        final List<String> actions = new ArrayList<>();
        final List<String> payloads = new ArrayList<>();

        @Override public void record(UUID actorId, String action, String entityType,
                                     String entityId, String detail) {
            actions.add(action);
        }

        @Override public void recordChange(UUID actorId, String action, String entityType,
                                           String entityId, String beforeJson, String afterJson) {
            actions.add(action);
            payloads.add(String.valueOf(afterJson));
        }

        @Override public void recordSecurityDenial(UUID actorId, String action,
                                                   String attemptedPermission, String route,
                                                   String method, String outcome, String ip) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class DirectRunner implements TransactionRunner {
        @Override public <T> T call(Supplier<T> work) {
            return work.get();
        }
    }

}
