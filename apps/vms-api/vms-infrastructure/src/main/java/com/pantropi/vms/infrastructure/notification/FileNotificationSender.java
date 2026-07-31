package com.pantropi.vms.infrastructure.notification;

import com.pantropi.vms.application.notification.port.NotificationSender;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

/**
 * Writes each message to a directory instead of sending it (development only).
 *
 * <p>This exists because no mail library is obtainable from this network — the same wall that
 * forced PBKDF2 over Argon2. It is not a stub that pretends: it produces a real, readable artefact
 * an operator can open, so "did the notification happen" has an answer during a demo.
 *
 * <p>It must never be the adapter in production. A message written to a local disk has not been
 * delivered to anyone, and the {@code vms.notification_logs} row will say {@code sent}.
 */
public final class FileNotificationSender implements NotificationSender {

    // System.Logger rather than SLF4J: this module carries no logging dependency, and adding one
    // for a development-only adapter would be the tail wagging the dog.
    private static final System.Logger log =
            System.getLogger(FileNotificationSender.class.getName());

    private final Path outboxDir;

    public FileNotificationSender(Path outboxDir) {
        this.outboxDir = outboxDir;
    }

    @Override
    public Sent send(Message message) {
        try {
            Files.createDirectories(outboxDir);
            Path file = outboxDir.resolve(Instant.now().toEpochMilli() + "-"
                    + UUID.randomUUID().toString().substring(0, 8) + ".txt");
            Files.writeString(file, """
                    To: %s
                    Subject: %s
                    X-VMS-Body-Ref: %s

                    %s""".formatted(message.recipient(), message.subject(), message.bodyRef(),
                    message.body()), StandardCharsets.UTF_8);

            // The path, never the body: the log is a place a message can be read by anyone with
            // log access, and the file already is the record.
            log.log(System.Logger.Level.INFO, "Notification written to " + file.toAbsolutePath());
            return new Sent(file.toAbsolutePath().toString());
        } catch (IOException e) {
            throw new SendFailed("Could not write the notification to " + outboxDir, e);
        }
    }
}
