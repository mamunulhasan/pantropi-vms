package com.pantropi.vms.infrastructure.visitor;

import com.pantropi.vms.application.identity.usecase.ScopePolicy;
import com.pantropi.vms.application.visitor.port.VisitorRequestQueries;
import com.pantropi.vms.domain.identity.ScopedEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC adapter for the tenant's view of its own requests (US-07.6.1, T-07.6.1.1).
 *
 * <h2>Scope first, filters after</h2>
 * The scope predicate is the first thing appended to every {@code WHERE}, before any caller-supplied
 * condition, and its arguments go into the list first. AC-2 asks that a filter never widen the
 * scope; written the other way round — filters assembled, scope appended if the author remembered —
 * a future filter that forgot would silently be a cross-tenant read.
 *
 * <p>The filter is a typed {@code Filter}, so there is no string a caller can supply that becomes
 * part of the SQL: the status is a {@link com.pantropi.vms.domain.visitor.RequestStatus} by the time
 * it reaches here, and the dates are {@link Instant}s bound as parameters.
 *
 * <h2>One query answers both "missing" and "not yours" (AC-4)</h2>
 * The detail read is a single scoped lookup. There is no "does it exist?" step followed by an
 * ownership check, so there is no branch that could take longer for a real-but-foreign id than for
 * one that was never issued — and nothing for the endpoint to say differently about the two.
 */
public final class JdbcVisitorRequestQueries implements VisitorRequestQueries {

    private static final String LIST_SELECT = """
            SELECT r.id, r.status::text AS status, h.full_name AS host_name,
                   r.scheduled_from, r.scheduled_to, r.created_at, r.decided_at,
                   r.decision_reason,
                   (SELECT count(*) FROM vms.visitors v WHERE v.request_id = r.id) AS visitor_count
              FROM vms.visitor_requests r
              LEFT JOIN vms.hosts h ON h.id = r.host_id
             WHERE TRUE""";

    private final JdbcTemplate jdbc;
    private final ScopePolicy scope;

    public JdbcVisitorRequestQueries(JdbcTemplate jdbc, ScopePolicy scope) {
        this.jdbc = jdbc;
        this.scope = scope;
    }

    @Override
    public Page list(Filter filter) {
        VisitorScopeSql.Clause clause = scopeClause();

        StringBuilder where = new StringBuilder(clause.and());
        List<Object> args = new ArrayList<>(clause.args());

        appendFilters(filter, where, args);

        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM vms.visitor_requests r WHERE TRUE" + where,
                Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(filter.page().size());
        pageArgs.add(filter.page().offset());

        List<Summary> content = jdbc.query(
                LIST_SELECT + where + " ORDER BY r.created_at DESC, r.id DESC LIMIT ? OFFSET ?",
                (rs, i) -> new Summary(
                        rs.getObject("id", UUID.class),
                        rs.getString("status"),
                        rs.getString("host_name"),
                        instant(rs.getTimestamp("scheduled_from")),
                        instant(rs.getTimestamp("scheduled_to")),
                        rs.getInt("visitor_count"),
                        instant(rs.getTimestamp("created_at")),
                        instant(rs.getTimestamp("decided_at")),
                        rs.getString("decision_reason")),
                pageArgs.toArray());

        return new Page(content, total == null ? 0 : total, filter.page().page(),
                filter.page().size());
    }

    /**
     * The list validator (US-07.6.2, T-07.6.2.3): a count and a high-water mark over the scoped rows.
     *
     * <p>{@code updated_at} is what moves on every write this list reflects — a decision, an
     * amendment, a cancellation all touch the request row (see {@code saveDecision} and
     * {@code saveAmendment}), so a change any of them makes is a change here. {@code created_at} is
     * folded in as well because a brand-new request in an empty list would otherwise leave the
     * high-water mark null.
     *
     * <p>The scope predicate is included as a discriminator, so two tenants whose counts and
     * timestamps coincide still get different validators.
     */
    @Override
    public String listVersion(Filter filter) {
        VisitorScopeSql.Clause clause = scopeClause();
        StringBuilder where = new StringBuilder(clause.and());
        List<Object> args = new ArrayList<>(clause.args());
        appendFilters(filter, where, args);

        String state = jdbc.queryForObject("""
                SELECT count(*)::text || ':' || coalesce(
                           max(greatest(r.updated_at, r.created_at))::text, '-')
                  FROM vms.visitor_requests r
                 WHERE TRUE""" + where, String.class, args.toArray());

        // The scope and the filter both go into the token. Hashed rather than concatenated, so the
        // validator cannot be read back as a description of what the caller is allowed to see.
        return shortHash(clause.sql() + '|' + clause.args() + '|' + describe(filter) + '|' + state);
    }

