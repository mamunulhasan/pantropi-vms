package com.pantropi.vms.domain.visitor;

import java.util.Objects;
import java.util.UUID;

/**
 * An individual expected visitor (US-07.1.1, T-07.1.1.1) — FR-VMS-01, FR-VMS-03 (SRS B1).
 *
 * <p>An entity inside the {@link VisitorRequest} aggregate: it has identity, but it is only ever
 * created, loaded and modified through its request, which owns the transaction boundary.
 *
 * <p>Carries personal data (name, email, phone), so it is length-bounded at construction and never
 * placed into a domain event or a log line. The email and phone are {@link EmailAddress} and
 * {@link PhoneNumber} rather than strings, which makes the second half of that sentence structural:
 * both redact themselves in {@code toString()}, so an accidental interpolation leaks nothing
 * (US-07.1.2, T-07.1.2.1).
 */
public final class Visitor {

    private static final int MAX_NAME = 200;
    private static final int MAX_COMPANY = 200;

    private final UUID id;
    private final String fullName;
    private final EmailAddress email;
    private final PhoneNumber phone;
    private final String company;

    /**
     * How this visitor is classified (US-07.1.2 AC-1), from {@code vms.visitor_types}.
     *
     * <p>Held as an id rather than the master-data record itself: the classification of a visit is
     * settled when it is booked, and pulling in the whole {@code VisitorType} would make the visitor
     * aggregate depend on another context's model — and change meaning when someone renames a type
     * two years later.
     *
     * <p>Nullable, matching the column. Whether the id names a type that exists and is active is not
     * a question this aggregate can answer; the use case resolves it before construction (AC-3).
     */
    private final UUID visitorTypeId;

    private VisitorStatus status;

    private Visitor(UUID id, String fullName, EmailAddress email, PhoneNumber phone, String company,
                    UUID visitorTypeId, VisitorStatus status) {
        this.id = id;
        this.fullName = fullName;
        this.email = email;
        this.phone = phone;
        this.company = company;
        this.visitorTypeId = visitorTypeId;
        this.status = status;
    }

    /**
     * A newly named visitor, pending until their request is approved.
     *
     * <p>Email and phone are normalised and format-checked by their value objects (US-07.1.2 AC-2,
     * AC-4), so an address that reaches this constructor is one this system can actually deliver to.
     */
    public static Visitor named(String fullName, String email, String phone, String company,
                                UUID visitorTypeId) {
        String name = required(fullName, "visitor name", MAX_NAME);
        return new Visitor(UUID.randomUUID(), name,
                EmailAddress.of(email),
                PhoneNumber.of(phone),
                bounded(company, "visitor company", MAX_COMPANY),
                visitorTypeId,
                VisitorStatus.PENDING);
    }

    /** Rehydrate from storage without re-running creation rules. */
    public static Visitor rehydrate(UUID id, String fullName, String email, String phone,
                                    String company, UUID visitorTypeId, VisitorStatus status) {
        return new Visitor(Objects.requireNonNull(id), fullName, EmailAddress.stored(email),
                PhoneNumber.stored(phone), company, visitorTypeId,
                Objects.requireNonNull(status));
    }

    public UUID id() {
        return id;
    }

    public String fullName() {
        return fullName;
    }

    /** The normalised address, or null. Redacts itself if logged — see {@link EmailAddress}. */
    public EmailAddress email() {
        return email;
    }

    /** The normalised number, or null. Redacts itself if logged — see {@link PhoneNumber}. */
    public PhoneNumber phone() {
        return phone;
    }

    /** For persistence, where a plain string is needed. */
    public String emailValue() {
        return email == null ? null : email.value();
    }

    /** For persistence, where a plain string is needed. */
    public String phoneValue() {
        return phone == null ? null : phone.value();
    }

    public String company() {
        return company;
    }

    public UUID visitorTypeId() {
        return visitorTypeId;
    }

    public VisitorStatus status() {
        return status;
    }

    /**
     * A corrected copy of this visitor: same identity, same status, new details (US-08.1.3 AC-1).
     *
     * <p>Package-private, like {@link #moveTo}: only the request aggregate may amend a visitor, and
     * it does so by swapping this copy in. The identity is preserved on purpose — a credential
     * references the visitor by id, and a "corrected" visitor with a new id would be a different
     * person as far as everything downstream is concerned.
     *
     * <p>Validation is {@link #named}'s, so an amendment obeys exactly the rules a fresh entry does
     * — a path with its own slightly different validation is how a rule ends up enforced on the way
     * in and not on the way back in.
     */
    Visitor withDetails(String fullName, String email, String phone, String company,
                        UUID visitorTypeId) {
        Visitor validated = named(fullName, email, phone, company, visitorTypeId);
        return new Visitor(this.id, validated.fullName, validated.email, validated.phone,
                validated.company, validated.visitorTypeId, this.status);
    }

    /**
     * Move this visitor to the status the request-level cascade decided (US-07.5.1 AC-3).
     *
     * <p>Package-private, and deliberately the only mutator: a visitor's status is a consequence of
     * a decision about the request, never something set independently from outside the aggregate.
     * Whether the move is allowed at all is {@link VisitorCascade}'s judgement, made before this is
     * called.
     */
    void moveTo(VisitorStatus target) {
        this.status = target;
    }

    private static String required(String value, String field, int max) {
        if (value == null || value.isBlank()) {
            throw new InvalidVisitorDetail(field, "is required");
        }
        String trimmed = value.trim();
        if (trimmed.length() > max) {
            throw new InvalidVisitorDetail(field, "must be at most " + max + " characters");
        }
        return trimmed;
    }

    private static String bounded(String value, String field, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > max) {
            throw new InvalidVisitorDetail(field, "must be at most " + max + " characters");
        }
        return trimmed;
    }

    /**
     * Names the offending field but never echoes the offending value, so an over-long or malformed
     * personal detail cannot be reflected into a response or a log.
     */
    public static final class InvalidVisitorDetail extends IllegalArgumentException {
        private final String field;

        public InvalidVisitorDetail(String field, String problem) {
            super("The " + field + " " + problem);
            this.field = field;
        }

        public String field() {
            return field;
        }
    }
}
