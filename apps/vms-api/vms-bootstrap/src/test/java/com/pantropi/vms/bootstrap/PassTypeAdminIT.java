package com.pantropi.vms.bootstrap;

import com.pantropi.vms.infrastructure.identity.Pbkdf2PasswordHasher;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * INTEGRATION TEST for US-04.6.1 — pass types over real HTTP against real PostgreSQL.
 *
 * <p>Ordered: the seeded-state assertion runs first, and the enum-drift test runs <strong>last</strong>
 * because it permanently adds a value to {@code vms.credential_type} in this database. PostgreSQL
 * cannot remove an enum value, so that test cannot be undone and must not precede anything that
 * assumes the original two.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "vms.identity.enabled=true",
                "vms.masterdata.enabled=true",
                "vms.security.jwt.secret=integration-test-secret-least-32-bytes-long-xx",
                "spring.flyway.enabled=false"
        })
class PassTypeAdminIT {

    private static final String PASSWORD = "correct horse battery staple";
    private static EmbeddedPostgres pg;
    private final HttpClient client = HttpClient.newHttpClient();

    @LocalServerPort int port;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) throws Exception {
        pg = EmbeddedPostgres.start();
        Flyway.configure().dataSource(pg.getPostgresDatabase())
                .schemas("vms").defaultSchema("vms").cleanDisabled(true).load().migrate();
        seed();
        r.add("spring.datasource.url", () -> "jdbc:postgresql://localhost:" + pg.getPort() + "/postgres");
        r.add("spring.datasource.username", () -> "postgres");
        r.add("spring.datasource.password", () -> "postgres");
    }

    @AfterAll
    static void stop() throws Exception {
        if (pg != null) pg.close();
    }

    @TestConfiguration
    static class DataSourceConfig {
        @Bean DataSource dataSource() { return pg.getPostgresDatabase(); }
    }

    @Test @Order(1)
    @DisplayName("AC-1: the three seeded pass types round-trip with their defaults intact")
    void seededPassTypesRoundTrip() throws Exception {
        String body = get("/api/v1/admin/pass-types?size=50", token()).body();

        // T-04.6.1.2: every seeded row round-trips without value loss — asserted field by field,
        // because an enum silently mapping to the wrong constant would still produce a valid list.
        assertThat(body).contains(
                "\"code\":\"DAY_QR\"", "\"code\":\"ONE_TIME\"", "\"code\":\"CARD_DAY\"");
        assertThat(body).contains("\"defaultCredential\":\"rfid\"");   // CARD_DAY
        assertThat(body).contains("\"defaultRestriction\":\"one_time\"");   // ONE_TIME
        assertThat(body).contains("\"defaultValidHours\":4");
        assertThat(body).contains("\"defaultValidHours\":12");
    }

    @Test @Order(2)
    @DisplayName("AC-2/AC-4: create writes every field and audits the complete state")
    void createIsAuditedInFull() throws Exception {
        HttpResponse<String> res = post("/api/v1/admin/pass-types",
                body("week_card", "Week card", "rfid", "time_bound", 168), token());

        assertThat(res.statusCode()).isEqualTo(201);
        String id = extract(res.body(), "id");

        assertThat(scalar("SELECT default_credential::text FROM vms.pass_types WHERE id='" + id + "'"))
                .isEqualTo("rfid");
        assertThat(scalar("SELECT default_restriction::text FROM vms.pass_types WHERE id='" + id + "'"))
                .isEqualTo("time_bound");
        assertThat(scalar("SELECT default_valid_hours FROM vms.pass_types WHERE id='" + id + "'"))
                .isEqualTo("168");

        // AC-4: reconstructible after the fact means every field, not a summary.
        String after = scalar("SELECT after_state::text FROM vms.audit_logs "
                + "WHERE action='pass_type.created' AND entity_id='" + id + "'");
        assertThat(after).contains("rfid").contains("time_bound").contains("168")
                .contains("WEEK_CARD");
    }

    @Test @Order(3)
    @DisplayName("AC-4: an update audits both complete states")
    void updateAuditsBothStates() throws Exception {
        String admin = token();
        String id = extract(post("/api/v1/admin/pass-types",
                body("upd", "Before", "qr", "time_bound", 8), admin).body(), "id");

        assertThat(put("/api/v1/admin/pass-types/" + id,
                body("upd", "After", "rfid", "one_time", 2), admin).statusCode()).isEqualTo(204);

        assertThat(scalar("SELECT before_state::text FROM vms.audit_logs "
                + "WHERE action='pass_type.updated' AND entity_id='" + id + "'"))
                .contains("qr").contains("time_bound").contains("8");
        assertThat(scalar("SELECT after_state::text FROM vms.audit_logs "
                + "WHERE action='pass_type.updated' AND entity_id='" + id + "'"))
                .contains("rfid").contains("one_time").contains("2");
    }

    @Test @Order(4)
    @DisplayName("AC-3: an unpermitted enum value is 400 listing the permitted ones, before the DB")
    void unpermittedEnumRefused() throws Exception {
        String admin = token();

        HttpResponse<String> credential = post("/api/v1/admin/pass-types",
                body("bad1", "Bad", "nfc", "time_bound", 12), admin);
        assertThat(credential.statusCode()).isEqualTo(400);
        assertThat(credential.body()).contains("defaultCredential").contains("qr").contains("rfid");

        HttpResponse<String> restriction = post("/api/v1/admin/pass-types",
                body("bad2", "Bad", "qr", "forever", 12), admin);
        assertThat(restriction.statusCode()).isEqualTo(400);
        assertThat(restriction.body())
                .contains("defaultRestriction").contains("time_bound").contains("one_time");

        assertThat(scalar("SELECT count(*) FROM vms.pass_types WHERE code IN ('BAD1','BAD2')"))
                .isEqualTo("0");
    }

    @Test @Order(5)
    @DisplayName("AC-5: zero or negative validity is 400 naming the field, not a CHECK violation")
    void nonPositiveHoursRefused() throws Exception {
        String admin = token();

        for (int hours : new int[]{0, -5}) {
            HttpResponse<String> res = post("/api/v1/admin/pass-types",
                    body("zero", "Zero", "qr", "time_bound", hours), admin);
            assertThat(res.statusCode()).isEqualTo(400);
            assertThat(res.body()).contains("defaultValidHours");
        }
    }

    @Test @Order(6)
    @DisplayName("AC-6: deactivation succeeds and leaves the row intact for existing references")
    void deactivateKeepsTheRow() throws Exception {
        String admin = token();
        String id = extract(post("/api/v1/admin/pass-types",
                body("retire", "Retire me", "qr", "time_bound", 6), admin).body(), "id");

        assertThat(post("/api/v1/admin/pass-types/" + id + "/deactivate", "", admin).statusCode())
                .isEqualTo(204);
        assertThat(scalar("SELECT is_active FROM vms.pass_types WHERE id='" + id + "'"))
                .isEqualTo("f");
        assertThat(get("/api/v1/admin/pass-types?active=true&size=50", admin).body())
                .doesNotContain("RETIRE");
    }

    @Test @Order(7)
    @DisplayName("negative: duplicate code 409, viewer 403, no delete route, unauthenticated 401")
    void standardGuards() throws Exception {
        String admin = token();

        assertThat(post("/api/v1/admin/pass-types", body("day_qr", "Dup", "qr", "time_bound", 12),
                admin).statusCode()).isEqualTo(409);
        assertThat(post("/api/v1/admin/pass-types", body("vwr", "V", "qr", "time_bound", 12),
                token("viewer")).statusCode()).isEqualTo(403);

        String id = extract(post("/api/v1/admin/pass-types",
                body("nodel", "No delete", "qr", "time_bound", 6), admin).body(), "id");
        assertThat(delete("/api/v1/admin/pass-types/" + id, admin).statusCode()).isIn(404, 405);

        assertThat(get("/api/v1/admin/pass-types", null).statusCode()).isEqualTo(401);
    }

    @Test @Order(99)
    @DisplayName("T-04.6.1.2: a stored enum value this build does not know fails loudly on read")
    void enumDriftFailsLoudly() throws Exception {
        // Simulates the real hazard: a migration adds a value to the database enum and the code is
        // not taught about it. PostgreSQL cannot remove an enum value, so this runs last.
        execute("ALTER TYPE vms.credential_type ADD VALUE IF NOT EXISTS 'nfc'");
        execute("""
                INSERT INTO vms.pass_types
                    (code, name, default_credential, default_restriction, default_valid_hours)
                VALUES ('DRIFT', 'Drifted', 'nfc', 'time_bound', 12)""");

        HttpResponse<String> res = get("/api/v1/admin/pass-types?size=50", token());

        // A lenient mapper would have returned 200 with this row silently reading as 'qr'.
        assertThat(res.statusCode()).isEqualTo(500);
        // The detail belongs in the log, not the response.
        assertThat(res.body()).doesNotContain("nfc");
    }

    // ---- helpers ----

    private static String body(String code, String name, String credential, String restriction,
                               int hours) {
        return "{\"code\":\"" + code + "\",\"name\":\"" + name
                + "\",\"defaultCredential\":\"" + credential
                + "\",\"defaultRestriction\":\"" + restriction
                + "\",\"defaultValidHours\":" + hours + "}";
    }

    private String token() throws Exception {
        return token("mdadmin");
    }

    private String token(String username) throws Exception {
        String body = post("/api/v1/auth/login",
                "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}", null).body();
        int i = body.indexOf("\"accessToken\":\"") + 15;
        return body.substring(i, body.indexOf('"', i));
    }

    private HttpResponse<String> post(String path, String body, String bearer) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)), bearer);
    }

    private HttpResponse<String> put(String path, String body, String bearer) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base() + path))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)), bearer);
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base() + path)).GET(), bearer);
    }

    private HttpResponse<String> delete(String path, String bearer) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base() + path)).DELETE(), bearer);
    }

    private HttpResponse<String> send(HttpRequest.Builder b, String bearer) throws Exception {
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String base() { return "http://localhost:" + port; }

    private static String extract(String json, String key) {
        int i = json.indexOf("\"" + key + "\":\"") + key.length() + 4;
        return json.substring(i, json.indexOf('"', i));
    }

    private static void seed() throws Exception {
        String hash = new Pbkdf2PasswordHasher().hash(PASSWORD.toCharArray());
        try (Connection c = pg.getPostgresDatabase().getConnection();
             Statement s = c.createStatement()) {
            String sysRole = single(s, "SELECT id FROM vms.roles WHERE code='SYSTEM_ADMIN'");
            String fmRole = single(s, "SELECT id FROM vms.roles WHERE code='FM_ADMIN'");
            insertUser(s, "mdadmin", hash, sysRole);
            insertUser(s, "viewer", hash, fmRole);
            grant(s, "SYSTEM_ADMIN", "masterdata.view");
            grant(s, "SYSTEM_ADMIN", "masterdata.edit");
            grant(s, "FM_ADMIN", "masterdata.view");
        }
    }

    private static void grant(Statement s, String role, String permission) throws Exception {
        s.execute("""
                INSERT INTO vms.role_permissions (role_id, permission_id)
                SELECT r.id, p.id FROM vms.roles r JOIN vms.permissions p ON p.code = '%s'
                WHERE r.code = '%s' ON CONFLICT DO NOTHING"""
                .formatted(permission, role));
    }

    private static void insertUser(Statement s, String u, String hash, String roleId)
            throws Exception {
        s.execute("INSERT INTO vms.users (id, username, email, full_name, password_hash, role_id,"
                + " is_active) VALUES ('" + UUID.randomUUID() + "', '" + u + "', '" + u
                + "@ex.com', '" + u + "', '" + hash + "', '" + roleId + "', true)");
    }

    private static String single(Statement s, String sql) throws Exception {
        try (ResultSet rs = s.executeQuery(sql)) { rs.next(); return rs.getString(1); }
    }

    private static String scalar(String sql) throws Exception {
        try (Connection c = pg.getPostgresDatabase().getConnection();
             Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static void execute(String sql) throws Exception {
        try (Connection c = pg.getPostgresDatabase().getConnection();
             Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }
}
