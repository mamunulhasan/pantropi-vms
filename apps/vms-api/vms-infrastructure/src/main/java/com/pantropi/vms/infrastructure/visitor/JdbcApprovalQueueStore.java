package com.pantropi.vms.infrastructure.visitor;

import com.pantropi.vms.application.identity.usecase.ScopePolicy;
import com.pantropi.vms.application.shared.PageRequest;
import com.pantropi.vms.application.visitor.port.ApprovalQueueStore;
import com.pantropi.vms.domain.identity.ScopedEntity;
import com.pantropi.vms.domain.visitor.RequestStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * JDBC read model for the approval queue (US-07.3.1, T-07.3.1.1).
 *
 * <h2>Ordering is deterministic, not merely descending</h2>
 * {@code created_at DESC} alone is not a stable order: two requests submitted in the same
 * millisecond may come back in either order, and under pagination that means a row appearing on two
 * pages while another appears on none. The tiebreak on {@code id} makes the sequence total, which is
 * what AC-2's "stable ordering" has to mean for paging to be sound.
 *
 * <p>The index this relies on — {@code idx_visitor_requests_status_created} on
 * {@code (status, created_at DESC)} — was added in {@code V10} alongside the decision columns.
 *
 * <h2>The scope predicate comes from the policy (AC-6)</h2>
 * Not from a condition written here. Restricting approvers to a subset of tenants is then a change
 * to {@code ScopePolicy}'s posture and nothing in this file — which is what
 * {@code ScopingRulesTest} enforces for every scoped read.
 */
public final class JdbcApprovalQueueStore implements ApprovalQueueStore {

    /**
     * The visitor count is a correlated subquery rather than a join with {@code GROUP BY}.
     *
     * <p>A join would multiply the request row by its visitors before collapsing it again, so every
     * other column would have to be aggregated or grouped — and one forgotten column becomes a row
     * count that silently disagrees with the request it describes.
     *
     * <p>Cancelled visitors are counted too: the queue says how many people the request is for, and
     * an approver deciding on a group of four should not see three because one withdrew.
     */
    private static final String SELECT = """
            SELECT r.id, r.tenant_id, t.name AS tenant_name, r.host_id, h.full_name AS host_name,
                   r.scheduled_from, r.scheduled_to, r.created_at,
                   (SELECT count(*) FROM vms.visitors v WHERE v.request_id = r.id) AS visitor_count
              FROM vms.visitor_requests r
              JOIN vms.tenants t ON t.id = r.tenant_id
              LEFT JOIN vms.hosts h ON h.id = r.host_id
             WHERE r.status = ?::vms.request_status""";

    private final JdbcTemplate jdbc;
    private final ScopePolicy scope;

    public JdbcApprovalQueueStore(JdbcTemplate jdbc, ScopePolicy scope) {
        this.jdbc = jdbc;
        this.scope = scope;
    }

    @Override
    public Page pending(PageRequest request) {
        // AC-3 is this line: "pending" is a status filter, so anything approved, rejected or
        // cancelled leaves the queue on the next read with nothing to clean up.
        String pending = RequestStatus.SUBMITTED.dbValue();

        VisitorScopeSql.Clause clause = VisitorScopeSql.on(
                scope.filterFor(ScopedEntity.VISITOR_REQUEST), "r.tenant_id", null);

        List<Object> countArgs = new ArrayList<>();
        countArgs.add(pending);
        countArgs.addAll(clause.args());

        Long total = jdbc.queryForObject("""
                SELECT count(*) FROM vms.visitor_requests r
                 WHERE r.status = ?::vms.request_status""" + clause.and(),
                Long.class, countArgs.toArray());

        List<Object> pageArgs = new ArrayList<>(countArgs);
        pageArgs.add(request.size());
        pageArgs.add(request.offset());

        List<PendingRequest> content = jdbc.query(
                SELECT + clause.and() + " ORDER BY r.created_at DESC, r.id DESC LIMIT ? OFFSET ?",
                (rs, i) -> new PendingRequest(
                        rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("tenant_name"),
                        rs.getObject("host_id", UUID.class),
                        rs.getString("host_name"),
                        instant(rs.getTimestamp("scheduled_from")),
                        instant(rs.getTimestamp("scheduled_to")),
                        rs.getInt("visitor_count"),
                        instant(rs.getTimestamp("created_at"))),
                pageArgs.toArray());

        return new Page(content, total == null ? 0 : total, request.page(), request.size());
    }

    private static java.time.Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
