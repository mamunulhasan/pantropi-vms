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
 * The eight configuration tables, as tabs across one screen (wireframe 7a).
 *
 * They were ten separate left-nav destinations until UI-6. The deck is right that they belong
 * together: an administrator setting a building up moves between floors, receptions and tenants in
 * one sitting, and a nav entry per table made each of those a round trip through the sidebar.
 *
 * Order is the order the data depends on itself — a floor needs its building, a reception and a
 * tenant need their floor — so working left to right is also working in the order that succeeds.
 *
 * Each tab still declares its own permission: a floor receptionist holding only `masterdata.view`
 * sees the master-data tabs and not Users or Roles, exactly as when they were nav items.
 */
export const CONFIG_TABS: readonly NavItem[] = [
  { href: "/admin/buildings", label: "Buildings", requires: [PERMISSIONS.MASTERDATA_VIEW] },
  { href: "/admin/receptions", label: "Receptions", requires: [PERMISSIONS.MASTERDATA_VIEW] },
  { href: "/admin/tenants", label: "Tenants", requires: [PERMISSIONS.MASTERDATA_VIEW] },
  { href: "/admin/visitor-types", label: "Visitor types", requires: [PERMISSIONS.MASTERDATA_VIEW] },
  { href: "/admin/pass-types", label: "Pass types", requires: [PERMISSIONS.MASTERDATA_VIEW] },
  { href: "/admin/holidays", label: "Holidays", requires: [PERMISSIONS.MASTERDATA_VIEW] },
  { href: "/admin/users", label: "Users", requires: [PERMISSIONS.USER_MANAGE] },
  { href: "/admin/roles", label: "Roles", requires: [PERMISSIONS.USER_MANAGE] },
];

/**
 * The sidebar is now three destinations, not ten: the console home, the tabbed configuration
 * area, and settings. The tables did not move — their URLs are unchanged — they simply stopped
 * each claiming a line in the sidebar.
 *
 * Configuration points at Buildings because that is the first tab and the root of the dependency
 * chain; the tab bar takes over from there.
 */
export const ADMIN_NAV: readonly NavItem[] = [
  // One name for one thing: the label, the page's h1 and the browser tab all say Administration.
  { href: "/admin", label: "Administration", requires: ADMIN_ENTRY },
  {
    href: "/admin/buildings",
    label: "Configuration",
    requires: [PERMISSIONS.MASTERDATA_VIEW, PERMISSIONS.USER_MANAGE],
  },
  // Viewing settings needs masterdata.view (the controller's class-level guard); the edit
  // affordance inside the screen additionally needs settings.manage.
  { href: "/admin/settings", label: "Settings", requires: [PERMISSIONS.MASTERDATA_VIEW] },
];

/**
 * What justifies entering the tenant area: `visitor.request`, the only permission the TENANT role
 * holds. Every screen behind it reads and writes the caller's *own* tenant's requests, and the API
 * scopes those queries itself (US-03.4.1) — this only decides whether to render the area.
 */
export const TENANT_ENTRY: readonly Permission[] = [PERMISSIONS.VISITOR_REQUEST];

export const TENANT_NAV: readonly NavItem[] = [
  { href: "/visits", label: "My visit requests", requires: TENANT_ENTRY },
  { href: "/visits/new", label: "New request", requires: TENANT_ENTRY },
];

/**
 * What justifies entering the facilities-management area: `visitor.approve`, held by FM_ADMIN and
 * MASTER_ADMIN. Its own route group rather than a corner of the admin console — the backlog's
 * group list predates the approval screens, and FM_ADMIN holds none of the admin permissions, so
 * the console's shell would refuse it entry (recorded in ADR-0006).
 */
export const FM_ENTRY: readonly Permission[] = [PERMISSIONS.VISITOR_APPROVE];

export const FM_NAV: readonly NavItem[] = [
  { href: "/approvals", label: "Approvals", requires: FM_ENTRY },
  // FM_ADMIN holds visitor.request as of V15, so the tenant request form admits them and no
  // separate screen is needed. Guarded on the permission rather than the area: if that grant is
  // ever rolled back, the entry disappears with it instead of leading to a 403.
  { href: "/visits/new", label: "New request", requires: [PERMISSIONS.VISITOR_REQUEST] },
];

/**
 * What justifies entering the reception desk: `visitor.register`, held by FLOOR_RECEPTIONIST.
 *
 * A receptionist also holds `masterdata.view` — they must read visitor types to register anyone —
 * which is why {@link homeFor} checks this area *before* the console: the console would otherwise
 * claim them on the strength of a permission they hold only in service of this screen.
 */
export const RECEPTION_ENTRY: readonly Permission[] = [PERMISSIONS.VISITOR_REGISTER];

export const RECEPTION_NAV: readonly NavItem[] = [
  { href: "/reception", label: "Pre-register", requires: RECEPTION_ENTRY },
];

/**
 * Where a signed-in principal belongs, from its permissions alone. Null when its grants open no
 * area at all.
 *
 * Both the front door and the post-sign-in redirect read this, so they cannot disagree — the bug
 * this replaces was a hardcoded "/admin" in each, which sent a tenant straight into the
 * no-access wall the moment a second area existed.
 */
export function homeFor(permissions: readonly string[] | null | undefined): string | null {
  // Order is most-specific-first among the areas a role actually works in. The desk comes before
  // the console: a floor receptionist holds masterdata.view only so they can read visitor types
  // while registering, and landing them in admin would answer the wrong question about their job.
  if (hasAny(permissions, RECEPTION_ENTRY)) {
    return "/reception";
  }
  if (hasAny(permissions, ADMIN_ENTRY)) {
    return "/admin";
  }
  if (hasAny(permissions, FM_ENTRY)) {
    return "/approvals";
  }
  if (hasAny(permissions, ADMIN_ENTRY)) {
    return "/admin";
  }
  if (hasAny(permissions, TENANT_ENTRY)) {
    return "/visits";
  }
  return null;
}

/** The one filter the shell renders navigation through. */
export function visibleNavItems(
  items: readonly NavItem[],
  held: readonly string[] | null | undefined,
): NavItem[] {
  return items.filter((item) => hasAny(held, item.requires));
}
