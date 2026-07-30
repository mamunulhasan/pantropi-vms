package com.pantropi.vms.interfaces.rest.visitor;

import com.pantropi.vms.application.visitor.usecase.PreRegisterVisitor;
import com.pantropi.vms.application.visitor.usecase.SubmitVisitorRequest;
import com.pantropi.vms.domain.visitor.TimeWindow;
import com.pantropi.vms.domain.visitor.Visitor;
import com.pantropi.vms.interfaces.rest.security.AuthenticatedPrincipal;
import com.pantropi.vms.interfaces.rest.security.AuthorizationDenialRecorder;
import com.pantropi.vms.interfaces.rest.security.RequiresPermission;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

/**
 * Pre-registration from the floor reception desk (US-08.1.1, T-08.1.1.3) — FR-VMS-03 (SRS B1).
 *
 * <p>{@code POST /api/v1/pre-registrations}, guarded by {@code visitor.register} — the permission
 * FLOOR_RECEPTIONIST holds and nobody else does. A separate controller from
 * {@link VisitorRequestController} because it is a separate entry path with a different actor and a
 * different authority, even though both end at the same aggregate.
 *
 * <p>The tenant and the floor are resolved from the receptionist's own station, never asserted by the
 * body. The body may <em>name</em> a tenant, which the use case checks against the floor they are
 * actually stationed on — see {@link PreRegisterVisitor} for why that is necessary and why it is
 * still safe.
 */
@RestController
@RequestMapping("/api/v1/pre-registrations")
@RequiresPermission("visitor.register")
@ConditionalOnProperty(prefix = "vms.identity", name = "enabled", havingValue = "true")
public class PreRegistrationController {

    private final PreRegisterVisitor preRegisterVisitor;
    private final AuthorizationDenialRecorder denials;

    public PreRegistrationController(PreRegisterVisitor preRegisterVisitor,
                                     AuthorizationDenialRecorder denials) {
        this.preRegisterVisitor = preRegisterVisitor;
        this.denials = denials;
    }

    @PostMapping
    public ResponseEntity<RegisteredResponse> preRegister(
            @RequestAttribute(AuthenticatedPrincipal.ATTRIBUTE) AuthenticatedPrincipal principal,
            @RequestBody PreRegistrationRequest body) {

        if (body == null) {
            return ResponseEntity.badRequest().build();
        }

        PreRegisterVisitor.Registered result = preRegisterVisitor.preRegister(
                UUID.fromString(principal.userId()),
                new PreRegisterVisitor.Command(body.fullName(), body.email(), body.phone(),
                        body.company(), body.visitorTypeId(), body.hostId(), body.tenantId(),
                        body.purpose(), body.appointmentFrom(), body.appointmentTo()));

        return ResponseEntity
                .created(URI.create("/api/v1/visitor-requests/" + result.requestId()))
                .body(new RegisteredResponse(result.requestId().toString(),
                        result.visitorId().toString(), result.tenantId().toString(),
                        result.receptionId().toString()));
    }

    /**
     * AC-6: reaching past your own floor is a 403, and it is audited.
     *
     * <p>403 rather than 404 because a tenant is not a secret the way a request id is. The
     * receptionist is entitled to know their own floor's tenants, so refusing plainly tells an honest
     * caller what is wrong while still leaving a record of the attempt.
     */
    @ExceptionHandler(PreRegisterVisitor.ForeignFloor.class)
    public ResponseEntity<Problem> onForeignFloor(HttpServletRequest request,
            @RequestAttribute(name = AuthenticatedPrincipal.ATTRIBUTE, required = false)
            AuthenticatedPrincipal principal) {

        denials.record(request, HttpStatus.FORBIDDEN.value(), "visitor.register.own_floor",
                principal == null ? null : UUID.fromString(principal.userId()));
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new Problem("foreign_floor", "That tenant is not on your floor"));
    }

    /**
     * The station could not answer, so there is nothing to derive from (AC-2).
     *
     * <p>409 rather than 400: the request was well-formed and the caller is who they say they are —
     * what is wrong is the state of their account, and the fix is an administrator assigning them a
     * reception, not a different request body.
     */
    @ExceptionHandler({PreRegisterVisitor.NotStationed.class,
            PreRegisterVisitor.NoTenantOnFloor.class})
    public ResponseEntity<Problem> onUnresolvableStation(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new Problem("station_unresolved", e.getMessage()));
    }

    /**
     * The floor hosts several tenants and none was named (TODO-20).
     *
     * <p>The count is disclosed because it is the caller's own floor and it tells them what to do
     * next; the tenants themselves are not, because listing them is master data's job and this
     * endpoint is not a directory.
     */
    @ExceptionHandler(PreRegisterVisitor.TenantNotSpecified.class)
    public ResponseEntity<Problem> onTenantNotSpecified(PreRegisterVisitor.TenantNotSpecified e) {
        return ResponseEntity.badRequest().body(new Problem("tenant_required", e.getMessage()));
    }

    /**
     * AC-5: an inverted window is a 400, a stale one a 422.
     *
     * <p>The distinction is whether retrying could ever work. An inverted window is malformed input.
     * A window that started too long ago was well-formed and simply describes the wrong day, which is
     * a semantic problem with the content — and 422 says so.
     */
    @ExceptionHandler(TimeWindow.InvalidTimeWindow.class)
    public ResponseEntity<Problem> onInvalidWindow(TimeWindow.InvalidTimeWindow e) {
        return ResponseEntity.badRequest().body(new Problem("invalid_window", e.getMessage()));
    }

    @ExceptionHandler(PreRegisterVisitor.AppointmentInPast.class)
    public ResponseEntity<Problem> onStaleAppointment(PreRegisterVisitor.AppointmentInPast e) {
        return ResponseEntity.unprocessableEntity()
                .body(new Problem("appointment_in_past", e.getMessage()));
    }

    /**
     * A malformed visitor detail, an unusable visitor type, or a host outside the tenant.
     *
     * <p>Names the field and never the value — the exception types carry the field name so a mistyped
     * email cannot be reflected into a response or a log (US-07.1.2 AC-4).
     */
    @ExceptionHandler({Visitor.InvalidVisitorDetail.class,
            SubmitVisitorRequest.UnknownVisitorType.class,
            SubmitVisitorRequest.HostNotInTenant.class})
    public ResponseEntity<Problem> onInvalidDetail(RuntimeException e) {
        String field = e instanceof Visitor.InvalidVisitorDetail d ? d.field()
                : e instanceof SubmitVisitorRequest.UnknownVisitorType ? "visitorTypeId" : "hostId";
        return ResponseEntity.badRequest().body(new Problem("invalid", field));
    }

    /**
     * Pre-registration payload.
     *
     * <p>Note what is absent: no {@code floorId}, {@code receptionId}, {@code status} or
     * {@code approvedBy}. Those are server-determined and there is nowhere for them to bind, so a
     * client sending them has them ignored (US-07.1.1 AC-5).
     *
     * @param tenantId optional; required only when the floor hosts more than one tenant, and always
     *                 checked against the caller's own floor
     */
    public record PreRegistrationRequest(String fullName, String email, String phone, String company,
                                         UUID visitorTypeId, UUID hostId, UUID tenantId,
                                         String purpose,
                                         @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                         Instant appointmentFrom,
                                         @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                                         Instant appointmentTo) {}

    public record RegisteredResponse(String requestId, String visitorId, String tenantId,
                                     String receptionId) {}

    public record Problem(String error, String detail) {}
}
