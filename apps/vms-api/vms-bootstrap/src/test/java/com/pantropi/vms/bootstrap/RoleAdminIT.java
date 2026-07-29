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
 * INTEGRATION TEST for US-03.3.1 — role and grant administration over real HTTP against real
 * PostgreSQL.
 *
 * <p>Each test edits a different role where it can, so the shared database does not couple them.
 * The two that must touch {@code SYSTEM_ADMIN} restore it before returning.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "vms.identity.enabled=true",
                "vms.masterdata.enabled=true",
                "vms.security.jwt.secret=integration-test-secret-least-32-bytes-long-xx",
                "spring.flyway.enabled=false"
        })
class RoleAdminIT {

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

    @Test
    @DisplayName("AC-1: the overview carries roles, grants, versions, user counts and the catalogue")
    void overview() throws Exception {
        HttpResponse<String> res = get("/api/v1/admin/roles", token());

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body())
                .contains("MASTER_ADMIN", "FLOOR_RECEPTIONIST", "FM_ADMIN", "TENANT", "SYSTEM_ADMIN")
                .contains("\"version\":")
                .contains("\"activeUsers\":")
                .contains("\"catalogue\":")
                .contains("credential.override");   // in the catalogue even though granted to nobody
    }

    @Test
    @DisplayName("AC-2: a grant change is applied and audited with both sets")
    void grantChangeIsAudited() throws Exception {
        String admin = token();
        String version = versionOf("FM_ADMIN", admin);

        HttpResponse<String> res = put("/api/v1/admin/roles/FM_ADMIN/grants",
                "{\"version\":\"" + version + "\",\"permissions\":[\"visitor.approve\","
                        + "\"masterdata.view\"]}", admin);

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(scalar("""
                SELECT count(*) FROM vms.role_permissions rp
                JOIN vms.roles r ON r.id = rp.role_id AND r.code = 'FM_ADMIN'""")).isEqualTo("2");
        assertThat(scalar("SELECT before_state::text FROM vms.audit_logs "
                + "WHERE action='role.grants_changed' AND entity_id='FM_ADMIN'"))
                .contains("visitor.approve").doesNotContain("masterdata.view");
        assertThat(scalar("SELECT after_state::text FROM vms.audit_logs "
                + "WHERE action='role.grants_changed' AND entity_id='FM_ADMIN'"))
                .contains("masterdata.view");
    }

    @Test
    @DisplayName("AC-3: a live session picks up new grants on its next request, without re-login")
    void grantsApplyToALiveSessionImmediately() throws Exception {
        String admin = token();

        // The receptionist logs in now, and keeps this token throughout.
        String receptionist = token("receptionist");
        assertThat(get("/api/v1/admin/settings", receptionist).statusCode()).isEqualTo(200);

        // Revoke masterdata.view from their role while they are logged in.
        String version = versionOf("FLOOR_RECEPTIONIST", admin);
        put("/api/v1/admin/roles/FLOOR_RECEPTIONIST/grants",
                "{\"version\":\"" + version + "\",\"permissions\":[\"visitor.register\"]}", admin);

        // Same token, no re-login: the very next request is decided against the new grants.
        assertThat(get("/api/v1/admin/settings", receptionist).statusCode()).isEqualTo(403);

        // ...and granting it back takes effect just as immediately.
        put("/api/v1/admin/roles/FLOOR_RECEPTIONIST/grants",
                "{\"version\":\"" + versionOf("FLOOR_RECEPTIONIST", admin)
                        + "\",\"permissions\":[\"visitor.register\",\"masterdata.view\"]}", admin);
        assertThat(get("/api/v1/admin/settings", receptionist).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("AC-4: revoking the last user.manage is refused and changes nothing")
    void administrativeLockoutRefused() throws Exception {
        String admin = token();
        String version = versionOf("SYSTEM_ADMIN", admin);

        HttpResponse<String> res = put("/api/v1/admin/roles/SYSTEM_ADMIN/grants",
                "{\"version\":\"" + version + "\",\"permissions\":[\"masterdata.view\"]}", admin);

        assertThat(res.statusCode()).isEqualTo(409);
        assertThat(res.body()).contains("administrative_lockout");
        // The door is still open — including the ability to undo whatever led here.
        assertThat(scalar("""
                SELECT count(*) FROM vms.role_permissions rp
                JOIN vms.roles r ON r.id = rp.role_id AND r.code = 'SYSTEM_ADMIN'
                JOIN vms.permissions p ON p.id = rp.permission_id AND p.code = 'user.manage'"""))
                .isEqualTo("1");
    }

    @Test
    @DisplayName("AC-5: a stale version is 409 carrying the current state, and writes nothing")
    void staleVersionConflict() throws Exception {
        String admin = token();
        String stale = versionOf("TENANT", admin);

        // A first administrator saves.
        assertThat(put("/api/v1/admin/roles/TENANT/grants",
                "{\"version\":\"" + stale + "\",\"permissions\":[\"visitor.request\","
                        + "\"masterdata.view\"]}", admin).statusCode()).isEqualTo(200);

        // A second, still holding the token from before that save.
        HttpResponse<String> res = put("/api/v1/admin/roles/TENANT/grants",
                "{\"version\":\"" + stale + "\",\"permissions\":[]}", admin);

        assertThat(res.statusCode()).isEqualTo(409);
        assertThat(res.body()).contains("stale_version")
                .contains("currentPermissions").contains("masterdata.view");
        // The first administrator's change stands; the second overwrote nothing.
        assertThat(scalar("""
                SELECT count(*) FROM vms.role_permissions rp
                JOIN vms.roles r ON r.id = rp.role_id AND r.code = 'TENANT'""")).isEqualTo("2");
    }

    @Test
    @DisplayName("an unbacked permission cannot be granted through the API")
    void unbackedPermissionRefused() throws Exception {
        String admin = token();

        HttpResponse<String> res = put("/api/v1/admin/roles/FM_ADMIN/grants",
                "{\"version\":\"" + versionOf("FM_ADMIN", admin)
                        + "\",\"permissions\":[\"credential.override\"]}", admin);

        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body()).contains("credential.override").contains("TODO-04");
        assertThat(scalar("""
                SELECT count(*) FROM vms.role_permissions rp
                JOIN vms.permissions p ON p.id = rp.permission_id
                WHERE p.code = 'credential.override'""")).isEqualTo("0");
    }

    @Test
    @DisplayName("an unknown permission code is 400, not a silently dropped entry")
    void unknownPermissionRefused() throws Exception {
        String admin = token();

        assertThat(put("/api/v1/admin/roles/FM_ADMIN/grants",
                "{\"version\":\"" + versionOf("FM_ADMIN", admin)
                        + "\",\"permissions\":[\"visitor.aprove\"]}", admin).statusCode())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("AC-6: without user.manage every role route is 403, and the denial is audited")
    void requiresUserManage() throws Exception {
        String receptionist = token("receptionist");

        assertThat(get("/api/v1/admin/roles", receptionist).statusCode()).isEqualTo(403);
        assertThat(get("/api/v1/admin/roles/TENANT", receptionist).statusCode()).isEqualTo(403);
        assertThat(put("/api/v1/admin/roles/TENANT/grants", "{\"version\":\"x\"}", receptionist)
                .statusCode()).isEqualTo(403);
        assertThat(scalar("SELECT count(*) FROM vms.audit_logs "
                + "WHERE action='authorization.denied'")).isNotEqualTo("0");
    }

    @Test
    @DisplayName("negative: unauthenticated is 401 and an unknown role is 404")
    void unauthenticatedAndUnknownRole() throws Exception {
        assertThat(get("/api/v1/admin/roles", null).statusCode()).isEqualTo(401);
        assertThat(get("/api/v1/admin/roles/INVENTED", token()).statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("there is no route to create a role or a permission")
    void creationIsNotOffered() throws Exception {
        String admin = token();

        // TODO-01 leaves FR-USR-02 undefined; the five roles and eleven permissions are the
        // published schema's, and inventing more would be inventing requirements.
        assertThat(post("/api/v1/admin/roles", "{\"code\":\"NEW_ROLE\"}", admin).statusCode())
                .isIn(404, 405);
        assertThat(post("/api/v1/admin/permissions", "{\"code\":\"new.permission\"}", admin)
                .statusCode()).isIn(401, 403, 404, 405);
        assertThat(scalar("SELECT count(*) FROM vms.roles")).isEqualTo("5");
        assertThat(scalar("SELECT count(*) FROM vms.permissions")).isEqualTo("12");
    }

    // ---- helpers ----

    private String versionOf(String roleCode, String bearer) throws Exception {
        String body = get("/api/v1/admin/roles/" + roleCode, bearer).body();
        return extract(body, "version");
    }

    private String token() throws Exception {
        return token("sysadmin");
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

    private HttpResponse<String> send(HttpRequest.Builder b, String bearer) throws Exception {
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String base() { return "http://localhost:" + port; }

    private static String extract(String json, String key) {
        int i = json.indexOf("\"" + key + "\":\"") + key.length() + 4;
        return json.substring(i, json.indexOf('"', i));
    }

    /** Users only — the grants come from V9, which is the point of the previous story. */
    private static void seed() throws Exception {
        String hash = new Pbkdf2PasswordHasher().hash(PASSWORD.toCharArray());
        try (Connection c = pg.getPostgresDatabase().getConnection();
             Statement s = c.createStatement()) {
            insertUser(s, "sysadmin", hash,
                    single(s, "SELECT id FROM vms.roles WHERE code='SYSTEM_ADMIN'"));
            insertUser(s, "receptionist", hash,
                    single(s, "SELECT id FROM vms.roles WHERE code='FLOOR_RECEPTIONIST'"));
        }
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
}
