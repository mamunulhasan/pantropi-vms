package com.pantropi.vms.application.visitor.usecase;

import com.pantropi.vms.application.identity.port.AuditTrail;
import com.pantropi.vms.application.shared.port.ClockPort;
import com.pantropi.vms.application.visitor.port.ArrivalDirectory;
import com.pantropi.vms.domain.visitor.AppointmentConfirmation;

import java.util.List;
import java.util.UUID;

/**
 * The arrival lookup, and the one confirmation the desk acts on (US-12.1.1, US-12.1.2) —
 * FR-VMS-04 (SRS B1).
 *
 * <p>Search and confirmation are one use case because they are one question. A receptionist is not
 * asking "does this record exist" — they are asking "may this person come in", and splitting that
 * across two calls invites a screen to answer the first and improvise the second.
 *
 * <p><strong>Every search is audited</strong> (T-12.1.1.3), with the terms and the result count and
 * not the results. Who was looked up matters for an incident review; a copy of the personal data
 * that came back does not belong in a second table.
 */
public final class FindArrivals {

    /** A desk scanning a queue does not need page 4; a search that broad is the wrong search. */
    private static final int LIMIT = 25;

    private final ArrivalDirectory directory;
    private final AuditTrail audit;
    private final ClockPort clock;

    public FindArrivals(ArrivalDirectory directory, AuditTrail audit, ClockPort clock) {
        this.directory = directory;
        this.audit = audit;
        this.clock = clock;
    }

    public List<Match> search(UUID actor, String terms) {
        String cleaned = terms == null ? "" : terms.trim();
        List<ArrivalDirectory.Arrival> found = directory.search(cleaned, clock.now(), LIMIT);

        // The terms and the count. Never the names that came back. Plain text rather than JSON:
        // AuditTrail.record wraps and escapes whatever it is given, so JSON here would arrive
        // double-encoded and unreadable to the person the trail exists for.
        audit.record(actor, "visitor.arrival_search", "visitor", null,
                "terms=" + cleaned + " results=" + found.size());

        return found.stream().map(this::confirm).toList();
    }

    /** @throws NotFound when the id is unknown, or outside the caller's scope — the same answer */
    public Match byVisitorId(UUID visitorId) {
        return directory.byVisitorId(visitorId).map(this::confirm)
                .orElseThrow(() -> new NotFound(visitorId));
    }

    private Match confirm(ArrivalDirectory.Arrival a) {
        AppointmentConfirmation confirmation = AppointmentConfirmation.evaluate(
                a.requestStatus(), a.visitorStatus(),
                a.appointmentFrom() == null || a.appointmentTo() == null
                        ? null
                        : new com.pantropi.vms.domain.visitor.TimeWindow(a.appointmentFrom(),
                                a.appointmentTo()),
                clock.now());
        return new Match(a, confirmation);
    }

    public record Match(ArrivalDirectory.Arrival arrival, AppointmentConfirmation confirmation) {}

    /** Unknown and out-of-scope answer identically, so an id cannot be used to probe (US-03.4.1). */
    public static final class NotFound extends RuntimeException {
        public NotFound(UUID visitorId) {
            super("No arriving visitor " + visitorId);
        }
    }
}
