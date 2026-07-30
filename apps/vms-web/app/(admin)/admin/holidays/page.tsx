"use client";

/**
 * Holiday calendar (UI-4b over US-04.7.x).
 *
 * A year at a time — the API's list takes the year and answers a bare date-ordered array, so
 * the year selector is the pagination. This is the one master-data resource with a true delete
 * (a wrong date must be removable), and the confirm names the record; the audit trail keeps the
 * only remaining trace.
 *
 * Bulk import is all-or-nothing on the server: a clean batch applies in one transaction, a dirty
 * one answers 409 with a per-row report and writes nothing — so the failure report IS the
 * preview, and the screen renders it as one (fix the lines, resubmit).
 */
import { useCallback, useEffect, useState } from "react";
import { Button } from "@/components/ui/Button";
import { ConfirmDialog, Dialog } from "@/components/ui/Dialog";
import { Field, Input } from "@/components/ui/Field";
import { useToast } from "@/components/ui/Toast";
import { EmptyState, ErrorState, LoadingState } from "@/components/ui/states";
import {
  HolidaysApi,
  errorMessage,
  fieldError,
  type Holiday,
  type HolidayImportReport,
} from "@/lib/admin-api";
import { parseHolidayLines } from "@/lib/holiday-import";
import { PERMISSIONS, hasAny } from "@/lib/permissions";
import { useAuth } from "@/lib/use-auth";

const THIS_YEAR = new Date().getFullYear();
const YEARS = Array.from({ length: 7 }, (_, i) => THIS_YEAR - 1 + i);

