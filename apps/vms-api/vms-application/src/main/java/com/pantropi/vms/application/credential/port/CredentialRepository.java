package com.pantropi.vms.application.credential.port;

import com.pantropi.vms.domain.credential.Credential;

import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for credentials (US-09.1.1, T-09.1.1.1).
 *
 * <p>{@link #save} and {@link #update} are separate because the issuance flow writes twice: once
 * before the outbound call and once after it. Collapsing them into an upsert would make it
 * possible to write the row and the result together, which is precisely the ordering AC-1 forbids.
 */
public interface CredentialRepository {

    /** Insert a {@code requested} credential. Called before any ACS call is attempted (AC-1). */
    void save(Credential credential);

    /** Record the outcome — {@code active} with its reference, or {@code failed}. */
    void update(Credential credential);

    Optional<Credential> findById(UUID id);

    /**
     * A credential already live for this visitor, if any.
     *
     * <p>Consulted before anything is written so a duplicate is refused ahead of the outbound call
     * (AC-7). The partial unique index {@code ux_credentials_active_per_visitor} still backs this
     * up, but an index violation surfacing as a 500 after ACS has already minted a second pass is
     * exactly the outcome the check exists to avoid.
     */
    Optional<Credential> findLiveFor(UUID visitorId);
}
