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
 * INTEGRATION TEST for US-04.4.1 — reception points, the central designation, and the guards
 * protecting the FR-ADM-01 (SRS B1) authority.
 *
 * <p>Each test builds its own floor, so the singular central designation — which is installation
 * wide, not per floor — does not couple them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "vms.identity.enabled=true",
                "vms.masterdata.enabled=true",
                "vms.security.jwt.secret=integration-test-secret-least-32-bytes-long-xx",
                "spring.flyway.enabled=false"
        })
class ReceptionAdminIT {

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
    @DisplayName("AC-1: a reception is created on an active floor and audited, never central")
    void createReception() throws Exception {
        String admin = token();
        String floor = createFloor("crt", admin);

        HttpResponse<String> res = post("/api/v1/admin/receptions",
                body(floor, "rc01", "Reception 1"), admin);

        assertThat(res.statusCode()).isEqualTo(201);
        String id = extract(res.body(), "id");
        assertThat(scalar("SELECT code FROM vms.receptions WHERE id='" + id + "'"))
                .isEqualTo("RC01");
        // Never central on create: the designation is singular and moving it needs confirmation,
        // so it cannot be smuggled in where nobody would be asked.
        assertThat(scalar("SELECT is_central FROM vms.receptions WHERE id='" + id + "'"))
                .isEqualTo("f");
        assertThat(scalar("SELECT count(*) FROM vms.audit_logs "
                + "WHERE action='reception.created' AND entity_id='" + id + "'")).isEqualTo("1");
    }

    @Test
    @DisplayName("AC-4: a duplicate code within a floor is 409; the same code elsewhere is fine")
    void floorScopedUniqueness() throws Exception {
        String admin = token();
        String a = createFloor("dupa", admin);
        String b = createFloor("dupb", admin);

        assertThat(post("/api/v1/admin/receptions", body(a, "rc01", "One"), admin).statusCode())
                .isEqualTo(201);
        assertThat(post("/api/v1/admin/receptions", body(a, "rc01", "Again"), admin).statusCode())
                .isEqualTo(409);
        assertThat(post("/api/v1/admin/receptions", body(b, "rc01", "One"), admin).statusCode())
                .isEqualTo(201);
    }

    @Test
    @DisplayName("AC-2: transferring the central designation needs confirmation and audits the previous holder")
    void centralTransferRequiresConfirmationAndAuditsPredecessor() throws Exception {
        String admin = token();
        String floor = createFloor("ctr", admin);
        String first = extract(post("/api/v1/admin/receptions",
                body(floor, "ctr-a", "First"), admin).body(), "id");
        String second = extract(post("/api/v1/admin/receptions",
                body(floor, "ctr-b", "Second"), admin).body(), "id");

        // Whoever currently holds it is cleared first, so the initial designation may need
        // confirmation too if the seeded central reception exists.
        assertThat(post("/api/v1/admin/receptions/" + first + "/designate-central?confirm=true", "",
                admin).statusCode()).isEqualTo(204);

        // Without confirmation the transfer is refused, and the response names the current holder
        // so a caller can show it before asking.
        HttpResponse<String> unconfirmed =
                post("/api/v1/admin/receptions/" + second + "/designate-central", "", admin);
        assertThat(unconfirmed.statusCode()).isEqualTo(409);
        assertThat(unconfirmed.body()).contains("confirmation_required").contains("CTR-A");
        assertThat(scalar("SELECT is_central FROM vms.receptions WHERE id='" + first + "'"))
                .isEqualTo("t");

        assertThat(post("/api/v1/admin/receptions/" + second + "/designate-central?confirm=true", "",
                admin).statusCode()).isEqualTo(204);

        // Atomic: exactly one central reception, never two and never none.
        assertThat(scalar("SELECT count(*) FROM vms.receptions WHERE is_central")).isEqualTo("1");
        assertThat(scalar("SELECT is_central FROM vms.receptions WHERE id='" + second + "'"))
                .isEqualTo("t");

        // The predecessor is the part that cannot be reconstructed afterwards — the row it was
        // cleared from looks identical to one that never held it.
        assertThat(scalar("SELECT before_state::text FROM vms.audit_logs "
                + "WHERE action='reception.central_designated' AND entity_id='" + second + "'"))
                .contains(first).contains("CTR-A");
    }

