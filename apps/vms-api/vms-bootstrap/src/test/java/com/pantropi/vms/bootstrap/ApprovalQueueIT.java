package com.pantropi.vms.bootstrap;

import com.pantropi.vms.application.identity.port.ScopeContext;
import com.pantropi.vms.application.identity.usecase.ScopePolicy;
import com.pantropi.vms.application.shared.PageRequest;
import com.pantropi.vms.application.visitor.port.ApprovalQueueStore;
import com.pantropi.vms.domain.visitor.RequestStatus;
import com.pantropi.vms.infrastructure.identity.Pbkdf2PasswordHasher;
import com.pantropi.vms.infrastructure.visitor.JdbcApprovalQueueStore;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * INTEGRATION TEST for US-07.3.1 — the FM Admin approval queue (FR-VMS-02, SRS B1), over real HTTP
 * against real PostgreSQL.
 *
 * <p>AC-6 is the one that needed a real database rather than a fake: it asks for a test that swaps in
 * a restrictive scoping strategy and <em>observes a filtered result</em>. That is done at the bottom
 * by building the store against a policy in the restrictive posture and running the same query.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "vms.identity.enabled=true",
                "vms.security.jwt.secret=integration-test-secret-least-32-bytes-long-xx",
                "spring.flyway.enabled=false"
        })
class ApprovalQueueIT {

    private static EmbeddedPostgres pg;
    private static UUID tenantA;
    private static UUID tenantB;
    private static UUID hostA;

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
    @DisplayName("AC-1: the queue lists submitted requests with tenant, host, window, visitor "
            + "count and submission time, newest first")
    void queueShowsWhatAnApproverNeeds() throws Exception {
        String older = submitAs("tenantuser", 1);
        Thread.sleep(10);                       // distinct created_at, so "newest first" is testable
        String newer = submitAs("tenantuser", 3);

        var res = get("/api/v1/visitor-requests/pending",
                token("fmadmin", "fm-admin-password-123"));

        assertThat(res.statusCode()).isEqualTo(200);
        String body = res.body();
        assertThat(body).contains(newer).contains(older);
        assertThat(body.indexOf(newer)).as("newest first").isLessThan(body.indexOf(older));

        assertThat(body).contains("\"tenant\":\"Tenant A\"").contains("\"host\":\"Host A\"");
        assertThat(body).contains("\"visitorCount\":3").contains("\"visitorCount\":1");
        assertThat(body).contains("scheduledFrom").contains("submittedAt");
    }

    @Test
    @DisplayName("the projection carries no visitor contact detail — it is never selected")
    void queueCarriesNoVisitorPii() throws Exception {
        String email = "pii-" + UUID.randomUUID() + "@example.test";
        submitWithVisitor("tenantuser", "Ada Lovelace", email, "+880100000000");

        var res = get("/api/v1/visitor-requests/pending",
                token("fmadmin", "fm-admin-password-123"));

        assertThat(res.body()).doesNotContain(email).doesNotContain("880100000000")
                .doesNotContain("Ada Lovelace");
    }

    // ---- AC-2, AC-5 ----

    @Test
    @DisplayName("AC-2: paging is server-side, ordering is stable, and no row is seen twice or "
            + "missed across pages")
    void pagingIsStable() throws Exception {
        // Submitted in one loop, so several share a created_at to the millisecond — which is
        // precisely when an unstable sort starts dropping and repeating rows between pages.
        for (int i = 0; i < 7; i++) {
            submitAs("tenantuser", 1);
        }
        String bearer = token("fmadmin", "fm-admin-password-123");

        List<String> firstPage = ids(get("/api/v1/visitor-requests/pending?page=0&size=3", bearer));
        List<String> secondPage = ids(get("/api/v1/visitor-requests/pending?page=1&size=3", bearer));
        List<String> againFirst = ids(get("/api/v1/visitor-requests/pending?page=0&size=3", bearer));

        assertThat(firstPage).hasSize(3);
        assertThat(secondPage).hasSize(3);
        assertThat(firstPage).doesNotContainAnyElementsOf(secondPage);
        assertThat(againFirst).containsExactlyElementsOf(firstPage);   // repeatable ordering
    }

