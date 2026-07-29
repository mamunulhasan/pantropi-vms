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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * INTEGRATION TEST for US-07.1.3 — a tenant amends or withdraws its own request (FR-VMS-01,
 * SRS B1), over real HTTP against real PostgreSQL.
 *
 * <p>The test that needed a real database is {@link #approveAndCancelRace()}: AC-5 asks that an
 * FM Admin approving and a tenant cancelling concurrently leave exactly one winner, and that the
 * aggregate is never both approved and cancelled. Two fakes cannot demonstrate that; two
 * transactions contending for one row can.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "vms.identity.enabled=true",
                "vms.security.jwt.secret=integration-test-secret-least-32-bytes-long-xx",
                "spring.flyway.enabled=false"
        })
class AmendCancelIT {

    private static EmbeddedPostgres pg;
    private static UUID tenantA;
    private static UUID hostA;
    private static UUID hostA2;
    private static UUID hostB;

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

    // ---- AC-1: amend ----

    @Test
    @DisplayName("AC-1: the window, purpose, host and visitors are amended, audited and announced")
    void amendPersistsAndIsAudited() throws Exception {
        String id = submit(1);
        Instant newFrom = Instant.now().plus(45, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);

        var res = patch(id, """
                {"hostId":"%s","scheduledFrom":"%s","scheduledTo":"%s","purpose":"Rescheduled",
                 "visitors":[{"fullName":"Grace Hopper","email":"Grace@Example.TEST"}]}
                """.formatted(hostA2, newFrom, newFrom.plus(2, ChronoUnit.HOURS)),
                token("tenantuser"));

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(scalar("SELECT host_id FROM vms.visitor_requests WHERE id='" + id + "'"))
                .isEqualTo(hostA2.toString());
        assertThat(scalar("SELECT purpose FROM vms.visitor_requests WHERE id='" + id + "'"))
                .isEqualTo("Rescheduled");
        assertThat(scalar("SELECT status::text FROM vms.visitor_requests WHERE id='" + id + "'"))
                .isEqualTo("submitted");

        // The visitor rows are replaced wholesale, and the replacement is normalised like any other.
        assertThat(scalar("SELECT count(*) FROM vms.visitors WHERE request_id='" + id + "'"))
                .isEqualTo("1");
        assertThat(scalar("SELECT full_name FROM vms.visitors WHERE request_id='" + id + "'"))
                .isEqualTo("Grace Hopper");
        assertThat(scalar("SELECT email FROM vms.visitors WHERE request_id='" + id + "'"))
                .isEqualTo("grace@example.test");

        assertThat(scalar("SELECT count(*) FROM vms.audit_logs"
                + " WHERE action='visitor_request.amend' AND entity_id='" + id + "'"))
                .isEqualTo("1");
        assertThat(scalar("SELECT count(*) FROM vms.domain_events WHERE aggregate_id='" + id
                + "' AND event_type='VisitorRequestAmended'")).isEqualTo("1");
        // AC-1: PII-redacted.
        assertThat(scalar("SELECT count(*) FROM vms.audit_logs WHERE entity_id='" + id
                + "' AND after_state::text LIKE '%grace@example.test%'")).isEqualTo("0");
    }

    @Test
    @DisplayName("AC-1: a partial amendment leaves the fields it does not mention alone")
    void partialAmendment() throws Exception {
        String id = submit(2);
        String hostBefore = scalar("SELECT host_id FROM vms.visitor_requests WHERE id='" + id + "'");

        assertThat(patch(id, "{\"purpose\":\"Only this\"}", token("tenantuser")).statusCode())
                .isEqualTo(200);

        assertThat(scalar("SELECT purpose FROM vms.visitor_requests WHERE id='" + id + "'"))
                .isEqualTo("Only this");
        assertThat(scalar("SELECT host_id FROM vms.visitor_requests WHERE id='" + id + "'"))
                .isEqualTo(hostBefore);
        assertThat(scalar("SELECT count(*) FROM vms.visitors WHERE request_id='" + id + "'"))
                .isEqualTo("2");
    }

