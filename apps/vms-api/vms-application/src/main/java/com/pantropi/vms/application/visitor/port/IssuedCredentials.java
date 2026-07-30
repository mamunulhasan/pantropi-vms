package com.pantropi.vms.application.visitor.port;

import java.util.UUID;

/**
 * Whether a visitor already holds a credential worth revoking (US-08.1.3 AC-3).
 *
 * <p>Declared by the visitor context, implemented by the credential context — which owns
 * {@code vms.credentials} — on the same pattern as {@link VisitorTypeDirectory} and
 * {@link ReceptionScope}. The visitor context asks one question; it never joins the table.
 *
 * <p>EPIC-09 is unbuilt, so today nothing issues credentials and this answers false. The check is
 * real regardless: the cancellation path must not need rework the day issuance arrives, and the
 * integration test exercises it by inserting a credential row directly.
 */
public interface IssuedCredentials {

    /**
     * True when any credential for this visitor is {@code requested} or {@code active}.
     *
     * <p>{@code requested} counts deliberately: a credential still in flight to the ACS must be
     * revoked too, or the pass arrives after the visit was withdrawn — the exact outcome AC-3
     * exists to prevent.
     */
    boolean anyLiveFor(UUID visitorId);
}
