"use client";

/**
 * One visit request in full, with amend and withdraw (VJ-1 over US-07.1.3 / US-07.6.1).
 *
 * **Why the visitor list is not editable by default.** The API's amend replaces the visitors
 * array wholesale, and this detail response returns each visitor's name, company, type and status
 * — but *not* their email or phone (TODO-13 keeps that PII off the read path). Pre-filling an
 * editor from what we can see and sending it back would therefore erase every visitor's contact
 * details, silently, on an unrelated edit to the time window. So editing the window and purpose
 * omits `visitors` entirely (which leaves them untouched), and replacing the list is a separate,
 * explicit choice that states the consequence and starts from names alone.
 *
 * Amend is legal only while `submitted`; withdraw also works once `approved`, and the API revokes
 * any issued credential in the same transaction. Both surface the server's 409 verbatim, because
 * "someone else decided this first" is exactly what the tenant needs to read.
 */
import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { Button } from "@/components/ui/Button";
import { ConfirmDialog, Dialog } from "@/components/ui/Dialog";
import { Field, Input, Textarea } from "@/components/ui/Field";
import { useToast } from "@/components/ui/Toast";
import { ErrorState, LoadingState } from "@/components/ui/states";
import {
  VisitorRows,
  cleanVisitors,
  emptyVisitor,
  validateVisitors,
} from "@/components/visits/VisitorRows";
import { formatInstant, formatWindow, instantToLocalInput, localInputToInstant } from "@/lib/datetime";
import { StatusTag, requestTone } from "@/components/ui/StatusTag";
import {
  STATUS_LABELS,
  VisitsApi,
  visitError,
  type AmendRequest,
  type MyRequestDetail,
  type VisitorPayload,
} from "@/lib/visits-api";

