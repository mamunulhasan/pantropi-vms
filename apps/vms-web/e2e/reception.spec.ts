/**
 * The reception desk in a browser (VJ-3), following wireframe 05.
 *
 * The two behaviours worth testing end to end are the ones driven by API shapes rather than by
 * layout: the floor is never sent (the API derives it from the caller), and a multi-tenant floor
 * is discovered only by being refused — there is no endpoint that says so up front.
 */
import { expect, test } from "@playwright/test";

// "From" and "To" are matched exactly: role-name matching is substring-based, and "Full name"
// contains "to".
import { FM_USER, RECEPTIONIST, TENANT_USER, mockApi, signIn } from "./fixtures/mock-api";

test.describe("the reception area boundary", () => {
  test("a receptionist lands at the desk, not in the console", async ({ page }) => {
    await mockApi(page, RECEPTIONIST);
    await signIn(page, "**/reception");

    await expect(
      page.getByRole("heading", { level: 1, name: "Pre-register a visitor" }),
    ).toBeVisible();
    await expect(page.getByRole("navigation", { name: "Reception" })).toBeVisible();
  });

  test("everyone without visitor.register is refused the desk", async ({ page }) => {
    const api = await mockApi(page, TENANT_USER);
    await page.goto("/reception");
    await expect(page.getByRole("heading", { name: "You don’t have access" })).toBeVisible();
    expect(api.requests.filter((p) => p.startsWith("/api/v1/pre-registrations"))).toEqual([]);

    await mockApi(page, FM_USER);
    await page.goto("/reception");
    await expect(page.getByRole("heading", { name: "You don’t have access" })).toBeVisible();
  });
});

