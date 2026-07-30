package com.pantropi.vms.bootstrap;

import com.pantropi.vms.infrastructure.identity.Pbkdf2PasswordHasher;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 * INTEGRATION TESTS for US-10.1.1 — the host directory over real HTTP against real PostgreSQL.
 *
 * <p>The cross-tenant case (AC-4) is the reason this file exists. It cannot be established with
 * fakes: the refusal lives in the SQL predicate, so only a database with two tenants' hosts in it
 * can show that one tenant's session sees exactly its own.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "vms.identity.enabled=true",
                "vms.security.jwt.secret=integration-test-secret-least-32-bytes-long-xx",
                "spring.flyway.enabled=false"
        })
class HostDirectoryIT {

    private static EmbeddedPostgres pg;
    private static UUID tenantA;
    private static UUID tenantB;
    private static UUID hostOfB;

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

    @Test
    @DisplayName("AC-1: the host is filed against the caller's own tenant, not one they name")
    void tenantComesFromTheCaller() throws Exception {
        String token = accessToken("tenant-a", "tenant-password-1234");

        var created = post("/api/v1/hosts",
                "{\"fullName\":\"Jia Tan\",\"email\":\"J.Tan@ACME.test\",\"phone\":\"+880 1712-345678\"}",
                token);
        assertThat(created.statusCode()).isEqualTo(201);
        String id = extract(created.body(), "id");

        // Filed against tenant A because that is who asked — nothing in the body said so.
        assertThat(scalar("SELECT tenant_id FROM vms.hosts WHERE id='" + id + "'"))
                .isEqualTo(tenantA.toString());
        // Normalised once, on the way in: citext lower-cases the email, the phone loses punctuation.
        assertThat(scalar("SELECT email FROM vms.hosts WHERE id='" + id + "'"))
                .isEqualTo("j.tan@acme.test");
        assertThat(scalar("SELECT phone FROM vms.hosts WHERE id='" + id + "'"))
                .isEqualTo("+8801712345678");
        assertThat(scalar("SELECT is_active FROM vms.hosts WHERE id='" + id + "'")).isEqualTo("t");
    }

    @Test
    @DisplayName("AC-2: the list shows my tenant's hosts only, active and inactive both")
    void listIsScopedAndShowsBothStates() throws Exception {
        String token = accessToken("tenant-a", "tenant-password-1234");
        post("/api/v1/hosts", "{\"fullName\":\"Active Person\"}", token);
        String inactive = extract(
                post("/api/v1/hosts", "{\"fullName\":\"Departed Person\"}", token).body(), "id");
        assertThat(post("/api/v1/hosts/" + inactive + "/deactivate", "", token).statusCode())
                .isEqualTo(204);

        var listed = get("/api/v1/hosts", token);
        assertThat(listed.statusCode()).isEqualTo(200);
        assertThat(listed.body()).contains("Active Person").contains("Departed Person");
        // Tenant B's host is in the same table and must not appear.
        assertThat(listed.body()).doesNotContain("Beatrice Bee");
        assertThat(listed.body()).contains("\"active\":false");
    }

    @Test
    @DisplayName("AC-4: another tenant's host is 404 on read, update and deactivate alike")
    void crossTenantIsRefusedIdentically() throws Exception {
        String token = accessToken("tenant-a", "tenant-password-1234");

        var read = get("/api/v1/hosts/" + hostOfB, token);
        var patched = patch("/api/v1/hosts/" + hostOfB, "{\"fullName\":\"Hijacked\"}", token);
        var deactivated = post("/api/v1/hosts/" + hostOfB + "/deactivate", "", token);

        assertThat(read.statusCode()).isEqualTo(404);
        assertThat(patched.statusCode()).isEqualTo(404);
        assertThat(deactivated.statusCode()).isEqualTo(404);
        // Byte-identical to an id that never existed: nothing distinguishes "yours" from "exists".
        assertThat(get("/api/v1/hosts/" + UUID.randomUUID(), token).body()).isEqualTo(read.body());
        // And nothing was written.
        assertThat(scalar("SELECT full_name FROM vms.hosts WHERE id='" + hostOfB + "'"))
                .isEqualTo("Beatrice Bee");
        assertThat(scalar("SELECT is_active FROM vms.hosts WHERE id='" + hostOfB + "'"))
                .isEqualTo("t");
    }

    @Test
    @DisplayName("AC-3: deactivating keeps the row, so requests naming the host stay readable")
    void deactivationPreservesTheRecord() throws Exception {
        String token = accessToken("tenant-a", "tenant-password-1234");
        String id = extract(post("/api/v1/hosts", "{\"fullName\":\"Leaver\"}", token).body(), "id");

        assertThat(post("/api/v1/hosts/" + id + "/deactivate", "", token).statusCode()).isEqualTo(204);

        assertThat(scalar("SELECT count(*) FROM vms.hosts WHERE id='" + id + "'")).isEqualTo("1");
        assertThat(scalar("SELECT is_active FROM vms.hosts WHERE id='" + id + "'")).isEqualTo("f");
        assertThat(scalar("SELECT count(*) FROM vms.audit_logs WHERE action='host.deactivated'"))
                .isNotEqualTo("0");
    }

