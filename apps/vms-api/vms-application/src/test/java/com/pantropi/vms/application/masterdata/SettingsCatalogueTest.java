package com.pantropi.vms.application.masterdata;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link SettingsCatalogue} (US-04.8.1, T-04.8.1.1) — pure, no I/O. */
class SettingsCatalogueTest {

    @Test
    @DisplayName("AC-5: an uncatalogued key is refused rather than silently created")
    void unknownKeyRefused() {
        assertThatThrownBy(() -> SettingsCatalogue.require("notification.sms.enabled"))
                .isInstanceOf(SettingsCatalogue.UnknownSetting.class)
                .hasMessageContaining("notification.sms.enabled");

        // A near-miss on a real key is the case that matters — this is what a typo looks like.
        assertThatThrownBy(() -> SettingsCatalogue.require("notification.email.enable"))
                .isInstanceOf(SettingsCatalogue.UnknownSetting.class);
        assertThatThrownBy(() -> SettingsCatalogue.require(null))
                .isInstanceOf(SettingsCatalogue.UnknownSetting.class);
    }

    @Test
    @DisplayName("every catalogued key has a default that parses as its own declared type")
    void defaultsMatchTheirDeclaredType() {
        // Guards against a catalogue entry that would fail the first time anything read it.
        for (SettingsCatalogue.Entry entry : SettingsCatalogue.all()) {
            assertThat(SettingsCatalogue.normalise(entry, entry.defaultValue()))
                    .as("default for %s", entry.key())
                    .isEqualTo(entry.defaultValue());
        }
    }

    @Test
    @DisplayName("keys are unique and every entry is fully described")
    void catalogueIsWellFormed() {
        assertThat(SettingsCatalogue.all()).extracting(SettingsCatalogue.Entry::key)
                .doesNotHaveDuplicates();
        assertThat(SettingsCatalogue.all()).allSatisfy(e -> {
            assertThat(e.key()).isNotBlank();
            assertThat(e.description()).isNotBlank();
            assertThat(e.defaultValue()).isNotNull();
            assertThat(e.type()).isNotNull();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"yes", "1", "", "  ", "truthy", "TRUE!"})
    @DisplayName("a boolean setting refuses anything that is not exactly true or false")
    void booleanRejectsNonBoolean(String candidate) {
        SettingsCatalogue.Entry email = SettingsCatalogue.require("notification.email.enabled");
        assertThatThrownBy(() -> SettingsCatalogue.normalise(email, candidate))
                .isInstanceOf(SettingsCatalogue.InvalidSettingValue.class)
                .hasMessageContaining("true or false");
    }

    @Test
    @DisplayName("a boolean setting accepts either case and normalises it")
    void booleanNormalises() {
        SettingsCatalogue.Entry email = SettingsCatalogue.require("notification.email.enabled");
        assertThat(SettingsCatalogue.normalise(email, "TRUE")).isEqualTo("true");
        assertThat(SettingsCatalogue.normalise(email, " False ")).isEqualTo("false");
    }

    @ParameterizedTest
    @ValueSource(strings = {"twelve", "12.5", "12h", "", "1e3"})
    @DisplayName("an integer setting refuses anything that is not a whole number")
    void integerRejectsNonInteger(String candidate) {
        SettingsCatalogue.Entry hours = SettingsCatalogue.require("default_pass_valid_hours");
        assertThatThrownBy(() -> SettingsCatalogue.normalise(hours, candidate))
                .isInstanceOf(SettingsCatalogue.InvalidSettingValue.class)
                .hasMessageContaining("whole number");
    }

    @Test
    @DisplayName("a null value is refused rather than stored as JSON null")
    void nullValueRefused() {
        SettingsCatalogue.Entry hours = SettingsCatalogue.require("default_pass_valid_hours");
        assertThatThrownBy(() -> SettingsCatalogue.normalise(hours, null))
                .isInstanceOf(SettingsCatalogue.InvalidSettingValue.class);
    }
}
