package com.pantropi.vms.interfaces.rest.error;

import com.pantropi.vms.application.identity.usecase.AccountActivation;
import com.pantropi.vms.application.identity.usecase.AuthenticateUser;
import com.pantropi.vms.application.identity.usecase.SessionManager;
import com.pantropi.vms.application.identity.usecase.UserAdministration;
import com.pantropi.vms.application.identity.usecase.UserImport;
import com.pantropi.vms.interfaces.rest.security.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The single translation point from exception to HTTP response (US-03.2.2, T-03.2.2.1).
 *
 * <p>Every body is an RFC 7807 problem detail of a fixed shape carrying only the status, a generic
 * title and the correlation id — never a stack trace, an internal class name or a SQL fragment
 * (AC-1). An unhandled exception becomes a generic 500 whose detail exists solely in the server log,
 * joined to the response by the correlation id (AC-4).
 *
 * <p>Deliberately terse: business failures that a caller may legitimately act on (a duplicate
 * username, an invalid field) keep their status, but never echo values back, so a body carrying
 * visitor personal data cannot be reflected into a response or a log (AC-5).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = Logger.getLogger(GlobalExceptionHandler.class.getName());

    // ---- authentication / session ----

    @ExceptionHandler({AuthenticateUser.InvalidCredentials.class,
            SessionManager.InvalidRefreshToken.class,
            AccountActivation.InvalidToken.class})
    public ProblemDetail onUnauthorized(HttpServletRequest request) {
        return ProblemDetails.of(HttpStatus.UNAUTHORIZED, CorrelationIdFilter.of(request));
    }

    // ---- validation ----

    @ExceptionHandler({UserAdministration.InvalidField.class,
            UserAdministration.InvalidReference.class,
            AccountActivation.WeakPassword.class,
            UserImport.MalformedFile.class,
            HttpMessageNotReadableException.class,
            IllegalArgumentException.class})
    public ProblemDetail onBadRequest(HttpServletRequest request) {
        return ProblemDetails.of(HttpStatus.BAD_REQUEST, CorrelationIdFilter.of(request));
    }

    @ExceptionHandler(UserImport.PasswordColumnRejected.class)
    public ProblemDetail onUnprocessable(HttpServletRequest request) {
        return ProblemDetails.of(HttpStatus.UNPROCESSABLE_ENTITY, CorrelationIdFilter.of(request));
    }

    @ExceptionHandler(UserAdministration.DuplicateField.class)
    public ProblemDetail onConflict(HttpServletRequest request) {
        return ProblemDetails.of(HttpStatus.CONFLICT, CorrelationIdFilter.of(request));
    }

    /**
     * A resource the caller may not see and a resource that does not exist are reported identically,
     * so absence cannot be probed (T-03.2.2.1).
     */
    @ExceptionHandler(UserAdministration.UserNotFound.class)
    public ProblemDetail onNotFound(HttpServletRequest request) {
        return ProblemDetails.of(HttpStatus.NOT_FOUND, CorrelationIdFilter.of(request));
    }

    /**
     * Framework exceptions that already carry a status — an unmapped path, an unsupported method —
     * keep it. Without this the catch-all below would turn every 404 into a 500.
     */
    @ExceptionHandler({ErrorResponseException.class, NoResourceFoundException.class,
            NoHandlerFoundException.class})
    public ProblemDetail onFrameworkError(HttpServletRequest request, Exception e) {
        HttpStatus status = e instanceof ErrorResponse er
                ? HttpStatus.valueOf(er.getStatusCode().value())
                : HttpStatus.NOT_FOUND;
        return ProblemDetails.of(status, CorrelationIdFilter.of(request));
    }

    // ---- anything else ----

    @ExceptionHandler(Exception.class)
    public ProblemDetail onUnexpected(HttpServletRequest request, Exception e) {
        String correlationId = CorrelationIdFilter.of(request);
        // Detail stays server-side. The message may embed caller input, so it is logged at the
        // exception level only and never returned.
        log.log(Level.SEVERE, "Unhandled exception [correlationId=" + correlationId
                + "] route=" + request.getMethod() + " " + request.getRequestURI(), e);
        return ProblemDetails.of(HttpStatus.INTERNAL_SERVER_ERROR, correlationId);
    }
}
