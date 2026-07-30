import { NextRequest, NextResponse } from "next/server";
import { API_URL_SERVER } from "@/lib/config";
import { setRefreshCookie } from "@/lib/session-cookie";

/**
 * Sign-in, through the auth-only BFF (US-06.3.1 AC-3, T-06.3.1.2 — design in ADR-0006).
 *
 * The API returns the refresh token in its response body, which page JS must never see. This
 * handler is where the two contracts meet: it forwards the credentials, keeps the refresh token
 * in an HttpOnly cookie scoped to /api/session, and passes only the access-token payload on.
 *
 * Every login failure is one neutral 401. The API already makes wrong-password, locked and
 * unknown-account indistinguishable (US-02.3.1 AC-5); this handler must not add a distinguishing
 * layer on top, so it never forwards the upstream body.
 */
export async function POST(request: NextRequest) {
  const body = await request.json().catch(() => null);
  if (!body?.username || !body?.password) {
    return NextResponse.json({ error: "invalid_request" }, { status: 400 });
  }

  const upstream = await fetch(`${API_URL_SERVER}/api/v1/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username: body.username, password: body.password }),
  });

  if (!upstream.ok) {
    return NextResponse.json({ error: "sign_in_failed" }, { status: 401 });
  }

  const token = (await upstream.json()) as {
    accessToken: string;
    expiresAt: string;
    refreshToken: string;
    mustChangePassword: boolean;
  };

  const response = NextResponse.json({
    accessToken: token.accessToken,
    expiresAt: token.expiresAt,
    mustChangePassword: token.mustChangePassword,
  });
  setRefreshCookie(response, token.refreshToken);
  return response;
}
