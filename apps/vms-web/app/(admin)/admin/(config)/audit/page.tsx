"use client";

/**
 * The audit trail, across every entity (US-02.5.1) — FR-AUD-01/02.
 *
 * `audit.view` existed long before anything served it in full: the only route was one request's
 * decision history, while master-data edits, user imports, settings changes and authorization
 * denials were written and never readable. This is the screen that makes the permission mean what
 * its description says.
 *
 * <strong>Read-only, and it looks it.</strong> No create button, no row action, no edit dialog —
 * because there is no endpoint behind any of them and there must not be. The table is append-only
 * by trigger since V12.
 *
 * The two state columns are rendered as collapsed `<details>` rather than inline. They hold
 * allow-listed projections that can name people, and an operator scanning for "who changed the
 * grace period" should not have to read past a dozen expanded JSON blobs to find it — but the
 * detail must still be one click away, because reconstructing what changed is the whole point.
 */
import { Suspense, useCallback, useEffect, useMemo, useState } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { PageHeader } from "@/components/ui/PageHeader";
import { DataTable, type Column } from "@/components/ui/DataTable";
import { EmptyState, ErrorState, LoadingState } from "@/components/ui/states";
import { AuditApi, type AuditEntry, type AuditVocabulary } from "@/lib/admin-api";
import { formatInstant } from "@/lib/datetime";

export default function AuditPage() {
  return (
    <Suspense fallback={<LoadingState />}>
      <AuditScreen />
    </Suspense>
  );
}

const PAGE_SIZE = 20;

