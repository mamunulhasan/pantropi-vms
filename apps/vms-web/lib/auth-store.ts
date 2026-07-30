/**
 * The client's session, held in memory and nowhere else (US-06.3.1 AC-3).
 *
 * There is deliberately no persistence: no localStorage, no sessionStorage, nothing in a URL.
 * The access token lives in this module for the lifetime of the page; continuity across reloads
 * comes from the HttpOnly refresh cookie, which only the BFF route handlers can read — page JS
 * asks {@link refreshSession} and receives a fresh access token, never the refresh token itself.
 *
 * Framework-free on purpose: every AC about this store (single-flight, memory-only, cleared on
 * logout) is testable without a DOM. React consumes it through `useAuth`, which subscribes via
 * {@link subscribe}/{@link getSnapshot}.
 */
import { API_URL_BROWSER } from "./config";

export type Me = {
  userId: string;
  username: string;
  role: string;
  displayName: string;
  /**
   * Sorted effective permission codes, resolved live by the API (US-06.3.2). The nav renders
   * from these; they are usability data, not an authorization decision — every call the nav
   * leads to is enforced server-side.
   */
  permissions: readonly string[];
};

export type AuthState = {
  /** null while signed out or before {@link resumeSession} has run. */
  accessToken: string | null;
  expiresAt: string | null;
  mustChangePassword: boolean;
  me: Me | null;
  /** True until the initial resume attempt settles, so guards can wait instead of flashing. */
  resuming: boolean;
};

let state: AuthState = {
  accessToken: null,
  expiresAt: null,
  mustChangePassword: false,
  me: null,
  resuming: true,
};

const listeners = new Set<() => void>();

function setState(next: Partial<AuthState>) {
  state = { ...state, ...next };
  listeners.forEach((l) => l());
}

export function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function getSnapshot(): AuthState {
  return state;
}

export function getAccessToken(): string | null {
  return state.accessToken;
}

// ---- session lifecycle ----

type SessionPayload = {
  accessToken: string;
  expiresAt: string;
  mustChangePassword: boolean;
};

async function loadMe(accessToken: string): Promise<Me | null> {
  const res = await fetch(`${API_URL_BROWSER}/api/v1/auth/me`, {
    headers: { Authorization: `Bearer ${accessToken}` },
  });
  if (!res.ok) {
    return null;
  }
  const body = (await res.json()) as {
    userId: string;
    username: string;
    role: string;
    displayName?: string;
    permissions?: string[];
  };
  // Fail closed on a payload from an older API: no permission list means no nav, not a crash.
  return {
    userId: body.userId,
    username: body.username,
    role: body.role,
    displayName: body.displayName ?? body.username,
    permissions: body.permissions ?? [],
  };
}

/**
 * Sign in. The BFF sets the refresh cookie; only the access-token payload reaches this code.
 *
 * @returns the session payload on success, or null on refusal — the caller shows one neutral
 *          message either way, because the API deliberately makes wrong-password, locked and
 *          unknown-account indistinguishable (US-02.3.1 AC-5) and the UI must not undo that.
 */
export async function login(username: string, password: string): Promise<SessionPayload | null> {
  const res = await fetch("/api/session/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password }),
  });
  if (!res.ok) {
    return null;
  }
  const session = (await res.json()) as SessionPayload;
  const me = await loadMe(session.accessToken);
  setState({ ...session, me, resuming: false });
  return session;
}

/**
 * Exactly one refresh in flight, however many callers ask (US-06.3.1 AC-6).
 *
 * Concurrent 401s all await the same promise; when it settles they either retry with the new
 * token or fail together. Without this, five parallel requests hitting an expired token would
 * fire five refreshes, four of which the API's rotation would refuse — a retry storm that looks
 * like an attack in the audit trail.
 */
let refreshInFlight: Promise<boolean> | null = null;

export function refreshSession(): Promise<boolean> {
  if (!refreshInFlight) {
    refreshInFlight = doRefresh().finally(() => {
      refreshInFlight = null;
    });
  }
  return refreshInFlight;
}

async function doRefresh(): Promise<boolean> {
  const res = await fetch("/api/session/refresh", { method: "POST" });
  if (!res.ok) {
    setState({ accessToken: null, expiresAt: null, mustChangePassword: false, me: null,
      resuming: false });
    return false;
  }
  const session = (await res.json()) as SessionPayload;
  const me = state.me ?? (await loadMe(session.accessToken));
  setState({ ...session, me, resuming: false });
  return true;
}

/**
 * One attempt to restore a session on first load, from the HttpOnly cookie if one exists.
 * Settles {@link AuthState.resuming} either way, so route guards know when to decide.
 */
export async function resumeSession(): Promise<void> {
  await refreshSession();
}

/**
 * Sign out (US-06.3.1 AC-4, T-06.3.1.3), in the order that matters:
 * first the API — revoking the server session is the security event — then the BFF cookie,
 * then every trace of client state. A failure revoking server-side still clears the client:
 * the user asked to be signed out here, and a dead network must not pin them signed in.
 */
export async function logout(): Promise<void> {
  const token = state.accessToken;
  try {
    if (token) {
      await fetch(`${API_URL_BROWSER}/api/v1/auth/logout`, {
        method: "POST",
        headers: { Authorization: `Bearer ${token}` },
      });
    }
  } finally {
    await fetch("/api/session/logout", { method: "POST" }).catch(() => {});
    setState({ accessToken: null, expiresAt: null, mustChangePassword: false, me: null,
      resuming: false });
  }
}

/** After a forced password change succeeds, the gate is lifted without a fresh login. */
export function clearMustChangePassword(): void {
  setState({ mustChangePassword: false });
}

/** Test seam: reset module state between tests. Not for application code. */
export function resetForTests(): void {
  state = { accessToken: null, expiresAt: null, mustChangePassword: false, me: null,
    resuming: true };
  refreshInFlight = null;
  listeners.clear();
}
