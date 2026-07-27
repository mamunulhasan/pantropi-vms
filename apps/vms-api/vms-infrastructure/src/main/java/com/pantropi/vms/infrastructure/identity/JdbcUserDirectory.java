package com.pantropi.vms.infrastructure.identity;

import com.pantropi.vms.application.identity.port.UserDirectory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC adapter for {@link UserDirectory} over {@code vms.users} (US-02.1.1).
 *
 * <p>Parameterised query only — no string concatenation, so username input cannot inject
 * (OWASP A03). Joins the role code so the token can carry it. Only active users authenticate.
 */
public final class JdbcUserDirectory implements UserDirectory {

    private static final String SQL = """
            SELECT u.id, u.username, u.password_hash, r.code AS role_code, u.is_active
            FROM vms.users u
            JOIN vms.roles r ON r.id = u.role_id
            WHERE u.username = ? AND u.is_active = true
            """;

    private final JdbcTemplate jdbc;

    public JdbcUserDirectory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<AuthUser> findActiveByUsername(String username) {
        List<AuthUser> rows = jdbc.query(SQL, (rs, i) -> new AuthUser(
                rs.getObject("id", UUID.class),
                rs.getString("username"),
                rs.getString("password_hash"),
                rs.getString("role_code"),
                rs.getBoolean("is_active")), username);
        return rows.stream().findFirst();
    }
}
