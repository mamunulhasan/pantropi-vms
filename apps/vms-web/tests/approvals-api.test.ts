/**
 * VJ-2 — the approval queue's query builder and decision-error handling.
 */
import { describe, expect, it } from "vitest";
import { ApiError } from "@/lib/api";
import { decisionError, isStale, queueQuery } from "@/lib/approvals-api";
import { homeFor } from "@/lib/nav";

describe("queueQuery", () => {
  it("relies on the server's default status rather than restating it", () => {
    // The endpoint defaults to `submitted`; sending it would make the URL lie about being filtered.
    expect(queueQuery({})).toBe("");
    expect(queueQuery({ page: 0 })).toBe("");
  });

  it("sends the filters the endpoint actually supports", () => {
    expect(queueQuery({ search: "rahman", page: 2 })).toBe("?search=rahman&page=2");
    expect(queueQuery({ status: "approved" })).toBe("?status=approved");
  });

  it("encodes a search term with reserved characters", () => {
    expect(queueQuery({ search: "a&b" })).toBe(`?search=${encodeURIComponent("a&b")}`);
  });
});

describe("decisionError", () => {
  it("shows the conflict verbatim — the reviewer needs to know someone decided first", () => {
    const e = new ApiError(409, {
      error: "already_decided",
      detail: "The request is approved and can no longer be decided",
    });
    expect(decisionError(e)).toBe("The request is approved and can no longer be decided");
  });

  it("shows the elapsed-window explanation, which says why retrying cannot work", () => {
    const e = new ApiError(422, {
      error: "window_elapsed",
      detail: "The visit window ended at X, before Y; approving it would issue a credential…",
    });
    expect(decisionError(e)).toContain("window ended");
  });

  it("shows the missing-reason refusal", () => {
    const e = new ApiError(400, { error: "reason_required", detail: "A rejection must say why" });
    expect(decisionError(e)).toBe("A rejection must say why");
  });

  it("never leaks a developer-facing message", () => {
    expect(decisionError(new Error("ECONNREFUSED"))).toBe(
      "The decision could not be recorded. Try again.",
    );
  });
});

describe("isStale", () => {
  it("is true only for the already-decided conflict, which means reload", () => {
    expect(isStale(new ApiError(409, { error: "already_decided" }))).toBe(true);
    // 422 is a different thing: right state, world moved on, retrying never works.
    expect(isStale(new ApiError(422, { error: "window_elapsed" }))).toBe(false);
    expect(isStale(new ApiError(404, null))).toBe(false);
    expect(isStale(new Error("boom"))).toBe(false);
  });
});

describe("homeFor with the FM area", () => {
  it("sends an approver to the queue", () => {
    expect(homeFor(["visitor.approve"])).toBe("/approvals");
    // MASTER_ADMIN holds approve plus credential.issue and no admin permission.
    expect(homeFor(["visitor.approve", "credential.issue"])).toBe("/approvals");
  });

  it("keeps the other areas where they were", () => {
    expect(homeFor(["user.manage"])).toBe("/admin");
    expect(homeFor(["visitor.request"])).toBe("/visits");
    expect(homeFor([])).toBeNull();
    expect(homeFor(undefined)).toBeNull();
  });

  it("a floor receptionist lands in the console until VJ-3 ships the desk", () => {
    // masterdata.view is what puts them there; visitor.register has no area yet.
    expect(homeFor(["visitor.register", "masterdata.view"])).toBe("/admin");
  });
});
