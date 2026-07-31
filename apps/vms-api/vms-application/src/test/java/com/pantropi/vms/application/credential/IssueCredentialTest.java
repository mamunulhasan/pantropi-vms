package com.pantropi.vms.application.credential;

import com.pantropi.vms.application.acs.port.AcsFailure;
import com.pantropi.vms.application.acs.port.AcsPort;
import com.pantropi.vms.application.credential.port.CredentialRepository;
import com.pantropi.vms.application.credential.usecase.IssueCredential;
import com.pantropi.vms.application.identity.port.AuditTrail;
import com.pantropi.vms.application.notification.port.NotificationLog;
import com.pantropi.vms.application.notification.port.NotificationSender;
import com.pantropi.vms.application.notification.port.VisitorContacts;
import com.pantropi.vms.application.notification.usecase.SendCredentialEmail;
import com.pantropi.vms.domain.credential.Credential;
import com.pantropi.vms.domain.masterdata.CredentialType;
import com.pantropi.vms.domain.masterdata.RestrictionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * UNIT TESTS for {@link IssueCredential} (US-09.1.1).
 *
 * <p>The assertions that matter are about <em>order</em>: the row before the call, the duplicate
 * check before the row, and the outcome after. A single shared journal records every step so the
 * sequence itself can be asserted rather than inferred.
 */
class IssueCredentialTest {

    private static final Instant NOW = Instant.parse("2030-06-01T09:00:00Z");
    private static final UUID ACTOR = UUID.randomUUID();
    private static final UUID VISITOR = UUID.randomUUID();

    private final List<String> journal = new ArrayList<>();
    private final FakeRepo repo = new FakeRepo(journal);
    private final FakeAudit audit = new FakeAudit();

    private final FakeSender mailer = new FakeSender(journal);

    private IssueCredential useCase(AcsPort acs) {
        return useCase(acs, true);
    }

    private IssueCredential useCase(AcsPort acs, boolean emailEnabled) {
        SendCredentialEmail send = new SendCredentialEmail(
                id -> Optional.of(new VisitorContacts.Contact(VISITOR, "Ada Lovelace",
                        "ada@example.com")),
                mailer, new FakeLog(journal), emailEnabled);
        return new IssueCredential(repo, acs, audit, send);
    }

    private IssueCredential.Command command() {
        return new IssueCredential.Command(VISITOR, null, CredentialType.QR,
                RestrictionType.TIME_BOUND, NOW, NOW.plus(Duration.ofHours(4)));
    }

    @Test
    @DisplayName("AC-1: the row is written before any outbound call is attempted")
    void rowPrecedesTheCall() {
        Credential issued = useCase(new RecordingAcs(journal)).issue(ACTOR, command());

        assertThat(journal).containsExactly("save:requested", "acs:createCredential",
                "update:active", "mail:ada@example.com", "log:sent");
        assertThat(issued.state()).isEqualTo(Credential.State.ACTIVE);
    }

