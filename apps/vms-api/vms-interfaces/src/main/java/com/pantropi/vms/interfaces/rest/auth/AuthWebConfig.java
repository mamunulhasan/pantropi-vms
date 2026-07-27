package com.pantropi.vms.interfaces.rest.auth;

import com.pantropi.vms.application.identity.port.AccessTokenIssuer;
import com.pantropi.vms.application.identity.port.SessionStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link BearerTokenInterceptor} on protected auth routes (US-02.1.1 / US-02.1.2).
 *
 * <p>{@code /login} and {@code /refresh} are open (they mint or exchange tokens); everything else
 * under {@code /api/v1/auth/**} — {@code /me}, {@code /logout} — requires a valid Bearer token on a
 * live session (deny-by-default; NFR-SEC-01, SRS B1).
 */
@Configuration
@ConditionalOnProperty(prefix = "vms.identity", name = "enabled", havingValue = "true")
public class AuthWebConfig implements WebMvcConfigurer {

    private final AccessTokenIssuer tokens;
    private final SessionStore sessions;

    public AuthWebConfig(AccessTokenIssuer tokens, SessionStore sessions) {
        this.tokens = tokens;
        this.sessions = sessions;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new BearerTokenInterceptor(tokens, sessions))
                .addPathPatterns("/api/v1/auth/**")
                .excludePathPatterns("/api/v1/auth/login", "/api/v1/auth/refresh");
    }
}
