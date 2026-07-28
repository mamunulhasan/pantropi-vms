package com.pantropi.vms.application.masterdata;

import com.pantropi.vms.application.identity.port.AuditTrail;
import com.pantropi.vms.application.masterdata.port.HolidayCalendarStore;
import com.pantropi.vms.application.masterdata.usecase.HolidayCalendar;
import com.pantropi.vms.application.shared.port.TransactionRunner;
import com.pantropi.vms.domain.masterdata.Holiday;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link HolidayCalendar} (US-04.7.1) — pure, fake ports. */
class HolidayCalendarTest {

    private static final UUID ACTOR = UUID.randomUUID();
    private static final LocalDate EID = LocalDate.of(2026, 3, 20);
    private static final LocalDate NEW_YEAR = LocalDate.of(2026, 1, 1);

    private final FakeStore store = new FakeStore();
    private final RecordingAudit audit = new RecordingAudit();
    private final HolidayCalendar calendar =
            new HolidayCalendar(store, new DirectTransactions(), audit);

    @Test
    @DisplayName("AC-1: adding an entry writes it and audits the creation")
    void addIsAudited() {
        UUID id = calendar.add(ACTOR, Holiday.draft(EID, "Eid al-Fitr", false));

        assertThat(store.rows.get(id).name()).isEqualTo("Eid al-Fitr");
        assertThat(audit.actions()).containsExactly("holiday.created");
        assertThat(String.valueOf(audit.entries.get(0)[5])).contains("2026-03-20");
    }

    @Test
    @DisplayName("AC-4: deletion is audited with the prior state BEFORE the row is removed")
    void deletionIsAuditedFirst() {
        UUID id = calendar.add(ACTOR, Holiday.draft(EID, "Eid al-Fitr", false));
        audit.entries.clear();
        store.failNextDelete = true;

        // The row is the only record of itself, so if the delete fails the audit must already
        // exist — and if the audit failed, the delete must not have happened.
        assertThatThrownBy(() -> calendar.remove(ACTOR, id)).isInstanceOf(RuntimeException.class);

        assertThat(audit.actions()).containsExactly("holiday.deleted");
        assertThat(String.valueOf(audit.entries.get(0)[4])).contains("Eid al-Fitr");
        assertThat(audit.entries.get(0)[5]).isNull();   // no after state — it is gone
    }

    @Test
    @DisplayName("AC-4: a successful delete removes the row and leaves the audit behind")
    void deleteRemovesTheRow() {
        UUID id = calendar.add(ACTOR, Holiday.draft(EID, "Eid al-Fitr", false));

        calendar.remove(ACTOR, id);

        assertThat(store.rows).doesNotContainKey(id);
        assertThat(audit.actions()).contains("holiday.deleted");
    }

    @Test
    @DisplayName("AC-2: a year query covers the whole year, ends included")
    void yearQueryCoversTheYear() {
        calendar.add(ACTOR, Holiday.draft(NEW_YEAR, "New Year", false));
        calendar.add(ACTOR, Holiday.draft(LocalDate.of(2026, 12, 31), "Year end", false));
        calendar.add(ACTOR, Holiday.draft(LocalDate.of(2027, 1, 1), "Next year", false));

        assertThat(calendar.forYear(2026)).extracting(Holiday::date)
                .containsExactly(NEW_YEAR, LocalDate.of(2026, 12, 31));
    }

    @Test
    @DisplayName("AC-2: a working exception is distinguishable from a holiday")
    void workingExceptionIsDistinguishable() {
        UUID id = calendar.add(ACTOR, Holiday.draft(LocalDate.of(2026, 3, 21),
                "Compensating working Saturday", true));

        assertThat(store.rows.get(id).working()).isTrue();
    }

