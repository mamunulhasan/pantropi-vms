/**
 * The client-tamper check (T-06.3.2.3, deferred from UI-2) and the two session behaviours only a
 * real browser can show (US-06.3.1 AC-3/AC-4).
 *
 * **What this proves and what it does not.** These tests establish that the portal cannot be
 * talked into revealing admin screens or data to a principal without the permissions: no
 * navigation, no rendered rows, and — the assertion that matters — *no data request is even
 * issued*, because the guard sits above the screen. They do not prove server-side enforcement;
 * the API here is a stub, and asserting a stub's refusal would be circular. Server-side
 * authorization is proven where it lives: `ApiAuthorizationIT` exercises the real interceptor
 * against a real database, including that an existing and a non-existent resource are
 * indistinguishable when denied.
 *
 * Hiding is usability. Enforcement is the API's. This file checks the first and points at the
 * second.
 */
import { expect, test } from "@playwright/test";
import { SYSADMIN, TENANT_USER, mockApi, signIn } from "./fixtures/mock-api";

test.describe("a principal with no admin permissions", () => {
  test("sees no admin navigation and the explicit no-access state", async ({ page }) => {
    await mockApi(page, TENANT_USER);
    await page.goto("/admin");

    await expect(page.getByRole("heading", { name: "You don’t have access" })).toBeVisible();
    // Absent, never disabled (US-06.3.2 AC-1): there is no greyed-out catalogue of capabilities.
    await expect(page.getByRole("link", { name: "Users" })).toHaveCount(0);
    await expect(page.getByRole("link", { name: "Settings" })).toHaveCount(0);
    await expect(page.getByRole("link", { name: "Buildings" })).toHaveCount(0);
    // Signing out must stay reachable from the dead end.
    await expect(page.getByRole("button", { name: "Sign out" })).toBeVisible();
  });

  test("a typed URL yields the no-access state and issues no data request", async ({ page }) => {
    const api = await mockApi(page, TENANT_USER);
    await page.goto("/admin/users");

    await expect(page.getByRole("heading", { name: "You don’t have access" })).toBeVisible();
    await expect(page.getByRole("table")).toHaveCount(0);
    await expect(page.getByText("jdoe")).toHaveCount(0);

    // The guard sits above the screen, so the users endpoint was never called at all.
    expect(api.requests.filter((p) => p.startsWith("/api/v1/admin"))).toEqual([]);
  });

  test("injecting a navigation link into the DOM reveals no data", async ({ page }) => {
    const api = await mockApi(page, TENANT_USER);
    await page.goto("/admin");
    await expect(page.getByRole("heading", { name: "You don’t have access" })).toBeVisible();

    // The tamper: add the link the nav filter withheld, then use it.
    await page.evaluate(() => {
      const a = document.createElement("a");
      a.href = "/admin/users";
      a.textContent = "Users";
      a.id = "tampered-link";
      document.body.prepend(a);
    });
    await page.click("#tampered-link");

    await expect(page.getByRole("heading", { name: "You don’t have access" })).toBeVisible();
    await expect(page.getByText("Jane Doe")).toHaveCount(0);
    expect(api.requests.filter((p) => p.startsWith("/api/v1/admin"))).toEqual([]);
  });

  test("granting the permission is what makes the screen appear", async ({ page }) => {
    // The mirror image of the tests above: the mechanism is permission-driven, not
    // always-hidden. Same code path, one different permission code.
    await mockApi(page, { ...TENANT_USER, permissions: ["user.manage"] });
    await page.goto("/admin/users");

    // Since UI-6 the tabbed area owns the h1; the table's own name is the section heading.
    await expect(page.getByRole("heading", { level: 1, name: "Configuration" })).toBeVisible();
    await expect(page.getByRole("heading", { level: 2, name: "Users" })).toBeVisible();
    await expect(page.getByText("Jane Doe")).toBeVisible();
    await expect(page.getByRole("link", { name: "Users" })).toBeVisible();
    // Still nothing it does not hold: the master-data tabs stay absent without masterdata.view.
    await expect(page.getByRole("link", { name: "Buildings" })).toHaveCount(0);
  });
});

test.describe("the session is not reachable from page scripts (US-06.3.1 AC-3)", () => {
  test("no storage and no readable cookie hold the token after signing in", async ({ page }) => {
    await mockApi(page, SYSADMIN);
    await signIn(page);
    await expect(page.getByRole("heading", { level: 1, name: "Administration" })).toBeVisible();

    const exposure = await page.evaluate(() => ({
      local: Object.entries(localStorage),
      session: Object.entries(sessionStorage),
      cookie: document.cookie,
    }));

    expect(exposure.local).toEqual([]);
    expect(exposure.session).toEqual([]);
    // The refresh cookie is HttpOnly and path-scoped to /api/session, so page JS sees nothing.
    expect(exposure.cookie).toBe("");
  });
});

test.describe("after signing out (US-06.3.1 AC-4)", () => {
  test("back-navigation shows nothing authenticated", async ({ page }) => {
    await mockApi(page, SYSADMIN);
    await signIn(page);
    await page.goto("/admin/users");
    await expect(page.getByRole("heading", { level: 2, name: "Users" })).toBeVisible();
    await expect(page.getByText("Jane Doe")).toBeVisible();

    await page.getByRole("button", { name: "Sign out" }).click();
    await expect(page.getByRole("heading", { name: "Sign in" })).toBeVisible();

    // A restored page must not render the console it was showing a moment ago.
    await page.goBack();
    await expect(page.getByText("Jane Doe")).toHaveCount(0);
    await expect(page.getByRole("heading", { level: 2, name: "Users" })).toHaveCount(0);
  });
});
