/**
 * EPIC-12 — the arrival desk.
 *
 * The assertions worth having here are about what the screen *refuses* to offer. A check-in control
 * that appears for someone whose visit was never approved is the shortcut this whole feature exists
 * to prevent, and it is exactly the sort of thing that looks fine in a screenshot.
 */
import { expect, test } from "@playwright/test";
import { HOST_NAME, RECEPTIONIST, TENANT_USER, mockApi, signIn } from "./fixtures/mock-api";

test.describe("the arrival desk", () => {
  test("lists who is due, with enough context to tell two people apart", async ({ page }) => {
    await mockApi(page, RECEPTIONIST);
    await signIn(page, "**/reception**");
    await page.goto("/reception/arrivals");

    await expect(page.getByRole("heading", { name: "Arrivals" })).toBeVisible();

    // AC-2: host, tenant and window on the row, so the right person is picked without opening each.
    const ada = page.getByRole("listitem").filter({ hasText: "Ada Lovelace" });
    await expect(ada).toContainText("Analytical Ltd");
    await expect(ada).toContainText(HOST_NAME);
    await expect(ada).toContainText("Appointed");
  });

  test("check in is offered only to the appointed visitor", async ({ page }) => {
    await mockApi(page, RECEPTIONIST);
    await signIn(page, "**/reception**");
    await page.goto("/reception/arrivals");

    const ada = page.getByRole("listitem").filter({ hasText: "Ada Lovelace" });
    const alan = page.getByRole("listitem").filter({ hasText: "Alan Turing" });

    await expect(ada.getByRole("button", { name: "Check in" })).toBeVisible();

    // AC-4, and T-12.1.2.2: absent, not disabled. A disabled control invites "why not, and who
    // can?"; an absent one plus the stated reason answers it.
    await expect(alan.getByRole("button", { name: "Check in" })).toHaveCount(0);
    await expect(alan).toContainText("This visit has not been approved yet.");
  });

  test("someone already checked in is offered check out, not a second arrival", async ({
    page,
  }) => {
    await mockApi(page, RECEPTIONIST);
    await signIn(page, "**/reception**");
    await page.goto("/reception/arrivals");

    const grace = page.getByRole("listitem").filter({ hasText: "Grace Hopper" });
    await expect(grace).toContainText("Already arrived");
    await expect(grace.getByRole("button", { name: "Check in" })).toHaveCount(0);
    await expect(grace.getByRole("button", { name: "Check out" })).toBeVisible();
  });

  test("checking in reports the time and reloads the list", async ({ page }) => {
    const api = await mockApi(page, RECEPTIONIST);
    await signIn(page, "**/reception**");
    await page.goto("/reception/arrivals");

    await page
      .getByRole("listitem")
      .filter({ hasText: "Ada Lovelace" })
      .getByRole("button", { name: "Check in" })
      .click();

    await expect(page.getByRole("status")).toContainText("checked in");
    expect(api.requests.some((p) => p.includes("/check-in"))).toBe(true);
  });

  test("an unreturned card is surfaced at check-out, and does not block it", async ({ page }) => {
    await mockApi(page, RECEPTIONIST);
    await signIn(page, "**/reception**");
    await page.goto("/reception/arrivals");

    await page
      .getByRole("listitem")
      .filter({ hasText: "Grace Hopper" })
      .getByRole("button", { name: "Check out" })
      .click();

    // AC-2 of US-12.2.2: the departure is recorded either way — the card is a reminder, not a gate.
    await expect(page.getByRole("status")).toContainText("checked out");
    const panel = page.getByRole("alert").filter({ hasText: "still holds" });
    await expect(panel).toContainText("RF-1042");
    await expect(panel).toContainText("The check-out is recorded");
  });

  test("a search that matches nobody points at walk-in rather than dead-ending", async ({
    page,
  }) => {
    await mockApi(page, RECEPTIONIST);
    await signIn(page, "**/reception**");
    await page.goto("/reception/arrivals");

    await page.getByRole("searchbox", { name: "Find a visitor" }).fill("Nobody At All");
    await page.getByRole("button", { name: "Search", exact: true }).click();

    // AC-4 of US-12.1.1.
    await expect(page.getByText("Nobody matches that")).toBeVisible();
    await expect(page.getByText(/register them as a walk-in/i)).toBeVisible();
  });

  test("visitor.register is required — a tenant is refused and asks for nothing", async ({
    page,
  }) => {
    const api = await mockApi(page, TENANT_USER);
    await signIn(page, "**/visits");
    await page.goto("/reception/arrivals");

    await expect(page.getByRole("heading", { name: "You don’t have access" })).toBeVisible();
    // The guard sits above the screen, so no visitor data is requested at all (AC-5).
    expect(api.requests.filter((p) => p.startsWith("/api/v1/arrivals"))).toEqual([]);
  });
});
