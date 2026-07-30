/**
 * Instant ↔ `datetime-local` conversion, plus display formatting (VJ-1).
 *
 * The API speaks ISO-8601 instants (UTC). `<input type="datetime-local">` speaks wall-clock time
 * with no zone, in the browser's zone. Converting through the Date constructor is what makes
 * "09:00" mean nine o'clock where the user is sitting, rather than nine UTC — a distinction that
 * silently shifts every appointment by the offset if you skip it (the project's local zone is
 * +06, so a naive implementation books visits six hours early).
 */

/** `2027-03-01T09:00` (browser-local) → `2027-03-01T03:00:00.000Z`. */
export function localInputToInstant(value: string): string | null {
  if (!value) {
    return null;
  }
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}

/** An instant → the `datetime-local` value that renders it in the browser's zone. */
export function instantToLocalInput(instant: string | null | undefined): string {
  if (!instant) {
    return "";
  }
  const date = new Date(instant);
  if (Number.isNaN(date.getTime())) {
    return "";
  }
  const pad = (n: number) => String(n).padStart(2, "0");
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    `T${pad(date.getHours())}:${pad(date.getMinutes())}`
  );
}

/** Human-readable, in the reader's own zone and locale. */
export function formatInstant(instant: string | null | undefined): string {
  if (!instant) {
    return "—";
  }
  const date = new Date(instant);
  if (Number.isNaN(date.getTime())) {
    return "—";
  }
  return date.toLocaleString(undefined, {
    year: "numeric",
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

/** A window as one line: same-day windows do not repeat the date. */
export function formatWindow(from: string, to: string): string {
  const start = new Date(from);
  const end = new Date(to);
  if (Number.isNaN(start.getTime()) || Number.isNaN(end.getTime())) {
    return "—";
  }
  const sameDay = start.toDateString() === end.toDateString();
  if (!sameDay) {
    return `${formatInstant(from)} → ${formatInstant(to)}`;
  }
  const time = end.toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" });
  return `${formatInstant(from)} – ${time}`;
}
