/**
 * The FM approval journey in a browser (VJ-2), following wireframe 04.
 *
 * The area boundary matters as much as the decisions here: FM_ADMIN holds `visitor.approve` and
 * nothing else, so it must reach the queue and be refused everywhere else — and the roles that can
 * reach everything else must be refused the queue.
 */
import { expect, test } from "@playwright/test";
import { FM_USER, SYSADMIN, TENANT_USER, mockApi, signIn } from "./fixtures/mock-api";

test.describe("the approvals area boundary", () => {
  test("an approver signs in and lands on the queue", async ({ page }) => {
    await mockApi(page, FM_USER);
    await signIn(page, "**/approvals");

    await expect(page.getByRole("heading", { level: 1, name: "Approvals" })).toBeVisible();
    await expect(page.getByRole("navigation", { name: "Facilities" })).toBeVisible();
    await expect(page.getByText(FM_USER.displayName)).toBeVisible();
  });

  test("an approver cannot enter the admin console or the tenant area", async ({ page }) => {
    const api = await mockApi(page, FM_USER);

    await page.goto("/admin");
    await expect(page.getByRole("heading", { name: "You don’t have access" })).toBeVisible();
    await page.goto("/visits");
    await expect(page.getByRole("heading", { name: "You don’t have access" })).toBeVisible();

    expect(api.requests.filter((p) => p.startsWith("/api/v1/admin"))).toEqual([]);
  });

  test("a tenant and a system administrator are both refused the queue", async ({ page }) => {
    const tenantApi = await mockApi(page, TENANT_USER);
    await page.goto("/approvals");
    await expect(page.getByRole("heading", { name: "You don’t have access" })).toBeVisible();
    // The guard sits above the screen, so the queue endpoint is never called.
    expect(tenantApi.requests.filter((p) => p.includes("/pending"))).toEqual([]);
  });

  test("a system administrator is refused too — approving is not an admin power", async ({ page }) => {
    await mockApi(page, SYSADMIN);
    await page.goto("/approvals");
    await expect(page.getByRole("heading", { name: "You don’t have access" })).toBeVisible();
  });
});

test.describe("the queue", () => {
  test("shows what the endpoint actually returns, and no invented columns", async ({ page }) => {
    await mockApi(page, FM_USER);
    await page.goto("/approvals");

    const table = page.getByRole("table");
    await expect(table).toContainText("Acme Corporation");
    await expect(table).toContainText("Not named"); // the request names no host
    // The row carries a count, not names — names are on the detail read only.
    await expect(page.getByRole("columnheader", { name: "Visitors" })).toBeVisible();
    await expect(page.getByRole("columnheader", { name: "Credential" })).toHaveCount(0);
    await expect(table).not.toContainText("Ada Lovelace");
  });

  test("the stat tiles report the queue's own totals", async ({ page }) => {
    await mockApi(page, FM_USER);
    await page.goto("/approvals");

    await expect(page.getByText("Awaiting decision")).toBeVisible();
    await expect(page.getByText("Arriving today")).toBeVisible();
    // Nothing can set a visitor on-site yet, so that tile is absent rather than showing a zero
    // that would read as "nobody is in the building".
    await expect(page.getByText("On-site now")).toHaveCount(0);
  });

  test("search writes the URL so a filtered queue can be shared", async ({ page }) => {
    await mockApi(page, FM_USER);
    await page.goto("/approvals");

    await page.getByRole("searchbox", { name: "Search visitor or host" }).fill("lovelace");
    await page.getByRole("button", { name: "Search" }).click();

    await expect(page).toHaveURL(/search=lovelace/);
  });
});

test.describe("deciding", () => {
  test("the drawer loads the request in full, including visitor names", async ({ page }) => {
    await mockApi(page, FM_USER);
    await page.goto("/approvals");
    await page.getByRole("button", { name: "Review" }).first().click();

    const dialog = page.getByRole("dialog", { name: "Review visitor request" });
    await expect(dialog).toBeVisible();
    // The detail read is what carries names — and the only reason it is deferred to opening.
    await expect(dialog).toContainText("Ada Lovelace");
    await expect(dialog).toContainText("Quarterly audit");
  });

  test("approving sends the optional note and reports back", async ({ page }) => {
    const posts: { url: string; body: string }[] = [];
    await mockApi(page, FM_USER);
    await page.route("**/api/v1/visitor-requests/*/approve", async (route, request) => {
      posts.push({ url: request.url(), body: request.postData() ?? "" });
      await route.fulfill({
        status: 200,
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ id: "x", status: "approved", decidedBy: "u", decidedAt: "t", note: null }),
      });
    });

    await page.goto("/approvals");
    await page.getByRole("button", { name: "Review" }).first().click();
    await page.getByRole("textbox", { name: "Approver note" }).fill("Cleared with security");
    await page.getByRole("button", { name: "Approve" }).click();

    await expect.poll(() => posts.length).toBe(1);
    expect(JSON.parse(posts[0].body).note).toBe("Cleared with security");
    await expect(page.getByRole("status")).toContainText("approved");
  });

  test("rejecting refuses to send a blank reason", async ({ page }) => {
    const posts: string[] = [];
    await mockApi(page, FM_USER);
    await page.route("**/api/v1/visitor-requests/*/reject", async (route) => {
      posts.push("sent");
      await route.fulfill({ status: 200, headers: { "Content-Type": "application/json" }, body: "{}" });
    });

    await page.goto("/approvals");
    await page.getByRole("button", { name: "Review" }).first().click();
    await page.getByRole("button", { name: "Reject…" }).click();
    await page.getByRole("button", { name: "Reject request" }).click();

    // The API refuses a blank reason; catching it here names the field instead of round-tripping.
    // Scoped to the dialog: Next renders its own role="alert" route announcer into every page.
    await expect(page.getByRole("dialog").getByRole("alert")).toContainText("must say why");
    expect(posts).toEqual([]);
  });

  test("a stale queue is reported, not silently retried", async ({ page }) => {
    await mockApi(page, FM_USER);
    await page.route("**/api/v1/visitor-requests/*/approve", async (route) =>
      route.fulfill({
        status: 409,
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          error: "already_decided",
          detail: "The request is approved and can no longer be decided",
        }),
      }),
    );

    await page.goto("/approvals");
    await page.getByRole("button", { name: "Review" }).first().click();
    await page.getByRole("button", { name: "Approve" }).click();

    // The drawer closes and the conflict is stated verbatim — someone decided first.
    await expect(page.getByRole("status")).toContainText("can no longer be decided");
    await expect(page.getByRole("dialog")).toHaveCount(0);
  });
});
