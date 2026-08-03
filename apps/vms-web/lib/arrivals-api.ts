/**
 * The reception desk at the moment a visitor arrives (EPIC-12).
 *
 * This is the first read-back the desk has ever had. Pre-registration returns a visitor id exactly
 * once, in its 201 body, so until now the screen kept its list in page memory and said so — reload
 * and it was gone. `search` replaces that.
 */
import { apiFetch, ApiError } from "./api";

/** The one outcome a screen branches on; `mayCheckIn` is the derived boolean it should use. */
export type ConfirmationOutcome =
  | "APPOINTED"
  | "NOT_APPOINTED"
  | "EARLY"
  | "ELAPSED"
  | "ALREADY_ARRIVED";

export type Arrival = {
  visitorId: string;
  requestId: string;
  fullName: string;
  company: string | null;
  visitorType: string | null;
  /** Kept: a desk calls the visitor standing in front of it, not only the host. */
  phone: string | null;
  host: string | null;
  tenant: string | null;
  appointmentFrom: string | null;
  appointmentTo: string | null;
  requestStatus: string;
  visitorStatus: string;
  checkedInAt: string | null;
  checkedOutAt: string | null;
  outcome: ConfirmationOutcome;
  /** Plain language for the desk, or null when simply appointed. */
  reason: string | null;
  mayCheckIn: boolean;
};

export type OutstandingCard = {
  issuanceId: string;
  acsCardId: string;
  issuedAt: string | null;
};

export type Recorded = {
  visitorId: string;
  status: string;
  at: string;
  /** Empty on check-in. On check-out, what to ask for before the visitor leaves. */
  outstandingCards: OutstandingCard[];
};

export const ArrivalsApi = {
  /** Blank search lists who is due, rather than everyone who has ever visited. */
  search: (search: string) =>
    apiFetch<Arrival[]>(
      `/api/v1/arrivals${search.trim() ? `?search=${encodeURIComponent(search.trim())}` : ""}`,
    ),
  get: (visitorId: string) => apiFetch<Arrival>(`/api/v1/arrivals/${visitorId}`),
  checkIn: (visitorId: string) =>
    apiFetch<Recorded>(`/api/v1/arrivals/${visitorId}/check-in`, { method: "POST" }),
  checkOut: (visitorId: string) =>
    apiFetch<Recorded>(`/api/v1/arrivals/${visitorId}/check-out`, { method: "POST" }),
};

/** What the desk should read out. The API's own words where it has them. */
export function arrivalError(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return "That did not go through. Try again.";
  }
  // 422 not_appointed and 409 illegal_transition both carry a sentence written for this moment;
  // paraphrasing them here would put two versions of the same refusal into circulation.
  if (error.detail) {
    return error.detail;
  }
  if (error.status === 404) {
    return "That visitor is not available from this desk.";
  }
  return "That did not go through. Try again.";
}

/** True when the desk should reload rather than retry — someone else moved this visitor. */
export function isStale(error: unknown): boolean {
  return error instanceof ApiError && error.status === 409;
}

/** How each confirmation outcome should read and colour. */
export const OUTCOME_LABELS: Record<ConfirmationOutcome, string> = {
  APPOINTED: "Appointed",
  NOT_APPOINTED: "Not appointed",
  EARLY: "Early",
  ELAPSED: "Elapsed",
  ALREADY_ARRIVED: "Already arrived",
};
