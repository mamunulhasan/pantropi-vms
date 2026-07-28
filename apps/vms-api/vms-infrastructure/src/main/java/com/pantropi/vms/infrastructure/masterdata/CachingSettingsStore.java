package com.pantropi.vms.infrastructure.masterdata;

import com.pantropi.vms.application.masterdata.SettingsCatalogue;
import com.pantropi.vms.application.masterdata.port.SettingsStore;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Caches settings in memory, invalidated on write (US-04.8.2, T-04.8.2.1, AC-3).
 *
 * <p>A decorator over another {@link SettingsStore}, so nothing that reads settings knows this
 * exists. Settings are read on paths that run per request and change perhaps a few times a year;
 * a database round trip for each read buys nothing.
 *
 * <p>The whole table is cached as one immutable snapshot rather than per key. There are four rows.
 * Loading them together means a listing is one query, and swapping an immutable map in an
 * {@link AtomicReference} avoids any locking — a reader either sees the whole old snapshot or the
 * whole new one, never a half-updated map.
 *
 * <h2>Single instance only — and that is a real limit, not a detail</h2>
 * Invalidation happens in the process that performed the write. On a second instance, a stale
 * snapshot survives until that instance next reloads. With one instance this is correct; with two,
 * a setting changed on one node is not seen by the other.
 *
 * <p>Cross-instance invalidation is <strong>US-04.9.2</strong>, which needs an event broadcast
 * (Kafka in the TDD, the {@code vms.domain_events} outbox here). This class is the seam: a Redis or
 * event-driven implementation replaces it without touching a single caller. Until that story lands,
 * <strong>a multi-instance deployment must not assume a settings change propagates.</strong>
 */
public final class CachingSettingsStore implements SettingsStore {

    private final SettingsStore delegate;

    /** Null means "not loaded". Replaced wholesale, never mutated in place. */
    private final AtomicReference<Map<String, StoredSetting>> snapshot = new AtomicReference<>();

    public CachingSettingsStore(SettingsStore delegate) {
        this.delegate = delegate;
    }

    @Override
    public List<StoredSetting> findAll() {
        return List.copyOf(current().values());
    }

    @Override
    public Optional<StoredSetting> find(String key) {
        return Optional.ofNullable(current().get(key));
    }

    @Override
    public void write(String key, SettingsCatalogue.Type type, String value, UUID updatedBy,
                      Instant at) {
        delegate.write(key, type, value, updatedBy, at);
        // Drop the snapshot rather than patching it: the next read reloads from the database and
        // therefore sees whatever the database actually stored, including any normalisation the
        // adapter applied. Patching would cache what we *think* we wrote.
        snapshot.set(null);
    }

    /** Visible for the rare case of an out-of-band change (a migration, a manual fix). */
    public void invalidate() {
        snapshot.set(null);
    }

    private Map<String, StoredSetting> current() {
        Map<String, StoredSetting> cached = snapshot.get();
        if (cached != null) {
            return cached;
        }
        // A benign race: two threads may both load. Both produce an equivalent snapshot and the
        // second simply wins. Cheaper and simpler than holding a lock across a query.
        Map<String, StoredSetting> loaded = new LinkedHashMap<>();
        for (StoredSetting s : delegate.findAll()) {
            loaded.put(s.key(), s);
        }
        // LinkedHashMap, not Map.copyOf: the delegate returns rows ordered by key, and Map.copyOf
        // gives an unspecified iteration order. A cache must not quietly reorder what it caches.
        Map<String, StoredSetting> immutable = java.util.Collections.unmodifiableMap(loaded);
        snapshot.set(immutable);
        return immutable;
    }
}
