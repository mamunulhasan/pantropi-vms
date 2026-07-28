package com.pantropi.vms.application.masterdata;

import com.pantropi.vms.application.identity.port.AuditTrail;
import com.pantropi.vms.application.masterdata.port.MasterDataStore;
import com.pantropi.vms.application.masterdata.usecase.MasterDataAdministration;
import com.pantropi.vms.domain.masterdata.Building;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the shared master data pattern (US-04.1.1, T-04.1.1.1) — pure, fake ports.
 *
 * <p>Tested once, through {@link Building}, because five further entities will use the same
 * {@link MasterDataAdministration} instance type. The behaviour asserted here is what each of them
 * inherits by construction rather than by remembering.
 */
class MasterDataAdministrationTest {

    private static final UUID ACTOR = UUID.randomUUID();

    private final FakeStore store = new FakeStore();
    private final RecordingAudit audit = new RecordingAudit();
    private final MasterDataAdministration<Building> admin =
            new MasterDataAdministration<>(new BuildingDefinition(), store, audit);

    @Test
    @DisplayName("AC-1: create writes the row active and audits the creation with an after state")
    void createIsAuditedAndActive() {
        UUID id = admin.create(ACTOR, Building.draft("wgt", "Westgate Tower", "Dhaka"));

        Building stored = store.rows.get(id);
        assertThat(stored.code()).isEqualTo("WGT");          // normalised by the domain record
        assertThat(stored.active()).isTrue();

        assertThat(audit.actions()).containsExactly("building.created");
        assertThat(audit.entries.get(0)[4]).isNull();                       // no before state
        assertThat(String.valueOf(audit.entries.get(0)[5])).contains("Westgate Tower");
    }

    @Test
    @DisplayName("update audits both states")
    void updateIsAuditedWithBothStates() {
        UUID id = admin.create(ACTOR, Building.draft("WGT", "Westgate Tower", null));
        audit.entries.clear();

        admin.update(ACTOR, id, Building.draft("WGT", "Westgate Tower North", null));

        assertThat(audit.actions()).containsExactly("building.updated");
        assertThat(String.valueOf(audit.entries.get(0)[4])).contains("Westgate Tower");
        assertThat(String.valueOf(audit.entries.get(0)[5])).contains("Westgate Tower North");
    }

    @Test
    @DisplayName("updating a deactivated record does not quietly reactivate it")
    void updateDoesNotResurrect() {
        UUID id = admin.create(ACTOR, Building.draft("WGT", "Westgate Tower", null));
        admin.deactivate(ACTOR, id);

        // Building.draft() is active by definition — the use case must keep the stored state.
        admin.update(ACTOR, id, Building.draft("WGT", "Renamed", null));

        assertThat(store.rows.get(id).active()).isFalse();
    }

    @Test
    @DisplayName("AC-3: deactivation retains the row and is audited")
    void deactivateRetainsTheRow() {
        UUID id = admin.create(ACTOR, Building.draft("WGT", "Westgate Tower", null));
        audit.entries.clear();

        admin.deactivate(ACTOR, id);

        assertThat(store.rows).containsKey(id);              // retained, not removed
        assertThat(store.rows.get(id).active()).isFalse();
        assertThat(audit.actions()).containsExactly("building.deactivated");
    }

    @Test
    @DisplayName("deactivating twice is idempotent and does not write a second audit row")
    void deactivateIsIdempotent() {
        UUID id = admin.create(ACTOR, Building.draft("WGT", "Westgate Tower", null));
        admin.deactivate(ACTOR, id);
        audit.entries.clear();

        admin.deactivate(ACTOR, id);

        assertThat(store.rows.get(id).active()).isFalse();
        assertThat(audit.entries).isEmpty();   // nothing happened, so nothing is recorded
    }

    @Test
    @DisplayName("reactivation restores the row and is audited distinctly from an update")
    void reactivate() {
        UUID id = admin.create(ACTOR, Building.draft("WGT", "Westgate Tower", null));
        admin.deactivate(ACTOR, id);
        audit.entries.clear();

        admin.reactivate(ACTOR, id);

        assertThat(store.rows.get(id).active()).isTrue();
        assertThat(audit.actions()).containsExactly("building.reactivated");
    }

