package com.pantropi.vms.application.masterdata.usecase;

import com.pantropi.vms.application.identity.port.AuditTrail;
import com.pantropi.vms.application.masterdata.port.HolidayCalendarStore;
import com.pantropi.vms.application.shared.port.TransactionRunner;
import com.pantropi.vms.domain.masterdata.Holiday;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Maintain the holiday calendar (US-04.7.1) — FR-CFG-08 (TDD-derived).
 *
 * <p>The one master data entity that does not use the shared pattern, for the reasons on
 * {@link HolidayCalendarStore}: no code, no active flag, keyed by date, and it really deletes.
 *
 * <h2>Deletion is audited with the prior state, and that is the whole safety net</h2>
 * Everywhere else, a mistake is recoverable because the row survives deactivation. Here the row is
 * gone, so the audit entry is the only remaining record that the date was ever a holiday (AC-4). It
 * is written <em>before</em> the delete for that reason — if the delete succeeds and the audit then
 * fails, the entry has vanished without trace.
 *
 * <h2>Bulk import is all-or-nothing, and reports per row</h2>
 * A half-applied year is worse than a rejected one: nobody can tell by looking which half arrived
 * (AC-6). So the whole set is checked first, and applied in one transaction.
 *
 * <p>Pure orchestration over ports — no framework.
 */
public final class HolidayCalendar {

    private final HolidayCalendarStore store;
    private final TransactionRunner transactions;
    private final AuditTrail audit;

    public HolidayCalendar(HolidayCalendarStore store, TransactionRunner transactions,
                           AuditTrail audit) {
        this.store = store;
        this.transactions = transactions;
        this.audit = audit;
    }

    /** @throws HolidayCalendarStore.DuplicateDate if the date already has an entry (AC-5) */
    public UUID add(UUID actorId, Holiday draft) {
        UUID id = store.insert(draft);
        audit.recordChange(actorId, "holiday.created", "holiday", id.toString(),
                null, json(new Holiday(id, draft.date(), draft.name(), draft.working())));
        return id;
    }

    /** @throws HolidayCalendarStore.NotFound if no entry has that id */
    public void update(UUID actorId, UUID id, Holiday entry) {
        Holiday before = require(id);
        Holiday after = new Holiday(id, entry.date(), entry.name(), entry.working());

        store.update(id, after);

        audit.recordChange(actorId, "holiday.updated", "holiday", id.toString(),
                json(before), json(after));
    }

    /**
     * Remove an entry (AC-4).
     *
     * @throws HolidayCalendarStore.NotFound if no entry has that id
     */
    public void remove(UUID actorId, UUID id) {
        Holiday before = require(id);

        // Audited first, deliberately. This row has no other trace once it is gone, so recording
        // afterwards would risk losing the entry and the evidence of it together.
        audit.recordChange(actorId, "holiday.deleted", "holiday", id.toString(),
                json(before), null);

        store.delete(id);
    }

    public Holiday get(UUID id) {
        return require(id);
    }

    /** Every entry in the year, ordered by date (AC-2). */
    public List<Holiday> forYear(int year) {
        return store.between(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
    }

    /** Every entry in an inclusive range (AC-2). */
    public List<Holiday> between(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new IllegalArgumentException("A range needs a start and an end, in that order");
        }
        return store.between(from, to);
    }

    /**
     * Validate the whole set, then apply it in one transaction (AC-3, AC-6).
     *
     * <p>Nothing is written unless every row can be. The report names each offending row so an
     * operator can fix the file rather than guess which of two hundred dates was the problem.
     */
    public ImportReport importEntries(UUID actorId, List<Holiday> entries) {
        if (entries == null || entries.isEmpty()) {
            throw new IllegalArgumentException("Nothing to import");
        }

        List<RowOutcome> outcomes = new ArrayList<>(entries.size());

        // Duplicates within the submitted set itself. The database cannot report these usefully —
        // it would fail on the second one and say nothing about the first.
        Set<LocalDate> seen = new LinkedHashSet<>();
        Set<LocalDate> repeated = new LinkedHashSet<>();
        for (Holiday entry : entries) {
            if (!seen.add(entry.date())) {
                repeated.add(entry.date());
            }
        }

        Set<LocalDate> alreadyPresent = new HashSet<>(
                store.existingDates(entries.stream().map(Holiday::date).distinct().toList()));

        boolean rejected = !repeated.isEmpty() || !alreadyPresent.isEmpty();

        for (Holiday entry : entries) {
            if (repeated.contains(entry.date())) {
                outcomes.add(RowOutcome.rejected(entry.date(), "appears more than once in this set"));
            } else if (alreadyPresent.contains(entry.date())) {
                outcomes.add(RowOutcome.rejected(entry.date(), "already exists in the calendar"));
            } else if (rejected) {
                // Nothing is applied when anything is rejected, so this row is not "accepted" —
                // saying so plainly avoids an operator believing part of the file went in.
                outcomes.add(RowOutcome.notApplied(entry.date()));
            } else {
                outcomes.add(RowOutcome.applied(entry.date()));
            }
        }

        if (rejected) {
            audit.record(actorId, "holiday.import_rejected", "holiday", null,
                    entries.size() + " row(s) submitted; none applied");
            return new ImportReport(false, 0, outcomes);
        }

        transactions.run(() -> {
            // The pre-check above produced the report; this is what actually guarantees the
            // invariant. If a concurrent insert takes one of these dates in between, the unique
            // constraint fires here and the whole transaction rolls back — so the calendar is
            // never half-imported even though the check and the write are not one statement.
            store.insertAll(entries);
        });

        audit.record(actorId, "holiday.imported", "holiday", null,
                entries.size() + " row(s) applied");
        return new ImportReport(true, entries.size(), outcomes);
    }

    private Holiday require(UUID id) {
        return store.find(id).orElseThrow(HolidayCalendarStore.NotFound::new);
    }

    private static String json(Holiday entry) {
        return "{\"date\":\"" + entry.date()
                + "\",\"name\":\"" + entry.name().replace("\\", "\\\\").replace("\"", "\\\"")
                + "\",\"working\":" + entry.working() + "}";
    }

    /** @param applied how many rows were written — zero whenever anything was rejected */
    public record ImportReport(boolean accepted, int applied, List<RowOutcome> rows) {}

    public record RowOutcome(LocalDate date, String status, String reason) {
        static RowOutcome applied(LocalDate date) {
            return new RowOutcome(date, "applied", null);
        }
        static RowOutcome rejected(LocalDate date, String reason) {
            return new RowOutcome(date, "rejected", reason);
        }
        static RowOutcome notApplied(LocalDate date) {
            return new RowOutcome(date, "not_applied", "another row in this set was rejected");
        }
    }
}
