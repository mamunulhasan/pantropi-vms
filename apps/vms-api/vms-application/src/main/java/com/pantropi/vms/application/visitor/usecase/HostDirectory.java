package com.pantropi.vms.application.visitor.usecase;

import com.pantropi.vms.application.identity.port.AuditTrail;
import com.pantropi.vms.application.shared.port.TransactionRunner;
import com.pantropi.vms.application.visitor.port.HostRepository;
import com.pantropi.vms.application.visitor.port.TenantDirectory;
import com.pantropi.vms.domain.visitor.Host;

import java.util.UUID;

/**
 * Maintain a tenant's host directory (US-10.1.1).
 *
 * <p>Two rules shape every method, and both exist because a host record is another organisation's
 * employee data:
 *
 * <ul>
 *   <li><strong>The tenant comes from the acting user, never from the request.</strong> AC-1 says
 *       {@code tenant_id} is derived from the user's own record, so {@link TenantDirectory} answers
 *       it and no command field can. A user with no tenant cannot create a host at all — there is
 *       no directory for them to add to.</li>
 *   <li><strong>Reads and writes are scoped in the repository, not here.</strong> The adapter
 *       obtains the predicate from {@code ScopePolicy} itself, so this class cannot pass the wrong
 *       one or forget to. A host the scope cannot see is reported as {@link HostNotFound} — the
 *       same answer an id that never existed produces, so the two are indistinguishable to a
 *       caller probing for other tenants' staff (AC-4).</li>
 * </ul>
 *
 * <p>Nothing here deletes. Departure is deactivation, which leaves every visit a host received
 * still readable (AC-3/AC-5).
 *
 * <p>Pure orchestration over ports — no framework.
 */
public final class HostDirectory {

    private final HostRepository hosts;
    private final TenantDirectory tenants;
    private final AuditTrail audit;
    private final TransactionRunner tx;

    public HostDirectory(HostRepository hosts, TenantDirectory tenants, AuditTrail audit,
                         TransactionRunner tx) {
        this.hosts = hosts;
        this.tenants = tenants;
        this.audit = audit;
        this.tx = tx;
    }

    /** Add a host to the acting user's own tenant (AC-1). */
    public UUID create(UUID actingUser, String fullName, String email, String phone) {
        UUID tenantId = tenants.tenantOfUser(actingUser)
                .orElseThrow(() -> new NoTenantForUser(actingUser));
        Host host = Host.named(tenantId, fullName, email, phone);

        return tx.call(() -> {
            hosts.save(host);
            // Flags, not contents: whether an email or phone was supplied is enough to reconstruct
            // what happened, and the values themselves are the PII this record exists to protect.
            audit.recordChange(actingUser, "host.created", "host", host.id().toString(), null,
                    "{\"tenantId\":\"" + tenantId + "\",\"hasEmail\":" + (host.emailValue() != null)
                            + ",\"hasPhone\":" + (host.phoneValue() != null) + "}");
            return host.id();
        });
    }

    /** Correct a host's details. Identity, tenant and active state are unchanged. */
    public void update(UUID actingUser, UUID hostId, String fullName, String email, String phone) {
        Host existing = require(hostId);
        Host corrected = existing.withDetails(fullName, email, phone);

        tx.run(() -> {
            if (!hosts.update(corrected)) {
                // Lost the row between the read and the write — same answer as never having it.
                throw new HostNotFound(hostId);
            }
            audit.recordChange(actingUser, "host.updated", "host", hostId.toString(), null,
                    "{\"nameChanged\":" + !existing.fullName().equals(corrected.fullName())
                            + ",\"hasEmail\":" + (corrected.emailValue() != null)
                            + ",\"hasPhone\":" + (corrected.phoneValue() != null) + "}");
        });
    }

    /** They have left. Existing requests keep naming them (AC-3). */
    public void deactivate(UUID actingUser, UUID hostId) {
        setActive(actingUser, hostId, false);
    }

    public void reactivate(UUID actingUser, UUID hostId) {
        setActive(actingUser, hostId, true);
    }

    private void setActive(UUID actingUser, UUID hostId, boolean active) {
        Host existing = require(hostId);
        if (existing.active() == active) {
            return;   // idempotent: no write, no audit row for a state that already holds
        }
        Host changed = active ? existing.reactivate() : existing.deactivate();

        tx.run(() -> {
            if (!hosts.update(changed)) {
                throw new HostNotFound(hostId);
            }
            audit.recordChange(actingUser, active ? "host.reactivated" : "host.deactivated",
                    "host", hostId.toString(), null, "{\"active\":" + active + "}");
        });
    }

    /** One host, or {@link HostNotFound} for an unknown id and for another tenant's alike. */
    public Host get(UUID hostId) {
        return require(hostId);
    }

    /** A page of the scope's hosts, active and inactive both (AC-2). */
    public HostRepository.Page list(String search, Boolean active, int page, int size) {
        return hosts.list(new HostRepository.Query(search, active, page, size));
    }

    private Host require(UUID hostId) {
        return hosts.findById(hostId).orElseThrow(() -> new HostNotFound(hostId));
    }


    // ---- failures ----

    /** Unknown, or belonging to another tenant. The caller cannot tell which, deliberately. */
    public static final class HostNotFound extends RuntimeException {
        public HostNotFound(UUID id) {
            super("No host " + id + " in scope");
        }
    }

    /** The acting user belongs to no tenant, so there is no directory to maintain. */
    public static final class NoTenantForUser extends IllegalStateException {
        public NoTenantForUser(UUID userId) {
            super("User " + userId + " belongs to no tenant");
        }
    }
}
