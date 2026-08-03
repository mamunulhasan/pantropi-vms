package com.pantropi.vms.interfaces.rest.visitor;

import com.pantropi.vms.application.visitor.port.ArrivalDirectory;
import com.pantropi.vms.application.visitor.port.OutstandingCards;
import com.pantropi.vms.application.visitor.usecase.FindArrivals;
import com.pantropi.vms.application.visitor.usecase.RecordArrival;
import com.pantropi.vms.domain.visitor.VisitorTransitions;
import com.pantropi.vms.interfaces.rest.security.AuthenticatedPrincipal;
import com.pantropi.vms.interfaces.rest.security.RequiresPermission;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The reception desk at the moment a visitor arrives (EPIC-12) — FR-VMS-04 (SRS B1) and
 * {@code TDD-DERIVED} FR-ENT-10 (TDD §4.4).
 *
 * <p>Guarded by {@code visitor.register}, the desk's permission. AC-5 of US-12.1.1 asks that a
 * caller without it be refused at the API boundary and audited, which the interceptor already does
 * for every route — this one simply declares what it needs.
 *
 * <p>The lookup is a GET and the two lifecycle moves are POSTs, because they are not idempotent in
 * the way a PUT promises: checking a visitor in twice is a duplicate arrival, and the second
 * attempt is refused rather than absorbed.
 *
 * <p>This is also the first read-back the desk has ever had. Pre-registration returned a visitor id
 * exactly once, in the 201 body, and the screen kept its list in memory because nothing could look
 * one up again.
 */
@RestController
@RequestMapping("/api/v1/arrivals")
@RequiresPermission("visitor.register")
@ConditionalOnProperty(prefix = "vms.identity", name = "enabled", havingValue = "true")
public class ArrivalController {

    private final FindArrivals findArrivals;
    private final RecordArrival recordArrival;

    public ArrivalController(FindArrivals findArrivals, RecordArrival recordArrival) {
        this.findArrivals = findArrivals;
        this.recordArrival = recordArrival;
    }

    /** @param search name, company, host, phone or booking reference; blank lists who is due */
    @GetMapping
    public List<ArrivalResponse> search(
            @RequestAttribute(AuthenticatedPrincipal.ATTRIBUTE) AuthenticatedPrincipal principal,
            @RequestParam(required = false) String search) {
        return findArrivals.search(UUID.fromString(principal.userId()), search)
                .stream().map(ArrivalResponse::of).toList();
    }

    @GetMapping("/{visitorId}")
    public ArrivalResponse one(
            @RequestAttribute(AuthenticatedPrincipal.ATTRIBUTE) AuthenticatedPrincipal principal,
            @PathVariable UUID visitorId) {
        return ArrivalResponse.of(findArrivals.byVisitorId(visitorId));
    }

    @PostMapping("/{visitorId}/check-in")
    public RecordedResponse checkIn(
            @RequestAttribute(AuthenticatedPrincipal.ATTRIBUTE) AuthenticatedPrincipal principal,
            @PathVariable UUID visitorId) {
        return RecordedResponse.of(
                recordArrival.checkIn(UUID.fromString(principal.userId()), visitorId));
    }

    @PostMapping("/{visitorId}/check-out")
    public RecordedResponse checkOut(
            @RequestAttribute(AuthenticatedPrincipal.ATTRIBUTE) AuthenticatedPrincipal principal,
            @PathVariable UUID visitorId) {
        return RecordedResponse.of(
                recordArrival.checkOut(UUID.fromString(principal.userId()), visitorId));
    }

    // ---- failures ----

    /**
     * Unknown and out-of-scope answer identically, so an id cannot be used to probe for visitors
     * belonging to another floor (US-03.4.1).
     */
    @ExceptionHandler(FindArrivals.NotFound.class)
    public ResponseEntity<ErrorResponse> onNotFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("not_found", "That visitor is not available from this desk"));
    }

    /**
     * 422 rather than 400: the request was well formed and the caller may not have known. The
     * confirmation's own words are forwarded, because "this visit was rejected" is what the desk
     * has to say out loud and paraphrasing it here would put two versions in circulation.
     */
    @ExceptionHandler(RecordArrival.NotAppointed.class)
    public ResponseEntity<ErrorResponse> onNotAppointed(RecordArrival.NotAppointed e) {
        return ResponseEntity.unprocessableEntity()
                .body(new ErrorResponse("not_appointed", e.getMessage()));
    }

    /** Already arrived, or already gone. 409, and the message names the state (AC-3). */
    @ExceptionHandler(VisitorTransitions.IllegalVisitorTransition.class)
    public ResponseEntity<ErrorResponse> onIllegal(VisitorTransitions.IllegalVisitorTransition e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("illegal_transition", e.getMessage()));
    }

    /** Another desk moved them first. Re-read rather than retry. */
    @ExceptionHandler(RecordArrival.MovedElsewhere.class)
    public ResponseEntity<ErrorResponse> onMoved() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("moved_elsewhere",
                "Someone else moved this visitor while you were deciding. Reload to see where they are."));
    }

    // ---- responses ----

    /**
     * @param outcome  APPOINTED, NOT_APPOINTED, EARLY, ELAPSED or ALREADY_ARRIVED
     * @param reason   plain language for the desk, or null when simply appointed
     * @param mayCheckIn the single boolean a screen should branch on, so each one does not
     *                   re-derive the rule from the outcome and eventually disagree
     */
    public record ArrivalResponse(String visitorId, String requestId, String fullName,
                                  String company, String visitorType, String phone, String host,
                                  String tenant, Instant appointmentFrom, Instant appointmentTo,
                                  String requestStatus, String visitorStatus, Instant checkedInAt,
                                  Instant checkedOutAt, String outcome, String reason,
                                  boolean mayCheckIn) {

        static ArrivalResponse of(FindArrivals.Match m) {
            ArrivalDirectory.Arrival a = m.arrival();
            return new ArrivalResponse(a.visitorId().toString(), a.requestId().toString(),
                    a.fullName(), a.company(), a.visitorType(), a.phone(), a.host(), a.tenant(),
                    a.appointmentFrom(), a.appointmentTo(), a.requestStatus().dbValue(),
                    a.visitorStatus().dbValue(), a.checkedInAt(), a.checkedOutAt(),
                    m.confirmation().outcome().name(), m.confirmation().reason(),
                    m.confirmation().mayProceed());
        }
    }

    /** @param outstandingCards empty on check-in; on check-out, what to ask for before they go */
    public record RecordedResponse(String visitorId, String status, Instant at,
                                   List<CardResponse> outstandingCards) {

        static RecordedResponse of(RecordArrival.Arrival a) {
            return new RecordedResponse(a.visitorId().toString(), a.status().dbValue(), a.at(),
                    a.outstandingCards().stream().map(CardResponse::of).toList());
        }
    }

    public record CardResponse(String issuanceId, String acsCardId, Instant issuedAt) {

        static CardResponse of(OutstandingCards.Card c) {
            return new CardResponse(c.issuanceId().toString(), c.acsCardId(), c.issuedAt());
        }
    }

    public record ErrorResponse(String error, String detail) {}
}
