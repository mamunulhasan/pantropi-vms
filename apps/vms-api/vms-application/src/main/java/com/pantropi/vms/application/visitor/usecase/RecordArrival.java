package com.pantropi.vms.application.visitor.usecase;

import com.pantropi.vms.application.identity.port.AuditTrail;
import com.pantropi.vms.application.shared.port.ClockPort;
import com.pantropi.vms.application.shared.port.TransactionRunner;
import com.pantropi.vms.application.visitor.port.ArrivalDirectory;
import com.pantropi.vms.application.visitor.port.DomainEventPublisher;
import com.pantropi.vms.application.visitor.port.OutstandingCards;
import com.pantropi.vms.application.visitor.port.VisitorLifecycle;
import com.pantropi.vms.domain.visitor.AppointmentConfirmation;
import com.pantropi.vms.domain.visitor.TimeWindow;
import com.pantropi.vms.domain.visitor.VisitorStatus;
import com.pantropi.vms.domain.visitor.VisitorTransitions;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Checking a visitor in and out (US-12.2.1, US-12.2.2) — {@code TDD-DERIVED} FR-ENT-10 (TDD §4.4),
 * supports FR-CRD-03 (SRS B1).
 *
 * <p><strong>Provenance, stated rather than assumed.</strong> F-12.2 is {@code TDD-DERIVED} and the
 * backlog marks it <em>blocked by TODO-01</em>, because FR-ENT-10 is undefined in the attached SRS
 * (D-03). It is built here as an approved enabler on the owner's instruction, exactly as ADR-0004
 * authorised EPIC-03/04 and ADR-0006 authorised F-06.2/F-06.3. The status columns have existed
 * since V1 and nothing has ever written them; until now "who is in the building" was not a hard
 * question but an unanswerable one.
 *
 * <p>Check-in is refused for anyone the confirmation does not call appointed (AC-4). That is the
 * load-bearing rule: check-in records an arrival, it is not a route around approval — and a desk
 * under time pressure is exactly where that shortcut would otherwise be taken.
 *
 * <p>Both transitions are compare-and-set on the status the caller decided from. Two receptionists
 * can have the same visitor open; the database is where they meet, and the loser is told rather
 * than silently overwriting a timestamp somebody else just wrote (AC-3 of both stories).
 */
public final class RecordArrival {

    private final ArrivalDirectory directory;
    private final VisitorLifecycle lifecycle;
    private final OutstandingCards cards;
    private final DomainEventPublisher events;
    private final AuditTrail audit;
    private final TransactionRunner tx;
    private final ClockPort clock;

