package com.pantropi.vms.domain.masterdata;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link PassType} and its enums (US-04.6.1, T-04.6.1.1) — pure, no I/O. */
class PassTypeTest {

    // ---- the enums ----

    @Test
    @DisplayName("AC-3: input parsing is case-insensitive and trims")
    void parseAcceptsReasonableInput() {
        assertThat(CredentialType.parse(" QR ")).isEqualTo(CredentialType.QR);
        assertThat(CredentialType.parse("rfid")).isEqualTo(CredentialType.RFID);
        assertThat(RestrictionType.parse("Time_Bound")).isEqualTo(RestrictionType.TIME_BOUND);
        assertThat(RestrictionType.parse("one_time")).isEqualTo(RestrictionType.ONE_TIME);
    }

    @ParameterizedTest
    @ValueSource(strings = {"nfc", "", "   ", "QR_CODE", "barcode"})
    @DisplayName("AC-3: an unpermitted credential value is refused, listing what is permitted")
    void unpermittedCredentialRefused(String candidate) {
        assertThatThrownBy(() -> CredentialType.parse(candidate))
                .isInstanceOf(MasterDataText.InvalidField.class)
                // A caller who guessed wrong needs to know the right answers, not just "invalid".
                .hasMessageContaining("qr").hasMessageContaining("rfid");
    }

    @Test
    @DisplayName("AC-3: an unpermitted restriction value is refused the same way")
    void unpermittedRestrictionRefused() {
        assertThatThrownBy(() -> RestrictionType.parse("forever"))
                .isInstanceOf(MasterDataText.InvalidField.class)
                .hasMessageContaining("time_bound").hasMessageContaining("one_time");
    }

    @Test
    @DisplayName("a null input is refused rather than throwing a null pointer")
    void nullInputRefused() {
        assertThatThrownBy(() -> CredentialType.parse(null))
                .isInstanceOf(MasterDataText.InvalidField.class);
        assertThatThrownBy(() -> RestrictionType.parse(null))
                .isInstanceOf(MasterDataText.InvalidField.class);
    }

    @Test
    @DisplayName("a stored value the code does not know fails loudly rather than defaulting")
    void unknownStoredValueFailsLoudly() {
        // The dangerous alternative would be returning QR here: a pass configured for an RFID card
        // would silently become a QR code, and nobody would find out until a turnstile.
        assertThatThrownBy(() -> CredentialType.fromDatabase("nfc"))
                .isInstanceOf(UnknownDatabaseValue.class)
                .hasMessageContaining("nfc")
                .hasMessageContaining("diverged");
        assertThatThrownBy(() -> RestrictionType.fromDatabase("perpetual"))
                .isInstanceOf(UnknownDatabaseValue.class);
    }

    @Test
    @DisplayName("every enum constant round-trips through its database value")
    void enumsRoundTrip() {
        for (CredentialType c : CredentialType.values()) {
            assertThat(CredentialType.fromDatabase(c.databaseValue())).isEqualTo(c);
        }
        for (RestrictionType r : RestrictionType.values()) {
            assertThat(RestrictionType.fromDatabase(r.databaseValue())).isEqualTo(r);
        }
    }

    // ---- the record ----

    @Test
    @DisplayName("a well-formed pass type is accepted and normalised")
    void wellFormedPassType() {
        PassType pass = PassType.draft(" day_qr ", "  Single-day QR  ",
                CredentialType.QR, RestrictionType.TIME_BOUND, 12);

        assertThat(pass.code()).isEqualTo("DAY_QR");
        assertThat(pass.name()).isEqualTo("Single-day QR");
        assertThat(pass.defaultValidHours()).isEqualTo(12);
        assertThat(pass.active()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, -12})
    @DisplayName("AC-5: non-positive validity is refused here, not left to the database CHECK")
    void nonPositiveHoursRefused(int hours) {
        assertThatThrownBy(() -> PassType.draft("DAY_QR", "Day",
                CredentialType.QR, RestrictionType.TIME_BOUND, hours))
                .isInstanceOf(MasterDataText.InvalidField.class)
                .satisfies(e -> assertThat(((MasterDataText.InvalidField) e).field)
                        .isEqualTo("defaultValidHours"));
    }

    @Test
    @DisplayName("an absurdly long validity is refused — a stale credential stays usable")
    void overLongValidityRefused() {
        assertThatCode(() -> PassType.draft("LONG", "Long",
                CredentialType.QR, RestrictionType.TIME_BOUND, 24 * 31))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> PassType.draft("LONG", "Long",
                CredentialType.QR, RestrictionType.TIME_BOUND, 24 * 365))
                .isInstanceOf(MasterDataText.InvalidField.class)
                .satisfies(e -> assertThat(((MasterDataText.InvalidField) e).field)
                        .isEqualTo("defaultValidHours"));
    }

    @Test
    @DisplayName("a missing enum is refused naming the field, not a null pointer at the adapter")
    void missingEnumRefused() {
        assertThatThrownBy(() -> PassType.draft("DAY_QR", "Day", null,
                RestrictionType.TIME_BOUND, 12))
                .isInstanceOf(MasterDataText.InvalidField.class)
                .satisfies(e -> assertThat(((MasterDataText.InvalidField) e).field)
                        .isEqualTo("defaultCredential"));
        assertThatThrownBy(() -> PassType.draft("DAY_QR", "Day", CredentialType.QR, null, 12))
                .isInstanceOf(MasterDataText.InvalidField.class)
                .satisfies(e -> assertThat(((MasterDataText.InvalidField) e).field)
                        .isEqualTo("defaultRestriction"));
    }
}
