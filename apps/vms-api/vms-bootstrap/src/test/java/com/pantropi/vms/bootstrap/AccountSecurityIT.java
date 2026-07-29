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
 * INTEGRATION TEST for US-02.3.1 — account security policy over real HTTP and real PostgreSQL
 * (Zonky embedded; no Docker).
 *
 * <p>Each test owns its own username so the shared lockout table cannot make them order-dependent —
 * that is the whole hazard of testing a stateful counter through a shared context.
 *
 * <p>The lockout threshold is lowered to 3 here so the tests stay fast; the window is left long
 * enough that expiry never happens accidentally mid-test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "vms.identity.enabled=true",
                "vms.security.jwt.secret=integration-test-secret-least-32-bytes-long-xx",
                "vms.security.lockout.threshold=3",
                "vms.security.lockout.window-minutes=30",
                "spring.flyway.enabled=false"
        })
class AccountSecurityIT {

    private static final String PASSWORD = "correct horse battery staple";
    private static EmbeddedPostgres pg;
    private final HttpClient client = HttpClient.newHttpClient();

    @LocalServerPort int port;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) throws Exception {
        pg = EmbeddedPostgres.start();
        Flyway.configure().dataSource(pg.getPostgresDatabase())
                .schemas("vms").defaultSchema("vms").cleanDisabled(true).load().migrate();
        seedUsers();
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

    // ---- lockout ----

    @Test
    @DisplayName("AC-5: the account locks after the threshold, and the right password then fails too")
    void lockoutAfterThreshold() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(login("lockme", "wrong-password").statusCode()).isEqualTo(401);
        }
        assertThat(scalar("SELECT locked_until IS NOT NULL FROM vms.login_attempts "
                + "WHERE username = 'lockme'")).isEqualTo("t");

        assertThat(login("lockme", PASSWORD).statusCode()).isEqualTo(401);
        assertThat(scalar("SELECT count(*) FROM vms.audit_logs WHERE action='account.locked'"))
                .isNotEqualTo("0");
    }

    @Test
    @DisplayName("AC-5: a locked account, a wrong password and an unknown account are identical")
    void failuresAreIndistinguishable() throws Exception {
        for (int i = 0; i < 3; i++) {
            login("oracle", "wrong-password");
        }

        HttpResponse<String> locked = login("oracle", PASSWORD);
        HttpResponse<String> wrongPassword = login("probe", "wrong-password");
        HttpResponse<String> unknownAccount = login("does-not-exist-at-all", "wrong-password");

        // Same status, byte-identical body, and the same content type: nothing to distinguish them.
        assertThat(locked.statusCode())
                .isEqualTo(wrongPassword.statusCode()).isEqualTo(unknownAccount.statusCode())
                .isEqualTo(401);
        assertThat(locked.body())
                .isEqualTo(wrongPassword.body()).isEqualTo(unknownAccount.body());
        assertThat(locked.body()).doesNotContain("lock").doesNotContain("exist");
    }

    @Test
    @DisplayName("AC-5: an unknown username accumulates failures exactly like a real one")
    void unknownUsernameIsCountedToo() throws Exception {
        for (int i = 0; i < 3; i++) {
            login("phantom-user", "anything");
        }
        assertThat(scalar("SELECT locked_until IS NOT NULL FROM vms.login_attempts "
                + "WHERE username = 'phantom-user'")).isEqualTo("t");
    }

    @Test
    @DisplayName("AC-5: varying the capitalisation does not buy a fresh set of attempts")
    void lockoutIsCaseInsensitive() throws Exception {
        // Without a case-insensitive key an attacker gets the threshold once per capitalisation.
        assertThat(login("mixedcase", "wrong-password").statusCode()).isEqualTo(401);
        assertThat(login("MixedCase", "wrong-password").statusCode()).isEqualTo(401);
        assertThat(login("MIXEDCASE", "wrong-password").statusCode()).isEqualTo(401);

        assertThat(scalar("SELECT count(*) FROM vms.login_attempts "
                + "WHERE username = 'mixedcase'")).isEqualTo("1");
        // Regression guard for V11. The assertion above passes even with a case-SENSITIVE
        // comparison, because the literal happens to match the stored spelling exactly — so it
        // proved the unique index was case-insensitive, not that a lookup was. Reading the same row
        // back under a different capitalisation is the comparison the citext column exists for, and
        // it answered zero while the extension sat in a schema no application connection could see.
        assertThat(scalar("SELECT count(*) FROM vms.login_attempts "
                + "WHERE username = 'MIXEDCASE'")).isEqualTo("1");
        assertThat(login("mixedcase", PASSWORD).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("a successful login clears the accumulated failure count")
    void successResetsCounter() throws Exception {
        assertThat(login("resetme", "wrong-password").statusCode()).isEqualTo(401);
        assertThat(login("resetme", "wrong-password").statusCode()).isEqualTo(401);
        assertThat(login("resetme", PASSWORD).statusCode()).isEqualTo(200);

        assertThat(scalar("SELECT failed_count FROM vms.login_attempts WHERE username='resetme'"))
                .isEqualTo("0");

        // The counter having reset is what matters: one more failure must not lock the account.
        assertThat(login("resetme", "wrong-password").statusCode()).isEqualTo(401);
        assertThat(scalar("SELECT locked_until IS NULL FROM vms.login_attempts "
                + "WHERE username='resetme'")).isEqualTo("t");
    }

    // ---- self-service change ----

    @Test
    @DisplayName("changing your own password works, revokes old sessions and leaves no hash exposed")
    void changePassword() throws Exception {
        String token = accessToken("changer", PASSWORD);
        String otherDevice = accessToken("changer", PASSWORD);   // a second live session

        HttpResponse<String> changed = post("/api/v1/auth/password",
                json("currentPassword", PASSWORD, "newPassword", "a different long passphrase"),
                token);
        assertThat(changed.statusCode()).isEqualTo(200);
        assertThat(changed.body()).contains("\"accessToken\"").doesNotContain("passphrase");

        // Both prior sessions are dead — including the one that made the request.
        assertThat(get("/api/v1/auth/me", token).statusCode()).isEqualTo(401);
        assertThat(get("/api/v1/auth/me", otherDevice).statusCode()).isEqualTo(401);

        // The token returned by the change is live.
        assertThat(get("/api/v1/auth/me", extract(changed.body(), "accessToken")).statusCode())
                .isEqualTo(200);

        // Old password no longer works; new one does.
        assertThat(login("changer", PASSWORD).statusCode()).isEqualTo(401);
        assertThat(login("changer", "a different long passphrase").statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("AC-1: a weak new password is refused 422 without naming the rule or echoing it")
    void weakNewPasswordRefused() throws Exception {
        String token = accessToken("weakling", PASSWORD);

        HttpResponse<String> res = post("/api/v1/auth/password",
                json("currentPassword", PASSWORD, "newPassword", "password1234"), token);

        assertThat(res.statusCode()).isEqualTo(422);
        assertThat(res.body()).doesNotContain("password1234").doesNotContain("denylist");
        // The session survives a rejected change.
        assertThat(get("/api/v1/auth/me", token).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("a wrong current password is refused 401 even with a valid token")
    void wrongCurrentPasswordRefused() throws Exception {
        String token = accessToken("stubborn", PASSWORD);

        assertThat(post("/api/v1/auth/password",
                json("currentPassword", "not-it-at-all", "newPassword", "a fine new passphrase"),
                token).statusCode()).isEqualTo(401);
        assertThat(scalar("SELECT count(*) FROM vms.audit_logs "
                + "WHERE action='password.change_denied'")).isNotEqualTo("0");
    }

    @Test
    @DisplayName("the change endpoint is not reachable without a token")
    void changeRequiresAuthentication() throws Exception {
        assertThat(post("/api/v1/auth/password",
                json("currentPassword", PASSWORD, "newPassword", "another long passphrase"), null)
                .statusCode()).isEqualTo(401);
    }

    // ---- administrator recovery ----

    @Test
    @DisplayName("an administrator unlocks a locked account; a non-administrator gets 403")
    void adminUnlock() throws Exception {
        for (int i = 0; i < 3; i++) {
            login("unlockme", "wrong-password");
        }
        String userId = scalar("SELECT id FROM vms.users WHERE username='unlockme'");

        // A user without user.manage cannot unlock.
        String worker = accessToken("worker", PASSWORD);
        assertThat(post("/api/v1/admin/users/" + userId + "/unlock", "", worker).statusCode())
                .isEqualTo(403);

        String admin = accessToken("admin", PASSWORD);
        assertThat(post("/api/v1/admin/users/" + userId + "/unlock", "", admin).statusCode())
                .isEqualTo(204);

        assertThat(login("unlockme", PASSWORD).statusCode()).isEqualTo(200);
        assertThat(scalar("SELECT count(*) FROM vms.audit_logs WHERE action='account.unlocked'"))
                .isNotEqualTo("0");
    }

    @Test
    @DisplayName("an administrator reset issues an activation token and never sets a password")
    void adminResetIssuesToken() throws Exception {
        String admin = accessToken("admin", PASSWORD);
        String userId = scalar("SELECT id FROM vms.users WHERE username='forgetful'");
        String hashBefore = scalar("SELECT password_hash FROM vms.users WHERE username='forgetful'");

        HttpResponse<String> res =
                post("/api/v1/admin/users/" + userId + "/reset-password", "", admin);

        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(extract(res.body(), "activationToken")).isNotBlank();
        // The decisive assertion: the administrator changed no password material.
        assertThat(scalar("SELECT password_hash FROM vms.users WHERE username='forgetful'"))
                .isEqualTo(hashBefore);
        assertThat(scalar("SELECT count(*) FROM vms.activation_tokens WHERE user_id='" + userId + "'"))
                .isEqualTo("1");
        // ...and the raw token was not written to the audit trail.
        assertThat(scalar("SELECT count(*) FROM vms.audit_logs "
                + "WHERE entity_id='" + userId + "' AND action='password.reset_requested'"))
                .isEqualTo("1");
    }

    @Test
    @DisplayName("resetting an unknown user is 404, not a 500")
    void resetUnknownUser() throws Exception {
        String admin = accessToken("admin", PASSWORD);
        assertThat(post("/api/v1/admin/users/" + UUID.randomUUID() + "/reset-password", "", admin)
                .statusCode()).isEqualTo(404);
    }

    // ---- forced rotation ----

    @Test
    @DisplayName("an account owing a password change reaches nothing but the change endpoints")
    void forcedChangeConfinesTheSession() throws Exception {
        execute("UPDATE vms.users SET must_change_password = true WHERE username = 'expired'");

        HttpResponse<String> login = login("expired", PASSWORD);
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(login.body()).contains("\"mustChangePassword\":true");
        String token = extract(login.body(), "accessToken");

        // Everything else is shut, including routes the role would otherwise be entitled to.
        assertThat(get("/api/v1/admin/users", token).statusCode()).isEqualTo(403);
        // ...but the way out stays open.
        assertThat(get("/api/v1/auth/me", token).statusCode()).isEqualTo(200);

        HttpResponse<String> changed = post("/api/v1/auth/password",
                json("currentPassword", PASSWORD, "newPassword", "an entirely fresh passphrase"),
                token);
        assertThat(changed.statusCode()).isEqualTo(200);
        assertThat(changed.body()).contains("\"mustChangePassword\":false");

        // The obligation is discharged: the new session is unrestricted.
        assertThat(scalar("SELECT must_change_password FROM vms.users WHERE username='expired'"))
                .isEqualTo("f");
        assertThat(get("/api/v1/admin/users", extract(changed.body(), "accessToken")).statusCode())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("a confined session cannot escape the gate by refreshing its token")
    void forcedChangeSurvivesRefresh() throws Exception {
        execute("UPDATE vms.users SET must_change_password = true WHERE username = 'refresher'");

        HttpResponse<String> login = login("refresher", PASSWORD);
        HttpResponse<String> refreshed = post("/api/v1/auth/refresh",
                json("refreshToken", extract(login.body(), "refreshToken")), null);

        assertThat(refreshed.statusCode()).isEqualTo(200);
        assertThat(refreshed.body()).contains("\"mustChangePassword\":true");
        assertThat(get("/api/v1/admin/users", extract(refreshed.body(), "accessToken")).statusCode())
                .isEqualTo(403);
    }

    // ---- helpers ----

    private HttpResponse<String> login(String u, String p) throws Exception {
        return post("/api/v1/auth/login", json("username", u, "password", p), null);
    }

    private String accessToken(String u, String p) throws Exception {
        return extract(login(u, p).body(), "accessToken");
    }

    private HttpResponse<String> post(String path, String body, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + path)).GET();
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String base() { return "http://localhost:" + port; }

    private static String json(String... keyValues) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < keyValues.length; i += 2) {
            if (i > 0) sb.append(',');
            sb.append('"').append(keyValues[i]).append("\":\"").append(keyValues[i + 1]).append('"');
        }
        return sb.append('}').toString();
    }

    private static String extract(String json, String key) {
        int i = json.indexOf("\"" + key + "\":\"") + key.length() + 4;
        return json.substring(i, json.indexOf('"', i));
    }

    /** One username per test, so a shared lockout table cannot couple them. */
    private static void seedUsers() throws Exception {
        String hash = new Pbkdf2PasswordHasher().hash(PASSWORD.toCharArray());
        try (Connection c = pg.getPostgresDatabase().getConnection();
             Statement s = c.createStatement()) {
            String sysRole = single(s, "SELECT id FROM vms.roles WHERE code='SYSTEM_ADMIN'");
            String floorRole = single(s, "SELECT id FROM vms.roles WHERE code='FLOOR_RECEPTIONIST'");
            for (String u : new String[]{"admin", "expired", "refresher"}) {
                insert(s, u, hash, sysRole);
            }
            for (String u : new String[]{"worker", "lockme", "oracle", "probe", "resetme",
                                         "changer", "weakling", "stubborn", "unlockme",
                                         "forgetful", "mixedcase"}) {
                insert(s, u, hash, floorRole);
            }
        }
    }

    private static void insert(Statement s, String u, String hash, String roleId) throws Exception {
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

    private static void execute(String sql) throws Exception {
        try (Connection c = pg.getPostgresDatabase().getConnection();
             Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }
}
