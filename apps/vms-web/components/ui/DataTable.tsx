"use client";

/**
 * Server-paginated data table (US-06.2.1 AC-3).
 *
 * Controlled, deliberately: it renders exactly the one page it is given and reports intent
 * (`onPageChange`/`onSortChange`) upward. It has no way to hold a full dataset, which is the
 * point — the API's list endpoints paginate server-side, and a component that could buffer
 * "all rows" would eventually be handed them. The owning screen translates the callbacks into
 * `page/size/sort` request params (and reflects them into the URL, so a filtered view survives
 * refresh and can be linked).
 *
 * Sorting is announced via `aria-sort` on the active header; header buttons toggle
 * asc → desc → asc. `page` is zero-based to match the API; the rendered text is one-based.
 */
import { type ReactNode } from "react";
import { Button } from "./Button";

export type Column<T> = {
  /** The sort key the API understands — also the column's identity. */
  key: string;
  header: string;
  sortable?: boolean;
  render: (row: T) => ReactNode;
  align?: "left" | "right";
};

export type Sort = { key: string; direction: "asc" | "desc" };

export function DataTable<T>({
  caption,
  columns,
  rows,
  rowKey,
  page,
  size,
  totalElements,
  sort,
  onPageChange,
  onSortChange,
  loading = false,
  empty,
}: {
  /** Names the table for assistive tech; rendered visually hidden. */
  caption: string;
  columns: readonly Column<T>[];
  /** The current page's rows — never the whole dataset. */
  rows: readonly T[];
  rowKey: (row: T) => string;
  /** Zero-based, matching the API. */
  page: number;
  size: number;
  totalElements: number;
  sort?: Sort;
  onPageChange: (page: number) => void;
  onSortChange?: (sort: Sort) => void;
  loading?: boolean;
  /** Shown instead of the body when there are no rows (usually an <EmptyState>). */
  empty?: ReactNode;
}) {
  const totalPages = Math.max(1, Math.ceil(totalElements / size));
  const from = totalElements === 0 ? 0 : page * size + 1;
  const to = Math.min(totalElements, (page + 1) * size);

  function toggleSort(column: Column<T>) {
    if (!column.sortable || !onSortChange) {
      return;
    }
    const direction = sort?.key === column.key && sort.direction === "asc" ? "desc" : "asc";
    onSortChange({ key: column.key, direction });
  }

  if (!loading && totalElements === 0 && empty) {
    return <>{empty}</>;
  }

  return (
    <div aria-busy={loading || undefined} className={loading ? "opacity-60" : undefined}>
      <div className="overflow-x-auto rounded-lg border border-border">
        <table className="w-full border-collapse bg-surface-raised text-sm">
          <caption className="sr-only">{caption}</caption>
          <thead>
            <tr className="border-b border-border bg-surface-sunken text-left">
              {columns.map((column) => {
                const sorted = sort?.key === column.key;
                return (
                  <th
                    key={column.key}
                    scope="col"
                    aria-sort={
                      sorted ? (sort.direction === "asc" ? "ascending" : "descending") : undefined
                    }
                    className={`px-4 py-3 font-medium text-text ${column.align === "right" ? "text-right" : ""}`}
                  >
                    {column.sortable && onSortChange ? (
                      <button
                        type="button"
                        onClick={() => toggleSort(column)}
                        className="inline-flex items-center gap-1 font-medium hover:text-brand"
                      >
                        {column.header}
                        <span aria-hidden="true">
                          {sorted ? (sort.direction === "asc" ? "▲" : "▼") : "↕"}
                        </span>
                      </button>
                    ) : (
                      column.header
                    )}
                  </th>
                );
              })}
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={rowKey(row)} className="border-b border-border last:border-b-0">
                {columns.map((column) => (
                  <td
                    key={column.key}
                    className={`px-4 py-3 text-text ${column.align === "right" ? "text-right" : ""}`}
                  >
                    {column.render(row)}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <nav aria-label="Pagination" className="mt-3 flex items-center justify-between text-sm">
        <p className="text-text-muted">
          {totalElements === 0 ? "No entries" : `Showing ${from}–${to} of ${totalElements}`}
        </p>
        <div className="flex items-center gap-2">
          <Button
            variant="secondary"
            disabled={page === 0 || loading}
            onClick={() => onPageChange(page - 1)}
          >
            Previous
          </Button>
          <span aria-current="page" className="px-2 text-text-muted">
            Page {page + 1} of {totalPages}
          </span>
          <Button
            variant="secondary"
            disabled={page >= totalPages - 1 || loading}
            onClick={() => onPageChange(page + 1)}
          >
            Next
          </Button>
        </div>
      </nav>
    </div>
  );
}
