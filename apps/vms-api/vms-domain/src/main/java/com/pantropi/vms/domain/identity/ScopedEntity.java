package com.pantropi.vms.domain.identity;

/**
 * The entity types whose reads are subject to tenant or floor isolation (US-03.4.1, AC-4).
 *
 * <p>Registering a type here is what makes {@link ScopeFilter} apply to it. A type that is
 * <strong>not</strong> listed is not thereby unrestricted — the policy answers {@code DENY_ALL} for
 * anything it does not recognise, so forgetting to register something makes its queries return
 * nothing rather than everything.
 *
 * <p>The four here are the ones Phase 2 consumes, named in the story: visitor requests, visitors,
 * hosts, and the user list view. Nothing else reads per-tenant data yet.
 *
 * <p>Pure Java: no framework.
 */
public enum ScopedEntity {

    /** {@code vms.visitor_requests} — a tenant's own requests, an approver's whole building. */
    VISITOR_REQUEST,

    /** {@code vms.visitors} — the people named on a request, scoped with it. */
    VISITOR,

    /** {@code vms.hosts} — who a tenant's visitors are coming to see. */
    HOST,

    /** The {@code vms.users} list view — who a tenant administrator may see. */
    USER_LIST
}
