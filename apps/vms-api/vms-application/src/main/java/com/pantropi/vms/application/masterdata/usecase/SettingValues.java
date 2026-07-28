package com.pantropi.vms.application.masterdata.usecase;

import com.pantropi.vms.application.masterdata.SettingsCatalogue;
import com.pantropi.vms.application.masterdata.port.SettingsStore;

import java.util.Optional;

/**
 * The typed way application code reads a setting (US-04.8.2, T-04.8.2.1, AC-1).
 *
 * <p>Callers ask for {@code getInt("acs.retry.max_attempts")} and receive an {@code int} — never a
 * jsonb node, never a string they have to parse at the call site. Parsing at the call site is how a
 * setting ends up interpreted two different ways in two different places.
 *
 * <h2>A bad stored value fails loudly</h2>
 * If the database holds something that does not match the key's declared type — a hand-edited row,
 * a bad restore — the read throws. It deliberately does <em>not</em> fall back to the default: a
 * silent fallback would mean an operator who set a value sees the system ignoring it, with nothing
 * anywhere saying so. Falling back is right when a value is <em>absent</em>, and wrong when it is
 * present and wrong.
 *
 * <p>Reads go through {@link SettingsStore}, which is where caching lives — so this class stays a
 * pure interpretation layer and knows nothing about how often the database is actually touched.
 *
 * <p>Pure orchestration over ports — no framework.
 */
public final class SettingValues {

    private final SettingsStore store;

    public SettingValues(SettingsStore store) {
        this.store = store;
    }

    /**
     * @throws SettingsCatalogue.UnknownSetting      if the key is not catalogued
     * @throws SettingsCatalogue.InvalidSettingValue if the key is not declared as an integer, or the
     *                                               stored value is not one
     */
    public long getLong(String key) {
        SettingsCatalogue.Entry entry = requireType(key, SettingsCatalogue.Type.INTEGER);
        return Long.parseLong(readNormalised(entry));
    }

    /** Convenience for the common case; the stored value must fit in an {@code int}. */
    public int getInt(String key) {
        long value = getLong(key);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new SettingsCatalogue.InvalidSettingValue(key, "does not fit in an int");
        }
        return (int) value;
    }

    /**
     * @throws SettingsCatalogue.InvalidSettingValue if the key is not declared as a boolean, or the
     *                                               stored value is not one
     */
    public boolean getBoolean(String key) {
        return Boolean.parseBoolean(readNormalised(requireType(key, SettingsCatalogue.Type.BOOLEAN)));
    }

    public String getString(String key) {
        return readNormalised(requireType(key, SettingsCatalogue.Type.STRING));
    }

    private static SettingsCatalogue.Entry requireType(String key, SettingsCatalogue.Type expected) {
        SettingsCatalogue.Entry entry = SettingsCatalogue.require(key);
        if (entry.type() != expected) {
            // A caller asking for the wrong type is a programming error, caught at the first read
            // rather than producing a plausible-looking coerced value.
            throw new SettingsCatalogue.InvalidSettingValue(key,
                    "is declared " + entry.type() + ", not " + expected);
        }
        return entry;
    }

    /**
     * The stored value, or the documented default when no row exists. A stored value that does not
     * parse as the declared type throws — see the class note on why this does not fall back.
     */
    private String readNormalised(SettingsCatalogue.Entry entry) {
        Optional<String> stored = store.find(entry.key())
                .map(SettingsStore.StoredSetting::value);
        if (stored.isEmpty()) {
            return entry.defaultValue();
        }
        return SettingsCatalogue.normalise(entry, stored.get());
    }
}
