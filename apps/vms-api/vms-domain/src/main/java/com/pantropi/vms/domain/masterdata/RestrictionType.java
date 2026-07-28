package com.pantropi.vms.domain.masterdata;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * How long a credential stays usable — {@code vms.restriction_type} (US-04.6.1).
 *
 * <p>{@code TIME_BOUND} works for its validity window; {@code ONE_TIME} is spent on first use. The
 * same two-way split as {@link CredentialType}: {@link #parse} for input, reporting the permitted
 * values, and {@link #fromDatabase} which throws rather than defaulting, because a restriction
 * quietly read as the wrong one is the difference between a pass that works once and one that works
 * all day.
 */
public enum RestrictionType {

    TIME_BOUND("time_bound"),
    ONE_TIME("one_time");

    private final String databaseValue;

    RestrictionType(String databaseValue) {
        this.databaseValue = databaseValue;
    }

    /** The value as {@code vms.restriction_type} spells it. */
    public String databaseValue() {
        return databaseValue;
    }

    /** @throws MasterDataText.InvalidField naming the field and listing the permitted values */
    public static RestrictionType parse(String input) {
        String value = input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
        for (RestrictionType candidate : values()) {
            if (candidate.databaseValue.equals(value)) {
                return candidate;
            }
        }
        throw new MasterDataText.InvalidField("defaultRestriction",
                "must be one of " + permitted());
    }

    /** @throws UnknownDatabaseValue if the stored value is not one this code knows */
    public static RestrictionType fromDatabase(String stored) {
        for (RestrictionType candidate : values()) {
            if (candidate.databaseValue.equals(stored)) {
                return candidate;
            }
        }
        throw new UnknownDatabaseValue("restriction_type", stored, permitted());
    }

    public static String permitted() {
        return Arrays.stream(values()).map(RestrictionType::databaseValue)
                .collect(Collectors.joining(", "));
    }
}
