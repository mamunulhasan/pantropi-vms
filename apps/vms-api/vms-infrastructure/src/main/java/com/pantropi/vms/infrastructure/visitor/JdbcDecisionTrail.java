package com.pantropi.vms.infrastructure.visitor;

import com.pantropi.vms.application.visitor.port.DecisionTrail;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

/**
 * JDBC read of the audit trail for one request (US-07.4.3, T-07.4.3.3).
 *
 * <h2>Not scoped by tenant, and that is deliberate</h2>
 * Every other read in this context takes its predicate from {@code ScopePolicy}. This one does not,
 * because it is not a tenant-facing read: it is guarded by {@code audit.view}, which only
 * SYSTEM_ADMIN holds, and an oversight function that could only see its own tenant's decisions would
 * not be oversight. The control here is the permission at the boundary rather than a row predicate.
 *
 * <p>Named explicitly because {@code ScopingRulesTest} exists precisely to catch a query over
 * scope-sensitive data that skips the policy — {@code vms.audit_logs} is not one of the registered
 * scope-sensitive types, so the rule does not fire, and a reader should know that was considered
 * rather than overlooked.
 *
 * <p>Ordered by {@code created_at} then {@code id}: two entries written in the same transaction
 * share a timestamp, and the identity column is the only thing that puts them in the order they
 * happened.
 */
public final class JdbcDecisionTrail implements DecisionTrail {

    private final JdbcTemplate jdbc;

    public JdbcDecisionTrail(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Entry> forRequest(UUID requestId) {
        return jdbc.query("""
                SELECT a.id, a.created_at, a.action, a.user_id, u.full_name AS actor_name,
                       a.before_state::text AS before_state, a.after_state::text AS after_state
                  FROM vms.audit_logs a
                  LEFT JOIN vms.users u ON u.id = a.user_id
                 WHERE a.entity_type = 'visitor_request' AND a.entity_id = ?
                 ORDER BY a.created_at, a.id
                """,
                (rs, i) -> new Entry(
                        rs.getLong("id"),
                        toInstant(rs.getTimestamp("created_at")),
                        rs.getString("action"),
                        rs.getObject("user_id", UUID.class),
                        rs.getString("actor_name"),
                        rs.getString("before_state"),
                        rs.getString("after_state")),
                requestId.toString());
    }

    private static java.time.Instant toInstant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