    @Test
    @DisplayName("a backwards or incomplete range is refused rather than returning nothing")
    void badRangeRefused() {
        assertThatThrownBy(() -> calendar.between(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 4, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> calendar.between(null, LocalDate.of(2026, 4, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- bulk import ----

    @Test
    @DisplayName("AC-3: a clean set applies in full and reports every row as applied")
    void cleanImportApplies() {
        HolidayCalendar.ImportReport report = calendar.importEntries(ACTOR, List.of(
                Holiday.draft(NEW_YEAR, "New Year", false),
                Holiday.draft(EID, "Eid al-Fitr", false)));

        assertThat(report.accepted()).isTrue();
        assertThat(report.applied()).isEqualTo(2);
        assertThat(report.rows()).extracting(HolidayCalendar.RowOutcome::status)
                .containsExactly("applied", "applied");
        assertThat(store.rows).hasSize(2);
    }

    @Test
    @DisplayName("AC-6: one clash with an existing date writes nothing at all")
    void existingDateBlocksTheWholeImport() {
        calendar.add(ACTOR, Holiday.draft(NEW_YEAR, "New Year", false));
        int before = store.rows.size();

        HolidayCalendar.ImportReport report = calendar.importEntries(ACTOR, List.of(
                Holiday.draft(NEW_YEAR, "New Year again", false),
                Holiday.draft(EID, "Eid al-Fitr", false)));

        assertThat(report.accepted()).isFalse();
        assertThat(report.applied()).isZero();
        assertThat(store.rows).hasSize(before);      // a half-imported year is worse than none
        assertThat(report.rows().get(0).status()).isEqualTo("rejected");
        assertThat(report.rows().get(0).reason()).contains("already exists");
        // The other row is reported as not applied rather than accepted, so nobody believes half
        // the file went in.
        assertThat(report.rows().get(1).status()).isEqualTo("not_applied");
    }

    @Test
    @DisplayName("AC-6: a duplicate inside the submitted set is caught before touching the database")
    void duplicateWithinTheSetIsCaught() {
        HolidayCalendar.ImportReport report = calendar.importEntries(ACTOR, List.of(
                Holiday.draft(EID, "Eid", false),
                Holiday.draft(EID, "Eid again", false)));

        assertThat(report.accepted()).isFalse();
        assertThat(store.rows).isEmpty();
        // The database would fail on the second and say nothing useful about the first, so both
        // are named here.
        assertThat(report.rows()).allSatisfy(row ->
                assertThat(row.status()).isEqualTo("rejected"));
        assertThat(report.rows().get(0).reason()).contains("more than once");
    }

    @Test
    @DisplayName("AC-3: a rejected import is audited as an attempt, so the try is on the record")
    void rejectedImportIsAudited() {
        calendar.add(ACTOR, Holiday.draft(NEW_YEAR, "New Year", false));
        audit.entries.clear();

        calendar.importEntries(ACTOR, List.of(Holiday.draft(NEW_YEAR, "Clash", false)));

        assertThat(audit.actions()).containsExactly("holiday.import_rejected");
    }

    @Test
    @DisplayName("the import runs inside one transaction, so a late failure discards everything")
    void importIsTransactional() {
        assertThat(calendar.importEntries(ACTOR, List.of(
                Holiday.draft(NEW_YEAR, "New Year", false))).accepted()).isTrue();
        assertThat(store.insertAllCalls).isEqualTo(1);
        assertThat(store.transactionDepthAtInsert).isEqualTo(1);   // inside the runner
    }

    @Test
    @DisplayName("an empty import is refused rather than reported as a successful no-op")
    void emptyImportRefused() {
        assertThatThrownBy(() -> calendar.importEntries(ACTOR, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- fakes ----

    private final class DirectTransactions implements TransactionRunner {
        public <T> T call(Supplier<T> work) {
            store.transactionDepth++;
            try {
                return work.get();
            } finally {
                store.transactionDepth--;
            }
        }
    }

    private static final class FakeStore implements HolidayCalendarStore {
        final Map<UUID, Holiday> rows = new LinkedHashMap<>();
        boolean failNextDelete;
        int insertAllCalls;
        int transactionDepth;
        int transactionDepthAtInsert;

        public UUID insert(Holiday draft) {
            rejectDuplicate(draft.date(), null);
            UUID id = UUID.randomUUID();
            rows.put(id, new Holiday(id, draft.date(), draft.name(), draft.working()));
            return id;
        }
        public void insertAll(List<Holiday> drafts) {
            insertAllCalls++;
            transactionDepthAtInsert = transactionDepth;
            drafts.forEach(this::insert);
        }
        public List<LocalDate> existingDates(List<LocalDate> candidates) {
            return rows.values().stream().map(Holiday::date).filter(candidates::contains).toList();
        }
        public void update(UUID id, Holiday entry) {
            if (!rows.containsKey(id)) throw new NotFound();
            rejectDuplicate(entry.date(), id);
            rows.put(id, new Holiday(id, entry.date(), entry.name(), entry.working()));
        }
        public void delete(UUID id) {
            if (failNextDelete) {
                failNextDelete = false;
                throw new IllegalStateException("simulated delete failure");
            }
            if (rows.remove(id) == null) throw new NotFound();
        }
        public Optional<Holiday> find(UUID id) { return Optional.ofNullable(rows.get(id)); }
        public List<Holiday> between(LocalDate from, LocalDate to) {
            return rows.values().stream()
                    .filter(h -> !h.date().isBefore(from) && !h.date().isAfter(to))
                    .sorted(java.util.Comparator.comparing(Holiday::date))
                    .toList();
        }
        private void rejectDuplicate(LocalDate date, UUID excluding) {
            for (Map.Entry<UUID, Holiday> e : rows.entrySet()) {
                if (!e.getKey().equals(excluding) && e.getValue().date().equals(date)) {
                    throw new DuplicateDate(date);
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
