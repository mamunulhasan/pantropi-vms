/**
 * The FM approval queue and its decisions (VJ-2, over US-07.3.x / US-07.4.x).
 *
 * Wireframe 04 (2a/2c) drives the shape. Three of its columns cannot be filled from this API, and
 * the screen states that rather than fetching around it:
 *
 * - **Visitor names.** The queue row carries `visitorCount` and no names — the store never selects
 *   them. Names live on the detail read, which *writes an audit row every time it is called*, so
 *   fetching one per row would inflate the audit trail by the size of the page on every render.
 * - **Credential type.** A request has no credential; the credential comes from a pass type at
 *   issuance, and nothing on a request carries one.
 * - **Bulk approve.** There is no bulk endpoint. Approving n requests would be n calls with n
 *   independent conflict outcomes — "approve all" would be a promise the API cannot keep.
 */
import { apiFetch, ApiError } from "./api";
import { type RequestStatus } from "./visits-api";

export type PendingRow = {
  id: string;
  /** Tenant display name — the row exposes no tenant id. */
  tenant: string | null;
  /** Host display name, null when the request names no host. */
  host: string | null;
  scheduledFrom: string;
  scheduledTo: string;
  visitorCount: number;
  submittedAt: string;
};

export type PendingPage = {
  content: PendingRow[];
  totalElements: number;
  page: number;
  size: number;
  maxSize: number;
};

export type QueueFilters = {
  /** Defaults to `submitted` server-side; asking for another status is how decided ones show. */
  status?: RequestStatus;
  from?: string;
  to?: string;
  /** Matches host name or any visitor name. Max 100 chars, enforced both sides. */
  search?: string;
  page?: number;
};

export const SEARCH_MAX = 100;

export function queueQuery(filters: QueueFilters): string {
  const q = new URLSearchParams();
  if (filters.status) q.set("status", filters.status);
  if (filters.from) q.set("from", filters.from);
  if (filters.to) q.set("to", filters.to);
  if (filters.search) q.set("search", filters.search);
  if (filters.page) q.set("page", String(filters.page));
  const s = q.toString();
  return s ? `?${s}` : "";
}

/** What became of one visitor's pass when the request was approved (US-09.1.2). */
export type IssuedPass = {
  visitorId: string;
  /**
   * `ISSUED` and `ALREADY_HELD` both mean the visitor has a working pass. `DEFERRED` means it is
   * recorded and waiting on a retry; `FAILED` means it will not arrive; `SKIPPED` means automatic
   * issuance is off. The last three are worth telling the approver about — they are the only person
   * who knows the visit is happening.
   */
  outcome: "ISSUED" | "ALREADY_HELD" | "DEFERRED" | "FAILED" | "SKIPPED";
};

export type Decision = {
  id: string;
  status: RequestStatus;
  decidedBy: string;
  decidedAt: string;
  note: string | null;
  /** Empty on a rejection, and on an approval that minted nothing. */
  issued: IssuedPass[];
};

/** The outcomes that mean "this visitor can get in". */
export function hasWorkingPass(pass: IssuedPass): boolean {
  return pass.outcome === "ISSUED" || pass.outcome === "ALREADY_HELD";
}

/** One line an approver can act on, or null when every pass is fine. */
export function issuanceWarning(issued: readonly IssuedPass[]): string | null {
  const deferred = issued.filter((p) => p.outcome === "DEFERRED").length;
  const failed = issued.filter((p) => p.outcome === "FAILED").length;
  const skipped = issued.filter((p) => p.outcome === "SKIPPED").length;

  if (failed > 0) {
    return `${failed} of ${issued.length} passes could not be issued. Those visitors have no pass — contact the security desk.`;
  }
  if (deferred > 0) {
    return `${deferred} of ${issued.length} passes are still pending with the access control system and will be retried.`;
  }
  if (skipped > 0) {
    return "Automatic issuance is switched off, so no passes were created.";
  }
  return null;
}

export const ApprovalsApi = {
  queue: (filters: QueueFilters) =>
    apiFetch<PendingPage>(`/api/v1/visitor-requests/pending${queueQuery(filters)}`),

  /** Note is optional and capped at 1000 characters. */
  approve: (id: string, note: string | null) =>
    apiFetch<Decision>(`/api/v1/visitor-requests/${id}/approve`, {
      method: "POST",
      body: JSON.stringify({ note }),
    }),

  /** The reason is mandatory — a blank one is refused with `reason_required`. */
  reject: (id: string, reason: string) =>
    apiFetch<Decision>(`/api/v1/visitor-requests/${id}/reject`, {
      method: "POST",
      body: JSON.stringify({ reason }),
    }),
};

/**
 * The sentence to show for a failed decision.
 *
 * The three that matter are distinct outcomes, not variations of "error":
 * - **409 `already_decided`** — somebody decided first. The queue is stale; reload.
 * - **422 `window_elapsed`** — the visit is in the past. Retrying will never work, and the API's
 *   own sentence explains why, so it is shown verbatim.
 * - **400 `reason_required`** — a rejection with no reason.
 */
export function decisionError(error: unknown): string {
  if (error instanceof ApiError) {
    const detail = error.problem?.["detail"];
    if (typeof detail === "string" && detail.length > 0) {
      return detail;
    }
    if (error.status === 404) {
      return "This request is no longer available.";
    }
  }
  return "The decision could not be recorded. Try again.";
}

/** True when the request moved on beneath us and the queue should be re-read. */
export function isStale(error: unknown): boolean {
  return error instanceof ApiError && error.problem?.["error"] === "already_decided";
}