    @Test
    @DisplayName("AC-3: receptions filter by floor and active status, with a visible total")
    void filteringAndTotals() throws Exception {
        String admin = token();
        String floor = createFloor("flt", admin);
        post("/api/v1/admin/receptions", body(floor, "flt-1", "One"), admin);
        post("/api/v1/admin/receptions", body(floor, "flt-2", "Two"), admin);

        String body = get("/api/v1/admin/receptions?floorId=" + floor + "&size=50", admin).body();

        assertThat(body).contains("FLT-1", "FLT-2").contains("\"total\":2");
        // The total is what an administrator checks the 120 + 1 structure against.
        assertThat(get("/api/v1/admin/receptions?size=1", admin).body()).contains("\"total\":");
    }

    @Test
    @DisplayName("AC-5: deactivation reports stationed users and leaves their reception_id intact")
    void deactivationKeepsUserAssignments() throws Exception {
        String admin = token();
        String floor = createFloor("sta", admin);
        String id = extract(post("/api/v1/admin/receptions",
                body(floor, "sta-1", "Staffed"), admin).body(), "id");
        attachReceptionists(id, 2);

        assertThat(get("/api/v1/admin/receptions/" + id + "/dependents", admin).body())
                .contains("\"activeUsers\":2");

        HttpResponse<String> res = post("/api/v1/admin/receptions/" + id + "/deactivate", "", admin);
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("\"activeUsers\":2");

        assertThat(scalar("SELECT is_active FROM vms.receptions WHERE id='" + id + "'"))
                .isEqualTo("f");
        // Nulling reception_id would silently unassign people as a side effect of retiring a
        // location. The assignment is worth more than the tidiness.
        assertThat(scalar("SELECT count(*) FROM vms.users WHERE reception_id='" + id + "'"))
                .isEqualTo("2");
    }

    @Test
    @DisplayName("AC-6: the sole Master Admin's central reception cannot be deactivated")
    void strandingGuard() throws Exception {
        String admin = token();
        String floor = createFloor("strand", admin);
        String central = extract(post("/api/v1/admin/receptions",
                body(floor, "strand-c", "Central"), admin).body(), "id");
        post("/api/v1/admin/receptions/" + central + "/designate-central?confirm=true", "", admin);
        UUID masterAdmin = createMasterAdmin("solemaster", central);

        HttpResponse<String> res =
                post("/api/v1/admin/receptions/" + central + "/deactivate", "", admin);

        assertThat(res.statusCode()).isEqualTo(409);
        assertThat(res.body()).contains("would_strand_master_admin");
        assertThat(scalar("SELECT is_active FROM vms.receptions WHERE id='" + central + "'"))
                .isEqualTo("t");

        deactivateDirectly(masterAdmin);
    }

    @Test
    @DisplayName("US-02.4.1 AC-4, finally wired: the last Master Admin cannot be deactivated")
    void lastMasterAdminGuardActuallyRuns() throws Exception {
        String admin = token();
        String floor = createFloor("lastma", admin);
        String central = extract(post("/api/v1/admin/receptions",
                body(floor, "lastma-c", "Central"), admin).body(), "id");
        post("/api/v1/admin/receptions/" + central + "/designate-central?confirm=true", "", admin);
        UUID sole = createMasterAdmin("lastmaster", central);

        // Before this story the guard existed, was unit-tested, and was called by nothing — this
        // request used to succeed.
        HttpResponse<String> res =
                post("/api/v1/admin/users/" + sole + "/deactivate", "", admin);

        assertThat(res.statusCode()).isEqualTo(409);
        assertThat(scalar("SELECT is_active FROM vms.users WHERE id='" + sole + "'"))
                .isEqualTo("t");

        deactivateDirectly(sole);
    }

