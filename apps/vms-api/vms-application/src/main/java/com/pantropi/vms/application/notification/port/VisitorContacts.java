package com.pantropi.vms.application.notification.port;

import java.util.Optional;
import java.util.UUID;

/**
 * Where a visitor's own email address comes from (US-09.6.1 AC-6).
 *
 * <p>A separate port rather than a field on the send command, and that is the point: if the
 * recipient could be passed in, the credential email would become an open relay pointed at whatever
 * address a caller chose. The only address reachable from here is the one on the visitor record.
 */
public interface VisitorContacts {

    Optional<Contact> findById(UUID visitorId);

    record Contact(UUID visitorId, String fullName, String email) {}
}
