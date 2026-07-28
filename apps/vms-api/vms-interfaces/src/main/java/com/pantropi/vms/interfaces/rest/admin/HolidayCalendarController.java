package com.pantropi.vms.interfaces.rest.admin;

import com.pantropi.vms.application.masterdata.port.HolidayCalendarStore;
import com.pantropi.vms.application.masterdata.usecase.HolidayCalendar;
import com.pantropi.vms.domain.masterdata.Holiday;
import com.pantropi.vms.domain.masterdata.MasterDataText;
import com.pantropi.vms.interfaces.rest.security.AuthenticatedPrincipal;
import com.pantropi.vms.interfaces.rest.security.RequiresPermission;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Holiday calendar API (US-04.7.1) — FR-CFG-08 (TDD-derived).
 *
 * <p>Reading needs {@code masterdata.view}; every mutation needs {@code masterdata.edit}.
 *
 * <p><strong>This is the one master data resource with a DELETE</strong>, which is deliberate rather
 * than inconsistent: the table has no {@code is_active} column and nothing references it by foreign
 * key, so removing a date loses only the date (AC-4). The deletion is audited with the full prior
 * state, because once the row is gone that entry is the only record it ever existed.
 */
@RestController
@RequestMapping("/api/v1/admin/holidays")
@RequiresPermission("masterdata.view")
@ConditionalOnProperty(prefix = "vms.masterdata", name = "enabled", havingValue = "true")
public class HolidayCalendarController {

    private final HolidayCalendar calendar;

    public HolidayCalendarController(HolidayCalendar calendar) {
        this.calendar = calendar;
    }

    @RequiresPermission("masterdata.edit")
    @PostMapping
    public ResponseEntity<CreatedResponse> add(
            @RequestAttribute(AuthenticatedPrincipal.ATTRIBUTE) AuthenticatedPrincipal actor,
            @RequestBody HolidayRequest req) {
        UUID id = calendar.add(UUID.fromString(actor.userId()), toDraft(req));
        return ResponseEntity.status(HttpStatus.CREATED).body(new CreatedResponse(id.toString()));
    }

    @RequiresPermission("masterdata.edit")
    @PutMapping("/{id}")
    public ResponseEntity<Void> update(
            @RequestAttribute(AuthenticatedPrincipal.ATTRIBUTE) AuthenticatedPrincipal actor,
            @PathVariable UUID id, @RequestBody HolidayRequest req) {
        calendar.update(UUID.fromString(actor.userId()), id, toDraft(req));
        return ResponseEntity.noContent().build();
    }

    /** The only DELETE in master data — see the class note. */
    @RequiresPermission("masterdata.edit")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> remove(
            @RequestAttribute(AuthenticatedPrincipal.ATTRIBUTE) AuthenticatedPrincipal actor,
            @PathVariable UUID id) {
        calendar.remove(UUID.fromString(actor.userId()), id);
        return ResponseEntity.noContent().build();
    }

    /** Validate-then-apply, all or nothing, with a per-row report (AC-3, AC-6). */
    @RequiresPermission("masterdata.edit")
    @PostMapping("/import")
    public ResponseEntity<HolidayCalendar.ImportReport> importEntries(
            @RequestAttribute(AuthenticatedPrincipal.ATTRIBUTE) AuthenticatedPrincipal actor,
            @RequestBody List<HolidayRequest> entries) {
        HolidayCalendar.ImportReport report = calendar.importEntries(
                UUID.fromString(actor.userId()),
                entries == null ? List.of()
                        : entries.stream().map(HolidayCalendarController::toDraft).toList());

        // 200 when applied, 409 when rejected: the caller needs to distinguish "your file is in"
        // from "your file was read, understood, and none of it was written".
        return ResponseEntity.status(report.accepted() ? HttpStatus.OK : HttpStatus.CONFLICT)
                .body(report);
    }

    @GetMapping("/{id}")
    public HolidayResponse get(@PathVariable UUID id) {
        return HolidayResponse.of(calendar.get(id));
    }

    /**
     * By year, or by an explicit range. Year is the common case and is offered directly so a caller
     * does not have to know that a year ends on the 31st of December.
     */
    @GetMapping
    public List<HolidayResponse> list(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        List<Holiday> found = year != null
                ? calendar.forYear(year)
                : calendar.between(from, to);
        return found.stream().map(HolidayResponse::of).toList();
    }

    private static Holiday toDraft(HolidayRequest req) {
        if (req == null || req.date() == null) {
            throw new MasterDataText.InvalidField("date", "is required");
        }
        return Holiday.draft(req.date(), req.name(), req.working() != null && req.working());
    }

    @ExceptionHandler(HolidayCalendarStore.DuplicateDate.class)
    public ResponseEntity<ErrorResponse> onDuplicate(HolidayCalendarStore.DuplicateDate e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("duplicate", "date", e.getMessage()));
    }

    @ExceptionHandler(HolidayCalendarStore.NotFound.class)
    public ResponseEntity<Void> onNotFound() {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(MasterDataText.InvalidField.class)
    public ResponseEntity<ErrorResponse> onInvalidField(MasterDataText.InvalidField e) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("invalid", e.field, e.getMessage()));
    }

    public record HolidayRequest(
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date, String name,
            Boolean working) {}

    /**
     * @param working true for a working exception — the Saturday that compensates for a holiday —
     *                false for a non-working holiday (AC-2)
     */
    public record HolidayResponse(String id, LocalDate date, String name, boolean working) {
        static HolidayResponse of(Holiday h) {
            return new HolidayResponse(h.id() == null ? null : h.id().toString(), h.date(),
                    h.name(), h.working());
        }
    }

    public record CreatedResponse(String id) {}
    public record ErrorResponse(String error, String field, String message) {}
}
