package com.pantropi.vms.application.visitor;

import com.pantropi.vms.application.shared.JsonText;
import com.pantropi.vms.domain.visitor.RequestStatus;
import com.pantropi.vms.domain.visitor.VisitorRequest;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The {@code before_state}/{@code after_state} projections of a visitor request (US-07.4.3,
 * T-07.4.3.1) — FR-VMS-02 (SRS B1), FR-AUD-01 (TDD §4.6).
 *
 * <h2>An allow-list, and the difference matters</h2>
 * T-07.4.3.1 asks for an allow-list rather than a deny-list, and the reason is what happens when
 * somebody adds a column. A deny-list — "serialise the aggregate, minus these fields" — leaks every
 * field nobody thought to exclude, and the fields nobody thinks about are exactly the ones added
 * last. Here nothing appears in the payload unless it is named below, so a new
 * {@code id_document_ref} on {@link com.pantropi.vms.domain.visitor.Visitor} is absent by default
 * and stays absent until someone deliberately writes it in.
 *
 * <p>{@link com.pantropi.vms.domain.visitor.Visitor} is therefore never projected at all. A
 * decision is about the request; the visitors are a <em>count</em>. The audit trail records that
 * four people were approved, not who they were — and the trail is read by administrators
 * investigating months later, which is the last place a name and phone number should be sitting.
 *
 * <h2>Stable and diffable</h2>
 * Keys are written in insertion order and every projection of the same shape emits the same keys in
 * the same sequence, so a before/after pair can be read down the page and a diff of two audit rows
 * shows only what actually changed.
 *
 * <p>Values go through {@link JsonText}, because one of them — the decision reason — is text a
 * person typed (US-07.4.2 AC-6).
 *
 * <p>No framework imports — this is application code (US-01.2.2).
 */
public final class AuditProjection {

    private final Map<String, String> fields = new LinkedHashMap<>();

    private AuditProjection() {
    }

    /**
     * The state of a request as the audit trail records it.
     *
     * <p>Status, window, decision and counts. This method <em>is</em> the allow-list: everything the
     * audit trail can say about a request is written here, in one place a reviewer can read.
     */
    public static String of(VisitorRequest request) {
        return new AuditProjection()
                .put("status", request.status().dbValue())
                .put("hostId", request.hostId())
                .put("scheduledFrom", request.window() == null ? null : request.window().from())
                .put("scheduledTo", request.window() == null ? null : request.window().to())
                .put("decidedBy", request.approvedBy())
                .put("decidedAt", request.decidedAt())
                .put("decisionReason", request.decisionReason())
                .putRaw("visitorCount", String.valueOf(request.visitors().size()))
                .json();
    }

    /**
     * The prior state, from the status alone.
     *
     * <p>The aggregate has already moved by the time a use case writes its audit entry, so the only
     * honest "before" it can produce is what it captured beforehand. Recording a fuller before-state
     * would mean cloning the aggregate on every decision to serialise a copy nobody reads.
     */
    public static String before(RequestStatus status) {
        return new AuditProjection().put("status", status.dbValue()).json();
    }

    public static AuditProjection start() {
        return new AuditProjection();
    }

    public AuditProjection put(String key, String value) {
        fields.put(key, JsonText.quotedOrNull(value));
        return this;
    }

    public AuditProjection put(String key, UUID value) {
        fields.put(key, value == null ? "null" : JsonText.quoted(value.toString()));
        return this;
    }

    public AuditProjection put(String key, Instant value) {
        fields.put(key, value == null ? "null" : JsonText.quoted(value.toString()));
        return this;
    }

    public AuditProjection put(String key, boolean value) {
        fields.put(key, String.valueOf(value));
        return this;
    }

    /** For values that are already JSON — a number, or a pre-rendered array of ids. */
    public AuditProjection putRaw(String key, String json) {
        fields.put(key, json);
        return this;
    }

    public String json() {
        return fields.entrySet().stream()
                .map(e -> JsonText.quoted(e.getKey()) + ":" + e.getValue())
                .collect(Collectors.joining(",", "{", "}"));
    }
}
