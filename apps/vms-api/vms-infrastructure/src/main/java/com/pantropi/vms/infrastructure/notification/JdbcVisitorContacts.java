package com.pantropi.vms.infrastructure.notification;

import com.pantropi.vms.application.notification.port.VisitorContacts;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;
import java.util.UUID;

/** Reads the visitor's own address — the only recipient the credential email can reach (AC-6). */
public final class JdbcVisitorContacts implements VisitorContacts {

    private final JdbcTemplate jdbc;

    public JdbcVisitorContacts(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Contact> findById(UUID visitorId) {
        return jdbc.query("""
                SELECT id, full_name, email::text AS email FROM vms.visitors WHERE id = ?
                """, (rs, i) -> new Contact(rs.getObject("id", UUID.class),
                        rs.getString("full_name"), rs.getString("email")), visitorId)
                .stream().findFirst();
    }
}
