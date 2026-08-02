"use client";

/**
 * The tenant's own visit requests (VJ-1 over US-07.1.2 / US-07.6.2).
 *
 * The list is fetched **conditionally**: the ETag from the previous read goes back as
 * `If-None-Match`, and a 304 leaves the rendered rows untouched. That is the whole point of
 * US-07.6.2 — a tenant watching for a decision can refresh without the server rebuilding a page
 * it has already sent, and the validator changes the moment any decision, amendment or
 * cancellation touches the scope.
 *
 * Filters live in the URL like every other list screen. The API offers `status`, `from` and `to`
 * and nothing else — no search, no sort — so the toolbar offers exactly those.
 */
import { Suspense, useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { StatusTag, requestTone } from "@/components/ui/StatusTag";
import { Button } from "@/components/ui/Button";
import { EmptyState, ErrorState, LoadingState } from "@/components/ui/states";
import { formatInstant, formatWindow } from "@/lib/datetime";
import {
  REQUEST_STATUSES,
  STATUS_LABELS,
  VisitsApi,
  type ListFilters,
  type MyRequestsPage,
  type RequestStatus,
} from "@/lib/visits-api";

export default function VisitsPage() {
  return (
    <Suspense fallback={<LoadingState />}>
      <VisitsScreen />
    </Suspense>
  );
}

function VisitsScreen() {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();

  const statusParam = searchParams.get("status");
  const status = REQUEST_STATUSES.includes(statusParam as RequestStatus)
    ? (statusParam as RequestStatus)
    : undefined;
  const pageRaw = Number(searchParams.get("page"));
  const page = Number.isInteger(pageRaw) && pageRaw > 0 ? pageRaw : 0;
  const from = searchParams.get("from") ?? undefined;
  const to = searchParams.get("to") ?? undefined;

  const [data, setData] = useState<MyRequestsPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>(null);
  const [revalidatedAt, setRevalidatedAt] = useState<string | null>(null);
  const [tick, setTick] = useState(0);

  // The validator for exactly the filter+page currently rendered. A filter change makes the old
  // one meaningless, so it is keyed and cleared rather than carried across.
  const etagRef = useRef<{ key: string; etag: string | null }>({ key: "", etag: null });

  const filterKey = `${status ?? ""}|${from ?? ""}|${to ?? ""}|${page}`;

  const load = useCallback(async () => {
    const filters: ListFilters = { status, from, to, page };
    const sameView = etagRef.current.key === filterKey;
    const etag = sameView ? etagRef.current.etag : null;

    setLoading(true);
    setError(null);
    try {
      const result = await VisitsApi.list(filters, etag);
      if (result.modified) {
        setData(result.data);
        etagRef.current = { key: filterKey, etag: result.etag };
      }
      // A 304 means what is on screen is still current — nothing to do but say so.
      setRevalidatedAt(new Date().toLocaleTimeString());
    } catch (e) {
      setError(e);
    } finally {
      setLoading(false);
    }
  }, [status, from, to, page, filterKey]);

  useEffect(() => {
    void load();
    // `tick` is the manual refresh; `load` changes when the filters do.
  }, [load, tick]);

  function updateUrl(next: Partial<{ status: string; from: string; to: string; page: number }>) {
    const q = new URLSearchParams(searchParams);
    const resetsPage = Object.keys(next).some((k) => k !== "page");
    const write = (key: string, value: string | undefined) =>
      value === undefined || value === "" ? q.delete(key) : q.set(key, value);
    if ("status" in next) write("status", next.status);
    if ("from" in next) write("from", next.from);
    if ("to" in next) write("to", next.to);
    const nextPage = resetsPage ? 0 : (next.page ?? page);
    write("page", nextPage > 0 ? String(nextPage) : undefined);
    const query = q.toString();
    router.replace(query ? `${pathname}?${query}` : pathname);
  }

  if (error) {
    return <ErrorState error={error} retry={() => setTick((t) => t + 1)} />;
  }

  const totalPages = data ? Math.max(1, Math.ceil(data.totalElements / data.size)) : 1;

  return (
    <div>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold text-text">My visit requests</h1>
          <p className="mt-1 text-sm text-text-muted">
            Requests from your tenant, newest first.
            {revalidatedAt && <> Checked for updates at {revalidatedAt}.</>}
          </p>
        </div>
        <div className="flex gap-2">
          <Button variant="secondary" onClick={() => setTick((t) => t + 1)} busy={loading}>
            Check for updates
          </Button>
          <Button onClick={() => router.push("/visits/new")}>New request</Button>
        </div>
      </div>

      <div className="mt-4 flex flex-wrap items-end gap-3">
        <div>
          <label htmlFor="filter-status" className="block text-sm font-medium text-text">
            Status
          </label>
          <select
            id="filter-status"
            value={status ?? "all"}
            onChange={(e) => updateUrl({ status: e.target.value === "all" ? undefined : e.target.value })}
            className="mt-1 rounded-md border border-border bg-surface px-3 py-2 text-sm"
          >
            <option value="all">All</option>
            {REQUEST_STATUSES.map((s) => (
              <option key={s} value={s}>
                {STATUS_LABELS[s]}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor="filter-from" className="block text-sm font-medium text-text">
            Visits from
          </label>
          <input
            id="filter-from"
            type="date"
            value={from ? from.slice(0, 10) : ""}
            onChange={(e) =>
              updateUrl({ from: e.target.value ? `${e.target.value}T00:00:00Z` : undefined })
            }
            className="mt-1 rounded-md border border-border bg-surface px-3 py-2 text-sm"
          />
        </div>
        <div>
          <label htmlFor="filter-to" className="block text-sm font-medium text-text">
            Visits before
          </label>
          <input
            id="filter-to"
            type="date"
            value={to ? to.slice(0, 10) : ""}
            onChange={(e) =>
              updateUrl({ to: e.target.value ? `${e.target.value}T00:00:00Z` : undefined })
            }
            className="mt-1 rounded-md border border-border bg-surface px-3 py-2 text-sm"
          />
        </div>
      </div>

      <div className="mt-4">
        {data === null ? (
          <LoadingState />
        ) : data.content.length === 0 ? (
          <EmptyState
            title="No requests match"
            description="Requests you submit appear here with their decision."
            action={<Button onClick={() => router.push("/visits/new")}>New request</Button>}
          />
        ) : (
          <>
            <ul className="space-y-3">
              {data.content.map((row) => (
                <li key={row.id}>
                  <Link
                    href={`/visits/${row.id}`}
                    className="block rounded-lg border border-border bg-surface p-4 hover:bg-surface-sunken"
                  >
                    <div className="flex flex-wrap items-start justify-between gap-2">
                      <div>
                        <p className="font-medium text-text">
                          {formatWindow(row.scheduledFrom, row.scheduledTo)}
                        </p>
                        <p className="mt-1 text-sm text-text-muted">
                          {row.visitorCount} visitor{row.visitorCount === 1 ? "" : "s"}
                          {row.host && <> · host {row.host}</>} · submitted{" "}
                          {formatInstant(row.submittedAt)}
                        </p>
                      </div>
                      <StatusTag
                        label={STATUS_LABELS[row.status]}
                        tone={requestTone(row.status)}
                      />
                    </div>
                    {row.decisionReason && (
                      <p className="mt-2 text-sm text-text">
                        <span className="text-text-muted">Reason: </span>
                        {row.decisionReason}
                      </p>
                    )}
                  </Link>
                </li>
              ))}
            </ul>

            <nav aria-label="Pagination" className="mt-4 flex items-center justify-between text-sm">
              <p className="text-text-muted">
                {data.totalElements} request{data.totalElements === 1 ? "" : "s"}
              </p>
              <div className="flex items-center gap-2">
                <Button
                  variant="secondary"
                  disabled={data.page === 0 || loading}
                  onClick={() => updateUrl({ page: data.page - 1 })}
                >
                  Previous
                </Button>
                <span aria-current="page" className="px-2 text-text-muted">
                  Page {data.page + 1} of {totalPages}
                </span>
                <Button
                  variant="secondary"
                  disabled={data.page >= totalPages - 1 || loading}
                  onClick={() => updateUrl({ page: data.page + 1 })}
                >
                  Next
                </Button>
              </div>
            </nav>
          </>
        )}
      </div>
    </div>
  );
}
