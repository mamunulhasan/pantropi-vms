/**
 * The tabbed configuration area (UI-6, wireframe 7a).
 *
 * What matters here is that a layout refactor did not quietly change three things it easily
 * could: the URLs (a route group must not be a path segment), the permission filtering (a tab is
 * absent, never disabled), and the heading structure the a11y gate depends on.
 */
import { expect, test } from "@playwright/test";
import { RECEPTIONIST, SYSADMIN, mockApi } from "./fixtures/mock-api";

const BUILDING_ID = "b1111111-1111-1111-1111-111111111111";

test.describe("the tab bar", () => {
  test("keeps every table at the URL it already had", async ({ page }) => {
    await mockApi(page, SYSADMIN);
    // A route group is a grouping in the file tree, not a path segment. If (config) leaked into
    // the URLs, every bookmark and every link in the docs would break silently.
    for (const path of [
      "/admin/buildings",
      "/admin/receptions",
      "/admin/tenants",
      "/admin/visitor-types",
      "/admin/pass-types",
      "/admin/holidays",
      "/admin/users",
      "/admin/roles",
    ]) {
      await page.goto(path);
      await expect(page).toHaveURL(new RegExp(`${path}$`));
      await expect(page.getByRole("heading", { level: 1, name: "Configuration" })).toBeVisible();
    }
  });

  test("marks the current tab, and keeps a nested page on its parent's tab", async ({ page }) => {
    await mockApi(page, SYSADMIN);
    const tabs = page.getByRole("navigation", { name: "Configuration sections" });

    await page.goto("/admin/tenants");
    await expect(tabs.getByRole("link", { name: "Tenants" })).toHaveAttribute(
      "aria-current",
      "page",
    );
    await expect(tabs.getByRole("link", { name: "Buildings" })).not.toHaveAttribute(
      "aria-current",
      "page",
    );

    // Floors live under a building; losing the highlight there would read as having left the area.
    await page.goto(`/admin/buildings/${BUILDING_ID}/floors`);
    await expect(tabs.getByRole("link", { name: "Buildings" })).toHaveAttribute(
      "aria-current",
      "page",
    );
  });

  test("moves between tables without leaving the area", async ({ page }) => {
    await mockApi(page, SYSADMIN);
    await page.goto("/admin/buildings");

    const tabs = page.getByRole("navigation", { name: "Configuration sections" });
    await tabs.getByRole("link", { name: "Holidays" }).click();

    await expect(page).toHaveURL(/\/admin\/holidays$/);
    await expect(page.getByRole("heading", { level: 2, name: "Holiday calendar" })).toBeVisible();
    await expect(tabs).toBeVisible();
  });

  test("a tab whose permission is not held is absent, not disabled", async ({ page }) => {
    // A floor receptionist holds masterdata.view and not user.manage.
    await mockApi(page, RECEPTIONIST);
    await page.goto("/admin/buildings");

    const tabs = page.getByRole("navigation", { name: "Configuration sections" });
    await expect(tabs.getByRole("link", { name: "Buildings" })).toBeVisible();
    await expect(tabs.getByRole("link", { name: "Users" })).toHaveCount(0);
    await expect(tabs.getByRole("link", { name: "Roles" })).toHaveCount(0);
  });
});

test.describe("the sidebar", () => {
  test("is three destinations, with the tables behind Configuration", async ({ page }) => {
    await mockApi(page, SYSADMIN);
    await page.goto("/admin");

    const sidebar = page.getByRole("navigation", { name: "Administration" });
    await expect(sidebar.getByRole("link", { name: "Administration" })).toBeVisible();
    await expect(sidebar.getByRole("link", { name: "Configuration" })).toBeVisible();
    await expect(sidebar.getByRole("link", { name: "Settings" })).toBeVisible();
    // The ten-entry sidebar is what this replaced; the tables are reached through the tabs now.
    await expect(sidebar.getByRole("link", { name: "Holidays" })).toHaveCount(0);
    await expect(sidebar.getByRole("link", { name: "Pass types" })).toHaveCount(0);
  });

  test("the console home still offers every screen directly", async ({ page }) => {
    await mockApi(page, SYSADMIN);
    await page.goto("/admin");

    const main = page.getByRole("main");
    for (const label of ["Settings", "Buildings", "Tenants", "Holidays", "Users", "Roles"]) {
      await expect(main.getByRole("link", { name: label })).toBeVisible();
    }
  });
});
