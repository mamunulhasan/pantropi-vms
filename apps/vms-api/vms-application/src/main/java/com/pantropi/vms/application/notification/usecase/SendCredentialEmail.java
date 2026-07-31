package com.pantropi.vms.application.notification.usecase;

import com.pantropi.vms.application.notification.port.NotificationLog;
import com.pantropi.vms.application.notification.port.NotificationSender;
import com.pantropi.vms.application.notification.port.VisitorContacts;

import java.time.Instant;
import java.util.UUID;

/**
 * Emails a visitor that their pass is ready (US-09.6.1).
 *
 * <p>Three things are deliberate here.
 *
 * <p><strong>The recipient is looked up, never passed in</strong> (AC-6). {@link Command} has no
 * recipient field at all, so there is no argument a caller could supply to redirect the mail. The
 * only address this can reach is the one on the visitor's own record.
 *
 * <p><strong>The QR payload is not in the message.</strong> The email says a pass exists and where
 * to view it; the scannable secret stays behind the pass endpoint. Mail is stored, forwarded and
 * indexed by systems nobody here controls, and a payload that opens a barrier does not belong in
 * one. This is a narrowing of the obvious reading of US-09.6.1 and is listed as such.
 *
 * <p><strong>Failure is reported, not thrown</strong> (US-17.1.1 AC-6). The credential exists
 * whether or not the mail went; unwinding an issued pass because a mail server was down would be
 * the notification tail wagging the access-control dog. Callers get an {@link Outcome} to log.
 */
public final class SendCredentialEmail {

    /** Mirrors {@code vms.delivery_status} for the log, plus the reason nothing was attempted. */
    public enum Outcome { SENT, FAILED, DISABLED, NO_ADDRESS, NO_VISITOR }

    private static final String CHANNEL = "email";
    private static final String NOTIFY_TYPE = "confirmation";
    private static final String BODY_REF = "credential-issued";

    private final VisitorContacts contacts;
    private final NotificationSender sender;
    private final NotificationLog log;
    private final boolean enabled;

    /**
     * @param enabled {@code notification.email.enabled}. When false nothing is sent and nothing is
     *                raised (US-16.2.1 AC-3) — a switched-off channel is a configuration, not a
     *                fault, and must not turn into an error on a path that had nothing to do with it.
     */
    public SendCredentialEmail(VisitorContacts contacts, NotificationSender sender,
                               NotificationLog log, boolean enabled) {
        this.contacts = contacts;
        this.sender = sender;
        this.log = log;
        this.enabled = enabled;
    }

    public Result send(Command command) {
        if (!enabled) {
            return new Result(Outcome.DISABLED, null);
        }
        VisitorContacts.Contact contact = contacts.findById(command.visitorId()).orElse(null);
        if (contact == null) {
            return new Result(Outcome.NO_VISITOR, null);
        }
        if (contact.email() == null || contact.email().isBlank()) {
            // Email is optional on vms.visitors, so this is an ordinary case rather than an error.
            // Logged so an operator can see the pass was issued and deliberately not emailed.
            log.record(command.visitorId(), "(none)", CHANNEL, NOTIFY_TYPE, subject(), BODY_REF,
                    "failed");
            return new Result(Outcome.NO_ADDRESS, null);
        }

        try {
            NotificationSender.Sent sent = sender.send(new NotificationSender.Message(
                    contact.email(), subject(), body(contact, command), BODY_REF));
            log.record(command.visitorId(), contact.email(), CHANNEL, NOTIFY_TYPE, subject(),
                    BODY_REF, "sent");
            return new Result(Outcome.SENT, sent.reference());
        } catch (RuntimeException e) {
            // Swallowed on purpose. See the class comment: issuance already happened.
            log.record(command.visitorId(), contact.email(), CHANNEL, NOTIFY_TYPE, subject(),
                    BODY_REF, "failed");
            return new Result(Outcome.FAILED, null);
        }
    }

    private static String subject() {
        return "Your visitor pass is ready";
    }

    private static String body(VisitorContacts.Contact contact, Command command) {
        return """
                Dear %s,

                Your visitor pass has been issued and is valid from %s until %s.

                Please present the pass shown to you at reception on arrival. For security, the
                pass itself is not included in this message.

                Westgate Tower
                """.formatted(contact.fullName(), command.validFrom(), command.validTo());
    }

    /** No recipient field, and there must never be one — see the class comment (AC-6). */
    public record Command(UUID visitorId, UUID credentialId, Instant validFrom, Instant validTo) {}

    /** @param reference where it went, when it went — a file path today */
    public record Result(Outcome outcome, String reference) {}
}
