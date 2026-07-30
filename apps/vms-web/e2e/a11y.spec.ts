/**
 * The accessibility gate (UI-5, F-06.4 AC-1).
 *
 * Every route the portal ships is scanned with axe-core against WCAG 2.1 A/AA, and the job fails
 * on any **critical or serious** violation. Moderate and minor findings are reported in the run
 * output but do not fail: a gate that fires on contrast hints nobody has triaged gets switched
 * off within a month, and a gate people switch off protects nothing.
 *
 * WCAG 2.1 AA is provisional — the SRS names no standard (ADR-0006, mirroring the backlog's own
 * caveat). If the client names a different level, the tag list here is the one place to change.
 *
 * Dialogs are scanned open, not just closed: a modal's markup is where labelling and focus
 * problems actually live, and a scan of the page behind it would report a clean bill of health
 * for DOM the user never reaches.
 */
import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";
import {
  FM_USER,
  RECEPTIONIST,
  SYSADMIN,
  TENANT_USER,
  VISIT_REQUEST_ID,
  mockApi,
  signIn,
} from "./fixtures/mock-api";

const WCAG_21_AA = ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"];

/** The floor route needs a building id; the mock answers for this one. */
const BUILDING_ID = "b1111111-1111-1111-1111-111111111111";

const ADMIN_ROUTES: { path: string; heading: string }[] = [
  { path: "/admin", heading: "Administration" },
  { path: "/admin/settings", heading: "Settings" },
  { path: "/admin/buildings", heading: "Buildings" },
  { path: `/admin/buildings/${BUILDING_ID}/floors`, heading: "Floors" },
  { path: "/admin/tenants", heading: "Tenants" },
  { path: "/admin/receptions", heading: "Receptions" },
  { path: "/admin/visitor-types", heading: "Visitor types" },
  { path: "/admin/pass-types", heading: "Pass types" },
  { path: "/admin/holidays", heading: "Holiday calendar" },
  { path: "/admin/users", heading: "Users" },
  { path: "/admin/roles", heading: "Roles and permissions" },
];

/** The tenant area (VJ-1) — scanned as its own principal, since its shell renders different nav. */
const TENANT_ROUTES: { path: string; heading: string }[] = [
  { path: "/visits", heading: "My visit requests" },
  { path: "/visits/new", heading: "New visit request" },
  { path: `/visits/${VISIT_REQUEST_ID}`, heading: "Visit request" },
];

async function scan(page: Page, label: string) {
  const results = await new AxeBuilder({ page }).withTags(WCAG_21_AA).analyze();

  const blocking = results.violations.filter(
    (v) => v.impact === "critical" || v.impact === "serious",
  );
  const advisory = results.violations.filter(
    (v) => v.impact !== "critical" && v.impact !== "serious",
  );

  if (advisory.length > 0) {
    // Reported, deliberately not failed — see the file header.
    console.log(
      `[a11y advisory] ${label}: ${advisory.map((v) => `${v.id} (${v.impact})`).join(", ")}`,
    );
  }

  expect(
    blocking.map((v) => ({
      rule: v.id,
      impact: v.impact,
      help: v.help,
      nodes: v.nodes.map((n) => n.target.join(" ")),
    })),
    `axe found critical/serious violations on ${label}`,
  ).toEqual([]);
}

test.describe("unauthenticated routes", () => {
  test("login page has no critical or serious violations", async ({ page }) => {
    await mockApi(page);
    await page.goto("/login");
    await expect(page.getByRole("heading", { name: "Sign in" })).toBeVisible();
    await scan(page, "/login");
  });

  test("login page announces a failure through a live region", async ({ page }) => {
    // The neutral failure message must reach a screen reader, not only the sighted user.
    await mockApi(page);
    // After mockApi, so this narrower handler is the one that answers: Playwright consults the
    // most recently registered route first.
    await page.route("**/api/session/login", (route) =>
      route.fulfill({
        status: 401,
        contentType: "application/json",
        body: JSON.stringify({ error: "sign_in_failed" }),
      }),
    );
    await page.goto("/login");
    await page.getByLabel("Username").fill("sysadmin");
    await page.getByLabel("Password").fill("wrong-password");
    await page.getByRole("button", { name: "Sign in" }).click();

    // Not a bare getByRole("alert"): Next renders its own route announcer with that role, and
    // matching it instead would have let this pass while sign-in quietly succeeded.
    await expect(page.locator('p[role="alert"]')).toContainText("Sign-in failed");
    await expect(page).toHaveURL(/\/login/);
    await scan(page, "/login (failed sign-in)");
  });
});

