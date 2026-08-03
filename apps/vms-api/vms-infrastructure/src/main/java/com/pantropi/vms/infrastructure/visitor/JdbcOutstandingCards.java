package com.pantropi.vms.infrastructure.visitor;

import com.pantropi.vms.application.visitor.port.OutstandingCards;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Cards a visitor has not handed back (US-12.2.2 AC-2, T-12.2.2.2).
 *
 * <p>Uses the partial index the baseline already declares for exactly this question
 * (idx_cards_open, WHERE returned_at IS NULL) — the schema anticipated the query before anything
 * asked it.
 *
 * <p>It answers empty today and that is correct, not a stub: nothing writes vms.card_issuances yet
 * because issuance is F-15.1. The read is here so the desk sees cards the moment there are any,
 * rather than the reminder being retrofitted after the first one goes missing.
 */
public final class JdbcOutstandingCards implements OutstandingCards {

    private final JdbcTemplate jdbc;

    public JdbcOutstandingCards(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Card> heldBy(UUID visitorId) {
        return jdbc.query("""
                SELECT id, acs_card_id, issued_at
                  FROM vms.card_issuances
                 WHERE visitor_id = ? AND returned_at IS NULL
                 ORDER BY issued_at
                """,
                (rs, i) -> new Card(rs.getObject("id", UUID.class), rs.getString("acs_card_id"),
                        rs.getTimestamp("issued_at") == null
                                ? null : rs.getTimestamp("issued_at").toInstant()),
                visitorId);
    }
}
