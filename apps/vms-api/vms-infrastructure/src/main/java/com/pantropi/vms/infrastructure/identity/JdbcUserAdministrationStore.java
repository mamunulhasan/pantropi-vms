package com.pantropi.vms.infrastructure.identity;

import com.pantropi.vms.application.identity.port.UserAdministrationStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * JDBC adapter for {@link UserAdministrationStore} over {@code vms.users} (US-02.2.1).
 *
 * <p>Parameterised queries only. Uniqueness checks rely on the {@code citext} columns, so they are
 * case-insensitive (AC-5). No method selects or returns {@code password_hash}.
 */
public final class JdbcUserAdministrationStore implements UserAdministrationStore {

    /** Sort columns are allow-listed — the sort parameter can never reach SQL as free text. */
    private static final Set<String> SORTABLE =
            Set.of("username", "email", "full_name", "created_at");

    private final JdbcTemplate jdbc;

    public JdbcUserAdministrationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean usernameExists(String username) {
        return count("SELECT count(*) FROM vms.users WHERE username = ?", username) > 0;
    }

    @Override
    public boolean emailExists(String email) {
        return count("SELECT count(*) FROM vms.users WHERE email = ?", email) > 0;
    }

    @Override
    public Optional<UUID> roleIdByCode(String roleCode) {
        return jdbc.query("SELECT id FROM vms.roles WHERE code = ?",
                (rs, i) -> rs.getObject("id", UUID.class), roleCode).stream().findFirst();
    }

    @Override
    public boolean receptionActive(UUID receptionId) {
        return count("SELECT count(*) FROM vms.receptions WHERE id = ? AND is_active = true",
                receptionId) > 0;
    }

    @Override
    public boolean tenantActive(UUID tenantId) {
        return count("SELECT count(*) FROM vms.tenants WHERE id = ? AND is_active = true",
                tenantId) > 0;
    }

    @Override
    public UUID insert(NewUser u, String placeholderPasswordHash) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO vms.users
                    (id, username, email, full_name, password_hash, role_id, reception_id, tenant_id, is_active)
                VALUES (?, ?, ?, ?, ?, (SELECT id FROM vms.roles WHERE code = ?), ?, ?, true)
                """, id, u.username(), u.email(), u.fullName(), placeholderPasswordHash,
                u.roleCode(), u.receptionId(), u.tenantId());
        return id;
    }

    @Override
    public Optional<UserView> findById(UUID id) {
        return jdbc.query(SELECT + " WHERE u.id = ?", ROW, id).stream().findFirst();
    }

    @Override
    public void updateAssignment(UUID id, UUID roleId, UUID receptionId, UUID tenantId) {
        jdbc.update("""
                UPDATE vms.users SET role_id = ?, reception_id = ?, tenant_id = ?, updated_at = now()
                WHERE id = ?
                """, roleId, receptionId, tenantId, id);
    }

    @Override
    public void setActive(UUID id, boolean active) {
        jdbc.update("UPDATE vms.users SET is_active = ?, updated_at = now() WHERE id = ?", active, id);
    }

    @Override
    public Page list(UserFilter f) {
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        List<Object> args = new java.util.ArrayList<>();
        if (f.roleCode() != null) { where.append(" AND r.code = ?"); args.add(f.roleCode()); }
        if (f.receptionId() != null) { where.append(" AND u.reception_id = ?"); args.add(f.receptionId()); }
        if (f.active() != null) { where.append(" AND u.is_active = ?"); args.add(f.active()); }

        long total = count("SELECT count(*) FROM vms.users u JOIN vms.roles r ON r.id = u.role_id"
                + where, args.toArray());

        String sortCol = SORTABLE.contains(f.sort()) ? f.sort() : "username";
        int size = Math.max(1, Math.min(f.size(), 200));
        int page = Math.max(0, f.page());
        List<Object> pageArgs = new java.util.ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add(page * size);

        List<UserView> content = jdbc.query(
                SELECT + where + " ORDER BY u." + sortCol + " ASC LIMIT ? OFFSET ?",
                ROW, pageArgs.toArray());
        return new Page(content, total, page, size);
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private static final String SELECT = """
            SELECT u.id, u.username, u.email, u.full_name, r.code AS role_code,
                   u.reception_id, u.tenant_id, u.is_active
            FROM vms.users u JOIN vms.roles r ON r.id = u.role_id""";

    private static final org.springframework.jdbc.core.RowMapper<UserView> ROW = (rs, i) ->
            new UserView(
                    rs.getObject("id", UUID.class),
                    rs.getString("username"),
                    rs.getString("email"),
                    rs.getString("full_name"),
                    rs.getString("role_code"),
                    rs.getObject("reception_id", UUID.class),
                    rs.getObject("tenant_id", UUID.class),
                    rs.getBoolean("is_active"));
}
