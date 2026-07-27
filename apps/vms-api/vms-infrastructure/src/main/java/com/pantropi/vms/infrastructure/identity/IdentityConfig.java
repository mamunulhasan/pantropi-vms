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
    AuthenticateUser authenticateUser(UserDirectory users, PasswordHasher hasher) {
        return new AuthenticateUser(users, hasher);
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

    @Bean
    com.pantropi.vms.application.identity.usecase.SessionManager sessionManager(
            com.pantropi.vms.application.identity.port.SessionStore store, AccessTokenIssuer issuer,
            com.pantropi.vms.application.identity.port.AuditTrail audit, Clock clock,
            @Value("${vms.security.jwt.refresh-ttl-minutes:1440}") long refreshTtlMinutes) {
        return new com.pantropi.vms.application.identity.usecase.SessionManager(
                store, issuer, audit, clock, Duration.ofMinutes(refreshTtlMinutes));
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
