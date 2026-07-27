package com.pantropi.vms.application.identity.port;

import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port: read access to authentication-relevant user records (US-02.1.1, AC-1).
 *
 * <p>Implemented by an infrastructure adapter over {@code vms.users}. The application layer
 * knows nothing about JDBC or the table shape — only this interface.
 */
public interface UserDirectory {

    Optional<AuthUser> findActiveByUsername(String username);

    /** Authentication view of a user — never carries anything the login flow does not need. */
    record AuthUser(UUID id, String username, String passwordHash, String roleCode, boolean active) {}
}
