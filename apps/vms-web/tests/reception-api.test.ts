/**
 * VJ-3 — the reception desk's error reading and the landing order it depends on.
 */
import { describe, expect, it } from "vitest";
import { ApiError } from "@/lib/api";
import { errorCode, needsTenantChoice, receptionError } from "@/lib/reception-api";
import { homeFor } from "@/lib/nav";

describe("needsTenantChoice", () => {
  it("recognises the multi-tenant floor refusal — the only way the UI learns of one", () => {
    const e = new ApiError(400, {
      error: "tenant_required",
      detail: "This floor hosts 2 tenants; say which the visit is for",
    });
    expect(needsTenantChoice(e)).toBe(true);
  });

  it("is false for every other refusal, including the foreign-floor 403", () => {
    expect(needsTenantChoice(new ApiError(403, { error: "foreign_floor" }))).toBe(false);
    expect(needsTenantChoice(new ApiError(409, { error: "station_unresolved" }))).toBe(false);
    expect(needsTenantChoice(new ApiError(404, null))).toBe(false);
    expect(needsTenantChoice(new Error("boom"))).toBe(false);
  });
});

describe("receptionError", () => {
  it("shows the grace-period refusal as written — it names the configured window", () => {
    const e = new ApiError(422, {
      error: "appointment_in_past",
      detail: "The appointment starts more than 60 minutes in the past; check the date",
    });
    expect(receptionError(e)).toContain("60 minutes in the past");
  });

  it("shows the foreign-floor refusal, which is deliberately not a 404", () => {
    const e = new ApiError(403, {
      error: "foreign_floor",
      detail: "That tenant is not on your floor",
    });
    expect(receptionError(e)).toBe("That tenant is not on your floor");
  });

  it("shows the not-editable conflict, which names the visitor's state", () => {
    const e = new ApiError(409, {
      error: "visitor_not_editable",
      detail: "Visitor abc is checked_in and cannot be changed from the pre-arrival workflow",
    });
    expect(receptionError(e)).toContain("checked_in");
  });

  it("turns a field-kind rejection into an instruction", () => {
    const e = new ApiError(400, { error: "invalid", detail: "visitor email" });
    expect(receptionError(e)).toBe("Check the visitor email.");
  });

  it("covers the empty-body 404 without hinting whether it exists elsewhere", () => {
    const message = receptionError(new ApiError(404, null));
    expect(message).toContain("not available from this desk");
    expect(message.toLowerCase()).not.toContain("floor");
  });

  it("never leaks a developer-facing message", () => {
    expect(receptionError(new Error("ECONNREFUSED"))).toBe("Something went wrong. Try again.");
  });
});

describe("errorCode", () => {
  it("reads the API's own code, or null when there is none", () => {
    expect(errorCode(new ApiError(409, { error: "station_unresolved" }))).toBe("station_unresolved");
    expect(errorCode(new ApiError(404, null))).toBeNull();
    expect(errorCode(new Error("boom"))).toBeNull();
  });
});

describe("homeFor with the reception desk", () => {
  it("sends a receptionist to the desk, not the console", () => {
    // FLOOR_RECEPTIONIST holds masterdata.view only so it can read visitor types while
    // registering; the console must not claim them on the strength of it.
    expect(homeFor(["visitor.register", "masterdata.view"])).toBe("/reception");
    expect(homeFor(["visitor.register"])).toBe("/reception");
  });

  it("leaves every other role where it was", () => {
    expect(homeFor(["user.manage"])).toBe("/admin");
    expect(homeFor(["masterdata.view"])).toBe("/admin");
    expect(homeFor(["visitor.approve"])).toBe("/approvals");
    expect(homeFor(["visitor.request"])).toBe("/visits");
    expect(homeFor([])).toBeNull();
  });
});
