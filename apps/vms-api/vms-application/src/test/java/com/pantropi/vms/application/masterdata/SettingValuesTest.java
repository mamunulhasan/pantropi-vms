package com.pantropi.vms.application.masterdata;

import com.pantropi.vms.application.masterdata.port.SettingsStore;
import com.pantropi.vms.application.masterdata.usecase.SettingValues;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link SettingValues} (US-04.8.2, T-04.8.2.1) — pure, fake store. */
class SettingValuesTest {

    private final FakeStore store = new FakeStore();
    private final SettingValues values = new SettingValues(store);

    @Test
    @DisplayName("AC-1: a stored value comes back correctly typed, not as a string to parse")
    void storedValueIsTyped() {
        store.put("acs.retry.max_attempts", "8");
        store.put("notification.email.enabled", "false");

        assertThat(values.getInt("acs.retry.max_attempts")).isEqualTo(8);
        assertThat(values.getBoolean("notification.email.enabled")).isFalse();
    }

    @Test
    @DisplayName("AC-1: an absent row yields the documented default, never null or zero")
    void absentRowYieldsDefault() {
        assertThat(values.getInt("default_pass_valid_hours")).isEqualTo(12);
        assertThat(values.getBoolean("notification.whatsapp.enabled")).isFalse();
        assertThat(values.getInt("acs.retry.max_attempts")).isEqualTo(5);
    }

    @Test
    @DisplayName("AC-1: a stored value of the wrong shape fails loudly instead of falling back")
    void badStoredValueFailsLoudly() {
        // A hand-edited row or a bad restore. Falling back to the default here would mean an
        // operator who set a value watches the system ignore it with nothing saying so.
        store.put("acs.retry.max_attempts", "not-a-number");

        assertThatThrownBy(() -> values.getInt("acs.retry.max_attempts"))
                .isInstanceOf(SettingsCatalogue.InvalidSettingValue.class);
    }

    @Test
    @DisplayName("AC-1: asking for the wrong type is refused rather than coerced")
    void wrongAccessorRefused() {
        assertThatThrownBy(() -> values.getBoolean("acs.retry.max_attempts"))
                .isInstanceOf(SettingsCatalogue.InvalidSettingValue.class)
                .hasMessageContaining("declared INTEGER");

        assertThatThrownBy(() -> values.getInt("notification.email.enabled"))
                .isInstanceOf(SettingsCatalogue.InvalidSettingValue.class)
                .hasMessageContaining("declared BOOLEAN");
    }

    @Test
    @DisplayName("an uncatalogued key is refused, as everywhere else")
    void unknownKeyRefused() {
        assertThatThrownBy(() -> values.getInt("no.such.setting"))
                .isInstanceOf(SettingsCatalogue.UnknownSetting.class);
    }

    @Test
    @DisplayName("a value too large for an int is refused rather than silently truncated")
    void oversizedIntRefused() {
        store.put("acs.retry.max_attempts", Long.toString(Integer.MAX_VALUE + 1L));

        assertThatThrownBy(() -> values.getInt("acs.retry.max_attempts"))
                .isInstanceOf(SettingsCatalogue.InvalidSettingValue.class)
                .hasMessageContaining("int");
        // ...but the long accessor reads it fine, so the limit is the accessor's, not the setting's.
        assertThat(values.getLong("acs.retry.max_attempts")).isEqualTo(Integer.MAX_VALUE + 1L);
    }

    private static final class FakeStore implements SettingsStore {
        final Map<String, StoredSetting> rows = new LinkedHashMap<>();

        void put(String key, String value) {
            rows.put(key, new StoredSetting(key, value, null, null, Instant.EPOCH));
        }
        public List<StoredSetting> findAll() { return new ArrayList<>(rows.values()); }
        public Optional<StoredSetting> find(String key) {
            return Optional.ofNullable(rows.get(key));
        }
        public void write(String k, SettingsCatalogue.Type t, String v, UUID by, Instant at) {
            put(k, v);
        }
    }
}
