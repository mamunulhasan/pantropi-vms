/**
 * Parses the holiday bulk-import textarea (UI-4b over US-04.7.x).
 *
 * One entry per line: `YYYY-MM-DD, Name` with an optional third field `working` marking a
 * working-day exception (a compensating Saturday). Parsing is client-side only for shaping the
 * request — the server re-validates everything and applies all-or-nothing.
 */
import { type HolidayRequest } from "./admin-api";

export type ParsedImport = {
  rows: HolidayRequest[];
  /** One message per bad line, naming the line number. */
  errors: string[];
};

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

export function parseHolidayLines(text: string): ParsedImport {
  const rows: HolidayRequest[] = [];
  const errors: string[] = [];

  const lines = text.split(/\r?\n/);
  lines.forEach((raw, index) => {
    const line = raw.trim();
    if (line === "") {
      return;
    }
    const lineNo = index + 1;
    const parts = line.split(",").map((p) => p.trim());
    const [date, name, flag] = [parts[0] ?? "", parts.slice(1, -1).join(", ") || parts[1] || "", parts.length > 2 ? parts[parts.length - 1] : ""];

    // A name containing commas: everything between the date and a trailing "working" flag is
    // the name; without the flag, everything after the date is.
    const working = flag.toLowerCase() === "working";
    const effectiveName = working ? name : parts.slice(1).join(", ").trim();

    if (!ISO_DATE.test(date)) {
      errors.push(`Line ${lineNo}: "${date || raw}" is not a date (expected YYYY-MM-DD).`);
      return;
    }
    if (!effectiveName) {
      errors.push(`Line ${lineNo}: a name is required after the date.`);
      return;
    }
    rows.push({ date, name: effectiveName, working });
  });

  if (rows.length === 0 && errors.length === 0) {
    errors.push("Nothing to import — add one holiday per line as YYYY-MM-DD, Name.");
  }
  return { rows, errors };
}
