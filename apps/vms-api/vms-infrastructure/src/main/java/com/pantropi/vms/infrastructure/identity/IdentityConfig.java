package com.pantropi.vms.infrastructure.identity;

import com.pantropi.vms.application.identity.port.AccessTokenIssuer;
import com.pantropi.vms.application.identity.port.PasswordHasher;
import com.pantropi.vms.application.identity.port.UserDirectory;
import com.pantropi.vms.application.identity.usecase.AuthenticateUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;

/**
 * Wires the identity ports to their adapters (US-02.1.1, T-01.2.1.3 convention).
 *
 * <p>Gated on {@code vms.identity.enabled=true} so the application still boots DB-less in the
 * {@code local}/{@code test} profiles (US-01.2.1 AC-4) where no DataSource exists yet. Staging
 * and prod enable it; the login integration test enables it with an embedded PostgreSQL.
 *
 * <p>Application classes stay annotation-free — the binding lives here in infrastructure.
 */
@Configuration
@ConditionalOnProperty(prefix = "vms.identity", name = "enabled", havingValue = "true")
public class IdentityConfig {

    @Bean
    PasswordHasher passwordHasher() {
        return new Pbkdf2PasswordHasher();
    }

    @Bean
    AccessTokenIssuer accessTokenIssuer(
            @Value("${vms.security.jwt.secret:dev-only-insecure-secret-change-me-32byteslong!!}") String secret,
            @Value("${vms.security.jwt.access-ttl-minutes:30}") long ttlMinutes,
            Clock clock) {
        return new HmacJwtIssuer(secret, Duration.ofMinutes(ttlMinutes), clock);
    }

    @Bean
    UserDirectory userDirectory(DataSource dataSource) {
        return new JdbcUserDirectory(new JdbcTemplate(dataSource));
    }

    @Bean
    AuthenticateUser authenticateUser(
            UserDirectory users, PasswordHasher hasher,
            com.pantropi.vms.application.identity.port.LoginAttemptStore attempts,
            com.pantropi.vms.application.identity.port.AuditTrail audit, Clock clock,
            @Value("${vms.security.lockout.threshold:5}") int threshold,
            @Value("${vms.security.lockout.window-minutes:15}") long windowMinutes) {
        return new AuthenticateUser(users, hasher, attempts, audit, clock, threshold,
                Duration.ofMinutes(windowMinutes));
    }

    // ---- US-02.3.1 account security policy ----

    @Bean
    com.pantropi.vms.application.identity.port.LoginAttemptStore loginAttemptStore(DataSource dataSource) {
        return new JdbcLoginAttemptStore(new JdbcTemplate(dataSource));
    }

    @Bean
    com.pantropi.vms.application.identity.port.CredentialStore credentialStore(DataSource dataSource) {
        return new JdbcCredentialStore(new JdbcTemplate(dataSource));
    }

    @Bean
    com.pantropi.vms.application.identity.usecase.PasswordPolicy passwordPolicy(
            @Value("${vms.security.password.min-length:12}") int minLength,
            @Value("${vms.security.password.max-length:200}") int maxLength) {
        return new com.pantropi.vms.application.identity.usecase.PasswordPolicy(minLength, maxLength);
    }

    @Bean
    com.pantropi.vms.application.identity.usecase.ChangePassword changePassword(
            com.pantropi.vms.application.identity.port.CredentialStore credentials,
            PasswordHasher hasher,
            com.pantropi.vms.application.identity.usecase.PasswordPolicy policy,
            com.pantropi.vms.application.identity.port.SessionStore sessions,
            com.pantropi.vms.application.identity.port.LoginAttemptStore attempts,
            com.pantropi.vms.application.identity.port.AuditTrail audit, Clock clock) {
        return new com.pantropi.vms.application.identity.usecase.ChangePassword(
                credentials, hasher, policy, sessions, attempts, audit, clock);
    }

    @Bean
    com.pantropi.vms.application.identity.usecase.AccountRecovery accountRecovery(
            com.pantropi.vms.application.identity.port.CredentialStore credentials,
            com.pantropi.vms.application.identity.port.LoginAttemptStore attempts,
            com.pantropi.vms.application.identity.port.SessionStore sessions,
            com.pantropi.vms.application.identity.usecase.AccountActivation activation,
            com.pantropi.vms.application.identity.port.AuditTrail audit) {
        return new com.pantropi.vms.application.identity.usecase.AccountRecovery(
                credentials, attempts, sessions, activation, audit);
    }