    public RecordArrival(ArrivalDirectory directory, VisitorLifecycle lifecycle,
                         OutstandingCards cards, DomainEventPublisher events, AuditTrail audit,
                         TransactionRunner tx, ClockPort clock) {
        this.directory = directory;
        this.lifecycle = lifecycle;
        this.cards = cards;
        this.events = events;
        this.audit = audit;
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * @throws FindArrivals.NotFound unknown, or outside the caller's scope
     * @throws NotAppointed          the confirmation does not permit entry (AC-4)
     * @throws VisitorTransitions.IllegalVisitorTransition already arrived, or already gone (AC-3)
     * @throws MovedElsewhere        another desk got there first
     */
    public Arrival checkIn(UUID actor, UUID visitorId) {
        ArrivalDirectory.Arrival a = directory.byVisitorId(visitorId)
                .orElseThrow(() -> new FindArrivals.NotFound(visitorId));

        Instant now = clock.now();
        AppointmentConfirmation confirmation = confirm(a, now);

        // Two different refusals, and they are not interchangeable.
        //
        // Someone who has already arrived is a CONFLICT about state: the desk should re-read and
        // see where that person actually is. Someone who is not entitled to enter is a refusal
        // about the visit itself, and the desk has to say why out loud. Routing both through the
        // confirmation would report a duplicate arrival as "not appointed", which is both wrong
        // and unhelpful — the visitor is very much appointed, they are already inside.
        if (confirmation.outcome() == AppointmentConfirmation.Outcome.ALREADY_ARRIVED) {
            throw new VisitorTransitions.IllegalVisitorTransition(a.visitorStatus(),
                    VisitorStatus.CHECKED_IN);
        }
        // AC-4. The transition table would refuse an unapproved visitor anyway, but the reason a
        // desk needs is "this visit was rejected", not "pending cannot become checked_in".
        if (!confirmation.mayProceed()) {
            throw new NotAppointed(confirmation);
        }
        VisitorTransitions.require(a.visitorStatus(), VisitorStatus.CHECKED_IN);

        return tx.call(() -> {
            if (!lifecycle.transition(visitorId, a.visitorStatus(), VisitorStatus.CHECKED_IN, now)) {
                throw new MovedElsewhere(visitorId);
            }
            audit.recordChange(actor, "visitor.check_in", "visitor", visitorId.toString(),
                    "{\"status\":\"" + a.visitorStatus().dbValue() + "\"}",
                    "{\"status\":\"checked_in\",\"at\":\"" + now + "\"}");
            events.publish("VisitorCheckedIn", "visitor", visitorId,
                    "{\"visitorId\":\"" + visitorId + "\",\"requestId\":\"" + a.requestId()
                            + "\",\"at\":\"" + now + "\",\"by\":\"" + actor + "\"}");
            return new Arrival(visitorId, VisitorStatus.CHECKED_IN, now, List.of());
        });
    }

    /**
     * Departure, and the last chance to ask for the card back.
     *
     * <p>The outstanding-card list is read inside the transaction and returned with the result, but
     * never gates it (AC-2): the visitor is leaving either way, and a desk that could not record a
     * departure until a card turned up would produce a wrong on-site roll rather than a returned
     * card. Recovering it is F-15.4's job.
     */
    public Arrival checkOut(UUID actor, UUID visitorId) {
        ArrivalDirectory.Arrival a = directory.byVisitorId(visitorId)
                .orElseThrow(() -> new FindArrivals.NotFound(visitorId));

        Instant now = clock.now();
        VisitorTransitions.require(a.visitorStatus(), VisitorStatus.CHECKED_OUT);

        return tx.call(() -> {
            if (!lifecycle.transition(visitorId, a.visitorStatus(), VisitorStatus.CHECKED_OUT,
                    now)) {
                throw new MovedElsewhere(visitorId);
            }
            List<OutstandingCards.Card> held = cards.heldBy(visitorId);

            audit.recordChange(actor, "visitor.check_out", "visitor", visitorId.toString(),
                    "{\"status\":\"" + a.visitorStatus().dbValue() + "\"}",
                    "{\"status\":\"checked_out\",\"at\":\"" + now + "\",\"cardsOutstanding\":"
                            + held.size() + "}");
            events.publish("VisitorCheckedOut", "visitor", visitorId,
                    "{\"visitorId\":\"" + visitorId + "\",\"requestId\":\"" + a.requestId()
                            + "\",\"at\":\"" + now + "\",\"by\":\"" + actor
                            + "\",\"cardsOutstanding\":" + held.size() + "}");
            return new Arrival(visitorId, VisitorStatus.CHECKED_OUT, now, held);
        });
    }

    private static AppointmentConfirmation confirm(ArrivalDirectory.Arrival a, Instant now) {
        TimeWindow window = a.appointmentFrom() == null || a.appointmentTo() == null
                ? null
                : new TimeWindow(a.appointmentFrom(), a.appointmentTo());
        return AppointmentConfirmation.evaluate(a.requestStatus(), a.visitorStatus(), window, now);
    }

    /** @param outstandingCards empty on check-in; on check-out, what the visitor still holds */
    public record Arrival(UUID visitorId, VisitorStatus status, Instant at,
                          List<OutstandingCards.Card> outstandingCards) {}

    /** The confirmation refused entry, and carries the plain-language reason the desk shows. */
    public static final class NotAppointed extends IllegalStateException {
        public final transient AppointmentConfirmation confirmation;

        public NotAppointed(AppointmentConfirmation confirmation) {
            super(confirmation.reason() == null
                    ? "This visitor is not appointed" : confirmation.reason());
            this.confirmation = confirmation;
        }
    }

    /** Lost the race with another desk. The current state is worth re-reading, so 409. */
    public static final class MovedElsewhere extends IllegalStateException {
        public MovedElsewhere(UUID visitorId) {
            super("Visitor " + visitorId + " was moved by someone else while you were deciding");
        }
    }
}
