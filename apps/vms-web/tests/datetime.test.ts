/**
 * VJ-1 — the datetime-local ↔ instant boundary.
 *
 * This is where an appointment silently shifts by the zone offset if the conversion is naive, so
 * the round trip is the test that matters: whatever the browser's zone, a value put into the input
 * and read back out must mean the same moment.
 */
import { describe, expect, it } from "vitest";
import { formatWindow, instantToLocalInput, localInputToInstant } from "@/lib/datetime";

describe("localInputToInstant", () => {
  it("reads the input as local wall-clock time, not as UTC", () => {
    const instant = localInputToInstant("2027-03-01T09:00");
    expect(instant).not.toBeNull();
    // 09:00 local is 09:00 local — whatever the offset, the parsed Date agrees with the browser.
    const parsed = new Date(instant!);
    expect(parsed.getHours()).toBe(9);
    expect(parsed.getMinutes()).toBe(0);
    expect(instant).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/);
  });

  it("answers null for empty and unparseable values", () => {
    expect(localInputToInstant("")).toBeNull();
    expect(localInputToInstant("not-a-date")).toBeNull();
  });
});

describe("instantToLocalInput", () => {
  it("round-trips through the input without shifting the moment", () => {
    const original = "2027-03-01T09:00";
    const instant = localInputToInstant(original)!;
    expect(instantToLocalInput(instant)).toBe(original);
  });

  it("round-trips an instant back to itself", () => {
    const instant = new Date("2027-07-04T15:30:00.000Z").toISOString();
    const roundTripped = localInputToInstant(instantToLocalInput(instant))!;
    expect(new Date(roundTripped).getTime()).toBe(new Date(instant).getTime());
  });

  it("pads month, day, hour and minute so the input accepts the value", () => {
    const instant = localInputToInstant("2027-01-02T03:04")!;
    expect(instantToLocalInput(instant)).toBe("2027-01-02T03:04");
  });

  it("answers empty for absent or unparseable input", () => {
    expect(instantToLocalInput(null)).toBe("");
    expect(instantToLocalInput(undefined)).toBe("");
    expect(instantToLocalInput("garbage")).toBe("");
  });
});

describe("formatWindow", () => {
  it("does not repeat the date for a same-day window", () => {
    const from = localInputToInstant("2027-03-01T09:00")!;
    const to = localInputToInstant("2027-03-01T17:00")!;
    const text = formatWindow(from, to);
    expect(text).toContain("–");
    // The day appears once, not twice.
    expect(text.match(/2027/g) ?? []).toHaveLength(1);
  });

  it("shows both dates when the window spans days", () => {
    const from = localInputToInstant("2027-03-01T22:00")!;
    const to = localInputToInstant("2027-03-02T02:00")!;
    expect(formatWindow(from, to)).toContain("→");
  });

  it("degrades to a dash rather than throwing on bad input", () => {
    expect(formatWindow("nonsense", "also-nonsense")).toBe("—");
  });
});
