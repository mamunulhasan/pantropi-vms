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
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.logging.StreamHandler;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * INTEGRATION TEST for US-04.3.1 — the tenant register over real HTTP against real PostgreSQL.
 *
 * <p>The interesting cases here are the ones the previous entities did not have: an optional parent
 * that must be active when supplied, a dependent-user count, and contact details that are personal
 * data and must not reach a log.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "vms.identity.enabled=true",
                "vms.masterdata.enabled=true",
                "vms.security.jwt.secret=integration-test-secret-least-32-bytes-long-xx",
                "spring.flyway.enabled=false"
        })
class TenantAdminIT {

    private static final String PASSWORD = "correct horse battery staple";
    private static final String EMAIL = "reception@uniquetenant.example";
    private static final String PHONE = "+880 1711 987654";

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
    @DisplayName("AC-1: a tenant is created with a floor and contact details, and audited")
    void createIsAudited() throws Exception {
        String admin = token();
        String floor = createFloor("crt", admin);

        HttpResponse<String> res = post("/api/v1/admin/tenants",
                body("acme", "Acme Corporation", floor, EMAIL, PHONE), admin);

        assertThat(res.statusCode()).isEqualTo(201);
        String id = extract(res.body(), "id");
        assertThat(scalar("SELECT code FROM vms.tenants WHERE id='" + id + "'")).isEqualTo("ACME");
        assertThat(scalar("SELECT floor_id FROM vms.tenants WHERE id='" + id + "'"))
                .isEqualTo(floor);

        // AC-6 permits the audit payload to hold them — an audit of a change that omitted the
        // changed field would not be an audit.
        assertThat(scalar("SELECT after_state::text FROM vms.audit_logs "
                + "WHERE action='tenant.created' AND entity_id='" + id + "'"))
                .contains(EMAIL);
    }

    @Test
    @DisplayName("a tenant with no floor and no contact details is accepted")
    void optionalFieldsAreOptional() throws Exception {
        HttpResponse<String> res = post("/api/v1/admin/tenants",
                "{\"code\":\"bare\",\"name\":\"Bare Tenant\"}", token());

        assertThat(res.statusCode()).isEqualTo(201);
        assertThat(scalar("SELECT floor_id IS NULL FROM vms.tenants WHERE code='BARE'"))
                .isEqualTo("t");
    }

