package com.pantropi.vms.application.visitor.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Cards a visitor still holds (US-12.2.2 AC-2, T-12.2.2.2) — supports FR-CRD-03 (SRS B1).
 *
 * <p>Read at check-out because that is the last moment anyone will see the person: an unreturned
 * card discovered the next morning is a card nobody is going to get back.
 *
 * <p><strong>It never blocks the check-out.</strong> The visitor is leaving either way, and a desk
 * that cannot record a departure until a card is found produces an inaccurate on-site roll rather
 * than a returned card. The discrepancy is surfaced and recorded; recovering it is F-15.4's job.
 *
 * <p>Today this always answers empty: {@code vms.card_issuances} has no writer yet, because card
 * issuance is F-15.1 and unbuilt. The query is here so the moment it gains rows the desk sees them,
 * rather than the reminder being retrofitted after the first card goes missing.
 */
public interface OutstandingCards {

    List<Card> heldBy(UUID visitorId);

    record Card(UUID issuanceId, String acsCardId, Instant issuedAt) {}
}
