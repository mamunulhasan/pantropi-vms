package com.pantropi.vms.domain.masterdata;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link Floor} (US-04.2.1) — pure, no I/O. */
class FloorTest {

    private static final UUID BUILDING = UUID.randomUUID();

    @Test
    @DisplayName("a floor inherits the shared code and name rules")
    void sharedRulesApply() {
        Floor floor = Floor.draft(BUILDING, " l01 ", "  Level 1  ", 1);

        assertThat(floor.code()).isEqualTo("L01");
        assertThat(floor.name()).isEqualTo("Level 1");
        assertThat(floor.active()).isTrue();
    }

    @Test
    @DisplayName("a floor without a building is refused — the code alone identifies nothing")
    void buildingIsRequired() {
        assertThatThrownBy(() -> Floor.draft(null, "L01", "Level 1", 1))
                .isInstanceOf(MasterDataText.InvalidField.class)
                .satisfies(e -> assertThat(((MasterDataText.InvalidField) e).field)
                        .isEqualTo("buildingId"));
    }

    @Test
    @DisplayName("level number is optional — mezzanines and plant rooms are not numbered")
    void levelNumberIsOptional() {
        assertThat(Floor.draft(BUILDING, "MEZZ", "Mezzanine", null).levelNo()).isNull();
    }

    @ParameterizedTest
    @ValueSource(ints = {-20, -1, 0, 1, 200})
    @DisplayName("plausible storeys are accepted, including basements and ground")
    void plausibleLevelsAccepted(int level) {
        assertThatCode(() -> Floor.draft(BUILDING, "L", "Floor", level))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(ints = {-21, 201, 2026})
    @DisplayName("an implausible level is refused — a pasted year must not sort a floor absurdly")
    void implausibleLevelRefused(int level) {
        assertThatThrownBy(() -> Floor.draft(BUILDING, "L", "Floor", level))
                .isInstanceOf(MasterDataText.InvalidField.class)
                .satisfies(e -> assertThat(((MasterDataText.InvalidField) e).field)
                        .isEqualTo("levelNo"));
    }

    @Test
    @DisplayName("the same code under a different building is a different floor, not a clash")
    void codesAreScopedToTheBuilding() {
        // Every tower has an L01. Uniqueness is (building_id, code), enforced by the database;
        // the domain deliberately imposes no global rule the schema does not ask for.
        UUID other = UUID.randomUUID();
        assertThat(Floor.draft(BUILDING, "L01", "Level 1", 1).code())
                .isEqualTo(Floor.draft(other, "L01", "Level 1", 1).code());
        assertThat(Floor.draft(BUILDING, "L01", "Level 1", 1).buildingId())
                .isNotEqualTo(Floor.draft(other, "L01", "Level 1", 1).buildingId());
    }
}
