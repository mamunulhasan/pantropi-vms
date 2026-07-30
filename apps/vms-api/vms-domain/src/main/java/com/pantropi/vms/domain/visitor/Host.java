package com.pantropi.vms.domain.visitor;

import java.util.UUID;

/**
 * A person in a tenant organisation who receives visitors (US-10.1.1, `vms.hosts`).
 *
 * <p>A host is a tenant's own record of its staff, and every field on it is employee personal data.
 * Two consequences run through this class:
 *
 * <ul>
 *   <li><strong>The tenant is not optional and not settable by a caller.</strong> {@code tenant_id}
 *       is {@code NOT NULL} in the schema and is derived from the acting user's own record
 *       (AC-1) — a host with the wrong tenant would be readable by the wrong organisation, which
 *       is the whole risk this entity carries (AC-4).</li>
 *   <li><strong>{@link #toString()} discloses neither email nor phone.</strong> The generated
 *       record-style rendering of a class like this is how contact details reach a log file and
 *       stay there for its retention period. The same reasoning as {@code Tenant} and
 *       {@code Visitor}.</li>
 * </ul>
 *
 * <p>There is no deletion, here or anywhere above this class. A host is referenced by every visit
 * they ever received, so removing the row would orphan the audit story of each one (AC-5);
 * departure is {@link #deactivate()}, which leaves those references intact (AC-3).
 *
 * <p>Pure domain: no framework, no persistence.
 */
public final class Host {

    public static final int MAX_NAME_LENGTH = 200;

    private final UUID id;
    private final UUID tenantId;
    private final String fullName;
    private final EmailAddress email;
    private final PhoneNumber phone;
    private final boolean active;

    private Host(UUID id, UUID tenantId, String fullName, EmailAddress email, PhoneNumber phone,
                 boolean active) {
        this.id = id;
        this.tenantId = tenantId;
        this.fullName = fullName;
        this.email = email;
        this.phone = phone;
        this.active = active;
    }

    /**
     * A new host for a tenant. Active from creation (AC-1).
     *
     * @throws Visitor.InvalidVisitorDetail if the name is blank or over-long, or the email or phone
     *                                      is present and malformed — the message names the field
     *                                      and never the value
     */
    public static Host named(UUID tenantId, String fullName, String email, String phone) {
        if (tenantId == null) {
            // Not a validation message a caller can act on, because a caller cannot supply this:
            // reaching here means the acting user's tenant was never resolved.
            throw new IllegalArgumentException("a host must belong to a tenant");
        }
        return new Host(UUID.randomUUID(), tenantId, name(fullName), EmailAddress.of(email),
                PhoneNumber.of(phone), true);
    }

    /** Rehydrate a stored row. Values are trusted — they were validated on the way in. */
    public static Host stored(UUID id, UUID tenantId, String fullName, String email, String phone,
                              boolean active) {
        return new Host(id, tenantId, fullName,
                email == null ? null : EmailAddress.stored(email),
                phone == null ? null : PhoneNumber.stored(phone), active);
    }

    /** The same host with corrected details. Identity, tenant and active state are unchanged. */
    public Host withDetails(String fullName, String email, String phone) {
        return new Host(id, tenantId, name(fullName), EmailAddress.of(email), PhoneNumber.of(phone),
                active);
    }

    /** They have left. Existing visit records keep pointing at them (AC-3). */
    public Host deactivate() {
        return new Host(id, tenantId, fullName, email, phone, false);
    }

    public Host reactivate() {
        return new Host(id, tenantId, fullName, email, phone, true);
    }

    private static String name(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            throw new Visitor.InvalidVisitorDetail("host name", "is required");
        }
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new Visitor.InvalidVisitorDetail("host name",
                    "must be at most " + MAX_NAME_LENGTH + " characters");
        }
        return trimmed;
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public String fullName() {
        return fullName;
    }

    /** The normalised email, or null when none was given. */
    public String emailValue() {
        return email == null ? null : email.value();
    }

    /** The normalised phone, or null when none was given. */
    public String phoneValue() {
        return phone == null ? null : phone.value();
    }

    public boolean active() {
        return active;
    }

    /**
     * Identifies the host without disclosing how to contact them.
     *
     * <p>The name is included because a log entry naming nobody is useless for diagnosis, and a
     * host's name is already visible to everyone who can see a request they are on. Email and
     * phone are not.
     */
    @Override
    public String toString() {
        return "Host[" + id + " " + fullName + "]";
    }
}
