/**
 * Where the API lives, for each side of the portal.
 *
 * The browser calls the API directly (CORS already allows the portal origin); the auth BFF route
 * handlers call it server-side. Two variables because the two sides can legitimately differ —
 * a containerised portal reaches the API by service name while browsers use the public host.
 */
export const API_URL_BROWSER =
  process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8081";

/** Server-side only. Falls back to the browser value so local dev needs one setting or none. */
export const API_URL_SERVER = process.env.VMS_API_URL ?? API_URL_BROWSER;

/** The refresh-token cookie. Path-scoped so it only ever travels to the session endpoints. */
export const REFRESH_COOKIE = "vms_refresh";
export const REFRESH_COOKIE_PATH = "/api/session";
