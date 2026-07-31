"use client";

/**
 * The FM approval queue (VJ-2 over US-07.3.x / US-07.4.x), following wireframe 04 — the queue
 * table with stat tiles (2a) and the detail drawer (2c).
 *
 * Where this departs from the wireframe, it is because the API does not carry what the drawing
 * shows, and the reconciliation in docs/project/17-wireframe-reconciliation.md records each:
 *
 * - **No visitor names in the table.** The queue row carries a count, not names. Names come from
 *   the detail read — which writes an audit row on every call — so fetching one per row would
 *   inflate the audit trail by a page-worth on every render. Names appear when a request is opened.
 * - **No credential column.** A request has no credential; that is decided at issuance, unbuilt.
 * - **No bulk approve.** There is no bulk endpoint, and n calls with n independent conflict
 *   outcomes is not the atomic "approve all" the wireframe implies.
 * - **No "on-site now" tile.** Nothing in the API can set a visitor to on-site yet (EPIC-09).
 *
 * The two tiles shown are ones the list endpoint can answer honestly: everything awaiting a
 * decision, and how much of that arrives today.
 */
import { Suspense, useCallback, useEffect, useState } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { Button } from "@/components/ui/Button";
import { DataTable, type Column } from "@/components/ui/DataTable";
import { Dialog } from "@/components/ui/Dialog";
import { PassDialog, type PassSubject } from "@/components/visits/PassDialog";
import { Field, Textarea } from "@/components/ui/Field";
import { useToast } from "@/components/ui/Toast";
import { EmptyState, ErrorState, LoadingState } from "@/components/ui/states";
import {
  ApprovalsApi,
  SEARCH_MAX,
  decisionError,
  hasWorkingPass,
  issuanceWarning,
  isStale,
  type PendingPage,
  type PendingRow,
  type QueueFilters,
  type IssuedPass,
} from "@/lib/approvals-api";
import { formatInstant, formatWindow } from "@/lib/datetime";
import { VisitsApi, type MyRequestDetail, type VisitorLine } from "@/lib/visits-api";

export default function ApprovalsPage() {
  return (
    <Suspense fallback={<LoadingState />}>
      <ApprovalsScreen />
    </Suspense>
  );
}

/** Midnight-to-midnight in the reader's own zone, as the instants the API filters on. */
function todayRange(): { from: string; to: string } {
  const start = new Date();
  start.setHours(0, 0, 0, 0);
  const end = new Date(start);
  end.setDate(end.getDate() + 1);
  return { from: start.toISOString(), to: end.toISOString() };
}

