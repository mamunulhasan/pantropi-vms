package com.pantropi.vms.application.visitor.port;

/**
 * Whether approving a request should mint the passes too (US-09.1.2 AC-2).
 *
 * <p>Narrow, like {@link RegistrationPolicy} beside it, and for the same reason: a general
 * "read any setting" port would hand the visitor context the whole configuration surface. This one
 * answers a single question.
 */
public interface IssuancePolicy {

    /**
     * <p>AC-2 is explicit that the trigger is a policy and not a hard-wired behaviour: a building
     * that wants a person between an approval and a working pass turns this off and issues by hand
     * through US-09.1.1.
     */
    boolean autoIssueOnApproval();
}
