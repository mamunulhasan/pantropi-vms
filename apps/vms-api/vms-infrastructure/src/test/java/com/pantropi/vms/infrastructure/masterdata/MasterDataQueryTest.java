package com.pantropi.vms.infrastructure.masterdata;

import com.pantropi.vms.application.masterdata.port.MasterDataStore.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for {@link MasterDataQuery} — the shared filter and paging assembly (US-04.5.1). */
class MasterDataQueryTest {

    @Test
    @DisplayName("an unbounded page size is capped rather than honoured")
    void pageSizeIsCapped() {
        // A listing endpoint with no ceiling is a way to ask the server for the whole table.
        assertThat(MasterDataQuery.of(new Query(null, null, null, 0, 1_000_000, "code"), null)
                .size()).isEqualTo(200);
    }

    @Test
    @DisplayName("a nonsensical page or size is corrected rather than passed to SQL")
    void nonsensicalPagingIsCorrected() {
        MasterDataQuery q = MasterDataQuery.of(new Query(null, null, null, -5, 0, "code"), null);

        assertThat(q.page()).isZero();
        assertThat(q.size()).isEqualTo(1);   // a negative OFFSET or LIMIT is a SQL error
    }

    @Test
    @DisplayName("no filters produce a where clause that matches everything")
    void noFiltersMatchEverything() {
        MasterDataQuery q = MasterDataQuery.of(new Query(null, null, null, 0, 20, "code"), null);

        assertThat(q.where()).isEqualTo(" WHERE 1=1");
        assertThat(q.countArgs()).isEmpty();
    }

    @Test
    @DisplayName("filters contribute placeholders and arguments in the same order")
    void argumentsMatchPlaceholderOrder() {
        UUID parent = UUID.randomUUID();
        MasterDataQuery q = MasterDataQuery.of(
                new Query(parent, "west", true, 2, 10, "code"), "building_id");

        // The count of '?' must equal the count of arguments, or every row shifts by one.
        assertThat(q.where().chars().filter(c -> c == '?').count()).isEqualTo(4);
        assertThat(q.countArgs()).containsExactly(parent, true, "%west%", "%west%");
        // Page args append LIMIT then OFFSET, in that order.
        assertThat(q.pageArgs()).containsExactly(parent, true, "%west%", "%west%", 10, 20);
    }

    @Test
    @DisplayName("a parent id is ignored for an entity that has no parent column")
    void parentIsIgnoredWhenNotApplicable() {
        // Passing null as the column says "this entity has no parent", so a stray parentId in the
        // query cannot silently filter on a column that does not exist.
        MasterDataQuery q = MasterDataQuery.of(
                new Query(UUID.randomUUID(), null, null, 0, 20, "code"), null);

        assertThat(q.where()).doesNotContain("building_id");
        assertThat(q.countArgs()).isEmpty();
    }

    @Test
    @DisplayName("a blank search is not a filter")
    void blankSearchIsIgnored() {
        assertThat(MasterDataQuery.of(new Query(null, "   ", null, 0, 20, "code"), null)
                .countArgs()).isEmpty();
    }

    @Test
    @DisplayName("a search term is trimmed and wrapped, never interpolated")
    void searchIsBound() {
        MasterDataQuery q = MasterDataQuery.of(
                new Query(null, "  o'brien  ", null, 0, 20, "code"), null);

        // The quote survives untouched in the argument because it never reaches the SQL text.
        assertThat(q.countArgs()).containsExactly("%o'brien%", "%o'brien%");
        assertThat(q.where()).doesNotContain("brien");
    }
}
