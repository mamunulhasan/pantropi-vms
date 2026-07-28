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
 * INTEGRATION TEST for US-04.5.1 — visitor types over real HTTP against real PostgreSQL.
 *
 * <p>The seeded-state assertion runs first ({@code @Order}) because later tests deactivate a seeded
 * type; without that, whether AC-1 passes would depend on the order JUnit happened to pick.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "vms.identity.enabled=true",
                "vms.masterdata.enabled=true",
                "vms.security.jwt.secret=integration-test-secret-least-32-bytes-long-xx",
                "spring.flyway.enabled=false"
        })
class VisitorTypeAdminIT {

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
    @DisplayName("AC-1: the four seeded types are present and active")
    void seededTypesArePresent() throws Exception {
        String body = get("/api/v1/admin/visitor-types?size=50", token()).body();

        assertThat(body).contains("GUEST", "CONTRACTOR", "VIP", "INTERVIEW");
        assertThat(scalar("SELECT count(*) FROM vms.visitor_types WHERE is_active")).isEqualTo("4");
    }

    @Test @Order(2)
    @DisplayName("AC-2: a new type is created and audited with an after state")
    void createIsAudited() throws Exception {
        HttpResponse<String> res = post("/api/v1/admin/visitor-types",
                body("delivery", "Delivery Courier", "Parcel and food delivery"), token());

        assertThat(res.statusCode()).isEqualTo(201);
        String id = extract(res.body(), "id");
        assertThat(scalar("SELECT code FROM vms.visitor_types WHERE id='" + id + "'"))
                .isEqualTo("DELIVERY");
        assertThat(scalar("SELECT after_state::text FROM vms.audit_logs "
                + "WHERE action='visitor_type.created' AND entity_id='" + id + "'"))
                .contains("Delivery Courier");
    }

    @Test @Order(3)
    @DisplayName("AC-4: a duplicate code is 409 naming the field")
    void duplicateCodeRefused() throws Exception {
        HttpResponse<String> res = post("/api/v1/admin/visitor-types",
                body("guest", "Guest Again", null), token());

        assertThat(res.statusCode()).isEqualTo(409);
        assertThat(res.body()).contains("code");
        assertThat(scalar("SELECT count(*) FROM vms.visitor_types WHERE code='GUEST'"))
                .isEqualTo("1");
    }

    @Test @Order(4)
    @DisplayName("AC-3: deactivating drops it from the active list, leaving existing rows valid")
    void deactivate() throws Exception {
        String admin = token();
        String id = extract(post("/api/v1/admin/visitor-types",
                body("temp", "Temporary", null), admin).body(), "id");

        assertThat(post("/api/v1/admin/visitor-types/" + id + "/deactivate", "", admin)
                .statusCode()).isEqualTo(204);

        assertThat(scalar("SELECT is_active FROM vms.visitor_types WHERE id='" + id + "'"))
                .isEqualTo("f");
        assertThat(get("/api/v1/admin/visitor-types?active=true&size=50", admin).body())
                .doesNotContain("TEMP");
        assertThat(get("/api/v1/admin/visitor-types?active=false&size=50", admin).body())
                .contains("TEMP");
    }

    @Test @Order(5)
    @DisplayName("AC-5: no delete route, even though the FK would allow it")
    void deleteIsNotOffered() throws Exception {
        String admin = token();
        String id = extract(post("/api/v1/admin/visitor-types",
                body("nodel", "No Delete", null), admin).body(), "id");

        // vms.visitors.visitor_type_id is ON DELETE SET NULL — the database would permit this and
        // quietly blank every visitor's classification. That is why the verb is absent.
        assertThat(delete("/api/v1/admin/visitor-types/" + id, admin).statusCode()).isIn(404, 405);
        assertThat(scalar("SELECT count(*) FROM vms.visitor_types WHERE id='" + id + "'"))
                .isEqualTo("1");
    }

    @Test @Order(6)
    @DisplayName("search matches code or name, and paging is bounded")
    void searchAndPaging() throws Exception {
        String admin = token();

        assertThat(get("/api/v1/admin/visitor-types?search=contract", admin).body())
                .contains("CONTRACTOR").doesNotContain("\"code\":\"VIP\"");
        // An unbounded size request is capped rather than honoured.
        assertThat(get("/api/v1/admin/visitor-types?size=100000", admin).body())
                .contains("\"size\":200");
    }

    @Test @Order(7)
    @DisplayName("negative: masterdata.view alone cannot create, and the denial is audited")
    void viewerCannotWrite() throws Exception {
        assertThat(post("/api/v1/admin/visitor-types", body("vwr", "Viewer", null),
                token("viewer")).statusCode()).isEqualTo(403);
        assertThat(scalar("SELECT count(*) FROM vms.audit_logs "
                + "WHERE action='authorization.denied'")).isNotEqualTo("0");
    }

    @Test @Order(8)
    @DisplayName("negative: an invalid code is 400 naming the field")
    void invalidCodeRefused() throws Exception {
        HttpResponse<String> res = post("/api/v1/admin/visitor-types",
                body("has space", "Bad", null), token());

        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body()).contains("code");
    }

    @Test @Order(9)
    @DisplayName("negative: unauthenticated is 401 and an unknown id is 404")
    void unauthenticatedAndUnknown() throws Exception {
        assertThat(get("/api/v1/admin/visitor-types", null).statusCode()).isEqualTo(401);
        assertThat(get("/api/v1/admin/visitor-types/" + UUID.randomUUID(), token()).statusCode())
                .isEqualTo(404);
    }

    // ---- helpers ----

    private static String body(String code, String name, String description) {
        return "{\"code\":\"" + code + "\",\"name\":\"" + name + "\""
                + (description == null ? "" : ",\"description\":\"" + description + "\"") + "}";
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
}
