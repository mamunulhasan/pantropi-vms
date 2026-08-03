package com.pantropi.vms.domain.visitor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * UNIT TESTS for the two pure pieces of the arrival path (US-12.1.2 T-12.1.2.1,
 * US-12.2.1 T-12.2.1.1).
 *
 * <p>Both are pure functions over three inputs, which is exactly why they are worth testing
 * exhaustively here rather than through a screen: every combination is cheap, and the boundary
 * instants are only reachable when the clock is an argument.
 */
class ArrivalDomainTest {

    private static final Instant OPENS = Instant.parse("2030-06-01T09:00:00Z");
    private static final Instant CLOSES = Instant.parse("2030-06-01T11:00:00Z");
    private static final TimeWindow WINDOW = new TimeWindow(OPENS, CLOSES);

    @Nested
    @DisplayName("the transition table")
    class Transitions {

        @Test
        @DisplayName("the arrival path is legal end to end")
        void arrivalPath() {
            assertThat(VisitorTransitions.isLegal(VisitorStatus.PENDING, VisitorStatus.APPROVED))
                    .isTrue();
            assertThat(VisitorTransitions.isLegal(VisitorStatus.APPROVED, VisitorStatus.CHECKED_IN))
                    .isTrue();
            assertThat(VisitorTransitions.isLegal(VisitorStatus.CHECKED_IN, VisitorStatus.INSIDE))
                    .isTrue();
            assertThat(VisitorTransitions.isLegal(VisitorStatus.INSIDE, VisitorStatus.CHECKED_OUT))
                    .isTrue();
        }

        @Test
        @DisplayName("checked_in may go straight to checked_out — no barrier feeds INSIDE yet")
        void checkedInMayLeaveWithoutPassingInside() {
            // INSIDE is set by an ACS entry event (US-12.3.1) and nothing produces one here. If
            // this were illegal, the on-site roll would only ever be correct in a wired building.
            assertThat(VisitorTransitions.isLegal(VisitorStatus.CHECKED_IN,
                    VisitorStatus.CHECKED_OUT)).isTrue();
        }

        @Test
        @DisplayName("AC-4: an unapproved visitor cannot be checked in")
        void pendingCannotCheckIn() {
            assertThat(VisitorTransitions.isLegal(VisitorStatus.PENDING, VisitorStatus.CHECKED_IN))
                    .isFalse();
        }

        @Test
        @DisplayName("AC-3: a second check-in is refused, and so is a second check-out")
        void noSecondArrivalOrDeparture() {
            assertThat(VisitorTransitions.isLegal(VisitorStatus.CHECKED_IN,
                    VisitorStatus.CHECKED_IN)).isFalse();
            assertThat(VisitorTransitions.isLegal(VisitorStatus.CHECKED_OUT,
                    VisitorStatus.CHECKED_OUT)).isFalse();
        }

        @Test
        @DisplayName("nothing moves out of a terminal state")
        void terminalIsTerminal() {
            for (VisitorStatus terminal : new VisitorStatus[] {VisitorStatus.CHECKED_OUT,
                    VisitorStatus.CANCELLED, VisitorStatus.EXPIRED, VisitorStatus.NO_SHOW}) {
                assertThat(VisitorTransitions.isTerminal(terminal))
                        .as("%s is terminal", terminal)
                        .isTrue();
                for (VisitorStatus target : VisitorStatus.values()) {
                    assertThat(VisitorTransitions.isLegal(terminal, target))
                            .as("%s -> %s", terminal, target)
                            .isFalse();
                }
            }
        }

        @Test
        @DisplayName("a refusal names both ends, so it can be reported as one sentence")
        void refusalNamesBothEnds() {
            assertThatThrownBy(() -> VisitorTransitions.require(VisitorStatus.CHECKED_OUT,
                    VisitorStatus.CHECKED_IN))
                    .isInstanceOf(VisitorTransitions.IllegalVisitorTransition.class)
                    .hasMessageContaining("checked_out")
                    .hasMessageContaining("checked_in");
        }
    }

