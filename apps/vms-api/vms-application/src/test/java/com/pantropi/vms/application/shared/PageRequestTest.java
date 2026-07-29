package com.pantropi.vms.application.shared;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * US-07.3.1 AC-5 — the bound holds at construction, so an unbounded query cannot be built.
 */
class PageRequestTest {

    @ParameterizedTest
    @ValueSource(ints = {101, 1_000, 10_000, Integer.MAX_VALUE})
    @DisplayName("AC-5: an oversized page is clamped to the maximum, not refused")
    void oversizedIsClamped(int asked) {
        // The AC names 10,000 specifically; the others are here because the interesting failure is
        // an off-by-one at the boundary or an overflow at the extreme, not the round number.
        assertThat(new PageRequest(0, asked).size()).isEqualTo(PageRequest.MAX_SIZE);
    }

    @Test
    @DisplayName("a size at the maximum is honoured exactly")
    void maximumIsAllowed() {
        assertThat(new PageRequest(0, PageRequest.MAX_SIZE).size())
                .isEqualTo(PageRequest.MAX_SIZE);
        assertThat(new PageRequest(0, PageRequest.MAX_SIZE - 1).size())
                .isEqualTo(PageRequest.MAX_SIZE - 1);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    @DisplayName("a nonsensical size falls back to the default rather than returning nothing")
    void nonsenseSizeBecomesDefault(int asked) {
        // Zero or negative would otherwise mean LIMIT 0 — an empty page that looks like an empty
        // queue, which is the wrong thing to show an approver with work waiting.
        assertThat(new PageRequest(0, asked).size()).isEqualTo(PageRequest.DEFAULT_SIZE);
    }

    @Test
    @DisplayName("a negative page is the first page")
    void negativePageIsTheFirst() {
        assertThat(new PageRequest(-5, 20).page()).isZero();
        assertThat(new PageRequest(-5, 20).offset()).isZero();
    }

    @Test
    @DisplayName("the offset is computed in long arithmetic, so a far page cannot overflow")
    void offsetDoesNotOverflow() {
        // page * size as ints overflows past ~21 million pages and turns into a negative OFFSET,
        // which PostgreSQL rejects outright — a 500 from a deep page number.
        PageRequest far = new PageRequest(Integer.MAX_VALUE, 100);

        assertThat(far.offset()).isEqualTo(214_748_364_700L).isPositive();
    }
}
