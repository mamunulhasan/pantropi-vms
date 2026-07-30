import type { NextResponse } from "next/server";
import { REFRESH_COOKIE, REFRESH_COOKIE_PATH } from "./config";

/**
 * The one definition of how the refresh cookie is written (US-06.3.1 AC-3, ADR-0006).
 *
 * Shared by the login and refresh handlers so rotation cannot drift from issuance — two copies of
 * these attributes is how one of them ends up without HttpOnly.
 */
export function setRefreshCookie(response: NextResponse, refreshToken: string): void {
  response.cookies.set(REFRESH_COOKIE, refreshToken, {
    httpOnly: true,
    // Secure in production; local dev runs plain http and some browsers refuse Secure cookies
    // off localhost. The AC's property — unreadable by page JS, never sent to another origin —
    // holds in both modes via httpOnly + sameSite + the path scope.
    secure: process.env.NODE_ENV === "production",
    sameSite: "strict",
    path: REFRESH_COOKIE_PATH,
    // The API owns real session expiry; this only stops the cookie outliving any plausible
    // refresh window.
    maxAge: 60 * 60 * 24 * 14,
  });
}

export function clearRefreshCookie(response: NextResponse): void {
  response.cookies.set(REFRESH_COOKIE, "", { path: REFRESH_COOKIE_PATH, maxAge: 0 });
}
