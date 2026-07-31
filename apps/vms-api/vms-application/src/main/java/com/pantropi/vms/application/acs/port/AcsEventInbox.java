package com.pantropi.vms.application.acs.port;

import java.time.Instant;
import java.util.UUID;

/**
 * The inbound half of the ACS boundary (US-11.1.1 AC-1): what ACS pushes at VMS.
 *
 * <p>Separate from {@link AcsPort} because the direction is reversed. {@code AcsPort} is
 * implemented by the adapter and called by VMS; this is implemented by VMS and called by the
 * adapter. One interface carrying both would force every implementor to stub the half it does not
 * own, and would make the dependency arrow point both ways at once.
 *
 * <p><strong>Every event carries {@code acsEventId}</strong>, and that is not decoration.
 * {@code vms.access_events.acs_event_id} is {@code NOT NULL UNIQUE} precisely so a redelivered
 * event is absorbed rather than double-counted, and ADR-0002 decision 5 requires inbound handling
 * to be idempotent on it. Any delivery mechanism the contract eventually specifies — webhook,
 * poll, queue — will redeliver eventually; the handler must already not care.
 *
 * <p>These carry identifiers and timing only. No visitor name, email or phone crosses this
 * boundary in either direction: ACS has no need of them, and the outbox comment in
 * {@code vms.domain_events} makes the same promise about what leaves VMS.
 */
public interface AcsEventInbox {

    /** Someone presented a credential at a reader and it was accepted or refused. */
    void accessEventReceived(AccessEvent event);

    /** A physical card was handed out or handed back — {@code vms.card_issuances}' side of the story. */
    void cardEventReceived(CardEvent event);

    /** Which way through the barrier, in the vocabulary {@code vms.access_direction} already uses. */
    enum Direction {
        ENTRY,
        EXIT
    }

    /**
     * @param acsEventId the deduplication key — the same event twice must land once
     * @param granted    whether the barrier opened; a refusal is as much a fact worth recording as
     *                   an admission, and losing it would leave a hole in the audit story
     */
    record AccessEvent(String acsEventId, AcsPort.AcsCredentialReference credential,
                       Direction direction, boolean granted, Instant occurredAt) {

        public AccessEvent {
            requireEventId(acsEventId);
            if (credential == null || direction == null || occurredAt == null) {
                throw new IllegalArgumentException("an access event needs a credential, a direction and a time");
            }
        }
    }

    /**
     * @param visitorId the visitor the card is associated with, when ACS knows it
     * @param returned  true when handed back, false when handed out
     */
    record CardEvent(String acsEventId, String cardId, UUID visitorId, boolean returned,
                     Instant occurredAt) {

        public CardEvent {
            requireEventId(acsEventId);
            if (cardId == null || cardId.isBlank() || occurredAt == null) {
                throw new IllegalArgumentException("a card event needs a card and a time");
            }
        }
    }

    private static void requireEventId(String acsEventId) {
        if (acsEventId == null || acsEventId.isBlank()) {
            // Without it there is no way to tell a redelivery from a new event, and the uniqueness
            // constraint downstream would be the first thing to notice — far too late.
            throw new IllegalArgumentException("an ACS event must carry its own id, for idempotency");
        }
    }
}
