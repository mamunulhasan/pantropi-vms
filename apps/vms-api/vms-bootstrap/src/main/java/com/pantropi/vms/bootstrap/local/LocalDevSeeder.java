package com.pantropi.vms.bootstrap.local;

import com.pantropi.vms.application.identity.port.PasswordHasher;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;
import java.util.logging.Logger;

/**
 * Seeds a usable local system so every role can be exercised by hand (US-01.3.1).
 *
 * <p>Creates one user per seeded role, plus the master data the visitor flow needs (a building, a
 * floor, a tenant, a central reception, a host). Idempotent: safe to run on every start.
 *
 * <p><strong>Local profile only</strong>, and the passwords below are fixed, published in this
 * source, and printed at startup — they exist so a developer can log in, and they are worthless
 * anywhere else. Nothing here runs in staging or production: the profile guard, and the
 * {@code developmentOnly} dependency on the embedded database, both prevent it.
 *
 * <h2>Role grants</h2>
 * The grants applied here are a <em>development convenience</em> so each role can do something
 * observable. They are deliberately NOT a migration: the production role-to-permission matrix is
 * authorization policy that still needs client sign-off (discrepancy D-15, TODO-04). Migrations
 * V3/V5/V7 grant only what an SRS requirement names.
 */
@Component
@Profile("local")
public class LocalDevSeeder implements ApplicationRunner {

    private static final Logger log = Logger.getLogger(LocalDevSeeder.class.getName());

    /** Dev-only credentials. Same password for every account, to keep exploration simple. */
    private static final String DEV_PASSWORD = "Password123!local";

    private record DevUser(String username, String role, boolean withTenant, boolean withReception) {}

    private static final List<DevUser> USERS = List.of(
            new DevUser("sysadmin",     "SYSTEM_ADMIN",       false, false),
            new DevUser("masteradmin",  "MASTER_ADMIN",       false, true),
            new DevUser("fmadmin",      "FM_ADMIN",           false, false),
            new DevUser("receptionist", "FLOOR_RECEPTIONIST", false, true),
            new DevUser("tenantuser",   "TENANT",             true,  false));

    /**
     * Development grant matrix, inferred from the seeded role descriptions so each role has
     * something to exercise. Not authoritative — see the class note.
     */
    private static final List<String[]> DEV_GRANTS = List.of(
            new String[]{"SYSTEM_ADMIN",       "masterdata.view"},
            new String[]{"SYSTEM_ADMIN",       "masterdata.edit"},
            new String[]{"SYSTEM_ADMIN",       "settings.manage"},
            new String[]{"MASTER_ADMIN",       "masterdata.view"},
            new String[]{"MASTER_ADMIN",       "visitor.register"},
            new String[]{"MASTER_ADMIN",       "report.view"},
            new String[]{"FM_ADMIN",           "visitor.approve"},
            new String[]{"FM_ADMIN",           "masterdata.view"},
            new String[]{"FM_ADMIN",           "report.view"},
            new String[]{"FLOOR_RECEPTIONIST", "visitor.register"},
            new String[]{"FLOOR_RECEPTIONIST", "masterdata.view"},
            new String[]{"TENANT",             "masterdata.view"});

    private final JdbcTemplate jdbc;
    private final PasswordHasher passwordHasher;

