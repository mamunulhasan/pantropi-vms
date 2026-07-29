package com.pantropi.vms.application.visitor;

import com.pantropi.vms.application.shared.PageRequest;
import com.pantropi.vms.application.visitor.port.ApprovalQueueStore;
import com.pantropi.vms.application.visitor.usecase.PendingApprovals;
import com.pantropi.vms.domain.visitor.RequestStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * US-07.3.2 · T-07.3.2.1/2 — what a query parameter must survive before it reaches SQL.
 */
class PendingApprovalsTest {

    private static final Instant FROM = Instant.parse("2030-06-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2030-07-01T00:00:00Z");

    private final RecordingStore store = new RecordingStore();
    private final PendingApprovals useCase = new PendingApprovals(store);

    @Test
    @DisplayName("the queue defaults to submitted, so loading it never shows a decided request")
    void defaultsToPending() {
        useCase.list(0, 20);
        assertThat(store.last.status()).isEqualTo(RequestStatus.SUBMITTED);

        useCase.list(null, null, null, null, null, 0, 20);
        assertThat(store.last.status()).isEqualTo(RequestStatus.SUBMITTED);
    }

    @ParameterizedTest
    @EnumSource(RequestStatus.class)
    @DisplayName("AC-1: an explicit status is honoured, by database value and by enum name")
    void explicitStatusIsHonoured(RequestStatus status) {
        useCase.list(status.dbValue(), null, null, null, null, 0, 20);
        assertThat(store.last.status()).isEqualTo(status);

        useCase.list(status.name().toLowerCase(), null, null, null, null, 0, 20);
        assertThat(store.last.status()).isEqualTo(status);
    }

    @ParameterizedTest
    @ValueSource(strings = {"aproved", "pending", "' OR 1=1 --", "submitted approved"})
    @DisplayName("T-07.3.2.2: an unknown status is 400 material, not a filter that does nothing")
    void unknownStatusIsRefused(String bogus) {
        assertThatThrownBy(() -> useCase.list(bogus, null, null, null, null, 0, 20))
                .isInstanceOf(PendingApprovals.UnknownStatus.class);
        assertThat(store.last).as("nothing was queried").isNull();
    }

    @Test
    @DisplayName("T-07.3.2.2: a range that starts after it ends is refused, not silently swapped")
    void invertedRangeIsRefused() {
        // Swapping it would answer a different question from the one asked, and the caller would
        // never find out their client is sending it backwards.
        assertThatThrownBy(() -> useCase.list(null, null, TO, FROM, null, 0, 20))
                .isInstanceOf(PendingApprovals.InvalidDateRange.class);
        assertThat(store.last).isNull();
    }

    @Test
    @DisplayName("an open-ended range is legal at either end, and equal bounds are legal")
    void openEndedRangesAreLegal() {
        assertThatCode(() -> useCase.list(null, null, FROM, null, null, 0, 20))
                .doesNotThrowAnyException();
        assertThatCode(() -> useCase.list(null, null, null, TO, null, 0, 20))
                .doesNotThrowAnyException();
        assertThatCode(() -> useCase.list(null, null, FROM, FROM, null, 0, 20))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("AC-1: every filter reaches the store, and they combine")
    void filtersCombine() {
        UUID tenant = UUID.randomUUID();

        useCase.list("approved", tenant, FROM, TO, "  Lovelace  ", 2, 10);

        ApprovalQueueStore.Filter f = store.last;
        assertThat(f.status()).isEqualTo(RequestStatus.APPROVED);
        assertThat(f.tenantId()).isEqualTo(tenant);
        assertThat(f.visitFrom()).isEqualTo(FROM);
        assertThat(f.visitTo()).isEqualTo(TO);
        assertThat(f.nameLike()).isEqualTo("Lovelace");     // trimmed
        assertThat(f.page().page()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t"})
    @DisplayName("a blank search is no search rather than a match-everything pattern")
    void blankSearchIsNoSearch(String blank) {
        useCase.list(null, null, null, null, blank, 0, 20);
        assertThat(store.last.nameLike()).isNull();
    }

    @Test
    @DisplayName("AC-4: an over-long search term is refused before it becomes the query's cost")
    void searchIsBounded() {
        assertThatThrownBy(() ->
                useCase.list(null, null, null, null, "x".repeat(101), 0, 20))
                .isInstanceOf(PendingApprovals.SearchTooLong.class);

        assertThatCode(() -> useCase.list(null, null, null, null, "x".repeat(100), 0, 20))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the page bound still applies with filters in play")
    void pageIsStillBounded() {
        useCase.list("approved", null, null, null, "Ada", 0, 10_000);
        assertThat(store.last.page().size()).isEqualTo(PageRequest.MAX_SIZE);
    }

    @Test
    @DisplayName("AC-5: the filter carries no way to express a scope — that is the store's alone")
    void filterCannotWidenScope() {
        // A tenantId here selects within whatever the policy already allows. Nothing in this record
        // can turn a restricted caller into an unrestricted one, which is the property AC-5 needs.
        assertThat(ApprovalQueueStore.Filter.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("status", "tenantId", "visitFrom", "visitTo", "nameLike", "page")
                .doesNotContain("scope", "unrestricted", "allTenants");
    }

    private static final class RecordingStore implements ApprovalQueueStore {
        Filter last;

        public Page pending(Filter filter) {
            last = filter;
            return new Page(List.of(), 0, filter.page().page(), filter.page().size());
        }
    }
}
