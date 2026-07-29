package com.pantropi.vms.infrastructure.visitor;

import com.pantropi.vms.application.identity.usecase.ScopePolicy;
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
    private static final String FROM = """
              FROM vms.visitor_requests r
              JOIN vms.tenants t ON t.id = r.tenant_id
              LEFT JOIN vms.hosts h ON h.id = r.host_id""";

    private static final String SELECT = """
            SELECT r.id, r.tenant_id, t.name AS tenant_name, r.host_id, h.full_name AS host_name,
                   r.scheduled_from, r.scheduled_to, r.created_at,
                   (SELECT count(*) FROM vms.visitors v WHERE v.request_id = r.id) AS visitor_count
            """ + FROM;

    /**
     * The count shares {@link #FROM} so it cannot drift from the page query.
     *
     * <p>Two independently written {@code FROM} clauses is how a total ends up disagreeing with the
     * rows underneath it — the host join in particular has to be present in both, because the name
     * search references it.
     */
    private static final String COUNT = "SELECT count(*)" + FROM;

    private final JdbcTemplate jdbc;
    private final ScopePolicy scope;

    public JdbcApprovalQueueStore(JdbcTemplate jdbc, ScopePolicy scope) {
        this.jdbc = jdbc;
        this.scope = scope;
    }

    @Override
    public Page pending(Filter filter) {
        VisitorScopeSql.Clause clause = VisitorScopeSql.on(
                scope.filterFor(ScopedEntity.VISITOR_REQUEST), "r.tenant_id", null);

        // Scope first, always, whatever the filter says (AC-5). Assembled here rather than left to
        // each filter branch to remember, so there is no combination of parameters that omits it.
        StringBuilder where = new StringBuilder(" WHERE r.status = ?::vms.request_status")
                .append(clause.and());
        List<Object> args = new ArrayList<>();
        args.add(filter.status().dbValue());
        args.addAll(clause.args());

        if (filter.tenantId() != null) {
            where.append(" AND r.tenant_id = ?");
            args.add(filter.tenantId());
        }
        if (filter.visitFrom() != null) {
            where.append(" AND r.scheduled_from >= ?");
            args.add(Timestamp.from(filter.visitFrom()));
        }
        if (filter.visitTo() != null) {
            where.append(" AND r.scheduled_from < ?");
            args.add(Timestamp.from(filter.visitTo()));
        }
        if (filter.nameLike() != null) {
            // AC-2/AC-4: parameterised and case-insensitive, with LIKE metacharacters escaped so a
            // search for "100%" is a search for that text rather than a match-everything pattern.
            // The visitor name is searchable and is never returned — the queue lists counts, not
            // people (US-07.3.1).
            where.append(" AND (h.full_name ILIKE ? ESCAPE '\\'"
                    + " OR EXISTS (SELECT 1 FROM vms.visitors v2"
                    + " WHERE v2.request_id = r.id AND v2.full_name ILIKE ? ESCAPE '\\'))");
            String pattern = "%" + escapeLike(filter.nameLike()) + "%";
            args.add(pattern);
            args.add(pattern);
        }

        Long total = jdbc.queryForObject(COUNT + where, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(filter.page().size());
        pageArgs.add(filter.page().offset());

        List<PendingRequest> content = jdbc.query(
                SELECT + where + " ORDER BY r.created_at DESC, r.id DESC LIMIT ? OFFSET ?",
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

        return new Page(content, total == null ? 0 : total, filter.page().page(),
                filter.page().size());
    }

    /**
     * Escapes the three characters {@code LIKE} treats specially.
     *
     * <p>The backslash first, or escaping the wildcards would then have their new backslashes
     * escaped in turn and the pattern would mean something else again.
     */
    private static String escapeLike(String raw) {
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static java.time.Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