export default function VisitDetailPage() {
  const { id } = useParams<{ id: string }>();
  const { toast } = useToast();

  const [request, setRequest] = useState<MyRequestDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>(null);
  const [amending, setAmending] = useState(false);
  const [withdrawing, setWithdrawing] = useState(false);
  const [withdrawBusy, setWithdrawBusy] = useState(false);

  const load = useCallback(() => {
    setLoading(true);
    setError(null);
    VisitsApi.get(id)
      .then(setRequest)
      .catch(setError)
      .finally(() => setLoading(false));
  }, [id]);

  useEffect(load, [load]);

  if (loading && request === null) {
    return <LoadingState />;
  }
  if (error) {
    return <ErrorState error={error} retry={load} />;
  }
  if (!request) {
    return null;
  }

  const canAmend = request.status === "submitted";
  const canWithdraw = request.status === "submitted" || request.status === "approved";

  return (
    <div className="max-w-3xl">
      <nav aria-label="Breadcrumb" className="text-sm text-text-muted">
        <Link href="/visits" className="hover:text-brand hover:underline">
          My visit requests
        </Link>
        {" / "}
        <span aria-current="page" className="text-text">
          {formatWindow(request.scheduledFrom, request.scheduledTo)}
        </span>
      </nav>

      <div className="mt-2 flex flex-wrap items-start justify-between gap-3">
        <h1 className="text-xl font-semibold text-text">Visit request</h1>
        <StatusTag label={STATUS_LABELS[request.status]} tone={requestTone(request.status)} />
      </div>

      {/* The outcome first: it is why a tenant opens this page (US-07.1.2). */}
      {request.decidedAt && (
        <div
          className={`mt-4 rounded-lg border p-4 ${
            request.status === "rejected" ? "border-danger bg-danger-surface" : "border-border bg-surface"
          }`}
        >
          <h2 className="text-sm font-medium text-text">
            {request.status === "approved" ? "Approved" : "Decision"}
          </h2>
          <p className="mt-1 text-sm text-text-muted">
            {formatInstant(request.decidedAt)}
            {request.decidedBy && <> by {request.decidedBy}</>}
          </p>
          {request.decisionReason && (
            <p className="mt-2 whitespace-pre-wrap text-sm text-text">{request.decisionReason}</p>
          )}
        </div>
      )}

      <dl className="mt-4 grid gap-3 rounded-lg border border-border bg-surface p-4 sm:grid-cols-2">
        <div>
          <dt className="text-sm text-text-muted">When</dt>
          <dd className="text-sm text-text">
            {formatWindow(request.scheduledFrom, request.scheduledTo)}
          </dd>
        </div>
        <div>
          <dt className="text-sm text-text-muted">Host</dt>
          <dd className="text-sm text-text">{request.host ?? "Not named"}</dd>
        </div>
        <div>
          <dt className="text-sm text-text-muted">Submitted</dt>
          <dd className="text-sm text-text">{formatInstant(request.submittedAt)}</dd>
        </div>
        <div>
          <dt className="text-sm text-text-muted">Purpose</dt>
          <dd className="whitespace-pre-wrap text-sm text-text">{request.purpose ?? "—"}</dd>
        </div>
      </dl>

      <h2 className="mt-6 text-base font-semibold text-text">
        Visitors ({request.visitors.length})
      </h2>
      <div className="mt-2 overflow-x-auto rounded-lg border border-border">
        <table className="w-full border-collapse bg-surface text-sm">
          <caption className="sr-only">Visitors on this request</caption>
          <thead>
            <tr className="border-b border-border bg-surface-sunken text-left">
              <th scope="col" className="px-4 py-3 font-medium">Name</th>
              <th scope="col" className="px-4 py-3 font-medium">Company</th>
              <th scope="col" className="px-4 py-3 font-medium">Type</th>
              <th scope="col" className="px-4 py-3 font-medium">Status</th>
            </tr>
          </thead>
          <tbody>
            {request.visitors.map((v, i) => (
              <tr key={`${v.fullName}-${i}`} className="border-b border-border last:border-b-0">
                <td className="px-4 py-3 text-text">{v.fullName}</td>
                <td className="px-4 py-3 text-text">{v.company ?? "—"}</td>
                <td className="px-4 py-3 text-text">{v.visitorType ?? "—"}</td>
                <td className="px-4 py-3 text-text-muted">{v.status}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {(canAmend || canWithdraw) && (
        <div className="mt-6 flex flex-wrap gap-3">
          {canAmend && <Button onClick={() => setAmending(true)}>Amend request</Button>}
          {canWithdraw && (
            <Button variant="danger" onClick={() => setWithdrawing(true)}>
              Withdraw request
            </Button>
          )}
        </div>
      )}
      {!canAmend && !canWithdraw && (
        <p className="mt-6 text-sm text-text-muted">
          This request is {STATUS_LABELS[request.status].toLowerCase()} and can no longer be changed.
        </p>
      )}

      {amending && (
        <AmendDialog
          request={request}
          onDone={(message) => {
            setAmending(false);
            if (message) {
              toast(message, "success");
              load();
            }
          }}
        />
      )}

      {withdrawing && (
        <ConfirmDialog
          open
          title="Withdraw visit request"
          description={
            <>
              Withdraw the request for{" "}
              <strong>{formatWindow(request.scheduledFrom, request.scheduledTo)}</strong> covering{" "}
              {request.visitors.length} visitor{request.visitors.length === 1 ? "" : "s"}?
              {request.status === "approved" &&
                " It is already approved, so any issued pass will be revoked."}
            </>
          }
          confirmLabel="Withdraw"
          destructive
          busy={withdrawBusy}
          onCancel={() => setWithdrawing(false)}
          onConfirm={async () => {
            setWithdrawBusy(true);
            try {
              await VisitsApi.cancel(request.id);
              toast("Request withdrawn.", "success");
              load();
            } catch (e) {
              toast(visitError(e), "error");
            } finally {
              setWithdrawBusy(false);
              setWithdrawing(false);
            }
          }}
        />
      )}

      <p className="mt-8 text-sm">
        <Link href="/visits" className="text-brand hover:underline">
          Back to my requests
        </Link>
      </p>
    </div>
  );
}

function AmendDialog({
  request,
  onDone,
}: {
  request: MyRequestDetail;
  onDone: (message: string | null) => void;
}) {
  const [from, setFrom] = useState(instantToLocalInput(request.scheduledFrom));
  const [to, setTo] = useState(instantToLocalInput(request.scheduledTo));
  const [purpose, setPurpose] = useState(request.purpose ?? "");
  const [replaceVisitors, setReplaceVisitors] = useState(false);
  const [visitors, setVisitors] = useState<VisitorPayload[]>(() =>
    // Names and companies are all this endpoint returns; emails and phones are deliberately not
    // on the read path, so they start blank and the warning below says so.
    request.visitors.map((v) => ({
      ...emptyVisitor(),
      fullName: v.fullName,
      company: v.company ?? "",
    })),
  );
  const [problems, setProblems] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);

  async function onSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const found: string[] = [];

    const scheduledFrom = localInputToInstant(from);
    const scheduledTo = localInputToInstant(to);
    if (!scheduledFrom || !scheduledTo) {
      found.push("Both ends of the window are required.");
    } else if (scheduledTo <= scheduledFrom) {
      found.push("The visit must end after it starts.");
    }
    if (replaceVisitors) {
      found.push(...validateVisitors(visitors));
    }
    setProblems(found);
    if (found.length > 0 || !scheduledFrom || !scheduledTo) {
      return;
    }

    const body: AmendRequest = {
      scheduledFrom,
      scheduledTo,
      purpose: purpose.trim() || null,
    };
    // Only send `visitors` when replacing: absent means "leave the list alone", which is what
    // protects the contact details this screen cannot see.
    if (replaceVisitors) {
      body.visitors = cleanVisitors(visitors);
    }

    setBusy(true);
    try {
      await VisitsApi.amend(request.id, body);
      onDone("Request updated.");
    } catch (e) {
      setProblems([visitError(e)]);
      setBusy(false);
    }
  }

  return (
    <Dialog open onClose={() => onDone(null)} title="Amend visit request">
      <form onSubmit={onSubmit} noValidate>
        {problems.length > 0 && (
          <ul role="alert" className="mt-3 space-y-1 rounded-md bg-danger-surface p-3 text-sm text-danger">
            {problems.map((p) => (
              <li key={p}>{p}</li>
            ))}
          </ul>
        )}

        <div className="mt-4 grid gap-3 sm:grid-cols-2">
          <Field label="From" required>
            <Input
              type="datetime-local"
              value={from}
              onChange={(e) => setFrom(e.target.value)}
              required
            />
          </Field>
          <Field label="To" required>
            <Input
              type="datetime-local"
              value={to}
              onChange={(e) => setTo(e.target.value)}
              required
            />
          </Field>
        </div>

        <Field label="Purpose" className="mt-3">
          <Textarea
            value={purpose}
            onChange={(e) => setPurpose(e.target.value)}
            maxLength={500}
            rows={3}
          />
        </Field>

        <div className="mt-4 rounded-md border border-border p-3">
          <label className="flex items-start gap-2 text-sm">
            <input
              type="checkbox"
              checked={replaceVisitors}
              onChange={(e) => setReplaceVisitors(e.target.checked)}
              className="mt-1"
            />
            <span>
              <span className="font-medium text-text">Replace the visitor list</span>
              <span className="mt-1 block text-text-muted">
                The API replaces all visitors at once, and this screen cannot read their email
                addresses or phone numbers — so re-enter every visitor in full. Leave this unticked
                to change only the window and purpose.
              </span>
            </span>
          </label>

          {replaceVisitors && (
            <div className="mt-4">
              <VisitorRows visitors={visitors} onChange={setVisitors} disabled={busy} />
            </div>
          )}
        </div>

        <div className="mt-6 flex justify-end gap-3">
          <Button variant="secondary" onClick={() => onDone(null)} disabled={busy}>
            Cancel
          </Button>
          <Button type="submit" busy={busy}>
            Save changes
          </Button>
        </div>
      </form>
    </Dialog>
  );
}
