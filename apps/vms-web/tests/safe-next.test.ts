/**
 * US-06.3.1 AC-1/AC-3 — the redirect-restore parameter is attacker-writable, so the validator is
 * an allowlist of shape. These are the shapes that must never pass.
 */
import { describe, expect, it } from "vitest";
import { safeNextPath } from "@/lib/safe-next";

describe("safeNextPath", () => {
  it("accepts a same-origin absolute path, with query", () => {
    expect(safeNextPath("/admin")).toBe("/admin");
    expect(safeNextPath("/admin/users?page=2")).toBe("/admin/users?page=2");
  });

  it("rejects absent and empty values", () => {
    expect(safeNextPath(null)).toBeNull();
    expect(safeNextPath(undefined)).toBeNull();
    expect(safeNextPath("")).toBeNull();
  });

  it("rejects absolute URLs — the login page must never become a phishing hop", () => {
    expect(safeNextPath("https://evil.example/login")).toBeNull();
    expect(safeNextPath("http://evil.example")).toBeNull();
  });

  it("rejects scheme-relative and backslash tricks", () => {
    // //evil.example starts with a slash and still leaves the site.
    expect(safeNextPath("//evil.example")).toBeNull();
    expect(safeNextPath("/\\evil.example")).toBeNull();
  });

  it("rejects embedded schemes and control characters", () => {
    expect(safeNextPath("/redirect?to=https://evil.example")).toBeNull();
    expect(safeNextPath("/admin\r\nSet-Cookie: x")).toBeNull();
  });

  it("rejects a relative path — only rooted paths are ever generated", () => {
    expect(safeNextPath("admin")).toBeNull();
  });
});
