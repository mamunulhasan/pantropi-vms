"use client";

/**
 * Submit a visitor request (VJ-1 over US-07.1.1).
 *
 * Two fields the SRS implies are deliberately absent, because the API offers no way to populate
 * them and inventing one is not on the table:
 *
 * - **Host.** `hostId` is optional on the endpoint, and there is no host endpoint anywhere in the
 *   API — `vms.hosts` exists and is validated against, but nothing exposes it. The request is
 *   submitted without a host and the form says so.
 * - **Visitor type.** Choosing one needs `masterdata.view`, which TENANT does not hold.
 *
 * Both are recorded as gaps in the traceability matrix rather than papered over.
 */
import { useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { Button } from "@/components/ui/Button";
import { Field, Input, Textarea } from "@/components/ui/Field";
import { useToast } from "@/components/ui/Toast";
import {
  VisitorRows,
  cleanVisitors,
  emptyVisitor,
  validateVisitors,
} from "@/components/visits/VisitorRows";
import { localInputToInstant } from "@/lib/datetime";
import { VisitsApi, visitError, type VisitorPayload } from "@/lib/visits-api";

export default function NewVisitRequestPage() {
  const router = useRouter();
  const { toast } = useToast();

  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [purpose, setPurpose] = useState("");
  const [visitors, setVisitors] = useState<VisitorPayload[]>([emptyVisitor()]);
  const [problems, setProblems] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);

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
      <h1 className="mt-2 text-xl font-semibold text-text">New visit request</h1>
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
          <p className="mt-3 text-sm text-text-muted">
            The visit is registered against your tenant. Naming a specific host is not available
            yet — the API exposes no host directory.
          </p>
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