    @Test
    @DisplayName("a host belonging to another tenant is refused and nothing changes")
    void amendWithForeignHost() throws Exception {
        String id = submit(1);

        var res = patch(id, "{\"hostId\":\"" + hostB + "\"}", token("tenantuser"));

        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(scalar("SELECT host_id FROM vms.visitor_requests WHERE id='" + id + "'"))
                .isNotEqualTo(hostB.toString());
    }

    // ---- AC-2, AC-3: cancel ----

    @Test
    @DisplayName("AC-2: cancelling removes the request from the queue and cancels every visitor")
    void cancelFromSubmitted() throws Exception {
        String id = submit(2);
        assertThat(get("/api/v1/visitor-requests/pending?size=100", token("fmadmin")).body())
                .contains(id);

        var res = post("/api/v1/visitor-requests/" + id + "/cancel", null, token("tenantuser"));

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(scalar("SELECT status::text FROM vms.visitor_requests WHERE id='" + id + "'"))
                .isEqualTo("cancelled");
        assertThat(scalar("SELECT count(*) FROM vms.visitors WHERE request_id='" + id
                + "' AND status <> 'cancelled'")).isEqualTo("0");
        // AC-2: it leaves the approval queue.
        assertThat(get("/api/v1/visitor-requests/pending?size=100", token("fmadmin")).body())
                .doesNotContain(id);

        assertThat(scalar("SELECT count(*) FROM vms.domain_events WHERE aggregate_id='" + id
                + "' AND event_type='VisitorRequestCancelled'")).isEqualTo("1");
        // Nothing was granted, so nothing is asked to be revoked.
        assertThat(scalar("SELECT count(*) FROM vms.domain_events WHERE aggregate_id='" + id
                + "' AND event_type='CredentialRevocationRequested'")).isEqualTo("0");
    }

    @Test
    @DisplayName("AC-3: cancelling an approved request is permitted and signals revocation")
    void cancelAfterApproval() throws Exception {
        String id = submit(1);
        assertThat(post("/api/v1/visitor-requests/" + id + "/approve", null, token("fmadmin"))
                .statusCode()).isEqualTo(200);

        var res = post("/api/v1/visitor-requests/" + id + "/cancel", null, token("tenantuser"));

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(scalar("SELECT status::text FROM vms.visitor_requests WHERE id='" + id + "'"))
                .isEqualTo("cancelled");
        assertThat(scalar("SELECT count(*) FROM vms.domain_events WHERE aggregate_id='" + id
                + "' AND event_type='CredentialRevocationRequested'")).isEqualTo("1");
        // AC-3: the audit says the cancellation followed an approval — the row itself no longer can.
        assertThat(scalar("SELECT after_state->>'followedApproval' FROM vms.audit_logs"
                + " WHERE action='visitor_request.cancel' AND entity_id='" + id + "'"))
                .isEqualTo("true");
    }

    // ---- AC-4 ----

    @Test
    @DisplayName("AC-4: a rejected request can be neither amended nor cancelled — 409, no write")
    void terminalRequestsAreClosed() throws Exception {
        String id = submit(1);
        post("/api/v1/visitor-requests/" + id + "/reject", "{\"reason\":\"Host on leave\"}",
                token("fmadmin"));

        assertThat(patch(id, "{\"purpose\":\"Sneaky\"}", token("tenantuser")).statusCode())
                .isEqualTo(409);
        assertThat(post("/api/v1/visitor-requests/" + id + "/cancel", null, token("tenantuser"))
                .statusCode()).isEqualTo(409);

        assertThat(scalar("SELECT status::text FROM vms.visitor_requests WHERE id='" + id + "'"))
                .isEqualTo("rejected");
        assertThat(scalar("SELECT purpose FROM vms.visitor_requests WHERE id='" + id + "'"))
                .isNotEqualTo("Sneaky");
    }

