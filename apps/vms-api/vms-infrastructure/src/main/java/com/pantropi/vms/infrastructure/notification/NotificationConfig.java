package com.pantropi.vms.infrastructure.notification;

import com.pantropi.vms.application.notification.port.NotificationLog;
import com.pantropi.vms.application.notification.port.NotificationSender;
import com.pantropi.vms.application.notification.port.VisitorContacts;
import com.pantropi.vms.application.notification.usecase.SendCredentialEmail;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.file.Path;

/**
 * Wiring for outbound notifications (US-09.6.1, US-16.2.1).
 *
 * <p>Gated on the identity switch like the rest of the visitor-side wiring, so a profile that
 * serves no visitor API builds no mailer either.
 */
@Configuration
@ConditionalOnProperty(prefix = "vms.identity", name = "enabled", havingValue = "true")
public class NotificationConfig {

    /**
     * The file adapter is the default because it is the only one that exists — see the class
     * comment on {@link FileNotificationSender}. Declared {@code @ConditionalOnMissingBean} so an
     * SMTP adapter, once obtainable, replaces it by being declared rather than by editing this.
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(NotificationSender.class)
    NotificationSender notificationSender(
            @Value("${vms.notification.outbox-dir:./local-notifications}") String outboxDir) {
        return new FileNotificationSender(Path.of(outboxDir));
    }

    @Bean
    NotificationLog notificationLog(DataSource dataSource) {
        return new JdbcNotificationLog(new JdbcTemplate(dataSource));
    }

    @Bean
    VisitorContacts visitorContacts(DataSource dataSource) {
        return new JdbcVisitorContacts(new JdbcTemplate(dataSource));
    }

    @Bean
    SendCredentialEmail sendCredentialEmail(VisitorContacts contacts, NotificationSender sender,
                                            NotificationLog log,
                                            @Value("${notification.email.enabled:true}") boolean enabled) {
        return new SendCredentialEmail(contacts, sender, log, enabled);
    }
}
