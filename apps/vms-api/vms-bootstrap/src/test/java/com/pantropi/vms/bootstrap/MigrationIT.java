package com.pantropi.vms.bootstrap;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * INTEGRATION TESTS for US-01.5.1 — the schema baseline against a REAL PostgreSQL.
 *
 * <p>Runs on Zonky embedded-postgres (genuine PostgreSQL binaries, no Docker), because the
 * schema is unapologetically PostgreSQL: extensions, enum types, partial unique indexes,
 * trigger functions. H2 cannot verify any of that.
 *
 * <p>Counts asserted here come from the published DDL itself, not the backlog prose — the
 * task text says "fourteen enum types"; the DDL defines twelve. The DDL wins.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MigrationIT {

    private static EmbeddedPostgres pg;
    private static DataSource ds;

    private static final Set<String> EXPECTED_TABLES = Set.of(
            "buildings", "floors", "tenants", "receptions", "visitor_types", "pass_types",
            "holiday_calendar", "roles", "permissions", "role_permissions", "users", "hosts",
            "visitor_requests", "visitors", "credentials", "card_issuances", "access_events",
            "acs_requests", "acs_api_log", "notification_logs", "system_settings", "audit_logs",
            "sessions", "activation_tokens", "domain_events", "login_attempts");

    private static final Set<String> EXPECTED_ENUMS = Set.of(
            "request_status", "visitor_status", "visit_kind", "credential_type",
            "credential_state", "restriction_type", "access_direction", "notify_channel",
            "notify_type", "delivery_status", "acs_op", "acs_req_status");

    @BeforeAll
    static void startPostgres() throws Exception {
        pg = EmbeddedPostgres.start();
        ds = pg.getPostgresDatabase();
    }

    @AfterAll
    static void stopPostgres() throws Exception {
        if (pg != null) pg.close();
    }

    private static Flyway flyway() {
        return Flyway.configure()
                .dataSource(ds)
                .schemas("vms")
                .defaultSchema("vms")
                .cleanDisabled(true)
                .load();
    }

    @Test @Order(1)
    @DisplayName("AC-1: baseline creates every table, enum, trigger and comment; history records V1+V2")
    void baselineCreatesFullSchema() throws Exception {
        MigrateResult result = flyway().migrate();
        assertThat(result.migrationsExecuted).isEqualTo(14);

        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            assertThat(query(s, """
                    SELECT tablename FROM pg_tables WHERE schemaname='vms'
                      AND tablename <> 'flyway_schema_history'"""))
                    .containsExactlyInAnyOrderElementsOf(EXPECTED_TABLES);

            assertThat(query(s, """
                    SELECT t.typname FROM pg_type t
                    JOIN pg_namespace n ON n.oid=t.typnamespace
                    WHERE n.nspname='vms' AND t.typtype='e'"""))
                    .containsExactlyInAnyOrderElementsOf(EXPECTED_ENUMS);

            // the updated_at trigger loop, applied to 16 tables
            assertThat(query(s, """
                    SELECT proname FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace
                    WHERE n.nspname='vms'""")).contains("set_updated_at");
            assertThat(query(s, """
                    SELECT DISTINCT event_object_table FROM information_schema.triggers
                    WHERE trigger_schema='vms' AND trigger_name LIKE 'trg_%_updated'"""))
                    .hasSize(16);

            // V12's append-only guard is a trigger too, and it is not one of the sixteen. Asserted
            // by name rather than by growing the count above, which would have made the two rules
            // indistinguishable the next time either changed.
            assertThat(query(s, """
                    SELECT trigger_name FROM information_schema.triggers
                    WHERE trigger_schema='vms' AND event_object_table='audit_logs'"""))
                    .contains("trg_audit_logs_append_only");

            // comments carry requirement references — sample the load-bearing ones
            assertThat(scalar(s, """
                    SELECT obj_description('vms.credentials'::regclass)"""))
                    .contains("FR-VMS-07..16");
            assertThat(scalar(s, """
                    SELECT obj_description('vms.access_events'::regclass)"""))
                    .contains("idempotency");

            // partial unique index: one active credential per visitor
            assertThat(query(s, """
                    SELECT indexname FROM pg_indexes WHERE schemaname='vms'"""))
                    .contains("ux_credentials_active_per_visitor");

            // In any order: query() collects into a HashSet, so an ordered assertion here was
            // really asserting a hash order — it held for nine elements and stopped holding at
            // eleven. What the test is about is which migrations ran, not the sequence, which
            // Flyway's own version ordering already guarantees.
            assertThat(query(s, """
                    SELECT version FROM vms.flyway_schema_history
                     WHERE success AND version IS NOT NULL"""))
                    .containsExactlyInAnyOrder("1", "2", "3", "4", "5", "6", "7", "8", "9", "10",
                            "11", "12", "13", "14");
        }
    }

    @Test @Order(2)
    @DisplayName("AC-2: re-running migrations is a no-op")
    void rerunIsNoOp() {
        MigrateResult again = flyway().migrate();
        assertThat(again.migrationsExecuted).isZero();
    }

    @Test @Order(3)
    @DisplayName("AC-3: reference seed rows exist exactly once, even after a manual re-run")
    void seedIsIdempotent() throws Exception {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            assertSeedCounts(s);
            // brute-force manual re-run of V2 (simulates an operator replaying it)
            String v2 = new String(getClass().getResourceAsStream(
                    "/db/migration/V2__reference_seed.sql").readAllBytes());
            s.execute(v2);
            assertSeedCounts(s);
            // no user row, no password hash anywhere in migrations (T-01.5.1.3)
            assertThat(scalar(s, "SELECT count(*)::text FROM vms.users")).isEqualTo("0");
        }
    }

    private static void assertSeedCounts(Statement s) throws Exception {
        assertThat(scalar(s, "SELECT count(*)::text FROM vms.roles")).isEqualTo("5");
        assertThat(scalar(s, "SELECT count(*)::text FROM vms.permissions")).isEqualTo("12");
        assertThat(scalar(s, "SELECT count(*)::text FROM vms.visitor_types")).isEqualTo("4");
        assertThat(scalar(s, "SELECT count(*)::text FROM vms.pass_types")).isEqualTo("3");
        assertThat(scalar(s, "SELECT count(*)::text FROM vms.system_settings")).isEqualTo("5");
        // V9 states the whole matrix (US-03.1.1, T-03.1.1.2), completing the lift of D-15.
        // RoleGrantMatrixIT asserts it role by role; this only pins the total so a stray grant
        // added elsewhere is noticed here too.
        // Ten from V9's matrix, plus V12's audit.view grant to SYSTEM_ADMIN.
        assertThat(scalar(s, "SELECT count(*)::text FROM vms.role_permissions")).isEqualTo("11");
        assertThat(query(s, """
                SELECT p.code FROM vms.role_permissions rp
                JOIN vms.roles r ON r.id = rp.role_id AND r.code = 'MASTER_ADMIN'
                JOIN vms.permissions p ON p.id = rp.permission_id"""))
                .containsExactlyInAnyOrder("visitor.approve", "credential.issue");
    }

    @Test @Order(4)
    @DisplayName("AC-4: a failing migration aborts naming its version and the SQL error")
    void failingMigrationNamesVersionAndError(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("V1__broken.sql"), "CREATE TABLE vms_broken (id uuid PRIMARY KEY);\nSELECT * FROM does_not_exist;");
        Flyway broken = Flyway.configure()
                .dataSource(ds).schemas("brokenschema").defaultSchema("brokenschema")
                .locations("filesystem:" + dir).cleanDisabled(true).load();
        assertThatThrownBy(broken::migrate)
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("V1__broken.sql")
                .hasMessageContaining("does_not_exist");
    }

    @Test @Order(5)
    @DisplayName("AC-5: editing an applied migration fails checksum validation without partial effects")
    void tamperedMigrationFailsValidation(@TempDir Path dir) throws Exception {
        Path m1 = dir.resolve("V1__one.sql");
        Files.writeString(m1, "CREATE TABLE t_one (id int PRIMARY KEY);");
        Flyway fw = Flyway.configure()
                .dataSource(ds).schemas("tamperschema").defaultSchema("tamperschema")
                .locations("filesystem:" + dir).cleanDisabled(true).load();
        fw.migrate();

        // tamper the applied file, add a second migration that must NOT apply
        Files.writeString(m1, "CREATE TABLE t_one (id bigint PRIMARY KEY);");
        Files.writeString(dir.resolve("V2__two.sql"), "CREATE TABLE t_two (id int PRIMARY KEY);");

        assertThatThrownBy(fw::migrate)
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("checksum");

        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            assertThat(query(s, """
                    SELECT tablename FROM pg_tables WHERE schemaname='tamperschema'"""))
                    .doesNotContain("t_two"); // nothing partially applied
        }
    }

    // ---- helpers ----
    private static Set<String> query(Statement s, String sql) throws Exception {
        Set<String> out = new HashSet<>();
        try (ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    private static String scalar(Statement s, String sql) throws Exception {
        try (ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
