import { NextResponse } from "next/server";
import { clearRefreshCookie } from "@/lib/session-cookie";

/**
 * Clears the refresh cookie (US-06.3.1 AC-4, T-06.3.1.3).
 *
 * Deliberately the second half of logout. The client calls the API's /auth/logout first with its
 * bearer token — server-side session revocation is the security event, and it belongs on the
 * system of record — then this, so the browser stops holding a refresh token for a session that
 * no longer exists. This handler never talks to the API: a cookie clear must succeed even when
 * the API is unreachable, or a user could not sign out of a browser during an outage.
 */
export async function POST() {
  const response = NextResponse.json({ ok: true });
  clearRefreshCookie(response);
  return response;
}