    @Test
    @DisplayName("T-04.4.1.3: two concurrent deactivations cannot both remove the authority")
    void concurrentDeactivationsCannotBothSucceed() throws Exception {
        String admin = token();
        String floor = createFloor("race", admin);
        String central = extract(post("/api/v1/admin/receptions",
                body(floor, "race-c", "Central"), admin).body(), "id");
        post("/api/v1/admin/receptions/" + central + "/designate-central?confirm=true", "", admin);

        UUID first = createMasterAdmin("racemaster1", central);
        UUID second = createMasterAdmin("racemaster2", central);

        // Each request is individually legitimate: two administrators exist, so removing either one
        // leaves the other. Run at the same instant against an unlocked read, both observe two and
        // both proceed — and the installation is left with none.
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier start = new CyclicBarrier(2);
        List<Callable<Integer>> tasks = List.of(
                () -> { start.await(); return post("/api/v1/admin/users/" + first + "/deactivate",
                        "", admin).statusCode(); },
                () -> { start.await(); return post("/api/v1/admin/users/" + second + "/deactivate",
                        "", admin).statusCode(); });

        List<Integer> codes = new ArrayList<>();
        for (Future<Integer> f : pool.invokeAll(tasks)) {
            codes.add(f.get());
        }
        pool.shutdown();

        // Exactly one may win. The row lock makes the second wait, re-read, and see a state where
        // it is now the last.
        assertThat(codes).filteredOn(c -> c == 204).hasSize(1);
        assertThat(codes).filteredOn(c -> c == 409).hasSize(1);

        assertThat(scalar("""
                SELECT count(*) FROM vms.users u
                JOIN vms.roles r ON r.id = u.role_id
                JOIN vms.receptions rc ON rc.id = u.reception_id
                WHERE r.code = 'MASTER_ADMIN' AND u.is_active
                  AND rc.is_active AND rc.is_central""")).isNotEqualTo("0");

        deactivateDirectly(first);
        deactivateDirectly(second);
    }

    @Test
    @DisplayName("negative: viewer cannot write, unknown id 404, unauthenticated 401")
    void guards() throws Exception {
        String admin = token();
        String floor = createFloor("grd", admin);

        assertThat(post("/api/v1/admin/receptions", body(floor, "grd-1", "V"), token("viewer"))
                .statusCode()).isEqualTo(403);
        assertThat(get("/api/v1/admin/receptions/" + UUID.randomUUID(), admin).statusCode())
                .isEqualTo(404);
        assertThat(get("/api/v1/admin/receptions", null).statusCode()).isEqualTo(401);
    }

    // ---- helpers ----

    private static String body(String floorId, String code, String name) {
        return "{\"floorId\":\"" + floorId + "\",\"code\":\"" + code + "\",\"name\":\"" + name
                + "\"}";
    }

    private String createFloor(String prefix, String bearer) throws Exception {
        String building = extract(post("/api/v1/admin/buildings",
                "{\"code\":\"" + prefix + "-b\",\"name\":\"Building\"}", bearer).body(), "id");
        return extract(post("/api/v1/admin/buildings/" + building + "/floors",
                "{\"code\":\"" + prefix + "-f\",\"name\":\"Floor\",\"levelNo\":1}", bearer).body(),
                "id");
    }

    private static UUID createMasterAdmin(String username, String receptionId) throws Exception {
        UUID id = UUID.randomUUID();
        String hash = new Pbkdf2PasswordHasher().hash(PASSWORD.toCharArray());
        try (Connection c = pg.getPostgresDatabase().getConnection();
             Statement s = c.createStatement()) {
            String role = single(s, "SELECT id FROM vms.roles WHERE code='MASTER_ADMIN'");
            s.execute("INSERT INTO vms.users (id, username, email, full_name, password_hash,"
                    + " role_id, reception_id, is_active) VALUES ('" + id + "', '" + username
                    + "', '" + username + "@ex.com', '" + username + "', '" + hash + "', '" + role
                    + "', '" + receptionId + "', true)");
        }
        return id;
    }

    /** Leaves the shared state clean without going through the guard being tested. */
    private static void deactivateDirectly(UUID userId) throws Exception {
        try (Connection c = pg.getPostgresDatabase().getConnection();
             Statement s = c.createStatement()) {
            s.execute("UPDATE vms.users SET is_active = false WHERE id = '" + userId + "'");
        }
    }

    private static void attachReceptionists(String receptionId, int count) throws Exception {
        String hash = new Pbkdf2PasswordHasher().hash(PASSWORD.toCharArray());
        try (Connection c = pg.getPostgresDatabase().getConnection();
             Statement s = c.createStatement()) {
            String role = single(s, "SELECT id FROM vms.roles WHERE code='FLOOR_RECEPTIONIST'");
            for (int i = 0; i < count; i++) {
                String u = "recept" + i + "-" + receptionId.substring(0, 8);
                s.execute("INSERT INTO vms.users (id, username, email, full_name, password_hash,"
                        + " role_id, reception_id, is_active) VALUES ('" + UUID.randomUUID()
                        + "', '" + u + "', '" + u + "@ex.com', '" + u + "', '" + hash + "', '"
                        + role + "', '" + receptionId + "', true)");
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
            grant(s, "SYSTEM_ADMIN", "user.manage");
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
