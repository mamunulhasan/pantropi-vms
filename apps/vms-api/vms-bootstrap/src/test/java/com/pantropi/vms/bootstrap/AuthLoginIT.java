package com.pantropi.vms.bootstrap;

import com.pantropi.vms.application.identity.port.PasswordHasher;
import com.pantropi.vms.infrastructure.identity.Pbkdf2PasswordHasher;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * INTEGRATION TEST for US-02.1.1 — login and JWT session, full HTTP round trip against a real
 * PostgreSQL (Zonky embedded; no Docker). Identity is enabled via {@code vms.identity.enabled}.
 *
 * <p>Uses the JDK {@link HttpClient} rather than TestRestTemplate: the latter's underlying
 * connection cannot cleanly handle a 401 to a POST with a streamed body, which is exactly our
 * negative-path shape.
 *
 * <p>Covers a successful login issuing a verifiable token, the protected {@code /me} route, a
 * tampered token, and the three uniform-failure cases (wrong password, unknown user, inactive
 * account) that a username oracle must not distinguish (OWASP / use-case security posture).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "vms.identity.enabled=true",
                "vms.security.jwt.secret=integration-test-secret-least-32-bytes-long-xx",
                "vms.security.jwt.access-ttl-minutes=15",
                "spring.flyway.enabled=false"
        })
class AuthLoginIT {

    private static EmbeddedPostgres pg;
    private final HttpClient client = HttpClient.newHttpClient();

    @LocalServerPort int port;

    @BeforeAll
    static void start() throws Exception {
        pg = EmbeddedPostgres.start();
        seed(pg.getPostgresDatabase());
    }

    @AfterAll
    static void stop() throws Exception {
        if (pg != null) pg.close();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",
                () -> "jdbc:postgresql://localhost:" + pg.getPort() + "/postgres");
        r.add("spring.datasource.username", () -> "postgres");
        r.add("spring.datasource.password", () -> "postgres");
    }

    @TestConfiguration
    static class DataSourceConfig {
        @Bean
        DataSource dataSource() {
            return pg.getPostgresDatabase();
        }
    }

    @Test
    @DisplayName("valid credentials return a Bearer token that the protected /me accepts")
    void loginSucceedsAndTokenWorks() throws Exception {
        HttpResponse<String> login = login("alice", "correct-horse-battery-staple");
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(login.body()).contains("\"tokenType\":\"Bearer\"");

        String token = extract(login.body(), "accessToken");
        assertThat(token.split("\\.")).hasSize(3); // header.payload.signature

        HttpResponse<String> me = get("/api/v1/auth/me", token);
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(me.body()).contains("\"username\":\"alice\"");
        assertThat(me.body()).contains("\"role\":\"MASTER_ADMIN\"");
    }

    @Test
    @DisplayName("negative: /me without a token is 401")
    void meRequiresToken() throws Exception {
        assertThat(get("/api/v1/auth/me", null).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("negative: a tampered token is rejected 401")
    void tamperedTokenRejected() throws Exception {
        String token = extract(login("alice", "correct-horse-battery-staple").body(), "accessToken");
        String tampered = token.substring(0, token.length() - 2) + "xx";
        assertThat(get("/api/v1/auth/me", tampered).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("negative: wrong password, unknown user and inactive account all fail identically")
    void uniformFailure() throws Exception {
        assertThat(login("alice", "wrong").statusCode()).isEqualTo(401);
        assertThat(login("nobody", "whatever").statusCode()).isEqualTo(401);
        assertThat(login("bob-inactive", "correct-horse-battery-staple").statusCode()).isEqualTo(401);
    }

    // ---- helpers ----
    private HttpResponse<String> login(String u, String p) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base() + "/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"username\":\"" + u + "\",\"password\":\"" + p + "\"}"))
                .build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + path)).GET();
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

    private static void seed(DataSource ds) throws Exception {
        PasswordHasher hasher = new Pbkdf2PasswordHasher();
        String hash = hasher.hash("correct-horse-battery-staple".toCharArray());
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA IF NOT EXISTS vms");
            s.execute("""
                    CREATE TABLE vms.roles (
                        id uuid PRIMARY KEY, code text UNIQUE NOT NULL, name text NOT NULL)""");
            s.execute("""
                    CREATE TABLE vms.users (
                        id uuid PRIMARY KEY, username text UNIQUE NOT NULL, password_hash text NOT NULL,
                        role_id uuid NOT NULL REFERENCES vms.roles(id), is_active boolean NOT NULL)""");
            s.execute("""
                    CREATE TABLE vms.sessions (
                        id uuid PRIMARY KEY, user_id uuid NOT NULL, username text NOT NULL,
                        role_code text NOT NULL, refresh_token_hash text NOT NULL,
                        issued_at timestamptz NOT NULL DEFAULT now(), expires_at timestamptz NOT NULL,
                        revoked boolean NOT NULL DEFAULT false, revoked_reason text, revoked_at timestamptz)""");
            UUID roleId = UUID.randomUUID();
            s.execute("INSERT INTO vms.roles (id, code, name) VALUES ('" + roleId
                    + "', 'MASTER_ADMIN', 'Master Admin')");
            s.execute("INSERT INTO vms.users (id, username, password_hash, role_id, is_active) VALUES ('"
                    + UUID.randomUUID() + "', 'alice', '" + hash + "', '" + roleId + "', true)");
            s.execute("INSERT INTO vms.users (id, username, password_hash, role_id, is_active) VALUES ('"
                    + UUID.randomUUID() + "', 'bob-inactive', '" + hash + "', '" + roleId + "', false)");
        }
    }
}
