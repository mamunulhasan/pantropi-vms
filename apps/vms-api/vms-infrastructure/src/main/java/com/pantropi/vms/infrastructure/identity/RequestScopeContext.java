package com.pantropi.vms.infrastructure.identity;

import com.pantropi.vms.application.identity.port.ScopeContext;
import com.pantropi.vms.application.identity.port.ScopeContextLifecycle;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;
import java.util.UUID;

/**
 * The request-scoped {@link ScopeContext} (US-03.4.1, T-03.4.1.1).
 *
 * <p>A {@link ThreadLocal} rather than a Spring request-scoped bean, so the application layer's port
 * has no framework behind it and the same implementation works off a servlet thread if anything ever
 * needs to.
 *
 * <h2>Clearing is not optional</h2>
 * Servlet threads are pooled. A context left behind is inherited by whoever gets that thread next,
 * which means one user's tenant scope silently applied to another user's request — the worst class
 * of isolation failure, because every query looks correct. {@link #clear()} is called from the
 * interceptor's {@code afterCompletion}, which Spring runs even when the handler threw.
 *
 * <h2>The tenant and reception are read once, on demand</h2>
 * They come from {@code vms.users} rather than the token, so moving a user between tenants applies
 * on their next request instead of at their next login — a stale scope is an isolation failure and a
 * session lasts hours. Reading them eagerly would spend a query on every request including the many
 * that never touch scoped data, so the lookup happens the first time something asks and the answer
 * is kept for the remainder of that request.
 */
public final class RequestScopeContext implements ScopeContext, ScopeContextLifecycle {

    private static final ThreadLocal<Holder> CURRENT = new ThreadLocal<>();

    private final JdbcTemplate jdbc;

    public RequestScopeContext(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Called by the authorization interceptor once the principal is known. */
    @Override
    public void begin(UUID userId, String roleCode) {
        CURRENT.set(new Holder(userId, roleCode));
    }

    /** Called from {@code afterCompletion}, always — see the class note on pooled threads. */
    @Override
    public void clear() {
        CURRENT.remove();
    }

    @Override
    public Optional<Scope> current() {
        Holder holder = CURRENT.get();
        if (holder == null) {
            return Optional.empty();
        }
        if (!holder.resolved) {
            resolve(holder);
        }
        return Optional.of(new Scope(holder.userId, holder.roleCode, holder.tenantId,
                holder.receptionId));
    }

    private void resolve(Holder holder) {
        jdbc.query("SELECT tenant_id, reception_id FROM vms.users WHERE id = ? AND is_active = true",
                rs -> {
                    holder.tenantId = rs.getObject("tenant_id", UUID.class);
                    holder.receptionId = rs.getObject("reception_id", UUID.class);
                }, holder.userId);
        // Marked resolved even when no row matched. A deactivated user has no scope, and asking
        // again on the same request would not change that — it would only spend another query.
        holder.resolved = true;
    }

    /** Mutable only within one request, on one thread. */
    private static final class Holder {
        final UUID userId;
        final String roleCode;
        boolean resolved;
        UUID tenantId;
        UUID receptionId;

        Holder(UUID userId, String roleCode) {
            this.userId = userId;
            this.roleCode = roleCode;
        }
    }
}