export default function HolidaysPage() {
  const auth = useAuth();
  const canEdit = hasAny(auth.me?.permissions, [PERMISSIONS.MASTERDATA_EDIT]);
  const { toast } = useToast();

  const [year, setYear] = useState(THIS_YEAR);
  const [holidays, setHolidays] = useState<Holiday[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>(null);

  const [editing, setEditing] = useState<Holiday | "new" | null>(null);
  const [deleting, setDeleting] = useState<Holiday | null>(null);
  const [deleteBusy, setDeleteBusy] = useState(false);
  const [importing, setImporting] = useState(false);

  const load = useCallback(() => {
    setLoading(true);
    setError(null);
    HolidaysApi.listYear(year)
      .then(setHolidays)
      .catch(setError)
      .finally(() => setLoading(false));
  }, [year]);

  useEffect(load, [load]);

  if (error) {
    return <ErrorState error={error} retry={load} />;
  }

  return (
    <div>
      <h1 className="text-xl font-semibold text-text">Holiday calendar</h1>

      <div className="mt-4 flex flex-wrap items-end justify-between gap-3">
        <div>
          <label htmlFor="holiday-year" className="block text-sm font-medium text-text">
            Year
          </label>
          <select
            id="holiday-year"
            value={year}
            onChange={(e) => setYear(Number(e.target.value))}
            className="mt-1 rounded-md border border-border bg-surface px-3 py-2 text-sm"
          >
            {YEARS.map((y) => (
              <option key={y} value={y}>
                {y}
              </option>
            ))}
          </select>
        </div>
        {canEdit && (
          <div className="flex gap-2">
            <Button variant="secondary" onClick={() => setImporting(true)}>
              Bulk import
            </Button>
            <Button onClick={() => setEditing("new")}>New entry</Button>
          </div>
        )}
      </div>

      <div className="mt-4">
        {loading || holidays === null ? (
          <LoadingState />
        ) : holidays.length === 0 ? (
          <EmptyState
            title={`No calendar entries for ${year}`}
            description="Add entries one by one, or bulk import the year."
            action={canEdit ? <Button onClick={() => setEditing("new")}>New entry</Button> : undefined}
          />
        ) : (
          <div className="overflow-x-auto rounded-lg border border-border">
            <table className="w-full border-collapse bg-surface text-sm">
              <caption className="sr-only">Holiday calendar {year}</caption>
              <thead>
                <tr className="border-b border-border bg-surface-sunken text-left">
                  <th scope="col" className="px-4 py-3 font-medium">Date</th>
                  <th scope="col" className="px-4 py-3 font-medium">Name</th>
                  <th scope="col" className="px-4 py-3 font-medium">Kind</th>
                  {canEdit && (
                    <th scope="col" className="px-4 py-3">
                      <span className="sr-only">Actions</span>
                    </th>
                  )}
                </tr>
              </thead>
              <tbody>
                {holidays.map((h) => (
                  <tr key={h.id} className="border-b border-border last:border-b-0">
                    <td className="px-4 py-3 font-mono">{h.date}</td>
                    <td className="px-4 py-3">{h.name}</td>
                    <td className="px-4 py-3">
                      {h.working ? (
                        <span className="rounded-full bg-warning px-2 py-0.5 text-xs text-warning-contrast">
                          Working exception
                        </span>
                      ) : (
                        <span className="text-text-muted">Holiday</span>
                      )}
                    </td>
                    {canEdit && (
                      <td className="px-4 py-3 text-right">
                        <span className="flex justify-end gap-2">
                          <Button variant="secondary" onClick={() => setEditing(h)}>
                            Edit
                          </Button>
                          <Button variant="danger" onClick={() => setDeleting(h)}>
                            Delete
                          </Button>
                        </span>
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {editing && (
        <HolidayFormDialog
          holiday={editing === "new" ? null : editing}
          defaultYear={year}
          onDone={(saved) => {
            setEditing(null);
            if (saved) {
              toast(saved, "success");
              load();
            }
          }}
        />
      )}

      {deleting && (
        <ConfirmDialog
          open
          title="Delete calendar entry"
          description={
            <>
              Delete <strong>{deleting.date}</strong> — <strong>{deleting.name}</strong>? The audit
              trail keeps the only remaining record of it.
            </>
          }
          confirmLabel="Delete"
          destructive
          busy={deleteBusy}
          onCancel={() => setDeleting(null)}
          onConfirm={async () => {
            setDeleteBusy(true);
            try {
              await HolidaysApi.remove(deleting.id);
              toast(`${deleting.date} deleted.`, "success");
              load();
            } catch (e) {
              toast(errorMessage(e), "error");
            } finally {
              setDeleteBusy(false);
              setDeleting(null);
            }
          }}
        />
      )}

      {importing && (
        <ImportDialog
          onDone={(applied) => {
            setImporting(false);
            if (applied !== null) {
              toast(`${applied} calendar entries imported.`, "success");
              load();
            }
          }}
        />
      )}
    </div>
  );
}

function HolidayFormDialog({
  holiday,
  defaultYear,
  onDone,
}: {
  holiday: Holiday | null;
  defaultYear: number;
  onDone: (savedMessage: string | null) => void;
}) {
  const [values, setValues] = useState({
    date: holiday?.date ?? `${defaultYear}-`,
    name: holiday?.name ?? "",
    working: holiday?.working ?? false,
  });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function onSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setErrors({});
    setFormError(null);
    setBusy(true);
    try {
      if (holiday) {
        await HolidaysApi.update(holiday.id, values);
        onDone(`${values.date} updated.`);
      } else {
        await HolidaysApi.create(values);
        onDone(`${values.date} added.`);
      }
    } catch (e) {
      const fe = fieldError(e);
      if (fe && ["date", "name"].includes(fe.field)) {
        setErrors({ [fe.field]: fe.message });
      } else {
        setFormError(errorMessage(e));
      }
      setBusy(false);
    }
  }

  return (
    <Dialog open onClose={() => onDone(null)} title={holiday ? `Edit ${holiday.date}` : "New calendar entry"}>
      <form onSubmit={onSubmit} noValidate>
        {formError && (
          <p role="alert" className="mt-3 rounded-md bg-danger-surface p-3 text-sm text-danger">
            {formError}
          </p>
        )}
        <div className="mt-4 space-y-4">
          <Field label="Date" error={errors["date"]} required>
            <Input
              type="date"
              value={values.date}
              onChange={(e) => setValues({ ...values, date: e.target.value })}
              required
              autoFocus={!holiday}
            />
          </Field>
          <Field label="Name" error={errors["name"]} required>
            <Input
              value={values.name}
              onChange={(e) => setValues({ ...values, name: e.target.value })}
              required
              maxLength={200}
            />
          </Field>
          <div className="flex items-start gap-2">
            <input
              id="holiday-working"
              type="checkbox"
              checked={values.working}
              onChange={(e) => setValues({ ...values, working: e.target.checked })}
              className="mt-1"
            />
            <label htmlFor="holiday-working" className="text-sm text-text">
              Working-day exception
              <span className="block text-text-muted">
                A compensating working day (e.g. a make-up Saturday), not a day off.
              </span>
            </label>
          </div>
        </div>
        <div className="mt-6 flex justify-end gap-3">
          <Button variant="secondary" onClick={() => onDone(null)} disabled={busy}>
            Cancel
          </Button>
          <Button type="submit" busy={busy}>
            {holiday ? "Save" : "Add"}
          </Button>
        </div>
      </form>
    </Dialog>
  );
}

function ImportDialog({ onDone }: { onDone: (applied: number | null) => void }) {
  const [text, setText] = useState("");
  const [parseErrors, setParseErrors] = useState<string[]>([]);
  const [report, setReport] = useState<HolidayImportReport | null>(null);
  const [busy, setBusy] = useState(false);

  async function onSubmit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setReport(null);
    const parsed = parseHolidayLines(text);
    setParseErrors(parsed.errors);
    if (parsed.errors.length > 0) {
      return;
    }
    setBusy(true);
    try {
      const result = await HolidaysApi.importAll(parsed.rows);
      onDone(result.applied);
    } catch (e) {
      // The 409 carries the same report shape with per-row reasons; nothing was written.
      const problem = (e as { problem?: unknown })?.problem;
      if (problem && typeof problem === "object" && "rows" in problem) {
        setReport(problem as HolidayImportReport);
      } else {
        setParseErrors([errorMessage(e)]);
      }
      setBusy(false);
    }
  }

  return (
    <Dialog open onClose={() => onDone(null)} title="Bulk import calendar entries">
      <form onSubmit={onSubmit} noValidate>
        <p className="mt-2 text-sm text-text-muted">
          One entry per line: <code className="font-mono">YYYY-MM-DD, Name</code>, with an optional
          trailing <code className="font-mono">working</code> for a working-day exception. The whole
          batch applies together — if any line is refused, nothing is written and the report below
          says why.
        </p>
        <textarea
          aria-label="Entries to import"
          value={text}
          onChange={(e) => setText(e.target.value)}
          rows={8}
          className="mt-3 w-full rounded-md border border-border bg-surface px-3 py-2 font-mono text-sm"
          placeholder={"2027-01-01, New Year\n2027-03-26, Independence Day\n2027-04-03, Make-up day, working"}
        />

        {parseErrors.length > 0 && (
          <ul role="alert" className="mt-3 space-y-1 rounded-md bg-danger-surface p-3 text-sm text-danger">
            {parseErrors.map((e) => (
              <li key={e}>{e}</li>
            ))}
          </ul>
        )}

        {report && !report.accepted && (
          <div role="alert" className="mt-3 rounded-md border border-border p-3 text-sm">
            <p className="font-medium text-danger">Nothing was imported:</p>
            <ul className="mt-2 space-y-1">
              {report.rows.map((r) => (
                <li key={r.date + (r.reason ?? "")}>
                  <code className="font-mono">{r.date}</code>{" "}
                  {r.status === "applied" ? (
                    <span className="text-text-muted">ok</span>
                  ) : (
                    <span className={r.status === "rejected" ? "text-danger" : "text-text-muted"}>
                      {r.reason ?? r.status}
                    </span>
                  )}
                </li>
              ))}
            </ul>
          </div>
        )}

        <div className="mt-6 flex justify-end gap-3">
          <Button variant="secondary" onClick={() => onDone(null)} disabled={busy}>
            Cancel
          </Button>
          <Button type="submit" busy={busy}>
            Import
          </Button>
        </div>
      </form>
    </Dialog>
  );
}
