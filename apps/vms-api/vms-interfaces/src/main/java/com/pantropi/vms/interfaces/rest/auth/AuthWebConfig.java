package com.pantropi.vms.interfaces.rest.auth;

import com.pantropi.vms.application.identity.port.AccessTokenIssuer;
import com.pantropi.vms.application.identity.port.AuditTrail;
import com.pantropi.vms.application.identity.port.PermissionChecker;
import com.pantropi.vms.application.identity.port.SessionStore;
import com.pantropi.vms.interfaces.rest.admin.AdminAuthorizationInterceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the authentication and authorization interceptors (US-02.1.1 / US-02.1.2 / US-02.2.1).
 *
 * <ul>
 *   <li>{@code /api/v1/auth/**} (except {@code /login}, {@code /refresh}) — valid Bearer token on a
 *       live session.</li>
 *   <li>{@code /api/v1/admin/**} — the above <b>and</b> the {@code user.manage} permission, or 403
 *       with an authorization-denial audit event (AC-6).</li>
 * </ul>
 */
@Configuration
@ConditionalOnProperty(prefix = "vms.identity", name = "enabled", havingValue = "true")
public class AuthWebConfig implements WebMvcConfigurer {

    private final AccessTokenIssuer tokens;
    private final SessionStore sessions;
    private final PermissionChecker permissions;
    private final AuditTrail audit;

    public AuthWebConfig(AccessTokenIssuer tokens, SessionStore sessions,
                         PermissionChecker permissions, AuditTrail audit) {
        this.tokens = tokens;
        this.sessions = sessions;
        this.permissions = permissions;
        this.audit = audit;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new BearerTokenInterceptor(tokens, sessions))
                .addPathPatterns("/api/v1/auth/**")
                .excludePathPatterns("/api/v1/auth/login", "/api/v1/auth/refresh",
                        "/api/v1/auth/activate");

        registry.addInterceptor(new AdminAuthorizationInterceptor(
                        tokens, sessions, permissions, audit, "user.manage"))
                .addPathPatterns("/api/v1/admin/**");
    }
}
