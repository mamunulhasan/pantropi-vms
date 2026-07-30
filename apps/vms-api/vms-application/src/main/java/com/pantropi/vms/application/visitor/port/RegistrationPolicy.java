package com.pantropi.vms.application.visitor.port;

/**
 * The configured rules a pre-registration has to satisfy (US-08.1.1 AC-5) — FR-VMS-03 (SRS B1).
 *
 * <p>A port rather than a direct read of {@code vms.system_settings}, for the same reason as
 * {@link ReceptionScope}: settings belong to the master-data context, and the visitor context asks
 * rather than joins.
 *
 * <p>Narrow to what this story needs. A general "give me any setting" port would hand the visitor
 * context the whole configuration surface, and every future caller would be free to depend on a key
 * nobody expected it to read.
 */
public interface RegistrationPolicy {

    /**
     * How far into the past an appointment may start, in minutes (AC-5).
     *
     * <p>Zero is not the sensible default. A receptionist typing a visitor in as they arrive is the
     * normal case, so an appointment beginning a few minutes ago is a correct record of what
     * happened, not an error — the rule exists to catch a mistyped date, not to insist the paperwork
     * precede the visitor.
     */
    int pastAppointmentGraceMinutes();
}
