package com.pantropi.vms.interfaces.rest.security;

import com.pantropi.vms.application.identity.port.AccessTokenIssuer;
import com.pantropi.vms.application.identity.port.AuditTrail;
import com.pantropi.vms.application.identity.port.PermissionChecker;
import com.pantropi.vms.application.identity.port.ScopeContextLifecycle;
import com.pantropi.vms.application.identity.port.SessionStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Security wiring for the API boundary (US-03.2.1, T-03.2.1.1).
 *
 * <p>Registers exactly one authorization interceptor across {@code /**}. This replaces the earlier
 * pair of path-scoped interceptors, which allowlisted by omission: any controller added on an
 * unlisted path would have been completely public. Deny-by-default removes that whole class of
 * mistake.
 *
 * <p><strong>CSRF</strong> is not applicable and deliberately absent: the API is stateless and
 * token-bearing, it issues no session cookie, and it rejects any request lacking an
 * {@code Authorization} header — so a browser cannot be induced to authenticate a cross-site
 * request ambiently.
 *
 * <p><strong>CORS</strong> is restricted to the portal origins configured per environment. There is
 * no wildcard origin in any profile; the default is the local development portal only.
 */
@Configuration
@ConditionalOnProperty(prefix = "vms.identity", name = "enabled", havingValue = "true")
public class SecurityWebConfig implements WebMvcConfigurer {

    private final AccessTokenIssuer tokens;
    private final SessionStore sessions;
    private final PermissionChecker permissions;
    private final AuditTrail audit;
    private final MeterRegistry meters;
    private final ScopeContextLifecycle scope;
    private final List<String> allowedOrigins;
    private final int denialAlertThreshold;

    public SecurityWebConfig(AccessTokenIssuer tokens, SessionStore sessions,
                             PermissionChecker permissions, AuditTrail audit, MeterRegistry meters,
                             ScopeContextLifecycle scope,
                             @Value("${vms.security.cors.allowed-origins:http://localhost:3000}")
                             List<String> allowedOrigins,
                             @Value("${vms.security.denial-alert-threshold:10}")
                             int denialAlertThreshold) {
        this.tokens = tokens;
        this.sessions = sessions;
        this.permissions = permissions;
        this.audit = audit;
        this.meters = meters;
        this.scope = scope;
        this.allowedOrigins = allowedOrigins;
        this.denialAlertThreshold = denialAlertThreshold;
    }

    @Bean
    AuthorizationDenialRecorder authorizationDenialRecorder() {
        return new AuthorizationDenialRecorder(audit, meters, denialAlertThreshold);
    }

    @Bean
    FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
        FilterRegistrationBean<CorrelationIdFilter> registration =
                new FilterRegistrationBean<>(new CorrelationIdFilter());
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);  // before anything that may report an error
        return registration;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AuthorizationInterceptor(
                        tokens, sessions, permissions, authorizationDenialRecorder(), scope))
                .addPathPatterns("/**");   // every route; PublicRoutes is the only way out
    }

    /**
     * CORS for the portal origins.
     *
     * <p>Three entries here are not decoration — omitting any one of them silently disables a
     * shipped feature from a browser, while leaving it perfectly usable from curl:
     *
     * <ul>
     *   <li><strong>{@code PATCH}</strong> — the amend endpoints for a visitor request (US-07.1.3)
     *       and a pre-registration (US-08.1.3) are PATCH. Without it the preflight is refused and
     *       neither can be called at all.</li>
     *   <li><strong>{@code If-None-Match}</strong> — not a CORS-safelisted request header, so a
     *       conditional GET cannot even be sent without naming it. That is the whole of
     *       US-07.6.2's revalidation mechanism.</li>
     *   <li><strong>{@code ETag}</strong> — not a safelisted <em>response</em> header, so page
     *       JavaScript cannot read the validator it is supposed to send back. Exposing it is what
     *       closes the loop; without it the server would emit an ETag no client could ever use.</li>
     * </ul>
     *
     * <p>Preflight itself is allowed through the authorization interceptor deliberately (it carries
     * no credentials by design); this configuration is what decides it.
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins.toArray(new String[0]))  // never "*"
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE")
                .allowedHeaders("Authorization", "Content-Type", "If-None-Match",
                        CorrelationIdFilter.HEADER)
                .exposedHeaders("ETag", CorrelationIdFilter.HEADER)
                .allowCredentials(false)   // bearer tokens, not cookies
                .maxAge(1800);
    }
}