    @Test
    @DisplayName("a deactivated floor cannot be named, but no floor at all is fine")
    void floorMustBeActiveWhenSupplied() throws Exception {
        String admin = token();
        String floor = createFloor("inact", admin);
        String building = scalar("SELECT building_id FROM vms.floors WHERE id='" + floor + "'");
        post("/api/v1/admin/buildings/" + building + "/floors/" + floor + "/deactivate", "", admin);

        HttpResponse<String> res = post("/api/v1/admin/tenants",
                body("onretired", "On a retired floor", floor, null, null), admin);

        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body()).contains("floorId");
        assertThat(scalar("SELECT count(*) FROM vms.tenants WHERE code='ONRETIRED'"))
                .isEqualTo("0");
    }

    @Test
    @DisplayName("AC-2: two tenants may share a contact address, differing only in case")
    void contactEmailIsNotAnIdentity() throws Exception {
        String admin = token();

        assertThat(post("/api/v1/admin/tenants",
                body("share1", "Shared One", null, "Agent@Managing.Example", null), admin)
                .statusCode()).isEqualTo(201);
        // A managing agent looking after two tenants is a real situation, and the schema places no
        // unique constraint on contact_email — only on code.
        assertThat(post("/api/v1/admin/tenants",
                body("share2", "Shared Two", null, "agent@managing.example", null), admin)
                .statusCode()).isEqualTo(201);
    }

    @Test
    @DisplayName("AC-3: the dependent active-user count is available before deactivating")
    void dependentUserCount() throws Exception {
        String admin = token();
        String id = extract(post("/api/v1/admin/tenants",
                body("withusers", "Has users", null, null, null), admin).body(), "id");

        assertThat(get("/api/v1/admin/tenants/" + id + "/dependents", admin).body())
                .contains("\"activeUsers\":0");

        attachUsers(id, 3);

        assertThat(get("/api/v1/admin/tenants/" + id + "/dependents", admin).body())
                .contains("\"activeUsers\":3");

        // Deactivation is not blocked — the administrator is told, and then decides. The response
        // repeats the count so a caller that skipped /dependents still learns what it affected.
        HttpResponse<String> res = post("/api/v1/admin/tenants/" + id + "/deactivate", "", admin);
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("\"activeUsers\":3");
        assertThat(scalar("SELECT is_active FROM vms.tenants WHERE id='" + id + "'"))
                .isEqualTo("f");
    }

    @Test
    @DisplayName("AC-3: a deactivated tenant drops out of the active list, and the row is kept")
    void deactivationRetainsTheRow() throws Exception {
        String admin = token();
        String id = extract(post("/api/v1/admin/tenants",
                body("retire", "To retire", null, null, null), admin).body(), "id");

        post("/api/v1/admin/tenants/" + id + "/deactivate", "", admin);

        assertThat(scalar("SELECT count(*) FROM vms.tenants WHERE id='" + id + "'")).isEqualTo("1");
        assertThat(get("/api/v1/admin/tenants?active=true&size=200", admin).body())
                .doesNotContain("RETIRE");
    }

    @Test
    @DisplayName("AC-4: a duplicate code is 409 on create and on rename")
    void duplicateCodeRefused() throws Exception {
        String admin = token();
        post("/api/v1/admin/tenants", body("dupa", "Dup A", null, null, null), admin);
        String other = extract(post("/api/v1/admin/tenants",
                body("dupb", "Dup B", null, null, null), admin).body(), "id");

        assertThat(post("/api/v1/admin/tenants", body("dupa", "Again", null, null, null), admin)
                .statusCode()).isEqualTo(409);
        assertThat(put("/api/v1/admin/tenants/" + other,
                body("dupa", "Dup B", null, null, null), admin).statusCode()).isEqualTo(409);
    }

    @Test
    @DisplayName("AC-5: a malformed email or phone is 400 and writes no partial row")
    void malformedContactsRefused() throws Exception {
        String admin = token();

        assertThat(post("/api/v1/admin/tenants",
                body("bad1", "Bad email", null, "not-an-address", null), admin).statusCode())
                .isEqualTo(400);
        assertThat(post("/api/v1/admin/tenants",
                body("bad2", "Bad phone", null, null, "call me"), admin).statusCode())
                .isEqualTo(400);

        assertThat(scalar("SELECT count(*) FROM vms.tenants WHERE code IN ('BAD1','BAD2')"))
                .isEqualTo("0");
    }

    /**
     * AC-6, scoped to what the application controls.
     *
     * <p>The capture is attached to the {@code com.pantropi.vms} logger at {@code ALL}, so any line
     * this project writes — at any level, on any path — is inspected.
     *
     * <p>It is deliberately <strong>not</strong> attached to the root logger. Tomcat's
     * {@code Http11InputBuffer} logs the raw request at {@code FINER}, so a root-level capture at
     * {@code ALL} contains every request body by definition and the assertion could never pass for
     * any field of any entity. That is a container configuration concern rather than something the
     * application can prevent — and it is worth knowing operationally: <em>enabling FINEST-level
     * Tomcat logging in a real deployment would put tenant contact details, and every other request
     * body, into the log.</em> Recorded in docs/project/15-running-locally.md.
     */
    @Test
    @DisplayName("AC-6: no contact value reaches an application log, at any level")
    void contactDetailsNeverReachTheLog() throws Exception {
        String admin = token();
        Logger root = Logger.getLogger("com.pantropi.vms");
        Level originalLevel = root.getLevel();
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Handler handler = new StreamHandler(captured, new SimpleFormatter()) {
            @Override
            public synchronized void publish(LogRecord record) {
                super.publish(record);
                flush();
            }
        };
        handler.setLevel(Level.ALL);

        root.addHandler(handler);
        root.setUseParentHandlers(true);
        root.setLevel(Level.ALL);   // FINEST included: a debug line is still a log line
        try {
            String id = extract(post("/api/v1/admin/tenants",
                    body("pii", "PII tenant", null, EMAIL, PHONE), admin).body(), "id");
            get("/api/v1/admin/tenants/" + id, admin);
            put("/api/v1/admin/tenants/" + id,
                    body("pii", "PII tenant renamed", null, EMAIL, PHONE), admin);
            get("/api/v1/admin/tenants?search=pii", admin);
            // ...and a rejected write, since an error path is where values usually escape.
            post("/api/v1/admin/tenants", body("pii", "Duplicate", null, EMAIL, PHONE), admin);

            handler.flush();
            String logged = captured.toString();

            assertThat(logged).doesNotContain(EMAIL);
            assertThat(logged).doesNotContain(PHONE);
            // Also the distinctive parts, in case something logged a fragment.
            assertThat(logged).doesNotContain("uniquetenant").doesNotContain("987654");
        } finally {
            root.removeHandler(handler);
            root.setLevel(originalLevel);
        }
    }

    @Test
    @DisplayName("negative: masterdata.view alone cannot create, and there is no delete route")
    void guards() throws Exception {
        String admin = token();
        String id = extract(post("/api/v1/admin/tenants",
                body("guard", "Guarded", null, null, null), admin).body(), "id");

        assertThat(post("/api/v1/admin/tenants", body("vwr", "Viewer", null, null, null),
                token("viewer")).statusCode()).isEqualTo(403);
        assertThat(delete("/api/v1/admin/tenants/" + id, admin).statusCode()).isIn(404, 405);
        assertThat(get("/api/v1/admin/tenants", null).statusCode()).isEqualTo(401);
        assertThat(get("/api/v1/admin/tenants/" + UUID.randomUUID() + "/dependents", admin)
                .statusCode()).isEqualTo(404);
    }

    // ---- helpers ----

    private static String body(String code, String name, String floorId, String email,
                               String phone) {
        StringBuilder sb = new StringBuilder("{\"code\":\"" + code + "\",\"name\":\"" + name + "\"");
        if (floorId != null) sb.append(",\"floorId\":\"").append(floorId).append('"');
        if (email != null) sb.append(",\"contactEmail\":\"").append(email).append('"');
        if (phone != null) sb.append(",\"contactPhone\":\"").append(phone).append('"');
        return sb.append('}').toString();
    }

    private String createFloor(String prefix, String bearer) throws Exception {
        String building = extract(post("/api/v1/admin/buildings",
                "{\"code\":\"" + prefix + "-b\",\"name\":\"Building\"}", bearer).body(), "id");
        return extract(post("/api/v1/admin/buildings/" + building + "/floors",
                "{\"code\":\"" + prefix + "-f\",\"name\":\"Floor\",\"levelNo\":1}", bearer).body(),
                "id");
    }

    /** Attaches users directly; user administration is not what this story is about. */
    private static void attachUsers(String tenantId, int count) throws Exception {
        String hash = new Pbkdf2PasswordHasher().hash(PASSWORD.toCharArray());
        try (Connection c = pg.getPostgresDatabase().getConnection();
             Statement s = c.createStatement()) {
            String role = single(s, "SELECT id FROM vms.roles WHERE code='TENANT'");
            for (int i = 0; i < count; i++) {
                String username = "tenantuser" + i + "-" + tenantId.substring(0, 8);
                s.execute("INSERT INTO vms.users (id, username, email, full_name, password_hash,"
                        + " role_id, tenant_id, is_active) VALUES ('" + UUID.randomUUID() + "', '"
                        + username + "', '" + username + "@ex.com', '" + username + "', '" + hash
                        + "', '" + role + "', '" + tenantId + "', true)");
            }
        }
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
            String viewerRole = single(s, "SELECT id FROM vms.roles WHERE code='FLOOR_RECEPTIONIST'");
            insertUser(s, "mdadmin", hash, sysRole);
            insertUser(s, "viewer", hash, viewerRole);
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
