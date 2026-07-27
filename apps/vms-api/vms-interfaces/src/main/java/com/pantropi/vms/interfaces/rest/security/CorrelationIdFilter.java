package com.pantropi.vms.interfaces.rest.security;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.UUID;

/**
 * Assigns every request a correlation id (US-03.2.2, T-03.2.2.1).
 *
 * <p>The id appears in the error body and the server log, so an operator can join a user-reported
 * failure to its log line without the response ever carrying diagnostic detail. An inbound
 * {@code X-Correlation-Id} is honoured when it is a plausible id, otherwise one is generated —
 * client-supplied text never reaches a log or a body unsanitised.
 */
public final class CorrelationIdFilter implements Filter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String ATTRIBUTE = "vms.correlationId";

    private static final int MAX_LENGTH = 64;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest http = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String correlationId = sanitize(http.getHeader(HEADER));
        request.setAttribute(ATTRIBUTE, correlationId);
        httpResponse.setHeader(HEADER, correlationId);

        chain.doFilter(request, response);
    }

    /** Accept only a short, safe token; anything else is replaced with a fresh id. */
    private static String sanitize(String supplied) {
        if (supplied == null || supplied.isBlank() || supplied.length() > MAX_LENGTH) {
            return UUID.randomUUID().toString();
        }
        for (int i = 0; i < supplied.length(); i++) {
            char c = supplied.charAt(i);
            boolean safe = Character.isLetterOrDigit(c) || c == '-' || c == '_';
            if (!safe) {
                return UUID.randomUUID().toString();
            }
        }
        return supplied;
    }

    /** Correlation id for the current request; never null once the filter has run. */
    public static String of(HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        return value instanceof String s ? s : "unassigned";
    }
}
