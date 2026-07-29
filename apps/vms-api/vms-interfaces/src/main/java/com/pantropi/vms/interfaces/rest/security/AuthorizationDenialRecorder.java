package com.pantropi.vms.interfaces.rest.security;

import com.pantropi.vms.application.identity.port.AuditTrail;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Records every authentication and authorization denial (US-03.2.2, T-03.2.2.2/3).
 *
 * <p>Writes one audit row per denial carrying the actor (or {@code anonymous}), the attempted
 * permission, the route, the method, the outcome and the source IP — and <strong>never</strong> the
 * request body, query string or any header value, so a denial for a request containing visitor
 * personal data records none of it (AC-2, AC-5).
 *
 * <p>Also increments a denial counter dimensioned by outcome and route class only. Labels carry no
 * user identifier and no personal data, so the metric cannot become a side channel (AC-3). Crossing
 * the configured per-principal threshold increments a separate alertable counter, which likewise
 * carries no identifying label — the counting is internal.
 */
public final class AuthorizationDenialRecorder {

    private static final Logger log = Logger.getLogger(AuthorizationDenialRecorder.class.getName());
    private static final String ANONYMOUS = "anonymous";

    private final AuditTrail audit;
    private final MeterRegistry meters;
    private final int alertThreshold;

    /** Per-principal denial tallies, used only to decide when to raise the alert counter. */
    private final Map<String, AtomicInteger> tallies = new ConcurrentHashMap<>();

    public AuthorizationDenialRecorder(AuditTrail audit, MeterRegistry meters, int alertThreshold) {
        this.audit = audit;
        this.meters = meters;
        this.alertThreshold = alertThreshold;
    }

    public void record(HttpServletRequest request, int status, String attemptedPermission,
                       UUID actorId) {
        try {
            recordOrThrow(request, status, attemptedPermission, actorId);
        } catch (RuntimeException e) {
            // A failing audit or metrics backend must never change the security decision, and must
            // never turn a clean 401/403 into a 500 that leaks a stack trace. The denial still
            // stands; the recording failure is surfaced in the log for operators.
            log.log(Level.SEVERE, "Failed to record authorization denial (the denial still applies)", e);
        }
    }

    private void recordOrThrow(HttpServletRequest request, int status, String attemptedPermission,
                               UUID actorId) {
        String outcome = switch (status) {
            case 401 -> "unauthenticated";
            // An object-level denial: the caller holds the permission but the resource is not
            // theirs. It answers 404 rather than 403 so it cannot confirm the resource exists
            // (US-07.1.3 AC-6), which is exactly why the trail has to be recorded here — the
            // response deliberately says nothing.
            case 404 -> "not_visible";
            default -> "forbidden";
        };
        String route = routeClass(request);
        String method = request.getMethod();
        String sourceIp = clientIp(request);

        audit.recordSecurityDenial(actorId, "authorization.denied",
                attemptedPermission == null ? "-" : attemptedPermission,
                route, method, outcome, sourceIp);

        Counter.builder("vms.security.denials")
                .description("Authentication and authorization denials")
                .tag("outcome", outcome)          // no user identity in any label
                .tag("route", route)
                .register(meters)
                .increment();

        String tallyKey = actorId != null ? actorId.toString() : ANONYMOUS + ":" + sourceIp;
        int count = tallies.computeIfAbsent(tallyKey, k -> new AtomicInteger()).incrementAndGet();
        if (count == alertThreshold) {
            Counter.builder("vms.security.denial_threshold_exceeded")
                    .description("Principals whose denial count crossed the alert threshold")
                    .register(meters)             // deliberately unlabelled
                    .increment();
        }
    }

    /**
     * Coarse route class for the metric label — the matched pattern where the framework resolved
     * one (e.g. {@code /api/v1/admin/users/{id}}), otherwise the first path segments. Never the raw
     * path, so identifiers cannot leak into metric cardinality.
     */
    private static String routeClass(HttpServletRequest request) {
        Object pattern = request.getAttribute(
                "org.springframework.web.servlet.HandlerMapping.bestMatchingPattern");
        if (pattern instanceof String s && !s.isBlank()) {
            return s;
        }
        String path = request.getRequestURI();
        if (path == null) {
            return "unknown";
        }
        String[] parts = path.split("/");
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < parts.length && i <= 4; i++) {
            sb.append('/').append(parts[i]);
        }
        return sb.isEmpty() ? "/" : sb.toString();
    }

    /** Left-most forwarded address when present, else the socket address. */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            if (!first.isEmpty() && first.length() <= 45) {
                return first;
            }
        }
        return request.getRemoteAddr();
    }
}
