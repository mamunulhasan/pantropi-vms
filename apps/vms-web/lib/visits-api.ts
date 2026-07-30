/**
 * Typed client for the tenant side of the visitor journey (VJ-1, over US-07.1.1/07.1.3/07.6.x).
 *
 * Transcribed from `VisitorRequestController`. Three properties of that contract shape everything
 * here, and each one is a trap if forgotten:
 *
 * - **The envelope is `{content, totalElements, page, size, maxSize}`** — the visitor endpoints'
 *   own shape, not the master-data `{items, total}` one. Separate types, deliberately.
 * - **Errors are `{error, detail}`** on the business paths and RFC 7807 problem+json on the
 *   auth/routing ones, with an unknown id answering **404 and an empty body**. {@link visitError}
 *   absorbs all three.
 * - **`visitors` on an amend replaces the whole list.** Omitting the field leaves it alone. There
 *   is no per-visitor edit, and the detail response does not return visitor emails or phones
 *   (TODO-13), so rebuilding the array from a detail view would silently erase them — which is why
 *   {@link VisitsApi.amend} takes the array only when the caller means to replace it.
 */
import { apiFetch, apiFetchConditional, ApiError, type Conditional } from "./api";

/** The API's own statuses, spelled as the database spells them. */
export const REQUEST_STATUSES = ["submitted", "approved", "rejected", "cancelled"] as const;
export type RequestStatus = (typeof REQUEST_STATUSES)[number];

export const STATUS_LABELS: Record<RequestStatus, string> = {
  submitted: "Awaiting decision",
  approved: "Approved",
  rejected: "Rejected",
  cancelled: "Cancelled",
};

export type VisitorPayload = {
  fullName: string;
  email?: string | null;
  phone?: string | null;
  company?: string | null;
  visitorTypeId?: string | null;
};

export type SubmitRequest = {
  hostId?: string | null;
  scheduledFrom: string;
  scheduledTo: string;
  purpose?: string | null;
  visitors: VisitorPayload[];
};

export type MyRequestRow = {
  id: string;
  status: RequestStatus;
  /** The host's display name, or null when the request names no host. */
  host: string | null;
  scheduledFrom: string;
  scheduledTo: string;
  visitorCount: number;
  submittedAt: string;
  decidedAt: string | null;
  decisionReason: string | null;
};

export type MyRequestsPage = {
  content: MyRequestRow[];
  totalElements: number;
  page: number;
  size: number;
  maxSize: number;
};

export type VisitorLine = {
  fullName: string;
  company: string | null;
  /** The visitor type's display name, not its id. Null when unset. */
  visitorType: string | null;
  status: string;
};

export type MyRequestDetail = {
  id: string;
  status: RequestStatus;
  host: string | null;
  purpose: string | null;
  scheduledFrom: string;
  scheduledTo: string;
  submittedAt: string;
  /** The approver's display name — never an id or a username. Null while undecided. */
  decidedBy: string | null;
  decidedAt: string | null;
  decisionReason: string | null;
  visitors: VisitorLine[];
};

export type AmendRequest = {
  scheduledFrom?: string;
  scheduledTo?: string;
  purpose?: string | null;
  /** Present only to replace the entire list; absent leaves the visitors untouched. */
  visitors?: VisitorPayload[];
};

export type ListFilters = {
  status?: RequestStatus;
  from?: string;
  to?: string;
  page?: number;
};

export function visitsQuery(filters: ListFilters): string {
  const q = new URLSearchParams();
  if (filters.status) q.set("status", filters.status);
  if (filters.from) q.set("from", filters.from);
  if (filters.to) q.set("to", filters.to);
  if (filters.page) q.set("page", String(filters.page));
  const s = q.toString();
  return s ? `?${s}` : "";
}

export const VisitsApi = {
  submit: (req: SubmitRequest) =>
    apiFetch<{ id: string; status: string }>("/api/v1/visitor-requests", {
      method: "POST",
      body: JSON.stringify(req),
    }),

  /**
   * The tenant's own requests, revalidated against the server's ETag (US-07.6.2). A 304 answers
   * `{modified: false}` and the caller keeps what it has — the point of the mechanism.
   */
  list: (filters: ListFilters, etag: string | null): Promise<Conditional<MyRequestsPage>> =>
    apiFetchConditional<MyRequestsPage>(`/api/v1/visitor-requests${visitsQuery(filters)}`, etag),

  get: (id: string) => apiFetch<MyRequestDetail>(`/api/v1/visitor-requests/${id}`),

  /** Legal only from `submitted`; anything else answers 409 `already_decided`. */
  amend: (id: string, req: AmendRequest) =>
    apiFetch<{ id: string; status: RequestStatus }>(`/api/v1/visitor-requests/${id}`, {
      method: "PATCH",
      body: JSON.stringify(req),
    }),

  /** Legal from `submitted` and `approved` — withdrawing an approval revokes credentials too. */
  cancel: (id: string) =>
    apiFetch<{ id: string; status: RequestStatus }>(`/api/v1/visitor-requests/${id}/cancel`, {
      method: "POST",
    }),
};

/**
 * The user-facing sentence for a failure from these endpoints.
 *
 * The API's `detail` is written to be shown — it names the offending field kind ("visitor email"),
 * or explains the conflict ("The request is approved and can no longer be decided"). What it never
 * carries is the submitted value, and this must not add one back.
 */
export function visitError(error: unknown): string {
  if (error instanceof ApiError) {
    const detail = error.problem?.["detail"];
    const code = error.problem?.["error"];

    if (code === "invalid" && typeof detail === "string") {
      return `Check the ${detail}.`;
    }
    if (typeof detail === "string" && detail.length > 0) {
      return detail;
    }
    if (error.status === 404) {
      // Foreign and never-existed are deliberately indistinguishable (US-03.2.2).
      return "This request is not available. It may have been removed.";
    }
    if (error.status === 409) {
      return "Someone else changed this request first. Reload to see where it stands.";
    }
  }
  return "Something went wrong. Try again.";
}

/** True when the failure means the request's state moved on and a reload is the answer. */
export function isStateConflict(error: unknown): boolean {
  return error instanceof ApiError && error.status === 409;
}
