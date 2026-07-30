/**
 * US-06.3.1 — the session store's ACs, tested without a browser.
 *
 * AC-3 (memory only), AC-4 (logout clears everything), AC-6 (exactly one refresh in flight).
 * fetch is faked per test; the store is module state, reset explicitly.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  getAccessToken,
  getSnapshot,
  login,
  logout,
  refreshSession,
  resetForTests,
} from "@/lib/auth-store";

const SESSION = {
  accessToken: "access-1",
  expiresAt: "2030-01-01T00:10:00Z",
  mustChangePassword: false,
};

const ME = { userId: "u-1", username: "sysadmin", role: "SYSTEM_ADMIN" };

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

beforeEach(() => {
  resetForTests();
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe("login", () => {
  it("holds the session in memory and nothing in storage (AC-3)", async () => {
    vi.stubGlobal("fetch", vi.fn(async (url: RequestInfo | URL) => {
      const path = String(url);
      if (path.includes("/api/session/login")) return jsonResponse(SESSION);
      if (path.includes("/api/v1/auth/me")) return jsonResponse(ME);
      throw new Error(`unexpected fetch ${path}`);
    }));

    const session = await login("sysadmin", "pw");

    expect(session?.accessToken).toBe("access-1");
    expect(getAccessToken()).toBe("access-1");
    expect(getSnapshot().me?.username).toBe("sysadmin");
    // AC-3: the token is nowhere the next page load could read it.
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
    expect(document.cookie).toBe(""); // the refresh cookie is HttpOnly — jsdom must see nothing
  });

  it("answers null on refusal without keeping any state", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => jsonResponse({ error: "sign_in_failed" }, 401)));

    expect(await login("sysadmin", "wrong")).toBeNull();
    expect(getAccessToken()).toBeNull();
  });
});

describe("profile parsing (US-06.3.2)", () => {
  it("carries displayName and the permission list into the store", async () => {
    vi.stubGlobal("fetch", vi.fn(async (url: RequestInfo | URL) => {
      const path = String(url);
      if (path.includes("/api/session/login")) return jsonResponse(SESSION);
      if (path.includes("/api/v1/auth/me")) {
        return jsonResponse({ ...ME, displayName: "Sydney Admin",
          permissions: ["audit.view", "user.manage"] });
      }
      throw new Error(`unexpected fetch ${path}`);
    }));

    await login("sysadmin", "pw");

    expect(getSnapshot().me?.displayName).toBe("Sydney Admin");
    expect(getSnapshot().me?.permissions).toEqual(["audit.view", "user.manage"]);
  });

  it("fails closed on an older API payload: username as name, no permissions, no crash", async () => {
    vi.stubGlobal("fetch", vi.fn(async (url: RequestInfo | URL) => {
      const path = String(url);
      if (path.includes("/api/session/login")) return jsonResponse(SESSION);
      if (path.includes("/api/v1/auth/me")) return jsonResponse(ME); // no new fields
      throw new Error(`unexpected fetch ${path}`);
    }));

    await login("sysadmin", "pw");

    expect(getSnapshot().me?.displayName).toBe("sysadmin");
    expect(getSnapshot().me?.permissions).toEqual([]);
  });
});

describe("refresh single-flight (AC-6)", () => {
  it("collapses concurrent callers onto exactly one refresh request", async () => {
    let refreshCalls = 0;
    let release!: () => void;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });

    vi.stubGlobal("fetch", vi.fn(async (url: RequestInfo | URL) => {
      const path = String(url);
      if (path.includes("/api/session/refresh")) {
        refreshCalls++;
        await gate; // hold the first call open so the others must join it
        return jsonResponse(SESSION);
      }
      if (path.includes("/api/v1/auth/me")) return jsonResponse(ME);
      throw new Error(`unexpected fetch ${path}`);
    }));

    const results = Promise.all([refreshSession(), refreshSession(), refreshSession()]);
    release();

    expect(await results).toEqual([true, true, true]);
    expect(refreshCalls).toBe(1);
  });

  it("a refresh after the first settles is a new request, not a stale answer", async () => {
    let refreshCalls = 0;
    vi.stubGlobal("fetch", vi.fn(async (url: RequestInfo | URL) => {
      const path = String(url);
      if (path.includes("/api/session/refresh")) {
        refreshCalls++;
        return jsonResponse(SESSION);
      }
      if (path.includes("/api/v1/auth/me")) return jsonResponse(ME);
      throw new Error(`unexpected fetch ${path}`);
    }));

    await refreshSession();
    await refreshSession();

    expect(refreshCalls).toBe(2);
  });

  it("a refused refresh clears the session and reports failure", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => jsonResponse({ error: "session_expired" }, 401)));

    expect(await refreshSession()).toBe(false);
    expect(getAccessToken()).toBeNull();
    expect(getSnapshot().resuming).toBe(false);
  });
});

describe("logout (AC-4)", () => {
  it("revokes server-side first, then clears the cookie and every trace of client state", async () => {
    const calls: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (url: RequestInfo | URL) => {
      const path = String(url);
      calls.push(path);
      if (path.includes("/api/session/login")) return jsonResponse(SESSION);
      if (path.includes("/api/v1/auth/me")) return jsonResponse(ME);
      return jsonResponse({ ok: true });
    }));

    await login("sysadmin", "pw");
    await logout();

    expect(getAccessToken()).toBeNull();
    expect(getSnapshot().me).toBeNull();
    const revokeIndex = calls.findIndex((c) => c.includes("/api/v1/auth/logout"));
    const cookieIndex = calls.findIndex((c) => c.includes("/api/session/logout"));
    expect(revokeIndex).toBeGreaterThan(-1);
    expect(cookieIndex).toBeGreaterThan(revokeIndex); // revocation is the security event; it goes first
  });

  it("still clears client state when the API is unreachable", async () => {
    vi.stubGlobal("fetch", vi.fn(async (url: RequestInfo | URL) => {
      const path = String(url);
      if (path.includes("/api/session/login")) return jsonResponse(SESSION);
      if (path.includes("/api/v1/auth/me")) return jsonResponse(ME);
      if (path.includes("/api/v1/auth/logout")) throw new Error("network down");
      return jsonResponse({ ok: true });
    }));

    await login("sysadmin", "pw");
    await logout().catch(() => {});

    // The user asked to be signed out of this browser; a dead network must not pin them in.
    expect(getAccessToken()).toBeNull();
  });
});
