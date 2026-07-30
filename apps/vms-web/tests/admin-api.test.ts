/**
 * UI-4a — the query builder and the error absorption layer over the admin API's three failure
 * shapes: ad-hoc {error,field,message} / {error,key,message}, RFC 7807 problem+json, and the
 * empty-body 404.
 */
import { describe, expect, it } from "vitest";
import { errorMessage, fieldError, listQuery } from "@/lib/admin-api";
import { ApiError } from "@/lib/api";

describe("listQuery", () => {
  it("renders only what deviates from the server defaults", () => {
    expect(listQuery({})).toBe("");
    expect(listQuery({ page: 0 })).toBe("");
    expect(listQuery({ search: "west", page: 2 })).toBe("?search=west&page=2");
  });

  it("carries the boolean filter explicitly — false is a filter, not an absence", () => {
    expect(listQuery({ active: false })).toBe("?active=false");
    expect(listQuery({ active: true })).toBe("?active=true");
    expect(listQuery({})).not.toContain("active");
  });

  it("encodes reserved characters in the search term", () => {
    expect(listQuery({ search: "a&b=c" })).toBe(`?search=${encodeURIComponent("a&b=c")}`);
  });
});

describe("errorMessage", () => {
  it("prefers the controller's field message (master data shape)", () => {
    const e = new ApiError(409, {
      error: "duplicate",
      field: "code",
      message: "code already exists: WGT",
    });
    expect(errorMessage(e)).toBe("code already exists: WGT");
  });

  it("uses the settings shape's message too — the 409 refusal texts were written to be shown", () => {
    const e = new ApiError(409, {
      error: "change_refused",
      key: "notification.whatsapp.enabled",
      message: "WhatsApp cannot be enabled while TODO-05 is open: …",
    });
    expect(errorMessage(e)).toContain("TODO-05");
  });

  it("falls back to problem+json detail", () => {
    const e = new ApiError(403, {
      type: "https://pantropi.com/vms/problems/forbidden",
      title: "You do not have access to this resource.",
      status: 403,
      detail: "You do not have access to this resource.",
      correlationId: "abc",
    });
    expect(errorMessage(e)).toBe("You do not have access to this resource.");
  });

  it("covers the empty-body 404 with a sentence, and never Error#message", () => {
    expect(errorMessage(new ApiError(404, null))).toContain("no longer exists");
    expect(errorMessage(new Error("ECONNREFUSED"))).toBe("Something went wrong. Try again.");
    expect(errorMessage(new Error("ECONNREFUSED"))).not.toContain("ECONNREFUSED");
  });
});

describe("fieldError", () => {
  it("names the field for master data and the key for settings", () => {
    expect(
      fieldError(new ApiError(400, { error: "invalid", field: "levelNo", message: "levelNo must be between -20 and 200" })),
    ).toEqual({ field: "levelNo", message: "levelNo must be between -20 and 200" });
    expect(
      fieldError(new ApiError(400, { error: "invalid_value", key: "acs.retry.max_attempts", message: "Invalid value" })),
    ).toEqual({ field: "acs.retry.max_attempts", message: "Invalid value" });
  });

  it("answers null for problem+json, empty bodies and non-API errors", () => {
    expect(fieldError(new ApiError(403, { detail: "no", correlationId: "x" }))).toBeNull();
    expect(fieldError(new ApiError(404, null))).toBeNull();
    expect(fieldError(new Error("boom"))).toBeNull();
  });
});
