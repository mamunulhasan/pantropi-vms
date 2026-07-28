package com.pantropi.vms.domain.masterdata;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * What a visitor is issued to get through a turnstile — {@code vms.credential_type} (US-04.6.1).
 *
 * <p>Phase 2's credential context consumes the same vocabulary ({@code vms.credentials}). It lives
 * here rather than in a shared package because there is one consumer today; moving it when the
 * second arrives is a rename, whereas guessing the right shared home now is a guess.
 *
 * <h2>Two ways in, deliberately different</h2>
 * {@link #parse} handles user input and reports the permitted values, because a person who typed
 * the wrong thing needs to know what the right things are (AC-3). {@link #fromDatabase} handles a
 * stored value and <strong>throws</strong> on anything unrecognised, because that is not user error
 * — it means the database's enum has a value this code does not know about, and the only safe
 * response is to stop. Falling back to a default there would silently issue the wrong kind of
 * credential, and that surfaces at a turnstile rather than in a test.
 */
public enum CredentialType {

    QR("qr"),
    RFID("rfid");

    private final String databaseValue;

    CredentialType(String databaseValue) {
        this.databaseValue = databaseValue;
    }

    /** The value as {@code vms.credential_type} spells it. */
    public String databaseValue() {
        return databaseValue;
    }

    /** @throws MasterDataText.InvalidField naming the field and listing the permitted values */
    public static CredentialType parse(String input) {
        String value = input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
        for (CredentialType candidate : values()) {
            if (candidate.databaseValue.equals(value)) {
                return candidate;
            }
        }
        throw new MasterDataText.InvalidField("defaultCredential",
                "must be one of " + permitted());
    }

    /**
     * @throws UnknownDatabaseValue if the stored value is not one this code knows. Deliberately not
     *                              a validation error: nobody submitted it, so there is nothing for
     *                              a caller to correct.
     */
    public static CredentialType fromDatabase(String stored) {
        for (CredentialType candidate : values()) {
            if (candidate.databaseValue.equals(stored)) {
                return candidate;
            }
        }
        throw new UnknownDatabaseValue("credential_type", stored, permitted());
    }

    public static String permitted() {
        return Arrays.stream(values()).map(CredentialType::databaseValue)
                .collect(Collectors.joining(", "));
    }
}