test.describe("authenticated routes", () => {
  for (const route of ADMIN_ROUTES) {
    test(`${route.path} has no critical or serious violations`, async ({ page }) => {
      await mockApi(page, SYSADMIN);
      await page.goto(route.path);
      await expect(page.getByRole("heading", { level: 1, name: route.heading })).toBeVisible();
      await scan(page, route.path);
    });
  }

  for (const route of TENANT_ROUTES) {
    test(`${route.path} has no critical or serious violations`, async ({ page }) => {
      await mockApi(page, TENANT_USER);
      await page.goto(route.path);
      await expect(page.getByRole("heading", { level: 1, name: route.heading })).toBeVisible();
      await scan(page, route.path);
    });
  }

  test("/approvals has no critical or serious violations", async ({ page }) => {
    await mockApi(page, FM_USER);
    await page.goto("/approvals");
    await expect(page.getByRole("heading", { level: 1, name: "Approvals" })).toBeVisible();
    await scan(page, "/approvals");
  });

  test("/reception has no critical or serious violations", async ({ page }) => {
    await mockApi(page, RECEPTIONIST);
    await page.goto("/reception");
    await expect(
      page.getByRole("heading", { level: 1, name: "Pre-register a visitor" }),
    ).toBeVisible();
    await scan(page, "/reception");
  });

  test("change-password has no critical or serious violations", async ({ page }) => {
    await mockApi(page, SYSADMIN);
    await page.goto("/change-password");
    await expect(page.getByRole("heading", { name: "Change your password" })).toBeVisible();
    await scan(page, "/change-password");
  });
});

test.describe("dialogs", () => {
  test("a form dialog is labelled and clean while open", async ({ page }) => {
    await mockApi(page, SYSADMIN);
    await page.goto("/admin/buildings");
    await page.getByRole("button", { name: "New building" }).click();

    const dialog = page.getByRole("dialog", { name: "New building" });
    await expect(dialog).toBeVisible();
    await scan(page, "/admin/buildings (form dialog open)");
  });

  test("a destructive confirm dialog is labelled and clean while open", async ({ page }) => {
    await mockApi(page, SYSADMIN);
    await page.goto("/admin/buildings");
    await page.getByRole("button", { name: "Deactivate" }).first().click();

    await expect(page.getByRole("dialog", { name: "Deactivate building" })).toBeVisible();
    // The record must be named in the prompt, not just "are you sure".
    await expect(page.getByRole("dialog")).toContainText("Westgate Tower");
    await scan(page, "/admin/buildings (confirm dialog open)");
  });

  test("the approval review drawer is labelled and clean while open", async ({ page }) => {
    await mockApi(page, FM_USER);
    await page.goto("/approvals");
    await page.getByRole("button", { name: "Review" }).first().click();

    await expect(page.getByRole("dialog", { name: "Review visitor request" })).toBeVisible();
    await scan(page, "/approvals (review drawer open)");
  });

  test("the settings edit dialog is labelled and clean while open", async ({ page }) => {
    await mockApi(page, SYSADMIN);
    await page.goto("/admin/settings");
    await page.getByRole("button", { name: "Edit" }).first().click();

    await expect(page.getByRole("dialog")).toBeVisible();
    await scan(page, "/admin/settings (edit dialog open)");
  });
});

test.describe("landmarks, headings and keyboard entry", () => {
  test("every admin route has one h1, a main and a navigation landmark", async ({ page }) => {
    await mockApi(page, SYSADMIN);
    for (const route of ADMIN_ROUTES) {
      await page.goto(route.path);
      await expect(page.getByRole("heading", { level: 1, name: route.heading })).toBeVisible();
      // Exactly one h1: the screen's identity, not a decorative repeat of the brand.
      expect(await page.locator("h1").count(), `h1 count on ${route.path}`).toBe(1);
      await expect(page.getByRole("main")).toBeVisible();
      await expect(page.getByRole("navigation", { name: "Administration" })).toBeVisible();
    }
  });

  test("every tenant route has one h1, a main and a navigation landmark", async ({ page }) => {
    await mockApi(page, TENANT_USER);
    for (const route of TENANT_ROUTES) {
      await page.goto(route.path);
      await expect(page.getByRole("heading", { level: 1, name: route.heading })).toBeVisible();
      expect(await page.locator("h1").count(), `h1 count on ${route.path}`).toBe(1);
      await expect(page.getByRole("main")).toBeVisible();
      await expect(page.getByRole("navigation", { name: "Visits" })).toBeVisible();
    }
  });

  test("the first Tab reaches the skip link, and it jumps to main content", async ({ page }) => {
    await mockApi(page, SYSADMIN);
    await page.goto("/admin");
    await expect(page.getByRole("heading", { level: 1, name: "Administration" })).toBeVisible();

    await page.keyboard.press("Tab");
    const skip = page.getByRole("link", { name: "Skip to content" });
    await expect(skip).toBeFocused();
    // Focused, it must be visible — a skip link that stays clipped helps nobody.
    await expect(skip).toBeVisible();

    await skip.press("Enter");
    await expect(page.locator("#main-content")).toBeVisible();
  });

  test("signing in through the form lands on the console", async ({ page }) => {
    await mockApi(page, SYSADMIN);
    await signIn(page);
    await expect(page.getByRole("heading", { level: 1, name: "Administration" })).toBeVisible();
    // The header greets by display name, from the profile payload.
    await expect(page.getByText(SYSADMIN.displayName)).toBeVisible();
  });
});