    @Test
    @DisplayName("AC-5: a page size of 10,000 is clamped to the server maximum, and the response "
            + "says what was applied")
    void oversizedPageIsClamped() throws Exception {
        var res = get("/api/v1/visitor-requests/pending?size=10000",
                token("fmadmin", "fm-admin-password-123"));

        assertThat(res.statusCode()).isEqualTo(200);      // clamped, not refused
        assertThat(res.body()).contains("\"size\":" + PageRequest.MAX_SIZE)
                .contains("\"maxSize\":" + PageRequest.MAX_SIZE);
    }

    // ---- AC-3 ----

    @Test
    @DisplayName("AC-3: a request leaves the queue once it is approved, rejected or cancelled")
    void decidedRequestsLeaveTheQueue() throws Exception {
        String approved = submitAs("tenantuser", 1);
        String rejected = submitAs("tenantuser", 1);
        String bearer = token("fmadmin", "fm-admin-password-123");

        assertThat(ids(get("/api/v1/visitor-requests/pending?size=100", bearer)))
                .contains(approved, rejected);

        post("/api/v1/visitor-requests/" + approved + "/approve", null, bearer);
        post("/api/v1/visitor-requests/" + rejected + "/reject",
                "{\"reason\":\"Host on leave\"}", bearer);
        // Cancellation has no endpoint yet (US-07.1.3); the queue filters on status, so this proves
        // the same rule for the third case without waiting for that story.
        String cancelled = submitAs("tenantuser", 1);
        execute("UPDATE vms.visitor_requests SET status='cancelled' WHERE id='" + cancelled + "'");

        assertThat(ids(get("/api/v1/visitor-requests/pending?size=100", bearer)))
                .doesNotContain(approved).doesNotContain(rejected).doesNotContain(cancelled);
    }

    // ---- AC-4 ----

    @Test
    @DisplayName("AC-4: a Tenant calling the dashboard endpoint directly gets 403 and an audited "
            + "denial — it is never merely hidden from the navigation")
    void tenantIsForbidden() throws Exception {
        var res = get("/api/v1/visitor-requests/pending",
                token("tenantuser", "tenant-password-1234"));

        assertThat(res.statusCode()).isEqualTo(403);
        assertThat(scalar("""
                SELECT count(*) FROM vms.audit_logs
                WHERE action='authorization.denied'
                  AND after_state::text LIKE '%visitor.approve%'""")).isNotEqualTo("0");
    }

    @Test
    @DisplayName("an unauthenticated call is 401")
    void unauthenticatedIsRejected() throws Exception {
        assertThat(get("/api/v1/visitor-requests/pending", null).statusCode()).isEqualTo(401);
    }

    // ---- US-07.3.2: filtering and searching ----

    @Test
    @DisplayName("US-07.3.2 AC-1: tenant, date range and status filters combine conjunctively")
    void filtersCombine() throws Exception {
        String mineSoon = submitAs("tenantuser", 1);
        String mineLater = submitAs("tenantuser", 1, 200);
        String theirs = submitAs("tenantbuser", 1);
        String bearer = token("fmadmin", "fm-admin-password-123");
        Instant cutoff = Instant.now().plus(100, ChronoUnit.DAYS);

        // Tenant alone.
        assertThat(ids(get("/api/v1/visitor-requests/pending?size=100&tenantId=" + tenantA, bearer)))
                .contains(mineSoon, mineLater).doesNotContain(theirs);

        // Tenant AND range: conjunctive, so the later one drops out too.
        assertThat(ids(get("/api/v1/visitor-requests/pending?size=100&tenantId=" + tenantA
                + "&to=" + cutoff, bearer)))
                .contains(mineSoon).doesNotContain(mineLater).doesNotContain(theirs);
    }

