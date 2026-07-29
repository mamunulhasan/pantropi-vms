package com.pantropi.vms.domain.identity;

import java.util.UUID;

/**
 * What a principal is allowed to see of a scoped entity (US-03.4.1) — TODO-14, NFR-SEC-01 (SRS B1).
 *
 * <p>Deliberately <strong>not</strong> a SQL fragment. The policy decides the rule; each adapter
 * knows which of its own columns expresses it, because the same rule is {@code tenant_id} on one
 * table and a join through another. A policy that emitted SQL would have to know every table it
 * might ever apply to, and the application layer would be writing queries.
 *
 * <p>Sealed, so adding a case is a compile error everywhere it is handled rather than a silently
 * unhandled branch that falls through to something permissive.
 *
 * <p>Pure Java: no framework.
 */
public sealed interface ScopeFilter {

    /**
     * See nothing. The answer for an unauthenticated caller, an unregistered entity, and a principal
     * whose scope cannot be determined — a tenant user with no tenant, for instance.
     */
    record DenyAll() implements ScopeFilter {}

    /**
     * See everything of this type. For the roles whose job spans the building — a Master Admin
     * approving access, an FM Admin reviewing requests, a System Administrator.
     */
    record Unrestricted() implements ScopeFilter {}

    /** See only rows belonging to this tenant. */
    record OwnTenant(UUID tenantId) implements ScopeFilter {}

    /** See only rows belonging to this reception. */
    record OwnReception(UUID receptionId) implements ScopeFilter {}

    ScopeFilter DENY_ALL = new DenyAll();
    ScopeFilter UNRESTRICTED = new Unrestricted();
}
