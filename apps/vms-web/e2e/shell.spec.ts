/**
 * UI-7 — the shell at two widths, and the "you are here" it never had.
 *
 * The rail was `w-56` at every viewport and marked nothing as current. Both are asserted here
 * because both are invisible to axe: a nav that does not say where you are passes every automated
 * check and still leaves a reader counting links, and a rail that overruns a 375px screen is a
 * layout fact no contrast ratio catches.
 *
 * The landmark assertions matter as much as the behaviour. The rail and the mobile panel are the
 * same `<nav>` element by design — two would be two navigation landmarks for a screen reader to
 * choose between, and the hidden one is the one that would rot.
 */
import { expect, test } from "@playwright/test";
import { SYSADMIN, TENANT_USER, mockApi, signIn } from "./fixtures/mock-api";

test.describe("the rail marks where you are", () => {
  test("the current destination is the only one marked, even with a sibling prefix", async ({
    page,
  }) => {
    await mockApi(page, TENANT_USER);
    await signIn(page, "**/visits");

    const nav = page.getByRole("navigation", { name: "Visits" });
    await expect(nav.getByRole("link", { name: "My visit requests" })).toHaveAttribute(
      "aria-current",
      "page",
    );

    // /visits/new starts with /visits/, so a plain prefix test would light up both. Longest match
    // wins, so the sibling takes over rather than joining.
    await page.goto("/visits/new");
    await expect(nav.getByRole("link", { name: "New request" })).toHaveAttribute(
      "aria-current",
      "page",
    );
    await expect(nav.getByRole("link", { name: "My visit requests" })).not.toHaveAttribute(
      "aria-current",
      "page",
    );
  });

  test("a nested page keeps its parent marked", async ({ page }) => {
    await mockApi(page, SYSADMIN);
    await signIn(page);

    await page.goto("/admin/buildings/b1111111-1111-1111-1111-111111111111/floors");
    const nav = page.getByRole("navigation", { name: "Administration" });
    // Losing the highlight on a nested route reads as having left the area.
    await expect(nav.getByRole("link", { name: "Configuration" })).toHaveAttribute(
      "aria-current",
      "page",
    );
  });
});

test.describe("the shell on a narrow screen", () => {
  test.use({ viewport: { width: 375, height: 812 } });

  test("the rail folds into a disclosure that opens, navigates and closes itself", async ({
    page,
  }) => {
    await mockApi(page, SYSADMIN);
    await signIn(page);

    const nav = page.getByRole("navigation", { name: "Administration" });
    const toggle = page.getByRole("button", { name: /Administration menu/ });

    // Folded away, but present in the DOM — one nav element in both shapes.
    await expect(toggle).toBeVisible();
    await expect(toggle).toHaveAttribute("aria-expanded", "false");
    await expect(nav).toBeHidden();

    await toggle.click();
    await expect(toggle).toHaveAttribute("aria-expanded", "true");
    await expect(nav).toBeVisible();

    await nav.getByRole("link", { name: "Settings" }).click();
    await expect(page).toHaveURL(/\/admin\/settings$/);
    // A tap that navigates must not leave the panel covering what it navigated to.
    await expect(nav).toBeHidden();
    await expect(toggle).toHaveAttribute("aria-expanded", "false");
  });

  test("there is still exactly one navigation landmark and one main", async ({ page }) => {
    await mockApi(page, SYSADMIN);
    await signIn(page);

    // Closed, the rail is hidden and so absent from the accessibility tree — which is the point.
    // Opened, there is exactly one of it: the rail and the panel are the same element.
    await page.getByRole("button", { name: /Administration menu/ }).click();
    await expect(page.getByRole("navigation", { name: "Administration" })).toHaveCount(1);
    await expect(page.getByRole("main")).toHaveCount(1);
  });

  test("the skip link still reaches the content past the disclosure", async ({ page }) => {
    await mockApi(page, SYSADMIN);
    await signIn(page);

    await page.keyboard.press("Tab");
    const skip = page.getByRole("link", { name: "Skip to content" });
    await expect(skip).toBeFocused();
    await skip.press("Enter");
    await expect(page.locator("#main-content")).toBeVisible();
  });
});
