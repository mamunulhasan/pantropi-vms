/**
 * US-06.3.2 — permission-driven navigation, tested at the logic seam the shell renders through.
 *
 * AC-1: an unheld destination is absent — visibleNavItems returns a filtered list, and there is
 * nothing else for a "disabled entry" to render from. AC-5: an empty permission set fails closed.
 */
import { describe, expect, it } from "vitest";
import { ADMIN_ENTRY, ADMIN_NAV, visibleNavItems, type NavItem } from "@/lib/nav";
import { PERMISSIONS, hasAny } from "@/lib/permissions";

const NAV: readonly NavItem[] = [
  { href: "/admin/users", label: "Users", requires: [PERMISSIONS.USER_MANAGE] },
  { href: "/admin/settings", label: "Settings", requires: [PERMISSIONS.SETTINGS_MANAGE] },
  {
    href: "/admin/buildings",
    label: "Buildings",
    requires: [PERMISSIONS.MASTERDATA_VIEW, PERMISSIONS.MASTERDATA_EDIT],
  },
];

describe("hasAny", () => {
  it("is any-of, matching the server's @RequiresPermission semantics", () => {
    expect(hasAny(["masterdata.view"], [PERMISSIONS.MASTERDATA_VIEW, PERMISSIONS.MASTERDATA_EDIT]))
      .toBe(true);
    expect(hasAny(["masterdata.edit"], [PERMISSIONS.MASTERDATA_VIEW, PERMISSIONS.MASTERDATA_EDIT]))
      .toBe(true);
    expect(hasAny(["visitor.request"], [PERMISSIONS.USER_MANAGE])).toBe(false);
  });

  it("fails closed: no session, empty held set and empty requirement all answer false", () => {
    expect(hasAny(null, [PERMISSIONS.USER_MANAGE])).toBe(false);
    expect(hasAny(undefined, [PERMISSIONS.USER_MANAGE])).toBe(false);
    expect(hasAny([], [PERMISSIONS.USER_MANAGE])).toBe(false);
    expect(hasAny(["user.manage"], [])).toBe(false);
  });
});

describe("visibleNavItems (AC-1)", () => {
  it("keeps exactly the destinations the held permissions justify", () => {
    const visible = visibleNavItems(NAV, ["user.manage", "masterdata.view"]);
    expect(visible.map((i) => i.href)).toEqual(["/admin/users", "/admin/buildings"]);
  });

  it("an unheld destination is absent from the result — there is nothing to render disabled", () => {
    const visible = visibleNavItems(NAV, ["settings.manage"]);
    expect(visible.map((i) => i.label)).toEqual(["Settings"]);
    expect(visible.find((i) => i.label === "Users")).toBeUndefined();
  });

  it("a TENANT's permission set yields no admin navigation at all (AC-5)", () => {
    expect(visibleNavItems(NAV, ["visitor.request"])).toEqual([]);
    expect(visibleNavItems(ADMIN_NAV, ["visitor.request"])).toEqual([]);
  });

  it("an empty or missing permission set yields nothing", () => {
    expect(visibleNavItems(NAV, [])).toEqual([]);
    expect(visibleNavItems(NAV, undefined)).toEqual([]);
  });
});

describe("admin-area entry (AC-4/AC-5)", () => {
  it("any admin-facing permission grants entry", () => {
    expect(hasAny(["user.manage"], ADMIN_ENTRY)).toBe(true);
    expect(hasAny(["settings.manage"], ADMIN_ENTRY)).toBe(true);
    expect(hasAny(["masterdata.view"], ADMIN_ENTRY)).toBe(true);
  });

  it("a purely tenant- or reception-facing set is refused entry", () => {
    expect(hasAny(["visitor.request"], ADMIN_ENTRY)).toBe(false);
    expect(hasAny(["visitor.register", "credential.issue"], ADMIN_ENTRY)).toBe(false);
    expect(hasAny([], ADMIN_ENTRY)).toBe(false);
  });
});