function AuditScreen() {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();

  const filters = useMemo(() => {
    const pageRaw = Number(searchParams.get("page"));
    return {
      action: searchParams.get("action") ?? undefined,
      entityType: searchParams.get("entityType") ?? undefined,
      from: searchParams.get("from") ?? undefined,
      to: searchParams.get("to") ?? undefined,
      page: Number.isInteger(pageRaw) && pageRaw > 0 ? pageRaw : 0,
      size: PAGE_SIZE,
    };
  }, [searchParams]);

  const [data, setData] = useState<{
    content: AuditEntry[];
    totalElements: number;
    page: number;
    size: number;
  } | null>(null);
  const [vocabulary, setVocabulary] = useState<AuditVocabulary>({ actions: [], entityTypes: [] });
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>(null);
  const [tick, setTick] = useState(0);

  const updateUrl = useCallback(
    (next: Partial<Record<"action" | "entityType" | "from" | "to" | "page", string | undefined>>) => {
      const q = new URLSearchParams(searchParams);
      for (const [key, value] of Object.entries(next)) {
        if (value === undefined || value === "") q.delete(key);
        else q.set(key, value);
      }
      // Any filter change returns to the first page: page 3 of the old result set is not page 3 of
      // the new one, and silently keeping it shows an empty table for no visible reason.
      if (!("page" in next)) q.delete("page");
      router.replace(`${pathname}?${q.toString()}`);
    },
    [router, pathname, searchParams],
  );

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    AuditApi.list(filters)
      .then((page) => {
        if (!cancelled) {
          setData(page);
          setError(null);
        }
      })
      .catch((e) => {
        if (!cancelled) setError(e);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [filters, tick]);

  useEffect(() => {
    // Offered choices come from what the trail contains, so a filter never lists an action nobody
    // has ever taken.
    AuditApi.vocabulary()
      .then(setVocabulary)
      .catch(() => setVocabulary({ actions: [], entityTypes: [] }));
  }, []);

  const columns: readonly Column<AuditEntry>[] = [
    { key: "at", header: "When", render: (e) => formatInstant(e.at) },
    { key: "action", header: "Action", render: (e) => <code className="text-xs">{e.action}</code> },
    {
      key: "entity",
      header: "Entity",
      render: (e) => (
        <span>
          {e.entityType ?? "—"}
          {e.entityId && <span className="block text-xs text-text-muted">{e.entityId}</span>}
        </span>
      ),
    },
    {
      key: "actor",
      header: "Who",
      // A display name, never a username or email — the same rule the decision trail follows.
      render: (e) => e.actor ?? <span className="text-text-muted">Account removed</span>,
    },
    { key: "ip", header: "From", render: (e) => e.ipAddress ?? "—" },
    {
      key: "change",
      header: "Change",
      render: (e) =>
        e.beforeState || e.afterState ? (
          <details className="max-w-md">
            <summary className="cursor-pointer text-sm text-text-muted">Show</summary>
            <dl className="mt-2 space-y-1 text-xs">
              {e.beforeState && (
                <>
                  <dt className="text-text-muted">Before</dt>
                  <dd className="overflow-x-auto">
                    <code>{e.beforeState}</code>
                  </dd>
                </>
              )}
              {e.afterState && (
                <>
                  <dt className="text-text-muted">After</dt>
                  <dd className="overflow-x-auto">
                    <code>{e.afterState}</code>
                  </dd>
                </>
              )}
            </dl>
          </details>
        ) : (
          <span className="text-text-muted">—</span>
        ),
    },
  ];

  return (
    <div>
      <PageHeader
        title="Audit log"
        level={2}
        description="Every recorded change, newest first. Entries are append-only and cannot be edited or removed."
      />

      <div className="mt-4 flex flex-wrap items-end gap-3">
        <div>
          <label htmlFor="audit-action" className="block text-sm font-medium text-text">
            Action
          </label>
          <select
            id="audit-action"
            value={filters.action ?? ""}
            onChange={(e) => updateUrl({ action: e.target.value || undefined })}
            className="mt-1 rounded-md border border-border bg-surface px-3 py-2 text-sm"
          >
            <option value="">All actions</option>
            {vocabulary.actions.map((a) => (
              <option key={a} value={a}>
                {a}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor="audit-entity" className="block text-sm font-medium text-text">
            Entity
          </label>
          <select
            id="audit-entity"
            value={filters.entityType ?? ""}
            onChange={(e) => updateUrl({ entityType: e.target.value || undefined })}
            className="mt-1 rounded-md border border-border bg-surface px-3 py-2 text-sm"
          >
            <option value="">All entities</option>
            {vocabulary.entityTypes.map((t) => (
              <option key={t} value={t}>
                {t}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label htmlFor="audit-from" className="block text-sm font-medium text-text">
            From
          </label>
          <input
            id="audit-from"
            type="date"
            value={filters.from ? filters.from.slice(0, 10) : ""}
            onChange={(e) =>
              updateUrl({ from: e.target.value ? `${e.target.value}T00:00:00Z` : undefined })
            }
            className="mt-1 rounded-md border border-border bg-surface px-3 py-2 text-sm"
          />
        </div>
        <div>
          <label htmlFor="audit-to" className="block text-sm font-medium text-text">
            Before
          </label>
          <input
            id="audit-to"
            type="date"
            value={filters.to ? filters.to.slice(0, 10) : ""}
            onChange={(e) =>
              updateUrl({ to: e.target.value ? `${e.target.value}T00:00:00Z` : undefined })
            }
            className="mt-1 rounded-md border border-border bg-surface px-3 py-2 text-sm"
          />
        </div>
      </div>

      {error ? (
        <div className="mt-4">
          <ErrorState error={error} retry={() => setTick((t) => t + 1)} />
        </div>
      ) : (
        <div className="mt-4">
          <DataTable
            caption="Audit trail entries"
            columns={columns}
            rows={data?.content ?? []}
            rowKey={(e) => e.id}
            page={data?.page ?? 0}
            size={data?.size ?? PAGE_SIZE}
            totalElements={data?.totalElements ?? 0}
            onPageChange={(p) => updateUrl({ page: String(p) })}
            loading={loading}
            empty={
              <EmptyState
                title="Nothing recorded in this range"
                description={
                  filters.action || filters.entityType || filters.from || filters.to
                    ? "No entry matches these filters. Widen the range or clear them."
                    : "Entries appear here as people approve requests, change settings and edit master data."
                }
              />
            }
          />
        </div>
      )}
    </div>
  );
}
