package com.pantropi.vms.domain.masterdata;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link VisitorType} (US-04.5.1).
 *
 * <p>Deliberately short. The code and name rules are {@link MasterDataText}'s and are covered once
 * in {@link BuildingTest}; repeating them per entity would be five copies of the same assertions
 * that all pass or all fail together. What is asserted here is what is specific to this record.
 */
class VisitorTypeTest {

    @Test
    @DisplayName("the shared code and name rules apply here too")
    void sharedRulesApply() {
        VisitorType type = VisitorType.draft(" guest ", "  Guest  ", null);

        assertThat(type.code()).isEqualTo("GUEST");
        assertThat(type.name()).isEqualTo("Guest");
        assertThat(type.active()).isTrue();
    }

    @Test
    @DisplayName("description is optional, and a blank one becomes null rather than empty")
    void descriptionIsOptional() {
        assertThat(VisitorType.draft("GUEST", "Guest", null).description()).isNull();
        assertThat(VisitorType.draft("GUEST", "Guest", "  ").description()).isNull();
        assertThat(VisitorType.draft("GUEST", "Guest", " General visitor ").description())
                .isEqualTo("General visitor");
    }

    @Test
    @DisplayName("an over-long description is refused rather than truncated by the database")
    void overLongDescriptionRefused() {
        assertThatThrownBy(() -> VisitorType.draft("GUEST", "Guest", "D".repeat(501)))
                .isInstanceOf(MasterDataText.InvalidField.class)
                .satisfies(e -> assertThat(((MasterDataText.InvalidField) e).field)
                        .isEqualTo("description"));
    }
}
