package com.pantropi.vms.interfaces.rest.admin;

import com.pantropi.vms.application.identity.port.AuditLogQueries;
import com.pantropi.vms.interfaces.rest.security.RequiresPermission;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The audit trail, across every entity (US-02.5.1) — FR-AUD-01/02.
 *
 * <p>{@code audit.view} already existed and exactly one route served it:
 * {@code GET /visitor-requests/{id}/history}, hard-scoped to one request. Everything else the audit
 * writer records — master-data edits, user imports, settings changes, authorization denials — was
 * written and never readable. This is the list that makes the permission mean what its description
 * says.
 *
 * <p>Read-only: there is no POST, PUT or DELETE here and there cannot be. The table is append-only
 * by trigger since V12, and entries are written inside the transaction of the change that caused
 * them. An endpoint that could add one would allow a record of something that did not happen.
 *
 * <p>Filters are exact matches over a vocabulary the data itself supplies ({@code /vocabulary}),
 * rather than a free-text search over {@code before_state}/{@code after_state}. Those columns hold
 * allow-listed projections that can name people; searching them would turn an accountability record
 * into a way to look people up, which is the opposite of the point.
 */
@RestController
@RequestMapping("/api/v1/admin/audit")
@RequiresPermission("audit.view")
@ConditionalOnProperty(prefix = "vms.identity", name = "enabled", havingValue = "true")
public class AuditLogController {

    private final AuditLogQueries audit;

    public AuditLogController(AuditLogQueries audit) {
        this.audit = audit;
    }

    /**
     * @param to exclusive, so a single day is {@code from=00:00&to=next 00:00} and no entry can
     *           land in two days at once
     */
    @GetMapping
    public PageResponse list(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        AuditLogQueries.Page found = audit.search(
                new AuditLogQueries.Filter(action, entityType, actorId, from, to, page, size));

        return new PageResponse(
                found.content().stream().map(EntryResponse::of).toList(),
                found.totalElements(), found.page(), found.size());
    }

    /** What the filters may offer — the distinct values actually present in the trail. */
    @GetMapping("/vocabulary")
    public AuditLogQueries.Vocabulary vocabulary() {
        return audit.vocabulary();
    }

    /**
     * @param actor    a display name, never a username or email — the same rule the decision trail
     *                 follows: a reader needs to know who acted, not how to contact them
     * @param actorId  null once the account is removed; the entry outlives it
     */
    public record EntryResponse(String id, Instant at, String action, String entityType,
                                String entityId, String actorId, String actor, String beforeState,
                                String afterState, String ipAddress) {

        static EntryResponse of(AuditLogQueries.Entry e) {
            return new EntryResponse(String.valueOf(e.id()), e.at(), e.action(), e.entityType(),
                    e.entityId(), e.actorId() == null ? null : e.actorId().toString(), e.actor(),
                    e.beforeState(), e.afterState(), e.ipAddress());
        }
    }

    public record PageResponse(List<EntryResponse> content, long totalElements, int page, int size) {}
}
