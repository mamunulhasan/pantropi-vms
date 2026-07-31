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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * INTEGRATION TEST for US-07.3.3 — an FM Admin opens a request for review (FR-VMS-02, SRS B1).
 *
 * <p>This closes a gap the earlier stories left: the detail route was declared with
 * {@code visitor.request} alone, so the approver who has to decide a request could not open the one
 * they were looking at in their own queue. It is the first route in the system reachable by two
 * different permissions, and the tests below are mostly about that not becoming a way for either
 * caller to see the other's data.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "vms.identity.enabled=true",
                "vms.security.jwt.secret=integration-test-secret-least-32-bytes-long-xx",
                "spring.flyway.enabled=false"
        })
class ReviewRequestIT {

    private static EmbeddedPostgres pg;
    private static UUID tenantA;
    private static UUID hostA;
    private static UUID hostB;
    private static UUID contractorType;

    private final HttpClient client = HttpClient.newHttpClient();

    @LocalServerPort int port;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) throws Exception {
        pg = EmbeddedPostgres.start();
        Flyway.configure().dataSource(pg.getPostgresDatabase())
                .schemas("vms").defaultSchema("vms").cleanDisabled(true).load().migrate();
        seed();
        r.add("spring.datasource.url",
                () -> "jdbc:postgresql://localhost:" + pg.getPort() + "/postgres");
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

    // ---- AC-1 ----

    @Test
    @DisplayName("AC-1: an FM Admin sees the tenant, host, purpose, window, status and every "
            + "visitor with name, company and type")
    void approverSeesTheWholeRequest() throws Exception {
        String id = submit("Ada Lovelace", "Analytical Ltd", contractorType);

        var res = get("/api/v1/visitor-requests/" + id, token("fmadmin"));

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body())
                .contains("\"host\":\"Host A\"")
                .contains("\"purpose\":\"Quarterly review\"")
                .contains("\"status\":\"submitted\"")
                .contains("scheduledFrom").contains("scheduledTo")
                .contains("Ada Lovelace").contains("Analytical Ltd")
                // The type's display name, not its id — a reviewer reads "Contractor".
                .contains("\"visitorType\":\"Contractor\"")
                .doesNotContain(contractorType.toString())
                // The two ids point in opposite directions, and both directions matter. The
                // visitor's own id IS present as of US-09.1.2 — an approver has to be able to act
                // on that visitor, and issuing their pass is why the line exists. The visitor
                // TYPE's id is still absent, because it can only be probed against master data.
                .contains(scalar("SELECT id::text FROM vms.visitors WHERE request_id='"
                        + id + "'"));
    }

    @Test
    @DisplayName("this route was unreachable for an approver before US-07.3.3 — a queue row now "
            + "opens")
    void theQueueRowOpens() throws Exception {
        String id = submit("Guest", null, null);
        String bearer = token("fmadmin");

        // Exactly the journey the story describes: find it in the queue, then open it.
        assertThat(get("/api/v1/visitor-requests/pending?size=100", bearer).body()).contains(id);
        assertThat(get("/api/v1/visitor-requests/" + id, bearer).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("the detail still carries no contact detail or document reference (TODO-13)")
    void detailStopsShortOfContactDetails() throws Exception {
        String email = "review-" + UUID.randomUUID() + "@example.test";
        String id = submitWith("Ada Lovelace", email, "+8801712345678");

        var res = get("/api/v1/visitor-requests/" + id, token("fmadmin"));

        // An approver deciding whether to admit somebody needs to know who is coming, not how to
        // reach them.
        assertThat(res.body()).contains("Ada Lovelace")
                .doesNotContain(email).doesNotContain("8801712345678")
                .doesNotContain("idDocument");
    }

    // ---- AC-2 ----

    @Test
    @DisplayName("AC-2: opening a request writes a visitor_request.view audit entry naming the "
            + "reader — and repeating none of the personal data it records access to")
    void readingIsAudited() throws Exception {
        String id = submit("Ada Lovelace", "Analytical Ltd", contractorType);
        String fmAdminId = scalar("SELECT id FROM vms.users WHERE username='fmadmin'");

        get("/api/v1/visitor-requests/" + id, token("fmadmin"));

        assertThat(scalar("SELECT count(*) FROM vms.audit_logs"
                + " WHERE action='visitor_request.view' AND entity_id='" + id + "'"))
                .isEqualTo("1");
        assertThat(scalar("SELECT user_id FROM vms.audit_logs"
                + " WHERE action='visitor_request.view' AND entity_id='" + id + "'"))
                .isEqualTo(fmAdminId);
        // T-07.3.3.1: no before or after state on a read. Both columns null is the whole point —
        // JdbcAuditTrail.record() wraps any detail it is given into after_state, so this asserts the
        // entry carries nothing rather than carrying something harmless.
        assertThat(scalar("SELECT before_state FROM vms.audit_logs"
                + " WHERE action='visitor_request.view' AND entity_id='" + id + "'")).isNull();
        assertThat(scalar("SELECT after_state FROM vms.audit_logs"
                + " WHERE action='visitor_request.view' AND entity_id='" + id + "'")).isNull();
        // And nowhere in the trail for this request does the visitor's name appear.
        assertThat(scalar("SELECT count(*) FROM vms.audit_logs WHERE entity_id='" + id
                + "' AND (after_state::text LIKE '%Ada Lovelace%'"
                + " OR before_state::text LIKE '%Ada Lovelace%')")).isEqualTo("0");
    }

    @Test
    @DisplayName("AC-2: every reader is recorded, so two people opening it leaves two entries")
    void eachReadIsRecorded() throws Exception {
        String id = submit("Guest", null, null);

        get("/api/v1/visitor-requests/" + id, token("fmadmin"));
        get("/api/v1/visitor-requests/" + id, token("tenantuser"));

        // The tenant's own read is audited too. They may look at their own request, and a record of
        // who looked at whose personal data is not less useful because the answer is "the owner".
        assertThat(scalar("SELECT count(*) FROM vms.audit_logs"
                + " WHERE action='visitor_request.view' AND entity_id='" + id + "'"))
                .isEqualTo("2");
    }

    @Test
    @DisplayName("a request that was never found is not recorded as having been viewed")
    void missingRequestIsNotAView() throws Exception {
        String unknown = UUID.randomUUID().toString();

        assertThat(get("/api/v1/visitor-requests/" + unknown, token("fmadmin")).statusCode())
                .isEqualTo(404);

        assertThat(scalar("SELECT count(*) FROM vms.audit_logs"
                + " WHERE action='visitor_request.view' AND entity_id='" + unknown + "'"))
                .isEqualTo("0");
    }

    // ---- AC-3 ----

    @Test
    @DisplayName("AC-3: a decided request shows the outcome, the decider and the recorded reason")
    void decidedRequestShowsItsDecision() throws Exception {
        String id = submit("Guest", null, null);
        post("/api/v1/visitor-requests/" + id + "/reject", "{\"reason\":\"Host is on leave\"}",
                token("fmadmin"));

        var res = get("/api/v1/visitor-requests/" + id, token("fmadmin"));

        assertThat(res.body()).contains("\"status\":\"rejected\"")
                .contains("\"decidedBy\":\"Frances Manager\"")
                .contains("Host is on leave")
                .contains("decidedAt");
        // Which controls to render is the UI's decision, and the status is what tells it (AC-3);
        // the API does not carry a flag for it, because a second representation of the same fact is
        // one that can disagree with the first.
    }

    // ---- AC-4, AC-5 ----

    @Test
    @DisplayName("AC-4: a missing id and one outside my scope are byte-identical 404s")
    void missingAndOutOfScopeAreIndistinguishable() throws Exception {
        String theirs = submitAs("tenantbuser", hostB);
        String neverExisted = UUID.randomUUID().toString();
        String bearer = token("tenantuser");

        var foreign = get("/api/v1/visitor-requests/" + theirs, bearer);
        var missing = get("/api/v1/visitor-requests/" + neverExisted, bearer);

        assertThat(foreign.statusCode()).isEqualTo(404);
        assertThat(foreign.body()).isEqualTo(missing.body());
        assertThat(foreign.headers().firstValue("Content-Type"))
                .isEqualTo(missing.headers().firstValue("Content-Type"));
    }

    @Test
    @DisplayName("AC-4: the approver's scope is wider, so the same id they cannot see is one the "
            + "FM Admin can — decided by the policy, not by the route")
    void scopeDiffersByCallerNotByRoute() throws Exception {
        String theirs = submitAs("tenantbuser", hostB);

        assertThat(get("/api/v1/visitor-requests/" + theirs, token("tenantuser")).statusCode())
                .isEqualTo(404);
        // Same handler, same route, same permission check passed — a different scope predicate.
        assertThat(get("/api/v1/visitor-requests/" + theirs, token("fmadmin")).statusCode())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("AC-5: a caller holding neither permission gets 403 with no PII in the body")
    void neitherPermissionIsRefused() throws Exception {
        String id = submit("Ada Lovelace", "Analytical Ltd", contractorType);

        var res = get("/api/v1/visitor-requests/" + id, token("receptionist"));

        assertThat(res.statusCode()).isEqualTo(403);
        assertThat(res.body()).doesNotContain("Ada Lovelace").doesNotContain("Analytical");
        // The denial names what was required. A caller holding none of several lacks all of them.
        assertThat(scalar("""
                SELECT count(*) FROM vms.audit_logs
                WHERE action='authorization.denied'
                  AND after_state::text LIKE '%visitor.request or visitor.approve%'"""))
                .isNotEqualTo("0");
        // AC-5: refused before the read, so nothing was recorded as viewed.
        assertThat(scalar("SELECT count(*) FROM vms.audit_logs"
                + " WHERE action='visitor_request.view' AND entity_id='" + id + "'"))
                .isEqualTo("0");
    }

    @Test
    @DisplayName("an unauthenticated read is 401")
    void unauthenticated() throws Exception {
        assertThat(get("/api/v1/visitor-requests/" + UUID.randomUUID(), null).statusCode())
                .isEqualTo(401);
    }

    // ---- helpers ----

    private String submit(String name, String company, UUID type) throws Exception {
        String visitor = "{\"fullName\":\"" + name + "\""
                + (company == null ? "" : ",\"company\":\"" + company + "\"")
                + (type == null ? "" : ",\"visitorTypeId\":\"" + type + "\"") + "}";
        return submitBody("tenantuser", hostA, visitor);
    }

    private String submitWith(String name, String email, String phone) throws Exception {
        return submitBody("tenantuser", hostA, "{\"fullName\":\"" + name + "\",\"email\":\"" + email
                + "\",\"phone\":\"" + phone + "\"}");
    }

    private String submitAs(String username, UUID host) throws Exception {
        return submitBody(username, host, "{\"fullName\":\"Other Guest\"}");
    }

    private String submitBody(String username, UUID host, String visitorJson) throws Exception {
        Instant from = Instant.now().plus(30, ChronoUnit.DAYS);
        var res = post("/api/v1/visitor-requests",
                "{\"hostId\":\"" + host + "\",\"scheduledFrom\":\"" + from + "\","
                        + "\"scheduledTo\":\"" + from.plus(2, ChronoUnit.HOURS) + "\","
                        + "\"purpose\":\"Quarterly review\",\"visitors\":[" + visitorJson + "]}",
                token(username));
        assertThat(res.statusCode()).isEqualTo(201);
        return field(res.body(), "id");
    }

    private String token(String username) throws Exception {
        String password = switch (username) {
            case "tenantuser" -> "tenant-password-1234";
            case "tenantbuser" -> "tenantb-password-1234";
            case "receptionist" -> "reception-password-12";
            default -> "fm-admin-password-123";
        };
        String body = post("/api/v1/auth/login",
                "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}",
                null).body();
        int i = body.indexOf("\"accessToken\":\"") + 15;
        return body.substring(i, body.indexOf('"', i));
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        return send("GET", path, null, bearer);
    }

    private HttpResponse<String> post(String path, String body, String bearer) throws Exception {
        return send("POST", path, body, bearer);
    }

    private HttpResponse<String> send(String method, String path, String body, String bearer)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String field(String json, String key) {
        int i = json.indexOf("\"" + key + "\":\"") + key.length() + 4;
        return json.substring(i, json.indexOf('"', i));
    }

    private static void seed() throws Exception {
        var hasher = new Pbkdf2PasswordHasher();
        try (Connection c = pg.getPostgresDatabase().getConnection();
             Statement s = c.createStatement()) {
            String building = UUID.randomUUID().toString();
            s.execute("INSERT INTO vms.buildings (id, code, name) VALUES ('" + building
                    + "','B1','Tower')");
            String floor = UUID.randomUUID().toString();
            s.execute("INSERT INTO vms.floors (id, building_id, code, name) VALUES ('" + floor
                    + "','" + building + "','F1','Floor 1')");

            tenantA = UUID.randomUUID();
            UUID tenantB = UUID.randomUUID();
            s.execute("INSERT INTO vms.tenants (id, code, name, floor_id) VALUES ('" + tenantA
                    + "','TA','Tenant A','" + floor + "')");
            s.execute("INSERT INTO vms.tenants (id, code, name, floor_id) VALUES ('" + tenantB
                    + "','TB','Tenant B','" + floor + "')");

            hostA = UUID.randomUUID();
            hostB = UUID.randomUUID();
            s.execute("INSERT INTO vms.hosts (id, tenant_id, full_name, is_active) VALUES ('"
                    + hostA + "','" + tenantA + "','Host A', true)");
            s.execute("INSERT INTO vms.hosts (id, tenant_id, full_name, is_active) VALUES ('"
                    + hostB + "','" + tenantB + "','Host B', true)");

            contractorType = UUID.fromString(single(s,
                    "SELECT id FROM vms.visitor_types WHERE code='CONTRACTOR'"));

            String tenantRole = single(s, "SELECT id FROM vms.roles WHERE code='TENANT'");
            String fmRole = single(s, "SELECT id FROM vms.roles WHERE code='FM_ADMIN'");
            String floorRole = single(s, "SELECT id FROM vms.roles WHERE code='FLOOR_RECEPTIONIST'");
            user(s, hasher, "tenantuser", "Tina Tenant", "tenant-password-1234", tenantRole,
                    tenantA.toString());
            user(s, hasher, "tenantbuser", "Bob Tenant", "tenantb-password-1234", tenantRole,
                    tenantB.toString());
            user(s, hasher, "fmadmin", "Frances Manager", "fm-admin-password-123", fmRole, null);
            // Holds visitor.register and masterdata.view — neither of which opens this route.
            user(s, hasher, "receptionist", "Rita Reception", "reception-password-12", floorRole,
                    null);
        }
    }

    private static void user(Statement s, Pbkdf2PasswordHasher hasher, String username,
                             String fullName, String password, String roleId, String tenantId)
            throws Exception {
        s.execute("INSERT INTO vms.users (id, username, email, full_name, password_hash, role_id,"
                + " tenant_id, is_active) VALUES ('" + UUID.randomUUID() + "','" + username + "','"
                + username + "@ex.com','" + fullName + "','"
                + hasher.hash(password.toCharArray()) + "','" + roleId + "',"
                + (tenantId == null ? "NULL" : "'" + tenantId + "'") + ", true)");
    }

    private static String single(Statement s, String sql) throws Exception {
        try (ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static String scalar(String sql) throws Exception {
        try (Connection c = pg.getPostgresDatabase().getConnection();
             Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
