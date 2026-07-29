package com.pantropi.vms.application.identity.usecase;

import com.pantropi.vms.application.identity.port.ScopeContext;
import com.pantropi.vms.domain.identity.ScopeFilter;
import com.pantropi.vms.domain.identity.ScopedEntity;

import java.util.Optional;
import java.util.Set;

/**
 * The one place tenant and floor isolation is decided (US-03.4.1, T-03.4.1.2).
 *
 * <p>Every read of a scope-sensitive entity gets its filter from here. Not because a single class is
 * tidier, but because TODO-14 is still open: <strong>no source document says whether Tenant A may
 * see Tenant B's visitor data</strong>, and when it is answered the answer has to land in one place
 * rather than in every query written in the meantime.
 *
 * <h2>The shipped policy is strict own-scope, per ADR-0004</h2>
 * The backlog's AC-3 asks for deny-all. ADR-0004 supersedes that with an interim decision — a tenant
 * sees its own data, a receptionist their own reception — for a reason worth restating: deny-all
 * would prevent a tenant seeing even <em>their own</em> requests, which makes the tenant-facing half
 * of EPIC-07 unbuildable. A restrictive-but-usable default still fails safe in the direction that
 * matters: if the eventual policy is more permissive, widening it is a change here; had we defaulted
 * permissive and the answer turned out strict, tenant data would have leaked in the meantime.
 *
 * <p>The deny-all instinct behind AC-3 is kept where it costs nothing: <strong>an entity this class
 * does not recognise, and a principal whose scope cannot be determined, both get nothing.</strong>
 * Forgetting to register a new scoped type makes its queries return zero rows, not every row.
 *
 * <p>TODO-14 stays open. Changing the answer means changing this class and nothing else.
 *
 * <p>Pure orchestration over ports — no framework.
 */
public final class ScopePolicy {

    /**
     * Roles whose work spans the building rather than one tenant or floor.
     *
     * <p>MASTER_ADMIN and FM_ADMIN approve requests, which they cannot do without seeing them.
     * SYSTEM_ADMIN administers the installation. Naming them here rather than testing for "not
     * TENANT" means adding a role later is a decision someone has to make, not a default they
     * inherit.
     */
    private static final Set<String> BUILDING_WIDE_ROLES =
            Set.of("MASTER_ADMIN", "FM_ADMIN", "SYSTEM_ADMIN");

    private static final String TENANT_ROLE = "TENANT";
    private static final String RECEPTIONIST_ROLE = "FLOOR_RECEPTIONIST";

    private final ScopeContext context;

    public ScopePolicy(ScopeContext context) {
        this.context = context;
    }

    /**
     * The filter to apply when reading this entity type, for whoever is asking now.
     *
     * @throws NullPointerException never — an unknown entity and an absent principal both answer
     *                              {@link ScopeFilter#DENY_ALL}
     */
    public ScopeFilter filterFor(ScopedEntity entity) {
        if (entity == null) {
            return ScopeFilter.DENY_ALL;
        }
        Optional<ScopeContext.Scope> scope = context.current();
        if (scope.isEmpty()) {
            // No authenticated principal. A public route reading scoped data would be a mistake;
            // this makes it return nothing rather than everything.
            return ScopeFilter.DENY_ALL;
        }
        return filterFor(entity, scope.get());
    }

    private ScopeFilter filterFor(ScopedEntity entity, ScopeContext.Scope scope) {
        String role = scope.roleCode();

        if (BUILDING_WIDE_ROLES.contains(role)) {
            return ScopeFilter.UNRESTRICTED;
        }

        return switch (entity) {
            case VISITOR_REQUEST, VISITOR, HOST, USER_LIST -> scopeOf(role, scope);
        };
    }

    /**
     * A tenant user is confined to their tenant; a receptionist to their reception.
     *
     * <p>A principal whose scoping column is null gets nothing. A tenant user with no tenant cannot
     * be confined to one, and the safe reading of "cannot be determined" is "sees nothing" — the
     * alternative is a user who accidentally sees everything.
     */
    private static ScopeFilter scopeOf(String role, ScopeContext.Scope scope) {
        if (TENANT_ROLE.equals(role)) {
            return scope.tenantId() == null
                    ? ScopeFilter.DENY_ALL : new ScopeFilter.OwnTenant(scope.tenantId());
        }
        if (RECEPTIONIST_ROLE.equals(role)) {
            return scope.receptionId() == null
                    ? ScopeFilter.DENY_ALL : new ScopeFilter.OwnReception(scope.receptionId());
        }
        // A role nobody has classified. Not a bug to paper over with a permissive default: the
        // question "what should this role see?" has an owner, and until they answer it the safe
        // answer is nothing.
        return ScopeFilter.DENY_ALL;
    }
}