    public LocalDevSeeder(DataSource dataSource, PasswordHasher passwordHasher) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.passwordHasher = passwordHasher;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedMasterData();
        seedGrants();
        seedUsers();
        announce();
    }

    private void seedMasterData() {
        jdbc.update("""
                INSERT INTO vms.buildings (id, code, name, address)
                VALUES (gen_random_uuid(), 'WGT', 'Westgate Tower', 'Westgate, Dhaka')
                ON CONFLICT (code) DO NOTHING""");
        jdbc.update("""
                INSERT INTO vms.floors (id, building_id, code, name, level_no)
                SELECT gen_random_uuid(), b.id, 'L01', 'Level 1', 1 FROM vms.buildings b
                WHERE b.code = 'WGT'
                ON CONFLICT (building_id, code) DO NOTHING""");
        jdbc.update("""
                INSERT INTO vms.receptions (id, floor_id, code, name, is_central)
                SELECT gen_random_uuid(), f.id, 'RC01', 'Central Reception', true
                FROM vms.floors f JOIN vms.buildings b ON b.id = f.building_id
                WHERE b.code = 'WGT' AND f.code = 'L01'
                ON CONFLICT (floor_id, code) DO NOTHING""");
        jdbc.update("""
                INSERT INTO vms.tenants (id, code, name, floor_id, contact_email)
                SELECT gen_random_uuid(), 'ACME', 'Acme Corporation', f.id, 'office@acme.test'
                FROM vms.floors f JOIN vms.buildings b ON b.id = f.building_id
                WHERE b.code = 'WGT' AND f.code = 'L01'
                ON CONFLICT (code) DO NOTHING""");
        // A host for the tenant, so a visitor request can name someone.
        if (count("SELECT count(*) FROM vms.hosts") == 0) {
            jdbc.update("""
                    INSERT INTO vms.hosts (id, tenant_id, full_name, email, is_active)
                    SELECT gen_random_uuid(), t.id, 'Alice Host', 'alice@acme.test', true
                    FROM vms.tenants t WHERE t.code = 'ACME'""");
        }
    }

    private void seedGrants() {
        for (String[] grant : DEV_GRANTS) {
            jdbc.update("""
                    INSERT INTO vms.role_permissions (role_id, permission_id)
                    SELECT r.id, p.id FROM vms.roles r JOIN vms.permissions p ON p.code = ?
                    WHERE r.code = ?
                    ON CONFLICT (role_id, permission_id) DO NOTHING""", grant[1], grant[0]);
        }
    }

    private void seedUsers() {
        String hash = passwordHasher.hash(DEV_PASSWORD.toCharArray());
        for (DevUser u : USERS) {
            if (count("SELECT count(*) FROM vms.users WHERE username = ?", u.username()) > 0) {
                continue;
            }
            jdbc.update("""
                    INSERT INTO vms.users (id, username, email, full_name, password_hash, role_id,
                                           tenant_id, reception_id, is_active)
                    SELECT gen_random_uuid(), ?, ?, ?, ?, r.id,
                           CASE WHEN ? THEN (SELECT id FROM vms.tenants WHERE code='ACME') END,
                           CASE WHEN ? THEN (SELECT id FROM vms.receptions WHERE code='RC01') END,
                           true
                    FROM vms.roles r WHERE r.code = ?""",
                    u.username(), u.username() + "@local.test", displayName(u.username()), hash,
                    u.withTenant(), u.withReception(), u.role());
        }
    }

    private void announce() {
        StringBuilder sb = new StringBuilder("""

                ────────────────────────────────────────────────────────────────
                 Local demo data ready — log in as any role
                   password for every account:  %s

                   username        role                  can do
                   ─────────────────────────────────────────────────────────────"""
                .formatted(DEV_PASSWORD));
        for (DevUser u : USERS) {
            sb.append("\n   %-15s %-21s %s".formatted(u.username(), u.role(), grantsFor(u.role())));
        }
        sb.append("""


                   POST /api/v1/auth/login  {"username":"...","password":"..."}
                   then send:  Authorization: Bearer <accessToken>
                ────────────────────────────────────────────────────────────────""");
        log.info(sb.toString());
    }

    private String grantsFor(String role) {
        List<String> codes = jdbc.query("""
                SELECT p.code FROM vms.role_permissions rp
                JOIN vms.roles r ON r.id = rp.role_id
                JOIN vms.permissions p ON p.id = rp.permission_id
                WHERE r.code = ? ORDER BY p.code""", (rs, i) -> rs.getString(1), role);
        return codes.isEmpty() ? "(no permissions)" : String.join(", ", codes);
    }

    private static String displayName(String username) {
        return Character.toUpperCase(username.charAt(0)) + username.substring(1);
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }
}
