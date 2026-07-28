package com.pantropi.vms.application.masterdata;

import com.pantropi.vms.application.identity.port.AuditTrail;
import com.pantropi.vms.application.masterdata.port.SettingsStore;
import com.pantropi.vms.application.masterdata.usecase.SystemSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link SystemSettings} (US-04.8.1) — pure, fake ports. */
class SystemSettingsTest {

    private static final UUID ADMIN = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-02-01T09:00:00Z");

    private final FakeStore store = new FakeStore();
    private final RecordingAudit audit = new RecordingAudit();
    private final SystemSettings settings =
            new SystemSettings(store, audit, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("AC-1: listing returns every catalogued setting, driven by the catalogue")
    void listCoversTheCatalogue() {
        store.rows.put("default_pass_valid_hours", row("default_pass_valid_hours", "12"));

        List<SystemSettings.Setting> all = settings.list();

        assertThat(all).extracting(SystemSettings.Setting::key)
                .containsExactlyElementsOf(SettingsCatalogue.all().stream()
                        .map(SettingsCatalogue.Entry::key).toList());
        assertThat(all).allSatisfy(s -> assertThat(s.description()).isNotBlank());
    }

    @Test
    @DisplayName("a catalogued key with no stored row reads as its default, and says so")
    void missingRowFallsBackToDefault() {
        SystemSettings.Setting acs = settings.get("acs.retry.max_attempts");

        assertThat(acs.value()).isEqualTo("5");
        assertThat(acs.usingDefault()).isTrue();   // "never set" is distinguishable from "set to 5"
        assertThat(acs.updatedBy()).isNull();
    }

    @Test
    @DisplayName("AC-2: a change writes the value, stamps the actor and audits both states")
    void changeIsStoredAndAudited() {
        store.rows.put("acs.retry.max_attempts", row("acs.retry.max_attempts", "5"));

        SystemSettings.Setting updated = settings.update(ADMIN, "acs.retry.max_attempts", "8");

        assertThat(updated.value()).isEqualTo("8");
        assertThat(updated.usingDefault()).isFalse();
        assertThat(store.rows.get("acs.retry.max_attempts").updatedBy()).isEqualTo(ADMIN);
        assertThat(store.rows.get("acs.retry.max_attempts").updatedAt()).isEqualTo(NOW);

        assertThat(audit.entries).hasSize(1);
        Object[] entry = audit.entries.get(0);
        assertThat(entry[0]).isEqualTo(ADMIN);
        assertThat(entry[1]).isEqualTo("settings.updated");
        assertThat(entry[3]).isEqualTo("acs.retry.max_attempts");
        assertThat(String.valueOf(entry[4])).contains("\"value\":\"5\"");   // before
        assertThat(String.valueOf(entry[5])).contains("\"value\":\"8\"");   // after
    }

    @Test
    @DisplayName("AC-3: enabling WhatsApp is refused citing TODO-05, and nothing is written")
    void whatsappCannotBeEnabled() {
        assertThatThrownBy(() ->
                settings.update(ADMIN, "notification.whatsapp.enabled", "true"))
                .isInstanceOf(SystemSettings.ChangeRefused.class)
                .hasMessageContaining("TODO-05");

        assertThat(store.writes).isZero();
    }

    @Test
    @DisplayName("AC-3: the refused attempt is itself audited — an auditor should see it was tried")
    void refusalIsAudited() {
        assertThatThrownBy(() ->
                settings.update(ADMIN, "notification.whatsapp.enabled", "true"))
                .isInstanceOf(SystemSettings.ChangeRefused.class);

        assertThat(audit.entries).hasSize(1);
        assertThat(audit.entries.get(0)[1]).isEqualTo("settings.change_refused");
        assertThat(audit.entries.get(0)[0]).isEqualTo(ADMIN);
    }

    @Test
    @DisplayName("AC-3: disabling WhatsApp is always allowed — the guard is not a trap")
    void whatsappCanStillBeDisabled() {
        store.rows.put("notification.whatsapp.enabled",
                row("notification.whatsapp.enabled", "true"));

        SystemSettings.Setting result =
                settings.update(ADMIN, "notification.whatsapp.enabled", "false");

        assertThat(result.value()).isEqualTo("false");
        assertThat(store.writes).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-5: writing an uncatalogued key is refused and touches nothing")
    void unknownKeyRefused() {
        assertThatThrownBy(() -> settings.update(ADMIN, "notification.sms.enabled", "true"))
                .isInstanceOf(SettingsCatalogue.UnknownSetting.class);

        assertThat(store.writes).isZero();
        assertThat(audit.entries).isEmpty();
    }

    @Test
    @DisplayName("a value of the wrong type is refused before any write or audit")
    void wrongTypeRefused() {
        assertThatThrownBy(() -> settings.update(ADMIN, "acs.retry.max_attempts", "lots"))
                .isInstanceOf(SettingsCatalogue.InvalidSettingValue.class);
        assertThatThrownBy(() -> settings.update(ADMIN, "notification.email.enabled", "maybe"))
                .isInstanceOf(SettingsCatalogue.InvalidSettingValue.class);

        assertThat(store.writes).isZero();
        assertThat(audit.entries).isEmpty();
    }

    @Test
    @DisplayName("AC-4: a credential-shaped value is refused, naming the secrets mechanism")
    void credentialShapedValueRefused() {
        assertThatThrownBy(() -> settings.update(ADMIN, "acs.retry.max_attempts",
                "ghp_16CharsAndThenSomeMore00"))
                .isInstanceOf(SystemSettings.ChangeRefused.class)
                .hasMessageContaining("F-05.2");

        assertThat(store.writes).isZero();
    }

    @Test
    @DisplayName("AC-4: the refusal is audited, but the value itself never reaches the audit trail")
    void credentialRefusalDoesNotRecordTheValue() {
        // An opaque blob rather than a recognisable vendor prefix: this test only needs *a* value
        // the guard refuses, so it should not also be a literal the repository secret scanner has
        // to be told to ignore. The vendor shapes live in CredentialShapeTest, where they earn it.
        String pastedKey = "aVeryLongOpaqueToken1234567890abcdef";

        assertThatThrownBy(() -> settings.update(ADMIN, "acs.retry.max_attempts", pastedKey))
                .isInstanceOf(SystemSettings.ChangeRefused.class);

        assertThat(audit.entries).hasSize(1);
        assertThat(audit.entries.get(0)[1]).isEqualTo("settings.credential_refused");
        for (Object field : audit.entries.get(0)) {
            assertThat(String.valueOf(field)).doesNotContain(pastedKey);
        }
    }

    @Test
    @DisplayName("AC-4: the credential check runs before the type check, so it is not shadowed")
    void credentialCheckPrecedesTypeCheck() {
        // Every catalogued key is an integer or a boolean today. If the type check ran first, a
        // pasted key would come back as "expected a whole number" and this guard would be dead.
        assertThatThrownBy(() -> settings.update(ADMIN, "notification.email.enabled",
                "Bearer abcdefghijklmnop"))
                .isInstanceOf(SystemSettings.ChangeRefused.class)
                .hasMessageContaining("credential");
    }

    @Test
    @DisplayName("the declared type reaches the store, so jsonb is written in the right shape")
    void typeIsPassedToTheStore() {
        settings.update(ADMIN, "acs.retry.max_attempts", "8");
        assertThat(store.lastType).isEqualTo(SettingsCatalogue.Type.INTEGER);

        settings.update(ADMIN, "notification.email.enabled", "false");
        assertThat(store.lastType).isEqualTo(SettingsCatalogue.Type.BOOLEAN);
    }

    private static SettingsStore.StoredSetting row(String key, String value) {
        return new SettingsStore.StoredSetting(key, value, "seeded description", null, NOW);
    }

    // ---- fakes ----

    private static final class FakeStore implements SettingsStore {
        final Map<String, StoredSetting> rows = new LinkedHashMap<>();
        int writes;
        SettingsCatalogue.Type lastType;

        public List<StoredSetting> findAll() {
            return new ArrayList<>(rows.values());
        }
        public Optional<StoredSetting> find(String key) {
            return Optional.ofNullable(rows.get(key));
        }
        public void write(String key, SettingsCatalogue.Type type, String value, UUID by,
                          Instant at) {
            writes++;
            lastType = type;
            rows.put(key, new StoredSetting(key, value, "seeded description", by, at));
        }
    }

    private static final class RecordingAudit implements AuditTrail {
        final List<Object[]> entries = new ArrayList<>();

        public void record(UUID a, String action, String t, String i, String d) {
            entries.add(new Object[]{a, action, t, i, null, null});
        }
        public void recordChange(UUID a, String action, String t, String i, String before,
                                 String after) {
            entries.add(new Object[]{a, action, t, i, before, after});
        }
        public void recordSecurityDenial(UUID a, String ac, String p, String r, String m, String o,
                                         String ip) {}
    }
}
