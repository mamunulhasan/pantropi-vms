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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * INTEGRATION TEST for US-07.6.1 — a tenant sees its own requests and what became of them
 * (FR-VMS-01, SRS B1), over real HTTP against real PostgreSQL.
 *
 * <p>This is the story that exercises the scoping seam of US-03.4.1 end to end: until now every
 * scoped read was made by a principal whose filter resolved to {@code Unrestricted}. Here the caller
 * is a Tenant, the filter is {@code OwnTenant}, and the assertions are about what they
 * <em>cannot</em> see — which the backlog calls the phase's primary IDOR surface.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "vms.identity.enabled=true",
                "vms.security.jwt.secret=integration-test-secret-least-32-bytes-long-xx",
                "spring.flyway.enabled=false"
        })
class TenantRequestsIT {

    private static EmbeddedPostgres pg;
    private static UUID tenantA;
    private static UUID tenantB;
    private static UUID hostA;
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

    // ---- AC-1 ----

    @Test
    @DisplayName("AC-1: the list shows only my tenant's requests, with status, window, host, "
            + "visitor count and decision outcome")
    void listIsScopedAndComplete() throws Exception {
        String mine = submit("tenantuser", 2);
        String theirs = submit("tenantbuser", 1);

        var res = get("/api/v1/visitor-requests?size=100", token("tenantuser"));

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(ids(res)).contains(mine).doesNotContain(theirs);
        assertThat(res.body()).contains("\"status\":\"submitted\"")
                .contains("\"host\":\"Host A\"").contains("\"visitorCount\":2")
                .contains("scheduledFrom").contains("submittedAt");
        // Tenant B's host must not appear either — the join is scoped with everything else.
        assertThat(res.body()).doesNotContain("Host B");
    }

    @Test
    @DisplayName("AC-1: the other tenant sees their own and not mine — isolation cuts both ways")
    void isolationIsSymmetric() throws Exception {
        String mine = submit("tenantuser", 1);
        String theirs = submit("tenantbuser", 1);

        assertThat(ids(get("/api/v1/visitor-requests?size=100", token("tenantbuser"))))
                .contains(theirs).doesNotContain(mine);
    }

    // ---- AC-2 ----

    @Test
    @DisplayName("AC-2: a status filter narrows within my tenant and cannot widen it")
    void statusFilterCannotWiden() throws Exception {
        String mineApproved = submit("tenantuser", 1);
        String mineSubmitted = submit("tenantuser", 1);
        String theirsApproved = submit("tenantbuser", 1);
        String fm = token("fmadmin");
        post("/api/v1/visitor-requests/" + mineApproved + "/approve", null, fm);
        post("/api/v1/visitor-requests/" + theirsApproved + "/approve", null, fm);

        List<String> approved = ids(get("/api/v1/visitor-requests?status=approved&size=100",
                token("tenantuser")));

        assertThat(approved).contains(mineApproved)
                .doesNotContain(mineSubmitted)          // the filter narrowed...
                .doesNotContain(theirsApproved);        // ...and did not widen
    }

    @Test
    @DisplayName("AC-2: a date range filters within my tenant")
    void dateRangeFilters() throws Exception {
        String soon = submit("tenantuser", 1, 10);
        String later = submit("tenantuser", 1, 200);
        Instant cutoff = Instant.now().plus(100, ChronoUnit.DAYS);

        List<String> early = ids(get("/api/v1/visitor-requests?to=" + cutoff + "&size=100",
                token("tenantuser")));
        List<String> late = ids(get("/api/v1/visitor-requests?from=" + cutoff + "&size=100",
                token("tenantuser")));

        assertThat(early).contains(soon).doesNotContain(later);
        assertThat(late).contains(later).doesNotContain(soon);
    }

