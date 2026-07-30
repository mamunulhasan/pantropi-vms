/**
 * VJ-1 — the visitor-request query builder and error absorption, plus the visitor validator.
 */
import { describe, expect, it } from "vitest";
import { ApiError } from "@/lib/api";
import { isStateConflict, visitError, visitsQuery } from "@/lib/visits-api";
import { cleanVisitors, validateVisitors } from "@/components/visits/VisitorRows";

describe("visitsQuery", () => {
  it("sends only what the API accepts, and nothing at defaults", () => {
    expect(visitsQuery({})).toBe("");
    expect(visitsQuery({ page: 0 })).toBe("");
    expect(visitsQuery({ status: "approved", page: 2 })).toBe("?status=approved&page=2");
  });

  it("carries the date range as the API names it", () => {
    expect(visitsQuery({ from: "2027-01-01T00:00:00Z" })).toBe(
      `?from=${encodeURIComponent("2027-01-01T00:00:00Z")}`,
    );
  });
});

describe("visitError", () => {
  it("turns a field-kind rejection into an instruction", () => {
    // The API names the field kind and never the value: {"error":"invalid","detail":"visitor email"}
    const e = new ApiError(400, { error: "invalid", detail: "visitor email" });
    expect(visitError(e)).toBe("Check the visitor email.");
  });

  it("shows a conflict detail verbatim — the tenant needs to read what happened", () => {
    const e = new ApiError(409, {
      error: "already_decided",
      detail: "The request is approved and can no longer be decided",
    });
    expect(visitError(e)).toBe("The request is approved and can no longer be decided");
  });

  it("shows the 422 elapsed-window explanation verbatim", () => {
    const e = new ApiError(422, {
      error: "window_elapsed",
      detail: "The visit window ended at X, before Y; approving it would issue a credential…",
    });
    expect(visitError(e)).toContain("The visit window ended at X");
  });

  it("covers the empty-body 404 without hinting whether it exists", () => {
    const message = visitError(new ApiError(404, null));
    expect(message).toContain("not available");
    // Foreign and never-existed are deliberately indistinguishable (US-03.2.2) — no "forbidden".
    expect(message.toLowerCase()).not.toContain("permission");
    expect(message.toLowerCase()).not.toContain("forbidden");
  });

  it("never leaks a developer-facing message", () => {
    expect(visitError(new Error("ECONNREFUSED 127.0.0.1:8081"))).toBe(
      "Something went wrong. Try again.",
    );
  });
});

describe("isStateConflict", () => {
  it("is true only for 409, the reload-and-look-again case", () => {
    expect(isStateConflict(new ApiError(409, { error: "already_decided" }))).toBe(true);
    expect(isStateConflict(new ApiError(422, { error: "window_elapsed" }))).toBe(false);
    expect(isStateConflict(new ApiError(404, null))).toBe(false);
    expect(isStateConflict(new Error("boom"))).toBe(false);
  });
});

describe("validateVisitors", () => {
  it("accepts a minimal visitor — only the name is required", () => {
    expect(validateVisitors([{ fullName: "Ada Lovelace" }])).toEqual([]);
  });

  it("names the visitor by position, which the API's error cannot do", () => {
    const problems = validateVisitors([
      { fullName: "Ada" },
      { fullName: "Bob", email: "not-an-email" },
    ]);
    expect(problems).toHaveLength(1);
    expect(problems[0]).toContain("Visitor 2");
  });

  it("requires a name and rejects an empty list", () => {
    expect(validateVisitors([])).toContain("Name at least one visitor.");
    expect(validateVisitors([{ fullName: "   " }])[0]).toContain("full name is required");
  });

  it("accepts the phone shapes the server accepts, and refuses the rest", () => {
    // The server strips spaces, brackets, dots and hyphens before checking 6–20 digits.
    expect(validateVisitors([{ fullName: "A", phone: "+880 1700-000000" }])).toEqual([]);
    expect(validateVisitors([{ fullName: "A", phone: "(017) 000.111" }])).toEqual([]);
    expect(validateVisitors([{ fullName: "A", phone: "12345" }])[0]).toContain("6 to 20 digits");
    expect(validateVisitors([{ fullName: "A", phone: "not a phone" }])[0]).toContain("digits");
  });

  it("refuses more than the server's 50 visitors", () => {
    const many = Array.from({ length: 51 }, (_, i) => ({ fullName: `V${i}` }));
    expect(validateVisitors(many).some((p) => p.includes("at most 50"))).toBe(true);
  });
});

describe("cleanVisitors", () => {
  it("turns untouched optional fields into nulls, not empty strings", () => {
    expect(cleanVisitors([{ fullName: "  Ada  ", email: "", phone: "  ", company: "" }])).toEqual([
      { fullName: "Ada", email: null, phone: null, company: null },
    ]);
  });

  it("keeps what was actually entered", () => {
    expect(
      cleanVisitors([{ fullName: "Ada", email: " a@b.co ", phone: "+8801", company: " Acme " }]),
    ).toEqual([{ fullName: "Ada", email: "a@b.co", phone: "+8801", company: "Acme" }]);
  });
});
