package com.pantropi.vms.application.visitor.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Where a floor receptionist is stationed, and which tenants that puts them in front of
 * (US-08.1.1, T-08.1.1.2) — FR-VMS-03 (SRS B1).
 *
 * <p>Declared by the visitor context, implemented by master data — which owns
 * {@code vms.receptions}, {@code vms.floors} and {@code vms.tenants}. T-08.1.1.2 asks specifically
 * that the visitor service not read {@code vms.receptions} directly, and this is how: the consumer
 * states the question, the owner answers it.
 *
 * <h2>Why this resolves to a list rather than one tenant</h2>
 * AC-2 describes the chain as {@code reception_id → floor_id → tenant association}, as though it
 * ends at one tenant. In the published schema it does not: {@code vms.tenants.floor_id} is a plain
 * nullable foreign key with a non-unique index, so a floor can host any number of tenants — which is
 * the normal case in a commercial tower.
 *
 * <p>So the port returns what the database can actually answer, and the use case decides what to do
 * with one, several or none. Collapsing several to "the first one" here would silently attribute a
 * visit to the wrong company, which is the kind of wrongness nobody notices until an incident
 * review. Recorded as TODO-20.
 *
 * <h2>Substitution: no cache</h2>
 * T-08.1.1.2 specifies this be served from the master-data cache with explicit invalidation. Redis
 * has no usable client in this build, so it reads through directly — the same substitution recorded
 * for {@code VisitorTypeDirectory} and {@code PermissionChecker}. The decorator drops in here without
 * touching this interface or any caller.
 */
public interface ReceptionScope {

    /**
     * @return empty when the user has no {@code reception_id} — a clear absence for the use case to
     *         turn into a domain error, rather than a null threaded onwards (T-08.1.1.2)
     */
    Optional<Station> stationOf(UUID userId);

    /**
     * @param tenantsOnFloor active tenants on this floor, in a stable order; may be empty
     */
    record Station(UUID receptionId, UUID floorId, List<UUID> tenantsOnFloor) {

        /** True when the chain in AC-2 does identify exactly one tenant, and derivation is safe. */
        public boolean identifiesOneTenant() {
            return tenantsOnFloor.size() == 1;
        }

        public boolean hosts(UUID tenantId) {
            return tenantId != null && tenantsOnFloor.contains(tenantId);
        }
    }
}