    @Test
    @DisplayName("US-07.3.2 AC-1: the queue is submitted-only by default, and a status is an "
            + "explicit opt-in")
    void statusIsAnOptIn() throws Exception {
        String decided = submitAs("tenantuser", 1);
        String bearer = token("fmadmin", "fm-admin-password-123");
        post("/api/v1/visitor-requests/" + decided + "/approve", null, bearer);

        // US-07.3.1 AC-3 still holds: loading the queue does not show it.
        assertThat(ids(get("/api/v1/visitor-requests/pending?size=100", bearer)))
                .doesNotContain(decided);
        // ...and asking for approved requests is a different question, which the filter answers.
        assertThat(ids(get("/api/v1/visitor-requests/pending?size=100&status=approved", bearer)))
                .contains(decided);
    }

    @Test
    @DisplayName("US-07.3.2 AC-2: searching by visitor or host name is case-insensitive")
    void searchByName() throws Exception {
        String withAda = submitNamed("tenantuser", "Ada Lovelace");
        String withGrace = submitNamed("tenantuser", "Grace Hopper");
        String bearer = token("fmadmin", "fm-admin-password-123");

        assertThat(ids(get("/api/v1/visitor-requests/pending?size=100&search=lovelace", bearer)))
                .contains(withAda).doesNotContain(withGrace);
        assertThat(ids(get("/api/v1/visitor-requests/pending?size=100&search=LOVELACE", bearer)))
                .contains(withAda);
        // The host name is searchable too — both belong to Host A.
        assertThat(ids(get("/api/v1/visitor-requests/pending?size=100&search=host%20a", bearer)))
                .contains(withAda, withGrace);
    }

    @Test
    @DisplayName("US-07.3.2 AC-2: a searchable visitor name is still never returned")
    void searchDoesNotWidenTheProjection() throws Exception {
        String id = submitNamed("tenantuser", "Ada Lovelace");

        var res = get("/api/v1/visitor-requests/pending?size=100&search=Lovelace",
                token("fmadmin", "fm-admin-password-123"));

        // Searchable as an input, absent as an output: the queue lists counts, not people.
        assertThat(ids(res)).contains(id);
        assertThat(res.body()).doesNotContain("Ada Lovelace").doesNotContain("Lovelace");
    }

    @Test
    @DisplayName("US-07.3.2 AC-4: LIKE metacharacters are escaped, so a wildcard is literal text")
    void wildcardsAreEscaped() throws Exception {
        String literal = submitNamed("tenantuser", "100% Cotton");
        String other = submitNamed("tenantuser", "Ada Lovelace");
        String bearer = token("fmadmin", "fm-admin-password-123");

        // Unescaped, "%" alone would match every row. Escaped, it matches the one name with a
        // percent sign in it.
        assertThat(ids(get("/api/v1/visitor-requests/pending?size=100&search=%25", bearer)))
                .contains(literal).doesNotContain(other);
        assertThat(ids(get("/api/v1/visitor-requests/pending?size=100&search=100%25%20C", bearer)))
                .contains(literal);
        // "_" is the single-character wildcard; escaped, it finds nothing rather than everything.
        assertThat(ids(get("/api/v1/visitor-requests/pending?size=100&search=_", bearer))).isEmpty();
    }

    @Test
    @DisplayName("US-07.3.2 AC-4: an injection attempt is data, not SQL")
    void injectionIsJustText() throws Exception {
        submitNamed("tenantuser", "Ada Lovelace");
        String bearer = token("fmadmin", "fm-admin-password-123");

        var res = get("/api/v1/visitor-requests/pending?size=100"
                + "&search=%27%20OR%201%3D1%20--", bearer);

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(ids(res)).isEmpty();     // it matched nothing, which is what a name search does
        assertThat(scalar("SELECT count(*) FROM vms.visitor_requests")).isNotEqualTo("0");
    }

    @Test
    @DisplayName("T-07.3.2.2: an unknown status and an inverted range are both 400")
    void invalidFiltersAreRefused() throws Exception {
        String bearer = token("fmadmin", "fm-admin-password-123");
        Instant from = Instant.now().plus(60, ChronoUnit.DAYS);

        var badStatus = get("/api/v1/visitor-requests/pending?status=aproved", bearer);
        assertThat(badStatus.statusCode()).isEqualTo(400);
        assertThat(badStatus.body()).contains("submitted");
        // The message says what was expected, not what was sent — a filter value must not be
        // reflected back where it could reach a log.
        assertThat(badStatus.body()).doesNotContain("aproved");

        var badRange = get("/api/v1/visitor-requests/pending?from=" + from
                + "&to=" + from.minus(1, ChronoUnit.DAYS), bearer);
        assertThat(badRange.statusCode()).isEqualTo(400);

        var longSearch = get("/api/v1/visitor-requests/pending?search=" + "x".repeat(101), bearer);
        assertThat(longSearch.statusCode()).isEqualTo(400);
    }

