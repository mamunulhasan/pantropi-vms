package com.pantropi.vms.application.identity.usecase;

import com.pantropi.vms.application.identity.port.AuditTrail;
import com.pantropi.vms.application.identity.port.SessionStore;
import com.pantropi.vms.application.identity.port.UserAdministrationStore;
import com.pantropi.vms.application.identity.port.UserAdministrationStore.NewUser;
import com.pantropi.vms.application.identity.port.UserAdministrationStore.Page;
import com.pantropi.vms.application.identity.port.UserAdministrationStore.UserFilter;
import com.pantropi.vms.application.identity.port.UserAdministrationStore.UserView;
import com.pantropi.vms.application.shared.port.TransactionRunner;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Create, update, deactivate and reactivate user accounts (US-02.2.1) — FR-ADM-02 (SRS B1),
 * supporting NFR-SEC-01 (SRS B1). Pure orchestration over ports; no framework.
 *
 * <p>Never handles a password: provisioning writes an unusable placeholder hash and activation is
 * out of band (AC-1). Deactivation revokes the user's live sessions through {@link SessionStore}
 * — the hook left ready by US-02.1.2 (AC-3). Every mutation writes an audit event with the
 * password hash excluded (AC-2).
 */
public final class UserAdministration {

    /** Non-PBKDF2 sentinel — {@code Pbkdf2PasswordHasher.matches} can never match it (AC-1). */
    public static final String ACTIVATION_PENDING = "!activation-pending";

    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9._-]{3,60}$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final UserAdministrationStore store;
    private final SessionStore sessions;
    private final AuditTrail audit;
    private final MasterAdminPolicy masterAdmins;
    private final TransactionRunner transactions;

    public UserAdministration(UserAdministrationStore store, SessionStore sessions, AuditTrail audit,
                              MasterAdminPolicy masterAdmins, TransactionRunner transactions) {
        this.store = store;
        this.sessions = sessions;
        this.audit = audit;
        this.masterAdmins = masterAdmins;
        this.transactions = transactions;
    }

    public UUID create(UUID actorId, NewUser u) {
        validateFormat(u);
        UUID roleId = resolveRole(u.roleCode());
        validateAssignment(u.receptionId(), u.tenantId());
        if (u.username() != null && store.usernameExists(u.username())) {
            throw new DuplicateField("username");
        }
        if (u.email() != null && store.emailExists(u.email())) {
            throw new DuplicateField("email");
        }
        UUID id = store.insert(u, ACTIVATION_PENDING);
        audit.recordChange(actorId, "user.created", "user", id.toString(), null, viewJson(
                new UserView(id, u.username(), u.email(), u.fullName(), u.roleCode(),
                        u.receptionId(), u.tenantId(), true)));
        return id;
    }

    public void updateAssignment(UUID actorId, UUID userId, String roleCode,
                                 UUID receptionId, UUID tenantId) {
        UserView before = store.findById(userId).orElseThrow(() -> new UserNotFound(userId));
        UUID roleId = resolveRole(roleCode);
        validateAssignment(receptionId, tenantId);
        store.updateAssignment(userId, roleId, receptionId, tenantId);
        UserView after = new UserView(before.id(), before.username(), before.email(),
                before.fullName(), roleCode, receptionId, tenantId, before.active());
        audit.recordChange(actorId, "user.updated", "user", userId.toString(),
                viewJson(before), viewJson(after));
    }

    /**
     * Deactivate a user, refusing to retire the last holder of the FR-ADM-01 (SRS B1) authority.
     *
     * <p>The guard runs inside the transaction that performs the write, and takes a row lock on the
     * active Master Admins — the same lock the reception-side guard takes (US-04.4.1 AC-6). Without
     * that, deactivating the second-to-last administrator and retiring the last one's reception can
     * run concurrently, each reading a state the other is about to invalidate, and both succeed.
     */
    public void deactivate(UUID actorId, UUID userId) {
        transactions.run(() -> {
            UserView before = store.findById(userId).orElseThrow(() -> new UserNotFound(userId));
            masterAdmins.assertUserDeactivatable(userId);

            store.setActive(userId, false);
            int revoked = sessions.revokeAllForUser(userId, "user_deactivated"); // AC-3
            audit.recordChange(actorId, "user.deactivated", "user", userId.toString(),
                    viewJson(before), viewJson(withActive(before, false)));
            audit.record(actorId, "user.sessions_revoked", "user", userId.toString(),
                    revoked + " session(s) revoked");
        });
    }

    public void reactivate(UUID actorId, UUID userId) {
        UserView before = store.findById(userId).orElseThrow(() -> new UserNotFound(userId));
        store.setActive(userId, true);
        audit.recordChange(actorId, "user.reactivated", "user", userId.toString(),
                viewJson(before), viewJson(withActive(before, true)));
    }

    public Page list(UserFilter filter) {
        return store.list(filter);
    }

    // ---- validation ----

    private void validateFormat(NewUser u) {
        if (u.username() == null || !USERNAME.matcher(u.username()).matches()) {
            throw new InvalidField("username");
        }
        if (u.email() != null && !EMAIL.matcher(u.email()).matches()) {
            throw new InvalidField("email");
        }
        if (u.fullName() == null || u.fullName().isBlank()) {
            throw new InvalidField("fullName");
        }
    }

    private UUID resolveRole(String roleCode) {
        return Optional.ofNullable(roleCode).flatMap(store::roleIdByCode)
                .orElseThrow(() -> new InvalidField("roleCode"));
    }

    private void validateAssignment(UUID receptionId, UUID tenantId) {
        if (receptionId != null && !store.receptionActive(receptionId)) {
            throw new InvalidReference("reception_id");
        }
        if (tenantId != null && !store.tenantActive(tenantId)) {
            throw new InvalidReference("tenant_id");
        }
    }

    // ---- audit JSON (no password material) ----
    private static String viewJson(UserView v) {
        return "{"
                + "\"username\":\"" + v.username() + "\","
                + "\"email\":" + (v.email() == null ? "null" : "\"" + v.email() + "\"") + ","
                + "\"role\":\"" + v.roleCode() + "\","
                + "\"receptionId\":" + q(v.receptionId()) + ","
                + "\"tenantId\":" + q(v.tenantId()) + ","
                + "\"active\":" + v.active()
                + "}";
    }

    private static String q(UUID u) {
        return u == null ? "null" : "\"" + u + "\"";
    }

    private static UserView withActive(UserView v, boolean active) {
        return new UserView(v.id(), v.username(), v.email(), v.fullName(), v.roleCode(),
                v.receptionId(), v.tenantId(), active);
    }

    // ---- failures ----
    public static final class InvalidField extends RuntimeException {
        public final String field;
        public InvalidField(String field) { super("Invalid field: " + field); this.field = field; }
    }
    public static final class InvalidReference extends RuntimeException {
        public final String field;
        public InvalidReference(String field) {
            super("Reference not found or inactive: " + field); this.field = field;
        }
    }
    public static final class DuplicateField extends RuntimeException {
        public final String field;
        public DuplicateField(String field) { super("Already exists: " + field); this.field = field; }
    }
    public static final class UserNotFound extends RuntimeException {
        public UserNotFound(UUID id) { super("User not found: " + id); }
    }
}