    @Test
    @DisplayName("AC-3: the reference and payload from ACS are stored, and the state becomes active")
    void successStoresTheReference() {
        Credential issued = useCase(new RecordingAcs(journal)).issue(ACTOR, command());

        assertThat(issued.acsCredentialId()).isEqualTo("REF-1");
        assertThat(issued.qrPayload()).isEqualTo("PAYLOAD-1");
        assertThat(issued.issuedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("AC-7: a duplicate is refused before the row is written and before the call")
    void duplicateIsRefusedFirst() {
        repo.live = Credential.requested(VISITOR, null, CredentialType.QR,
                RestrictionType.TIME_BOUND, NOW, NOW.plus(Duration.ofHours(1)));

        assertThatThrownBy(() -> useCase(new RecordingAcs(journal)).issue(ACTOR, command()))
                .isInstanceOf(IssueCredential.AlreadyIssued.class);

        // Nothing written, nothing sent: the unique index is never the first line of defence.
        assertThat(journal).isEmpty();
        assertThat(audit.actions).isEmpty();
    }

    @Test
    @DisplayName("a transient failure leaves the credential requested, for the retry machinery")
    void transientLeavesItRequested() {
        assertThatThrownBy(() -> useCase(new FailingAcs(new AcsFailure.Transient("timeout")))
                .issue(ACTOR, command()))
                .isInstanceOf(AcsFailure.Transient.class);

        // No update at all — marking it failed would abandon a pass a timeout away from working.
        assertThat(journal).containsExactly("save:requested");
        assertThat(audit.actions).containsExactly("credential.requested", "credential.request_deferred");
    }

    @Test
    @DisplayName("a permanent failure marks the credential failed, so nothing retries it")
    void permanentMarksItFailed() {
        assertThatThrownBy(() -> useCase(new FailingAcs(new AcsFailure.Permanent("rejected")))
                .issue(ACTOR, command()))
                .isInstanceOf(AcsFailure.Permanent.class);

        assertThat(journal).containsExactly("save:requested", "update:failed");
        assertThat(audit.actions).contains("credential.request_failed");
    }

    @Test
    @DisplayName("a malformed response is treated as permanent — the contract, not the network, is wrong")
    void malformedMarksItFailed() {
        assertThatThrownBy(() -> useCase(new FailingAcs(new AcsFailure.MalformedResponse("garbage")))
                .issue(ACTOR, command()))
                .isInstanceOf(AcsFailure.MalformedResponse.class);

        assertThat(journal).containsExactly("save:requested", "update:failed");
    }

    @Test
    @DisplayName("the audit records the ACS reference but never the QR payload")
    void auditNeverCarriesThePayload() {
        useCase(new RecordingAcs(journal)).issue(ACTOR, command());

        assertThat(audit.payloads).anySatisfy(p -> assertThat(p).contains("REF-1"));
        assertThat(audit.payloads).allSatisfy(p -> assertThat(p).doesNotContain("PAYLOAD-1"));
    }

    @Test
    @DisplayName("an inverted window is refused by the domain, before anything is written")
    void invertedWindowIsRefused() {
        IssueCredential.Command bad = new IssueCredential.Command(VISITOR, null, CredentialType.QR,
                RestrictionType.TIME_BOUND, NOW, NOW.minus(Duration.ofHours(1)));

        assertThatThrownBy(() -> useCase(new RecordingAcs(journal)).issue(ACTOR, bad))
                .isInstanceOf(Credential.InvalidValidityWindow.class);
        assertThat(journal).isEmpty();
    }

    @Test
    @DisplayName("the credential survives a mail failure — the pass exists whether or not it was sent")
    void mailFailureDoesNotUndoIssuance() {
        mailer.explode = true;

        Credential issued = useCase(new RecordingAcs(journal)).issue(ACTOR, command());

        // Still active, still updated, and the failure is written down rather than raised.
        assertThat(issued.state()).isEqualTo(Credential.State.ACTIVE);
        assertThat(journal).contains("update:active", "log:failed");
        assertThat(audit.payloads).anySatisfy(p -> assertThat(p).contains("FAILED"));
    }

    @Test
    @DisplayName("US-16.2.1 AC-3: email switched off sends nothing and raises nothing")
    void disabledChannelIsNotAFailure() {
        Credential issued = useCase(new RecordingAcs(journal), false).issue(ACTOR, command());

        assertThat(issued.state()).isEqualTo(Credential.State.ACTIVE);
        assertThat(journal).doesNotContain("mail:ada@example.com");
        assertThat(audit.payloads).anySatisfy(p -> assertThat(p).contains("DISABLED"));
    }

    @Test
    @DisplayName("the email never carries the QR payload — mail is stored and forwarded elsewhere")
    void emailNeverCarriesThePayload() {
        useCase(new RecordingAcs(journal)).issue(ACTOR, command());

        assertThat(mailer.bodies).isNotEmpty();
        assertThat(mailer.bodies).allSatisfy(b -> assertThat(b).doesNotContain("PAYLOAD-1"));
    }

    // ---- fakes ----

    private static final class FakeSender implements NotificationSender {
        private final List<String> journal;
        final List<String> bodies = new ArrayList<>();
        boolean explode;

        FakeSender(List<String> journal) {
            this.journal = journal;
        }

        @Override public Sent send(Message message) {
            if (explode) {
                throw new SendFailed("no transport", new RuntimeException());
            }
            journal.add("mail:" + message.recipient());
            bodies.add(message.body());
            return new Sent("/tmp/fake.txt");
        }
    }

    private record FakeLog(List<String> journal) implements NotificationLog {
        @Override public void record(UUID visitorId, String recipient, String channel,
                                     String notifyType, String subject, String bodyRef,
                                     String status) {
            journal.add("log:" + status);
        }
    }

    private static final class FakeRepo implements CredentialRepository {
        private final List<String> journal;
        Credential live;

        FakeRepo(List<String> journal) {
            this.journal = journal;
        }

        @Override public void save(Credential c) {
            journal.add("save:" + c.state().dbValue());
        }

        @Override public void update(Credential c) {
            journal.add("update:" + c.state().dbValue());
        }

        @Override public Optional<Credential> findById(UUID id) {
            return Optional.empty();
        }

        @Override public Optional<Credential> findLiveFor(UUID visitorId) {
            return Optional.ofNullable(live);
        }
    }

    private record RecordingAcs(List<String> journal) implements AcsPort {
        @Override public AcsCredential createCredential(AcsCredentialRequest request) {
            journal.add("acs:createCredential");
            return new AcsCredential(new AcsCredentialReference("REF-1"), "PAYLOAD-1", NOW);
        }

        @Override public void deactivateCredential(AcsCredentialReference reference) {
            throw new UnsupportedOperationException();
        }

        @Override public AcsCredentialStatus queryCredentialStatus(AcsCredentialReference reference) {
            throw new UnsupportedOperationException();
        }
    }

    private record FailingAcs(AcsFailure failure) implements AcsPort {
        @Override public AcsCredential createCredential(AcsCredentialRequest request) {
            throw failure;
        }

        @Override public void deactivateCredential(AcsCredentialReference reference) {
            throw new UnsupportedOperationException();
        }

        @Override public AcsCredentialStatus queryCredentialStatus(AcsCredentialReference reference) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FakeAudit implements AuditTrail {
        final List<String> actions = new ArrayList<>();
        final List<String> payloads = new ArrayList<>();

        @Override public void record(UUID a, String action, String t, String i, String d) {
            actions.add(action);
        }

        @Override public void recordChange(UUID a, String action, String t, String i, String b, String af) {
            actions.add(action);
            payloads.add(String.valueOf(af));
        }

        @Override public void recordSecurityDenial(UUID a, String ac, String p, String r,
                                                   String m, String o, String ip) {
            throw new UnsupportedOperationException();
        }
    }
}
