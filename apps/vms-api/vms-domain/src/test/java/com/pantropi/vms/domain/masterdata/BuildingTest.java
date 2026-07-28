package com.pantropi.vms.domain.masterdata;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link Building} and the shared {@link MasterDataText} rules (US-04.1.1). */
class BuildingTest {

    @Test
    @DisplayName("a code is upper-cased, so WGT and wgt cannot become two buildings")
    void codeIsNormalised() {
        // The database's unique constraint is case-sensitive; without this, two rows every human
        // involved would read as one.
        assertThat(Building.draft(" wgt ", "Westgate Tower", null).code()).isEqualTo("WGT");
    }

    @Test
    @DisplayName("a name is trimmed but otherwise left exactly as typed — people read it")
    void nameIsTrimmedNotTransformed() {
        assertThat(Building.draft("WGT", "  Westgate Tower  ", null).name())
                .isEqualTo("Westgate Tower");
    }

    @Test
    @DisplayName("a blank optional address becomes null rather than an empty string")
    void blankAddressBecomesNull() {
        assertThat(Building.draft("WGT", "Westgate Tower", "   ").address()).isNull();
        assertThat(Building.draft("WGT", "Westgate Tower", null).address()).isNull();
    }

    @Test
    @DisplayName("a draft is active from the moment it exists")
    void draftIsActive() {
        assertThat(Building.draft("WGT", "Westgate Tower", null).active()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "WG T", "WG/T", "WG.T", "WG#T"})
    @DisplayName("a blank code, or one with characters that break a URL or a CSV, is refused")
    void badCodeRefused(String candidate) {
        assertThatThrownBy(() -> Building.draft(candidate, "Westgate Tower", null))
                .isInstanceOf(MasterDataText.InvalidField.class)
                .satisfies(e -> assertThat(((MasterDataText.InvalidField) e).field).isEqualTo("code"));
    }

    @Test
    @DisplayName("a null code is refused rather than throwing a null pointer")
    void nullCodeRefused() {
        assertThatThrownBy(() -> Building.draft(null, "Westgate Tower", null))
                .isInstanceOf(MasterDataText.InvalidField.class);
    }

    @Test
    @DisplayName("a blank name is refused, naming the field so the API can point at it")
    void blankNameRefused() {
        assertThatThrownBy(() -> Building.draft("WGT", "  ", null))
                .isInstanceOf(MasterDataText.InvalidField.class)
                .satisfies(e -> assertThat(((MasterDataText.InvalidField) e).field).isEqualTo("name"));
    }

    @Test
    @DisplayName("over-long values are refused rather than truncated by the database")
    void overLongValuesRefused() {
        assertThatThrownBy(() -> Building.draft("W".repeat(33), "Westgate", null))
                .isInstanceOf(MasterDataText.InvalidField.class);
        assertThatThrownBy(() -> Building.draft("WGT", "N".repeat(201), null))
                .isInstanceOf(MasterDataText.InvalidField.class);
        assertThatThrownBy(() -> Building.draft("WGT", "Westgate", "A".repeat(501)))
                .isInstanceOf(MasterDataText.InvalidField.class);
    }

    @Test
    @DisplayName("codes accept the separators a mnemonic actually needs")
    void reasonableCodesAccepted() {
        assertThat(Building.draft("WGT-2", "Two", null).code()).isEqualTo("WGT-2");
        assertThat(Building.draft("WGT_B", "B", null).code()).isEqualTo("WGT_B");
    }
}
