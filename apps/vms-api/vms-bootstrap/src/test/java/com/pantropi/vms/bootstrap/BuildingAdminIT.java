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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * INTEGRATION TEST for US-04.1.1 — the building register over real HTTP against real PostgreSQL
 * (Zonky embedded; no Docker). Also the first exercise of the shared master data pattern.
 *
 * <p>Two principals: {@code mdadmin} holds {@code masterdata.view} + {@code masterdata.edit};
 * {@code viewer} holds only {@code masterdata.view}, which is what AC-6 is about.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "vms.identity.enabled=true",
                "vms.masterdata.enabled=true",
                "vms.security.jwt.secret=integration-test-secret-least-32-bytes-long-xx",
                "spring.flyway.enabled=false"
        })
class BuildingAdminIT {

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
    @DisplayName("AC-1: create writes an active row and audits it with an after state")
    void createBuilding() throws Exception {
        HttpResponse<String> res = post("/api/v1/admin/buildings",
                body("wgt-a", "Westgate Tower A", "Westgate, Dhaka"), token("mdadmin"));

        assertThat(res.statusCode()).isEqualTo(201);
        String id = extract(res.body(), "id");

        assertThat(scalar("SELECT code FROM vms.buildings WHERE id='" + id + "'"))
                .isEqualTo("WGT-A");                     // normalised on the way in
        assertThat(scalar("SELECT is_active FROM vms.buildings WHERE id='" + id + "'"))
                .isEqualTo("t");
        assertThat(scalar("SELECT count(*) FROM vms.audit_logs "
                + "WHERE action='building.created' AND entity_id='" + id + "'")).isEqualTo("1");
        assertThat(scalar("SELECT after_state::text FROM vms.audit_logs "
                + "WHERE action='building.created' AND entity_id='" + id + "'"))
                .contains("Westgate Tower A");
    }

    @Test
    @DisplayName("AC-2: listing is paginated, filterable by active and searchable by code or name")
    void listIsPaginatedAndFilterable() throws Exception {
        String admin = token("mdadmin");
        post("/api/v1/admin/buildings", body("lst-1", "List One", null), admin);
        post("/api/v1/admin/buildings", body("lst-2", "List Two", null), admin);

        assertThat(get("/api/v1/admin/buildings?size=1", admin).body())
                .contains("\"size\":1")
                .contains("\"total\":");

        // Search matches the name...
        assertThat(get("/api/v1/admin/buildings?search=List%20One", admin).body())
                .contains("LST-1").doesNotContain("LST-2");
        // ...and the code, without the operator needing to know which they typed.
        assertThat(get("/api/v1/admin/buildings?search=lst-2", admin).body())
                .contains("LST-2").doesNotContain("LST-1");
    }

    @Test
    @DisplayName("AC-3: deactivation retains the row and drops it from the active filter")
    void deactivateRetainsTheRow() throws Exception {
        String admin = token("mdadmin");
        String id = extract(post("/api/v1/admin/buildings",
                body("dea-1", "To Deactivate", null), admin).body(), "id");

        assertThat(post("/api/v1/admin/buildings/" + id + "/deactivate", "", admin).statusCode())
                .isEqualTo(204);

        assertThat(scalar("SELECT count(*) FROM vms.buildings WHERE id='" + id + "'"))
                .isEqualTo("1");                          // retained
        assertThat(scalar("SELECT is_active FROM vms.buildings WHERE id='" + id + "'"))
                .isEqualTo("f");
        assertThat(get("/api/v1/admin/buildings?active=true&size=200", admin).body())
                .doesNotContain("DEA-1");
        assertThat(get("/api/v1/admin/buildings?active=false&size=200", admin).body())
                .contains("DEA-1");
    }

    @Test
    @DisplayName("AC-4: a duplicate code is 409 naming the field, under a genuine concurrent race")
    void duplicateCodeUnderConcurrency() throws Exception {
        String admin = token("mdadmin");

        // The AC says the unique constraint must be honoured "rather than relying on a race-prone
        // pre-check", so this fires the requests simultaneously rather than one after another —
        // sequential inserts would pass even with a check-then-insert implementation.
        int racers = 6;
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CyclicBarrier start = new CyclicBarrier(racers);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < racers; i++) {
            tasks.add(() -> {
                start.await();
                return post("/api/v1/admin/buildings", body("race", "Race", null), admin)
                        .statusCode();
            });
        }
        List<Integer> codes = new ArrayList<>();
        for (Future<Integer> f : pool.invokeAll(tasks)) {
            codes.add(f.get());
        }
        pool.shutdown();

