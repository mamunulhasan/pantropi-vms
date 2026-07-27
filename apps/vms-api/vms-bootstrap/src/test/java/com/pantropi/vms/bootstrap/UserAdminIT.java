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
 * INTEGRATION TEST for US-02.2.1 — user administration over real HTTP against real PostgreSQL.
 *
 * <p>Two seeded users: a SYSTEM_ADMIN (holds {@code user.manage} via V5) and a plain FLOOR
 * receptionist (does not). Proves: create → list (AC-1/AC-4), duplicate username conflict (AC-5),
 * deactivate revokes the target's live sessions (AC-3), and the non-admin gets 403 with an
 * authorization-denial audit event (AC-6).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "vms.identity.enabled=true",
                "vms.security.jwt.secret=integration-test-secret-least-32-bytes-long-xx",
                "spring.flyway.enabled=false"
        })
class UserAdminIT {

    private static EmbeddedPostgres pg;
    private final HttpClient client = HttpClient.newHttpClient();

    @LocalServerPort int port;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) throws Exception {
        pg = EmbeddedPostgres.start();
        Flyway.configure().dataSource(pg.getPostgresDatabase())
                .schemas("vms").defaultSchema("vms").cleanDisabled(true).load().migrate();
        seedUsers();
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
    @DisplayName("AC-1/AC-4: admin creates a user, then it appears in the paginated list")
    void createAndList() throws Exception {
        String admin = accessToken("admin", "admin-password-1234");

        var created = post("/api/v1/admin/users",
                "{\"username\":\"newbie\",\"email\":\"n@ex.com\",\"fullName\":\"New Bie\","
                        + "\"roleCode\":\"FLOOR_RECEPTIONIST\"}", admin);
        assertThat(created.statusCode()).isEqualTo(201);

        var list = get("/api/v1/admin/users?size=50", admin);
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.body()).contains("\"username\":\"newbie\"");
        // the placeholder hash never leaves the store
        assertThat(list.body()).doesNotContain("password").doesNotContain("activation-pending");
    }

    @Test
    @DisplayName("AC-5: creating a duplicate username returns 409 naming the field")
    void duplicateUsername() throws Exception {
        String admin = accessToken("admin", "admin-password-1234");
        var dup = post("/api/v1/admin/users",
                "{\"username\":\"admin\",\"email\":\"x@ex.com\",\"fullName\":\"X\","
                        + "\"roleCode\":\"FLOOR_RECEPTIONIST\"}", admin);
        assertThat(dup.statusCode()).isEqualTo(409);
        assertThat(dup.body()).contains("username");
    }

    @Test
    @DisplayName("AC-3: deactivating a user revokes their live session")
    void deactivateRevokesSessions() throws Exception {
        String admin = accessToken("admin", "admin-password-1234");
        // the receptionist logs in — a live session exists
        String workerAccess = accessToken("worker", "worker-password-1234");
        assertThat(get("/api/v1/auth/me", workerAccess).statusCode()).isEqualTo(200);

        String workerId = scalar("SELECT id FROM vms.users WHERE username='worker'");
        var deact = post("/api/v1/admin/users/" + workerId + "/deactivate", "", admin);
        assertThat(deact.statusCode()).isEqualTo(204);

        // the worker's previously-valid access token is now dead
        assertThat(get("/api/v1/auth/me", workerAccess).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("AC-6: a user without user.manage gets 403 and an audit event is written")
    void nonAdminForbidden() throws Exception {
        String worker = accessToken("worker2", "worker-password-1234");
        var res = post("/api/v1/admin/users",
                "{\"username\":\"z\",\"email\":\"z@ex.com\",\"fullName\":\"Z\","
                        + "\"roleCode\":\"FLOOR_RECEPTIONIST\"}", worker);
        assertThat(res.statusCode()).isEqualTo(403);
        assertThat(scalar("SELECT count(*) FROM vms.audit_logs WHERE action='authorization.denied'"))
                .isNotEqualTo("0");
    }

    // ---- helpers ----
    private String accessToken(String u, String p) throws Exception {
        String body = post("/api/v1/auth/login",
                "{\"username\":\"" + u + "\",\"password\":\"" + p + "\"}", null).body();
        int i = body.indexOf("\"accessToken\":\"") + 15;
        return body.substring(i, body.indexOf('"', i));
    }

    private HttpResponse<String> post(String path, String body, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + path)).GET();
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String base() { return "http://localhost:" + port; }

    private static void seedUsers() throws Exception {
        var hasher = new Pbkdf2PasswordHasher();
        String adminHash = hasher.hash("admin-password-1234".toCharArray());
        String workerHash = hasher.hash("worker-password-1234".toCharArray());
        try (Connection c = pg.getPostgresDatabase().getConnection(); Statement s = c.createStatement()) {
            String sysRole = single(s, "SELECT id FROM vms.roles WHERE code='SYSTEM_ADMIN'");
            String floorRole = single(s, "SELECT id FROM vms.roles WHERE code='FLOOR_RECEPTIONIST'");
            insert(s, "admin", "admin@ex.com", adminHash, sysRole);
            insert(s, "worker", "worker@ex.com", workerHash, floorRole);
            insert(s, "worker2", "worker2@ex.com", workerHash, floorRole);
        }
    }

    private static void insert(Statement s, String u, String e, String hash, String roleId) throws Exception {
        s.execute("INSERT INTO vms.users (id, username, email, full_name, password_hash, role_id, is_active) "
                + "VALUES ('" + UUID.randomUUID() + "', '" + u + "', '" + e + "', '" + u + "', '"
                + hash + "', '" + roleId + "', true)");
    }

    private static String single(Statement s, String sql) throws Exception {
        try (ResultSet rs = s.executeQuery(sql)) { rs.next(); return rs.getString(1); }
    }

    private static String scalar(String sql) throws Exception {
        try (Connection c = pg.getPostgresDatabase().getConnection();
             Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next(); return rs.getString(1);
        }
    }
}
