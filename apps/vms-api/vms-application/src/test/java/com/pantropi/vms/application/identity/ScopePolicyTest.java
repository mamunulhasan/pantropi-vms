package com.pantropi.vms.application.identity;

import com.pantropi.vms.application.identity.port.ScopeContext;
import com.pantropi.vms.application.identity.usecase.ScopePolicy;
import com.pantropi.vms.domain.identity.ScopeFilter;
import com.pantropi.vms.domain.identity.ScopedEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ScopePolicy} (US-03.4.1, T-03.4.1.2) — pure, fake context.
 *
 * <p>The shipped policy is strict own-scope per ADR-0004, not the backlog's deny-all. What is
 * asserted here is that every way of <em>failing</em> to determine a scope still ends in seeing
 * nothing, which is the part of AC-3's intent that survives the ADR.
 */
class ScopePolicyTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID RECEPTION = UUID.randomUUID();

    private final FakeContext context = new FakeContext();
    private final ScopePolicy policy = new ScopePolicy(context);

    @ParameterizedTest
    @ValueSource(strings = {"MASTER_ADMIN", "FM_ADMIN", "SYSTEM_ADMIN"})
    @DisplayName("a building-wide role sees everything of a scoped type")
    void buildingWideRolesAreUnrestricted(String role) {
        context.scope = new ScopeContext.Scope(USER, role, null, null);

        assertThat(policy.filterFor(ScopedEntity.VISITOR_REQUEST))
                .isInstanceOf(ScopeFilter.Unrestricted.class);
    }

    @Test
    @DisplayName("a tenant user is confined to their own tenant")
    void tenantSeesOwnTenant() {
        context.scope = new ScopeContext.Scope(USER, "TENANT", TENANT, null);

        assertThat(policy.filterFor(ScopedEntity.VISITOR_REQUEST))
                .isEqualTo(new ScopeFilter.OwnTenant(TENANT));
    }

    @Test
    @DisplayName("a receptionist is confined to their own reception")
    void receptionistSeesOwnReception() {
        context.scope = new ScopeContext.Scope(USER, "FLOOR_RECEPTIONIST", null, RECEPTION);

        assertThat(policy.filterFor(ScopedEntity.VISITOR_REQUEST))
                .isEqualTo(new ScopeFilter.OwnReception(RECEPTION));
    }

    // ---- every way of not knowing ends in seeing nothing ----

    @Test
    @DisplayName("an unauthenticated request sees nothing")
    void noPrincipalDeniesAll() {
        context.scope = null;

        assertThat(policy.filterFor(ScopedEntity.VISITOR_REQUEST)).isEqualTo(ScopeFilter.DENY_ALL);
    }

    @Test
    @DisplayName("a tenant user with no tenant sees nothing, rather than everything")
    void tenantWithoutTenantDeniesAll() {
        // The dangerous alternative is treating "no scoping column" as "no restriction".
        context.scope = new ScopeContext.Scope(USER, "TENANT", null, null);

        assertThat(policy.filterFor(ScopedEntity.VISITOR_REQUEST)).isEqualTo(ScopeFilter.DENY_ALL);
    }

    @Test
    @DisplayName("a receptionist with no reception sees nothing")
    void receptionistWithoutReceptionDeniesAll() {
        context.scope = new ScopeContext.Scope(USER, "FLOOR_RECEPTIONIST", TENANT, null);

        assertThat(policy.filterFor(ScopedEntity.VISITOR_REQUEST)).isEqualTo(ScopeFilter.DENY_ALL);
    }

    @Test
    @DisplayName("a role nobody has classified sees nothing")
    void unclassifiedRoleDeniesAll() {
        // "What should this role see?" has an owner. Until they answer, nothing.
        context.scope = new ScopeContext.Scope(USER, "SOME_FUTURE_ROLE", TENANT, RECEPTION);

        assertThat(policy.filterFor(ScopedEntity.VISITOR_REQUEST)).isEqualTo(ScopeFilter.DENY_ALL);
    }

    @Test
    @DisplayName("a null entity type sees nothing")
    void nullEntityDeniesAll() {
        context.scope = new ScopeContext.Scope(USER, "TENANT", TENANT, null);

        assertThat(policy.filterFor(null)).isEqualTo(ScopeFilter.DENY_ALL);
    }

    @ParameterizedTest
    @EnumSource(ScopedEntity.class)
    @DisplayName("every registered entity is scoped for a tenant user — none is accidentally open")
    void everyRegisteredEntityIsScoped(ScopedEntity entity) {
        context.scope = new ScopeContext.Scope(USER, "TENANT", TENANT, null);

        // A switch that fell through to Unrestricted for one case would be invisible in review and
        // would leak exactly one entity type.
        assertThat(policy.filterFor(entity)).isNotInstanceOf(ScopeFilter.Unrestricted.class);
    }

    private static final class FakeContext implements ScopeContext {
        Scope scope;

        public Optional<Scope> current() {
            return Optional.ofNullable(scope);
        }
    }
}
