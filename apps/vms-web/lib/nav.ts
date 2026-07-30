/**
 * The admin navigation registry (US-06.3.2, T-06.3.2.2).
 *
 * Navigation is data, not markup: each destination declares the permissions that justify showing
 * it, and {@link visibleNavItems} is the single filter the shell renders through. An unheld item
 * is absent — never rendered disabled (AC-1): a greyed-out entry is a catalogue of what the
 * system can do handed to whoever is looking over the receptionist's shoulder.
 *
 * None of this is an authorization decision. Every route's API calls are enforced server-side;
 * hiding is usability, and the access-denied state exists precisely because a URL can be typed.
 */
import { PERMISSIONS, type Permission, hasAny } from "./permissions";

export type NavItem = {
  href: string;
  label: string;
  /** Any-of, matching the server's `@RequiresPermission` semantics. */
  requires: readonly Permission[];
};

/**
 * What justifies entering the admin area at all — the union of what its screens require.
 * A principal holding none of these (e.g. a TENANT with only `visitor.request`) gets the
 * explicit no-access state, not an empty shell (AC-5).
 */
export const ADMIN_ENTRY: readonly Permission[] = [
  PERMISSIONS.USER_MANAGE,
  PERMISSIONS.SETTINGS_MANAGE,
  PERMISSIONS.MASTERDATA_VIEW,
];

/**
 * Destinations appear here as their screens ship (UI-4a/4b add settings, master data, users,
 * roles). Adding an entry is: href, label, and the permission codes its screen's API needs.
 */
export const ADMIN_NAV: readonly NavItem[] = [
  { href: "/admin", label: "Overview", requires: ADMIN_ENTRY },
];

/** The one filter the shell renders navigation through. */
export function visibleNavItems(
  items: readonly NavItem[],
  held: readonly string[] | null | undefined,
): NavItem[] {
  return items.filter((item) => hasAny(held, item.requires));
}
