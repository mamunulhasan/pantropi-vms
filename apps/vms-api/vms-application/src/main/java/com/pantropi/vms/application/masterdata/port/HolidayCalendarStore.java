package com.pantropi.vms.application.masterdata.port;

import com.pantropi.vms.domain.masterdata.Holiday;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port for {@code vms.holiday_calendar} (US-04.7.1, T-04.7.1.1).
 *
 * <p>Deliberately <strong>not</strong> {@link MasterDataStore}. The holiday calendar has no
 * {@code code}, no {@code is_active}, is keyed by a date, and genuinely deletes. Bending it into a
 * port whose defining rule is "no delete exists" would mean adding a delete to that port — which the
 * other five entities would then have to be trusted never to call, in place of a guarantee that is
 * currently structural and asserted by test.
 *
 * <p>A separate port of about thirty lines is the cheaper honest answer than a shared one that no
 * longer means what it says.
 */
public interface HolidayCalendarStore {

    /** @throws DuplicateDate if an entry already exists for that date (AC-5) */
    UUID insert(Holiday draft);

    /**
     * Insert several entries. Called inside a transaction the caller controls, so a failure part
     * way through rolls the whole set back (AC-6).
     *
     * @throws DuplicateDate naming the first date that collides
     */
    void insertAll(List<Holiday> drafts);

    /** Dates from the given set that already have an entry — used to report before applying. */
    List<LocalDate> existingDates(List<LocalDate> candidates);

    /** @throws NotFound if no entry has that id */
    void update(UUID id, Holiday entry);

    /** Hard delete (AC-4). The caller audits the prior state first. */
    void delete(UUID id);

    Optional<Holiday> find(UUID id);

    /** Entries within the range, inclusive of both ends, ordered by date (AC-2). */
    List<Holiday> between(LocalDate from, LocalDate to);

    class DuplicateDate extends RuntimeException {
        public final LocalDate date;

        public DuplicateDate(LocalDate date) {
            super("An entry already exists for " + date);
            this.date = date;
        }
    }

    class NotFound extends RuntimeException {
        public NotFound() {
            super("No such calendar entry");
        }
    }
}
