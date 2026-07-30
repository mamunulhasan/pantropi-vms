/**
 * UI-4b — the holiday bulk-import line parser. Shaping only: the server re-validates and
 * applies all-or-nothing.
 */
import { describe, expect, it } from "vitest";
import { parseHolidayLines } from "@/lib/holiday-import";

describe("parseHolidayLines", () => {
  it("parses date, name and the optional working flag", () => {
    const { rows, errors } = parseHolidayLines(
      "2027-01-01, New Year\n2027-04-03, Make-up day, working\n",
    );
    expect(errors).toEqual([]);
    expect(rows).toEqual([
      { date: "2027-01-01", name: "New Year", working: false },
      { date: "2027-04-03", name: "Make-up day", working: true },
    ]);
  });

  it("keeps commas inside names", () => {
    const { rows } = parseHolidayLines("2027-03-26, Independence, and National Day");
    expect(rows[0].name).toBe("Independence, and National Day");

    const { rows: flagged } = parseHolidayLines("2027-03-27, Long, complex name, working");
    expect(flagged[0]).toEqual({ date: "2027-03-27", name: "Long, complex name", working: true });
  });

  it("names the line for a bad date or a missing name", () => {
    const { rows, errors } = parseHolidayLines("01/01/2027, New Year\n2027-05-01,");
    expect(rows).toEqual([]);
    expect(errors).toHaveLength(2);
    expect(errors[0]).toContain("Line 1");
    expect(errors[0]).toContain("YYYY-MM-DD");
    expect(errors[1]).toContain("Line 2");
  });

  it("skips blank lines without renumbering the rest", () => {
    const { rows, errors } = parseHolidayLines("\n\nbad-date, X");
    expect(rows).toEqual([]);
    expect(errors[0]).toContain("Line 3");
  });

  it("an empty textarea is one clear message, not silence", () => {
    const { errors } = parseHolidayLines("   \n  ");
    expect(errors).toHaveLength(1);
    expect(errors[0]).toContain("Nothing to import");
  });
});
