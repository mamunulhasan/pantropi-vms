package com.pantropi.vms.interfaces.rest.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

import java.io.IOException;
import java.net.URI;

/**
 * The single renderer for every error body (US-03.2.2, T-03.2.2.1) — RFC 7807 problem detail.
 *
 * <p>Used by both the {@link GlobalExceptionHandler} (exceptions from handlers) and the
 * authorization interceptor (denials before any handler runs), so the two paths cannot drift into
 * different shapes.
 *
 * <p>The body carries only a stable type, a fixed generic title, the status and the correlation id.
 * It never carries a stack trace, an internal class name, a SQL fragment, or anything indicating
 * whether the target resource exists (AC-1, AC-4).
 */
public final class ProblemDetails {

    /** Fixed, non-disclosing titles keyed by status. */
    private static final String TYPE_BASE = "https://pantropi.com/vms/problems/";

    private ProblemDetails() {
    }

    public static ProblemDetail of(HttpStatus status, String correlationId) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(URI.create(TYPE_BASE + slug(status)));
        problem.setTitle(title(status));
        problem.setDetail(title(status));   // deliberately identical: no per-case detail leaks
        problem.setProperty("correlationId", correlationId);
        return problem;
    }

    /**
     * Write a problem detail straight to the response — used where no handler will run, so no
     * exception can be thrown for the advice to translate.
     */
    public static void write(HttpServletRequest request, HttpServletResponse response,
                             HttpStatus status, String correlationId) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{"
                + "\"type\":\"" + TYPE_BASE + slug(status) + "\","
                + "\"title\":\"" + title(status) + "\","
                + "\"status\":" + status.value() + ","
                + "\"detail\":\"" + title(status) + "\","
                + "\"correlationId\":\"" + correlationId + "\""
                + "}");
        response.getWriter().flush();
    }

    /**
     * Generic titles. 403 and 404 share nothing that would let a caller distinguish "exists but
     * forbidden" from "does not exist" beyond the status itself, and callers lacking read authority
     * receive 403 for both (T-03.2.2.1).
     */
    private static String title(HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST -> "The request is invalid.";
            case UNAUTHORIZED -> "Authentication is required.";
            case FORBIDDEN -> "You do not have access to this resource.";
            case NOT_FOUND -> "You do not have access to this resource.";
            case CONFLICT -> "The request conflicts with the current state.";
            case UNPROCESSABLE_ENTITY -> "The request could not be processed.";
            default -> "An unexpected error occurred.";
        };
    }

    private static String slug(HttpStatus status) {
        return status.name().toLowerCase().replace('_', '-');
    }
}
