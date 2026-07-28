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
 * INTEGRATION TEST for US-04.2.1 — floors over real HTTP against real PostgreSQL (Zonky embedded).
 *
 * <p>The pattern's first child entity, so the interesting cases are the ones buildings could not
 * exercise: composite uniqueness, the active-parent rule, and ordering by an optional column.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "vms.identity.enabled=true",
                "vms.masterdata.enabled=true",
                "vms.security.jwt.secret=integration-test-secret-least-32-bytes-long-xx",
                "spring.flyway.enabled=false"
        })
class FloorAdminIT {

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
    @DisplayName("AC-1: a floor is created under an active building and audited")
    void createFloor() throws Exception {
        String admin = token();
        String building = createBuilding("crt", admin);

        HttpResponse<String> res = post(floors(building), floorBody("l01", "Level 1", 1), admin);

        assertThat(res.statusCode()).isEqualTo(201);
        String id = extract(res.body(), "id");
        assertThat(scalar("SELECT code FROM vms.floors WHERE id='" + id + "'")).isEqualTo("L01");
        assertThat(scalar("SELECT count(*) FROM vms.audit_logs "
                + "WHERE action='floor.created' AND entity_id='" + id + "'")).isEqualTo("1");
        assertThat(scalar("SELECT after_state::text FROM vms.audit_logs "
                + "WHERE action='floor.created' AND entity_id='" + id + "'"))
                .contains(building);      // the parent is part of a floor's identity
    }

    @Test
    @DisplayName("AC-2: floors order by level_no, with unnumbered ones last, and filter by active")
    void orderingAndFiltering() throws Exception {
        String admin = token();
        String building = createBuilding("ord", admin);
        post(floors(building), floorBody("l02", "Level 2", 2), admin);
        post(floors(building), floorBody("mezz", "Mezzanine", null), admin);
        post(floors(building), floorBody("l01", "Level 1", 1), admin);

        String body = get(floors(building) + "?size=50", admin).body();

        // Numbered floors in order, then the unnumbered one — where someone reading a floor list
        // expects it, rather than sorted ahead of the ground floor.
        assertThat(body.indexOf("L01")).isLessThan(body.indexOf("L02"));
        assertThat(body.indexOf("L02")).isLessThan(body.indexOf("MEZZ"));

        String floorId = extract(post(floors(building), floorBody("l99", "Old", 99), admin).body(),
                "id");
        post(floors(building) + "/" + floorId + "/deactivate", "", admin);
        assertThat(get(floors(building) + "?active=true&size=50", admin).body())
                .doesNotContain("L99");
        assertThat(get(floors(building) + "?active=false&size=50", admin).body()).contains("L99");
    }

    @Test
    @DisplayName("AC-2: a building's floor list contains only its own floors")
    void listIsScopedToTheBuilding() throws Exception {
        String admin = token();
        String a = createBuilding("sca", admin);
        String b = createBuilding("scb", admin);
        post(floors(a), floorBody("only-a", "In A", 1), admin);
        post(floors(b), floorBody("only-b", "In B", 1), admin);

        assertThat(get(floors(a) + "?size=50", admin).body())
                .contains("ONLY-A").doesNotContain("ONLY-B");
    }

    @Test
    @DisplayName("AC-4: a duplicate code within a building is 409; the same code elsewhere is fine")
    void compositeUniqueness() throws Exception {
        String admin = token();
        String a = createBuilding("dupa", admin);
        String b = createBuilding("dupb", admin);

        assertThat(post(floors(a), floorBody("l01", "Level 1", 1), admin).statusCode())
                .isEqualTo(201);
        assertThat(post(floors(a), floorBody("l01", "Again", 1), admin).statusCode())
                .isEqualTo(409);
        // Every tower has an L01 — uniqueness is (building_id, code), not global.
        assertThat(post(floors(b), floorBody("l01", "Level 1", 1), admin).statusCode())
                .isEqualTo(201);
    }

