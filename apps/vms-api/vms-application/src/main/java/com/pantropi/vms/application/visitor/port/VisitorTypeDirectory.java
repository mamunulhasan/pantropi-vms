package com.pantropi.vms.application.visitor.port;

import java.util.UUID;

/**
 * Whether a visitor type may be used on a new request (US-07.1.2, T-07.1.2.2).
 *
 * <p>Declared by the visitor context, implemented by the master-data context. The consumer states
 * the one question it needs answered and the owner of the data answers it — so the visitor context
 * never writes SQL against {@code vms.visitor_types}, and master data stays free to change how it
 * stores them.
 *
 * <p>Narrow on purpose. The visitor context could have taken a dependency on
 * {@code MasterDataStore<VisitorType>}, which is already a port and already wired — and would have
 * handed it {@code insert}, {@code update} and {@code setActive} as well. A request submission has
 * no business being able to deactivate a visitor type.
 *
 * <h2>Substitution: no cache</h2>
 * T-07.1.2.2 specifies this be served from the Redis master-data cache (F-04.9). Redis has no usable
 * client in this build, so the implementation reads through the master-data port directly. The
 * caching decorator drops in here later without touching this interface or any caller — the same
 * seam recorded for {@code PermissionChecker} and {@code MasterDataStore}.
 */
public interface VisitorTypeDirectory {

    /**
     * @return true only if a type with this id exists <em>and</em> is active; false for an unknown
     *         id and for a retired one alike, because from a submission's point of view they are
     *         the same answer — this classification is not available
     */
    boolean isSelectable(UUID visitorTypeId);
}
