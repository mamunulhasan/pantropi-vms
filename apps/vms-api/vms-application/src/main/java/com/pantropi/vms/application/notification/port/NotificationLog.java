package com.pantropi.vms.application.notification.port;

import java.util.UUID;

/** Writes {@code vms.notification_logs} — the record that a message was attempted (FR-NOT-05). */
public interface NotificationLog {

    /** @param status queued, sent, delivered or failed; {@code sent} also stamps {@code sent_at} */
    void record(UUID visitorId, String recipient, String channel, String notifyType, String subject,
                String bodyRef, String status);
}
