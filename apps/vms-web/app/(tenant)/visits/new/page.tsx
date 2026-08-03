"use client";

/**
 * Submit a visitor request (VJ-1 over US-07.1.1).
 *
 * The host selector reads the tenant's own directory (US-10.1.1), which is scoped server-side —
 * there is no tenant id to send and none would be honoured. Only **active** hosts are offered: a
 * departed colleague must not be attachable to a new visit (AC-3).
 *
 * A tenant whose directory is empty still submits without a host, which the endpoint allows, and
 * the form says why rather than presenting an empty control. Adding hosts is the directory page of
 * T-10.1.1.3 and has not shipped; until then they are created through the API.
 *
 * **Visitor type is still absent**, and that gap is unchanged: choosing one needs `masterdata.view`,
 * which the TENANT role does not hold. Recorded in the traceability matrix rather than papered over.
 */
import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { PageHeader } from "@/components/ui/PageHeader";
import { Button } from "@/components/ui/Button";
import { Field, Input, Select, Textarea } from "@/components/ui/Field";
import { useToast } from "@/components/ui/Toast";
import {
  VisitorRows,
  cleanVisitors,
  emptyVisitor,
  validateVisitors,
} from "@/components/visits/VisitorRows";
import { localInputToInstant } from "@/lib/datetime";
import { HostsApi, type HostSummary } from "@/lib/hosts-api";
import { VisitsApi, visitError, type VisitorPayload } from "@/lib/visits-api";

export default function NewVisitRequestPage() {
  const router = useRouter();
  const { toast } = useToast();

  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [purpose, setPurpose] = useState("");
  const [hostId, setHostId] = useState("");
  const [hosts, setHosts] = useState<HostSummary[] | null>(null);
  const [visitors, setVisitors] = useState<VisitorPayload[]>([emptyVisitor()]);
  const [problems, setProblems] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    // A directory that cannot be read is the same as an empty one for this form's purposes: the
    // request is submitted without a host either way, so a failure here must not block submitting.
    HostsApi.listActive()
      .then((page) => setHosts(page.content))
      .catch(() => setHosts([]));
  }, []);

  async function onSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const found = validateVisitors(visitors);

    const scheduledFrom = localInputToInstant(from);
    const scheduledTo = localInputToInstant(to);
    if (!scheduledFrom) {
      found.unshift("Choose when the visit starts.");
    }
    if (!scheduledTo) {
      found.unshift("Choose when the visit ends.");
    }
    if (scheduledFrom && scheduledTo && scheduledTo <= scheduledFrom) {
      found.unshift("The visit must end after it starts.");
    }
    setProblems(found);
    if (found.length > 0 || !scheduledFrom || !scheduledTo) {
      return;
    }

    setBusy(true);
    try {
      const { id } = await VisitsApi.submit({
        hostId: hostId || null,
        scheduledFrom,
        scheduledTo,
        purpose: purpose.trim() || null,
        visitors: cleanVisitors(visitors),
      });
      toast("Request submitted. It is now awaiting a decision.", "success");
      router.replace(`/visits/${id}`);
    } catch (e) {
      setProblems([visitError(e)]);
      setBusy(false);
    }
  }

  return (
    <div className="max-w-3xl">
      <nav aria-label="Breadcrumb" className="text-sm text-text-muted">
        <Link href="/visits" className="hover:text-brand hover:underline">
          My visit requests
        </Link>
        {" / "}
        <span aria-current="page" className="text-text">
          New request
        </span>
      </nav>
      <PageHeader kicker="Tenant" title="New visit request" />
      <p className="mt-1 text-sm text-text-muted">
        Facility management reviews every request. You will see the outcome, and the reason if it is
        declined, on this list.
      </p>

      <form onSubmit={onSubmit} noValidate className="mt-6">
        {problems.length > 0 && (
          <ul
            role="alert"
            className="mb-4 space-y-1 rounded-md bg-danger-surface p-3 text-sm text-danger"
          >
            {problems.map((p) => (
              <li key={p}>{p}</li>
            ))}
          </ul>
        )}

        <fieldset className="rounded-lg border border-border p-4">
          <legend className="px-1 text-sm font-medium text-text">When</legend>
          <div className="grid gap-3 sm:grid-cols-2">
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
          <Field
            label="Purpose"
            hint="Optional. What the visit is for — helps the reviewer decide."
            className="mt-3"
          >
            <Textarea
              value={purpose}
              onChange={(e) => setPurpose(e.target.value)}
              maxLength={500}
              rows={3}
            />
          </Field>
          <Field
            label="Host"
            hint="Who in your organisation the visitor is coming to see. Optional."
            className="mt-3"
          >
            <Select value={hostId} onChange={(e) => setHostId(e.target.value)}>
              <option value="">No host named</option>
              {(hosts ?? []).map((h) => (
                <option key={h.id} value={h.id}>
                  {h.fullName}
                </option>
              ))}
            </Select>
          </Field>
          {hosts !== null && hosts.length === 0 && (
            <p className="mt-2 text-sm text-text-muted">
              Your organisation has no hosts on file yet, so this request will not name one. That
              is allowed — the visit is still registered against your tenant.
            </p>
          )}
        </fieldset>

        <fieldset className="mt-4 rounded-lg border-0 p-0">
          <legend className="text-sm font-medium text-text">Who is visiting</legend>
          <div className="mt-3">
            <VisitorRows visitors={visitors} onChange={setVisitors} disabled={busy} />
          </div>
        </fieldset>

        <div className="mt-6 flex justify-end gap-3">
          <Button variant="secondary" onClick={() => router.push("/visits")} disabled={busy}>
            Cancel
          </Button>
          <Button type="submit" busy={busy}>
            Submit request
          </Button>
        </div>
      </form>
    </div>
  );
}