    @Test
    @DisplayName("AC-4: an approved request cannot be amended, though it can still be cancelled")
    void approvedIsAmendmentClosed() throws Exception {
        String id = submit(1);
        post("/api/v1/visitor-requests/" + id + "/approve", null, token("fmadmin"));

        assertThat(patch(id, "{\"purpose\":\"Changed after approval\"}", token("tenantuser"))
                .statusCode()).isEqualTo(409);
        assertThat(post("/api/v1/visitor-requests/" + id + "/cancel", null, token("tenantuser"))
                .statusCode()).isEqualTo(200);
    }

    // ---- AC-5: the race ----

    @Test
    @DisplayName("AC-5: an admin approving and a tenant cancelling at once leave exactly one "
            + "winner, and never a request that is both")
    void approveAndCancelRace() throws Exception {
        String id = submit(1);
        String fm = token("fmadmin");
        String tenant = token("tenantuser");

        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> results = List.of(
                    pool.submit(() -> race(go, "/api/v1/visitor-requests/" + id + "/approve", fm)),
                    pool.submit(() -> race(go, "/api/v1/visitor-requests/" + id + "/cancel",
                            tenant)));
            go.countDown();

            List<Integer> codes = List.of(results.get(0).get(30, TimeUnit.SECONDS),
                    results.get(1).get(30, TimeUnit.SECONDS));
            assertThat(codes).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }

        // Whichever won, the request is in exactly one of the two states — never a blend, and never
        // carrying the other's side effects.
        String status = scalar("SELECT status::text FROM vms.visitor_requests WHERE id='" + id
                + "'");
        assertThat(status).isIn("approved", "cancelled");

