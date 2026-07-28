package com.pantropi.vms.domain.masterdata;

import java.util.UUID;

/**
 * A kind of visitor pass and the defaults it carries (US-04.6.1) — FR-CFG-07 (TDD-derived), schema
 * {@code vms.pass_types}. Seeded with {@code DAY_QR}, {@code ONE_TIME} and {@code CARD_DAY}.
 *
 * <p>These three values are not descriptive. In Phase 2 they become the credential type, the
 * restriction and the validity window actually requested from the access control system, so a wrong
 * one here is a wrong one at a turnstile. That is why the enums refuse unknown values rather than
 * defaulting, and why every change is audited with both states (AC-4) — a credential issued last
 * month has to be explicable by what the configuration said last month.
 *
 * <p>{@code defaultValidHours} is validated here as strictly positive rather than left to the
 * schema's {@code CHECK (default_valid_hours > 0)}. The database would reject zero either way; the
 * difference is whether the caller gets a message naming the field or a constraint-violation
 * stack trace (AC-5).
 *
 * <p>Pure Java: no framework.
 */
public record PassType(UUID id, String code, String name, CredentialType defaultCredential,
                       RestrictionType defaultRestriction, int defaultValidHours, boolean active) {

    /**
     * A pass valid for longer than a month is almost certainly a typo, and an over-long validity
     * window is the kind of mistake that leaves a working credential in circulation. The schema
     * bounds only the lower end; this bounds the end that matters for access.
     */
    private static final int MAX_VALID_HOURS = 24 * 31;

    public PassType {
        code = MasterDataText.code(code);
        name = MasterDataText.name(name);
        if (defaultCredential == null) {
            throw new MasterDataText.InvalidField("defaultCredential",
                    "must be one of " + CredentialType.permitted());
        }
        if (defaultRestriction == null) {
            throw new MasterDataText.InvalidField("defaultRestriction",
                    "must be one of " + RestrictionType.permitted());
        }
        if (defaultValidHours <= 0) {
            throw new MasterDataText.InvalidField("defaultValidHours", "must be greater than zero");
        }
        if (defaultValidHours > MAX_VALID_HOURS) {
            throw new MasterDataText.InvalidField("defaultValidHours",
                    "must be at most " + MAX_VALID_HOURS + " hours");
        }
    }

    /** A pass type not yet persisted: no id, active from the moment it exists. */
    public static PassType draft(String code, String name, CredentialType credential,
                                 RestrictionType restriction, int validHours) {
        return new PassType(null, code, name, credential, restriction, validHours, true);
    }
}
