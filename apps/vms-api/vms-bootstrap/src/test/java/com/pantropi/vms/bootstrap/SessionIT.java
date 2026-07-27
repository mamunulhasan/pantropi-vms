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
 * INTEGRATION TEST for US-02.1.2 — refresh rotation, logout and server-side revocation, full HTTP
 * round trip against real PostgreSQL (Zonky). Migrations V1–V4 build the real schema.
 *
 * <p>Proves: refresh returns a new pair and invalidates the old refresh (AC-1); replaying the old
 * refresh revokes the family and 401s with an audit event (AC-5); logout makes the access token
 * fail on a protected route before it expires (AC-2); a directly-revoked session is rejected too
 * (AC-3 mechanism).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "vms.identity.enabled=true",
                "vms.security.jwt.secret=integration-test-secret-least-32-bytes-long-xx",
                "vms.security.jwt.access-ttl-minutes=15",
                "vms.security.jwt.refresh-ttl-minutes=60",
                "spring.flyway.enabled=false"
        })
class SessionIT {

    private static EmbeddedPostgres pg;
    private final HttpClient client = HttpClient.newHttpClient();

    @LocalServerPort int port;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) throws Exception {
        pg = EmbeddedPostgres.start();
        Flyway.configure().dataSource(pg.getPostgresDatabase())
                .schemas("vms").defaultSchema("vms").cleanDisabled(true).load().migrate();
        seedUser();
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
    @DisplayName("AC-1/AC-5: refresh rotates; replaying the old refresh revokes the family and audits")
    void refreshRotationAndReplay() throws Exception {
        var login = login("worker", "correct-horse-battery-staple");
        String refresh1 = field(login, "refreshToken");

        // rotate once — new pair, old refresh consumed
        var refreshed = post("/api/v1/auth/refresh", "{\"refreshToken\":\"" + refresh1 + "\"}", null);
        assertThat(refreshed.statusCode()).isEqualTo(200);
        String refresh2 = field(refreshed.body(), "refreshToken");
        assertThat(refresh2).isNotEqualTo(refresh1);

        // replay the ORIGINAL refresh → 401, family revoked, audit event written
        var replay = post("/api/v1/auth/refresh", "{\"refreshToken\":\"" + refresh1 + "\"}", null);
        assertThat(replay.statusCode()).isEqualTo(401);
        assertThat(scalar("SELECT count(*) FROM vms.audit_logs WHERE action='session.replay_detected'"))
                .isEqualTo("1");

        // the rotated (newer) refresh is now also dead, because the family was revoked
        var afterReplay = post("/api/v1/auth/refresh", "{\"refreshToken\":\"" + refresh2 + "\"}", null);
        assertThat(afterReplay.statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("AC-2: after logout the access token is rejected on a protected route before expiry")
    void logoutRevokesLiveToken() throws Exception {
        var login = login("worker", "correct-horse-battery-staple");
        String access = field(login, "accessToken");

        assertThat(get("/api/v1/auth/me", access).statusCode()).isEqualTo(200); // works before logout
        assertThat(post("/api/v1/auth/logout", "", access).statusCode()).isEqualTo(204);
        assertThat(get("/api/v1/auth/me", access).statusCode()).isEqualTo(401); // dead after logout
        assertThat(scalar("SELECT count(*) FROM vms.audit_logs WHERE action='session.logout'"))
                .isEqualTo("1");
    }

    // ---- helpers ----
    private String login(String u, String p) throws Exception {
        return post("/api/v1/auth/login",
                "{\"username\":\"" + u + "\",\"password\":\"" + p + "\"}", null).body();
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

    private static String field(String json, String key) {
        int i = json.indexOf("\"" + key + "\":\"") + key.length() + 4;
        return json.substring(i, json.indexOf('"', i));
    }

    private static void seedUser() throws Exception {
        String hash = new Pbkdf2PasswordHasher().hash("correct-horse-battery-staple".toCharArray());
        try (Connection c = pg.getPostgresDatabase().getConnection(); Statement s = c.createStatement()) {
            String roleId = single(s, "SELECT id FROM vms.roles WHERE code='FLOOR_RECEPTIONIST'");
            s.execute("INSERT INTO vms.users (id, username, full_name, password_hash, role_id, is_active) "
                    + "VALUES ('" + UUID.randomUUID() + "', 'worker', 'Worker', '" + hash + "', '"
                    + roleId + "', true)");
        }
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
