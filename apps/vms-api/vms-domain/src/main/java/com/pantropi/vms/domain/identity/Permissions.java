package com.pantropi.vms.domain.identity;

import java.util.Set;

/**
 * Every permission code the system knows (US-03.1.1, T-03.1.1.1, AC-4).
 *
 * <p>Referencing a permission through a constant here rather than a string literal at the call site
 * means a typo is a compile error. The alternative failure is worse than it sounds: a misspelled
 * code in a {@code @RequiresPermission} never matches any grant, so the route denies every caller
 * forever, and it does so quietly — no error, no log, just an endpoint nobody can reach and a role
 * that appears to be missing a permission it actually has.
 *
 * <p>{@link #ALL} must correspond <strong>exactly</strong> to {@code vms.permissions}, in both
 * directions, and a test asserts it against the migrated database. A code here that the table does
 * not have would guard a route no grant can satisfy; a code in the table that is missing here is a
 * capability nothing can reference.
 *
 * <h2>Three of these are granted to nobody</h2>
 * {@link #CREDENTIAL_OVERRIDE}, {@link #REPORT_VIEW} and {@link #REPORT_EXPORT} exist because the
 * published schema seeds them, not because anything is authorised to use them — see D-12, TODO-04
 * and TODO-16. They are named here so the eventual grant is a one-line change rather than an
 * archaeology exercise.
 *
 * <p>Pure Java: no framework.
 */
public final class Permissions {

    public static final String MASTERDATA_VIEW = "masterdata.view";
    public static final String MASTERDATA_EDIT = "masterdata.edit";
    public static final String USER_MANAGE = "user.manage";
    public static final String VISITOR_REQUEST = "visitor.request";
    public static final String VISITOR_APPROVE = "visitor.approve";
    public static final String VISITOR_REGISTER = "visitor.register";
    public static final String CREDENTIAL_ISSUE = "credential.issue";
    public static final String CREDENTIAL_OVERRIDE = "credential.override";
    public static final String REPORT_VIEW = "report.view";
    public static final String REPORT_EXPORT = "report.export";
    public static final String SETTINGS_MANAGE = "settings.manage";

    /**
     * Read the immutable audit trail (US-07.4.3 AC-6, FR-AUD-01).
     *
     * <p>Separate from {@link #REPORT_VIEW} on purpose. That one is ungranted pending
     * TODO-16 because no role description mentions reporting, and borrowing it here would
     * have answered that open question as a side effect of an unrelated story.
     */
    public static final String AUDIT_VIEW = "audit.view";

    /** The complete set, for the correspondence test and for validating a grant request. */
    public static final Set<String> ALL = Set.of(
            MASTERDATA_VIEW, MASTERDATA_EDIT, USER_MANAGE,
            VISITOR_REQUEST, VISITOR_APPROVE, VISITOR_REGISTER,
            CREDENTIAL_ISSUE, CREDENTIAL_OVERRIDE,
            REPORT_VIEW, REPORT_EXPORT, SETTINGS_MANAGE, AUDIT_VIEW);

    /**
     * Permissions that exist in the schema but are not authorised for use in this phase.
     *
     * <p>Named rather than merely absent from the grant matrix, so that a future grant operation can
     * refuse them with a reason instead of silently succeeding and creating an authority nobody
     * defined the rules for.
     */
    public static final Set<String> UNBACKED = Set.of(
            CREDENTIAL_OVERRIDE, REPORT_VIEW, REPORT_EXPORT);

    private Permissions() {
    }

    public static boolean isKnown(String code) {
        return code != null && ALL.contains(code);
    }
}
