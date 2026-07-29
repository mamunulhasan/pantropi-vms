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
 * <h2>The posture is configuration, not code (US-07.3.1 AC-6, T-07.3.1.2)</h2>
 * US-07.3.1 asks that restricting an approver to a subset of tenants or floors later require no
 * change to query-building code. It does not: every scoped query already takes its predicate from
 * here, and which posture this class applies is a {@link Posture} chosen at wiring time. Answering
 * TODO-14 in the restrictive direction is then a property, not a patch.
 *
 * <p>Deliberately <em>not</em> a second scoping interface, which T-07.3.1.2 phrases as an
 * {@code ApprovalScopeStrategy}. A separate port for approval visibility would give isolation two
 * homes, which is the exact thing ADR-0005 and {@code ScopingRulesTest} exist to prevent — and the
 * queue would then be scoped by different rules from the detail read behind it.
 *
 * <p>TODO-14 stays open. Changing the answer means changing this class or its posture, and nothing
 * else.
 *
 * <p>Pure orchestration over ports — no framework.
 */
public final class ScopePolicy {

    /**
     * How much a building-wide role sees. Provisional pending TODO-14; recorded in ADR-0005.
     */
    public enum Posture {
        /**
         * The shipped default: MASTER_ADMIN, FM_ADMIN and SYSTEM_ADMIN see the whole building.
         *
         * <p>Approvers cannot decide requests they cannot see, and nothing in the SRS divides the
         * building between them.
         */
        BUILDING_WIDE,

        /**
         * Every principal is confined to their own tenant or reception, approvers included.
         *
         * <p>What TODO-14 would mean if answered restrictively. An approver with neither column set
         * sees nothing at all — under this posture that is the correct answer rather than a bug:
         * an approver who has not been assigned a scope has not been given anything to approve.
         */
        OWN_SCOPE
    }

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
    private final Posture posture;

    /** Wires the shipped default. */
    public ScopePolicy(ScopeContext context) {
        this(context, Posture.BUILDING_WIDE);
    }

    public ScopePolicy(ScopeContext context, Posture posture) {
        this.context = context;
        this.posture = posture == null ? Posture.BUILDING_WIDE : posture;
    }

    /** The active posture, so a deployment can report the isolation it is actually running. */
    public Posture posture() {
        return posture;
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
            return posture == Posture.BUILDING_WIDE
                    ? ScopeFilter.UNRESTRICTED
                    : confinedTo(scope);
        }

        return switch (entity) {
            case VISITOR_REQUEST, VISITOR, HOST, USER_LIST -> scopeOf(role, scope);
        };
    }

    /**
     * A building-wide role under {@link Posture#OWN_SCOPE}: confined by whichever assignment it has.
     *
     * <p>An approver given a tenant sees that tenant; one given a reception sees that reception; one
     * given neither sees nothing. The last case is the common one today — FM Admins are not
     * tenant-assigned — and that is the honest answer under this posture rather than a bug: if
     * approvers are to be restricted to a subset, somebody has to say which subset, and until they
     * do there is no defensible set to show.
     */
    private static ScopeFilter confinedTo(ScopeContext.Scope scope) {
        if (scope.tenantId() != null) {
            return new ScopeFilter.OwnTenant(scope.tenantId());
        }
        if (scope.receptionId() != null) {
            return new ScopeFilter.OwnReception(scope.receptionId());
        }
        return ScopeFilter.DENY_ALL;
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
