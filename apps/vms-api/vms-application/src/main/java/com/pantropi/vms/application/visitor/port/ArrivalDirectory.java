package com.pantropi.vms.application.visitor.port;

import com.pantropi.vms.domain.visitor.RequestStatus;
import com.pantropi.vms.domain.visitor.VisitorStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Finding the person standing at the desk (US-12.1.1) — FR-VMS-04 (SRS B1).
 *
 * <p>A read model, not a repository: it answers "who is this" for a receptionist under time
 * pressure and never writes. The visitor aggregate is loaded through
 * {@link VisitorRequestRepository} when something has to change.
 *
 * <p>Until this existed the reception desk had <em>no</em> read-back path at all — its screen kept
 * the session's registrations in memory and said so, because nothing could look a visitor up again.
 * That is the gap this closes.
 */
public interface ArrivalDirectory {

    /**
     * @param search matched against visitor name, company, host name, phone and the request id —
     *               the five fields AC-1 names. Blank returns today's expected arrivals.
     */
    List<Arrival> search(String search, Instant now, int limit);

    Optional<Arrival> byVisitorId(UUID visitorId);

    /**
     * One row of the arrival list.
     *
     * <p>Carries enough to tell two people of the same name apart without opening either (AC-2):
     * host, tenant and the appointment window. It deliberately does not carry the email or the
     * identity-document reference — a desk needs to recognise somebody, not to contact them, and
     * TODO-13 keeps document references out of every endpoint in this phase.
     */
    record Arrival(UUID visitorId, UUID requestId, String fullName, String company,
                   String visitorType, String phone, String host, String tenant,
                   Instant appointmentFrom, Instant appointmentTo,
                   RequestStatus requestStatus, VisitorStatus visitorStatus,
                   Instant checkedInAt, Instant checkedOutAt) {}
}
