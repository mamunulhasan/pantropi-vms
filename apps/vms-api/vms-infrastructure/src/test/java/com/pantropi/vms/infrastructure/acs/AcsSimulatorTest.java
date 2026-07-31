package com.pantropi.vms.infrastructure.acs;

import com.pantropi.vms.application.acs.port.AcsFailure;
import com.pantropi.vms.application.acs.port.AcsPort;
import com.pantropi.vms.domain.masterdata.CredentialType;
import com.pantropi.vms.domain.masterdata.RestrictionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * UNIT TESTS for the ACS simulator (US-11.2.1).
 *
 * <p>These assert that it behaves like a system with rules rather than a stub that agrees with
 * everything — the distinction ADR-0002 draws when it calls the simulator a first-class
 * deliverable. What they cannot assert is anything about UAL.
 */
class AcsSimulatorTest {

    private static final Instant NOW = Instant.parse("2030-06-01T09:00:00Z");

    private final MutableClock clock = new MutableClock(NOW);
    private final AcsSimulator acs = new AcsSimulator(clock);

    private AcsPort.AcsCredentialRequest request(CredentialType type) {
        return new AcsPort.AcsCredentialRequest(UUID.randomUUID(), type, RestrictionType.TIME_BOUND,
                NOW, NOW.plus(Duration.ofHours(4)));
    }

    @Test
    @DisplayName("a qr credential comes back with a reference and an opaque payload")
    void issuesQrWithPayload() {
        AcsPort.AcsCredential issued = acs.createCredential(request(CredentialType.QR));

        assertThat(issued.reference().value()).isNotBlank();
        assertThat(issued.qrPayload()).isNotBlank();
        assertThat(issued.issuedAt()).isEqualTo(NOW);
        assertThat(acs.queryCredentialStatus(issued.reference()))
                .isEqualTo(AcsPort.AcsCredentialStatus.ACTIVE);
    }

    @Test
    @DisplayName("an rfid credential carries no payload — the card is collected, not rendered")
    void rfidHasNoPayload() {
        assertThat(acs.createCredential(request(CredentialType.RFID)).qrPayload()).isNull();
    }

    @Test
    @DisplayName("two credentials never share a reference or a payload")
    void referencesAndPayloadsAreUnique() {
        AcsPort.AcsCredential first = acs.createCredential(request(CredentialType.QR));
        AcsPort.AcsCredential second = acs.createCredential(request(CredentialType.QR));

        assertThat(first.reference()).isNotEqualTo(second.reference());
        assertThat(first.qrPayload()).isNotEqualTo(second.qrPayload());
    }

    @Test
    @DisplayName("a window that has already ended is refused permanently, not retried")
    void deadWindowIsPermanent() {
        AcsPort.AcsCredentialRequest expired = new AcsPort.AcsCredentialRequest(
                UUID.randomUUID(), CredentialType.QR, RestrictionType.TIME_BOUND,
                NOW.minus(Duration.ofHours(4)), NOW.minus(Duration.ofHours(1)));

        assertThatThrownBy(() -> acs.createCredential(expired))
                .isInstanceOf(AcsFailure.Permanent.class);
        assertThat(acs.issuedCount()).isZero();
    }

    @Test
    @DisplayName("deactivation is idempotent — twice is not an error, and does not un-revoke")
    void deactivationIsIdempotent() {
        AcsPort.AcsCredential issued = acs.createCredential(request(CredentialType.QR));

        acs.deactivateCredential(issued.reference());
        acs.deactivateCredential(issued.reference());

        assertThat(acs.queryCredentialStatus(issued.reference()))
                .isEqualTo(AcsPort.AcsCredentialStatus.REVOKED);
    }

    @Test
    @DisplayName("deactivating something ACS never issued is accepted, not refused")
    void deactivatingUnknownIsAccepted() {
        // A revocation that failed because it had already happened would be retried forever.
        acs.deactivateCredential(new AcsPort.AcsCredentialReference("SIM-never-existed"));
    }

    @Test
    @DisplayName("a reference ACS does not know answers UNKNOWN — a real answer, not an exception")
    void unknownReferenceIsAStatus() {
        assertThat(acs.queryCredentialStatus(new AcsPort.AcsCredentialReference("SIM-nope")))
                .isEqualTo(AcsPort.AcsCredentialStatus.UNKNOWN);
    }

    @Test
    @DisplayName("expiry follows the clock, with nobody having to write it down")
    void expiryIsComputedFromTheClock() {
        AcsPort.AcsCredential issued = acs.createCredential(request(CredentialType.QR));
        assertThat(acs.queryCredentialStatus(issued.reference()))
                .isEqualTo(AcsPort.AcsCredentialStatus.ACTIVE);

        clock.advance(Duration.ofHours(5));

        assertThat(acs.queryCredentialStatus(issued.reference()))
                .isEqualTo(AcsPort.AcsCredentialStatus.EXPIRED);
    }

    @Test
    @DisplayName("revocation outranks expiry — a revoked credential does not become merely expired")
    void revocationOutranksExpiry() {
        AcsPort.AcsCredential issued = acs.createCredential(request(CredentialType.QR));
        acs.deactivateCredential(issued.reference());

        clock.advance(Duration.ofHours(5));

        assertThat(acs.queryCredentialStatus(issued.reference()))
                .isEqualTo(AcsPort.AcsCredentialStatus.REVOKED);
    }

    @Test
    @DisplayName("the issued credential's toString does not disclose the payload")
    void toStringHidesThePayload() {
        AcsPort.AcsCredential issued = acs.createCredential(request(CredentialType.QR));

        // The payload opens a barrier. It is stored; it is never logged.
        assertThat(issued.toString()).doesNotContain(issued.qrPayload()).contains("qr=present");
    }

    /** A clock a test can move, so expiry can be observed rather than waited for. */
    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override public Instant instant() {
            return now;
        }

        @Override public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