    @Test
    @DisplayName("US-07.3.2 AC-5: a filter for a tenant outside my scope returns nothing")
    void filterCannotReachOutsideScope() throws Exception {
        submitAs("tenantuser", 1);          // tenant A
        submitAs("tenantbuser", 1);         // tenant B
        JdbcTemplate jdbc = new JdbcTemplate(pg.getPostgresDatabase());
        UUID fmAdmin = UUID.fromString(scalar("SELECT id FROM vms.users WHERE username='fmadmin'"));

        // An approver confined to tenant A, asking explicitly for tenant B.
        ApprovalQueueStore confined = new JdbcApprovalQueueStore(jdbc,
                new ScopePolicy(fixedScope(fmAdmin, "FM_ADMIN", tenantA),
                        ScopePolicy.Posture.OWN_SCOPE));

        var page = confined.pending(new ApprovalQueueStore.Filter(RequestStatus.SUBMITTED, tenantB,
                null, null, null, new PageRequest(0, 100)));

        // The scope predicate is applied first, so the filter narrows an already-empty set rather
        // than selecting from somebody else's.
        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isZero();
    }

    // ---- AC-6: swap the strategy, observe a filtered result ----

    @Test
    @DisplayName("AC-6: swapping in the restrictive posture filters the queue with no change to "
            + "the query-building code")
    void restrictivePostureFiltersTheQueue() throws Exception {
        submitAs("tenantuser", 1);          // tenant A
        submitAs("tenantbuser", 1);         // tenant B

        JdbcTemplate jdbc = new JdbcTemplate(pg.getPostgresDatabase());
        UUID fmAdmin = UUID.fromString(scalar("SELECT id FROM vms.users WHERE username='fmadmin'"));

        // Same store, same SQL, same principal — only the posture differs.
        ApprovalQueueStore buildingWide = new JdbcApprovalQueueStore(jdbc,
                new ScopePolicy(fixedScope(fmAdmin, "FM_ADMIN", null),
                        ScopePolicy.Posture.BUILDING_WIDE));
        ApprovalQueueStore ownTenantOnly = new JdbcApprovalQueueStore(jdbc,
                new ScopePolicy(fixedScope(fmAdmin, "FM_ADMIN", tenantA),
                        ScopePolicy.Posture.OWN_SCOPE));

        var everything = buildingWide.pending(new ApprovalQueueStore.Filter(RequestStatus.SUBMITTED, null, null, null,
                null, new PageRequest(0, 100)));
        var confined = ownTenantOnly.pending(new ApprovalQueueStore.Filter(RequestStatus.SUBMITTED, null, null, null,
                null, new PageRequest(0, 100)));

        assertThat(everything.content()).extracting(ApprovalQueueStore.PendingRequest::tenantId)
                .contains(tenantA, tenantB);
        assertThat(confined.content()).isNotEmpty()
                .allSatisfy(r -> assertThat(r.tenantId()).isEqualTo(tenantA));
        assertThat(confined.totalElements()).isLessThan(everything.totalElements());
    }

    @Test
    @DisplayName("AC-6: an approver with no scope at all sees nothing under the restrictive posture")
    void restrictivePostureFailsClosed() throws Exception {
        submitAs("tenantuser", 1);
        UUID fmAdmin = UUID.fromString(scalar("SELECT id FROM vms.users WHERE username='fmadmin'"));

        ApprovalQueueStore unscoped = new JdbcApprovalQueueStore(
                new JdbcTemplate(pg.getPostgresDatabase()),
                new ScopePolicy(fixedScope(fmAdmin, "FM_ADMIN", null),
                        ScopePolicy.Posture.OWN_SCOPE));

        var page = unscoped.pending(new ApprovalQueueStore.Filter(RequestStatus.SUBMITTED, null, null, null,
                null, new PageRequest(0, 100)));

        assertThat(page.content()).isEmpty();
        assertThat(page.totalElements()).isZero();
    }