    @Test
    @DisplayName("AC-5: creating under a deactivated building is refused, naming buildingId")
    void parentMustBeActive() throws Exception {
        String admin = token();
        String building = createBuilding("inact", admin);
        post("/api/v1/admin/buildings/" + building + "/deactivate", "", admin);

        HttpResponse<String> res = post(floors(building), floorBody("l01", "Level 1", 1), admin);

        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body()).contains("buildingId");
        assertThat(scalar("SELECT count(*) FROM vms.floors WHERE building_id='" + building + "'"))
                .isEqualTo("0");
    }

    @Test
    @DisplayName("AC-5: creating under a building that does not exist is refused the same way")
    void parentMustExist() throws Exception {
        HttpResponse<String> res = post(floors(UUID.randomUUID().toString()),
                floorBody("l01", "Level 1", 1), token());

        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body()).contains("buildingId");
    }

    @Test
    @DisplayName("AC-6: there is no delete route — only deactivation")
    void deleteIsNotOffered() throws Exception {
        String admin = token();
        String building = createBuilding("nodel", admin);
        String id = extract(post(floors(building), floorBody("l01", "Level 1", 1), admin).body(),
                "id");

        assertThat(delete(floors(building) + "/" + id, admin).statusCode()).isIn(404, 405);
        assertThat(scalar("SELECT count(*) FROM vms.floors WHERE id='" + id + "'")).isEqualTo("1");
    }

    @Test
    @DisplayName("a floor reached through the wrong building is a 404, not someone else's floor")
    void wrongParentInPathIsNotFound() throws Exception {
        String admin = token();
        String a = createBuilding("wpa", admin);
        String b = createBuilding("wpb", admin);
        String id = extract(post(floors(a), floorBody("l01", "Level 1", 1), admin).body(), "id");

        assertThat(get(floors(a) + "/" + id, admin).statusCode()).isEqualTo(200);
        assertThat(get(floors(b) + "/" + id, admin).statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("updating through the wrong building does not move the floor between buildings")
    void updateCannotRelocateAFloor() throws Exception {
        String admin = token();
        String a = createBuilding("mva", admin);
        String b = createBuilding("mvb", admin);
        String id = extract(post(floors(a), floorBody("l01", "Level 1", 1), admin).body(), "id");

        // Moving a floor would relocate every reception on it without anyone asking.
        assertThat(put(floors(b) + "/" + id, floorBody("l01", "Moved", 1), admin).statusCode())
                .isEqualTo(404);
        assertThat(scalar("SELECT building_id FROM vms.floors WHERE id='" + id + "'"))
                .isEqualTo(a);
    }

    @Test
    @DisplayName("AC-3: deactivation retains the row and is audited")
    void deactivateRetainsTheRow() throws Exception {
        String admin = token();
        String building = createBuilding("dea", admin);
        String id = extract(post(floors(building), floorBody("l01", "Level 1", 1), admin).body(),
                "id");

        assertThat(post(floors(building) + "/" + id + "/deactivate", "", admin).statusCode())
                .isEqualTo(204);
        assertThat(scalar("SELECT is_active FROM vms.floors WHERE id='" + id + "'")).isEqualTo("f");
        assertThat(scalar("SELECT count(*) FROM vms.audit_logs "
                + "WHERE action='floor.deactivated' AND entity_id='" + id + "'")).isEqualTo("1");
    }

    @Test
    @DisplayName("negative: masterdata.view alone cannot create a floor")
    void viewerCannotWrite() throws Exception {
        String building = createBuilding("vwr", token());

        assertThat(post(floors(building), floorBody("l01", "Level 1", 1), token("viewer"))
                .statusCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("negative: an implausible level number is 400 naming the field")
    void implausibleLevelRefused() throws Exception {
        String admin = token();
        String building = createBuilding("lvl", admin);

        HttpResponse<String> res = post(floors(building), floorBody("l01", "Level 1", 2026), admin);

        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body()).contains("levelNo");
    }

    // ---- helpers ----

    private static String floors(String buildingId) {
        return "/api/v1/admin/buildings/" + buildingId + "/floors";
    }

    private static String floorBody(String code, String name, Integer levelNo) {
        return "{\"code\":\"" + code + "\",\"name\":\"" + name + "\""
                + (levelNo == null ? "" : ",\"levelNo\":" + levelNo) + "}";
    }

    private String createBuilding(String code, String bearer) throws Exception {
        return extract(post("/api/v1/admin/buildings",
                "{\"code\":\"" + code + "\",\"name\":\"Building " + code + "\"}", bearer).body(),
                "id");
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
}
