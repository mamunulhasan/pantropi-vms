package com.pantropi.vms.infrastructure.credential;

import com.pantropi.vms.application.visitor.port.IssuedCredentials;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * The credential context answering whether a visitor holds a live credential (US-08.1.3 AC-3).
 *
 * <p>First code in {@code infrastructure.credential}: the context that owns {@code vms.credentials}
 * is the one that reads it, so when EPIC-09 builds issuance the two live together and the visitor
 * context's view of "live" cannot drift from the owner's.
 */
public final class JdbcIssuedCredentials implements IssuedCredentials {

    private final JdbcTemplate jdbc;

    public JdbcIssuedCredentials(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean anyLiveFor(UUID visitorId) {
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM vms.credentials
                 WHERE visitor_id = ? AND state IN ('requested', 'active')
                """, Long.class, visitorId);
        return count != null && count > 0;
    }
}
