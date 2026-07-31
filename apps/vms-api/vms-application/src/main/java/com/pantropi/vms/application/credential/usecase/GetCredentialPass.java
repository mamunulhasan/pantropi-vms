package com.pantropi.vms.application.credential.usecase;

import com.pantropi.vms.application.credential.port.CredentialRepository;
import com.pantropi.vms.domain.credential.Credential;

import java.time.Instant;
import java.util.UUID;

/**
 * Reads the pass a visitor currently holds, payload included (US-09.5.1).
 *
 * <p>This is the only read in the system that returns {@code qr_payload}, and it exists for one
 * caller: the portal server, which turns the payload into a PNG. US-09.5.1 AC-3 wants the payload
 * to stay server-side, so the browser is expected to fetch the rendered image and never this.
 *
 * <p>That expectation is a design, not an enforcement — any holder of {@code credential.issue} can
 * call the route directly. Closing that would need a back-end-for-frontend with its own token
 * exchange; it is recorded as deferred rather than pretended away.
 *
 * <p>Only an {@code ACTIVE} credential has a payload to give. A {@code requested} one is still
 * waiting on ACS and a {@code failed} one never will be, and both are refused rather than answered
 * with a null the caller would have to interpret.
 */
public final class GetCredentialPass {

    private final CredentialRepository credentials;

    public GetCredentialPass(CredentialRepository credentials) {
        this.credentials = credentials;
    }

    /** @param qrPayload the scannable secret — never logged, never audited, never in an error */
    public record Pass(UUID credentialId, String qrPayload, Instant validFrom, Instant validTo,
                       Instant issuedAt) {}

    public Pass forVisitor(UUID visitorId) {
        Credential c = credentials.findLiveFor(visitorId).orElseThrow(NoPass::new);
        if (c.state() != Credential.State.ACTIVE || c.qrPayload() == null) {
            throw new NotRenderable(c.state());
        }
        return new Pass(c.id(), c.qrPayload(), c.validFrom(), c.validTo(), c.issuedAt());
    }

    public static final class NoPass extends RuntimeException {
        public NoPass() {
            super("This visitor holds no credential");
        }
    }

    public static final class NotRenderable extends RuntimeException {
        public final Credential.State state;

        public NotRenderable(Credential.State state) {
            super("Credential is " + state.dbValue() + " and has no pass to render");
            this.state = state;
        }
    }
}