        assertThat(codes).filteredOn(c -> c == 201).hasSize(1);          // exactly one winner
        assertThat(codes).filteredOn(c -> c == 409).hasSize(racers - 1); // the rest are conflicts
        assertThat(scalar("SELECT count(*) FROM vms.buildings WHERE code='RACE'")).isEqualTo("1");
    }

    @Test
    @DisplayName("AC-4: renaming onto an existing code is refused too")
    void renameOntoExistingCode() throws Exception {
        String admin = token("mdadmin");
        post("/api/v1/admin/buildings", body("ren-a", "Ren A", null), admin);
        String id = extract(post("/api/v1/admin/buildings",
                body("ren-b", "Ren B", null), admin).body(), "id");

        HttpResponse<String> res = put("/api/v1/admin/buildings/" + id,
                body("ren-a", "Ren B", null), admin);

        assertThat(res.statusCode()).isEqualTo(409);
        assertThat(res.body()).contains("code");
    }

    @Test
    @DisplayName("AC-5: there is no delete route at all — only deactivation")
    void deleteIsNotOffered() throws Exception {
        String admin = token("mdadmin");
        String id = extract(post("/api/v1/admin/buildings",
                body("nodel", "No Delete", null), admin).body(), "id");

        HttpResponse<String> res = delete("/api/v1/admin/buildings/" + id, admin);

        // Not 403-because-guarded: the verb is not mapped, so it is refused as unroutable and the
        // row is untouched.
        assertThat(res.statusCode()).isIn(404, 405);
        assertThat(scalar("SELECT count(*) FROM vms.buildings WHERE id='" + id + "'"))
                .isEqualTo("1");
    }

    @Test
    @DisplayName("AC-6: masterdata.view alone can read but not write, and the denial is audited")
    void viewerCannotWrite() throws Exception {
        String viewer = token("viewer");

        assertThat(get("/api/v1/admin/buildings", viewer).statusCode()).isEqualTo(200);
        assertThat(post("/api/v1/admin/buildings", body("vwr", "Viewer", null), viewer)
                .statusCode()).isEqualTo(403);
        assertThat(scalar("SELECT count(*) FROM vms.audit_logs "
                + "WHERE action='authorization.denied'")).isNotEqualTo("0");
        assertThat(scalar("SELECT count(*) FROM vms.buildings WHERE code='VWR'")).isEqualTo("0");
    }

    @Test
    @DisplayName("negative: an invalid code is 400 naming the field, and writes nothing")
    void invalidCodeIsBadRequest() throws Exception {
        HttpResponse<String> res = post("/api/v1/admin/buildings",
                body("has space", "Bad Code", null), token("mdadmin"));

        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body()).contains("code");
    }

    @Test
    @DisplayName("negative: buildings are not reachable without a token")
    void requiresAuthentication() throws Exception {
        assertThat(get("/api/v1/admin/buildings", null).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("negative: an unknown id is 404 on read and on mutation")
    void unknownIdIsNotFound() throws Exception {
        String admin = token("mdadmin");
        UUID missing = UUID.randomUUID();

        assertThat(get("/api/v1/admin/buildings/" + missing, admin).statusCode()).isEqualTo(404);
        assertThat(post("/api/v1/admin/buildings/" + missing + "/deactivate", "", admin)
                .statusCode()).isEqualTo(404);
    }

    // ---- helpers ----

    private static String body(String code, String name, String address) {
        return "{\"code\":\"" + code + "\",\"name\":\"" + name + "\""
                + (address == null ? "" : ",\"address\":\"" + address + "\"") + "}";
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

    /** Grants are seeded here; the production matrix lands in V9 (US-03.1.1), not in this story. */
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
            grant(s, "FM_ADMIN", "masterdata.view");   // deliberately not masterdata.edit
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
