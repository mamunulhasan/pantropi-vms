package com.pantropi.vms.application.identity.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Reads the audit trail across entities (US-02.5.1) — FR-AUD-01/02 (TDD §4.6).
 *
 * <p>{@link com.pantropi.vms.application.visitor.port.DecisionTrail} answers "what happened to this
 * one request". This answers "what happened", which is the question a compliance review actually
 * opens with — and until now had no endpoint at all, even though {@code audit.view} existed and
 * {@code JdbcAuditTrail} had been writing master-data edits, user imports, settings changes and
 * authorization denials all along.
 *
 * <p><strong>Read-only, and deliberately no write method.</strong> Entries reach the trail as a
 * side effect of the change that caused them, inside its transaction. A port that could append
 * independently would allow a record of something that did not happen. V12 makes the table
 * append-only in the database too, so this is belt and braces rather than the only guard.
 */
public interface AuditLogQueries {

    Page search(Filter filter);

    /**
     * @param action     exact match on the recorded action, e.g. {@code visitor_request.approve}
     * @param entityType exact match, e.g. {@code visitor_request}, {@code building}
     * @param actorId    who acted; null means anyone
     * @param from       inclusive lower bound on {@code created_at}
     * @param to         exclusive upper bound, so a day filter is [00:00, next 00:00)
     */
    record Filter(String action, String entityType, UUID actorId, Instant from, Instant to,
                  int page, int size) {}

    /**
     * @param actor    the display name of whoever acted, or null once the account is removed —
     *                 {@code user_id} is {@code ON DELETE SET NULL} and the entry outlives it
     * @param ipAddress present because "from where" is the second question an incident review asks
     */
    record Entry(long id, Instant at, String action, String entityType, String entityId,
                 UUID actorId, String actor, String beforeState, String afterState,
                 String ipAddress) {}

    record Page(List<Entry> content, long totalElements, int page, int size) {}

    /** The distinct values present, so a filter offers what exists rather than a free-text box. */
    record Vocabulary(List<String> actions, List<String> entityTypes) {}

    Vocabulary vocabulary();
}
