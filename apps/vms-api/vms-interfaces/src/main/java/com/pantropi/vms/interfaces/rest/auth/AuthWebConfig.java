package com.pantropi.vms.interfaces.rest.auth;

import com.pantropi.vms.application.identity.port.AccessTokenIssuer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers {@link BearerTokenInterceptor} on protected auth routes (US-02.1.1).
 *
 * <p>{@code /api/v1/auth/login} is deliberately left open (it mints the token); everything
 * else under {@code /api/v1/auth/**} requires a valid Bearer token — deny-by-default for the
 * protected surface (NFR-SEC-01, SRS B1).
 */
@Configuration
@ConditionalOnProperty(prefix = "vms.identity", name = "enabled", havingValue = "true")
public class AuthWebConfig implements WebMvcConfigurer {

    private final AccessTokenIssuer tokens;

    public AuthWebConfig(AccessTokenIssuer tokens) {
        this.tokens = tokens;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new BearerTokenInterceptor(tokens))
                .addPathPatterns("/api/v1/auth/**")
                .excludePathPatterns("/api/v1/auth/login");
    }
}
