package com.pantropi.vms.application.identity.usecase;

import com.pantropi.vms.application.identity.port.AdminDirectory;
import com.pantropi.vms.application.identity.port.PasswordHasher;

import java.util.UUID;

/**
 * First-run bootstrap of the initial administrator (US-02.4.1, T-02.4.1.3, AC-5).
 *
 * <p>Creates the initial {@code SYSTEM_ADMIN} account ONLY when {@code vms.users} is empty, so
 * the deny-by-default RBAC system (D-15) has a first holder without a password ever being
 * seeded in a migration or defaulted in source. The password is supplied by the operator at
 * run time and is never logged.
 *
 * <p>Pure orchestration over ports — no framework, no I/O of its own (US-01.2.1 boundary).
 */
public final class BootstrapAdministrator {

    /** Minimal interim password policy; F-02.3 will formalise and replace this. */
    private static final int MIN_PASSWORD_LENGTH = 12;

    private final AdminDirectory admin;
    private final PasswordHasher passwordHasher;

    public BootstrapAdministrator(AdminDirectory admin, PasswordHasher passwordHasher) {
        this.admin = admin;
        this.passwordHasher = passwordHasher;
    }

    /**
     * @return the id of the created administrator
     * @throws AlreadyBootstrapped if any user already exists
     * @throws WeakPassword        if the supplied password fails the interim policy
     * @throws IllegalStateException if the SYSTEM_ADMIN role is not seeded
     */
    public UUID createInitialAdmin(String username, char[] password) {
        if (admin.countUsers() > 0) {
            throw new AlreadyBootstrapped();
        }
        if (password == null || password.length < MIN_PASSWORD_LENGTH) {
            throw new WeakPassword(MIN_PASSWORD_LENGTH);
        }
        UUID roleId = admin.roleIdByCode("SYSTEM_ADMIN")
                .orElseThrow(() -> new IllegalStateException(
                        "SYSTEM_ADMIN role missing — reference seed (V2) not applied"));

        UUID id = UUID.randomUUID();
        String hash = passwordHasher.hash(password);
        admin.insertUser(id, username, hash, roleId, null, true);
        return id;
    }

    public static final class AlreadyBootstrapped extends RuntimeException {
        public AlreadyBootstrapped() {
            super("Bootstrap refused: at least one user already exists");
        }
    }

    public static final class WeakPassword extends RuntimeException {
        public WeakPassword(int min) {
            super("Bootstrap password must be at least " + min + " characters");
        }
    }
}
