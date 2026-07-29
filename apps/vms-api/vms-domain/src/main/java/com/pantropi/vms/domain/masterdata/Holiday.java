package com.pantropi.vms.domain.masterdata;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A dated entry in the holiday calendar (US-04.7.1) — FR-CFG-08 (TDD-derived), schema
 * {@code vms.holiday_calendar}.
 *
 * <p>{@code working} inverts the usual reading: most entries are non-working holidays, but the
 * calendar also has to express the working Saturday that compensates for one. The schema defaults it
 * to false, so an entry is a holiday unless it says otherwise.
 *
 * <h2>This phase attaches no behaviour to a holiday</h2>
 * Nothing schedules, blocks or reschedules anything because of an entry here — <strong>no SRS
 * requirement says what a holiday should change</strong>. The calendar is data, recorded so that
 * later scheduling and reporting have an authoritative definition to consult. Inventing a rule now
 * ("no visits on holidays") would be inventing a requirement, and it would be the kind that only
 * surfaces when someone is turned away at a door.
 *
 * <h2>Why this record is unlike the other master data</h2>
 * No {@code code}, no {@code is_active}, uniqueness on a date, and entries are genuinely deleted
 * rather than deactivated (AC-4) — nothing references this table by foreign key, so removing a date
 * loses nothing but the date. That is why it does not use the shared master data pattern, whose
 * central rule is that no delete exists.
 *
 * <p>Pure Java: no framework.
 */
public record Holiday(UUID id, LocalDate date, String name, boolean working) {

    public Holiday {
        if (date == null) {
            throw new MasterDataText.InvalidField("date", "is required");
        }
        name = MasterDataText.name(name);
    }

    /** An entry not yet persisted. */
    public static Holiday draft(LocalDate date, String name, boolean working) {
        return new Holiday(null, date, name, working);
    }
}