    @Nested
    @DisplayName("the appointment confirmation")
    class Confirmation {

        @Test
        @DisplayName("approved and in window is the one outcome that permits entry")
        void appointed() {
            AppointmentConfirmation c = evaluate(RequestStatus.APPROVED, VisitorStatus.APPROVED,
                    OPENS.plus(30, ChronoUnit.MINUTES));
            assertThat(c.outcome()).isEqualTo(AppointmentConfirmation.Outcome.APPOINTED);
            assertThat(c.mayProceed()).isTrue();
            assertThat(c.reason()).isNull();
        }

        @Test
        @DisplayName("both boundary instants are inside the window")
        void boundariesAreInclusive() {
            // A visitor arriving exactly as it opens is on time; one arriving exactly as it closes
            // has not yet missed it. The alternative turns a boundary into an unpredictable refusal.
            assertThat(evaluate(RequestStatus.APPROVED, VisitorStatus.APPROVED, OPENS).outcome())
                    .isEqualTo(AppointmentConfirmation.Outcome.APPOINTED);
            assertThat(evaluate(RequestStatus.APPROVED, VisitorStatus.APPROVED, CLOSES).outcome())
                    .isEqualTo(AppointmentConfirmation.Outcome.APPOINTED);
        }

        @Test
        @DisplayName("AC-3: arriving early is a state, not a refusal")
        void early() {
            AppointmentConfirmation c = evaluate(RequestStatus.APPROVED, VisitorStatus.APPROVED,
                    OPENS.minusSeconds(1));
            assertThat(c.outcome()).isEqualTo(AppointmentConfirmation.Outcome.EARLY);
            assertThat(c.mayProceed()).isFalse();
            assertThat(c.windowFrom()).isEqualTo(OPENS);
        }

        @Test
        @DisplayName("AC-4: an elapsed window offers no automatic extension")
        void elapsed() {
            assertThat(evaluate(RequestStatus.APPROVED, VisitorStatus.APPROVED,
                    CLOSES.plusSeconds(1)).outcome())
                    .isEqualTo(AppointmentConfirmation.Outcome.ELAPSED);
        }

        @Test
        @DisplayName("AC-2: an undecided or refused visit names its reason")
        void notAppointed() {
            assertThat(evaluate(RequestStatus.SUBMITTED, VisitorStatus.PENDING, OPENS).reason())
                    .contains("not been approved");
            assertThat(evaluate(RequestStatus.REJECTED, VisitorStatus.CANCELLED, OPENS).reason())
                    .contains("rejected");
            assertThat(evaluate(RequestStatus.CANCELLED, VisitorStatus.CANCELLED, OPENS).reason())
                    .contains("withdrawn");
        }

        @Test
        @DisplayName("AC-5: a duplicate arrival is reported before the clock is consulted")
        void alreadyArrivedWinsOverTheWindow() {
            // Someone already inside who presents again is a question about them, not about time —
            // so this must hold even when the window has elapsed.
            AppointmentConfirmation c = evaluate(RequestStatus.APPROVED, VisitorStatus.INSIDE,
                    CLOSES.plus(5, ChronoUnit.HOURS));
            assertThat(c.outcome()).isEqualTo(AppointmentConfirmation.Outcome.ALREADY_ARRIVED);
            assertThat(c.reason()).contains("inside");
        }

        @Test
        @DisplayName("a visit with no window is appointed for as long as it is approved")
        void noWindow() {
            AppointmentConfirmation c = AppointmentConfirmation.evaluate(RequestStatus.APPROVED,
                    VisitorStatus.APPROVED, null, OPENS);
            assertThat(c.outcome()).isEqualTo(AppointmentConfirmation.Outcome.APPOINTED);
        }

        private AppointmentConfirmation evaluate(RequestStatus request, VisitorStatus visitor,
                                                 Instant now) {
            return AppointmentConfirmation.evaluate(request, visitor, WINDOW, now);
        }
    }
}
