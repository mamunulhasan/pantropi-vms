package com.pantropi.vms.infrastructure.masterdata;

import com.pantropi.vms.application.masterdata.port.MasterDataStore;
import com.pantropi.vms.domain.masterdata.VisitorType;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC adapter for {@link MasterDataStore} over {@code vms.visitor_types} (US-04.5.1, T-04.5.1.1).
 *
 * <p>The simplest of the coded stores: a global unique {@code code}, no parent, no enum columns.
 * Duplicate handling is the same constraint-driven translation as everywhere else — the database
 * decides, not a pre-check that two concurrent requests could both pass (AC-4).
 */
public final class JdbcVisitorTypeStore implements MasterDataStore<VisitorType> {

    private static final Map<String, String> SORTABLE = Map.of(
            "code", "code",
            "name", "name",
            "createdAt", "created_at");
    private static final String DEFAULT_SORT = "code";

    private static final String COLUMNS = "id, code, name, description, is_active";

    private final JdbcTemplate jdbc;

    public JdbcVisitorTypeStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UUID insert(VisitorType draft) {
        try {
            return jdbc.queryForObject("""
                    INSERT INTO vms.visitor_types (code, name, description, is_active)
                    VALUES (?, ?, ?, ?) RETURNING id
                    """, UUID.class,
                    draft.code(), draft.name(), draft.description(), draft.active());
        } catch (DuplicateKeyException e) {
            throw new DuplicateCode(draft.code());
        }
    }

    @Override
    public void update(UUID id, VisitorType draft) {
        int updated;
        try {
            updated = jdbc.update("""
                    UPDATE vms.visitor_types SET code = ?, name = ?, description = ?, is_active = ?
                    WHERE id = ?
                    """, draft.code(), draft.name(), draft.description(), draft.active(), id);
        } catch (DuplicateKeyException e) {
            throw new DuplicateCode(draft.code());
        }
        if (updated == 0) {
            throw new NotFound();
        }
    }

    @Override
    public void setActive(UUID id, boolean active) {
        if (jdbc.update("UPDATE vms.visitor_types SET is_active = ? WHERE id = ?", active, id) == 0) {
            throw new NotFound();
        }
    }

    @Override
    public Optional<VisitorType> find(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM vms.visitor_types WHERE id = ?",
                JdbcVisitorTypeStore::map, id).stream().findFirst();
    }

    @Override
    public Page<VisitorType> list(Query query) {
        MasterDataQuery q = MasterDataQuery.of(query, null);

        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM vms.visitor_types" + q.where(), Long.class, q.countArgs());

        List<VisitorType> items = jdbc.query(
                "SELECT " + COLUMNS + " FROM vms.visitor_types" + q.where()
                        + " ORDER BY " + sortColumn(query.sort()) + ", id LIMIT ? OFFSET ?",
                JdbcVisitorTypeStore::map, q.pageArgs());

        return new Page<>(items, q.page(), q.size(), total == null ? 0 : total);
    }

    /** Allow-listed; an unknown key becomes the default rather than reaching SQL. */
    private static String sortColumn(String requested) {
        return SORTABLE.getOrDefault(requested, DEFAULT_SORT);
    }

    private static VisitorType map(ResultSet rs, int rowNum) throws SQLException {
        return new VisitorType(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getBoolean("is_active"));
    }
}
