package com.pantropi.vms.application.masterdata.port;

import com.pantropi.vms.application.masterdata.SettingsCatalogue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port for {@code vms.system_settings} (US-04.8.1, T-04.8.1.1) — FR-SET-01.
 *
 * <p>Values cross this boundary as plain text, never as a jsonb node: the application layer decides
 * what a setting <em>means</em>, and the adapter is the only thing that knows the column is jsonb.
 * The declared {@link SettingsCatalogue.Type} is passed on write so the adapter can store the right
 * JSON type — a number as a number and a boolean as a boolean, not everything as a quoted string.
 */
public interface SettingsStore {

    List<StoredSetting> findAll();

    Optional<StoredSetting> find(String key);

    /**
     * Write a value, stamping who changed it and when (AC-2). The row always exists in practice —
     * the baseline seed creates it — but this upserts so a setting is still writable if a
     * deployment has been restored without the seed.
     */
    void write(String key, SettingsCatalogue.Type type, String value, UUID updatedBy, Instant at);

    /** @param value the jsonb rendered as text; {@code 12}, {@code true}, or the string's content */
    record StoredSetting(String key, String value, String description, UUID updatedBy,
                         Instant updatedAt) {}
}
