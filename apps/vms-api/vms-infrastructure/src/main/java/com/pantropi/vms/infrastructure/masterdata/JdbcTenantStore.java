package com.pantropi.vms.infrastructure.masterdata;

import com.pantropi.vms.application.masterdata.port.MasterDataStore;
import com.pantropi.vms.domain.masterdata.Tenant;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC adapter for {@link MasterDataStore} over {@code vms.tenants} (US-04.3.1, T-04.3.1.2).
 *
 * <h2>An optional parent that must still be active when supplied</h2>
 * {@code floor_id} is nullable, so unlike floors-under-buildings the parent rule is conditional: no
 * floor is fine, but a named floor must be active. Both halves are expressed in the write itself —
 * {@code WHERE ?::uuid IS NULL OR EXISTS (… AND is_active)} — for the same reason as US-04.2.1: a
 * separate check could pass and then be invalidated by a concurrent deactivation before the insert
 * lands.
 */
public final class JdbcTenantStore implements MasterDataStore<Tenant> {

    private static final Map<String, String> SORTABLE = Map.of(
            "code", "code",
            "name", "name",
            "createdAt", "created_at");
    private static final String DEFAULT_SORT = "code";

    private static final String COLUMNS =
            "id, code, name, floor_id, contact_email, contact_phone, is_active";

    private final JdbcTemplate jdbc;

    public JdbcTenantStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UUID insert(Tenant draft) {
        List<UUID> ids;
        try {
            ids = jdbc.query("""
                    INSERT INTO vms.tenants
                        (code, name, floor_id, contact_email, contact_phone, is_active)
                    SELECT ?, ?, ?, ?, ?, ?
                    WHERE ?::uuid IS NULL
                       OR EXISTS (SELECT 1 FROM vms.floors WHERE id = ?::uuid AND is_active)
                    RETURNING id
                    """, (rs, i) -> rs.getObject("id", UUID.class),
                    draft.code(), draft.name(), draft.floorId(), draft.contactEmail(),
                    draft.contactPhone(), draft.active(),
                    draft.floorId(), draft.floorId());
        } catch (DuplicateKeyException e) {
            throw new DuplicateCode(draft.code());
        }
        if (ids.isEmpty()) {
            throw new InvalidParent("floorId");
        }
        return ids.get(0);
    }

    @Override
    public void update(UUID id, Tenant draft) {
        int updated;
        try {
            updated = jdbc.update("""
                    UPDATE vms.tenants SET
                        code = ?, name = ?, floor_id = ?, contact_email = ?,
                        contact_phone = ?, is_active = ?
                    WHERE id = ?
                      AND (?::uuid IS NULL
                           OR EXISTS (SELECT 1 FROM vms.floors WHERE id = ?::uuid AND is_active))
                    """, draft.code(), draft.name(), draft.floorId(), draft.contactEmail(),
                    draft.contactPhone(), draft.active(), id,
                    draft.floorId(), draft.floorId());
        } catch (DuplicateKeyException e) {
            throw new DuplicateCode(draft.code());
        }
        if (updated == 0) {
            // Either no such tenant, or the named floor is not usable. Distinguished with one extra
            // read rather than guessed, so the caller gets 404 or 400 correctly.
            if (find(id).isEmpty()) {
                throw new NotFound();
            }
            throw new InvalidParent("floorId");
        }
    }

    @Override
    public void setActive(UUID id, boolean active) {
        if (jdbc.update("UPDATE vms.tenants SET is_active = ? WHERE id = ?", active, id) == 0) {
            throw new NotFound();
        }
    }

    @Override
    public Optional<Tenant> find(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM vms.tenants WHERE id = ?",
                JdbcTenantStore::map, id).stream().findFirst();
    }

    @Override
    public Page<Tenant> list(Query query) {
        // floor_id filter uses idx_tenants_floor (T-04.3.1.2).
        MasterDataQuery q = MasterDataQuery.of(query, "floor_id");

        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM vms.tenants" + q.where(), Long.class, q.countArgs());

        List<Tenant> items = jdbc.query(
                "SELECT " + COLUMNS + " FROM vms.tenants" + q.where()
                        + " ORDER BY " + sortColumn(query.sort()) + ", id LIMIT ? OFFSET ?",
                JdbcTenantStore::map, q.pageArgs());

        return new Page<>(items, q.page(), q.size(), total == null ? 0 : total);
    }

    /** Allow-listed; an unknown key becomes the default rather than reaching SQL. */
    private static String sortColumn(String requested) {
        return SORTABLE.getOrDefault(requested, DEFAULT_SORT);
    }

    private static Tenant map(ResultSet rs, int rowNum) throws SQLException {
        return new Tenant(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                rs.getObject("floor_id", UUID.class),
                rs.getString("contact_email"),
                rs.getString("contact_phone"),
                rs.getBoolean("is_active"));
    }
}