    private static ScopeContext fixedScope(UUID user, String role, UUID tenant) {
        return () -> Optional.of(new ScopeContext.Scope(user, role, tenant, null));
    }

    // ---- helpers ----

    private String submitNamed(String username, String visitorName) throws Exception {
        return submitBody(username, "{\"fullName\":\"" + visitorName + "\"}", 30);
    }

    private String submitAs(String username, int visitors, int daysAhead) throws Exception {
        StringBuilder people = new StringBuilder();
        for (int i = 0; i < visitors; i++) {
            people.append(i == 0 ? "" : ",")
                    .append("{\"fullName\":\"Guest ").append(i).append("\"}");
        }
        return submitBody(username, people.toString(), daysAhead);
    }

    private String submitAs(String username, int visitors) throws Exception {
        StringBuilder people = new StringBuilder();
        for (int i = 0; i < visitors; i++) {
            people.append(i == 0 ? "" : ",")
                    .append("{\"fullName\":\"Guest ").append(i).append("\"}");
        }
        return submitBody(username, people.toString());
    }

    private String submitWithVisitor(String username, String name, String email, String phone)
            throws Exception {
        return submitBody(username, "{\"fullName\":\"" + name + "\",\"email\":\"" + email
                + "\",\"phone\":\"" + phone + "\"}");
    }

    private String submitBody(String username, String visitorsJson) throws Exception {
        return submitBody(username, visitorsJson, 30);
    }

    private String submitBody(String username, String visitorsJson, int daysAhead)
            throws Exception {
        Instant from = Instant.now().plus(daysAhead, ChronoUnit.DAYS);
        String host = username.equals("tenantuser") ? "\"hostId\":\"" + hostA + "\"," : "";
        String password = username.equals("tenantuser")
                ? "tenant-password-1234" : "tenantb-password-1234";
        var res = post("/api/v1/visitor-requests",
                "{" + host + "\"scheduledFrom\":\"" + from + "\",\"scheduledTo\":\""
                        + from.plus(2, ChronoUnit.HOURS) + "\",\"purpose\":\"Review\","
                        + "\"visitors\":[" + visitorsJson + "]}",
                token(username, password));
        assertThat(res.statusCode()).isEqualTo(201);
        return field(res.body(), "id");
    }

    /** Ids in response order — the ordering itself is what several assertions are about. */
    private static List<String> ids(HttpResponse<String> res) {
        List<String> found = new java.util.ArrayList<>();
        String body = res.body();
        int i = 0;
        while ((i = body.indexOf("\"id\":\"", i)) >= 0) {
            int start = i + 6;
            found.add(body.substring(start, body.indexOf('"', start)));
            i = start;
        }
        return found;
    }

    private String token(String u, String p) throws Exception {
        String body = post("/api/v1/auth/login",
                "{\"username\":\"" + u + "\",\"password\":\"" + p + "\"}", null).body();
        int i = body.indexOf("\"accessToken\":\"") + 15;
        return body.substring(i, body.indexOf('"', i));
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .GET();
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(body == null ? HttpRequest.BodyPublishers.noBody()
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
            tenantB = UUID.randomUUID();
            s.execute("INSERT INTO vms.tenants (id, code, name, floor_id) VALUES ('" + tenantA
                    + "','TA','Tenant A','" + floor + "')");
            s.execute("INSERT INTO vms.tenants (id, code, name, floor_id) VALUES ('" + tenantB
                    + "','TB','Tenant B','" + floor + "')");

            hostA = UUID.randomUUID();
            s.execute("INSERT INTO vms.hosts (id, tenant_id, full_name, is_active) VALUES ('"
                    + hostA + "','" + tenantA + "','Host A', true)");

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

    private static void execute(String sql) throws Exception {
        try (Connection c = pg.getPostgresDatabase().getConnection();
             Statement s = c.createStatement()) {
            s.execute(sql);
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
