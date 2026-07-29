package com.pantropi.vms.infrastructure.visitor;

import com.pantropi.vms.application.identity.usecase.ScopePolicy;
import com.pantropi.vms.application.visitor.port.VisitorRequestRepository;
import com.pantropi.vms.domain.identity.ScopedEntity;
import com.pantropi.vms.domain.visitor.RequestStatus;
import com.pantropi.vms.domain.visitor.TimeWindow;
import com.pantropi.vms.domain.visitor.VisitKind;
import com.pantropi.vms.domain.visitor.Visitor;
import com.pantropi.vms.domain.visitor.VisitorRequest;
import com.pantropi.vms.domain.visitor.VisitorStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC adapter for {@link VisitorRequestRepository} (US-07.1.1, T-07.1.1.2).
 *
 * <p>Saves the aggregate as one unit: the request row and every visitor row, in the caller's
 * transaction. PostgreSQL enum columns are written with an explicit cast, and read back through the
 * domain's {@code fromDb} mappers, so a value can never drift between the database enum and the
 * Java enum without failing loudly.
 *
 * <p>JDBC rather than JPA: Hibernate is not available in this build, and the project has used
 * {@link JdbcTemplate} throughout. The port keeps that choice invisible to the use case.
 */
public final class JdbcVisitorRequestRepository implements VisitorRequestRepository {

    private final JdbcTemplate jdbc;

    private final ScopePolicy scope;

    public JdbcVisitorRequestRepository(JdbcTemplate jdbc, ScopePolicy scope) {
        this.scope = scope;
        this.jdbc = jdbc;
    }

    @Override
    public void save(VisitorRequest r) {
        jdbc.update("""
                INSERT INTO vms.visitor_requests
                    (id, tenant_id, host_id, requested_by, approved_by, visit_kind,
                     scheduled_from, scheduled_to, status, purpose)
                VALUES (?, ?, ?, ?, ?, ?::vms.visit_kind, ?, ?, ?::vms.request_status, ?)
                """,
                r.id(), r.tenantId(), r.hostId(), r.requestedBy(), r.approvedBy(),
                r.visitKind().dbValue(),
                Timestamp.from(r.window().from()), Timestamp.from(r.window().to()),
                r.status().dbValue(), r.purpose());

        for (Visitor v : r.visitors()) {
            jdbc.update("""
                    INSERT INTO vms.visitors
                        (id, request_id, full_name, email, phone, company,
                         appointment_from, appointment_to, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::vms.visitor_status)
                    """,
                    v.id(), r.id(), v.fullName(), v.email(), v.phone(), v.company(),
                    Timestamp.from(r.window().from()), Timestamp.from(r.window().to()),
                    v.status().dbValue());
        }
    }

    /**
     * Applies a decision as a compare-and-set on the status (US-07.4.1, T-07.4.1.2, AC-6).
     *
     * <p>The expected previous status is part of the {@code WHERE} clause, so of two concurrent
     * approvals the second updates no row and gets {@code false}. There is no {@code SELECT … FOR
     * UPDATE} beforehand and none is needed: a single {@code UPDATE} takes its own row lock, and the
     * loser's re-read after that lock is released sees the winner's status and fails the predicate.
     *
     * <p>The visitor rows are written only once the request row has been won, so a losing caller
     * never touches them even before its transaction rolls back.
     */
    @Override
    public boolean saveDecision(VisitorRequest r, RequestStatus expectedPrevious) {
        int updated = jdbc.update("""
                UPDATE vms.visitor_requests
                   SET status          = ?::vms.request_status,
                       approved_by     = ?,
                       decision_reason = ?,
                       decided_at      = ?,
                       updated_at      = now()
                 WHERE id = ? AND status = ?::vms.request_status
                """,
                r.status().dbValue(), r.approvedBy(), r.decisionReason(),
                r.decidedAt() == null ? null : Timestamp.from(r.decidedAt()),
                r.id(), expectedPrevious.dbValue());

        if (updated == 0) {
            return false;
        }

        // Each visitor is written with the status the aggregate holds, which for one already in a
        // terminal state is the value it came in with — the cascade decided what moves, and this
        // only records the outcome.
        for (Visitor v : r.visitors()) {
            jdbc.update("UPDATE vms.visitors SET status = ?::vms.visitor_status WHERE id = ?",
                    v.status().dbValue(), v.id());
        }
        return true;
    }

    /**
     * Reads one request, <strong>subject to the caller's scope</strong> (US-03.4.1 AC-2).
     *
     * <p>The predicate comes from {@link ScopePolicy}, never from a condition written here. A
     * tenant asking for another tenant's request gets an empty result — the same answer as for a
     * request that does not exist, so the id cannot be used to probe for what else is there.
     */
    @Override
    public Optional<VisitorRequest> findById(UUID id) {
        VisitorScopeSql.Clause clause = VisitorScopeSql.on(
                scope.filterFor(ScopedEntity.VISITOR_REQUEST), "tenant_id", null);
        List<Object> args = new java.util.ArrayList<>();
        args.add(id);
        args.addAll(clause.args());

        List<Object[]> rows = jdbc.query("""
                SELECT tenant_id, host_id, requested_by, approved_by, visit_kind,
                       scheduled_from, scheduled_to, status, purpose,
                       decision_reason, decided_at
                FROM vms.visitor_requests WHERE id = ?
                """ + " AND " + clause.sql(),
                // The separator sits outside the text block on purpose: a text block strips the
                // trailing whitespace from every line, so "... AND """ + clause would concatenate
                // to "ANDTRUE".
                (rs, i) -> new Object[]{
                rs.getObject("tenant_id", UUID.class), rs.getObject("host_id", UUID.class),
                rs.getObject("requested_by", UUID.class), rs.getObject("approved_by", UUID.class),
                rs.getString("visit_kind"), rs.getTimestamp("scheduled_from"),
                rs.getTimestamp("scheduled_to"), rs.getString("status"), rs.getString("purpose"),
                rs.getString("decision_reason"), rs.getTimestamp("decided_at")},
                args.toArray());
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] row = rows.get(0);

        List<Visitor> visitors = jdbc.query("""
                SELECT id, full_name, email, phone, company, status
                FROM vms.visitors WHERE request_id = ? ORDER BY created_at
                """, (rs, i) -> Visitor.rehydrate(
                rs.getObject("id", UUID.class), rs.getString("full_name"), rs.getString("email"),
                rs.getString("phone"), rs.getString("company"),
                VisitorStatus.fromDb(rs.getString("status"))), id);

        Timestamp decidedAt = (Timestamp) row[10];
        return Optional.of(VisitorRequest.rehydrate(id,
                (UUID) row[0], (UUID) row[1], (UUID) row[2],
                VisitKind.fromDb((String) row[4]),
                new TimeWindow(((Timestamp) row[5]).toInstant(), ((Timestamp) row[6]).toInstant()),
                (String) row[8], visitors, RequestStatus.fromDb((String) row[7]), (UUID) row[3],
                (String) row[9], decidedAt == null ? null : decidedAt.toInstant()));
    }
}
