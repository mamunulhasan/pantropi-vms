package com.pantropi.vms.application.masterdata.usecase;

import com.pantropi.vms.application.identity.port.AuditTrail;
import com.pantropi.vms.application.masterdata.CredentialShape;
import com.pantropi.vms.application.masterdata.SettingsCatalogue;
import com.pantropi.vms.application.masterdata.port.SettingsStore;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Read and change application-wide settings (US-04.8.1) — FR-SET-01.
 *
 * <p>Listing is driven by the {@link SettingsCatalogue}, not by whatever rows happen to exist: a
 * catalogued key with no row reads as its documented default rather than vanishing, and a stored row
 * for an uncatalogued key is not surfaced as though the application understood it.
 *
 * <p>Every accepted change writes one audit row carrying both states (AC-2), and every <em>refused</em>
 * change writes one too. A refusal is the more interesting event of the two — it records that an
 * administrator tried to turn on a channel that is not approved, which is exactly what an auditor
 * would want to see.
 *
 * <p>Pure orchestration over ports — no framework.
 */
public final class SystemSettings {

    /**
     * Enabling WhatsApp would activate a channel the client has not had approved. TODO-05 tracks
     * that approval; until it closes, the flag cannot be turned on from the API (AC-3, T-04.8.1.3).
     * Turning it off is always allowed — a guard that blocked disabling would be a trap.
     */
    private static final String WHATSAPP_KEY = "notification.whatsapp.enabled";

    private final SettingsStore store;
    private final AuditTrail audit;
    private final Clock clock;

    public SystemSettings(SettingsStore store, AuditTrail audit, Clock clock) {
        this.store = store;
        this.audit = audit;
        this.clock = clock;
    }

    /** Every catalogued setting with its current value, or its default where no row exists. */
    public List<Setting> list() {
        Map<String, SettingsStore.StoredSetting> stored = store.findAll().stream()
                .collect(Collectors.toMap(SettingsStore.StoredSetting::key, Function.identity(),
                        (a, b) -> a));

        List<Setting> out = new ArrayList<>(SettingsCatalogue.all().size());
        for (SettingsCatalogue.Entry entry : SettingsCatalogue.all()) {
            out.add(toView(entry, Optional.ofNullable(stored.get(entry.key()))));
        }
        return out;
    }

    /** @throws SettingsCatalogue.UnknownSetting if the key is not catalogued */
    public Setting get(String key) {
        SettingsCatalogue.Entry entry = SettingsCatalogue.require(key);
        return toView(entry, store.find(key));
    }

    /**
     * Change a setting.
     *
     * @throws SettingsCatalogue.UnknownSetting     if the key is not catalogued (AC-5)
     * @throws SettingsCatalogue.InvalidSettingValue if the value does not match the declared type
     * @throws ChangeRefused                        if a policy guard forbids this particular change
     */
    public Setting update(UUID actorId, String key, String proposedValue) {
        SettingsCatalogue.Entry entry = SettingsCatalogue.require(key);

        // US-04.8.2 AC-4, and deliberately BEFORE the type check. Every catalogued key today is an
        // integer or a boolean, so a pasted API key would otherwise come back as "expected a whole
        // number" — a format complaint, when the useful response is "that looks like a credential,
        // and here is where credentials go". The more serious diagnosis should win, and putting it
        // first also means the guard is reachable rather than shadowed by the type check.
        if (CredentialShape.looksLikeCredential(proposedValue)) {
            // The attempt is audited; the value never is. Writing it to audit_logs would commit
            // the very leak this guard exists to prevent.
            audit.record(actorId, "settings.credential_refused", "system_setting", key,
                    "value refused as credential-shaped; the value itself is not recorded");
            throw new ChangeRefused(key, CredentialShape.refusalMessage(key));
        }

        String normalised = SettingsCatalogue.normalise(entry, proposedValue);

        Optional<SettingsStore.StoredSetting> before = store.find(key);
        String currentValue = before.map(SettingsStore.StoredSetting::value)
                .orElse(entry.defaultValue());

        if (WHATSAPP_KEY.equals(key) && "true".equals(normalised)) {
            String reason = "WhatsApp cannot be enabled while TODO-05 is open: the WhatsApp "
                    + "Business API has not been approved for this client, so enabling it would "
                    + "activate an unapproved notification channel.";
            audit.recordChange(actorId, "settings.change_refused", "system_setting", key,
                    json(entry, currentValue), json(entry, normalised));
            throw new ChangeRefused(key, reason);
        }

        store.write(key, entry.type(), normalised, actorId, clock.instant());

        audit.recordChange(actorId, "settings.updated", "system_setting", key,
                json(entry, currentValue), json(entry, normalised));

        return toView(entry, store.find(key));
    }

    private static Setting toView(SettingsCatalogue.Entry entry,
                                  Optional<SettingsStore.StoredSetting> stored) {
        String value = stored.map(SettingsStore.StoredSetting::value).orElse(entry.defaultValue());
        return new Setting(
                entry.key(),
                // US-04.8.2 AC-2: a secret-valued key discloses that it is set, never what to.
                SettingsCatalogue.disclose(entry, value),
                entry.type().name(),
                // The catalogue's description is authoritative; the column is a convenience copy
                // that a hand-edited database could have drifted.
                entry.description(),
                stored.map(SettingsStore.StoredSetting::updatedBy).orElse(null),
                stored.map(SettingsStore.StoredSetting::updatedAt).orElse(null),
                stored.isEmpty(),
                entry.secret());
    }

    /**
     * Minimal JSON for the audit's before/after columns.
     *
     * <p>The audit trail is a log, and AC-2 says a secret-valued setting is redacted when read
     * <em>or logged</em>. Recording the change of a secret key must therefore say that it changed
     * without saying to what — otherwise the audit becomes the leak.
     */
    private static String json(SettingsCatalogue.Entry entry, String value) {
        String rendered = SettingsCatalogue.disclose(entry, value);
        return "{\"key\":\"" + entry.key() + "\",\"value\":\""
                + rendered.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
    }

    /**
     * @param usingDefault true when no row exists and the value shown is the catalogue's default —
     *                     an operator should be able to tell "never set" from "set to this"
     * @param secret       true when {@code value} is the redaction marker rather than the real one,
     *                     so a client can say "set, not shown" instead of showing asterisks as data
     */
    public record Setting(String key, String value, String type, String description,
                          UUID updatedBy, java.time.Instant updatedAt, boolean usingDefault,
                          boolean secret) {}

    /** A change that is well formed but forbidden by policy. Carries the reason for the operator. */
    public static final class ChangeRefused extends RuntimeException {
        public final String key;

        public ChangeRefused(String key, String reason) {
            super(reason);
            this.key = key;
        }
    }
}
