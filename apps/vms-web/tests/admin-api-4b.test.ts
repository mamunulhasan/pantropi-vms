/**
 * UI-4b — the new API-shape helpers: the users query builder (its params differ from master
 * data), the stale-version extractor, and the last_master_admin wire quirk.
 */
import { describe, expect, it } from "vitest";
import { errorMessage, staleVersionOf, userListQuery } from "@/lib/admin-api";
import { ApiError } from "@/lib/api";

describe("userListQuery", () => {
  it("uses the users API's param names — role, not roleCode; no search", () => {
    expect(userListQuery({ role: "SYSTEM_ADMIN", active: true, page: 2 })).toBe(
      "?role=SYSTEM_ADMIN&active=true&page=2",
    );
    expect(userListQuery({})).toBe("");
  });

  it("carries the raw column sort names as given", () => {
    expect(userListQuery({ sort: "full_name" })).toBe("?sort=full_name");
  });
});

describe("staleVersionOf", () => {
  it("extracts the 409 stale_version body with the current state for re-asking", () => {
    const conflict = staleVersionOf(
      new ApiError(409, {
        error: "stale_version",
        roleCode: "TENANT",
        currentVersion: "XyZ123AbC456",
        currentPermissions: ["masterdata.view", "visitor.request"],
        message: "The grants for TENANT changed since you loaded them.",
      }),
    );
    expect(conflict?.currentVersion).toBe("XyZ123AbC456");
    expect(conflict?.currentPermissions).toEqual(["masterdata.view", "visitor.request"]);
  });

  it("answers null for every other failure", () => {
    expect(staleVersionOf(new ApiError(409, { error: "administrative_lockout" }))).toBeNull();
    expect(staleVersionOf(new ApiError(404, null))).toBeNull();
    expect(staleVersionOf(new Error("boom"))).toBeNull();
  });
});

describe("errorMessage — users API quirks", () => {
  it("reads the last_master_admin sentence out of the field slot", () => {
    const e = new ApiError(409, {
      error: "last_master_admin",
      field:
        "Refused: this is the only active Master Admin; the system would be left with no holder of FR-ADM-01 authority",
    });
    expect(errorMessage(e)).toContain("only active Master Admin");
  });

  it("an {error, field} body without a message still gets a sentence, not the raw field name", () => {
    const e = new ApiError(409, { error: "duplicate", field: "username" });
    expect(errorMessage(e)).toBe("Something went wrong. Try again.");
  });
});
