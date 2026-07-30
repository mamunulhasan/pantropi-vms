/**
 * The one way the browser talks to the API (US-06.3.1 AC-2/AC-5).
 *
 * Attaches the in-memory access token; on a 401, joins the single-flight refresh and retries the
 * request exactly once. A second 401 means the session is genuinely over — server-side revocation,
 * expiry past the refresh window — and the answer is one redirect to the login screen with the
 * current location preserved, never a retry loop.
 */
import { API_URL_BROWSER } from "./config";
import { getAccessToken, refreshSession } from "./auth-store";

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    /** The API's problem body, when it sent one: {error, detail} or {correlationId}. */
    public readonly problem: Record<string, unknown> | null,
  ) {
    super(`API ${status}`);
  }

  /** For the error components: something to quote at support, never the raw payload. */
  get correlationId(): string | null {
    const id = this.problem?.["correlationId"];
    return typeof id === "string" ? id : null;
  }

  get detail(): string | null {
    const detail = this.problem?.["detail"];
    return typeof detail === "string" ? detail : null;
  }
}

/** Guards against a redirect storm when many requests fail together: one navigation only. */
let redirectingToLogin = false;

function redirectToLogin(): void {
  if (redirectingToLogin || typeof window === "undefined") {
    return;
  }
  redirectingToLogin = true;
  const here = window.location.pathname + window.location.search;
  window.location.assign(`/login?next=${encodeURIComponent(here)}`);
}

async function rawFetch(path: string, init?: RequestInit): Promise<Response> {
  const token = getAccessToken();
  const headers = new Headers(init?.headers);
  if (token) {
    headers.set("Authorization", `Bearer ${token}`);
  }
  if (init?.body && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }
  return fetch(`${API_URL_BROWSER}${path}`, { ...init, headers });
}

/**
 * One request with authentication and at most one refresh-and-retry (US-06.3.1 AC-2/AC-5).
 *
 * Both {@link apiFetch} and {@link apiFetchConditional} go through here, so the session-recovery
 * behaviour cannot differ between a plain read and a conditional one.
 */
async function authedFetch(path: string, init?: RequestInit): Promise<Response> {
  const res = await rawFetch(path, init);
  if (res.status !== 401) {
    return res;
  }
  const refreshed = await refreshSession();
  if (!refreshed) {
    redirectToLogin();
    throw new ApiError(401, null);
  }
  const retried = await rawFetch(path, init);
  if (retried.status === 401) {
    // Refreshed and still refused: revoked mid-session (AC-5). One exit, no loop.
    redirectToLogin();
    throw new ApiError(401, null);
  }
  return retried;
}

/**
 * Fetch an API path with authentication, refresh-and-retry-once, and typed failure.
 *
 * @returns the parsed JSON body (or null for 204)
 * @throws ApiError for any non-2xx answer that survives the refresh path
 */
export async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await authedFetch(path, init);

  if (!res.ok) {
    const problem = await res.json().catch(() => null);
    throw new ApiError(res.status, problem);
  }
  if (res.status === 204) {
    return null as T;
  }
  return (await res.json()) as T;
}

/** What a conditional GET answers: fresh data with a validator, or "unchanged". */
export type Conditional<T> =
  | { modified: true; data: T; etag: string | null }
  | { modified: false };

/**
 * A conditional GET (US-07.6.2). Sends `If-None-Match` when given a validator and reports a 304
 * as `{modified: false}` rather than an error, so a caller can keep the data it already has.
 *
 * Both headers only work because CORS names them: `If-None-Match` is not a safelisted request
 * header and `ETag` is not a safelisted response header, so without the API allowing the first and
 * exposing the second this function would silently degrade to an unconditional fetch.
 */
export async function apiFetchConditional<T>(
  path: string,
  etag: string | null,
): Promise<Conditional<T>> {
  const headers = new Headers();
  if (etag) {
    headers.set("If-None-Match", etag);
  }
  const res = await authedFetch(path, { headers });

  if (res.status === 304) {
    return { modified: false };
  }
  if (!res.ok) {
    const problem = await res.json().catch(() => null);
    throw new ApiError(res.status, problem);
  }
  return { modified: true, data: (await res.json()) as T, etag: res.headers.get("ETag") };
}
