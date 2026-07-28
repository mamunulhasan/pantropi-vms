package com.pantropi.vms.infrastructure.masterdata;

import com.pantropi.vms.application.masterdata.port.HolidayCalendarStore;
import com.pantropi.vms.domain.masterdata.Holiday;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC adapter for {@link HolidayCalendarStore} over {@code vms.holiday_calendar} (US-04.7.1).
 *
 * <p>The only master data adapter with a {@code DELETE}, and the only one keyed by something other
 * than a code. Both are proper to this table: it has no dependants and no {@code is_active} column.
 */
public final class JdbcHolidayCalendarStore implements HolidayCalendarStore {

    private static final String COLUMNS = "id, holiday_date, name, is_working";

    private final JdbcTemplate jdbc;

    public JdbcHolidayCalendarStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UUID insert(Holiday draft) {
        try {
            return jdbc.queryForObject("""
                    INSERT INTO vms.holiday_calendar (holiday_date, name, is_working)
                    VALUES (?, ?, ?) RETURNING id
                    """, UUID.class,
                    Date.valueOf(draft.date()), draft.name(), draft.working());
        } catch (DuplicateKeyException e) {
            throw new DuplicateDate(draft.date());
        }
    }

    @Override
    public void insertAll(List<Holiday> drafts) {
        // One statement per row rather than a batch: a batch reports a failure index, and turning
        // that back into a date is guesswork the moment the driver reorders or truncates. The
        // caller runs this inside a transaction, so a failure anywhere discards everything.
        for (Holiday draft : drafts) {
            try {
                jdbc.update("""
                        INSERT INTO vms.holiday_calendar (holiday_date, name, is_working)
                        VALUES (?, ?, ?)
                        """, Date.valueOf(draft.date()), draft.name(), draft.working());
            } catch (DuplicateKeyException e) {
                throw new DuplicateDate(draft.date());
            }
        }
    }

    @Override
    public List<LocalDate> existingDates(List<LocalDate> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        // One query for the whole set — checking each date individually would be a round trip per
        // row, and a year import is three hundred of them.
        String placeholders = String.join(",", java.util.Collections.nCopies(candidates.size(), "?"));
        Object[] args = candidates.stream().map(Date::valueOf).toArray();
        return jdbc.query(
                "SELECT holiday_date FROM vms.holiday_calendar WHERE holiday_date IN (" + placeholders + ")",
                (rs, i) -> rs.getObject("holiday_date", LocalDate.class), args);
    }

    @Override
    public void update(UUID id, Holiday entry) {
        int updated;
        try {
            updated = jdbc.update("""
                    UPDATE vms.holiday_calendar SET holiday_date = ?, name = ?, is_working = ?
                    WHERE id = ?
                    """, Date.valueOf(entry.date()), entry.name(), entry.working(), id);
        } catch (DuplicateKeyException e) {
            throw new DuplicateDate(entry.date());
        }
        if (updated == 0) {
            throw new NotFound();
        }
    }

    @Override
    public void delete(UUID id) {
        if (jdbc.update("DELETE FROM vms.holiday_calendar WHERE id = ?", id) == 0) {
            throw new NotFound();
        }
    }

    @Override
    public Optional<Holiday> find(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM vms.holiday_calendar WHERE id = ?",
                JdbcHolidayCalendarStore::map, id).stream().findFirst();
    }

    @Override
    public List<Holiday> between(LocalDate from, LocalDate to) {
        return jdbc.query("SELECT " + COLUMNS + " FROM vms.holiday_calendar"
                        + " WHERE holiday_date BETWEEN ? AND ? ORDER BY holiday_date",
                JdbcHolidayCalendarStore::map, Date.valueOf(from), Date.valueOf(to));
    }

    private static Holiday map(ResultSet rs, int rowNum) throws SQLException {
        return new Holiday(
                rs.getObject("id", UUID.class),
                rs.getObject("holiday_date", LocalDate.class),
                rs.getString("name"),
                rs.getBoolean("is_working"));
    }
}
