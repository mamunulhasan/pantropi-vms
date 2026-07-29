package com.pantropi.vms.bootstrap;

import com.pantropi.vms.domain.identity.Permissions;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * INTEGRATION TEST for US-03.1.1 — the role-permission matrix, asserted against the real migrated
 * database (T-03.1.1.2, AC-1, AC-4).
 *
 * <p>No Spring context: this is about what the migrations put in the tables, and starting an
 * application to find out would only add ways for the answer to be wrong.
 */
class RoleGrantMatrixIT {

    private static EmbeddedPostgres pg;
    private static DataSource ds;

    /**
     * The matrix as T-03.1.1.2 specifies it, plus the one documented addition.
     *
     * <p>Written out here rather than derived from the migration — a test that computes its
     * expectation the same way the code does cannot fail. This is the second, independent statement
     * of the intent, and the migration has to agree with it.
     */
    private static final Map<String, Set<String>> EXPECTED = expected();

    private static Map<String, Set<String>> expected() {
        Map<String, Set<String>> m = new LinkedHashMap<>();
        m.put("TENANT", Set.of(Permissions.VISITOR_REQUEST));
        m.put("FLOOR_RECEPTIONIST", Set.of(Permissions.VISITOR_REGISTER,
                Permissions.MASTERDATA_VIEW));
        m.put("FM_ADMIN", Set.of(Permissions.VISITOR_APPROVE));
        m.put("MASTER_ADMIN", Set.of(Permissions.VISITOR_APPROVE, Permissions.CREDENTIAL_ISSUE));
        // AUDIT_VIEW is US-07.4.3's, and SYSTEM_ADMIN is the only role that gets it: reading back
        // who decided what is oversight, not part of taking the decision.
        m.put("SYSTEM_ADMIN", Set.of(Permissions.USER_MANAGE, Permissions.MASTERDATA_EDIT,
                Permissions.SETTINGS_MANAGE, Permissions.MASTERDATA_VIEW, Permissions.AUDIT_VIEW));
        return m;
    }

    @BeforeAll
    static void migrate() throws Exception {
        pg = EmbeddedPostgres.start();
        ds = pg.getPostgresDatabase();
        Flyway.configure().dataSource(ds).schemas("vms").defaultSchema("vms")
                .cleanDisabled(true).load().migrate();
    }

    @AfterAll
    static void stop() throws Exception {
        if (pg != null) pg.close();
    }

    @Test
    @DisplayName("AC-1: exactly the five seeded roles and twelve seeded permissions exist")
    void seededRolesAndPermissions() throws Exception {
        assertThat(query("SELECT code FROM vms.roles")).containsExactlyInAnyOrder(
                "MASTER_ADMIN", "FLOOR_RECEPTIONIST", "FM_ADMIN", "TENANT", "SYSTEM_ADMIN");
        assertThat(query("SELECT code FROM vms.permissions")).hasSize(12);
    }

    @Test
    @DisplayName("AC-4: the curated constants and vms.permissions agree, in both directions")
    void constantsMatchTheDatabase() throws Exception {
        Set<String> stored = query("SELECT code FROM vms.permissions");

        // A constant with no row would guard a route no grant can ever satisfy — the route denies
        // everyone, silently, forever. A row with no constant is a capability nothing can reference.
        assertThat(Permissions.ALL).containsExactlyInAnyOrderElementsOf(stored);
    }

    @Test
    @DisplayName("T-03.1.1.2: the grant matrix is exactly as specified, role by role")
    void grantMatrixIsExact() throws Exception {
        Map<String, Set<String>> actual = actualMatrix();

        assertThat(actual.keySet()).containsExactlyInAnyOrderElementsOf(EXPECTED.keySet());
        EXPECTED.forEach((role, permissions) ->
                assertThat(actual.get(role))
                        .as("grants for %s", role)
                        .containsExactlyInAnyOrderElementsOf(permissions));
    }

    @Test
    @DisplayName("T-03.1.1.2: the unbacked permissions are granted to nobody")
    void unbackedPermissionsAreUngranted() throws Exception {
        // credential.override pending TODO-04, report.* pending TODO-16. MASTER_ADMIN's description
        // does mention overrides — this is the one place the matrix contradicts a role description
        // deliberately, because nobody has written the rules for when an override is legitimate.
        for (String code : Permissions.UNBACKED) {
            assertThat(scalar("""
                    SELECT count(*) FROM vms.role_permissions rp
                    JOIN vms.permissions p ON p.id = rp.permission_id
                    WHERE p.code = '%s'""".formatted(code)))
                    .as("grants of %s", code)
                    .isEqualTo("0");
        }
    }

    @Test
    @DisplayName("SYSTEM_ADMIN can read what it can write")
    void systemAdminCanReadWhatItWrites() throws Exception {
        // Documented addition to the matrix as written. Every master data read route is guarded by
        // masterdata.view and every write by masterdata.edit, so without this the role could create
        // a building and not list buildings.
        Set<String> systemAdmin = actualMatrix().get("SYSTEM_ADMIN");

        assertThat(systemAdmin).contains(Permissions.MASTERDATA_EDIT, Permissions.MASTERDATA_VIEW);
    }

    @Test
    @DisplayName("re-running the migration does not duplicate any grant")
    void migrationIsIdempotent() throws Exception {
        String before = scalar("SELECT count(*) FROM vms.role_permissions");

        Flyway.configure().dataSource(ds).schemas("vms").defaultSchema("vms")
                .cleanDisabled(true).load().migrate();
        // ...and applying V9's statement again by hand, which is what an operator replaying it does.
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute(new String(getClass()
                    .getResourceAsStream("/db/migration/V9__role_permission_grants.sql")
                    .readAllBytes()));
        }

        assertThat(scalar("SELECT count(*) FROM vms.role_permissions")).isEqualTo(before);
    }

    @Test
    @DisplayName("every grant references a permission the constants know")
    void noGrantOfAnUnknownPermission() throws Exception {
        // Catches a migration granting a code that was never added to Permissions — the grant would
        // work in the database and be unreachable from any @RequiresPermission.
        Set<String> granted = query("""
                SELECT DISTINCT p.code FROM vms.role_permissions rp
                JOIN vms.permissions p ON p.id = rp.permission_id""");

        assertThat(granted).allSatisfy(code ->
                assertThat(Permissions.isKnown(code)).as("%s is a known constant", code).isTrue());
    }

    // ---- helpers ----

    private static Map<String, Set<String>> actualMatrix() throws Exception {
        Map<String, Set<String>> matrix = new LinkedHashMap<>();
        for (String role : query("SELECT code FROM vms.roles")) {
            matrix.put(role, new LinkedHashSet<>());
        }
        try (Connection c = ds.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("""
                     SELECT r.code AS role_code, p.code AS permission_code
                     FROM vms.role_permissions rp
                     JOIN vms.roles r ON r.id = rp.role_id
                     JOIN vms.permissions p ON p.id = rp.permission_id""")) {
            while (rs.next()) {
                matrix.get(rs.getString("role_code")).add(rs.getString("permission_code"));
            }
        }
        return matrix;
    }

    private static Set<String> query(String sql) throws Exception {
        Set<String> out = new LinkedHashSet<>();
        try (Connection c = ds.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    private static String scalar(String sql) throws Exception {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
