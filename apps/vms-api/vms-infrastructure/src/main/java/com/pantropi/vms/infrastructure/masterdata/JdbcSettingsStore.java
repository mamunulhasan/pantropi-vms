package com.pantropi.vms.infrastructure.masterdata;

import com.pantropi.vms.application.masterdata.SettingsCatalogue;
import com.pantropi.vms.application.masterdata.port.SettingsStore;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC adapter for {@link SettingsStore} over {@code vms.system_settings} (US-04.8.1).
 *
 * <p>This class is the only place that knows the column is jsonb.
 *
 * <p>Reads use {@code value #>> '{}'}, which extracts any jsonb scalar as text — {@code 12} becomes
 * "12", {@code true} becomes "true", and a JSON string yields its content without the quotes. That
 * one expression covers every catalogued type, so the application never handles a JSON node.
 *
 * <p>Writes build the jsonb from the declared type rather than casting blindly. Passing
 * {@code '12'::jsonb} stores a number and {@code to_jsonb('12'::text)} stores the string "12" —
 * different values that would read back identically through {@code #>>}, and would only diverge
 * later when something compared them as JSON. Deciding from the catalogue's type keeps the stored
 * shape honest.
 */
public final class JdbcSettingsStore implements SettingsStore {

    private static final String SELECT = """
            SELECT key, value #>> '{}' AS value_text, description, updated_by, updated_at
            FROM vms.system_settings
            """;

    private final JdbcTemplate jdbc;

    public JdbcSettingsStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<StoredSetting> findAll() {
        return jdbc.query(SELECT + " ORDER BY key", JdbcSettingsStore::map);
    }

    @Override
    public Optional<StoredSetting> find(String key) {
        return jdbc.query(SELECT + " WHERE key = ?", JdbcSettingsStore::map, key)
                .stream().findFirst();
    }

    @Override
    public void write(String key, SettingsCatalogue.Type type, String value, UUID updatedBy,
                      Instant at) {
        // The row is created by the baseline seed; the upsert keeps the write working on a
        // deployment restored without it, rather than silently updating zero rows.
        jdbc.update("""
                INSERT INTO vms.system_settings (key, value, updated_by, updated_at)
                VALUES (?, %s, ?, ?)
                ON CONFLICT (key) DO UPDATE SET
                    value = EXCLUDED.value,
                    updated_by = EXCLUDED.updated_by,
                    updated_at = EXCLUDED.updated_at
                """.formatted(jsonbExpression(type)),
                key, value, updatedBy, Timestamp.from(at));
    }

    /**
     * The SQL fragment producing jsonb of the right shape. Chosen from the enum, never built from
     * caller input — the value itself is always a bound parameter, so this cannot inject.
     */
    private static String jsonbExpression(SettingsCatalogue.Type type) {
        return switch (type) {
            case INTEGER, BOOLEAN -> "?::jsonb";      // bare literal: a JSON number or boolean
            case STRING -> "to_jsonb(?::text)";       // quoted JSON string
        };
    }

    private static StoredSetting map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        return new StoredSetting(
                rs.getString("key"),
                rs.getString("value_text"),
                rs.getString("description"),
                rs.getObject("updated_by", UUID.class),
                updatedAt == null ? null : updatedAt.toInstant());
    }
}
