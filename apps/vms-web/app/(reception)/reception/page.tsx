"use client";

/**
 * The reception desk (VJ-3 over US-08.1.1 / US-08.1.3), following wireframe 05 (3a).
 *
 * Two of the wireframe's elements are deliberately absent, both deferred by the owner and recorded
 * in docs/project/17-wireframe-reconciliation.md: **ID/NRIC capture** (the API exposes no such
 * field, by TODO-13's design) and the **sync-to-central column** (pre-registration is one
 * synchronous transaction against one database — there is nothing in flight to report).
 *
 * The list is titled "registered at this desk" rather than "today", and that is not a wording
 * choice. There is **no endpoint that reads pre-registrations back**: the `visitorId` appears
 * exactly once, in the 201 body, and the amend and cancel endpoints are keyed on it. Keeping the
 * session's registrations in memory is the only thing that makes those endpoints reachable at all.
 * Reloading clears it, and the screen says so instead of pretending to be a day's record.
 *
 * The floor is never asked for. The API derives the station from the caller — the command carries
 * no floor and no reception — so a desk cannot register a visit against another floor by asking.
 */
import { useEffect, useState } from "react";
import { PageHeader } from "@/components/ui/PageHeader";
import { Button } from "@/components/ui/Button";
import { ConfirmDialog, Dialog } from "@/components/ui/Dialog";
import { Field, Input, Select, Textarea } from "@/components/ui/Field";
import { useToast } from "@/components/ui/Toast";
import { EmptyState } from "@/components/ui/states";
import { VisitorTypesApi, TenantsApi, type Tenant, type VisitorType } from "@/lib/admin-api";
import { formatInstant, formatWindow, instantToLocalInput, localInputToInstant } from "@/lib/datetime";
import {
  ReceptionApi,
  needsTenantChoice,
  receptionError,
  type PreRegistrationRequest,
} from "@/lib/reception-api";

/** What this desk registered in this session — the only record of it the UI can hold. */
type DeskEntry = {
  visitorId: string;
  requestId: string;
  fullName: string;
  company: string | null;
  email: string | null;
  phone: string | null;
  visitorTypeId: string | null;
  appointmentFrom: string;
  appointmentTo: string;
  registeredAt: string;
  cancelled: boolean;
};

