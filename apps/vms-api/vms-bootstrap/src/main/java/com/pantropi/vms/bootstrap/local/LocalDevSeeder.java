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
 * <h2>No grants are applied here any more</h2>
 * This class used to hand out a development-only grant matrix, because no migration granted much of
 * anything and each role would otherwise have been unable to do anything observable. V9 replaced
 * that: the real role-to-permission matrix now ships as a migration (US-03.1.1, T-03.1.1.2).
 *
 * <p>Keeping the dev grants alongside it would be worse than redundant. Two sources of truth that
 * agree today drift tomorrow, and a developer exercising a role locally would be exercising
 * permissions the deployed system does not give it — which is precisely the bug this seeder exists
 * to help find.
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

    private final JdbcTemplate jdbc;
    private final PasswordHasher passwordHasher;

    public LocalDevSeeder(DataSource dataSource, PasswordHasher passwordHasher) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.passwordHasher = passwordHasher;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedMasterData();
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
