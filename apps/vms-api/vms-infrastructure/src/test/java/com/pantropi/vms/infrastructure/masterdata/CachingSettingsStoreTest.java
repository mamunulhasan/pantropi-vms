package com.pantropi.vms.infrastructure.masterdata;

import com.pantropi.vms.application.masterdata.SettingsCatalogue;
import com.pantropi.vms.application.masterdata.port.SettingsStore;
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

/** Unit tests for {@link CachingSettingsStore} (US-04.8.2, T-04.8.2.1, AC-3). */
class CachingSettingsStoreTest {

    private final CountingStore delegate = new CountingStore();
    private final CachingSettingsStore cache = new CachingSettingsStore(delegate);

    @Test
    @DisplayName("AC-3: repeated reads hit the database once, not once per read")
    void readsAreServedFromCache() {
        delegate.put("acs.retry.max_attempts", "5");

        for (int i = 0; i < 10; i++) {
            cache.find("acs.retry.max_attempts");
            cache.findAll();
        }

        assertThat(delegate.loads).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-3: a write invalidates, so the next read sees the new value")
    void writeInvalidates() {
        delegate.put("acs.retry.max_attempts", "5");
        assertThat(cache.find("acs.retry.max_attempts").orElseThrow().value()).isEqualTo("5");

        cache.write("acs.retry.max_attempts", SettingsCatalogue.Type.INTEGER, "9",
                UUID.randomUUID(), Instant.EPOCH);

        assertThat(cache.find("acs.retry.max_attempts").orElseThrow().value()).isEqualTo("9");
        assertThat(delegate.loads).isEqualTo(2);   // reloaded exactly once after the write
    }

    @Test
    @DisplayName("the reload reads back from the database rather than caching what we think we wrote")
    void reloadComesFromTheDatabase() {
        // The delegate deliberately stores something different from what was asked. A cache that
        // patched its snapshot optimistically would report "9"; one that reloads reports the truth.
        delegate.normaliseTo = "0009";
        cache.write("acs.retry.max_attempts", SettingsCatalogue.Type.INTEGER, "9",
                UUID.randomUUID(), Instant.EPOCH);

        assertThat(cache.find("acs.retry.max_attempts").orElseThrow().value()).isEqualTo("0009");
    }

    @Test
    @DisplayName("a change made behind the cache's back is invisible until invalidated")
    void staleUntilInvalidated() {
        // This is the documented single-instance limit, asserted rather than left as prose: a
        // second application instance writing to the same database is exactly this scenario, and
        // it is what US-04.9.2 exists to fix. If this test ever starts failing because the cache
        // learned to notice, that story has effectively landed and this expectation should change.
        delegate.put("acs.retry.max_attempts", "5");
        assertThat(cache.find("acs.retry.max_attempts").orElseThrow().value()).isEqualTo("5");

        delegate.put("acs.retry.max_attempts", "99");           // as if from another instance
        assertThat(cache.find("acs.retry.max_attempts").orElseThrow().value()).isEqualTo("5");

        cache.invalidate();
        assertThat(cache.find("acs.retry.max_attempts").orElseThrow().value()).isEqualTo("99");
    }

    @Test
    @DisplayName("the cache preserves the delegate's ordering rather than quietly reshuffling it")
    void orderingIsPreserved() {
        delegate.put("aaa", "1");
        delegate.put("bbb", "2");
        delegate.put("ccc", "3");

        assertThat(cache.findAll()).extracting(SettingsStore.StoredSetting::key)
                .containsExactly("aaa", "bbb", "ccc");
    }

    @Test
    @DisplayName("an unknown key is empty and does not trigger a reload each time")
    void unknownKeyDoesNotThrash() {
        delegate.put("acs.retry.max_attempts", "5");

        assertThat(cache.find("no.such.key")).isEmpty();
        assertThat(cache.find("no.such.key")).isEmpty();
        assertThat(delegate.loads).isEqualTo(1);
    }

    /** Counts how often the underlying store is actually read. */
    private static final class CountingStore implements SettingsStore {
        final Map<String, StoredSetting> rows = new LinkedHashMap<>();
        int loads;
        String normaliseTo;

        void put(String key, String value) {
            rows.put(key, new StoredSetting(key, value, null, null, Instant.EPOCH));
        }
        public List<StoredSetting> findAll() {
            loads++;
            return new ArrayList<>(rows.values());
        }
        public Optional<StoredSetting> find(String key) {
            loads++;
            return Optional.ofNullable(rows.get(key));
        }
        public void write(String key, SettingsCatalogue.Type type, String value, UUID by,
                          Instant at) {
            put(key, normaliseTo == null ? value : normaliseTo);
        }
    }
}
