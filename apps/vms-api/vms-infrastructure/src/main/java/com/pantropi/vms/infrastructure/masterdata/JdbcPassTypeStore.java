package com.pantropi.vms.infrastructure.masterdata;

import com.pantropi.vms.application.masterdata.port.MasterDataStore;
import com.pantropi.vms.domain.masterdata.CredentialType;
import com.pantropi.vms.domain.masterdata.PassType;
import com.pantropi.vms.domain.masterdata.RestrictionType;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC adapter for {@link MasterDataStore} over {@code vms.pass_types} (US-04.6.1, T-04.6.1.2).
 *
 * <h2>PostgreSQL enum columns need an explicit cast</h2>
 * {@code default_credential} and {@code default_restriction} are {@code vms.credential_type} and
 * {@code vms.restriction_type}. A bound {@code String} arrives as {@code varchar}, which PostgreSQL
 * will not implicitly coerce to an enum, so each placeholder carries {@code ::vms.<type>}. The cast
 * is a fixed literal in the SQL and the value stays a bound parameter — nothing here is
 * concatenated.
 *
 * <h2>Reading fails loudly on a value it does not know</h2>
 * The mapper goes through {@code fromDatabase}, which throws rather than defaulting. If someone adds
 * a value to the database enum without teaching the application about it, every read of an affected
 * row fails with a message naming the type and the value. The alternative — falling back to the
 * first constant — would silently issue the wrong kind of credential, and that is discovered at a
 * turnstile rather than in a log.
 */
public final class JdbcPassTypeStore implements MasterDataStore<PassType> {

    private static final Map<String, String> SORTABLE = Map.of(
            "code", "code",
            "name", "name",
            "validHours", "default_valid_hours");
    private static final String DEFAULT_SORT = "code";

    private static final String COLUMNS =
            "id, code, name, default_credential, default_restriction, default_valid_hours, is_active";

    private final JdbcTemplate jdbc;

    public JdbcPassTypeStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UUID insert(PassType draft) {
        try {
            return jdbc.queryForObject("""
                    INSERT INTO vms.pass_types
                        (code, name, default_credential, default_restriction,
                         default_valid_hours, is_active)
                    VALUES (?, ?, ?::vms.credential_type, ?::vms.restriction_type, ?, ?)
                    RETURNING id
                    """, UUID.class,
                    draft.code(), draft.name(),
                    draft.defaultCredential().databaseValue(),
                    draft.defaultRestriction().databaseValue(),
                    draft.defaultValidHours(), draft.active());
        } catch (DuplicateKeyException e) {
            throw new DuplicateCode(draft.code());
        }
    }

    @Override
    public void update(UUID id, PassType draft) {
        int updated;
        try {
            updated = jdbc.update("""
                    UPDATE vms.pass_types SET
                        code = ?, name = ?,
                        default_credential = ?::vms.credential_type,
                        default_restriction = ?::vms.restriction_type,
                        default_valid_hours = ?, is_active = ?
                    WHERE id = ?
                    """,
                    draft.code(), draft.name(),
                    draft.defaultCredential().databaseValue(),
                    draft.defaultRestriction().databaseValue(),
                    draft.defaultValidHours(), draft.active(), id);
        } catch (DuplicateKeyException e) {
            throw new DuplicateCode(draft.code());
        }
        if (updated == 0) {
            throw new NotFound();
        }
    }

    @Override
    public void setActive(UUID id, boolean active) {
        if (jdbc.update("UPDATE vms.pass_types SET is_active = ? WHERE id = ?", active, id) == 0) {
            throw new NotFound();
        }
    }

    @Override
    public Optional<PassType> find(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM vms.pass_types WHERE id = ?",
                JdbcPassTypeStore::map, id).stream().findFirst();
    }

    @Override
    public Page<PassType> list(Query query) {
        MasterDataQuery q = MasterDataQuery.of(query, null);

        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM vms.pass_types" + q.where(), Long.class, q.countArgs());

        List<PassType> items = jdbc.query(
                "SELECT " + COLUMNS + " FROM vms.pass_types" + q.where()
                        + " ORDER BY " + sortColumn(query.sort()) + ", id LIMIT ? OFFSET ?",
                JdbcPassTypeStore::map, q.pageArgs());

        return new Page<>(items, q.page(), q.size(), total == null ? 0 : total);
    }

    /** Allow-listed; an unknown key becomes the default rather than reaching SQL. */
    private static String sortColumn(String requested) {
        return SORTABLE.getOrDefault(requested, DEFAULT_SORT);
    }

    private static PassType map(ResultSet rs, int rowNum) throws SQLException {
        return new PassType(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                // fromDatabase throws on a value this code does not know — see the class note.
                CredentialType.fromDatabase(rs.getString("default_credential")),
                RestrictionType.fromDatabase(rs.getString("default_restriction")),
                rs.getInt("default_valid_hours"),
                rs.getBoolean("is_active"));
    }
}