export default function ReceptionPage() {
  const { toast } = useToast();

  const [visitorTypes, setVisitorTypes] = useState<VisitorType[]>([]);
  const [entries, setEntries] = useState<DeskEntry[]>([]);
  const [editing, setEditing] = useState<DeskEntry | null>(null);
  const [cancelling, setCancelling] = useState<DeskEntry | null>(null);
  const [cancelBusy, setCancelBusy] = useState(false);

  // A receptionist holds masterdata.view, so the type list is readable here — unlike the tenant
  // form, where the role cannot reach it at all.
  useEffect(() => {
    VisitorTypesApi.list({ active: true, size: 200 })
      .then((p) => setVisitorTypes(p.items))
      .catch(() => setVisitorTypes([]));
  }, []);

  return (
    <div className="max-w-4xl">
      <PageHeader
        kicker="Reception"
        title="Pre-register a visitor"
        description={
          <>
            The visit is filed against your floor&rsquo;s tenant and joins the approval queue like
            any other request. The pass is issued and emailed to the visitor once an FM Admin
            approves it — there is nothing to hand out from this desk.
          </>
        }
      />

      <RegisterForm
        visitorTypes={visitorTypes}
        onRegistered={(entry) => {
          setEntries((current) => [entry, ...current]);
          toast(`${entry.fullName} pre-registered.`, "success");
        }}
      />

      <section className="mt-8">
        <h2 className="text-base font-semibold text-text">Registered at this desk</h2>
        <p className="mt-1 text-sm text-text-muted">
          This session only. The API has no way to read pre-registrations back, so this list starts
          empty after a reload — amend or cancel before then.
        </p>

        <div className="mt-3">
          {entries.length === 0 ? (
            <EmptyState
              title="Nothing registered yet"
              description="Visitors you pre-register appear here so you can amend or cancel them."
            />
          ) : (
            <div className="overflow-x-auto rounded-lg border border-border">
              <table className="w-full border-collapse bg-surface text-sm">
                <caption className="sr-only">Visitors pre-registered at this desk this session</caption>
                <thead>
                  <tr className="border-b border-border bg-surface-sunken text-left">
                    <th scope="col" className="px-4 py-3 font-medium">Visitor</th>
                    <th scope="col" className="px-4 py-3 font-medium">Appointment</th>
                    <th scope="col" className="px-4 py-3 font-medium">Registered</th>
                    <th scope="col" className="px-4 py-3">
                      <span className="sr-only">Actions</span>
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {entries.map((e) => (
                    <tr key={e.visitorId} className="border-b border-border last:border-b-0">
                      <td className="px-4 py-3 text-text">
                        {e.fullName}
                        {e.company && <span className="block text-text-muted">{e.company}</span>}
                      </td>
                      <td className="px-4 py-3 text-text">
                        {formatWindow(e.appointmentFrom, e.appointmentTo)}
                      </td>
                      <td className="px-4 py-3 text-text-muted">{formatInstant(e.registeredAt)}</td>
                      <td className="px-4 py-3 text-right">
                        {e.cancelled ? (
                          <span className="text-text-muted">Cancelled</span>
                        ) : (
                          <span className="flex justify-end gap-2">
                            <Button variant="secondary" onClick={() => setEditing(e)}>
                              Amend
                            </Button>
                            <Button variant="danger" onClick={() => setCancelling(e)}>
                              Cancel
                            </Button>
                          </span>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </section>

      {editing && (
        <AmendDialog
          entry={editing}
          visitorTypes={visitorTypes}
          onDone={(updated) => {
            setEditing(null);
            if (updated) {
              setEntries((current) =>
                current.map((e) => (e.visitorId === updated.visitorId ? updated : e)),
              );
              toast(`${updated.fullName} updated.`, "success");
            }
          }}
        />
      )}

      {cancelling && (
        <ConfirmDialog
          open
          title="Cancel pre-registration"
          description={
            <>
              Cancel the pre-registration for <strong>{cancelling.fullName}</strong> at{" "}
              <strong>{formatWindow(cancelling.appointmentFrom, cancelling.appointmentTo)}</strong>?
            </>
          }
          confirmLabel="Cancel visit"
          destructive
          busy={cancelBusy}
          onCancel={() => setCancelling(null)}
          onConfirm={async () => {
            setCancelBusy(true);
            try {
              const result = await ReceptionApi.cancel(cancelling.visitorId);
              setEntries((current) =>
                current.map((e) =>
                  e.visitorId === cancelling.visitorId ? { ...e, cancelled: true } : e,
                ),
              );
              // The API says whether the whole request went with them and whether a credential
              // was revoked — both are things the desk should hear, not swallow.
              const extra = [
                result.requestCancelled ? "the visit was withdrawn" : null,
                result.revocationRequested ? "the issued pass was revoked" : null,
              ].filter(Boolean);
              toast(
                extra.length > 0
                  ? `${cancelling.fullName} cancelled — ${extra.join(", ")}.`
                  : `${cancelling.fullName} cancelled.`,
                "success",
              );
            } catch (e) {
              toast(receptionError(e), "error");
            } finally {
              setCancelBusy(false);
              setCancelling(null);
            }
          }}
        />
      )}
    </div>
  );
}

function RegisterForm({
  visitorTypes,
  onRegistered,
}: {
  visitorTypes: VisitorType[];
  onRegistered: (entry: DeskEntry) => void;
}) {
  const [values, setValues] = useState({
    fullName: "",
    company: "",
    email: "",
    phone: "",
    visitorTypeId: "",
    purpose: "",
    from: "",
    to: "",
  });
  const [problems, setProblems] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);

  // Only a floor hosting more than one tenant ever needs this, and the API is what tells us so.
  const [tenantChoice, setTenantChoice] = useState<{ message: string; tenants: Tenant[] } | null>(
    null,
  );
  const [tenantId, setTenantId] = useState("");

  function reset() {
    setValues({
      fullName: "",
      company: "",
      email: "",
      phone: "",
      visitorTypeId: "",
      purpose: "",
      from: "",
      to: "",
    });
    setTenantChoice(null);
    setTenantId("");
  }

  async function submit(withTenantId: string | null) {
    const appointmentFrom = localInputToInstant(values.from);
    const appointmentTo = localInputToInstant(values.to);
    const found: string[] = [];
    if (!values.fullName.trim()) found.push("A visitor name is required.");
    if (!appointmentFrom) found.push("Choose when the appointment starts.");
    if (!appointmentTo) found.push("Choose when the appointment ends.");
    if (appointmentFrom && appointmentTo && appointmentTo <= appointmentFrom) {
      found.push("The appointment must end after it starts.");
    }
    setProblems(found);
    if (found.length > 0 || !appointmentFrom || !appointmentTo) {
      return;
    }

    const req: PreRegistrationRequest = {
      fullName: values.fullName.trim(),
      company: values.company.trim() || null,
      email: values.email.trim() || null,
      phone: values.phone.trim() || null,
      visitorTypeId: values.visitorTypeId || null,
      purpose: values.purpose.trim() || null,
      tenantId: withTenantId,
      appointmentFrom,
      appointmentTo,
    };

    setBusy(true);
    try {
      const result = await ReceptionApi.register(req);
      onRegistered({
        visitorId: result.visitorId,
        requestId: result.requestId,
        fullName: req.fullName,
        company: req.company ?? null,
        email: req.email ?? null,
        phone: req.phone ?? null,
        visitorTypeId: req.visitorTypeId ?? null,
        appointmentFrom,
        appointmentTo,
        registeredAt: new Date().toISOString(),
        cancelled: false,
      });
      reset();
    } catch (e) {
      if (needsTenantChoice(e) && withTenantId === null) {
        // The floor hosts several tenants. The refusal names the count, never the tenants — so
        // the picker is the full active list and the API refuses any that is not on this floor.
        const message = receptionError(e);
        try {
          const page = await TenantsApi.list({ active: true, size: 200 });
          setTenantChoice({ message, tenants: page.items });
        } catch {
          setTenantChoice({ message, tenants: [] });
        }
        setBusy(false);
        return;
      }
      setProblems([receptionError(e)]);
      setBusy(false);
      return;
    }
    setBusy(false);
  }

  return (
    <>
      <form
        className="mt-6 rounded-lg border border-border bg-surface p-4"
        noValidate
        onSubmit={(e) => {
          e.preventDefault();
          void submit(null);
        }}
      >
        {problems.length > 0 && (
          <ul role="alert" className="mb-4 space-y-1 rounded-md bg-danger-surface p-3 text-sm text-danger">
            {problems.map((p) => (
              <li key={p}>{p}</li>
            ))}
          </ul>
        )}

        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="Visitor name" required>
            <Input
              value={values.fullName}
              onChange={(e) => setValues({ ...values, fullName: e.target.value })}
              required
              maxLength={200}
              autoComplete="off"
              autoFocus
            />
          </Field>
          <Field label="Company">
            <Input
              value={values.company}
              onChange={(e) => setValues({ ...values, company: e.target.value })}
              maxLength={200}
              autoComplete="off"
            />
          </Field>
          <Field label="Email">
            <Input
              type="email"
              value={values.email}
              onChange={(e) => setValues({ ...values, email: e.target.value })}
              maxLength={320}
              autoComplete="off"
            />
          </Field>
          <Field label="Phone">
            <Input
              type="tel"
              value={values.phone}
              onChange={(e) => setValues({ ...values, phone: e.target.value })}
              maxLength={40}
              autoComplete="off"
            />
          </Field>
          <Field label="Visitor type">
            <Select
              value={values.visitorTypeId}
              onChange={(e) => setValues({ ...values, visitorTypeId: e.target.value })}
            >
              <option value="">Not specified</option>
              {visitorTypes.map((t) => (
                <option key={t.id} value={t.id}>
                  {t.name}
                </option>
              ))}
            </Select>
          </Field>
          <div className="hidden sm:block" />
          <Field label="From" required>
            <Input
              type="datetime-local"
              value={values.from}
              onChange={(e) => setValues({ ...values, from: e.target.value })}
              required
            />
          </Field>
          <Field label="To" required>
            <Input
              type="datetime-local"
              value={values.to}
              onChange={(e) => setValues({ ...values, to: e.target.value })}
              required
            />
          </Field>
        </div>

        <Field label="Purpose" className="mt-3">
          <Textarea
            value={values.purpose}
            onChange={(e) => setValues({ ...values, purpose: e.target.value })}
            maxLength={500}
            rows={2}
          />
        </Field>

        <p className="mt-3 text-sm text-text-muted">
          Your floor and reception come from your account, not from this form. Naming a specific
          host is not available yet — the API exposes no host directory.
        </p>

        <div className="mt-4 flex justify-end">
          <Button type="submit" busy={busy}>
            Pre-register visitor
          </Button>
        </div>
      </form>

      {tenantChoice && (
        <Dialog open onClose={() => setTenantChoice(null)} title="Which tenant is this visit for?">
          <p className="mt-2 text-sm text-text-muted">{tenantChoice.message}</p>
          <div className="mt-4">
            <Field
              label="Tenant"
              hint="Only tenants on your own floor are accepted; anything else is refused."
              required
            >
              <Select value={tenantId} onChange={(e) => setTenantId(e.target.value)}>
                <option value="">Select a tenant…</option>
                {tenantChoice.tenants.map((t) => (
                  <option key={t.id} value={t.id}>
                    {t.code} — {t.name}
                  </option>
                ))}
              </Select>
            </Field>
          </div>
          <div className="mt-6 flex justify-end gap-3">
            <Button variant="secondary" onClick={() => setTenantChoice(null)} disabled={busy}>
              Cancel
            </Button>
            <Button
              busy={busy}
              disabled={tenantId === ""}
              onClick={() => {
                setTenantChoice(null);
                void submit(tenantId);
              }}
            >
              Pre-register
            </Button>
          </div>
        </Dialog>
      )}
    </>
  );
}

function AmendDialog({
  entry,
  visitorTypes,
  onDone,
}: {
  entry: DeskEntry;
  visitorTypes: VisitorType[];
  onDone: (updated: DeskEntry | null) => void;
}) {
  const [values, setValues] = useState({
    fullName: entry.fullName,
    company: entry.company ?? "",
    email: entry.email ?? "",
    phone: entry.phone ?? "",
    visitorTypeId: entry.visitorTypeId ?? "",
    from: instantToLocalInput(entry.appointmentFrom),
    to: instantToLocalInput(entry.appointmentTo),
  });
  const [problem, setProblem] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function onSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setProblem(null);

    const appointmentFrom = localInputToInstant(values.from);
    const appointmentTo = localInputToInstant(values.to);
    if (!appointmentFrom || !appointmentTo) {
      setProblem("Both ends of the appointment are required.");
      return;
    }
    if (appointmentTo <= appointmentFrom) {
      setProblem("The appointment must end after it starts.");
      return;
    }

    setBusy(true);
    try {
      // Every field is sent: these are the values this desk submitted, so re-sending them is
      // exactly what is stored. The API coalesces absent fields, which would be equivalent.
      await ReceptionApi.amend(entry.visitorId, {
        fullName: values.fullName.trim(),
        company: values.company.trim() || null,
        email: values.email.trim() || null,
        phone: values.phone.trim() || null,
        visitorTypeId: values.visitorTypeId || null,
        appointmentFrom,
        appointmentTo,
      });
      onDone({
        ...entry,
        fullName: values.fullName.trim(),
        company: values.company.trim() || null,
        email: values.email.trim() || null,
        phone: values.phone.trim() || null,
        visitorTypeId: values.visitorTypeId || null,
        appointmentFrom,
        appointmentTo,
      });
    } catch (e) {
      // Includes the 409 that says the visitor has already arrived and is no longer editable
      // from the pre-arrival workflow — shown as written.
      setProblem(receptionError(e));
      setBusy(false);
    }
  }

  return (
    <Dialog open onClose={() => onDone(null)} title={`Amend ${entry.fullName}`}>
      <form onSubmit={onSubmit} noValidate>
        {problem && (
          <p role="alert" className="mt-3 rounded-md bg-danger-surface p-3 text-sm text-danger">
            {problem}
          </p>
        )}
        <div className="mt-4 grid gap-3 sm:grid-cols-2">
          <Field label="Visitor name" required>
            <Input
              value={values.fullName}
              onChange={(e) => setValues({ ...values, fullName: e.target.value })}
              required
              maxLength={200}
            />
          </Field>
          <Field label="Company">
            <Input
              value={values.company}
              onChange={(e) => setValues({ ...values, company: e.target.value })}
              maxLength={200}
            />
          </Field>
          <Field label="Email">
            <Input
              type="email"
              value={values.email}
              onChange={(e) => setValues({ ...values, email: e.target.value })}
              maxLength={320}
            />
          </Field>
          <Field label="Phone">
            <Input
              type="tel"
              value={values.phone}
              onChange={(e) => setValues({ ...values, phone: e.target.value })}
              maxLength={40}
            />
          </Field>
          <Field label="Visitor type">
            <Select
              value={values.visitorTypeId}
              onChange={(e) => setValues({ ...values, visitorTypeId: e.target.value })}
            >
              <option value="">Not specified</option>
              {visitorTypes.map((t) => (
                <option key={t.id} value={t.id}>
                  {t.name}
                </option>
              ))}
            </Select>
          </Field>
          <div className="hidden sm:block" />
          <Field label="From" required>
            <Input
              type="datetime-local"
              value={values.from}
              onChange={(e) => setValues({ ...values, from: e.target.value })}
              required
            />
          </Field>
          <Field label="To" required>
            <Input
              type="datetime-local"
              value={values.to}
              onChange={(e) => setValues({ ...values, to: e.target.value })}
              required
            />
          </Field>
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
