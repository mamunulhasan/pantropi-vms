package com.pantropi.vms.infrastructure.visitor;

import com.pantropi.vms.application.visitor.port.VisitorLifecycle;
import com.pantropi.vms.domain.visitor.VisitorStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Moves one visitor's status, and the timestamp that goes with it (US-12.2.1, US-12.2.2).
 *
 * <p>Compare-and-set in the WHERE clause, like the approval path. Two receptionists can have the
 * same visitor open; the second UPDATE matches no row and the caller is told, rather than
 * overwriting a checked_in_at somebody else just wrote.
 *
 * <p>The timestamp column is chosen from the target status rather than passed in, so a status and
 * its timestamp cannot disagree — there is no call that could set checked_out while stamping
 * checked_in_at.
 */
public final class JdbcVisitorLifecycle implements VisitorLifecycle {

    private final JdbcTemplate jdbc;

    public JdbcVisitorLifecycle(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean transition(UUID visitorId, VisitorStatus expected, VisitorStatus next,
                              Instant at) {
        String stamp = switch (next) {
            case CHECKED_IN -> ", checked_in_at = ?";
            case CHECKED_OUT -> ", checked_out_at = ?";
            // Every other transition is a status change with no arrival time of its own.
            default -> "";
        };

        String sql = "UPDATE vms.visitors SET status = ?::vms.visitor_status" + stamp
                + ", updated_at = now() WHERE id = ? AND status = ?::vms.visitor_status";

        int updated = stamp.isEmpty()
                ? jdbc.update(sql, next.dbValue(), visitorId, expected.dbValue())
                : jdbc.update(sql, next.dbValue(), Timestamp.from(at), visitorId,
                        expected.dbValue());
        return updated == 1;
    }
}
