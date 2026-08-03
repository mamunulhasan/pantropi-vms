/**
 * The heading block every screen opens with (UI-7, prototype `meta`).
 *
 * The prototype gives each screen the same trio — an area kicker, a title, and one sentence saying
 * what the screen is for — and that consistency is most of why it reads as one product rather than
 * a set of pages. It was already the de-facto pattern here (`h1` + muted paragraph, hand-rolled on
 * every screen); this makes it one component so the spacing and type scale stop drifting.
 *
 * `level` exists because of the configuration area: `(config)/layout.tsx` owns the `h1` for all
 * eight tables, so those screens must render an `h2` or the page would carry two `h1`s — which the
 * accessibility suite fails on, correctly.
 */
import type { ReactNode } from "react";

export function PageHeader({
  kicker,
  title,
  description,
  actions,
  level = 1,
}: {
  /** The area this screen belongs to — "Reception", "Facility". Omitted where the rail says it. */
  kicker?: string;
  title: string;
  description?: ReactNode;
  /** Right-hand slot: the screen's primary action, when it has exactly one. */
  actions?: ReactNode;
  /** 2 when an enclosing layout already owns the page's `h1`. */
  level?: 1 | 2;
}) {
  const Heading = level === 1 ? "h1" : "h2";

  return (
    <div className="flex flex-wrap items-start justify-between gap-3">
      <div className="min-w-0">
        {kicker && (
          <p className="text-xs font-medium uppercase tracking-wider text-text-muted">{kicker}</p>
        )}
        <Heading
          className={
            level === 1
              ? "text-xl font-semibold text-text"
              : "text-lg font-semibold text-text"
          }
        >
          {title}
        </Heading>
        {description && <p className="mt-1 max-w-prose text-sm text-text-muted">{description}</p>}
      </div>
      {actions && <div className="flex shrink-0 flex-wrap items-center gap-2">{actions}</div>}
    </div>
  );
}