    // ---- US-02.1.2 sessions: store, audit, manager ----

    @Bean
    com.pantropi.vms.application.identity.port.SessionStore sessionStore(DataSource dataSource) {
        return new JdbcSessionStore(new JdbcTemplate(dataSource));
    }

    @Bean
    com.pantropi.vms.application.identity.port.AuditTrail auditTrail(DataSource dataSource) {
        return new JdbcAuditTrail(new JdbcTemplate(dataSource));
    }

    // ---- US-02.2.2 bulk import & activation ----

    @Bean
    com.pantropi.vms.application.shared.port.TransactionRunner transactionRunner(
            org.springframework.transaction.PlatformTransactionManager tm) {
        return new com.pantropi.vms.infrastructure.shared.SpringTransactionRunner(
                new org.springframework.transaction.support.TransactionTemplate(tm));
    }

    /** The read side of the same table (US-02.5.1). Separate port: writing and reading a trail
     *  are different privileges, and nothing that reads it should be able to append. */
    @Bean
    com.pantropi.vms.application.identity.port.AuditLogQueries auditLogQueries(DataSource dataSource) {
        return new JdbcAuditLogQueries(new JdbcTemplate(dataSource));
    }

    @Bean
    com.pantropi.vms.application.identity.port.ActivationStore activationStore(DataSource dataSource) {
        return new JdbcActivationStore(new JdbcTemplate(dataSource));
    }

    @Bean
    com.pantropi.vms.application.identity.usecase.AccountActivation accountActivation(
            com.pantropi.vms.application.identity.port.ActivationStore store, PasswordHasher hasher,
            Clock clock,
            @Value("${vms.identity.activation-ttl-hours:72}") long activationTtlHours) {
        return new com.pantropi.vms.application.identity.usecase.AccountActivation(
                store, hasher, clock, Duration.ofHours(activationTtlHours));
    }

    @Bean
    com.pantropi.vms.application.identity.usecase.UserImport userImport(
            com.pantropi.vms.application.identity.port.UserAdministrationStore store,
            com.pantropi.vms.application.identity.port.AuditTrail audit,
            com.pantropi.vms.application.shared.port.TransactionRunner tx,
            com.pantropi.vms.application.identity.usecase.AccountActivation activation) {
        return new com.pantropi.vms.application.identity.usecase.UserImport(store, audit, tx, activation);
    }

    // ---- US-02.2.1 user administration ----

    @Bean
    com.pantropi.vms.application.identity.port.UserAdministrationStore userAdministrationStore(
            DataSource dataSource) {
        return new JdbcUserAdministrationStore(new JdbcTemplate(dataSource));
    }

    @Bean
    com.pantropi.vms.application.identity.port.PermissionChecker permissionChecker(DataSource dataSource) {
        return new JdbcPermissionChecker(new JdbcTemplate(dataSource));
    }

    @Bean
    com.pantropi.vms.application.identity.usecase.UserAdministration userAdministration(
            com.pantropi.vms.application.identity.port.UserAdministrationStore store,
            com.pantropi.vms.application.identity.port.SessionStore sessions,
            com.pantropi.vms.application.identity.port.AuditTrail audit,
            com.pantropi.vms.application.identity.usecase.MasterAdminPolicy masterAdmins,
            com.pantropi.vms.application.shared.port.TransactionRunner transactions) {
        return new com.pantropi.vms.application.identity.usecase.UserAdministration(
                store, sessions, audit, masterAdmins, transactions);
    }

    @Bean
    com.pantropi.vms.application.identity.usecase.SessionManager sessionManager(
            com.pantropi.vms.application.identity.port.SessionStore store, AccessTokenIssuer issuer,
            com.pantropi.vms.application.identity.port.AuditTrail audit, Clock clock,
            @Value("${vms.security.jwt.refresh-ttl-minutes:1440}") long refreshTtlMinutes) {
        return new com.pantropi.vms.application.identity.usecase.SessionManager(
                store, issuer, audit, clock, Duration.ofMinutes(refreshTtlMinutes));
    }

    // ---- US-03.4.1 tenant and floor scoping seam ----

    /**
     * One bean, two ports. {@link RequestScopeContext} implements both the read side and the
     * lifecycle, and registering it once under its own type lets each injection point ask for the
     * interface it needs — there is only one candidate for either, so nothing is ambiguous.
     */
    @Bean
    RequestScopeContext requestScopeContext(DataSource dataSource) {
        return new RequestScopeContext(new JdbcTemplate(dataSource));
    }

