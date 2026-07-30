/**
 * Pre-registration at the reception desk (VJ-3, over US-08.1.1 / US-08.1.3).
 *
 * **The desk has no read-back path, and that shapes the whole screen.** There is no
 * `GET /api/v1/pre-registrations`, no `GET /{visitorId}`, and a FLOOR_RECEPTIONIST holds neither
 * `visitor.request` nor `visitor.approve` — so both visitor-request reads answer 403. The only
 * place a `visitorId` ever appears is the 201 body of the call that created it, and the amend and
 * cancel endpoints are keyed on exactly that id.
 *
 * So the screen keeps what this desk registered **in memory for the session**. Not a cache of
 * server state — there is no server state to read — but the only way US-08.1.3's endpoints are
 * reachable from a UI at all. It is labelled as session-scoped on screen, because it is.
 *
 * Two other API facts the screen is built around:
 * - **The tenant is derived, not chosen**, when the floor hosts exactly one. Only a multi-tenant
 *   floor answers `tenant_required`, and its message discloses the *count* and not the tenants.
 * - **The station comes from the caller**, never the request: the command carries no floor and no
 *   reception, so a desk cannot register a visit against someone else's floor by asking.
 */
import { apiFetch, ApiError } from "./api";

export type PreRegistrationRequest = {
  fullName: string;
  email?: string | null;
  phone?: string | null;
  company?: string | null;
  visitorTypeId?: string | null;
  hostId?: string | null;
  /** Only needed when the floor hosts more than one tenant. */
  tenantId?: string | null;
  purpose?: string | null;
  appointmentFrom: string;
  appointmentTo: string;
};

export type Registered = {
  requestId: string;
  visitorId: string;
  tenantId: string;
  receptionId: string;
};

/** Amend coalesces against stored values: an absent field is left alone. */
export type AmendPreRegistration = {
  fullName?: string;
  email?: string | null;
  company?: string | null;
  phone?: string | null;
  visitorTypeId?: string | null;
  appointmentFrom?: string;
  appointmentTo?: string;
};

export type Maintained = {
  requestId: string;
  visitorId: string;
  requestStatus: string;
  /** True when this was the last live visitor and the whole request went with them. */
  requestCancelled: boolean;
  /** True when a credential had been issued and its revocation was requested in the same transaction. */
  revocationRequested: boolean;
};

export const ReceptionApi = {
  register: (req: PreRegistrationRequest) =>
    apiFetch<Registered>("/api/v1/pre-registrations", {
      method: "POST",
      body: JSON.stringify(req),
    }),

  amend: (visitorId: string, req: AmendPreRegistration) =>
    apiFetch<Maintained>(`/api/v1/pre-registrations/${visitorId}`, {
      method: "PATCH",
      body: JSON.stringify(req),
    }),

  cancel: (visitorId: string) =>
    apiFetch<Maintained>(`/api/v1/pre-registrations/${visitorId}/cancel`, { method: "POST" }),
};

/** The API's `error` code for a failure, when it sent one. */
export function errorCode(error: unknown): string | null {
  if (error instanceof ApiError) {
    const code = error.problem?.["error"];
    return typeof code === "string" ? code : null;
  }
  return null;
}

/** True when the floor hosts several tenants and the desk must name one (TODO-20). */
export function needsTenantChoice(error: unknown): boolean {
  return errorCode(error) === "tenant_required";
}

/**
 * The sentence to show for a failed registration or change.
 *
 * Almost every one of these is written by the API to be read by the person at the desk — the
 * grace-period refusal names the configured window, the foreign-floor refusal says which rule it
 * broke, the not-editable refusal names the visitor's state. They are shown verbatim; only the
 * shapes that carry no message get one composed here.
 */
export function receptionError(error: unknown): string {
  if (error instanceof ApiError) {
    const detail = error.problem?.["detail"];
    if (typeof detail === "string" && detail.length > 0) {
      if (errorCode(error) === "invalid") {
        // `detail` is the field kind — "visitor email", "visitorTypeId", "hostId".
        return `Check the ${detail}.`;
      }
      return detail;
    }
    if (error.status === 404) {
      // Unknown and another floor's are deliberately indistinguishable (US-03.2.2).
      return "That visitor is not available from this desk.";
    }
  }
  return "Something went wrong. Try again.";
}