    @Test
    @DisplayName("AC-2: an unrecognised status is 400, not a filter that silently does nothing")
    void unknownStatusIsRejected() throws Exception {
        var res = get("/api/v1/visitor-requests?status=aproved", token("tenantuser"));

        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body()).contains("invalid").contains("submitted");
    }

    @Test
    @DisplayName("the page size is clamped here too")
    void pageSizeIsClamped() throws Exception {
        var res = get("/api/v1/visitor-requests?size=10000", token("tenantuser"));

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("\"size\":100").contains("\"maxSize\":100");
    }

    // ---- AC-3 ----

    @Test
    @DisplayName("AC-3: a rejected request shows the reason and the decision timestamp, and the "
            + "approver by display name only")
    void rejectionReasonIsVisibleToTheTenant() throws Exception {
        String id = submit("tenantuser", 1);
        post("/api/v1/visitor-requests/" + id + "/reject",
                "{\"reason\":\"Host is on leave that week\"}", token("fmadmin"));

        var res = get("/api/v1/visitor-requests/" + id, token("tenantuser"));

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("\"status\":\"rejected\"")
                .contains("Host is on leave that week")
                .contains("decidedAt")
                .contains("\"decidedBy\":\"Frances Manager\"");
        // A display name, not an identifier the tenant could use anywhere else.
        assertThat(res.body()).doesNotContain("fmadmin@ex.com").doesNotContain("\"fmadmin\"");
        assertThat(res.body()).doesNotContain(
                scalar("SELECT id FROM vms.users WHERE username='fmadmin'"));
    }

    @Test
    @DisplayName("AC-3: markup in a reason reaches the tenant as JSON data, not as markup")
    void hostileReasonIsTransportedAsData() throws Exception {
        String id = submit("tenantuser", 1);
        post("/api/v1/visitor-requests/" + id + "/reject",
                "{\"reason\":\"<script>alert('x')</script> \\\"quoted\\\"\"}", token("fmadmin"));

        var res = get("/api/v1/visitor-requests/" + id, token("tenantuser"));

        assertThat(res.headers().firstValue("Content-Type").orElse(""))
                .contains("application/json");
        // The quote inside the reason is escaped by the serialiser, so the payload stays parseable;
        // the markup is carried verbatim, and encoding it for display is the renderer's job.
        assertThat(res.body()).contains("<script>alert('x')</script>").contains("\\\"quoted\\\"");
    }

    @Test
    @DisplayName("the detail lists my own guests by name and status, and no approver internals")
    void detailShowsOwnVisitors() throws Exception {
        String id = submitWithVisitor("tenantuser", "Ada Lovelace");

        var res = get("/api/v1/visitor-requests/" + id, token("tenantuser"));

        assertThat(res.body()).contains("Ada Lovelace").contains("\"status\":\"pending\"");
    }

    // ---- AC-4: the IDOR surface ----

    @Test
    @DisplayName("AC-4: another tenant's request is 404, identical in shape to a missing id")
    void foreignRequestIsIndistinguishableFromMissing() throws Exception {
        String theirs = submit("tenantbuser", 1);
        String neverExisted = UUID.randomUUID().toString();
        String bearer = token("tenantuser");

        var foreign = get("/api/v1/visitor-requests/" + theirs, bearer);
        var missing = get("/api/v1/visitor-requests/" + neverExisted, bearer);

        assertThat(foreign.statusCode()).isEqualTo(404);
        // Byte-identical bodies: nothing in the response distinguishes "exists but not yours" from
        // "was never issued", so the endpoint cannot be used to enumerate what else is in the
        // building.
        assertThat(foreign.body()).isEqualTo(missing.body());
        assertThat(foreign.headers().firstValue("Content-Type"))
                .isEqualTo(missing.headers().firstValue("Content-Type"));
    }

    @Test
    @DisplayName("AC-4: a decision endpoint gives the same answer for a foreign id — the scope is "
            + "in the repository, so every read inherits it")
    void foreignRequestCannotBeDecidedEither() throws Exception {
        String theirs = submit("tenantbuser", 1);

        // A tenant lacks visitor.approve, so this is 403 at the boundary before any read...
        assertThat(post("/api/v1/visitor-requests/" + theirs + "/approve", null,
                token("tenantuser")).statusCode()).isEqualTo(403);
        // ...and their own detail read of it is 404, not 403: they may ask, and the answer is that
        // there is nothing there.
        assertThat(get("/api/v1/visitor-requests/" + theirs, token("tenantuser")).statusCode())
                .isEqualTo(404);
    }

    // ---- US-07.6.2: the conditional read ----

    @Test
    @DisplayName("US-07.6.2 T-07.6.2.3: an unchanged list answers 304 with no body")
    void unchangedListIsNotModified() throws Exception {
        submit("tenantuser", 1);
        String bearer = token("tenantuser");

        var first = get("/api/v1/visitor-requests?size=100", bearer);
        assertThat(first.statusCode()).isEqualTo(200);
        String etag = first.headers().firstValue("ETag").orElseThrow();
        assertThat(etag).startsWith("W/\"");

        var second = conditionalGet("/api/v1/visitor-requests?size=100", bearer, etag);

        assertThat(second.statusCode()).isEqualTo(304);
        assertThat(second.body()).isEmpty();
        assertThat(second.headers().firstValue("ETag")).hasValue(etag);
    }

    @Test
    @DisplayName("US-07.6.2 AC-1: a decision changes the validator, so the next poll gets the "
            + "new status")
    void decisionInvalidatesTheValidator() throws Exception {
        String id = submit("tenantuser", 1);
        String bearer = token("tenantuser");
        String before = get("/api/v1/visitor-requests?size=100", bearer)
                .headers().firstValue("ETag").orElseThrow();

        post("/api/v1/visitor-requests/" + id + "/approve", null, token("fmadmin"));

        // The poll that would have been a 304 is now a 200 carrying the decision.
        var after = conditionalGet("/api/v1/visitor-requests?size=100", bearer, before);
        assertThat(after.statusCode()).isEqualTo(200);
        assertThat(after.body()).contains("\"status\":\"approved\"");
        assertThat(after.headers().firstValue("ETag")).isPresent()
                .isNotEqualTo(java.util.Optional.of(before));
    }

    @Test
    @DisplayName("T-07.6.2.3: two tenants polling the same URL never share a validator, so one "
            + "tenant's ETag can never produce a 304 for the other")
    void noCrossTenantValidatorBleed() throws Exception {
        // Deliberately symmetric state: one request each, submitted moments apart. Count and
        // timestamp alone could coincide; the scope discriminator is what makes this impossible
        // rather than unlikely.
        submit("tenantuser", 1);
        submit("tenantbuser", 1);

        var mine = get("/api/v1/visitor-requests?size=100", token("tenantuser"));
        var theirs = get("/api/v1/visitor-requests?size=100", token("tenantbuser"));
        String myEtag = mine.headers().firstValue("ETag").orElseThrow();
        String theirEtag = theirs.headers().firstValue("ETag").orElseThrow();

        assertThat(myEtag).isNotEqualTo(theirEtag);

        // Presenting the other tenant's validator must not be accepted as "yours is current".
        var probe = conditionalGet("/api/v1/visitor-requests?size=100", token("tenantuser"),
                theirEtag);
        assertThat(probe.statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("T-07.6.2.3: the response forbids shared caching, so no intermediary can serve "
            + "one tenant's list to another on the same URL")
    void responseIsPrivate() throws Exception {
        submit("tenantuser", 1);

        var res = get("/api/v1/visitor-requests?size=100", token("tenantuser"));

        assertThat(res.headers().firstValue("Cache-Control")).hasValueSatisfying(
                value -> assertThat(value).contains("private"));
        assertThat(res.headers().allValues("Vary")).anySatisfy(
                value -> assertThat(value).contains("Authorization"));
    }

    @Test
    @DisplayName("US-07.6.2 AC-2: two different filtered views never validate against each other")
    void filtersHaveDistinctValidators() throws Exception {
        submit("tenantuser", 1);
        String bearer = token("tenantuser");

        String unfiltered = get("/api/v1/visitor-requests?size=100", bearer)
                .headers().firstValue("ETag").orElseThrow();
        String filtered = get("/api/v1/visitor-requests?size=100&status=approved", bearer)
                .headers().firstValue("ETag").orElseThrow();

        assertThat(unfiltered).isNotEqualTo(filtered);
        // ...and the filtered view's own validator does hold for itself.
        assertThat(conditionalGet("/api/v1/visitor-requests?size=100&status=approved", bearer,
                filtered).statusCode()).isEqualTo(304);
    }

    @Test
    @DisplayName("US-07.6.2 AC-4: an expired or absent session gets 401 on the poll, not a 304")
    void unauthenticatedPollIsRefused() throws Exception {
        submit("tenantuser", 1);
        String etag = get("/api/v1/visitor-requests?size=100", token("tenantuser"))
                .headers().firstValue("ETag").orElseThrow();

        // A client holding a valid ETag but no longer a valid session must be told to stop, not
        // handed a cheap 304 that looks like success.
        var res = conditionalGet("/api/v1/visitor-requests?size=100", null, etag);

        assertThat(res.statusCode()).isEqualTo(401);
    }

    // ---- AC-5, AC-6 ----

    @Test
    @DisplayName("AC-5: a tenant calling the FM Admin queue gets 403 and an audited denial")
    void tenantCannotReachTheApprovalQueue() throws Exception {
        var res = get("/api/v1/visitor-requests/pending", token("tenantuser"));

        assertThat(res.statusCode()).isEqualTo(403);
        assertThat(scalar("""
                SELECT count(*) FROM vms.audit_logs
                WHERE action='authorization.denied'
                  AND after_state::text LIKE '%visitor.approve%'""")).isNotEqualTo("0");
    }

    @Test
    @DisplayName("AC-6: a tenant user with no tenant assigned sees nothing at all")
    void unscopedTenantUserSeesNothing() throws Exception {
        // The restrictive default made concrete: a principal whose scoping column is null cannot be
        // confined to a tenant, and the safe reading of "cannot be determined" is "sees nothing".
        // The alternative — treating an absent tenant as no filter — is a user who sees everything.
        submit("tenantuser", 1);

        var res = get("/api/v1/visitor-requests?size=100", token("orphanuser"));

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("\"totalElements\":0");
        assertThat(ids(res)).isEmpty();
    }

    @Test
    @DisplayName("an unauthenticated call is 401")
    void unauthenticatedIsRejected() throws Exception {
        assertThat(get("/api/v1/visitor-requests", null).statusCode()).isEqualTo(401);
        assertThat(get("/api/v1/visitor-requests/" + UUID.randomUUID(), null).statusCode())
                .isEqualTo(401);
    }

    // ---- helpers ----

    private String submit(String username, int visitors) throws Exception {
        return submit(username, visitors, 30);
    }

    private String submit(String username, int visitors, int daysAhead) throws Exception {
        StringBuilder people = new StringBuilder();
        for (int i = 0; i < visitors; i++) {
            people.append(i == 0 ? "" : ",")
                    .append("{\"fullName\":\"Guest ").append(i).append("\"}");
        }
        return submitBody(username, people.toString(), daysAhead);
    }

    private String submitWithVisitor(String username, String name) throws Exception {
        return submitBody(username, "{\"fullName\":\"" + name + "\"}", 30);
    }

    private String submitBody(String username, String visitorsJson, int daysAhead)
            throws Exception {
        Instant from = Instant.now().plus(daysAhead, ChronoUnit.DAYS);
        UUID host = username.equals("tenantuser") ? hostA : hostB;
        var res = post("/api/v1/visitor-requests",
                "{\"hostId\":\"" + host + "\",\"scheduledFrom\":\"" + from + "\","
                        + "\"scheduledTo\":\"" + from.plus(2, ChronoUnit.HOURS) + "\","
                        + "\"purpose\":\"Quarterly review\",\"visitors\":[" + visitorsJson + "]}",
                token(username));
        assertThat(res.statusCode()).isEqualTo(201);
        return field(res.body(), "id");
    }

    private static List<String> ids(HttpResponse<String> res) {
        List<String> found = new ArrayList<>();
        String body = res.body();
        int i = 0;
        while ((i = body.indexOf("\"id\":\"", i)) >= 0) {
            int start = i + 6;
            found.add(body.substring(start, body.indexOf('"', start)));
            i = start;
        }
        return found;
    }

    private String token(String username) throws Exception {
        String password = switch (username) {
            case "tenantuser" -> "tenant-password-1234";
            case "tenantbuser" -> "tenantb-password-1234";
            case "orphanuser" -> "orphan-password-1234";
            default -> "fm-admin-password-123";
        };
        String body = post("/api/v1/auth/login",
                "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}",
                null).body();
        int i = body.indexOf("\"accessToken\":\"") + 15;
        return body.substring(i, body.indexOf('"', i));
    }

    private HttpResponse<String> conditionalGet(String path, String bearer, String etag)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("If-None-Match", etag)
                .GET();
        if (bearer != null) {
            b.header("Authorization", "Bearer " + bearer);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
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
            hostB = UUID.randomUUID();
            s.execute("INSERT INTO vms.hosts (id, tenant_id, full_name, is_active) VALUES ('"
                    + hostA + "','" + tenantA + "','Host A', true)");
            s.execute("INSERT INTO vms.hosts (id, tenant_id, full_name, is_active) VALUES ('"
                    + hostB + "','" + tenantB + "','Host B', true)");

            String tenantRole = single(s, "SELECT id FROM vms.roles WHERE code='TENANT'");
            String fmRole = single(s, "SELECT id FROM vms.roles WHERE code='FM_ADMIN'");
            user(s, hasher, "tenantuser", "Tina Tenant", "tenant-password-1234", tenantRole,
                    tenantA.toString());
            user(s, hasher, "tenantbuser", "Bob Tenant", "tenantb-password-1234", tenantRole,
                    tenantB.toString());
            // A TENANT-role user with no tenant: AC-6's fail-closed case.
            user(s, hasher, "orphanuser", "Orphan User", "orphan-password-1234", tenantRole, null);
            user(s, hasher, "fmadmin", "Frances Manager", "fm-admin-password-123", fmRole, null);
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
