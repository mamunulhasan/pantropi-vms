package com.pantropi.vms.infrastructure.masterdata;

import com.pantropi.vms.application.masterdata.port.MasterDataStore;
import com.pantropi.vms.domain.masterdata.Building;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC adapter for {@link MasterDataStore} over {@code vms.buildings} (US-04.1.1, T-04.1.1.2).
 *
 * <h2>Uniqueness is the database's answer, not ours</h2>
 * The unique constraint on {@code code} is allowed to fire, and Spring's
 * {@link DuplicateKeyException} is translated into {@link DuplicateCode}. A "does this code exist?"
 * query followed by an insert would be a race — two concurrent requests both see nothing and both
 * insert, and whichever loses gets a raw constraint error the API never planned for (AC-4).
 *
 * <h2>Sorting is allow-listed</h2>
 * A sort key arrives from the query string and ends up in an {@code ORDER BY}, which cannot be a
 * bound parameter. It is therefore mapped through {@link #SORTABLE} and anything unrecognised falls
 * back to the default — the caller's string is never concatenated into SQL. Same approach as
 * {@code JdbcUserAdministrationStore}.
 *
 * <p>{@code updated_at} is left to the schema's trigger rather than set here, so a row touched by a
 * migration or a manual fix is stamped the same way as one touched by the API.
 */
public final class JdbcBuildingStore implements MasterDataStore<Building> {

    private static final Map<String, String> SORTABLE = Map.of(
            "code", "code",
            "name", "name",
            "createdAt", "created_at");
    private static final String DEFAULT_SORT = "code";

    private static final String COLUMNS = "id, code, name, address, is_active";

    private final JdbcTemplate jdbc;

    public JdbcBuildingStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UUID insert(Building draft) {
        try {
            return jdbc.queryForObject("""
                    INSERT INTO vms.buildings (code, name, address, is_active)
                    VALUES (?, ?, ?, ?) RETURNING id
                    """, UUID.class,
                    draft.code(), draft.name(), draft.address(), draft.active());
        } catch (DuplicateKeyException e) {
            throw new DuplicateCode(draft.code());
        }
    }

    @Override
    public void update(UUID id, Building draft) {
        int updated;
        try {
            updated = jdbc.update("""
                    UPDATE vms.buildings SET code = ?, name = ?, address = ?, is_active = ?
                    WHERE id = ?
                    """, draft.code(), draft.name(), draft.address(), draft.active(), id);
        } catch (DuplicateKeyException e) {
            throw new DuplicateCode(draft.code());
        }
        if (updated == 0) {
            throw new NotFound();
        }
    }

    @Override
    public void setActive(UUID id, boolean active) {
        if (jdbc.update("UPDATE vms.buildings SET is_active = ? WHERE id = ?", active, id) == 0) {
            throw new NotFound();
        }
    }

    @Override
    public Optional<Building> find(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM vms.buildings WHERE id = ?",
                JdbcBuildingStore::map, id).stream().findFirst();
    }

    @Override
    public Page<Building> list(Query query) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new java.util.ArrayList<>();

        if (query.active() != null) {
            where.append(" AND is_active = ?");
            args.add(query.active());
        }
        if (query.search() != null && !query.search().isBlank()) {
            // ILIKE over both fields: an operator searching "west" should find it whether it is the
            // code or the name that matches, without having to know which.
            where.append(" AND (code ILIKE ? OR name ILIKE ?)");
            String pattern = "%" + query.search().trim() + "%";
            args.add(pattern);
            args.add(pattern);
        }

        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM vms.buildings" + where, Long.class, args.toArray());

        int size = Math.max(1, Math.min(query.size(), 200));   // bounded: a page is not a dump
        int page = Math.max(0, query.page());
        List<Object> pageArgs = new java.util.ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(page * size);

        List<Building> items = jdbc.query(
                "SELECT " + COLUMNS + " FROM vms.buildings" + where
                        + " ORDER BY " + sortColumn(query.sort()) + ", id LIMIT ? OFFSET ?",
                JdbcBuildingStore::map, pageArgs.toArray());

        return new Page<>(items, page, size, total == null ? 0 : total);
    }

    /** Allow-listed; an unknown key silently becomes the default rather than reaching SQL. */
    private static String sortColumn(String requested) {
        return SORTABLE.getOrDefault(requested, DEFAULT_SORT);
    }

    private static Building map(ResultSet rs, int rowNum) throws SQLException {
        return new Building(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("address"),
                rs.getBoolean("is_active"));
    }
}
