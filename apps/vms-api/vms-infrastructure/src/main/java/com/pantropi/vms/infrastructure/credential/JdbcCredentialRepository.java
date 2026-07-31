package com.pantropi.vms.infrastructure.credential;

import com.pantropi.vms.application.credential.port.CredentialRepository;
import com.pantropi.vms.domain.credential.Credential;
import com.pantropi.vms.domain.masterdata.CredentialType;
import com.pantropi.vms.domain.masterdata.RestrictionType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC adapter for {@code vms.credentials} (US-09.1.1, T-09.1.1.1).
 *
 * <p>The enum columns are written through the domain's own {@code databaseValue()} rather than
 * {@code name()}, and cast explicitly — PostgreSQL will not coerce a bind parameter to an enum
 * type on its own, and the database spells these lowercase while Java spells them upper.
 */
public final class JdbcCredentialRepository implements CredentialRepository {

    private static final String COLUMNS = """
            id, visitor_id, pass_type_id, credential_type::text AS credential_type,
            restriction::text AS restriction, valid_from, valid_to, state::text AS state,
            acs_credential_id, qr_payload, issued_at""";

    private static final RowMapper<Credential> ROW = (rs, i) -> Credential.stored(
            rs.getObject("id", UUID.class),
            rs.getObject("visitor_id", UUID.class),
            rs.getObject("pass_type_id", UUID.class),
            CredentialType.fromDatabase(rs.getString("credential_type")),
            RestrictionType.fromDatabase(rs.getString("restriction")),
            rs.getTimestamp("valid_from").toInstant(),
            rs.getTimestamp("valid_to").toInstant(),
            Credential.State.fromDatabase(rs.getString("state")),
            rs.getString("acs_credential_id"),
            rs.getString("qr_payload"),
            rs.getTimestamp("issued_at") == null ? null : rs.getTimestamp("issued_at").toInstant());

    private final JdbcTemplate jdbc;

    public JdbcCredentialRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(Credential c) {
        jdbc.update("""
                INSERT INTO vms.credentials
                    (id, visitor_id, pass_type_id, credential_type, restriction,
                     valid_from, valid_to, state)
                VALUES (?, ?, ?, ?::vms.credential_type, ?::vms.restriction_type, ?, ?,
                        ?::vms.credential_state)
                """,
                c.id(), c.visitorId(), c.passTypeId(), c.credentialType().databaseValue(),
                c.restriction().databaseValue(), Timestamp.from(c.validFrom()),
                Timestamp.from(c.validTo()), c.state().dbValue());
    }

    @Override
    public void update(Credential c) {
        jdbc.update("""
                UPDATE vms.credentials
                   SET state = ?::vms.credential_state, acs_credential_id = ?, qr_payload = ?,
                       issued_at = ?, updated_at = now()
                 WHERE id = ?
                """,
                c.state().dbValue(), c.acsCredentialId(), c.qrPayload(),
                c.issuedAt() == null ? null : Timestamp.from(c.issuedAt()), c.id());
    }

    @Override
    public Optional<Credential> findById(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM vms.credentials WHERE id = ?", ROW, id)
                .stream().findFirst();
    }

    @Override
    public Optional<Credential> findLiveFor(UUID visitorId) {
        // `requested` counts as live: a request in flight is a pass about to exist, and issuing a
        // second one alongside it is the duplicate AC-7 is about — the same reasoning
        // IssuedCredentials.anyLiveFor already applies to revocation.
        return jdbc.query("SELECT " + COLUMNS + """
                 FROM vms.credentials
                WHERE visitor_id = ? AND state IN ('requested', 'active')
                ORDER BY created_at DESC LIMIT 1""", ROW, visitorId)
                .stream().findFirst();
    }
}
