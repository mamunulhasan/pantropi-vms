package com.pantropi.vms.application.identity;

import com.pantropi.vms.application.identity.port.ActivationStore;
import com.pantropi.vms.application.identity.port.AuditTrail;
import com.pantropi.vms.application.identity.port.PasswordHasher;
import com.pantropi.vms.application.identity.port.UserAdministrationStore;
import com.pantropi.vms.application.identity.port.UserAdministrationStore.NewUser;
import com.pantropi.vms.application.identity.port.UserAdministrationStore.Page;
import com.pantropi.vms.application.identity.port.UserAdministrationStore.UserFilter;
import com.pantropi.vms.application.identity.port.UserAdministrationStore.UserView;
import com.pantropi.vms.application.identity.usecase.AccountActivation;
import com.pantropi.vms.application.identity.usecase.UserImport;
import com.pantropi.vms.application.shared.port.TransactionRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests for {@link UserImport} (US-02.2.2) — pure, fake ports. */
class UserImportTest {

    private final UUID rec = UUID.randomUUID();
    private final FakeStore store = new FakeStore(rec);
    private final FakeAudit audit = new FakeAudit();
    private final FakeActivationStore actStore = new FakeActivationStore();
    private final AccountActivation activation =
            new AccountActivation(actStore, passthrough(), Clock.systemUTC(), Duration.ofHours(72));
    private final UserImport imp = new UserImport(store, audit, direct(), activation);
    private final UUID actor = UUID.randomUUID();

    private String csv(String... rows) {
        StringBuilder sb = new StringBuilder("username,email,fullName,roleCode,receptionId\n");
        for (String r : rows) sb.append(r).append("\n");
        return sb.toString();
    }

    @Test
    @DisplayName("AC-4: a file with a password column is rejected outright")
    void passwordColumnRejected() {
        String csv = "username,password,fullName,roleCode,receptionId\na,x,A,FLOOR_RECEPTIONIST," + rec;
        assertThatThrownBy(() -> imp.preview(csv))
                .isInstanceOf(UserImport.PasswordColumnRejected.class);
        assertThat(store.inserted).isZero(); // AC-4: nothing written, dry run wrote nothing
    }

    @Test
    @DisplayName("AC-1: preview classifies creatable, conflict and rejected rows without writing")
    void previewClassifies() {
        store.existingUsernames.add("taken");
        var p = imp.preview(csv(
                "alice,a@ex.com,Alice,FLOOR_RECEPTIONIST," + rec,          // creatable
                "taken,t@ex.com,Taken,FLOOR_RECEPTIONIST," + rec,         // conflict
                "bob,b@ex.com,Bob,NO_SUCH_ROLE," + rec,                    // rejected: role
                "carol,c@ex.com,Carol,FLOOR_RECEPTIONIST," + UUID.randomUUID())); // rejected: reception
        assertThat(p.creatable()).isEqualTo(1);
        assertThat(p.conflicts()).isEqualTo(1);
        assertThat(p.rejected()).isEqualTo(2);
        assertThat(store.inserted).isZero();
    }

    @Test
    @DisplayName("AC-3: conflicts block execution until confirmation, then are skipped")
    void conflictsRequireConfirmation() {
        store.existingUsernames.add("taken");
        String csv = csv("alice,a@ex.com,Alice,FLOOR_RECEPTIONIST," + rec,
                "taken,t@ex.com,Taken,FLOOR_RECEPTIONIST," + rec);
        assertThatThrownBy(() -> imp.execute(actor, csv, "f.csv", false))
                .isInstanceOf(UserImport.ConfirmationRequired.class);
        assertThat(store.inserted).isZero();

        var result = imp.execute(actor, csv, "f.csv", true);
        assertThat(result.created()).hasSize(1);   // only alice
        assertThat(result.skipped()).isEqualTo(1);  // taken
        assertThat(store.inserted).isEqualTo(1);
        assertThat(audit.actions).contains("user.imported", "user.import_completed");
    }

    @Test
    @DisplayName("AC-2: each created user gets an unusable placeholder and an activation token")
    void createdUsersActivateOutOfBand() {
        var result = imp.execute(actor, csv("alice,a@ex.com,Alice,FLOOR_RECEPTIONIST," + rec),
                "f.csv", false);
        assertThat(result.created()).hasSize(1);
        assertThat(result.created().get(0).activationToken()).contains(".");
        assertThat(store.lastPlaceholder).isEqualTo(
                com.pantropi.vms.application.identity.usecase.UserAdministration.ACTIVATION_PENDING);
        assertThat(actStore.tokens).hasSize(1);
    }

    // ---- fakes ----
    private static TransactionRunner direct() {
        return new TransactionRunner() {
            public <T> T call(Supplier<T> work) { return work.get(); }
        };
    }

    private static PasswordHasher passthrough() {
        return new PasswordHasher() {
            public String hash(char[] p) { return "hash"; }
            public boolean matches(char[] p, String s) { return false; }
        };
    }

    private static final class FakeStore implements UserAdministrationStore {
        final UUID activeReception;
        final List<String> existingUsernames = new ArrayList<>();
        int inserted; String lastPlaceholder;
        FakeStore(UUID activeReception) { this.activeReception = activeReception; }
        public boolean usernameExists(String u) { return existingUsernames.contains(u); }
        public boolean emailExists(String e) { return false; }
        public Optional<UUID> roleIdByCode(String r) {
            return r.equals("FLOOR_RECEPTIONIST") ? Optional.of(UUID.randomUUID()) : Optional.empty();
        }
        public boolean receptionActive(UUID r) { return r.equals(activeReception); }
        public boolean tenantActive(UUID t) { return true; }
        public UUID insert(NewUser u, String ph) { inserted++; lastPlaceholder = ph; return UUID.randomUUID(); }
        public Optional<UserView> findById(UUID id) { return Optional.empty(); }
        public void updateAssignment(UUID id, UUID r, UUID rec, UUID t) {}
        public void setActive(UUID id, boolean a) {}
        public Page list(UserFilter f) { return new Page(List.of(), 0, 0, 0); }
    }

    private static final class FakeActivationStore implements ActivationStore {
        final List<String> tokens = new ArrayList<>();
        public void createToken(UUID userId, String hash, java.time.Instant exp) { tokens.add(hash); }
        public Optional<UUID> redeem(String hash, java.time.Instant now) { return Optional.empty(); }
        public void setPassword(UUID userId, String hash) {}
    }

    private static final class FakeAudit implements AuditTrail {
        final List<String> actions = new ArrayList<>();
        public void record(UUID a, String action, String et, String eid, String d) { actions.add(action); }
        public void recordChange(UUID a, String action, String et, String eid, String b, String af) { actions.add(action); }
        public void recordSecurityDenial(UUID a, String action, String p, String r,
                                         String m, String o, String ip) { actions.add(action); }
    }
}
