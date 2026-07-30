import { NextRequest, NextResponse } from "next/server";
import { API_URL_SERVER, REFRESH_COOKIE } from "@/lib/config";
import { clearRefreshCookie, setRefreshCookie } from "@/lib/session-cookie";

/**
 * Token refresh, from the HttpOnly cookie (US-06.3.1 AC-2/AC-3).
 *
 * The browser cannot present the refresh token itself — it cannot read it. It POSTs here with no
 * body; the cookie arrives because this path is the one place it travels to, and the API's
 * rotation result goes back the same way: new refresh token into the cookie, access token into
 * the response body.
 *
 * A refused refresh clears the cookie. Leaving a dead token behind would make every future load
 * pay a doomed round-trip before showing the login screen.
 *
 * CSRF: SameSite=Strict means a cross-site POST arrives without the cookie and is a plain 401 —
 * and a successful forged refresh would hand the attacker nothing anyway, since the response goes
 * to the victim's own origin.
 */
export async function POST(request: NextRequest) {
  const refreshToken = request.cookies.get(REFRESH_COOKIE)?.value;
  if (!refreshToken) {
    return NextResponse.json({ error: "no_session" }, { status: 401 });
  }

  const upstream = await fetch(`${API_URL_SERVER}/api/v1/auth/refresh`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ refreshToken }),
  });

  if (!upstream.ok) {
    const response = NextResponse.json({ error: "session_expired" }, { status: 401 });
    clearRefreshCookie(response);
    return response;
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
