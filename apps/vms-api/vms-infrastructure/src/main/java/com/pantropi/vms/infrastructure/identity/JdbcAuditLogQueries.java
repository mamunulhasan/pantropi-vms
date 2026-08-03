package com.pantropi.vms.infrastructure.identity;

import com.pantropi.vms.application.identity.port.AuditLogQueries;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * JDBC adapter over {@code vms.audit_logs} (US-02.5.1).
 *
 * <p>Ordered newest first — the opposite of {@code JdbcDecisionTrail}, and on purpose. A decision
 * trail is read to reconstruct one sequence, so it reads forwards; a log is opened to see what just
 * happened, so it reads backwards. Ties break on the identity column because several rows written
 * in one transaction share a timestamp, and the column is the only thing that orders them.
 *
 * <p>The projection is the same shape the drill-down already discloses, plus the IP address the
 * denial recorder writes. It is not widened beyond that: {@code before_state} and {@code
 * after_state} are the allow-listed projections the writers chose, and this returns them as stored
 * rather than deciding for itself what may be shown.
 */
public final class JdbcAuditLogQueries implements AuditLogQueries {

    /** Clamped like every other list: a caller asking for everything gets a page. */
    private static final int MAX_SIZE = 200;

    private static final RowMapper<Entry> ROW = (rs, i) -> new Entry(
            rs.getLong("id"),
            instant(rs.getTimestamp("created_at")),
            rs.getString("action"),
            rs.getString("entity_type"),
            rs.getString("entity_id"),
            rs.getObject("user_id", UUID.class),
            rs.getString("actor_name"),
            rs.getString("before_state"),
            rs.getString("after_state"),
            rs.getString("ip_address"));

    private final JdbcTemplate jdbc;

    public JdbcAuditLogQueries(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Page search(Filter f) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (f.action() != null && !f.action().isBlank()) {
            where.append(" AND a.action = ?");
            args.add(f.action().trim());
        }
        if (f.entityType() != null && !f.entityType().isBlank()) {
            where.append(" AND a.entity_type = ?");
            args.add(f.entityType().trim());
        }
        if (f.actorId() != null) {
            where.append(" AND a.user_id = ?");
            args.add(f.actorId());
        }
        if (f.from() != null) {
            where.append(" AND a.created_at >= ?");
            args.add(Timestamp.from(f.from()));
        }
        if (f.to() != null) {
            // Exclusive, so "today" is [00:00, tomorrow 00:00) and no entry lands in two days.
            where.append(" AND a.created_at < ?");
            args.add(Timestamp.from(f.to()));
        }

        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM vms.audit_logs a" + where, Long.class, args.toArray());

        int size = Math.max(1, Math.min(f.size(), MAX_SIZE));
        int page = Math.max(0, f.page());
        List<Object> paged = new ArrayList<>(args);
        paged.add(size);
        paged.add((long) page * size);

        List<Entry> rows = jdbc.query("""
                SELECT a.id, a.created_at, a.action, a.entity_type, a.entity_id, a.user_id,
                       u.full_name AS actor_name, a.before_state::text AS before_state,
                       a.after_state::text AS after_state, a.ip_address::text AS ip_address
                  FROM vms.audit_logs a
                  LEFT JOIN vms.users u ON u.id = a.user_id
                """ + where + " ORDER BY a.created_at DESC, a.id DESC LIMIT ? OFFSET ?",
                ROW, paged.toArray());

        return new Page(rows, total == null ? 0 : total, page, size);
    }

    @Override
    public Vocabulary vocabulary() {
        // DISTINCT over what has actually been written, so the filter offers real choices rather
        // than a list of actions someone hoped existed.
        return new Vocabulary(
                jdbc.queryForList("SELECT DISTINCT action FROM vms.audit_logs ORDER BY action",
                        String.class),
                jdbc.queryForList("""
                        SELECT DISTINCT entity_type FROM vms.audit_logs
                         WHERE entity_type IS NOT NULL ORDER BY entity_type""", String.class));
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
