package com.pantropi.vms.infrastructure.visitor;

import com.pantropi.vms.application.identity.usecase.ScopePolicy;
import com.pantropi.vms.application.visitor.port.ArrivalDirectory;
import com.pantropi.vms.domain.identity.ScopedEntity;
import com.pantropi.vms.domain.visitor.RequestStatus;
import com.pantropi.vms.domain.visitor.VisitorStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The arrival lookup over {@code vms.visitors} (US-12.1.1, T-12.1.1.1) — FR-VMS-04 (SRS B1).
 *
 * <h2>Ranking is the feature</h2>
 * AC-3 asks that visitors whose window includes now come first. A desk is serving a queue, so the
 * person standing there is almost always expected right now — sorting by name would put them
 * wherever the alphabet happens to place them. The ordering is: in-window first, then by how soon
 * the appointment starts, so the next arrival is the next row.
 *
 * <h2>Scoped, like every other visitor read</h2>
 * {@link ScopedEntity#VISITOR} through {@link ScopePolicy}, joined via the request's tenant. A floor
 * receptionist sees their own reception's visitors and a central one sees the building, and that is
 * decided in the policy rather than restated here.
 */
public final class JdbcArrivalDirectory implements ArrivalDirectory {

    /**
     * The five fields AC-1 names, plus the request id so a booking reference can be pasted in.
     *
     * <p>{@code ILIKE} over a handful of columns rather than a full-text index: the table is one
     * building's visitors, the desk types three or four characters, and a GIN index would be
     * machinery to maintain for a query that is already fast at this size. If a tower ever makes
     * this slow, the fix is an index, not a rewrite.
     */
    private static final String SEARCH = """
             AND (v.full_name ILIKE ? OR v.company ILIKE ? OR h.full_name ILIKE ?
                  OR v.phone ILIKE ? OR r.id::text ILIKE ?)""";

    private static final RowMapper<Arrival> ROW = (rs, i) -> new Arrival(
            rs.getObject("visitor_id", UUID.class),
            rs.getObject("request_id", UUID.class),
            rs.getString("full_name"),
            rs.getString("company"),
            rs.getString("visitor_type"),
            rs.getString("phone"),
            rs.getString("host_name"),
            rs.getString("tenant_name"),
            instant(rs.getTimestamp("appointment_from")),
            instant(rs.getTimestamp("appointment_to")),
            RequestStatus.fromDb(rs.getString("request_status")),
            VisitorStatus.fromDb(rs.getString("visitor_status")),
            instant(rs.getTimestamp("checked_in_at")),
            instant(rs.getTimestamp("checked_out_at")));

    private static final String BASE = """
            SELECT v.id AS visitor_id, r.id AS request_id, v.full_name, v.company,
                   vt.name AS visitor_type, v.phone, h.full_name AS host_name,
                   t.name AS tenant_name, v.appointment_from, v.appointment_to,
                   r.status::text AS request_status, v.status::text AS visitor_status,
                   v.checked_in_at, v.checked_out_at
              FROM vms.visitors v
              JOIN vms.visitor_requests r ON r.id = v.request_id
              LEFT JOIN vms.visitor_types vt ON vt.id = v.visitor_type_id
              LEFT JOIN vms.hosts h ON h.id = r.host_id
              LEFT JOIN vms.tenants t ON t.id = r.tenant_id
             WHERE 1=1""";

    private final JdbcTemplate jdbc;
    private final ScopePolicy scope;

    public JdbcArrivalDirectory(JdbcTemplate jdbc, ScopePolicy scope) {
        this.jdbc = jdbc;
        this.scope = scope;
    }

    @Override
    public List<Arrival> search(String search, Instant now, int limit) {
        VisitorScopeSql.Clause clause = scopeClause();
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder(BASE).append(clause.and());

        if (search != null && !search.isBlank()) {
            String like = "%" + search.trim() + "%";
            sql.append(SEARCH);
            for (int i = 0; i < 5; i++) {
                args.add(like);
            }
        } else {
            // An empty box means "who is due", not "everyone who ever visited". Anything whose
            // window has not closed, so a desk opening the screen sees the day ahead of it.
            sql.append(" AND (v.appointment_to IS NULL OR v.appointment_to >= ?)");
            args.add(Timestamp.from(now.minusSeconds(3600)));
        }

        // AC-3: in-window first, then by how soon it starts. A desk serves a queue, and the person
        // standing there is nearly always the one expected now.
        sql.append("""
                 ORDER BY (v.appointment_from <= ? AND v.appointment_to >= ?) DESC,
                          v.appointment_from NULLS LAST, v.full_name
                 LIMIT ?""");

        List<Object> all = new ArrayList<>(clause.args());
        all.addAll(args);
        all.add(Timestamp.from(now));
        all.add(Timestamp.from(now));
        all.add(limit);

        return jdbc.query(sql.toString(), ROW, all.toArray());
    }

    @Override
    public Optional<Arrival> byVisitorId(UUID visitorId) {
        VisitorScopeSql.Clause clause = scopeClause();
        List<Object> args = new ArrayList<>();
        args.add(visitorId);
        args.addAll(clause.args());

        return jdbc.query(BASE + " AND v.id = ?" + clause.and(), ROW, args.toArray())
                .stream().findFirst();
    }

    /** Scoped through the request, because a visitor's tenant is their request's tenant. */
    private VisitorScopeSql.Clause scopeClause() {
        return VisitorScopeSql.on(scope.filterFor(ScopedEntity.VISITOR), "r.tenant_id", null);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