    /**
     * The scoping posture is configuration (US-07.3.1, T-07.3.1.2).
     *
     * <p>{@code vms.scoping.posture: building-wide | own-scope}, defaulting to the shipped
     * building-wide reading of TODO-14. Answering that question restrictively is then a property
     * change rather than a code change, which is what AC-6 asks for.
     *
     * <p>The active posture is logged at startup so the isolation a deployment is actually running
     * is discoverable from its logs, not only from its configuration files.
     */
    @Bean
    com.pantropi.vms.application.identity.usecase.ScopePolicy scopePolicy(
            com.pantropi.vms.application.identity.port.ScopeContext context,
            @org.springframework.beans.factory.annotation.Value(
                    "${vms.scoping.posture:building-wide}") String posture) {

        var chosen = "own-scope".equalsIgnoreCase(posture)
                ? com.pantropi.vms.application.identity.usecase.ScopePolicy.Posture.OWN_SCOPE
                : com.pantropi.vms.application.identity.usecase.ScopePolicy.Posture.BUILDING_WIDE;

        if (!"own-scope".equalsIgnoreCase(posture) && !"building-wide".equalsIgnoreCase(posture)) {
            // Not a failure: an unrecognised value falls back to the shipped default rather than
            // refusing to start, but it is said out loud so a typo is not silently a policy.
            SCOPE_LOG.warning("Unknown vms.scoping.posture '" + posture + "'; using " + chosen);
        }
        SCOPE_LOG.info("Tenant scoping posture: " + chosen
                + " (TODO-14 provisional; see ADR-0005)");
        return new com.pantropi.vms.application.identity.usecase.ScopePolicy(context, chosen);
    }

    // java.util.logging rather than SLF4J: it is what this module already uses
    // (BootstrapAdminRunner), and the alternative is a new dependency for two lines.
    private static final java.util.logging.Logger SCOPE_LOG =
            java.util.logging.Logger.getLogger(IdentityConfig.class.getName());

    // ---- US-03.1.1 roles, permissions and effective resolution ----

    @Bean
    com.pantropi.vms.application.identity.port.RoleGrantStore roleGrantStore(DataSource dataSource) {
        return new JdbcRoleGrantStore(new JdbcTemplate(dataSource));
    }

    @Bean
    com.pantropi.vms.application.identity.usecase.EffectivePermissions effectivePermissions(
            com.pantropi.vms.application.identity.port.RoleGrantStore store) {
        return new com.pantropi.vms.application.identity.usecase.EffectivePermissions(store);
    }

    @Bean
    com.pantropi.vms.application.identity.usecase.CurrentUser currentUser(
            com.pantropi.vms.application.identity.port.UserAdministrationStore users,
            com.pantropi.vms.application.identity.usecase.EffectivePermissions permissions) {
        return new com.pantropi.vms.application.identity.usecase.CurrentUser(users, permissions);
    }

    @Bean
    com.pantropi.vms.application.identity.usecase.RoleAdministration roleAdministration(
            com.pantropi.vms.application.identity.port.RoleGrantStore store,
            com.pantropi.vms.application.shared.port.TransactionRunner transactions,
            com.pantropi.vms.application.identity.port.AuditTrail audit) {
        return new com.pantropi.vms.application.identity.usecase.RoleAdministration(
                store, transactions, audit);
    }

    // ---- US-02.4.1 Master Admin authority & bootstrap ----

    @Bean
    com.pantropi.vms.application.identity.port.AdminDirectory adminDirectory(DataSource dataSource) {
        return new JdbcAdminDirectory(new JdbcTemplate(dataSource));
    }

    @Bean
    com.pantropi.vms.application.identity.usecase.BootstrapAdministrator bootstrapAdministrator(
            com.pantropi.vms.application.identity.port.AdminDirectory admin, PasswordHasher hasher) {
        return new com.pantropi.vms.application.identity.usecase.BootstrapAdministrator(admin, hasher);
    }

    @Bean
    com.pantropi.vms.application.identity.usecase.MasterAdminPolicy masterAdminPolicy(
            com.pantropi.vms.application.identity.port.AdminDirectory admin) {
        return new com.pantropi.vms.application.identity.usecase.MasterAdminPolicy(admin);
    }
}