    @Test
    @DisplayName("AC-5: there is no delete route at all — the method is not allowed")
    void deletionIsStructurallyImpossible() throws Exception {
        String token = accessToken("tenant-a", "tenant-password-1234");
        String id = extract(post("/api/v1/hosts", "{\"fullName\":\"Permanent\"}", token).body(), "id");

        var attempted = delete("/api/v1/hosts/" + id, token);

        assertThat(attempted.statusCode()).isEqualTo(405);
        assertThat(scalar("SELECT count(*) FROM vms.hosts WHERE id='" + id + "'")).isEqualTo("1");
    }

    @Test
    @DisplayName("AC-6: a role without visitor.request is refused, and the denial is audited")
    void withoutPermissionIsForbidden() throws Exception {
        String admin = accessToken("sysadmin-h", "admin-password-1234");
        String before = scalar("SELECT count(*) FROM vms.audit_logs WHERE action='authorization.denied'");

        var res = post("/api/v1/hosts", "{\"fullName\":\"Sneaky\"}", admin);

        assertThat(res.statusCode()).isEqualTo(403);
        assertThat(scalar("SELECT count(*) FROM vms.hosts WHERE full_name='Sneaky'")).isEqualTo("0");
        assertThat(scalar("SELECT count(*) FROM vms.audit_logs WHERE action='authorization.denied'"))
                .isNotEqualTo(before);
    }

    @Test
    @DisplayName("the audit trail records that contact details exist, never what they are")
    void auditDoesNotCarryContactDetails() throws Exception {
        String token = accessToken("tenant-a", "tenant-password-1234");
        post("/api/v1/hosts",
                "{\"fullName\":\"Audited Person\",\"email\":\"secret@acme.test\","
                        + "\"phone\":\"+8801799999999\"}", token);

        assertThat(scalar("SELECT count(*) FROM vms.audit_logs WHERE after_state::text LIKE "
                + "'%secret@acme.test%' OR after_state::text LIKE '%1799999999%'")).isEqualTo("0");
        assertThat(scalar("""
                SELECT after_state::text FROM vms.audit_logs
                WHERE action='host.created' ORDER BY created_at DESC LIMIT 1"""))
                .contains("\"hasEmail\": true");
    }

    @Test
    @DisplayName("an invalid email names the field and never echoes the value")
    void invalidEmailNamesTheField() throws Exception {
        String token = accessToken("tenant-a", "tenant-password-1234");

        var res = post("/api/v1/hosts",
                "{\"fullName\":\"Bad Contact\",\"email\":\"not-an-email\"}", token);

        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body()).contains("visitor email").doesNotContain("not-an-email");
    }

    // ---- helpers ----

    private String accessToken(String user, String password) throws Exception {
        return extract(post("/api/v1/auth/login",
                "{\"username\":\"" + user + "\",\"password\":\"" + password + "\"}", null).body(),
                "accessToken");
    }

    private HttpResponse<String> post(String path, String body, String bearer) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)), bearer);
    }

    private HttpResponse<String> patch(String path, String body, String bearer) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(base() + path))
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body)), bearer);
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

    private String base() {
        return "http://localhost:" + port;
    }

    private static String extract(String json, String key) {
        int i = json.indexOf("\"" + key + "\":\"") + key.length() + 4;
        return json.substring(i, json.indexOf('"', i));
    }

    private static String scalar(String sql) throws Exception {
        try (Connection c = pg.getPostgresDatabase().getConnection();
             Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static void seed() throws Exception {
        var hasher = new Pbkdf2PasswordHasher();
        String tenantHash = hasher.hash("tenant-password-1234".toCharArray());
        String adminHash = hasher.hash("admin-password-1234".toCharArray());

        try (Connection c = pg.getPostgresDatabase().getConnection(); Statement s = c.createStatement()) {
            String building = single(s, "INSERT INTO vms.buildings (code, name) "
                    + "VALUES ('HST','Host Tower') RETURNING id");
            String floor = single(s, "INSERT INTO vms.floors (building_id, code, name) "
                    + "VALUES ('" + building + "','L1','Level 1') RETURNING id");
            tenantA = UUID.fromString(single(s, "INSERT INTO vms.tenants (floor_id, code, name) "
                    + "VALUES ('" + floor + "','TA','Tenant A') RETURNING id"));
            tenantB = UUID.fromString(single(s, "INSERT INTO vms.tenants (floor_id, code, name) "
                    + "VALUES ('" + floor + "','TB','Tenant B') RETURNING id"));

            // A host belonging to the *other* tenant — the subject of the AC-4 assertions.
            hostOfB = UUID.fromString(single(s, "INSERT INTO vms.hosts (tenant_id, full_name) "
                    + "VALUES ('" + tenantB + "','Beatrice Bee') RETURNING id"));

            String tenantRole = single(s, "SELECT id FROM vms.roles WHERE code='TENANT'");
            String adminRole = single(s, "SELECT id FROM vms.roles WHERE code='SYSTEM_ADMIN'");
            s.execute("INSERT INTO vms.users (username, email, full_name, password_hash, role_id, "
                    + "tenant_id, is_active) VALUES ('tenant-a','ta@ex.com','Tenant A User','"
                    + tenantHash + "','" + tenantRole + "','" + tenantA + "', true)");
            s.execute("INSERT INTO vms.users (username, email, full_name, password_hash, role_id, "
                    + "is_active) VALUES ('sysadmin-h','sa@ex.com','Sys Admin','"
                    + adminHash + "','" + adminRole + "', true)");
        }
    }

    private static String single(Statement s, String sql) throws Exception {
        try (ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
