/**
 * US-02.5.1 — the audit trail across every entity.
 *
 * The permission existed before anything served it: one route answered for one visitor request,
 * while every master-data edit, user import, settings change and authorization denial was written
 * and unreadable. These assert the two properties that make the screen trustworthy — it shows more
 * than visitor requests, and it is unreachable without the permission.
 */
import { expect, test } from "@playwright/test";
import { FM_USER, SYSADMIN, TENANT_USER, mockApi, signIn } from "./fixtures/mock-api";

test.describe("the audit log", () => {
  test("shows entries from more than one entity type, newest first", async ({ page }) => {
    await mockApi(page, SYSADMIN);
    await signIn(page);
    await page.goto("/admin/audit");

    await expect(page.getByRole("heading", { name: "Audit log" })).toBeVisible();

    const rows = page.getByRole("row");
    await expect(rows.filter({ hasText: "visitor_request.approve" })).toBeVisible();
    await expect(rows.filter({ hasText: "settings.update" })).toBeVisible();
  });

  test("an entry with no actor says so without claiming the account was deleted", async ({
    page,
  }) => {
    await mockApi(page, SYSADMIN);
    await signIn(page);
    await page.goto("/admin/audit");

    // user_id is ON DELETE SET NULL, so a null actor is either an entry that never had a signed-in
    // user — an authorization denial before authentication — or one whose account has since gone.
    // The row cannot tell them apart, so the cell must not assert either.
    await expect(page.getByText("Not recorded")).toBeVisible();
    await expect(page.getByText("Account removed")).toHaveCount(0);
  });

  test("the before/after projection is one click away, not shouted", async ({ page }) => {
    await mockApi(page, SYSADMIN);
    await signIn(page);
    await page.goto("/admin/audit");

    const first = page.getByRole("row").filter({ hasText: "visitor_request.approve" });
    await expect(first.getByText('{"status":"approved"}')).toBeHidden();
    await first.getByRole("group").first().getByText("Show").click();
    await expect(first.getByText('{"status":"approved"}')).toBeVisible();
  });

  test("filtering by action narrows the table and travels in the URL", async ({ page }) => {
    await mockApi(page, SYSADMIN);
    await signIn(page);
    await page.goto("/admin/audit");

    await page.getByLabel("Action").selectOption("settings.update");

    await expect(page).toHaveURL(/action=settings.update/);
    const rows = page.getByRole("row");
    await expect(rows.filter({ hasText: "settings.update" })).toBeVisible();
    await expect(rows.filter({ hasText: "visitor_request.approve" })).toHaveCount(0);
  });

  test("audit.view is required — an approver is refused and asks for nothing", async ({ page }) => {
    // FM_ADMIN can read one request's history; that never implied the whole log.
    const api = await mockApi(page, FM_USER);
    await signIn(page, "**/approvals");
    await page.goto("/admin/audit");

    await expect(page.getByRole("heading", { name: "You don’t have access" })).toBeVisible();
    // The guard sits above the screen, so no request is issued at all.
    expect(api.requests.filter((p) => p.startsWith("/api/v1/admin/audit"))).toEqual([]);
  });

  test("a tenant sees no audit destination in the navigation", async ({ page }) => {
    await mockApi(page, TENANT_USER);
    await signIn(page, "**/visits");

    // Unheld destinations are absent, never disabled.
    await expect(page.getByRole("link", { name: "Audit log" })).toHaveCount(0);
  });
});
