package com.pantropi.vms.infrastructure.notification;

import com.pantropi.vms.application.notification.port.NotificationLog;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * JDBC adapter for {@code vms.notification_logs} (FR-NOT-05).
 *
 * <p>{@code body_ref} rather than the body, as the column's own comment insists — the log records
 * that a message was attempted and to whom, not what it said.
 */
public final class JdbcNotificationLog implements NotificationLog {

    private final JdbcTemplate jdbc;

    public JdbcNotificationLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(UUID visitorId, String recipient, String channel, String notifyType,
                       String subject, String bodyRef, String status) {
        jdbc.update("""
                INSERT INTO vms.notification_logs
                    (visitor_id, recipient, channel, notify_type, subject, body_ref,
                     delivery_status, sent_at)
                VALUES (?, ?, ?::vms.notify_channel, ?::vms.notify_type, ?, ?,
                        ?::vms.delivery_status, CASE WHEN ? = 'sent' THEN now() ELSE NULL END)
                """, visitorId, recipient, channel, notifyType, subject, bodyRef, status, status);
    }
}