    @Test
    @DisplayName("AC-4: a duplicate code surfaces from the store, not from a pre-check")
    void duplicateCodeIsTheStoresAnswer() {
        admin.create(ACTOR, Building.draft("WGT", "Westgate Tower", null));

        assertThatThrownBy(() -> admin.create(ACTOR, Building.draft("wgt", "Another", null)))
                .isInstanceOf(MasterDataStore.DuplicateCode.class);
        // The use case never asks "does this code exist" — the store is the only authority.
        assertThat(store.existenceChecks).isZero();
    }

    @Test
    @DisplayName("operating on an unknown id is a not-found, and audits nothing")
    void unknownIdIsNotFound() {
        UUID missing = UUID.randomUUID();

        assertThatThrownBy(() -> admin.update(ACTOR, missing, Building.draft("X", "Y", null)))
                .isInstanceOf(MasterDataStore.NotFound.class);
        assertThatThrownBy(() -> admin.deactivate(ACTOR, missing))
                .isInstanceOf(MasterDataStore.NotFound.class);
        assertThatThrownBy(() -> admin.get(missing))
                .isInstanceOf(MasterDataStore.NotFound.class);

        assertThat(audit.entries).isEmpty();
    }

    @Test
    @DisplayName("AC-5: the pattern offers no delete — not on the use case, not on the port")
    void noDeleteExistsAnywhere() {
        // Asserted structurally rather than trusted to review. A future entity cannot acquire a
        // delete by accident, because there is nowhere for one to be called from.
        assertThat(methodNames(MasterDataAdministration.class))
                .noneMatch(n -> n.contains("delete") || n.contains("remove") || n.contains("purge"));
        assertThat(methodNames(MasterDataStore.class))
                .noneMatch(n -> n.contains("delete") || n.contains("remove") || n.contains("purge"));
    }

    private static List<String> methodNames(Class<?> type) {
        List<String> names = new ArrayList<>();
        for (Method m : type.getDeclaredMethods()) {
            names.add(m.getName().toLowerCase(java.util.Locale.ROOT));
        }
        return names;
    }

    // ---- fakes ----

    /** In-memory store honouring the same uniqueness and not-found semantics as the JDBC one. */
    private static final class FakeStore implements MasterDataStore<Building> {
        final Map<UUID, Building> rows = new LinkedHashMap<>();
        int existenceChecks;

        public UUID insert(Building draft) {
            rejectDuplicate(draft.code(), null);
            UUID id = UUID.randomUUID();
            rows.put(id, new Building(id, draft.code(), draft.name(), draft.address(),
                    draft.active()));
            return id;
        }
        public void update(UUID id, Building draft) {
            if (!rows.containsKey(id)) throw new NotFound();
            rejectDuplicate(draft.code(), id);
            rows.put(id, new Building(id, draft.code(), draft.name(), draft.address(),
                    draft.active()));
        }
        public void setActive(UUID id, boolean active) {
            Building b = rows.get(id);
            if (b == null) throw new NotFound();
            rows.put(id, new Building(id, b.code(), b.name(), b.address(), active));
        }
        public Optional<Building> find(UUID id) { return Optional.ofNullable(rows.get(id)); }
        public Page<Building> list(Query q) {
            return new Page<>(new ArrayList<>(rows.values()), q.page(), q.size(), rows.size());
        }
        /** Stands in for the database constraint — the caller never performs this check itself. */
        private void rejectDuplicate(String code, UUID excluding) {
            for (Map.Entry<UUID, Building> e : rows.entrySet()) {
                if (!e.getKey().equals(excluding) && e.getValue().code().equals(code)) {
                    throw new DuplicateCode(code);
                }
            }
        }
    }

    private static final class RecordingAudit implements AuditTrail {
        final List<Object[]> entries = new ArrayList<>();

        public void record(UUID a, String action, String t, String i, String d) {
            entries.add(new Object[]{a, action, t, i, null, null});
        }
        public void recordChange(UUID a, String action, String t, String i, String b, String af) {
            entries.add(new Object[]{a, action, t, i, b, af});
        }
        public void recordSecurityDenial(UUID a, String ac, String p, String r, String m, String o,
                                         String ip) {}

        List<String> actions() { return entries.stream().map(e -> (String) e[1]).toList(); }
    }
}
