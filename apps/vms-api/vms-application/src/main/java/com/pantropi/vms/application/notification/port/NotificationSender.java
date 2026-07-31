package com.pantropi.vms.application.notification.port;

/**
 * The way out of the building for a message (US-09.6.1, FR-NOT-01..05).
 *
 * <p>One method, VMS types only, no transport anywhere in the signature — the same anti-corruption
 * discipline ADR-0002 applies to ACS. Today the only adapter writes a file, because no mail jar is
 * obtainable from this network; an SMTP adapter is a class that implements this and nothing else
 * changes. That is the whole reason the port exists ahead of the transport.
 */
public interface NotificationSender {

    /**
     * @param message the notification payload; recipient is looked up from the visitor record,
     *                never supplied by a caller (AC-6), and bodyRef is a template or storage
     *                reference for the log — not the body itself, which the schema comment on
     *                {@code notification_logs.body_ref} rules out
     * @return where the message went, for the operator who has to confirm it did
     */
    Sent send(Message message);

    record Message(String recipient, String subject, String body, String bodyRef) {}

    /** @param reference an adapter-specific locator: a file path today, a message id under SMTP */
    record Sent(String reference) {}

    /** The transport refused. Callers decide whether that is fatal; for issuance it is not. */
    class SendFailed extends RuntimeException {
        public SendFailed(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