function ApprovalsScreen() {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const { toast } = useToast();

  const page = Math.max(0, Number(searchParams.get("page")) || 0);
  const search = searchParams.get("search") ?? undefined;
  const from = searchParams.get("from") ?? undefined;
  const to = searchParams.get("to") ?? undefined;

  const [data, setData] = useState<PendingPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>(null);
  const [arrivingToday, setArrivingToday] = useState<number | null>(null);
  const [tick, setTick] = useState(0);
  const [draft, setDraft] = useState(search ?? "");
  const [opened, setOpened] = useState<PendingRow | null>(null);
  /** Shown after an approval mints them — the request leaves the queue, so this is the one chance. */
  const [passes, setPasses] = useState<PassSubject[]>([]);

  const reload = useCallback(() => setTick((t) => t + 1), []);

  useEffect(() => {
    setDraft(search ?? "");
  }, [search]);

  useEffect(() => {
    let cancelled = false;
    const filters: QueueFilters = { search, from, to, page };
    setLoading(true);
    setError(null);
    ApprovalsApi.queue(filters)
      .then((p) => {
        if (!cancelled) setData(p);
      })
      .catch((e: unknown) => {
        if (!cancelled) setError(e);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [search, from, to, page, tick]);

  // The second tile: the same pending queue, narrowed to today's arrivals.
  useEffect(() => {
    let cancelled = false;
    const range = todayRange();
    ApprovalsApi.queue({ from: range.from, to: range.to })
      .then((p) => {
        if (!cancelled) setArrivingToday(p.totalElements);
      })
      .catch(() => {
        if (!cancelled) setArrivingToday(null);
      });
    return () => {
      cancelled = true;
    };
  }, [tick]);

  function updateUrl(next: Partial<{ search: string; from: string; to: string; page: number }>) {
    const q = new URLSearchParams(searchParams);
    const resetsPage = Object.keys(next).some((k) => k !== "page");
    const write = (key: string, value: string | undefined) =>
      value === undefined || value === "" ? q.delete(key) : q.set(key, value);
    if ("search" in next) write("search", next.search);
    if ("from" in next) write("from", next.from);
    if ("to" in next) write("to", next.to);
    const nextPage = resetsPage ? 0 : (next.page ?? page);
    write("page", nextPage > 0 ? String(nextPage) : undefined);
    const query = q.toString();
    router.replace(query ? `${pathname}?${query}` : pathname);
  }

  const columns: Column<PendingRow>[] = [
    {
      key: "tenant",
      header: "Tenant",
      render: (r) => <span className="font-medium text-text">{r.tenant ?? "—"}</span>,
    },
    { key: "host", header: "Host", render: (r) => r.host ?? <span className="text-text-muted">Not named</span> },
    {
      key: "appointment",
      header: "Appointment",
      render: (r) => formatWindow(r.scheduledFrom, r.scheduledTo),
    },
    {
      key: "visitors",
      header: "Visitors",
      align: "right",
      render: (r) => r.visitorCount,
    },
    { key: "submitted", header: "Submitted", render: (r) => formatInstant(r.submittedAt) },
    {
      key: "actions",
      header: "",
      align: "right",
      render: (r) => (
        <Button variant="secondary" onClick={() => setOpened(r)}>
          Review
        </Button>
      ),
    },
  ];

  if (error) {
    return <ErrorState error={error} retry={reload} />;
  }

  const todayFilterOn = Boolean(from && to);

  return (
    <div>
      <h1 className="text-xl font-semibold text-text">Approvals</h1>
      <p className="mt-1 text-sm text-text-muted">
        Visitor requests awaiting a decision, newest first. Every decision is audited.
      </p>

      <div className="mt-4 grid gap-3 sm:grid-cols-2 lg:w-2/3">
        <div className="rounded-lg border border-border bg-surface p-4">
          <p className="text-sm text-text-muted">Awaiting decision</p>
          <p className="mt-1 text-2xl font-semibold text-text">
            {data ? data.totalElements : "—"}
          </p>
        </div>
        <div className="rounded-lg border border-border bg-surface p-4">
          <p className="text-sm text-text-muted">Arriving today</p>
          <p className="mt-1 text-2xl font-semibold text-text">{arrivingToday ?? "—"}</p>
        </div>
      </div>

      <div className="mt-4 flex flex-wrap items-end justify-between gap-3">
        <form
          role="search"
          className="flex flex-wrap items-end gap-2"
          onSubmit={(e) => {
            e.preventDefault();
            updateUrl({ search: draft.trim() || undefined });
          }}
        >
          <div>
            <label htmlFor="queue-search" className="block text-sm font-medium text-text">
              Search visitor or host
            </label>
            <input
              id="queue-search"
              type="search"
              value={draft}
              maxLength={SEARCH_MAX}
              onChange={(e) => setDraft(e.target.value)}
              className="mt-1 w-72 rounded-md border border-border bg-surface px-3 py-2 text-sm"
            />
          </div>
          <Button type="submit" variant="secondary">
            Search
          </Button>
        </form>

        <Button
          variant={todayFilterOn ? "primary" : "secondary"}
          onClick={() => {
            if (todayFilterOn) {
              updateUrl({ from: undefined, to: undefined });
            } else {
              const range = todayRange();
              updateUrl({ from: range.from, to: range.to });
            }
          }}
        >
          {todayFilterOn ? "Showing today only" : "Today only"}
        </Button>
      </div>

      <div className="mt-4">
        {data === null ? (
          <LoadingState />
        ) : (
          <DataTable<PendingRow>
            caption="Visitor requests awaiting a decision"
            columns={columns}
            rows={data.content}
            rowKey={(r) => r.id}
            page={data.page}
            size={data.size}
            totalElements={data.totalElements}
            onPageChange={(p) => updateUrl({ page: p })}
            loading={loading}
            empty={
              <EmptyState
                title="Nothing is waiting"
                description="Requests appear here as tenants and reception submit them."
              />
            }
          />
        )}
      </div>

      <PassDialog subjects={passes} onClose={() => setPasses([])} />

      {opened && (
        <ReviewDialog
          row={opened}
          onDone={(message, issued, visitors) => {
            const window = opened;
            setOpened(null);
            if (!message) {
              return;
            }
            toast(message, "success");
            reload();

            if (issued && issued.length > 0) {
              // Only the visitors who actually have a pass get one shown. Offering a QR for a
              // credential that failed would be a picture of something that does not exist.
              setPasses(
                issued.filter(hasWorkingPass).map((p) => ({
                  visitorId: p.visitorId,
                  visitorName:
                    visitors?.find((v) => v.id === p.visitorId)?.fullName ?? "This visitor",
                  validFrom: window.scheduledFrom,
                  validTo: window.scheduledTo,
                })),
              );

              const warning = issuanceWarning(issued);
              if (warning) {
                toast(warning, "error");
              }
            }
          }}
          onStale={(message) => {
            setOpened(null);
            toast(message, "error");
            reload();
          }}
        />
      )}
    </div>
  );
}

/**
 * The detail drawer (wireframe 2c): the request in full, then approve with an optional note or
 * reject with a mandatory reason.
 *
 * Opening it is what triggers the detail read — and that read writes a `visitor_request.view`
 * audit row, which is the deliberate design: looking at a visitor's details is itself an event
 * worth recording. It is also why the queue does not pre-fetch these.
 */
function ReviewDialog({
  row,
  onDone,
  onStale,
}: {
  row: PendingRow;
  onDone: (message: string | null, issued?: IssuedPass[], visitors?: VisitorLine[]) => void;
  onStale: (message: string) => void;
}) {
  const [detail, setDetail] = useState<MyRequestDetail | null>(null);
  const [loadError, setLoadError] = useState<unknown>(null);
  const [note, setNote] = useState("");
  const [reason, setReason] = useState("");
  const [rejecting, setRejecting] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    let cancelled = false;
    VisitsApi.get(row.id)
      .then((d) => {
        if (!cancelled) setDetail(d);
      })
      .catch((e: unknown) => {
        if (!cancelled) setLoadError(e);
      });
    return () => {
      cancelled = true;
    };
  }, [row.id]);

  async function decide(kind: "approve" | "reject") {
    setProblem(null);
    if (kind === "reject" && reason.trim() === "") {
      // The API refuses a blank reason; saying so here saves a round trip and names the field.
      setProblem("A rejection must say why.");
      return;
    }
    setBusy(true);
    try {
      if (kind === "approve") {
        const decision = await ApprovalsApi.approve(row.id, note.trim() || null);
        // Approving is what minted the passes; carry them out so the parent can show them. The
        // request leaves the pending queue the moment this returns, so this is the only chance.
        onDone("Request approved.", decision.issued, detail?.visitors ?? []);
      } else {
        await ApprovalsApi.reject(row.id, reason.trim());
        onDone("Request rejected. The tenant sees the reason.");
      }
    } catch (e) {
      if (isStale(e)) {
        onStale(decisionError(e));
        return;
      }
      setProblem(decisionError(e));
      setBusy(false);
    }
  }

  return (
    <Dialog open onClose={() => onDone(null)} title="Review visitor request">
      {loadError ? (
        <div className="mt-4">
          <ErrorState error={loadError} />
        </div>
      ) : detail === null ? (
        <LoadingState label="Loading the request…" />
      ) : (
        <>
          <dl className="mt-4 grid gap-3 sm:grid-cols-2">
            <div>
              <dt className="text-sm text-text-muted">Tenant</dt>
              <dd className="text-sm text-text">{row.tenant ?? "—"}</dd>
            </div>
            <div>
              <dt className="text-sm text-text-muted">Host</dt>
              <dd className="text-sm text-text">{detail.host ?? "Not named"}</dd>
            </div>
            <div>
              <dt className="text-sm text-text-muted">Appointment</dt>
              <dd className="text-sm text-text">
                {formatWindow(detail.scheduledFrom, detail.scheduledTo)}
              </dd>
            </div>
            <div>
              <dt className="text-sm text-text-muted">Submitted</dt>
              <dd className="text-sm text-text">{formatInstant(detail.submittedAt)}</dd>
            </div>
            <div className="sm:col-span-2">
              <dt className="text-sm text-text-muted">Purpose</dt>
              <dd className="whitespace-pre-wrap text-sm text-text">{detail.purpose ?? "—"}</dd>
            </div>
          </dl>

          <h3 className="mt-4 text-sm font-medium text-text">
            Visitors ({detail.visitors.length})
          </h3>
          <ul className="mt-2 space-y-1 text-sm">
            {detail.visitors.map((v, i) => (
              <li key={`${v.fullName}-${i}`} className="text-text">
                {v.fullName}
                {v.company && <span className="text-text-muted"> · {v.company}</span>}
                {v.visitorType && <span className="text-text-muted"> · {v.visitorType}</span>}
              </li>
            ))}
          </ul>

          {problem && (
            <p role="alert" className="mt-4 rounded-md bg-danger-surface p-3 text-sm text-danger">
              {problem}
            </p>
          )}

          {rejecting ? (
            <div className="mt-4">
              <Field
                label="Reason for rejection"
                hint="The tenant sees this. Say what would make the request acceptable."
                required
              >
                <Textarea
                  value={reason}
                  onChange={(e) => setReason(e.target.value)}
                  maxLength={1000}
                  rows={3}
                  autoFocus
                />
              </Field>
              <div className="mt-4 flex justify-end gap-3">
                <Button variant="secondary" onClick={() => setRejecting(false)} disabled={busy}>
                  Back
                </Button>
                <Button variant="danger" onClick={() => decide("reject")} busy={busy}>
                  Reject request
                </Button>
              </div>
            </div>
          ) : (
            <div className="mt-4">
              <Field label="Approver note" hint="Optional. Recorded in the decision trail.">
                <Textarea
                  value={note}
                  onChange={(e) => setNote(e.target.value)}
                  maxLength={1000}
                  rows={2}
                />
              </Field>
              <div className="mt-4 flex justify-end gap-3">
                <Button variant="secondary" onClick={() => onDone(null)} disabled={busy}>
                  Close
                </Button>
                <Button variant="danger" onClick={() => setRejecting(true)} disabled={busy}>
                  Reject…
                </Button>
                <Button onClick={() => decide("approve")} busy={busy}>
                  Approve
                </Button>
              </div>
            </div>
          )}
        </>
      )}
    </Dialog>
  );
}
