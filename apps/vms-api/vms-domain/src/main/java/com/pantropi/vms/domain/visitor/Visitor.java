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
 * placed into a domain event or a log line.
 */
public final class Visitor {

    private static final int MAX_NAME = 200;
    private static final int MAX_EMAIL = 320;   // RFC 5321 practical maximum
    private static final int MAX_PHONE = 40;
    private static final int MAX_COMPANY = 200;

    private final UUID id;
    private final String fullName;
    private final String email;
    private final String phone;
    private final String company;
    private VisitorStatus status;

    private Visitor(UUID id, String fullName, String email, String phone, String company,
                    VisitorStatus status) {
        this.id = id;
        this.fullName = fullName;
        this.email = email;
        this.phone = phone;
        this.company = company;
        this.status = status;
    }

    /** A newly named visitor, pending until their request is approved. */
    public static Visitor named(String fullName, String email, String phone, String company) {
        String name = required(fullName, "visitor name", MAX_NAME);
        return new Visitor(UUID.randomUUID(), name,
                bounded(email, "visitor email", MAX_EMAIL),
                bounded(phone, "visitor phone", MAX_PHONE),
                bounded(company, "visitor company", MAX_COMPANY),
                VisitorStatus.PENDING);
    }

    /** Rehydrate from storage without re-running creation rules. */
    public static Visitor rehydrate(UUID id, String fullName, String email, String phone,
                                    String company, VisitorStatus status) {
        return new Visitor(Objects.requireNonNull(id), fullName, email, phone, company,
                Objects.requireNonNull(status));
    }

    public UUID id() {
        return id;
    }

    public String fullName() {
        return fullName;
    }

    public String email() {
        return email;
    }

    public String phone() {
        return phone;
    }

    public String company() {
        return company;
    }

    public VisitorStatus status() {
        return status;
    }

    void markApproved() {
        this.status = VisitorStatus.APPROVED;
    }

    void markCancelled() {
        this.status = VisitorStatus.CANCELLED;
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
