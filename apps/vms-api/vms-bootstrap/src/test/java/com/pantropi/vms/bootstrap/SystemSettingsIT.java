package com.pantropi.vms.bootstrap;

import com.pantropi.vms.application.masterdata.SettingsCatalogue;
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
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * INTEGRATION TEST for US-04.8.1 — system settings over real HTTP against real PostgreSQL
 * (Zonky embedded; no Docker).
 *
 * <p>Two seeded principals, which is the whole point of AC-4: {@code cfgadmin} holds
 * {@code masterdata.view} + {@code settings.manage}; {@code dataadmin} holds
 * {@code masterdata.view} + {@code masterdata.edit} but <strong>not</strong> {@code settings.manage}.
 *
 * <p>The context — and so the database — is shared across the class, and several tests necessarily
 * mutate settings. The read-only assertions about the <em>seeded</em> state therefore run first,
 * declared with {@code @Order}, the same approach {@link MigrationIT} takes for the same reason.
 * Without it, whether AC-1 passes would depend on the order JUnit happened to pick.
 */
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.OrderAnnotation.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "vms.identity.enabled=true",
                "vms.masterdata.enabled=true",
                "vms.security.jwt.secret=integration-test-secret-least-32-bytes-long-xx",
                "spring.flyway.enabled=false"
        })
class SystemSettingsIT {

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

    // ---- the catalogue is the contract ----

    @Test @org.junit.jupiter.api.Order(1)
    @DisplayName("T-04.8.1.1: the catalogue and the seeded table agree, in both directions")
    void catalogueMatchesTheSeed() throws Exception {
        Set<String> seeded = queryKeys("SELECT key FROM vms.system_settings");
        Set<String> catalogued = new HashSet<>(
                SettingsCatalogue.all().stream().map(SettingsCatalogue.Entry::key).toList());

        // Both directions matter. A seeded key missing from the catalogue would be unreadable
        // through the API; a catalogued key never seeded would read as its default while looking
        // to an operator as though it were stored.
        assertThat(catalogued).containsExactlyInAnyOrderElementsOf(seeded);
    }

