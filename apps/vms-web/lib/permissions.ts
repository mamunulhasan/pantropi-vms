/**
 * Permission codes, mirroring `Permissions.java` one for one (US-06.3.2).
 *
 * The same reasoning as the server constant class: a string literal at a call site can be
 * misspelled, and a misspelled code never matches any grant — the nav item just never appears,
 * for anyone, quietly. Referencing through this object makes the typo a compile error.
 *
 * The server's correspondence test guarantees `Permissions.java` ≡ `vms.permissions`; this file
 * is transcribed from that class, so drift is a review-visible diff on one file.
 */
export const PERMISSIONS = {
  MASTERDATA_VIEW: "masterdata.view",
  MASTERDATA_EDIT: "masterdata.edit",
  USER_MANAGE: "user.manage",
  VISITOR_REQUEST: "visitor.request",
  VISITOR_APPROVE: "visitor.approve",
  VISITOR_REGISTER: "visitor.register",
  CREDENTIAL_ISSUE: "credential.issue",
  CREDENTIAL_OVERRIDE: "credential.override",
  REPORT_VIEW: "report.view",
  REPORT_EXPORT: "report.export",
  SETTINGS_MANAGE: "settings.manage",
  AUDIT_VIEW: "audit.view",
} as const;

export type Permission = (typeof PERMISSIONS)[keyof typeof PERMISSIONS];

/**
 * Any-of check, matching the server's `@RequiresPermission` semantics — holding any one of the
 * required codes suffices.
 *
 * Fails closed: no session, an empty held list, or an empty requirement all answer false. An
 * empty requirement would mean "visible to everyone", and nothing in the admin area is that;
 * a route that genuinely needs no permission simply doesn't call this.
 */
export function hasAny(
  held: readonly string[] | null | undefined,
  required: readonly Permission[],
): boolean {
  if (!held || held.length === 0 || required.length === 0) {
    return false;
  }
  return required.some((code) => held.includes(code));
}
