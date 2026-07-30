/**
 * The tenant journey end to end in a browser (VJ-1).
 *
 * These are the checks that only a real browser can make, and two of them exist because the
 * corresponding CORS entries were missing until this milestone — server-side tests passed the
 * whole time:
 *
 * - the amend request actually goes out as **PATCH** (preflight must permit it);
 * - the list read sends **If-None-Match** and can read back **ETag** (neither is CORS-safelisted).
 *
 * The rest covers the area boundary: a tenant reaches the visit screens and nothing else, an
 * administrator reaches the console and not these, and the front door routes each to the right one.
 */
import { expect, test } from "@playwright/test";
import {
  CORS_HEADERS,
  NO_ACCESS_USER,
  SYSADMIN,
  TENANT_USER,
  VISIT_REQUEST_ID,
  mockApi,
  signIn,
} from "./fixtures/mock-api";

/**
 * Controls are addressed by **role and accessible name**, not by label text: a required field's
 * label reads "From *", where the asterisk is `aria-hidden` decoration. The accessible name is
 * "From" — what a screen reader announces, and therefore what a test should ask for.
 */

/**
 * The form's own error list. A bare `getByRole("alert")` is ambiguous: Next renders a
 * `role="alert"` route announcer into every page, so the selector must say which alert it means.
 */
function formErrors(page: import("@playwright/test").Page) {
  return page.locator('ul[role="alert"]');
}

test.describe("the front door routes by permission", () => {
  test("a tenant lands on the visit list, not the admin console", async ({ page }) => {
    await mockApi(page, TENANT_USER);
    await signIn(page, "**/visits");

    await expect(page.getByRole("heading", { level: 1, name: "My visit requests" })).toBeVisible();
    await expect(page.getByRole("navigation", { name: "Visits" })).toBeVisible();
  });

  test("an account granted nothing is told so, not bounced around", async ({ page }) => {
    await mockApi(page, NO_ACCESS_USER);
    await page.goto("/");

    await expect(page.getByRole("heading", { name: "You don’t have access" })).toBeVisible();
    await expect(page.getByText(NO_ACCESS_USER.username)).toBeVisible();
  });

  test("an administrator cannot enter the tenant area", async ({ page }) => {
    const api = await mockApi(page, SYSADMIN);
    await page.goto("/visits");

    // SYSTEM_ADMIN holds no visitor.request, so the shell refuses entry and no request is issued.
    await expect(page.getByRole("heading", { name: "You don’t have access" })).toBeVisible();
    expect(api.requests.filter((p) => p.startsWith("/api/v1/visitor-requests"))).toEqual([]);
  });

  test("a tenant cannot enter the admin area", async ({ page }) => {
    await mockApi(page, TENANT_USER);
    await page.goto("/admin");
    await expect(page.getByRole("heading", { name: "You don’t have access" })).toBeVisible();
  });
});

test.describe("submitting a request", () => {
  test("the form submits and lands on the new request", async ({ page }) => {
    await mockApi(page, TENANT_USER);
    await page.goto("/visits/new");

    await page.getByRole("textbox", { name: "From" }).fill("2099-03-01T09:00");
    await page.getByRole("textbox", { name: "To" }).fill("2099-03-01T17:00");
    await page.getByRole("textbox", { name: "Purpose" }).fill("Quarterly audit");
    await page.getByRole("textbox", { name: "Full name" }).fill("Ada Lovelace");

    await page.getByRole("button", { name: "Submit request" }).click();

    await page.waitForURL(`**/visits/${VISIT_REQUEST_ID}`);
    await expect(page.getByRole("heading", { level: 1, name: "Visit request" })).toBeVisible();
  });

  test("client-side checks name the offending visitor by position", async ({ page }) => {
    const api = await mockApi(page, TENANT_USER);
    await page.goto("/visits/new");

    await page.getByRole("textbox", { name: "From" }).fill("2099-03-01T09:00");
    await page.getByRole("textbox", { name: "To" }).fill("2099-03-01T17:00");
    await page.getByRole("textbox", { name: "Full name" }).fill("Ada Lovelace");
    await page.getByRole("textbox", { name: "Email" }).fill("not-an-email");
    await page.getByRole("button", { name: "Submit request" }).click();

    await expect(formErrors(page)).toContainText("Visitor 1");
    // Caught before the round trip: the API's own error cannot say which row it came from.
    expect(api.requests.filter((p) => p === "/api/v1/visitor-requests")).toEqual([]);
  });

  test("an inverted window is refused before it reaches the API", async ({ page }) => {
    const api = await mockApi(page, TENANT_USER);
    await page.goto("/visits/new");

    await page.getByRole("textbox", { name: "From" }).fill("2099-03-01T17:00");
    await page.getByRole("textbox", { name: "To" }).fill("2099-03-01T09:00");
    await page.getByRole("textbox", { name: "Full name" }).fill("Ada Lovelace");
    await page.getByRole("button", { name: "Submit request" }).click();

    await expect(formErrors(page)).toContainText("must end after it starts");
    expect(api.requests.filter((p) => p === "/api/v1/visitor-requests")).toEqual([]);
  });
});

