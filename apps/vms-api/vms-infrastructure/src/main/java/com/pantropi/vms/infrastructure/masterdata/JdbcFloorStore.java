package com.pantropi.vms.infrastructure.masterdata;

import com.pantropi.vms.application.masterdata.port.MasterDataStore;
import com.pantropi.vms.domain.masterdata.Floor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC adapter for {@link MasterDataStore} over {@code vms.floors} (US-04.2.1, T-04.2.1.2).
 *
 * <h2>The active-parent rule is part of the write, not a check before it</h2>
 * A foreign key enforces that the building <em>exists</em>; it says nothing about whether it is
 * still active. The obvious implementation — query the building, then insert — is a race: the
 * building can be deactivated between the two statements, and the floor lands under a retired
 * parent. So the insert is conditional on the parent being active <em>in the same statement</em>
 * ({@code WHERE EXISTS (... AND is_active)}), and zero rows returned means the parent was not
 * eligible (AC-5). Same reasoning as letting the unique constraint decide duplicates.
 *
 * <h2>Uniqueness is composite</h2>
 * {@code UNIQUE (building_id, code)} — every tower has an "L01", and the same code under a different
 * building is legitimate (AC-4). The conflict translation is identical to the building store's; only
 * the constraint's scope differs, which is exactly the kind of difference the shared port is meant
 * to absorb.
 */
public final class JdbcFloorStore implements MasterDataStore<Floor> {

    private static final Map<String, String> SORTABLE = Map.of(
            "levelNo", "level_no NULLS LAST, code",
            "code", "code",
            "name", "name");

    /**
     * AC-2: by {@code level_no} where present, by {@code code} otherwise. {@code NULLS LAST} puts
     * the unnumbered floors — mezzanines, plant rooms — after the numbered ones rather than before,
     * which is where a person looking at a building's floor list expects them.
     */
    private static final String DEFAULT_SORT = "level_no NULLS LAST, code";

    private static final String COLUMNS = "id, building_id, code, name, level_no, is_active";

    private final JdbcTemplate jdbc;

    public JdbcFloorStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UUID insert(Floor draft) {
        List<UUID> ids;
        try {
            ids = jdbc.query("""
                    INSERT INTO vms.floors (building_id, code, name, level_no, is_active)
                    SELECT ?, ?, ?, ?, ?
                    WHERE EXISTS (SELECT 1 FROM vms.buildings WHERE id = ? AND is_active)
                    RETURNING id
                    """, (rs, i) -> rs.getObject("id", UUID.class),
                    draft.buildingId(), draft.code(), draft.name(), draft.levelNo(), draft.active(),
                    draft.buildingId());
        } catch (DuplicateKeyException e) {
            throw new DuplicateCode(draft.code());
        }
        if (ids.isEmpty()) {
            // The EXISTS guard rejected it: no such building, or it is deactivated.
            throw new InvalidParent("buildingId");
        }
        return ids.get(0);
    }

    @Override
    public void update(UUID id, Floor draft) {
        int updated;
        try {
            // Scoped by building as well as id, so a request that names the wrong parent in its
            // path is a not-found rather than a silent move between buildings — which would
            // relocate every reception on the floor without anyone asking for it.
            updated = jdbc.update("""
                    UPDATE vms.floors SET code = ?, name = ?, level_no = ?, is_active = ?
                    WHERE id = ? AND building_id = ?
                    """, draft.code(), draft.name(), draft.levelNo(), draft.active(),
                    id, draft.buildingId());
        } catch (DuplicateKeyException e) {
            throw new DuplicateCode(draft.code());
        }
        if (updated == 0) {
            throw new NotFound();
        }
    }

    @Override
    public void setActive(UUID id, boolean active) {
        if (jdbc.update("UPDATE vms.floors SET is_active = ? WHERE id = ?", active, id) == 0) {
            throw new NotFound();
        }
    }

    @Override
    public Optional<Floor> find(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM vms.floors WHERE id = ?",
                JdbcFloorStore::map, id).stream().findFirst();
    }

    @Override
    public Page<Floor> list(Query query) {
        // building_id filter uses idx_floors_building (T-04.2.1.2).
        MasterDataQuery q = MasterDataQuery.of(query, "building_id");

        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM vms.floors" + q.where(), Long.class, q.countArgs());

        List<Floor> items = jdbc.query(
                "SELECT " + COLUMNS + " FROM vms.floors" + q.where()
                        // The id tiebreak keeps paging stable when level_no and code both tie
                        // across buildings — without it, two pages could repeat or skip a row.
                        + " ORDER BY " + sortColumn(query.sort()) + ", id LIMIT ? OFFSET ?",
                JdbcFloorStore::map, q.pageArgs());

        return new Page<>(items, q.page(), q.size(), total == null ? 0 : total);
    }

    /** Allow-listed; an unknown key becomes the default rather than reaching SQL. */
    private static String sortColumn(String requested) {
        return SORTABLE.getOrDefault(requested, DEFAULT_SORT);
    }

    private static Floor map(ResultSet rs, int rowNum) throws SQLException {
        return new Floor(
                rs.getObject("id", UUID.class),
                rs.getObject("building_id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                // getObject, not getInt + wasNull(): wasNull() reports on the most recent column
                // read, so with other reads interleaved it would answer about the wrong one and a
                // null level_no would silently map to 0 — a plant room sorted as the ground floor.
                rs.getObject("level_no", Integer.class),
                rs.getBoolean("is_active"));
    }
}
