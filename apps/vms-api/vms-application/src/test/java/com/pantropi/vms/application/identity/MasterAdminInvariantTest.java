package com.pantropi.vms.application.identity;

import com.pantropi.vms.application.identity.port.AdminDirectory;
import com.pantropi.vms.application.identity.usecase.MasterAdminPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The FR-ADM-01 (SRS B1) invariant, tested as an invariant (US-04.4.1, T-04.4.1.3).
 *
 * <p>The two acceptance criteria read as separate counting rules — "not the last Master Admin",
 * "not the sole admin's reception". Implemented that way they do not compose, and this suite exists
 * because the sequence in {@link #retiringTheReceptionFirstDoesNotOpenADoor()} defeats them while
 * satisfying both.
 *
 * <p>What is actually protected: <strong>at least one active Master Admin stationed at an active
 * central reception</strong>.
 */
class MasterAdminInvariantTest {

    private static final UUID ADMIN_A = UUID.randomUUID();
    private static final UUID ADMIN_B = UUID.randomUUID();
    private static final UUID CENTRAL = UUID.randomUUID();
    private static final UUID OTHER = UUID.randomUUID();

    private final FakeDirectory directory = new FakeDirectory();
    private final MasterAdminPolicy policy = new MasterAdminPolicy(directory);

    @Test
    @DisplayName("the sole administrator cannot be deactivated")
    void soleAdministratorProtected() {
        directory.holders = List.of(holder(ADMIN_A, CENTRAL, true));

        assertThatThrownBy(() -> policy.assertUserDeactivatable(ADMIN_A))
                .isInstanceOf(MasterAdminPolicy.LastMasterAdmin.class);
    }

    @Test
    @DisplayName("the sole administrator's central reception cannot be deactivated")
    void soleAdministratorsStationProtected() {
        directory.holders = List.of(holder(ADMIN_A, CENTRAL, true));

        assertThatThrownBy(() -> policy.assertReceptionDeactivatable(CENTRAL))
                .isInstanceOf(MasterAdminPolicy.WouldStrandMasterAdmin.class);
    }

    @Test
    @DisplayName("with two administrators, either one may go")
    void secondAdministratorMakesTheFirstReplaceable() {
        directory.holders = List.of(holder(ADMIN_A, CENTRAL, true), holder(ADMIN_B, CENTRAL, true));

        assertThatCode(() -> policy.assertUserDeactivatable(ADMIN_A)).doesNotThrowAnyException();
        assertThatCode(() -> policy.assertUserDeactivatable(ADMIN_B)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the composition hole: retiring the reception first must not open a door")
    void retiringTheReceptionFirstDoesNotOpenADoor() {
        // Both administrators are stationed at the same central reception, which is the realistic
        // arrangement — FR-ADM-01 places the authority at central reception, singular.
        directory.holders = List.of(holder(ADMIN_A, CENTRAL, true), holder(ADMIN_B, CENTRAL, true));

        // Step one under a counting rule: two administrators exist, so retiring their reception
        // "does not remove the last one" and would be allowed. Under the invariant it is refused,
        // because afterwards neither has a station.
        assertThatThrownBy(() -> policy.assertReceptionDeactivatable(CENTRAL))
                .isInstanceOf(MasterAdminPolicy.WouldStrandMasterAdmin.class);
    }

    @Test
    @DisplayName("retiring a reception only some administrators use is allowed")
    void retiringOneOfTwoStationsIsFine() {
        directory.holders = List.of(holder(ADMIN_A, CENTRAL, true), holder(ADMIN_B, OTHER, true));

        assertThatCode(() -> policy.assertReceptionDeactivatable(OTHER))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an administrator whose station is already invalid does not count as cover")
    void invalidStationIsNotCover() {
        // B exists and is active, but is stationed nowhere valid — a reception that was retired, or
        // one that is not central. B cannot exercise FR-ADM-01 authority, so B is not a reason to
        // let A go.
        directory.holders = List.of(holder(ADMIN_A, CENTRAL, true), holder(ADMIN_B, OTHER, false));

        assertThatThrownBy(() -> policy.assertUserDeactivatable(ADMIN_A))
                .isInstanceOf(MasterAdminPolicy.LastMasterAdmin.class);
    }

    @Test
    @DisplayName("when the invariant is already broken, the guards get out of the way")
    void alreadyBrokenDoesNotLockTheDoor() {
        // Nobody holds a valid station. Refusing every change here would mean an installation that
        // has lost its central reception could no longer alter any administrator — locking the door
        // on the people arriving to fix it.
        directory.holders = List.of(holder(ADMIN_A, OTHER, false));

        assertThatCode(() -> policy.assertUserDeactivatable(ADMIN_A)).doesNotThrowAnyException();
        assertThatCode(() -> policy.assertReceptionDeactivatable(OTHER)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("with no administrators at all there is nothing to protect")
    void noAdministrators() {
        directory.holders = List.of();

        assertThatCode(() -> policy.assertUserDeactivatable(ADMIN_A)).doesNotThrowAnyException();
        assertThatCode(() -> policy.assertReceptionDeactivatable(CENTRAL))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("both guards read through the same locking call, so they contend on one set")
    void bothGuardsUseTheLockingRead() {
        // If one of them read unlocked, the pair could interleave and each would be reading a state
        // the other is about to change.
        directory.holders = List.of(holder(ADMIN_A, CENTRAL, true), holder(ADMIN_B, OTHER, true));

        policy.assertUserDeactivatable(ADMIN_B);
        policy.assertReceptionDeactivatable(OTHER);

        org.assertj.core.api.Assertions.assertThat(directory.lockingReads).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(directory.unlockedCountReads).isZero();
    }

    private static AdminDirectory.RoleHolder holder(UUID user, UUID reception, boolean valid) {
        return new AdminDirectory.RoleHolder(user, reception, valid);
    }

    private static final class FakeDirectory implements AdminDirectory {
        List<AdminDirectory.RoleHolder> holders = new ArrayList<>();
        int lockingReads;
        int unlockedCountReads;

        public List<RoleHolder> lockActiveHoldersOf(String roleCode) {
            lockingReads++;
            return holders;
        }
        public long countActiveUsersWithRole(String roleCode) {
            unlockedCountReads++;
            return holders.size();
        }
        public long countUsers() { return holders.size(); }
        public Optional<UUID> roleIdByCode(String roleCode) { return Optional.empty(); }
        public boolean receptionIsCentral(UUID receptionId) { return CENTRAL.equals(receptionId); }
        public void insertUser(UUID id, String u, String h, UUID r, UUID rec, boolean a) {}
    }
}
