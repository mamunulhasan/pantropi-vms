package com.pantropi.vms.infrastructure.identity;

import com.pantropi.vms.application.identity.usecase.BootstrapAdministrator;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * First-run bootstrap administrator command (US-02.4.1, T-02.4.1.3, AC-5).
 *
 * <p>Runs at startup ONLY when {@code vms.bootstrap.admin.enabled=true} — an explicit,
 * operator-driven one-time action. The password is taken from configuration/environment
 * ({@code VMS_BOOTSTRAP_ADMIN_PASSWORD}); it is never defaulted, never seeded in a migration,
 * and never written to a log. The command is a no-op once any user exists.
 *
 * <p>Operator procedure:
 * <pre>
 *   VMS_BOOTSTRAP_ADMIN_PASSWORD='&lt;strong-secret&gt;' \
 *     java -jar vms-api.jar --vms.bootstrap.admin.enabled=true
 *   # then remove the flag for normal operation
 * </pre>
 */
@Component
@ConditionalOnProperty(prefix = "vms.bootstrap.admin", name = "enabled", havingValue = "true")
public class BootstrapAdminRunner implements ApplicationRunner {

    private static final Logger log = Logger.getLogger(BootstrapAdminRunner.class.getName());

    private final BootstrapAdministrator bootstrap;
    private final String username;
    private final char[] password;

    public BootstrapAdminRunner(
            BootstrapAdministrator bootstrap,
            @Value("${vms.bootstrap.admin.username:sysadmin}") String username,
            @Value("${vms.bootstrap.admin.password:}") String password) {
        this.bootstrap = bootstrap;
        this.username = username;
        // Copy into a char[] so we can wipe it; never keep the String around longer than needed.
        this.password = password == null ? new char[0] : password.toCharArray();
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            if (password.length == 0) {
                log.severe("Bootstrap admin enabled but no password supplied. Set "
                        + "VMS_BOOTSTRAP_ADMIN_PASSWORD and re-run. No account created.");
                return;
            }
            UUID id = bootstrap.createInitialAdmin(username, password);
            // Log the identity of the account, NEVER the password.
            log.warning("Bootstrap administrator created: username='" + username + "' id=" + id
                    + " role=SYSTEM_ADMIN. Disable vms.bootstrap.admin.enabled now.");
        } catch (BootstrapAdministrator.AlreadyBootstrapped e) {
            log.info("Bootstrap skipped: users already exist.");
        } catch (BootstrapAdministrator.WeakPassword e) {
            log.log(Level.SEVERE, "Bootstrap refused: " + e.getMessage()); // message carries no secret
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
    }
}