    /** The filter as a stable string, so two different views never share a validator. */
    private static String describe(Filter filter) {
        return (filter.status() == null ? "-" : filter.status().dbValue())
                + ',' + filter.visitFrom() + ',' + filter.visitTo()
                + ',' + filter.page().page() + ',' + filter.page().size();
    }

    private static String shortHash(String input) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                out.append(String.format("%02x", digest[i]));
            }
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 is required of every JVM; if it is absent the platform is not one we can
            // reason about, and a silently weaker validator would be worse than failing.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    @Override
    public Optional<Detail> detail(UUID id) {
        VisitorScopeSql.Clause clause = scopeClause();
        List<Object> args = new ArrayList<>();
        args.add(id);
        args.addAll(clause.args());

        List<Detail> found = jdbc.query("""
                SELECT r.id, r.status::text AS status, h.full_name AS host_name, r.purpose,
                       r.scheduled_from, r.scheduled_to, r.created_at, r.decided_at,
                       r.decision_reason, u.full_name AS decided_by_name
                  FROM vms.visitor_requests r
                  LEFT JOIN vms.hosts h ON h.id = r.host_id
                  LEFT JOIN vms.users u ON u.id = r.approved_by
                 WHERE r.id = ?""" + clause.and(),
                (rs, i) -> new Detail(
                        rs.getObject("id", UUID.class),
                        rs.getString("status"),
                        rs.getString("host_name"),
                        rs.getString("purpose"),
                        instant(rs.getTimestamp("scheduled_from")),
                        instant(rs.getTimestamp("scheduled_to")),
                        instant(rs.getTimestamp("created_at")),
                        // The approver's display name, joined from vms.users — deliberately not
                        // their username, email or id.
                        rs.getString("decided_by_name"),
                        instant(rs.getTimestamp("decided_at")),
                        rs.getString("decision_reason"),
                        List.of()),
                args.toArray());

        if (found.isEmpty()) {
            return Optional.empty();
        }

        Detail header = found.get(0);
        // One query for all of them, joined to the type — not a lookup per visitor (T-07.3.3.1).
        List<VisitorLine> visitors = jdbc.query("""
                SELECT v.full_name, v.company, vt.name AS visitor_type, v.status::text AS status
                  FROM vms.visitors v
                  LEFT JOIN vms.visitor_types vt ON vt.id = v.visitor_type_id
                 WHERE v.request_id = ? ORDER BY v.created_at
                """, (rs, i) -> new VisitorLine(rs.getString("full_name"), rs.getString("company"),
                rs.getString("visitor_type"), rs.getString("status")), id);

        // The visitor read is unscoped by design: it runs only after the parent request has already
        // been matched under the caller's scope, so reaching it at all means the request is theirs.
        return Optional.of(new Detail(header.id(), header.status(), header.host(), header.purpose(),
                header.scheduledFrom(), header.scheduledTo(), header.submittedAt(),
                header.decidedBy(), header.decidedAt(), header.decisionReason(), visitors));
    }

    /**
     * One filter assembly, shared by the list and its validator.
     *
     * <p>Written twice, the two would eventually disagree — and a validator that filters differently
     * from the list it validates reports "unchanged" for a list that changed.
     */
    private static void appendFilters(Filter filter, StringBuilder where, List<Object> args) {
        if (filter.status() != null) {
            where.append(" AND r.status = ?::vms.request_status");
            args.add(filter.status().dbValue());
        }
        if (filter.visitFrom() != null) {
            where.append(" AND r.scheduled_from >= ?");
            args.add(Timestamp.from(filter.visitFrom()));
        }
        if (filter.visitTo() != null) {
            // Exclusive, so two adjacent ranges neither overlap nor leave a gap.
            where.append(" AND r.scheduled_from < ?");
            args.add(Timestamp.from(filter.visitTo()));
        }
    }

    private VisitorScopeSql.Clause scopeClause() {
        return VisitorScopeSql.on(scope.filterFor(ScopedEntity.VISITOR_REQUEST), "r.tenant_id",
                null);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