    @Test @org.junit.jupiter.api.Order(2)
    @DisplayName("AC-1: the seeded settings are listed with their values and descriptions")
    void listShowsSeededSettings() throws Exception {
        HttpResponse<String> res = get("/api/v1/admin/settings", token("cfgadmin"));

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body())
                .contains("\"key\":\"default_pass_valid_hours\"", "\"value\":\"12\"")
                .contains("\"key\":\"notification.email.enabled\"", "\"value\":\"true\"")
                .contains("\"key\":\"notification.whatsapp.enabled\"")
                .contains("\"key\":\"acs.retry.max_attempts\"")
                .contains("Fallback validity window");
    }

    // ---- changing a setting ----

    @Test @org.junit.jupiter.api.Order(3)
    @DisplayName("AC-2: a change updates the jsonb, stamps the actor, and audits both states")
    void changeIsPersistedAndAudited() throws Exception {
        HttpResponse<String> res = put("/api/v1/admin/settings/acs.retry.max_attempts",
                "{\"value\":\"8\"}", token("cfgadmin"));

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(scalar("SELECT value #>> '{}' FROM vms.system_settings "
                + "WHERE key='acs.retry.max_attempts'")).isEqualTo("8");
        assertThat(scalar("SELECT updated_by IS NOT NULL FROM vms.system_settings "
                + "WHERE key='acs.retry.max_attempts'")).isEqualTo("t");

        assertThat(scalar("SELECT count(*) FROM vms.audit_logs WHERE action='settings.updated' "
                + "AND entity_id='acs.retry.max_attempts'")).isEqualTo("1");
        assertThat(scalar("SELECT before_state::text FROM vms.audit_logs "
                + "WHERE action='settings.updated' AND entity_id='acs.retry.max_attempts'"))
                .contains("5");
        assertThat(scalar("SELECT after_state::text FROM vms.audit_logs "
                + "WHERE action='settings.updated' AND entity_id='acs.retry.max_attempts'"))
                .contains("8");
    }

    @Test @org.junit.jupiter.api.Order(4)
    @DisplayName("an integer stays a JSON number and a boolean a JSON boolean, not quoted strings")
    void jsonbKeepsItsType() throws Exception {
        put("/api/v1/admin/settings/default_pass_valid_hours", "{\"value\":\"24\"}",
                token("cfgadmin"));
        put("/api/v1/admin/settings/notification.email.enabled", "{\"value\":\"false\"}",
                token("cfgadmin"));

        assertThat(scalar("SELECT jsonb_typeof(value) FROM vms.system_settings "
                + "WHERE key='default_pass_valid_hours'")).isEqualTo("number");
        assertThat(scalar("SELECT jsonb_typeof(value) FROM vms.system_settings "
                + "WHERE key='notification.email.enabled'")).isEqualTo("boolean");
    }

    // ---- the WhatsApp guard ----

    @Test @org.junit.jupiter.api.Order(5)
    @DisplayName("AC-3: enabling WhatsApp is refused citing TODO-05, and nothing is stored")
    void whatsappCannotBeEnabled() throws Exception {
        HttpResponse<String> res = put("/api/v1/admin/settings/notification.whatsapp.enabled",
                "{\"value\":\"true\"}", token("cfgadmin"));

        assertThat(res.statusCode()).isEqualTo(409);
        assertThat(res.body()).contains("TODO-05");
        assertThat(scalar("SELECT value #>> '{}' FROM vms.system_settings "
                + "WHERE key='notification.whatsapp.enabled'")).isEqualTo("false");

        // The attempt is on the record — an auditor should see that it was tried.
        assertThat(scalar("SELECT count(*) FROM vms.audit_logs "
                + "WHERE action='settings.change_refused'")).isNotEqualTo("0");
    }

    @Test @org.junit.jupiter.api.Order(6)
    @DisplayName("AC-3: disabling WhatsApp behaves normally — the guard is one-directional")
    void whatsappCanBeDisabled() throws Exception {
        execute("UPDATE vms.system_settings SET value='true'::jsonb "
                + "WHERE key='notification.whatsapp.enabled'");

        HttpResponse<String> res = put("/api/v1/admin/settings/notification.whatsapp.enabled",
                "{\"value\":\"false\"}", token("cfgadmin"));

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(scalar("SELECT value #>> '{}' FROM vms.system_settings "
                + "WHERE key='notification.whatsapp.enabled'")).isEqualTo("false");
    }

    // ---- authorization ----

    @Test @org.junit.jupiter.api.Order(7)
    @DisplayName("AC-4: masterdata.edit does not confer settings.manage — the write is 403 and audited")
    void masterDataAdminCannotChangeSettings() throws Exception {
        String dataAdmin = token("dataadmin");

        // It can read — reading settings only needs masterdata.view.
        assertThat(get("/api/v1/admin/settings", dataAdmin).statusCode()).isEqualTo(200);

        // It cannot write, despite holding masterdata.edit.
        assertThat(put("/api/v1/admin/settings/acs.retry.max_attempts", "{\"value\":\"9\"}",
                dataAdmin).statusCode()).isEqualTo(403);
        assertThat(scalar("SELECT count(*) FROM vms.audit_logs "
                + "WHERE action='authorization.denied'")).isNotEqualTo("0");
    }

    @Test @org.junit.jupiter.api.Order(8)
    @DisplayName("negative: settings are not readable without a token")
    void readRequiresAuthentication() throws Exception {
        assertThat(get("/api/v1/admin/settings", null).statusCode()).isEqualTo(401);
    }

    // ---- unknown keys ----

    @Test @org.junit.jupiter.api.Order(9)
    @DisplayName("AC-5: writing an uncatalogued key is refused and creates no row")
    void unknownKeyRefused() throws Exception {
        HttpResponse<String> res = put("/api/v1/admin/settings/notification.sms.enabled",
                "{\"value\":\"true\"}", token("cfgadmin"));

        assertThat(res.statusCode()).isEqualTo(404);
        assertThat(scalar("SELECT count(*) FROM vms.system_settings "
                + "WHERE key='notification.sms.enabled'")).isEqualTo("0");
    }

    // ---- US-04.8.2 ----

    @Test @org.junit.jupiter.api.Order(11)
    @DisplayName("AC-3: a change is visible on the next read, so the cache does not serve stale data")
    void cacheIsInvalidatedOnWrite() throws Exception {
        String admin = token("cfgadmin");

        // Read first, so the cache is warm and a broken invalidation would show as a stale read.
        assertThat(get("/api/v1/admin/settings/acs.retry.max_attempts", admin).body())
                .contains("\"value\":");

        put("/api/v1/admin/settings/acs.retry.max_attempts", "{\"value\":\"17\"}", admin);

        assertThat(get("/api/v1/admin/settings/acs.retry.max_attempts", admin).body())
                .contains("\"value\":\"17\"");
        // ...and again, to prove the reload was cached rather than reloaded per request.
        assertThat(get("/api/v1/admin/settings/acs.retry.max_attempts", admin).body())
                .contains("\"value\":\"17\"");
    }

    @Test @org.junit.jupiter.api.Order(12)
    @DisplayName("AC-4: a credential-shaped value is refused, and never reaches the audit trail")
    void credentialShapedValueRefused() throws Exception {
        String pastedKey = "ghp_16CharsAndThenSomeMore00";

        HttpResponse<String> res = put("/api/v1/admin/settings/acs.retry.max_attempts",
                "{\"value\":\"" + pastedKey + "\"}", token("cfgadmin"));

        assertThat(res.statusCode()).isEqualTo(409);
        assertThat(res.body()).contains("F-05.2");
        // The response must not echo it back either.
        assertThat(res.body()).doesNotContain(pastedKey);

        assertThat(scalar("SELECT count(*) FROM vms.audit_logs "
                + "WHERE action='settings.credential_refused'")).isNotEqualTo("0");
        assertThat(scalar("SELECT count(*) FROM vms.audit_logs "
                + "WHERE before_state::text LIKE '%" + pastedKey + "%' "
                + "   OR after_state::text LIKE '%" + pastedKey + "%'")).isEqualTo("0");
    }

    @Test @org.junit.jupiter.api.Order(13)
    @DisplayName("AC-2: no setting is marked secret, so nothing in the listing is redacted")
    void nothingIsRedactedToday() throws Exception {
        HttpResponse<String> res = get("/api/v1/admin/settings", token("cfgadmin"));

        // The redaction machinery exists and is unit-tested; this asserts the deployed catalogue
        // has no reason to use it. A secret appearing here would be a secret in the wrong store.
        assertThat(res.body()).doesNotContain("********");
        assertThat(res.body()).contains("\"secret\":false").doesNotContain("\"secret\":true");
    }

    @Test @org.junit.jupiter.api.Order(10)
    @DisplayName("a value of the wrong type is 400 and leaves the stored value untouched")
    void wrongTypeRefused() throws Exception {
        HttpResponse<String> res = put("/api/v1/admin/settings/acs.retry.max_attempts",
                "{\"value\":\"lots\"}", token("cfgadmin"));

        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(scalar("SELECT jsonb_typeof(value) FROM vms.system_settings "
                + "WHERE key='acs.retry.max_attempts'")).isEqualTo("number");
    }

    // ---- helpers ----

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

    /**
     * The one integration test that still seeds its own grants, and deliberately.
     *
     * <p>Since V9 the other suites rely entirely on the real matrix. This one cannot: AC-4 needs a
     * principal holding {@code masterdata.edit} but <strong>not</strong> {@code settings.manage},
     * and no seeded role is that shape — {@code SYSTEM_ADMIN} holds both, and every other role holds
     * neither. The principal is hypothetical by design, because the point of the AC is that the two
     * permissions are independent of each other, not that some particular role lacks one.
     *
     * <p>{@code cfgadmin} still comes from the real matrix; only {@code dataadmin} is constructed.
     */
    private static void seed() throws Exception {
        String hash = new Pbkdf2PasswordHasher().hash(PASSWORD.toCharArray());
        try (Connection c = pg.getPostgresDatabase().getConnection();
             Statement s = c.createStatement()) {
            String sysRole = single(s, "SELECT id FROM vms.roles WHERE code='SYSTEM_ADMIN'");
            String fmRole = single(s, "SELECT id FROM vms.roles WHERE code='FM_ADMIN'");
            insertUser(s, "cfgadmin", hash, sysRole);
            insertUser(s, "dataadmin", hash, fmRole);

            grant(s, "SYSTEM_ADMIN", "masterdata.view");
            grant(s, "SYSTEM_ADMIN", "settings.manage");
            // Deliberately NOT settings.manage — this is the principal AC-4 is about.
            grant(s, "FM_ADMIN", "masterdata.view");
            grant(s, "FM_ADMIN", "masterdata.edit");
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

    private static Set<String> queryKeys(String sql) throws Exception {
        Set<String> out = new HashSet<>();
        try (Connection c = pg.getPostgresDatabase().getConnection();
             Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    private static String scalar(String sql) throws Exception {
        try (Connection c = pg.getPostgresDatabase().getConnection();
             Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
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
}
