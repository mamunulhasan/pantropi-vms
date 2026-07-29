package com.pantropi.vms.infrastructure.masterdata;

import com.pantropi.vms.application.masterdata.port.MasterDataStore;
import com.pantropi.vms.domain.masterdata.Reception;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC adapter for {@link MasterDataStore} over {@code vms.receptions} (US-04.4.1, T-04.4.1.2).
 *
 * <p>Floor-scoped uniqueness ({@code UNIQUE (floor_id, code)}) and the active-parent rule inside the
 * write, exactly as for floors under buildings. The central designation is <em>not</em> handled
 * here — it spans two rows and belongs to
 * {@link com.pantropi.vms.application.masterdata.usecase.ReceptionAdministration}.
 */
public final class JdbcReceptionStore implements MasterDataStore<Reception> {

    private static final Map<String, String> SORTABLE = Map.of(
            "code", "code",
            "name", "name",
            "central", "is_central DESC, code");
    private static final String DEFAULT_SORT = "code";

    private static final String COLUMNS = "id, floor_id, code, name, is_central, is_active";

    private final JdbcTemplate jdbc;

    public JdbcReceptionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UUID insert(Reception draft) {
        List<UUID> ids;
        try {
            ids = jdbc.query("""
                    INSERT INTO vms.receptions (floor_id, code, name, is_central, is_active)
                    SELECT ?, ?, ?, ?, ?
                    WHERE EXISTS (SELECT 1 FROM vms.floors WHERE id = ? AND is_active)
                    RETURNING id
                    """, (rs, i) -> rs.getObject("id", UUID.class),
                    draft.floorId(), draft.code(), draft.name(), draft.central(), draft.active(),
                    draft.floorId());
        } catch (DuplicateKeyException e) {
            throw new DuplicateCode(draft.code());
        }
        if (ids.isEmpty()) {
            throw new InvalidParent("floorId");
        }
        return ids.get(0);
    }

    @Override
    public void update(UUID id, Reception draft) {
        int updated;
        try {
            // is_central is deliberately absent: it is transferred through ReceptionAdministration,
            // never set as a side effect of renaming. An ordinary edit that could silently move the
            // designation would be a way to move it without the confirmation AC-2 requires.
            updated = jdbc.update("""
                    UPDATE vms.receptions SET code = ?, name = ?, is_active = ?
                    WHERE id = ? AND floor_id = ?
                    """, draft.code(), draft.name(), draft.active(), id, draft.floorId());
        } catch (DuplicateKeyException e) {
            throw new DuplicateCode(draft.code());
        }
        if (updated == 0) {
            throw new NotFound();
        }
    }

    @Override
    public void setActive(UUID id, boolean active) {
        if (jdbc.update("UPDATE vms.receptions SET is_active = ? WHERE id = ?", active, id) == 0) {
            throw new NotFound();
        }
    }

    @Override
    public Optional<Reception> find(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM vms.receptions WHERE id = ?",
                JdbcReceptionStore::map, id).stream().findFirst();
    }

    @Override
    public Page<Reception> list(Query query) {
        // floor_id filter uses idx_receptions_floor (T-04.4.1.2).
        MasterDataQuery q = MasterDataQuery.of(query, "floor_id");

        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM vms.receptions" + q.where(), Long.class, q.countArgs());

        List<Reception> items = jdbc.query(
                "SELECT " + COLUMNS + " FROM vms.receptions" + q.where()
                        + " ORDER BY " + sortColumn(query.sort()) + ", id LIMIT ? OFFSET ?",
                JdbcReceptionStore::map, q.pageArgs());

        return new Page<>(items, q.page(), q.size(), total == null ? 0 : total);
    }

    /**
     * Receptions across a whole building, and those flagged central (AC-3) — the counts an
     * administrator checks the 120+1 structure against.
     */
    public Page<Reception> listInBuilding(UUID buildingId, Boolean central, Boolean active,
                                          int page, int size) {
        StringBuilder where = new StringBuilder("""
                 WHERE r.floor_id IN (SELECT id FROM vms.floors WHERE building_id = ?)""");
        List<Object> args = new java.util.ArrayList<>();
        args.add(buildingId);
        if (central != null) {
            where.append(" AND r.is_central = ?");
            args.add(central);
        }
        if (active != null) {
            where.append(" AND r.is_active = ?");
            args.add(active);
        }

        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM vms.receptions r" + where, Long.class, args.toArray());

        int bounded = Math.max(1, Math.min(size, 200));
        int safePage = Math.max(0, page);
        List<Object> pageArgs = new java.util.ArrayList<>(args);
        pageArgs.add(bounded);
        pageArgs.add(safePage * bounded);

        List<Reception> items = jdbc.query(
                "SELECT r.id, r.floor_id, r.code, r.name, r.is_central, r.is_active"
                        + " FROM vms.receptions r" + where
                        + " ORDER BY r.is_central DESC, r.code, r.id LIMIT ? OFFSET ?",
                JdbcReceptionStore::map, pageArgs.toArray());

        return new Page<>(items, safePage, bounded, total == null ? 0 : total);
    }

    /** Allow-listed; an unknown key becomes the default rather than reaching SQL. */
    private static String sortColumn(String requested) {
        return SORTABLE.getOrDefault(requested, DEFAULT_SORT);
    }

    private static Reception map(ResultSet rs, int rowNum) throws SQLException {
        return new Reception(
                rs.getObject("id", UUID.class),
                rs.getObject("floor_id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                rs.getBoolean("is_central"),
                rs.getBoolean("is_active"));
    }
}
