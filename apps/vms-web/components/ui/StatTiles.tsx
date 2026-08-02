/**
 * The row of headline numbers a screen opens with (UI-7, prototype KPI grid).
 *
 * Lifted from the two tiles hand-rolled in `app/(fm)/approvals/page.tsx`. The prototype puts four
 * across the top of each operational screen; this keeps that composition while staying honest
 * about arithmetic — a tile renders whatever it is given, and every caller derives its numbers
 * from data an endpoint actually returned.
 *
 * <strong>An unknown value is an em dash, not a zero.</strong> Zero is a fact ("nobody is waiting");
 * a dash is the absence of one ("we have not been told"). Rendering the first when you mean the
 * second is how a dashboard ends up lying quietly — so `value` accepts `null` and says so.
 */
export type Stat = {
  label: string;
  /** `null` renders an em dash: not yet loaded, or not derivable from what the API returned. */
  value: number | string | null;
  /** One short line under the number — what it counts, or what it excludes. */
  note?: string;
};

export function StatTiles({ stats, className }: { stats: readonly Stat[]; className?: string }) {
  if (stats.length === 0) {
    return null;
  }

  return (
    <dl
      className={`grid gap-3 sm:grid-cols-2 lg:grid-cols-4 ${className ?? ""}`}
    >
      {stats.map((stat) => (
        // The note lives inside the <dd>, not beside it: axe's definition-list rule allows a div
        // wrapper to hold only dt/dd, and the note is part of what the number means anyway.
        <div key={stat.label} className="rounded-lg border border-border bg-surface p-4">
          <dt className="text-sm text-text-muted">{stat.label}</dt>
          <dd className="mt-1">
            <span className="block text-2xl font-semibold tabular-nums text-text">
              {stat.value ?? "—"}
            </span>
            {stat.note && <span className="mt-1 block text-xs text-text-muted">{stat.note}</span>}
          </dd>
        </div>
      ))}
    </dl>
  );
}