        long approvedEvents = Long.parseLong(scalar("SELECT count(*) FROM vms.domain_events"
                + " WHERE aggregate_id='" + id + "' AND event_type='VisitorRequestApproved'"));
        long cancelledEvents = Long.parseLong(scalar("SELECT count(*) FROM vms.domain_events"
                + " WHERE aggregate_id='" + id + "' AND event_type='VisitorRequestCancelled'"));
        assertThat(approvedEvents + cancelledEvents).isEqualTo(1);
        assertThat(approvedEvents).isEqualTo("approved".equals(status) ? 1 : 0);
        // The loser's audit entry rolled back with its transaction.
        assertThat(scalar("SELECT count(*) FROM vms.audit_logs WHERE entity_id='" + id
                + "' AND action IN ('visitor_request.approve','visitor_request.cancel')"))
                .isEqualTo("1");
    }

    private int race(CountDownLatch go, String path, String bearer) throws Exception {
        go.await();
        return post(path, null, bearer).statusCode();
    }

    // ---- AC-6 ----

    @Test
    @DisplayName("AC-6: another tenant's request is 404 for both routes, identical to a missing id")
    void foreignRequestIsNotFound() throws Exception {
        String theirs = submitAs("tenantbuser", hostB, 1);
        String neverExisted = UUID.randomUUID().toString();
        String bearer = token("tenantuser");

        var foreignCancel = post("/api/v1/visitor-requests/" + theirs + "/cancel", null, bearer);
        var missingCancel = post("/api/v1/visitor-requests/" + neverExisted + "/cancel", null,
                bearer);
        var foreignAmend = patch(theirs, "{\"purpose\":\"Not mine\"}", bearer);

        assertThat(foreignCancel.statusCode()).isEqualTo(404);
        assertThat(foreignAmend.statusCode()).isEqualTo(404);
        // Not 403: confirming the difference would be confirming the request exists.
        assertThat(foreignCancel.body()).isEqualTo(missingCancel.body());

        assertThat(scalar("SELECT status::text FROM vms.visitor_requests WHERE id='" + theirs + "'"))
                .isEqualTo("submitted");

        // AC-6: the probe leaves a trail even though the response tells the caller nothing. This is
        // the only record that an id belonging to someone else was reached for.
        assertThat(scalar("""
                SELECT count(*) FROM vms.audit_logs
                WHERE action='authorization.denied'
                  AND after_state::text LIKE '%visitor_request.object%'""")).isNotEqualTo("0");
    }

    @Test
    @DisplayName("unauthenticated amend and cancel are both 401")
    void unauthenticated() throws Exception {
        String id = UUID.randomUUID().toString();
        assertThat(post("/api/v1/visitor-requests/" + id + "/cancel", null, null).statusCode())
                .isEqualTo(401);
        assertThat(patch(id, "{\"purpose\":\"x\"}", null).statusCode()).isEqualTo(401);
    }

    // ---- helpers ----

    private String submit(int visitors) throws Exception {
        return submitAs("tenantuser", hostA, visitors);
    }

    private String submitAs(String username, UUID host, int visitors) throws Exception {
        StringBuilder people = new StringBuilder();
        for (int i = 0; i < visitors; i++) {
            people.append(i == 0 ? "" : ",").append("{\"fullName\":\"Guest ").append(i).append("\"}");
        }
        Instant from = Instant.now().plus(30, ChronoUnit.DAYS);
        var res = post("/api/v1/visitor-requests",
                "{\"hostId\":\"" + host + "\",\"scheduledFrom\":\"" + from + "\","
                        + "\"scheduledTo\":\"" + from.plus(2, ChronoUnit.HOURS) + "\","
                        + "\"purpose\":\"Quarterly review\",\"visitors\":[" + people + "]}",
                token(username));
        assertThat(res.statusCode()).isEqualTo(201);
        return field(res.body(), "id");
    }

    private String token(String username) throws Exception {
        String password = switch (username) {
            case "tenantuser" -> "tenant-password-1234";
            case "tenantbuser" -> "tenantb-password-1234";
            default -> "fm-admin-password-123";
        };
        String body = post("/api/v1/auth/login",
                "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}",
                null).body();
        int i = body.indexOf("\"accessToken\":\"") + 15;
        return body.substring(i, body.indexOf('"', i));
    }

    private HttpResponse<String> patch(String id, String body, String bearer) throws Exception {
        return send("PATCH", "/api/v1/visitor-requests/" + id, body, bearer);
    }

    private HttpResponse<String> post(String path, String body, String bearer) throws Exception {
        return send("POST", path, body, bearer);
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        return send("GET", path, null, bearer);
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
            hostA2 = UUID.randomUUID();
            hostB = UUID.randomUUID();
            s.execute("INSERT INTO vms.hosts (id, tenant_id, full_name, is_active) VALUES ('"
                    + hostA + "','" + tenantA + "','Host A', true)");
            s.execute("INSERT INTO vms.hosts (id, tenant_id, full_name, is_active) VALUES ('"
                    + hostA2 + "','" + tenantA + "','Host A2', true)");
            s.execute("INSERT INTO vms.hosts (id, tenant_id, full_name, is_active) VALUES ('"
                    + hostB + "','" + tenantB + "','Host B', true)");

            String tenantRole = single(s, "SELECT id FROM vms.roles WHERE code='TENANT'");
            String fmRole = single(s, "SELECT id FROM vms.roles WHERE code='FM_ADMIN'");
            user(s, hasher, "tenantuser", "tenant-password-1234", tenantRole, tenantA.toString());
            user(s, hasher, "tenantbuser", "tenantb-password-1234", tenantRole, tenantB.toString());
            user(s, hasher, "fmadmin", "fm-admin-password-123", fmRole, null);
        }
    }

    private static void user(Statement s, Pbkdf2PasswordHasher hasher, String username,
                             String password, String roleId, String tenantId) throws Exception {
        s.execute("INSERT INTO vms.users (id, username, email, full_name, password_hash, role_id,"
                + " tenant_id, is_active) VALUES ('" + UUID.randomUUID() + "','" + username + "','"
                + username + "@ex.com','" + username + "','"
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
