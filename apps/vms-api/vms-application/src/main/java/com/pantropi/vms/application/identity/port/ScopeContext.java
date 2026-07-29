package com.pantropi.vms.application.identity.port;

import java.util.Optional;
import java.util.UUID;

/**
 * Who is asking, and what they are scoped to (US-03.4.1, T-03.4.1.1, AC-1).
 *
 * <p>A port rather than a framework holder, so the application layer can read the current principal
 * without importing a request-scoped bean or a servlet type, and without threading the scope through
 * every method signature between the controller and the query.
 *
 * <p>Empty for an unauthenticated request — a public route has no principal, and the policy answers
 * {@link com.pantropi.vms.domain.identity.ScopeFilter.DenyAll} for that.
 *
 * <h2>The tenant and reception are resolved lazily</h2>
 * They come from {@code vms.users}, not from the token, so that moving a user between tenants takes
 * effect on their next request rather than at their next login — a stale scope is an isolation
 * failure, and a session can last a day. Reading them on every request would spend a query that most
 * requests never need, so the implementation resolves them the first time they are asked for and
 * remembers the answer for the rest of that request.
 */
public interface ScopeContext {

    /** The current principal's scope, or empty on an unauthenticated request. */
    Optional<Scope> current();

    /**
     * @param tenantId    the user's tenant, or null — a System Administrator belongs to none
     * @param receptionId the user's reception, or null
     */
    record Scope(UUID userId, String roleCode, UUID tenantId, UUID receptionId) {}
}
