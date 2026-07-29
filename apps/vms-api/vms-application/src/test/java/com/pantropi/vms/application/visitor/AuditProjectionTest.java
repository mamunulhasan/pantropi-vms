package com.pantropi.vms.application.visitor;

import com.pantropi.vms.domain.visitor.RequestStatus;
import com.pantropi.vms.domain.visitor.TimeWindow;
import com.pantropi.vms.domain.visitor.Visitor;
import com.pantropi.vms.domain.visitor.VisitorRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US-07.4.3 · T-07.4.3.1 — the audit projection is an allow-list. Pure, no I/O.
 */
class AuditProjectionTest {

    private static final Instant WINDOW_FROM = Instant.parse("2030-06-01T09:00:00Z");
    private static final Instant WINDOW_TO = Instant.parse("2030-06-01T11:00:00Z");
    private static final Instant NOW = Instant.parse("2030-05-30T00:00:00Z");

    @Test
    @DisplayName("AC-2: the projection carries status, window and decision fields — and a count")
    void projectionCarriesTheDecisionFields() {
        VisitorRequest request = submitted();
        request.reject(UUID.randomUUID(), "Host is on leave", NOW);

        String json = AuditProjection.of(request);

        assertThat(json).contains("\"status\":\"rejected\"")
                .contains("\"scheduledFrom\":\"2030-06-01T09:00:00Z\"")
                .contains("\"decisionReason\":\"Host is on leave\"")
                .contains("\"visitorCount\":2");
    }

    @Test
    @DisplayName("AC-2: no visitor personal data appears, however the request is decided")
    void noVisitorPiiInAnyState() {
        for (RequestStatus decided : RequestStatus.values()) {
            String json = AuditProjection.of(inState(decided));

            assertThat(json).as("%s", decided)
                    .doesNotContain("Ada Lovelace").doesNotContain("Alan Turing")
                    .doesNotContain("ada@example.test").doesNotContain("alan@example.test")
                    .doesNotContain("880171").doesNotContain("Analytical");
        }
    }

    /**
     * T-07.4.3.1's central requirement, expressed as the thing it is really about.
     *
     * <p>The task asks for a test that "adds a synthetic PII field to the entity and asserts it is
     * absent from the projection". Adding a field to {@link Visitor} inside a test is not something
     * Java permits, so this asserts the property that makes such a field absent: <strong>no visitor
     * value reaches the projection at all</strong>. Every readable string on a real visitor —
     * whatever it happens to be called — is checked against the output.
     *
     * <p>That is stronger than naming today's four PII fields. A test listing {@code email} and
     * {@code phone} passes on the day someone adds {@code idDocumentRef}; this one fails.
     */
    @Test
    @DisplayName("T-07.4.3.1: no readable value of any visitor reaches the projection, whatever it "
            + "is called — so a newly added field cannot leak by default")
    void noVisitorFieldCanLeakByDefault() throws Exception {
        VisitorRequest request = submitted();
        request.approve(UUID.randomUUID(), "Cleared", NOW);
        String json = AuditProjection.of(request);

        Visitor visitor = request.visitors().get(0);
        int checked = 0;
        for (Method accessor : Visitor.class.getMethods()) {
            if (accessor.getParameterCount() != 0 || accessor.getDeclaringClass() == Object.class) {
                continue;
            }
            Object value = accessor.invoke(visitor);
            if (value == null) {
                continue;
            }
            String rendered = String.valueOf(value);
            if (rendered.length() < 3 || "PENDING".equals(rendered)) {
                continue;   // a status is a shared vocabulary, not a value belonging to a person
            }
            assertThat(json).as("Visitor.%s() leaked into the audit projection", accessor.getName())
                    .doesNotContain(rendered);
            checked++;
        }
        // Guard against the loop silently checking nothing, which would make this test vacuous.
        assertThat(checked).isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("the projection is stable and diffable — same shape, same key order, every time")
    void projectionIsStable() {
        // Two audit rows for the same request should differ only where the request differed, so a
        // reader comparing them sees the change rather than a reordering.
        String first = AuditProjection.of(submitted());
        String second = AuditProjection.of(submitted());

        assertThat(keysOf(first)).isEqualTo(keysOf(second));
        assertThat(keysOf(first)).containsExactly("status", "hostId", "scheduledFrom",
                "scheduledTo", "decidedBy", "decidedAt", "decisionReason", "visitorCount");
    }

    @Test
    @DisplayName("a reason containing quotes and newlines still yields valid JSON")
    void reasonIsEscaped() {
        VisitorRequest request = submitted();
        request.reject(UUID.randomUUID(), "Refused:\n  \"no host\"", NOW);

        String json = AuditProjection.of(request);

        assertThat(json).contains("\\n").contains("\\\"no host\\\"")
                .doesNotContain("\n");
    }

    @Test
    @DisplayName("the before-state is the status alone, because that is all the caller still knows")
    void beforeStateIsTheStatus() {
        assertThat(AuditProjection.before(RequestStatus.SUBMITTED))
                .isEqualTo("{\"status\":\"submitted\"}");
    }

    @Test
    @DisplayName("absent values are the JSON literal null, not the string")
    void absentValuesAreLiteralNull() {
        String json = AuditProjection.of(submitted());

        assertThat(json).contains("\"decidedBy\":null").contains("\"decisionReason\":null");
    }

    // ---- helpers ----

    private static List<String> keysOf(String json) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"([a-zA-Z]+)\":")
                .matcher(json);
        List<String> keys = new java.util.ArrayList<>();
        while (m.find()) {
            keys.add(m.group(1));
        }
        return keys;
    }

    private static VisitorRequest submitted() {
        return VisitorRequest.submit(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new TimeWindow(WINDOW_FROM, WINDOW_TO), "Quarterly review",
                List.of(Visitor.named("Ada Lovelace", "ada@example.test", "+8801712345678",
                                "Analytical Ltd", null),
                        Visitor.named("Alan Turing", "alan@example.test", null, null, null)));
    }

    private static VisitorRequest inState(RequestStatus status) {
        VisitorRequest request = submitted();
        switch (status) {
            case APPROVED -> request.approve(UUID.randomUUID(), "Cleared with security", NOW);
            case REJECTED -> request.reject(UUID.randomUUID(), "Host on leave", NOW);
            case CANCELLED -> request.cancel(NOW);
            case SUBMITTED -> { /* already there */ }
        }
        return request;
    }
}