test.describe("pre-registering", () => {
  test("the request carries no floor or reception — the API derives the station", async ({ page }) => {
    const posts: string[] = [];
    await mockApi(page, RECEPTIONIST);
    await page.route("**/api/v1/pre-registrations", async (route, request) => {
      if (request.method() === "POST") {
        posts.push(request.postData() ?? "");
      }
      await route.fallback();
    });

    await page.goto("/reception");
    await page.getByRole("textbox", { name: "Full name" }).fill("Grace Lim");
    await page.getByRole("textbox", { name: "From", exact: true }).fill("2099-03-01T10:00");
    await page.getByRole("textbox", { name: "To", exact: true }).fill("2099-03-01T12:00");
    await page.getByRole("button", { name: "Pre-register visitor" }).click();

    await expect.poll(() => posts.length).toBe(1);
    const body = JSON.parse(posts[0]);
    // The desk sends the list; the flat field echoes the first person, which is what the API
    // ignores when `visitors` is present.
    expect(body.visitors).toHaveLength(1);
    expect(body.visitors[0].fullName).toBe("Grace Lim");
    expect(body.fullName).toBe("Grace Lim");
    // A desk cannot register against another floor by asking: the command has no such field.
    expect(body).not.toHaveProperty("floorId");
    expect(body).not.toHaveProperty("receptionId");
    // Nor can it capture an identity document — the API exposes no field, by design (TODO-13).
    expect(body).not.toHaveProperty("idDocumentRef");
  });

  test("a registration joins the session list, where it can be amended or cancelled", async ({ page }) => {
    await mockApi(page, RECEPTIONIST);
    await page.goto("/reception");

    await expect(page.getByText("Nothing registered yet")).toBeVisible();

    await page.getByRole("textbox", { name: "Full name" }).fill("Grace Lim");
    await page.getByRole("textbox", { name: "From", exact: true }).fill("2099-03-01T10:00");
    await page.getByRole("textbox", { name: "To", exact: true }).fill("2099-03-01T12:00");
    await page.getByRole("button", { name: "Pre-register visitor" }).click();

    const table = page.getByRole("table");
    await expect(table).toContainText("Grace Lim");
    // The only reason the list exists: these endpoints are keyed on an id the 201 alone carries.
    await expect(page.getByRole("button", { name: "Amend" })).toBeVisible();
    await expect(page.getByRole("button", { name: "Cancel" })).toBeVisible();
  });

  test("several people register under one visit, and each gets its own desk row", async ({
    page,
  }) => {
    const api = await mockApi(page, RECEPTIONIST);
    await signIn(page, "**/reception");
    await page.goto("/reception");

    await page.getByRole("textbox", { name: "Full name" }).first().fill("Grace Lim");
    await page.getByRole("textbox", { name: "Phone" }).first().fill("+8801710000001");
    await page.getByRole("button", { name: "Add another visitor" }).click();
    await page.getByRole("textbox", { name: "Full name" }).nth(1).fill("Ada Lovelace");
    await page.getByRole("textbox", { name: "Phone" }).nth(1).fill("+8801710000002");
    await page.getByRole("textbox", { name: "From", exact: true }).fill("2099-03-01T10:00");
    await page.getByRole("textbox", { name: "To", exact: true }).fill("2099-03-01T12:00");

    const post = page.waitForRequest(
      (r) => r.url().includes("/api/v1/pre-registrations") && r.method() === "POST",
    );
    await page.getByRole("button", { name: "Pre-register visitor" }).click();
    const body = JSON.parse((await post).postData() || "{}");

    // One request, one window, two people — that is what "under a single visit" means.
    expect(body.visitors).toHaveLength(2);
    expect(body.visitors.map((v: { fullName: string }) => v.fullName)).toEqual([
      "Grace Lim",
      "Ada Lovelace",
    ]);
    // The mobile number is carried for each of them: the desk calls the visitor, not the host.
    expect(body.visitors.map((v: { phone: string }) => v.phone)).toEqual([
      "+8801710000001",
      "+8801710000002",
    ]);
    expect(body.appointmentFrom).toBeTruthy();

    // Amend and cancel are per visitor, so each person needs a row of their own.
    await expect(page.getByRole("row", { name: /Grace Lim/ })).toBeVisible();
    await expect(page.getByRole("row", { name: /Ada Lovelace/ })).toBeVisible();
    expect(api.forbidden).toEqual([]);
  });

  test("the desk says the list is session-scoped rather than implying a day's record", async ({ page }) => {
    await mockApi(page, RECEPTIONIST);
    await page.goto("/reception");
    await expect(page.getByText(/no way to read pre-registrations back/)).toBeVisible();
  });

  test("a multi-tenant floor is discovered by refusal, then answered", async ({ page }) => {
    const posts: string[] = [];
    await mockApi(page, RECEPTIONIST);
    await page.route("**/api/v1/pre-registrations", async (route, request) => {
      if (request.method() === "POST") {
        posts.push(request.postData() ?? "");
      }
      await route.fallback();
    });

    await page.goto("/reception");
    // The stub refuses this name without a tenantId, exactly as a shared floor does.
    await page.getByRole("textbox", { name: "Full name" }).fill("Ambiguous Visitor");
    await page.getByRole("textbox", { name: "From", exact: true }).fill("2099-03-01T10:00");
    await page.getByRole("textbox", { name: "To", exact: true }).fill("2099-03-01T12:00");
    await page.getByRole("button", { name: "Pre-register visitor" }).click();

    const dialog = page.getByRole("dialog", { name: "Which tenant is this visit for?" });
    await expect(dialog).toBeVisible();
    // The refusal's own words — it discloses the count, never the tenants.
    await expect(dialog).toContainText("hosts 2 tenants");

    await dialog.getByRole("combobox", { name: "Tenant" }).selectOption({ index: 1 });
    await dialog.getByRole("button", { name: "Pre-register" }).click();

    await expect.poll(() => posts.length).toBe(2);
    expect(JSON.parse(posts[0]).tenantId).toBeNull();
    expect(JSON.parse(posts[1]).tenantId).toBeTruthy();
    await expect(page.getByRole("table")).toContainText("Ambiguous Visitor");
  });

  test("an inverted appointment is refused before it reaches the API", async ({ page }) => {
    const api = await mockApi(page, RECEPTIONIST);
    await page.goto("/reception");

    await page.getByRole("textbox", { name: "Full name" }).fill("Grace Lim");
    await page.getByRole("textbox", { name: "From", exact: true }).fill("2099-03-01T12:00");
    await page.getByRole("textbox", { name: "To", exact: true }).fill("2099-03-01T10:00");
    await page.getByRole("button", { name: "Pre-register visitor" }).click();

    await expect(page.locator('ul[role="alert"]')).toContainText("must end after it starts");
    expect(api.requests.filter((p) => p === "/api/v1/pre-registrations")).toEqual([]);
  });

  test("the grace-period refusal is shown as the API wrote it", async ({ page }) => {
    await mockApi(page, RECEPTIONIST);
    await page.route("**/api/v1/pre-registrations", async (route, request) => {
      if (request.method() !== "POST") return route.fallback();
      await route.fulfill({
        status: 422,
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          error: "appointment_in_past",
          detail: "The appointment starts more than 60 minutes in the past; check the date",
        }),
      });
    });

    await page.goto("/reception");
    await page.getByRole("textbox", { name: "Full name" }).fill("Late Arrival");
    await page.getByRole("textbox", { name: "From", exact: true }).fill("2020-03-01T10:00");
    await page.getByRole("textbox", { name: "To", exact: true }).fill("2020-03-01T12:00");
    await page.getByRole("button", { name: "Pre-register visitor" }).click();

    // The configured window is in the message; paraphrasing would lose it.
    await expect(page.locator('ul[role="alert"]')).toContainText("60 minutes in the past");
  });
});

test.describe("cancelling", () => {
  test("the confirm names the visitor, and the outcome reports what else happened", async ({ page }) => {
    await mockApi(page, RECEPTIONIST);
    await page.goto("/reception");

    await page.getByRole("textbox", { name: "Full name" }).fill("Grace Lim");
    await page.getByRole("textbox", { name: "From", exact: true }).fill("2099-03-01T10:00");
    await page.getByRole("textbox", { name: "To", exact: true }).fill("2099-03-01T12:00");
    await page.getByRole("button", { name: "Pre-register visitor" }).click();
    await expect(page.getByRole("table")).toContainText("Grace Lim");

    await page.getByRole("button", { name: "Cancel" }).click();
    const dialog = page.getByRole("dialog", { name: "Cancel pre-registration" });
    await expect(dialog).toContainText("Grace Lim");
    await expect(dialog.getByRole("button", { name: "Cancel", exact: true })).toBeFocused();

    await dialog.getByRole("button", { name: "Cancel visit" }).click();

    // The stub reports the request went with them; the desk is told rather than left guessing.
    await expect(page.getByRole("status")).toContainText("the visit was withdrawn");
    await expect(page.getByRole("table")).toContainText("Cancelled");
  });
});