test.describe("the conditional GET (US-07.6.2) works from a browser", () => {
  test("the list reads the ETag and sends it back as If-None-Match", async ({ page }) => {
    const sent: (string | null)[] = [];
    await mockApi(page, TENANT_USER);
    // Registered after mockApi on purpose: Playwright consults the most recently added handler
    // first, so a specific route registered before the catch-all would never run.
    await page.route("**/api/v1/visitor-requests*", async (route, request) => {
      sent.push(request.headers()["if-none-match"] ?? null);
      await route.fulfill({
        status: 200,
        headers: {
          ...CORS_HEADERS,
          "Content-Type": "application/json",
          ETag: 'W/"0123456789abcdef"',
        },
        body: JSON.stringify({
          content: [],
          totalElements: 0,
          page: 0,
          size: 20,
          maxSize: 100,
        }),
      });
    });
    await page.goto("/visits");
    await expect(page.getByRole("heading", { level: 1, name: "My visit requests" })).toBeVisible();

    await page.getByRole("button", { name: "Check for updates" }).click();
    await expect(page.getByText(/Checked for updates at/)).toBeVisible();

    // The first read has no validator; the second must carry the one the first returned. Reading
    // ETag at all requires the API to expose it — an unexposed header is invisible to page JS.
    await expect.poll(() => sent.length).toBeGreaterThanOrEqual(2);
    expect(sent[0]).toBeNull();
    expect(sent[sent.length - 1]).toBe('W/"0123456789abcdef"');
  });

  test("a 304 leaves the rendered rows in place", async ({ page }) => {
    let calls = 0;
    await mockApi(page, TENANT_USER);
    await page.route("**/api/v1/visitor-requests*", async (route) => {
      calls++;
      if (calls === 1) {
        return route.fulfill({
          status: 200,
          headers: {
            ...CORS_HEADERS,
            "Content-Type": "application/json",
            ETag: 'W/"aaaaaaaaaaaaaaaa"',
          },
          body: JSON.stringify({
            content: [
              {
                id: VISIT_REQUEST_ID,
                status: "submitted",
                host: null,
                scheduledFrom: "2099-03-01T09:00:00Z",
                scheduledTo: "2099-03-01T17:00:00Z",
                visitorCount: 1,
                submittedAt: "2099-02-01T10:00:00Z",
                decidedAt: null,
                decisionReason: null,
              },
            ],
            totalElements: 1,
            page: 0,
            size: 20,
            maxSize: 100,
          }),
        });
      }
      // Unchanged: no body at all, which is the saving the mechanism exists for.
      return route.fulfill({
        status: 304,
        headers: { ...CORS_HEADERS, ETag: 'W/"aaaaaaaaaaaaaaaa"' },
        body: "",
      });
    });
    await page.goto("/visits");
    await expect(page.getByText("1 request")).toBeVisible();

    await page.getByRole("button", { name: "Check for updates" }).click();

    // Still there — a 304 must not blank the list it just revalidated.
    await expect(page.getByText("1 request")).toBeVisible();
    await expect(page.getByRole("link", { name: /2099/ })).toBeVisible();
  });
});

test.describe("amending (US-07.1.3) reaches the API as PATCH", () => {
  test("the amend dialog issues a PATCH and leaves visitors alone by default", async ({ page }) => {
    const patches: { method: string; body: string }[] = [];
    await mockApi(page, TENANT_USER);
    await page.route(`**/api/v1/visitor-requests/${VISIT_REQUEST_ID}`, async (route, request) => {
      if (request.method() === "PATCH") {
        patches.push({ method: request.method(), body: request.postData() ?? "" });
        return route.fulfill({
          status: 200,
          headers: { ...CORS_HEADERS, "Content-Type": "application/json" },
          body: JSON.stringify({ id: VISIT_REQUEST_ID, status: "submitted" }),
        });
      }
      return route.fallback();
    });
    await page.goto(`/visits/${VISIT_REQUEST_ID}`);

    await page.getByRole("button", { name: "Amend request" }).click();
    await expect(page.getByRole("dialog", { name: "Amend visit request" })).toBeVisible();
    await page.getByRole("textbox", { name: "To" }).fill("2099-03-01T18:00");
    await page.getByRole("button", { name: "Save changes" }).click();

    await expect.poll(() => patches.length).toBe(1);
    expect(patches[0].method).toBe("PATCH");
    const body = JSON.parse(patches[0].body);
    expect(body.scheduledTo).toBeTruthy();
    // Omitted, not empty: sending the array would replace the list and wipe contact details the
    // detail response never returned (TODO-13).
    expect(body).not.toHaveProperty("visitors");
  });

  test("replacing the visitor list is explicit and states the consequence", async ({ page }) => {
    await mockApi(page, TENANT_USER);
    await page.goto(`/visits/${VISIT_REQUEST_ID}`);
    await page.getByRole("button", { name: "Amend request" }).click();

    const dialog = page.getByRole("dialog", { name: "Amend visit request" });
    // Not offered by default — the editor only appears once the choice is made.
    await expect(dialog.getByRole("textbox", { name: "Full name" })).toHaveCount(0);

    await dialog.getByRole("checkbox").check();
    await expect(dialog.getByRole("textbox", { name: "Full name" })).toHaveValue("Ada Lovelace");
    await expect(dialog).toContainText("re-enter every visitor in full");
    // Names come back; contact details cannot, so they start empty rather than looking preserved.
    await expect(dialog.getByRole("textbox", { name: "Email" })).toHaveValue("");
  });
});

test.describe("withdrawing", () => {
  test("the confirm names the window and the visitor count", async ({ page }) => {
    await mockApi(page, TENANT_USER);
    await page.goto(`/visits/${VISIT_REQUEST_ID}`);

    await page.getByRole("button", { name: "Withdraw request" }).click();
    const dialog = page.getByRole("dialog", { name: "Withdraw visit request" });
    await expect(dialog).toContainText("2099");
    await expect(dialog).toContainText("1 visitor");
    // Destructive: Cancel holds the focus, so an early Enter keeps the request.
    await expect(dialog.getByRole("button", { name: "Cancel" })).toBeFocused();
  });
});
